package io.github.docmorphic.cmuxapp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey

/** Real encrypted fixture documents and real Keystore keys, on the owned emulator only. */
class NativeCredentialReadRuntimeTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var context: IsolatedContext
    private lateinit var alias: String
    private val records = JSONObject()
    private val login = "fixture-read-login"
    private val team = NativeTeamScope(login, "fixture-user", "fixture-team", 1)
    private lateinit var mac: NativeCredentialStore.PairedMac
    private lateinit var phone: PhonePushIdentity
    private lateinit var peer: PhonePushPeer
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key() = keyStore().getKey(alias, null) as SecretKey
    private fun store(provider: (() -> SecretKey)? = null) = NativeCredentialStore.withKeyProvider(context, "fixture", alias, provider)
    private val preferences get() = context.getSharedPreferences("fixture", Context.MODE_PRIVATE)
    private fun cipherValue() = preferences.getString("state", null)!!
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        context = IsolatedContext(target); alias = "cmux_fixture_read_"+UUID.randomUUID()
        mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(
            IrohV2Computer("record", "a".repeat(64), "fixture-mac", "stable", "Fixture Mac", emptyList()), team),
            "fixture-mac", "Fixture Mac", "stable"), team)
        store().update { root ->
            root.put("task_session", login).put("access_token", "fixture-access").put("refresh_token", "fixture-refresh")
                .put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))
            NativeAccountProfileCache.writeVerifiedUser(root, login, "fixture-environment",
                NativeSignInUser(team.userId, "Fixture Person", "fixture@example.test", team.teamId))
            val keys = PhonePushKeyState(root); phone = keys.identity(login)
            peer = PhonePushPeer(PhonePushTuple(team.userId, null, context.packageName, phone.installationID,
                "fixture-physical-mac", "stable", "fixture.build"), PhonePushIdentity.generate().descriptor())
            keys.pin(team, mac.origin, peer)
        }
    }
    @After fun cleanup() {
        val result = File(target.getExternalFilesDir(null), "credential-read").apply { mkdirs() }
        File(result, records.optString("case", "unknown")+".json").writeText(records.toString(2))
        context.close(); keyStore().deleteEntry(alias)
    }
    private fun assertRecovered(value: NativeCredentialStore) {
        val root = checkNotNull(value.load())
        assertEquals(NativeCredentialReadStatus.AVAILABLE, value.readability.value)
        assertEquals("fixture-access", root.getString("access_token")); assertEquals("fixture-refresh", root.getString("refresh_token"))
        assertEquals(login, value.taskSession()); assertEquals(listOf(mac), value.pairedMacs())
        assertEquals("Fixture Person", NativeAccountProfileCache(value::load, value::update).read(login,"fixture-environment")!!.displayName)
        assertArrayEquals(phone.privateKey, PhonePushKeyState(root).existingIdentity(login)!!.privateKey)
        assertEquals(peer, PhonePushKeyState(root).peer(team, mac.origin))
    }
    private fun unreadable(action: () -> Unit): NativeCredentialReadException {
        try { action(); fail("Unavailable data must not be used as an empty document") }
        catch (failure: NativeCredentialReadException) {
            assertNull("Parser/crypto causes must not leak plaintext", failure.cause)
            assertFalse(failure.message!!.contains("fixture-refresh")); return failure
        }
        error("Expected sanitized read failure")
    }
    @Test fun temporaryKeyFailurePreservesEncryptedAccountPairingAndPushIdentityAcrossReconstruction() {
        records.put("case","temporary-key")
        val encoded = cipherValue(); var unavailable = true
        val reader = store { if (unavailable) throw KeyStoreException("Fixture provider unavailable") else key() }
        val revision = reader.revisions.value
        assertNull(reader.load()); assertEquals(NativeCredentialReadStatus.UNAVAILABLE, reader.readability.value)
        assertEquals(encoded,cipherValue()); assertEquals(revision,reader.revisions.value)
        unavailable = false; assertRecovered(reader); assertRecovered(store())
        assertEquals(encoded,cipherValue()); assertEquals(revision,reader.revisions.value)
        records.put("ciphertextUnchanged",true).put("accountPairingAndPushIdentityRecovered",true).put("readDoesNotPublishMutation",true)
    }
    @Test fun unavailableReadBlocksAllMutationBaselinesAndConditionalAccountClear() {
        records.put("case","blocked-mutations")
        val encoded = cipherValue(); val reader = store { throw KeyStoreException("Fixture unavailable") }
        val revision=reader.revisions.value;var transforms=0
        unreadable { reader.update { transforms++;it.remove("pairings") } }
        unreadable { reader.updateFromMaintenance { transforms++;it.remove(PhonePushKeyState.KEY) } }
        unreadable { reader.requireReadable() }
        unreadable { NativeAccount(reader).signOut(login) }
        assertEquals(0,transforms);assertEquals(encoded,cipherValue());assertEquals(revision,reader.revisions.value)
        assertRecovered(store())
        records.put("transformCalls",transforms).put("ciphertextUnchanged",true).put("conditionalClearBlocked",true)
    }
    @Test fun missingReadKeyDoesNotGenerateAReplacementOrOverwriteTheDocument() {
        records.put("case","missing-key")
        val encoded=cipherValue();keyStore().deleteEntry(alias);val reader=store()
        repeat(3) { assertNull(reader.load());assertFalse(keyStore().containsAlias(alias));assertEquals(encoded,cipherValue()) }
        var transforms=0;unreadable { reader.update { transforms++ } }
        assertEquals(0,transforms);assertFalse(keyStore().containsAlias(alias));assertEquals(encoded,cipherValue())
        records.put("readKeyRegenerations",0).put("ciphertextUnchanged",true).put("scope","Only a uniquely named fixture key was removed; the application key was untouched.")
    }
    @Test fun corruptCipherMalformedPlaintextAndWrongPreferenceTypeArePreservedWithoutLeakingTheirContents() {
        records.put("case","malformed-data")
        val good=cipherValue();val bytes=Base64.decode(good,Base64.NO_WRAP);bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
        val crypto=Cipher.getInstance("AES/GCM/NoPadding");crypto.init(Cipher.ENCRYPT_MODE,key())
        val malformed=Base64.encodeToString(crypto.iv+crypto.doFinal("fixture-refresh is not JSON".toByteArray()),Base64.NO_WRAP)
        val variants=listOf<Any>(Base64.encodeToString(bytes,Base64.NO_WRAP),"invalid encoding",malformed,7)
        val reader=store();val revision=reader.revisions.value
        for (variant in variants) {
            val edit=preferences.edit();if(variant is Int)edit.putInt("state",variant) else edit.putString("state",variant as String);assertTrue(edit.commit())
            assertNull(reader.load());assertEquals(NativeCredentialReadStatus.UNAVAILABLE,reader.readability.value)
            var ran=false;unreadable { reader.update { ran=true } };assertFalse(ran)
            assertEquals(variant,preferences.all["state"]);assertEquals(revision,reader.revisions.value)
            assertTrue(keyStore().containsAlias(alias))
        }
        assertTrue(preferences.edit().putString("state",good).commit());assertRecovered(reader)
        records.put("preservedVariants",variants.size).put("parserCauseExposed",false).put("recoverySucceeded",true)
    }
    @Test fun failedEncryptionAfterAReadableSnapshotCannotCommitTheMutatedProjection() {
        records.put("case","write-key-failure")
        val good=cipherValue();var reads=0
        val reader=store { if(++reads>1)throw KeyStoreException("Fixture encryption unavailable") else key() }
        val revision=reader.revisions.value;var ran=false
        try { reader.update { ran=true;it.put("refresh_token","must-not-commit").remove("pairings") };fail("Write must fail") }
        catch (_:KeyStoreException) { }
        assertTrue(ran);assertEquals(good,cipherValue());assertEquals(revision,reader.revisions.value);assertRecovered(store())
        records.put("transformRan",true).put("failedWriteCommitted",false).put("originalDataRecovered",true)
    }
    @Test fun readAvailabilityIsSharedAndOnlyExplicitResetRemovesAnUnreadableFixture() {
        records.put("case","shared-availability")
        val reader=store();val other=store { throw KeyStoreException("Fixture unavailable") };val revision=reader.revisions.value
        assertNull(other.load());assertEquals(NativeCredentialReadStatus.UNAVAILABLE,reader.readability.value)
        assertEquals(revision,reader.revisions.value);assertRecovered(reader)
        assertEquals(NativeCredentialReadStatus.AVAILABLE,other.readability.value)
        other.clear();assertFalse(preferences.contains("state"));assertEquals(NativeCredentialReadStatus.EMPTY,reader.readability.value)
        assertEquals(revision+1,reader.revisions.value);assertNull(reader.load())
        records.put("availabilityShared",true).put("readMutationCount",0).put("explicitResetMutationCount",1)
    }
    private class IsolatedContext(base: Context):ContextWrapper(base),AutoCloseable {
        private val namespace="credential-read-"+UUID.randomUUID();private val names=mutableSetOf<String>()
        override fun getApplicationContext():Context=this
        override fun getApplicationInfo()=ApplicationInfo(super.getApplicationInfo()).apply { dataDir+="/"+namespace }
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences { names+=name;return super.getSharedPreferences("$namespace.$name",mode) }
        override fun close() { names.forEach { super.deleteSharedPreferences("$namespace.$it") } }
    }
}

package io.github.docmorphic.cmuxapp

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Collections
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class SshKeyKind { GENERATED, IMPORTED }
internal data class SshKeyRecord(val id: UUID, val label: String, val kind: SshKeyKind,
    val publicKey: SshHostKey, val requiresBiometrics: Boolean, val createdAtMillis: Long)

/** A one-use Keystore operation. A biometric UI must authorize this exact Signature. */
internal class SshPreparedSignature internal constructor(val signature: Signature, private val finish: (ByteArray) -> ByteArray) {
    private var used = false
    @Synchronized fun sign(data: ByteArray): ByteArray {
        check(!used) { "SSH signature operation is already consumed" }
        used = true
        return finish(data)
    }
}

internal class SshImportedKeyLease internal constructor(val publicKey: SshHostKey,
    private val signBlock: (ByteArray) -> ByteArray, private val closeBlock: () -> Unit) : AutoCloseable {
    fun sign(data: ByteArray) = signBlock(data)
    override fun close() = closeBlock()
}

/** Phone-local SSH secrets. Use get() in production: a single main-process owner.
 * Per-key AES aliases make deleted imported ciphertext unusable even if restored.
 * Test constructors must use their own directory/alias namespace. */
internal class SshKeyVault internal constructor(
    private val directory: File,
    private val unlocked: () -> Boolean,
    private val removeHostReferences: (UUID) -> Unit,
    private val beforeWrite: () -> Unit = {},
) {
    private data class Entry(val record: SshKeyRecord, val sealed: String? = null, val deleted: Boolean = false)
    private val file = AtomicFile(File(directory, "keys-v1.json"))
    private val prefix = "cmux.ssh.v1." + MessageDigest.getInstance("SHA-256")
        .digest(directory.canonicalPath.toByteArray()).take(12).joinToString("") { "%02x".format(it) } + "."
    private val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private var entries = read()
    private val leases = mutableMapOf<UUID, MutableSet<KeyPair>>()
    private val mutable = MutableStateFlow(records())
    val state = mutable.asStateFlow()

    init {
        cleanupDeleted()
        // An interrupted creation can leave a key without metadata. Only this
        // vault's aliases are eligible; malformed metadata never reaches here.
        val expected = entries.keys.map(::alias).toSet()
        keystore.aliases().toList().filter { it.startsWith(prefix) && it !in expected }.forEach(keystore::deleteEntry)
    }

    @Synchronized fun generate(label: String, requiresBiometrics: Boolean = false): SshKeyRecord {
        checkUnlocked(); validateLabel(label)
        val id = UUID.randomUUID()
        try {
            val builder = KeyGenParameterSpec.Builder(alias(id), KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(requiresBiometrics)
            if (requiresBiometrics) {
                builder.setInvalidatedByBiometricEnrollment(true)
                if (Build.VERSION.SDK_INT >= 30) builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                else @Suppress("DEPRECATION") builder.setUserAuthenticationValidityDurationSeconds(-1)
            }
            // Older Android versions have documented bugs with this flag.
            if (Build.VERSION.SDK_INT >= 35) builder.setUnlockedDeviceRequired(true)
            val pair = KeyPairGenerator.getInstance("EC", "AndroidKeyStore").run {
                initialize(builder.build()); generateKeyPair()
            }
            check(pair.private.encoded == null)
            val record = SshKeyRecord(id, label.trim(), SshKeyKind.GENERATED,
                SshKeyCrypto.publicKey(pair.public as ECPublicKey), requiresBiometrics, System.currentTimeMillis())
            persist(entries + (id to Entry(record)))
            return record
        } catch (failure: Throwable) { keystore.deleteEntry(alias(id)); throw failure }
    }

    /** Copies caller buffers; the caller still owns clearing its UI/import buffers. */
    @Synchronized fun import(label: String, privateKey: ByteArray, passphrase: ByteArray?): SshKeyRecord {
        checkUnlocked(); validateLabel(label)
        require(privateKey.size <= SshKeyCrypto.MAX_PRIVATE_BYTES && (passphrase?.size ?: 0) <= 65536)
        val ownedKey = privateKey.copyOf()
        val ownedPassphrase = passphrase?.copyOf()
        try { return importOwned(label, ownedKey, ownedPassphrase) }
        finally { ownedKey.fill(0); ownedPassphrase?.fill(0) }
    }

    private fun importOwned(label: String, privateKey: ByteArray, passphrase: ByteArray?): SshKeyRecord {
        val parsed = SshKeyCrypto.load(privateKey, passphrase)
        val public = try { SshKeyCrypto.publicKey(parsed) } finally { parsed.dispose() }
        val id = UUID.randomUUID()
        val record = SshKeyRecord(id, label.trim(), SshKeyKind.IMPORTED, public, false, System.currentTimeMillis())
        val plain = SshKeyCrypto.encodeSecret(privateKey, passphrase)
        try {
            val builder = KeyGenParameterSpec.Builder(alias(id), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
            if (Build.VERSION.SDK_INT >= 35) builder.setUnlockedDeviceRequired(true)
            val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(builder.build()); generateKey()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad(record))
            val sealed = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain))
            persist(entries + (id to Entry(record, sealed)))
            return record
        } catch (failure: Throwable) { keystore.deleteEntry(alias(id)); throw failure }
        finally { plain.fill(0) }
    }

    @Synchronized fun prepareSignature(id: UUID): SshPreparedSignature {
        checkUnlocked()
        val entry = active(id); check(entry.record.kind == SshKeyKind.GENERATED)
        check(SshKeyCrypto.publicKey(keystore.getCertificate(alias(id)).publicKey as ECPublicKey) == entry.record.publicKey) {
            "SSH key metadata does not match its Keystore identity"
        }
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keystore.getKey(alias(id), null) as PrivateKey)
        return SshPreparedSignature(signer) { data -> synchronized(this) {
            checkUnlocked(); active(id)
            signer.update(data)
            SshKeyCrypto.ecdsaSignature(signer.sign())
        } }
    }

    @Synchronized fun openImportedKey(id: UUID): SshImportedKeyLease {
        checkUnlocked()
        val entry = active(id); check(entry.record.kind == SshKeyKind.IMPORTED)
        val sealed = Base64.getDecoder().decode(checkNotNull(entry.sealed))
        require(sealed.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystore.getKey(alias(id), null) as SecretKey,
            GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        cipher.updateAAD(aad(entry.record))
        val plain = cipher.doFinal(sealed, 12, sealed.size - 12)
        val parsed = try { SshKeyCrypto.withDecodedSecret(plain, SshKeyCrypto::load) } finally { plain.fill(0) }
        try { check(SshKeyCrypto.publicKey(parsed) == entry.record.publicKey) }
        catch (failure: Throwable) { parsed.dispose(); throw failure }
        leases.getOrPut(id) { mutableSetOf() }.add(parsed)
        return SshImportedKeyLease(entry.record.publicKey, signBlock = { data -> synchronized(this) {
            checkUnlocked(); active(id)
            check(leases[id]?.contains(parsed) == true) { "SSH key lease is closed" }
            checkNotNull(parsed.getSignature(data))
        } }, closeBlock = { synchronized(this) {
            if (leases[id]?.remove(parsed) == true) parsed.dispose()
            if (leases[id]?.isEmpty() == true) leases.remove(id)
        } })
    }

    @Synchronized fun rename(id: UUID, label: String) {
        validateLabel(label)
        val entry = active(id)
        persist(entries + (id to entry.copy(record = entry.record.copy(label = label.trim()))))
    }

    @Synchronized fun delete(id: UUID) {
        val entry = entries[id] ?: return
        if (!entry.deleted) persist(entries + (id to entry.copy(deleted = true)))
        cleanupDeleted()
    }

    private fun cleanupDeleted() {
        for ((id, entry) in entries.toMap()) if (entry.deleted) {
            leases.remove(id)?.forEach(KeyPair::dispose)
            removeHostReferences(id)
            keystore.deleteEntry(alias(id))
            persist(entries - id)
        }
    }
    private fun active(id: UUID): Entry = checkNotNull(entries[id]?.takeUnless { it.deleted }) {
        "SSH key is unavailable or deleted"
    }.also { check(keystore.containsAlias(alias(id))) { "SSH key material is unavailable" } }
    private fun checkUnlocked() { check(unlocked()) { "Unlock this device to use its SSH keys" } }
    private fun alias(id: UUID) = prefix + id
    private fun aad(record: SshKeyRecord) = "cmux-ssh-import-v1\u0000${record.id}\u0000${record.publicKey.openSsh}".toByteArray()
    private fun validateLabel(label: String) { require(label.trim().isNotEmpty() && label.length <= 256 && label.none { it.isISOControl() }) }
    private fun records() = Collections.unmodifiableList(entries.values.filterNot { it.deleted }.map { it.record })

    private fun persist(next: Map<UUID, Entry>) {
        val json = JSONObject().put("version", 1).put("keys", JSONArray().apply {
            next.values.forEach { entry -> val key = entry.record; put(JSONObject()
                .put("id", key.id.toString()).put("label", key.label).put("kind", key.kind.name)
                .put("publicKey", key.publicKey.openSsh).put("biometrics", key.requiresBiometrics)
                .put("created", key.createdAtMillis).put("sealed", entry.sealed ?: JSONObject.NULL)
                .put("deleted", entry.deleted)) }
        }).toString().toByteArray()
        require(json.size <= MAX_BYTES)
        beforeWrite()
        check(directory.isDirectory || directory.mkdirs())
        val output = file.startWrite()
        try {
            output.write(json); output.fd.sync(); file.finishWrite(output)
            // AtomicFile logs some rename failures rather than throwing them.
            check(file.baseFile.readBytes().contentEquals(json)) { "SSH key metadata commit could not be verified" }
        } catch (failure: Throwable) { file.failWrite(output); throw failure }
        entries = next.toMap()
        mutable.value = records()
    }
    private fun read(): Map<UUID, Entry> = try {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) emptyMap() else {
            val text = file.openRead().use { require(it.channel.size() <= MAX_BYTES); it.readBytes().decodeToString(throwOnInvalidSequence = true) }
            val json = JSONObject(text); require(json.get("version") == 1)
            val rows = json.getJSONArray("keys")
            buildMap { for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val idText = row.get("id") as String
                val id = UUID.fromString(idText); require(id.toString() == idText && id !in this)
                val kind = SshKeyKind.valueOf(row.get("kind") as String)
                val record = SshKeyRecord(id, row.get("label") as String, kind,
                    SshHostKey.parse(row.get("publicKey") as String), row.get("biometrics") as Boolean,
                    row.getLong("created"))
                validateLabel(record.label)
                require(!record.requiresBiometrics || kind == SshKeyKind.GENERATED)
                require(kind != SshKeyKind.GENERATED || record.publicKey.algorithm == "ecdsa-sha2-nistp256")
                val sealed = if (row.isNull("sealed")) null else row.get("sealed") as String
                require((kind == SshKeyKind.IMPORTED) == (sealed != null))
                put(id, Entry(record, sealed, row.get("deleted") as Boolean))
            } }
        }
    } catch (failure: Exception) { throw IOException("Could not read saved SSH keys", failure) }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
        private val instances = mutableMapOf<String, SshKeyVault>()
        @Synchronized fun get(context: Context): SshKeyVault {
            val app = context.applicationContext
            val directory = File(app.noBackupFilesDir, "ssh/keys")
            return instances.getOrPut(directory.absolutePath) {
                SshKeyVault(directory, { !app.getSystemService(KeyguardManager::class.java).isDeviceLocked },
                    { AndroidSshHostStore.get(app).removeKeyReferences(it) })
            }
        }
    }
}

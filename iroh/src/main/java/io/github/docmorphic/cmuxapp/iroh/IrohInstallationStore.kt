package io.github.docmorphic.cmuxapp.iroh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import computer.iroh.SecretKey as NativeSecretKey
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Exact authorization scope. Installation ID is supplied by this app's private store. */
data class IrohAccountScope(
    val environment: String,
    val projectId: String,
    val teamId: String,
    val userId: String,
    val appNamespace: String,
    val buildTag: String
) {
    init {
        listOf(environment, projectId, teamId, userId).forEach { validate(it, 128) }
        validate(appNamespace, 255); validate(buildTag, 64)
    }
    internal fun json(deviceId: String) = JSONObject().put("environment", environment)
        .put("projectId", projectId).put("teamId", teamId).put("userId", userId)
        .put("deviceId", deviceId).put("appNamespace", appNamespace).put("buildTag", buildTag)

    companion object {
        private fun validate(value: String, limit: Int) {
            require(value.isNotBlank() && value.length <= limit) { "Invalid Iroh identity field" }
            // Scope bytes must be injective, including for malformed UTF-16 supplied by callers.
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        }
    }
}

/** Owns one native Ed25519 handle. Endpoint and proof signing use the same seed. */
class IrohInstallationKey internal constructor(
    val scope: IrohAccountScope,
    val deviceId: String,
    private val native: NativeSecretKey
) : AutoCloseable {
    private var closed = false
    val endpointId: String = native.`public`().use { key -> key.toBytes().hex() }
    fun identity(): JSONObject = scope.json(deviceId)

    @Synchronized fun sign(canonicalProof: ByteArray): ByteArray {
        check(!closed) { "Iroh identity closed" }
        return native.sign(canonicalProof).use { it.toBytes() }
    }

    /** Caller must clear this temporary copy after Endpoint.bind has consumed it. */
    @Synchronized internal fun seedForEndpoint(): ByteArray {
        check(!closed) { "Iroh identity closed" }
        return native.toBytes()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        native.close()
    }
}

/**
 * Dedicated V2 identities, encrypted with a nonexportable Android Keystore AES key.
 * No backup, account-token import, fallback scope, or implicit replacement of unreadable records.
 * The app and its connection service use one process; the lock coordinates all store instances.
 */
class IrohInstallationStore private constructor(context: Context, private val storageName: String) {
    constructor(context: Context) : this(context, "installation")

    private val application = context.applicationContext
    private val namespace = application.packageName
    private val directory = File(application.noBackupFilesDir, "cmux-iroh-v2/$storageName")
    private val alias = "$namespace.cmux-iroh-v2.$storageName"

    init {
        require(storageName.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        IrohRuntime.initialize(application)
    }

    fun loadOrCreate(scope: IrohAccountScope): IrohInstallationKey = synchronized(lock) {
        require(scope.appNamespace == namespace) { "Iroh identity must use this installed app's namespace" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create Iroh identity directory" }
        val deviceId = deviceId()
        val aad = scopeBytes(scope, deviceId)
        val file = AtomicFile(File(directory, sha256(aad) + ".seed"))
        var seed: ByteArray? = null
        try {
            seed = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                val envelope = file.openRead().use {
                    if (it.channel.size() != 61L) throw IOException("Invalid stored Iroh identity length")
                    it.readBytes()
                }
                if (envelope.size != 1 + 12 + 32 + 16 || envelope[0] != 1.toByte())
                    throw IOException("Invalid stored Iroh identity")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, wrappingKey(create = false), GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
                cipher.updateAAD(aad)
                cipher.doFinal(envelope.copyOfRange(13, envelope.size))
            } else {
                NativeSecretKey.generate().use { it.toBytes() }.also { candidate ->
                    try {
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(create = true))
                        cipher.updateAAD(aad)
                        write(file, byteArrayOf(1) + cipher.iv + cipher.doFinal(candidate))
                    } catch (error: Throwable) {
                        candidate.fill(0)
                        throw error
                    }
                }
            }
            IrohInstallationKey(scope, deviceId, NativeSecretKey.fromBytes(seed))
        } catch (error: Exception) {
            throw IOException("Could not load this installation's Iroh identity; no replacement was created", error)
        } finally { seed?.fill(0) }
    }

    /** Cloud enrollment uses this registry ID. Reading never mints an ID or opens a scoped signing key. */
    fun storedDeviceId(): String? = synchronized(lock) { readDeviceId() }

    /** Shared registry identity for Cloud-first startup; no scoped signing key is opened.
     * The same corruption/lost-ID checks as loadOrCreate remain authoritative.
     */
    fun loadOrCreateDeviceId(): String = synchronized(lock) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create Iroh identity directory" }
        deviceId()
    }

    private fun deviceId(): String = readDeviceId() ?: UUID.randomUUID().toString().also {
        write(AtomicFile(File(directory, "installation-id")), it.toByteArray(Charsets.US_ASCII))
    }

    private fun readDeviceId(): String? {
        if (!directory.exists()) return null
        val entries = directory.listFiles() ?: throw IOException("Iroh identity directory is unreadable")
        val file = AtomicFile(File(directory, "installation-id"))
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val stored = file.openRead().use {
                if (it.channel.size() != 36L) throw IOException("Invalid Iroh installation ID length")
                it.readBytes()
            }
            if (stored.size != 36) throw IOException("Invalid Iroh installation ID")
            val value = stored.toString(Charsets.US_ASCII)
            if (runCatching { UUID.fromString(value).toString() }.getOrNull() != value)
                throw IOException("Invalid Iroh installation ID")
            return value
        }
        // Never replace a lost installation ID while scoped identities still exist.
        if (entries.any { it.name.endsWith(".seed") || it.name.endsWith(".seed.bak") })
            throw IOException("Iroh installation ID missing for existing keys")
        return null
    }

    private fun wrappingKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw IOException("Iroh wrapping key unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun write(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }

    companion object {
        private val lock = Any()
        internal fun isolatedTestStore(context: Context, name: String) = IrohInstallationStore(context, name)

        // Private storage encoding, not Worker canonical JSON. Fixed ordered, length-delimited UTF-8
        // prevents delimiter collisions and includes every wire identity field as authenticated data.
        internal fun scopeBytes(scope: IrohAccountScope, deviceId: String): ByteArray = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF("cmux-iroh-android-key-v1")
                for (value in listOf(scope.environment, scope.projectId, scope.teamId, scope.userId,
                    deviceId, scope.appNamespace, scope.buildTag)) {
                    val encoded = value.toByteArray(Charsets.UTF_8)
                    output.writeInt(encoded.size); output.write(encoded)
                }
            }
            bytes.toByteArray()
        }
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

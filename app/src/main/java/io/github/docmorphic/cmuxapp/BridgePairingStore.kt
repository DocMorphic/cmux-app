package io.github.docmorphic.cmuxapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the personal helper credential on the phone across app restarts.
 * Android Keystore owns the AES key; only the encrypted value enters preferences.
 * Android backup is disabled in the manifest, so a copied preference cannot be
 * restored onto a phone without its matching key.
 */
class BridgePairingStore(context: Context) {
    private val preferences = context.getSharedPreferences("paired_mac", Context.MODE_PRIVATE)

    fun load(): BridgePairing? {
        val encoded = preferences.getString(PREF_KEY, null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > IV_BYTES)
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val ciphertext = bytes.copyOfRange(IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            BridgePairing.parse(
                "cmux-app://pair?host=${json.getString("host")}&port=${json.getInt("port")}&token=${json.getString("token")}"
            )
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun save(pairing: BridgePairing) {
        val json = JSONObject()
            .put("host", pairing.host)
            .put("port", pairing.port)
            .put("token", pairing.token)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        val value = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(PREF_KEY, value).commit()) { "Could not save pairing" }
    }

    fun clear() {
        preferences.edit().remove(PREF_KEY).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREF_KEY = "helper_pairing"
        const val KEY_ALIAS = "cmux_app_helper_pairing_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

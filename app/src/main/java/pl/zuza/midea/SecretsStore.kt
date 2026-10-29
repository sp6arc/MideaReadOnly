package pl.zuza.midea

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
 * Opt-in credential persistence, encrypted by Android Keystore AES/GCM.
 * No tokens are written to disk. Data is local, no Android Backup (manifest).
 * Credentials are re-authenticated over HTTPS at app startup.
 */
internal data class SavedLogin(val account: String, val password: String, val pumpId: Long?)

internal class SecretsStore(context: Context) {
    private val prefs = context.getSharedPreferences("private_sign_in_v1", Context.MODE_PRIVATE)
    private val alias = "midea_readonly_credentials_v1"
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(): SecretKey {
        val keys = keystore()
        val old = keys.getKey(alias, null)
        if (old is SecretKey) return old
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
    fun save(credentials: SavedLogin) {
        require(credentials.account.isNotBlank() && credentials.password.isNotBlank())
        val data = JSONObject().put("account", credentials.account)
            .put("password", credentials.password)
            .put("pumpId", credentials.pumpId ?: JSONObject.NULL)
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(data)
        // Save IV and ciphertext together in a single atomic pref entry.
        val payload = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString("payload", payload).commit()) { "Nie zapisano ustawień" }
        data.fill(0)
    }
    fun load(): SavedLogin? {
        val encoded = prefs.getString("payload", null) ?: return null
        return try {
            val all = Base64.decode(encoded, Base64.NO_WRAP)
            require(all.size >= 29)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
            val bytes = cipher.doFinal(all.copyOfRange(12, all.size))
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            bytes.fill(0)
            SavedLogin(json.getString("account"), json.getString("password"),
                if (json.isNull("pumpId")) null else json.getLong("pumpId"))
        } catch (_: Exception) {
            // Keystore data may become inaccessible after key invalidation or restore.
            prefs.edit().remove("payload").commit()
            null
        }
    }
    fun forget() {
        prefs.edit().remove("payload").commit()
        try { keystore().deleteEntry(alias) } catch (_: Exception) { /* no material left in prefs */ }
    }
}

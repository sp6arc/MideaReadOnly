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

/** Device-bound encrypted credentials. Android app backup is disabled. */
internal data class PumpSettings(
    val host:String,
    val port:String,
    val deviceId:String,
    val model:String,
    val protocol:String,
    val cloud:String,
    val username:String,
    val password:String
) {
    fun json():JSONObject = JSONObject()
        .put("host",host).put("port",port).put("deviceId",deviceId)
        .put("model",model).put("protocol",protocol).put("cloud",cloud)
        .put("username",username).put("password",password)

    companion object {
        fun parse(json:JSONObject) = PumpSettings(
            json.getString("host"),json.getString("port"),json.getString("deviceId"),
            json.getString("model"),json.getString("protocol"),json.getString("cloud"),
            json.getString("username"),json.getString("password")
        )
    }
}

internal object SecurePumpSettings {
    private const val ALIAS = "midearemote_v241_account_key"
    private const val PREF = "midearemote_secure_connection"
    private const val KEY = "aes_gcm_configuration_v1"

    private fun preferences(context:Context) =
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE)

    private fun secretKey():SecretKey {
        val keystore=KeyStore.getInstance("AndroidKeyStore")
        keystore.load(null)
        val old=keystore.getKey(ALIAS,null)
        if(old is SecretKey) return old
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build())
        return generator.generateKey()
    }

    fun save(context:Context,settings:PumpSettings) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,secretKey())
        val encrypted=cipher.doFinal(settings.json().toString().toByteArray(Charsets.UTF_8))
        val packed=cipher.iv + encrypted
        check(preferences(context).edit()
            .putString(KEY,Base64.encodeToString(packed,Base64.NO_WRAP)).commit()) {
            "Nie udało się zapisać konfiguracji"
        }
    }

    fun load(context:Context):PumpSettings? {
        val saved=preferences(context).getString(KEY,null)?:return null
        val packed=Base64.decode(saved,Base64.NO_WRAP)
        require(packed.size>28) {"Niepełne zaszyfrowane dane"}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,secretKey(),GCMParameterSpec(128,packed.copyOfRange(0,12)))
        val json=JSONObject(String(cipher.doFinal(packed.copyOfRange(12,packed.size)),Charsets.UTF_8))
        return PumpSettings.parse(json)
    }

    fun enabled(context:Context):Boolean=preferences(context).getBoolean("monitor_enabled",false)

    fun setEnabled(context:Context,enabled:Boolean) {
        preferences(context).edit().putBoolean("monitor_enabled",enabled).apply()
    }

    fun clear(context:Context) {
        preferences(context).edit().remove(KEY).putBoolean("monitor_enabled",false).apply()
    }
}

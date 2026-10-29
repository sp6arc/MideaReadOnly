package pl.zuza.midea

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** SmartHome wire crypto, ported from midea_auto_cloud (Apache-2.0). */
internal object Crypto {
    const val APP_KEY = "ac21b9f9cbfe4ca5a88562ef25e2b768"
    val IOT_KEY = java.math.BigInteger("7882822598523843940").toByteArray().toString(Charsets.US_ASCII)
    val HMAC_KEY = java.math.BigInteger("117390035944627627450677220413733956185864939010425").toByteArray()
    private val fixedKey = java.lang.Long.toUnsignedString(java.lang.Long.parseUnsignedLong("13101328926877700970"), 16).toByteArray()
    private val fixedIv = java.lang.Long.toUnsignedString(java.lang.Long.parseUnsignedLong("16429062708050928556"), 16).toByteArray()
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0 && s.matches(Regex("[0-9a-fA-F]*")))
        return s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    fun digest(algorithm: String, data: ByteArray) = MessageDigest.getInstance(algorithm).digest(data)
    fun hash(algorithm: String, text: String) = hex(digest(algorithm, text.toByteArray(Charsets.UTF_8)))
    fun sign(body: String, random: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_KEY, "HmacSHA256"))
        return hex(mac.doFinal((IOT_KEY + body + random).toByteArray(Charsets.UTF_8)))
    }
    fun password(id: String, password: String) = hash("SHA-256", id + hash("SHA-256", password) + APP_KEY)
    fun iamPassword(id: String, password: String) = hash("SHA-256", id + hash("MD5", hash("MD5", password)) + APP_KEY)
    fun aes(data: ByteArray, key: ByteArray, iv: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }
    fun sessionKey(encrypted: String): ByteArray {
        val digest = hash("SHA-256", APP_KEY)
        return aes(unhex(encrypted), digest.take(16).toByteArray(), digest.substring(16,32).toByteArray(), false)
    }
    fun fixedEncrypt(text: String) = hex(aes(text.toByteArray(Charsets.UTF_8), fixedKey, fixedIv, true))
}

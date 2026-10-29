package pl.zuza.midea

import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.Base64

internal data class Pump(val id: Long, val name: String, val serial: String, val subtype: String, val manufacturer: String, val online: Boolean) {
    val sn8 get() = if (serial.length > 17) serial.substring(9,17) else "nieznany"
}
internal class CloudException(val code: Int, val endpoint: String): Exception("Midea: $code ($endpoint)")
internal fun obj(vararg pairs: Pair<String,Any?>) = JSONObject().apply { pairs.forEach { put(it.first,it.second ?: JSONObject.NULL) } }

/** Direct HTTPS only. Credentials/session exist in RAM, never logs/preferences/backups. */
internal data class DiagnosticResult(val readings: Map<String,Int>, val rawFrameHex: String, val profileDecoded: Boolean)
internal class CloudClient(private val transport: ((String, Map<String,String>, String) -> String)? = null) {
    private var base = "https://mp-prod.appsmb.com/mas/v5/app/proxy?alias="
    private var token = ""
    private var uid = ""
    private var device = ""
    private var key = byteArrayOf()
    private var iv = byteArrayOf()
    private fun stamp() = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
    private fun general() = obj("appVersion" to "3.0.2", "src" to "10", "format" to "2", "stamp" to stamp(), "platformId" to "1", "deviceId" to device, "reqId" to UUID.randomUUID().toString().replace("-",""), "uid" to uid, "clientType" to "1", "appId" to "1010")
    private fun request(path: String, body: JSONObject, frame: ByteArray?=null): JSONObject {
        ReadPolicy.check(path,body,frame)
        ReadPolicy.validateBase(base)
        if (!body.has("reqId")) body.put("reqId",UUID.randomUUID().toString().replace("-",""))
        if (!body.has("stamp")) body.put("stamp",stamp())
        val text = body.toString()
        val random = (System.currentTimeMillis()/1000).toString()
        val headers = mutableMapOf("content-type" to "application/json; charset=utf-8", "secretVersion" to "1", "sign" to Crypto.sign(text,random), "random" to random, "x-recipe-app" to "1010", "authorization" to "Basic ${Base64.getEncoder().encodeToString((Crypto.APP_KEY+":"+Crypto.IOT_KEY).toByteArray())}")
        if (token.isNotEmpty()) headers["accesstoken"] = token
        if (uid.isNotEmpty()) headers["uid"] = uid
        val response = JSONObject(transport?.invoke(base+path,headers,text) ?: post(base+path,headers,text))
        val code = response.optString("code","-1").toIntOrNull() ?: -1
        if (code != 0) throw CloudException(code,path)
        return response.optJSONObject("data") ?: throw IllegalStateException("Brak obiektu danych")
    }
    private fun post(url: String, headers: Map<String,String>, text: String): String {
        val c = URL(url).openConnection() as HttpsURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout=15000; c.readTimeout=25000; c.requestMethod="POST"; c.doOutput=true
            headers.forEach { (k,v) -> c.setRequestProperty(k,v) }
            c.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val bytes = c.inputStream.use { input ->
                val output=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                while (true) {
                    val count=input.read(buffer);if(count<0) break
                    require(output.size()+count<=1_048_576) { "Za duża odpowiedź" }
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            }
            require(bytes.size <= 1_048_576) { "Za duża odpowiedź" }
            return bytes.toString(Charsets.UTF_8)
        } finally { c.disconnect() }
    }
    fun login(account: String, password: String) {
        clear()
        device = Crypto.hash("MD5","Hello, $account!").take(16)
        val route = request(ReadPolicy.ROUTE, general().put("userName",account).put("userType","0"))
        if (route.has("masUrl")) base = ReadPolicy.validateBase(route.getString("masUrl"))
        val loginId = request(ReadPolicy.LOGIN_ID,general().put("loginAccount",account).put("type","1")).getString("loginId")
        val iot = general().apply { remove("uid") }.put("loginAccount",account).put("password",Crypto.password(loginId,password)).put("iampwd",Crypto.iamPassword(loginId,password))
        val session = request(ReadPolicy.LOGIN,obj("iotData" to iot,"data" to obj("appKey" to Crypto.APP_KEY,"deviceId" to device,"platform" to "2"),"stamp" to stamp()))
        uid=session.getString("uid"); token=session.getJSONObject("mdata").getString("accessToken")
        key=Crypto.sessionKey(session.getString("accessToken")); iv=Crypto.sessionKey(session.getString("randomData"))
        require(key.size==16 && iv.size==16)
    }
    fun pumps(): List<Pump> {
        val list=request(ReadPolicy.LIST,general()).getJSONArray("list")
        return (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optString("type").removePrefix("0x").removePrefix("0X").toIntOrNull(16)==0xc3 }.map { a ->
            Pump(a.getString("id").toLong(), a.optString("name","Midea C3"), if (a.optString("sn").isNotEmpty()) Crypto.aes(Crypto.unhex(a.getString("sn")),key,iv,false).toString(Charsets.UTF_8) else "",a.optString("modelNumber","0"),a.optString("enterpriseCode","0000"),a.optString("onlineStatus")=="1")
        }
    }
    private fun cloudQuery(p: Pump, query: JSONObject): JSONObject = request(ReadPolicy.STATUS,
        obj("clientType" to "1","appId" to "1010","format" to "2","deviceId" to device,
            "iotAppId" to "1010","applianceMFCode" to p.manufacturer,"applianceType" to "0xC3",
            "modelNumber" to p.subtype,"applianceSn" to Crypto.fixedEncrypt(p.serial),"version" to "0",
            "encryptedType " to "2","applianceCode" to p.id,"command" to obj("query" to query)))
    fun status(p: Pump): JSONObject = cloudQuery(p, JSONObject())
    /** Midea's 171H120F Lua defines query_type=16 (0x10) as read-only unit parameters.
     * Whether the cloud will return it depends on the device/firmware; not a control command. */
    fun unitDiagnosticsLua(p: Pump): JSONObject = cloudQuery(p, obj("query_type" to "16"))
    /** Obtain LAN-only V3 token and key for the selected user's own appliance. Never export or persist. */
    fun lanKeys(applianceId:Long): Pair<ByteArray, ByteArray> {
        val failures=mutableListOf<Int>()
        for(method in listOf(1,2)){
            val id=ByteArray(6){ i -> if(method==1) (applianceId ushr (8*(5-i))).toByte() else (applianceId ushr (8*i)).toByte() }
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(id)
            val udp=Crypto.hex(ByteArray(16){ i->(digest[i].toInt() xor digest[i+16].toInt()).toByte()})
            val res=try { request(ReadPolicy.TOKEN,general().put("udpid",udp).put("applianceCodes",applianceId.toString())) }
                catch(e:CloudException){failures.add(e.code);continue}
            val list=res.optJSONArray("tokenlist")?:continue
            for(i in 0 until list.length()){
                val item=list.optJSONObject(i)?:continue
                if(!item.optString("udpId").equals(udp,true))continue
                val a=runCatching{Crypto.unhex(item.getString("token"))}.getOrNull()?:continue
                val b=runCatching{Crypto.unhex(item.getString("key"))}.getOrNull()?:continue
                if(a.size>=32 && b.size==32)return Pair(a,b)
            }
        }
        throw IllegalStateException("Chmura nie udostępniła kluczy Wi-Fi V3 (${failures.distinct().joinToString(",").ifEmpty{"brak tokenu"}})")
    }
    fun diagnostic(p: Pump): DiagnosticResult {
        val packet=C3.packet(p.id)
        val csv=packet.joinToString(",") { (it.toInt() and 255).toString() }
        val order=Crypto.hex(Crypto.aes(csv.toByteArray(),key,iv,true))
        val result=request(ReadPolicy.RELAY,general().put("applianceCode",p.id.toString()).put("order",order), C3.query())
        val plain=Crypto.aes(Crypto.unhex(result.getString("reply")),key,iv,false).toString(Charsets.UTF_8)
        val data=plain.split(",").map { val n=it.trim().toInt(); require(n in -128..255); n.toByte() }.toByteArray()
        val frame = C3.unwrap(data,p.id)
        C3.validateFrame(frame)
        return if (p.sn8 == "171H120F") {
            DiagnosticResult(C3.decode(frame,p.sn8),Crypto.hex(frame),true)
        } else {
            DiagnosticResult(emptyMap(),Crypto.hex(frame),false)
        }
    }
    fun clear() { key.fill(0); iv.fill(0); key=byteArrayOf();iv=byteArrayOf();token="";uid="";device="";base="https://mp-prod.appsmb.com/mas/v5/app/proxy?alias=" }
}

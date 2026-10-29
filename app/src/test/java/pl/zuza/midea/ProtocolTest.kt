package pl.zuza.midea

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class ProtocolTest {
    @Test fun pythonIndependentCryptoVectors() {
        assertEquals("meicloud",Crypto.IOT_KEY)
        assertEquals("9547f87b005d59f906fdda84bd6d6a138c62b03679db1aba1ea996465489ef8a",Crypto.sign("{\"x\":1}","1700000000"))
        assertEquals("03a0780738aa82236f895f72aaeb32e7fc1ff2c60326c4dfd3434dcfc9d350c3",Crypto.password("123456","test-password"))
        assertEquals("f56ee2ff9159073033b9ffa0e7e8944336f793c1c5c23210170fef79cc4c4416",Crypto.iamPassword("123456","test-password"))
    }
    @Test fun aesNistFirstBlock() {
        val encrypted=Crypto.aes(Crypto.unhex("6bc1bee22e409f96e93d7e117393172a"),Crypto.unhex("2b7e151628aed2a6abf7158809cf4f3c"),Crypto.unhex("000102030405060708090a0b0c0d0e0f"),true)
        assertEquals("7649abac8119b246cee98e9b12e9197d",Crypto.hex(encrypted.copyOfRange(0,16)))
    }
    @Test fun writeEndpointsAndControlPayloadsAreRejected() {
        listOf("/v1/device/lua/control","/mjl/v1/device/lua/control","/v1/appliance/set","/unknown").forEach {
            assertThrows(IllegalArgumentException::class.java) { ReadPolicy.check(it,JSONObject()) }
        }
        assertThrows(IllegalArgumentException::class.java) { ReadPolicy.check(ReadPolicy.STATUS,obj("command" to obj("query" to JSONObject(),"control" to obj("power" to "off")))) }
        assertThrows(IllegalArgumentException::class.java) { ReadPolicy.check(ReadPolicy.STATUS,obj("command" to obj("query" to obj("power" to "off")))) }
        ReadPolicy.check(ReadPolicy.STATUS,obj("command" to obj("query" to JSONObject())))
    }
    @Test fun relayRejectsAnyDifferentFrame() {
        ReadPolicy.check(ReadPolicy.RELAY,JSONObject(),C3.query())
        for(i in C3.query().indices) {
            val mutated=C3.query();mutated[i]=(mutated[i].toInt() xor 1).toByte()
            assertThrows(IllegalArgumentException::class.java) { ReadPolicy.check(ReadPolicy.RELAY,JSONObject(),mutated) }
        }
        assertThrows(IllegalArgumentException::class.java) { ReadPolicy.check(ReadPolicy.RELAY,JSONObject()) }
    }
    @Test fun regionRoutingCannotExfiltrateCredentials() {
        ReadPolicy.validateBase("https://mp-prod-eu.appsmb.com/mas/v5/app/proxy?alias=")
        listOf("http://mp-prod.appsmb.com/mas/v5/app/proxy?alias=","https://appsmb.com.evil.test/mas/v5/app/proxy?alias=","https://evil.test/mas/v5/app/proxy?alias=","https://mp-prod.appsmb.com@evil.test/mas/v5/app/proxy?alias=","https://mp-prod.appsmb.com/evil?alias=","https://mp-prod.appsmb.com:8888/mas/v5/app/proxy?alias=").forEach {
            assertThrows(IllegalArgumentException::class.java) { ReadPolicy.validateBase(it) }
        }
    }
    @Test fun packetChecksumsAndDeviceIdentity() {
        val id=123456L;val packet=C3.packet(id)
        assertArrayEquals(C3.query(),C3.unwrap(packet,id))
        assertEquals(0,C3.query().drop(1).sumOf { it.toInt() and 255 } and 255)
        assertThrows(IllegalArgumentException::class.java) { C3.unwrap(packet,id+1) }
        packet[40]=(packet[40].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { C3.unwrap(packet,id) }
    }
    private fun fixture(): ByteArray {
        val f=ByteArray(100);f[0]=0xaa.toByte();f[1]=99;f[2]=0xc3.toByte();f[9]=3;f[10]=0x10
        f[11]=48;f[11+35]=32;f[11+42]=1;f[11+43]=44;f[11+44]=0;f[11+45]=70
        f[f.lastIndex]=(-f.drop(1).sumOf { it.toInt() and 255 }).toByte()
        return f
    }
    @Test fun diagnosticOffsetsAndUnsupportedProfile() {
        val f=fixture();val m=C3.decode(f,"171H120F")
        assertEquals(48,m["comp_run_freq"]);assertEquals(32,m["temp_t2"]);assertEquals(300,m["pressure_high"]);assertEquals(70,m["pressure_low"])
        assertThrows(IllegalArgumentException::class.java) { C3.decode(f,"17100003") }
        assertThrows(IllegalArgumentException::class.java) { C3.decode(f.copyOf(15),"171H120F") }
        f[20]=42;assertThrows(IllegalArgumentException::class.java) { C3.decode(f,"171H120F") }
    }
    @Test fun missingIsNotZeroAndExportStripsSecrets() {
        val safe=Telemetry.safeValues(obj("accessToken" to "secret","id" to 123,"serial" to "secret","pressure_high" to 0,"temp_t2" to "unknown","email" to "private"))
        assertEquals(1,safe.length());assertEquals(0,safe.getInt("pressure_high"));assertFalse(safe.has("pressure_low"))
        val report=Telemetry.report(Telemetry.capture(safe),"test","now","171H120F",3176,ReadPolicy.RELAY,"now").toString()
        assertFalse(report.contains("secret"));assertFalse(report.contains("private"))
    }
    @Test fun offlineLoginDiscoveryStatusAndAsyncErrorFlow() {
        val paths=mutableListOf<String>()
        val sessionKey="0123456789abcdef".toByteArray();val sessionIv="abcdef0123456789".toByteArray()
        val appHash=Crypto.hash("SHA-256",Crypto.APP_KEY)
        fun wrap(b:ByteArray)=Crypto.hex(Crypto.aes(b,appHash.take(16).toByteArray(),appHash.substring(16,32).toByteArray(),true))
        val client=CloudClient { url,headers,body ->
            val path=url.substringAfter("?alias=");paths.add(path)
            assertEquals(Crypto.sign(body,headers.getValue("random")),headers["sign"])
            val data=when(path) {
                ReadPolicy.ROUTE -> obj("masUrl" to "https://mp-prod-eu.appsmb.com/mas/v5/app/proxy?alias=")
                ReadPolicy.LOGIN_ID -> obj("loginId" to "123456")
                ReadPolicy.LOGIN -> obj("uid" to "fake-user","mdata" to obj("accessToken" to "fake-token"),"accessToken" to wrap(sessionKey),"randomData" to wrap(sessionIv))
                ReadPolicy.LIST -> obj("list" to org.json.JSONArray().put(obj("id" to "123456","name" to "Test C3","type" to "0xC3","modelNumber" to "0","onlineStatus" to "1","sn" to Crypto.hex(Crypto.aes("000000000171H120F000000".toByteArray(),sessionKey,sessionIv,true)))))
                ReadPolicy.STATUS -> { assertEquals(0,JSONObject(body).getJSONObject("command").getJSONObject("query").length());obj("tank_actual_temp" to 42.5) }
                ReadPolicy.RELAY -> return@CloudClient obj("code" to 3176).toString()
                else -> error("Unexpected endpoint")
            }
            obj("code" to 0,"data" to data).toString()
        }
        client.login("fake@example.test","test-password")
        val p=client.pumps().single();assertEquals("171H120F",p.sn8)
        assertEquals(42.5,client.status(p).getDouble("tank_actual_temp"),0.001)
        assertEquals(3176,assertThrows(CloudException::class.java) {client.diagnostic(p)}.code)
        assertEquals(listOf(ReadPolicy.ROUTE,ReadPolicy.LOGIN_ID,ReadPolicy.LOGIN,ReadPolicy.LIST,ReadPolicy.STATUS,ReadPolicy.RELAY),paths)
        client.clear()
    }
    @Test fun cloudResponseKeepsEveryUnknownField() {
        val raw=JSONObject()
        repeat(48) { raw.put("cloud_unknown_${it+1}", it) }
        val current=Telemetry.capture(raw)
        assertEquals(48,current.fields.size)
        assertEquals(48,current.values.length())
        assertEquals(0,current.recognized)
        assertEquals(48,current.unknown)
        val export=Telemetry.report(current,"status/lua/get","2026-10-29T20:00:00Z","171H120F",9999,ReadPolicy.RELAY,"2026-10-29T20:01:00Z")
        assertEquals(48,export.getInt("receivedFields"))
        assertEquals(48,export.getJSONObject("values").length())
        assertEquals(9999,export.getInt("lastCloudError"))
        assertEquals(48,export.getJSONArray("fields").length())
    }
    @Test fun nestedTelemetryAndRedaction() {
        val input=obj(
            "temp_t2" to 22,
            "arbitrary_data" to obj("status_value" to false,"pressure_low" to 3.5),
            "accessToken" to "secretToken",
            "serialNumber" to "privateSerial"
        )
        val s=Telemetry.capture(input)
        assertEquals(2,s.recognized)
        assertEquals(1,s.unknown)
        assertEquals(2,s.hidden)
        assertEquals("[ukryto]",s.values.getString("accessToken"))
        val exported=Telemetry.report(s,"status","now","171H120F",null,null,"").toString()
        assertFalse(exported.contains("secretToken"))
        assertFalse(exported.contains("privateSerial"))
        assertTrue(exported.contains("arbitrary_data"))
    }
    @Test fun expandedC3DashboardLabelsDoNotInventUnits() {
        val names = listOf("comp_run_freq", "odu_comp_current", "pressure_high",
            "pressure_low", "temp_t2", "temp_t2b", "temp_t3", "temp_tp",
            "temp_th", "temp_tf", "temp_tw_in", "temp_tw_out", "water_pressure")
        for (name in names) {
            assertTrue("Missing dashboard label: $name", Telemetry.priorities.containsKey(name))
            assertTrue(Telemetry.priorities.getValue(name).contains("surow"))
        }
    }
    @Test fun unknownCloudKeysRemainVisibleInV03() {
        val values = JSONObject().put("unknown_c3_001", 0).put("unknown_c3_002", -3)
        val snap = Telemetry.capture(values)
        assertEquals(2, snap.fields.size)
        assertEquals(2, snap.unknown)
        assertTrue(snap.fields.any { it.path == "unknown_c3_001" && it.shownValue == "0" })
        assertTrue(Telemetry.report(snap,"status/lua/get","now","profile",null,null,"")
            .getJSONObject("values").has("unknown_c3_002"))
    }

}

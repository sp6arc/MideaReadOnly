package pl.zuza.midea

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class ProtocolTest {
    @Test fun c3X01Profile171H120FBasicState36Bytes() {
        val f=ByteArray(36)
        f[0]=0xaa.toByte();f[1]=35;f[2]=0xc3.toByte();f[9]=3;f[10]=1
        f[11]=1 // zone1 on
        f[12]=5 // heat and dhw permitted
        f[14]=3 // heating mode
        f[16]=45;f[17]=46;f[18]=48 // zone 1/2, dhw setpoints
        f[32]=18;f[33]=0 // tank actual / error code
        f[35]=(-f.drop(1).sumOf{it.toInt() and 255}).toByte()
        val d=WifiC3.decodeBasicX01(f,"171H120F")
        assertEquals(1,d["lan_zone1_power_state"])
        assertEquals(0,d["lan_dhw_power_state"])
        assertEquals(45,d["lan_zone1_temp_set"])
        assertEquals(18,d["lan_tank_actual_temp"])
        assertFalse(d.containsKey("comp_run_freq"))
        f[20]=3
        assertThrows(IllegalArgumentException::class.java){WifiC3.decodeBasicX01(f,"171H120F")}
    }
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
        ReadPolicy.check(ReadPolicy.STATUS,obj("command" to obj("query" to obj("query_type" to "16"))))
        listOf("1", "0", "02", "17", "16;control").forEach { other ->
            assertThrows(IllegalArgumentException::class.java) {
                ReadPolicy.check(ReadPolicy.STATUS,obj("command" to obj("query" to obj("query_type" to other))))
            }
        }
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
    @Test fun c3CloudDashboardFieldsAreMappedWithoutInventingCompressor() {
        val raw = obj(
            "run_mode_set" to "heat",
            "zone1_power_state" to "on",
            "zone1_temp_set" to "45",
            "zone2_power_state" to "off",
            "zone2_temp_set" to "46",
            "double_zone_enable" to "off",
            "dhw_power_state" to "off",
            "dhw_enable" to "on",
            "tank_actual_temp" to "21",
            "dhw_temp_set" to "48",
            "room_temp_set" to "23",
            "error_code" to "0"
        )
        val data = Telemetry.capture(raw)
        assertEquals(12, data.fields.size)
        assertEquals(12, data.recognized)
        assertEquals(0, data.unknown)
        assertFalse(data.values.has("comp_run_freq"))
        assertFalse(data.values.has("pressure_high"))
        assertEquals("on", data.values.getString("dhw_enable"))
        assertEquals("off", data.values.getString("dhw_power_state"))
        assertEquals("46", data.values.getString("zone2_temp_set"))
        assertEquals("off", data.values.getString("zone2_power_state"))
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


    @Test fun a0ApplianceProbeMatchesMideaLanWireFormat() {
        val frame=WifiC3.queryAppliance()
        assertEquals(30,frame.size)
        assertEquals(0xaa,frame[0].toInt() and 255)
        assertEquals(29,frame[1].toInt() and 255)
        assertEquals(0xa0,frame[9].toInt() and 255)
        assertEquals(0,frame.drop(1).sumOf { it.toInt() and 255 } and 255)
        assertTrue(frame.copyOfRange(10,29).all { it.toInt()==0 })
    }
    @Test fun x10RealDeviceLayoutAndCompressorFrequency() {
        // Reconstructed sanitized 211 B device reply: no tail SN or credentials.
        val frame=ByteArray(211)
        frame[0]=0xaa.toByte();frame[1]=210.toByte();frame[2]=0xc3.toByte()
        frame[9]=3;frame[10]=16
        fun b(i:Int,v:Int) { frame[11+i]=v.toByte() }
        b(0,57);b(1,2);b(2,64) // 57 Hz, cooling, 640 RPM from wuwentao/midea-lan#51
        b(7,26);b(9,30);b(10,36)
        b(18,239);b(19,1);b(20,224) // EXV 480
        b(35,30);b(36,33);b(42,6);b(43,204);b(44,8);b(45,52)
        b(54,0);b(55,53) // 0.53 m3/h raw
        frame[frame.lastIndex]=(-frame.drop(1).sumOf { it.toInt() and 255 }).toByte()
        val v=C3.decode(frame,"171H120F")
        assertEquals(57,v["comp_run_freq"])
        assertEquals(1,v["compressor_on"])
        assertEquals(640,v["fan_speed"])
        assertEquals(26,v["temp_t4"])
        assertEquals(36,v["temp_tw_out"])
        assertEquals(30,v["temp_tw_in"])
        assertEquals(239,v["odu_voltage"])
        assertEquals(480,v["exv_current"])
        assertEquals(1740,v["pressure_high"])
        assertEquals(2100,v["pressure_low"])
        assertEquals(53,v["water_flow_raw"])
    }

    @Test fun heartbeatPacketIsV2TypeZeroNotC3Set() {
        val hb=WifiC3.heartbeatPacket(123456L)
        assertEquals(0x5a,hb[0].toInt() and 255)
        assertEquals(0x5a,hb[1].toInt() and 255)
        assertEquals(0x10,hb[3].toInt() and 255)
        assertEquals(0x7b,hb[6].toInt() and 255)
        assertEquals(56,hb.size)
        assertEquals(56,(hb[4].toInt() and 255) + (hb[5].toInt() and 255)*256)
        assertEquals(0x5a,hb[0].toInt() and 255)
    }
}

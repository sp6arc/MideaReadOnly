package pl.zuza.midea

/** Query and offsets ported from midea-lan (MIT); packet envelope from midea-cloud-relay (MIT). */
internal object C3 {
    private val signKey = "xhdiwjnchekd4d512chdjx5d8e4c394D2D7S".toByteArray()
    // C3 / protocol version 0 / message type QUERY 03 / unit parameters 10.
    fun query(): ByteArray = Crypto.unhex("aa0bc300000000000003101f")
    fun packet(id: Long): ByteArray {
        require(id in 1..0xffffffffffffL)
        val frame = query()
        val header = ByteArray(40)
        header[0]=0x5a; header[1]=0x5a; header[2]=1; header[3]=0x11
        val length = 40 + frame.size + 16
        header[4]=length.toByte(); header[5]=(length shr 8).toByte()
        for (i in 0..5) header[20+i]=(id shr (8*i)).toByte()
        val data = header + frame
        return data + Crypto.digest("MD5", data + signKey)
    }
    fun unwrap(data: ByteArray, id: Long): ByteArray {
        require(data.size > 56 && data[0] == 0x5a.toByte() && data[1] == 0x5a.toByte()) { "Niepoprawny pakiet" }
        require((data[4].toInt() and 255) + ((data[5].toInt() and 255) shl 8) == data.size)
        val returnedId = (0..5).fold(0L) { a,i -> a or ((data[20+i].toLong() and 255) shl (8*i)) }
        require(returnedId == id) { "Odpowiedź innej pompy" }
        require(java.security.MessageDigest.isEqual(data.takeLast(16).toByteArray(), Crypto.digest("MD5", data.dropLast(16).toByteArray()+signKey)))
        return data.copyOfRange(40, data.size-16)
    }
    /** Validates the C3/X10 envelope even for an as-yet unrecognized device profile. */
    fun validateFrame(frame: ByteArray) {
        require(frame.size >= 12 && (frame[0].toInt() and 255) == 0xaa && (frame[2].toInt() and 255) == 0xc3)
        require((frame[1].toInt() and 255) == frame.size-1)
        require(frame.drop(1).sumOf { it.toInt() and 255 } and 255 == 0)
        require((frame[9].toInt() and 255) in setOf(3,4) && (frame[10].toInt() and 255) == 0x10)
    }
    /** Unknown layouts are never interpreted using a different pump's offsets. Units stay raw. */
    /** Layout verified against Midea's 171H120F Lua and vasicekmilan90-eng/midea-lan.
     * Never interpret another SN8 using these offsets; null sensors are omitted. */
    fun decode(frame: ByteArray, sn8: String): Map<String, Int> {
        validateFrame(frame)
        require(sn8 == "171H120F") { "Odebrano X10; profil $sn8 nie jest zweryfikowany" }
        require(frame.size >= 100) { "Niepełna odpowiedź X10: ${frame.size} B" }
        fun u(i: Int) = frame[11+i].toInt() and 255
        fun word(i: Int) = (u(i) shl 8) + u(i+1)
        val data=linkedMapOf<String,Int>()
        fun value(name:String, raw:Int) { data[name]=raw }
        fun temp(name:String, index:Int) {
            val raw=u(index)
            if(raw!=127 && raw!=255) data[name]=frame[11+index].toInt()
        }
        value("comp_run_freq",u(0))
        value("compressor_on",if(u(0)>0) 1 else 0)
        value("unit_mode_run",u(1))
        value("fan_speed",u(2)*10) // RPM; 171H120F Lua byte 3
        value("odu_comp_current",u(16))
        value("odu_voltage",u(18))
        value("exv_current",word(19))
        value("odu_target_fre",u(48))
        value("dc_current",u(49))
        value("dc_bus_voltage",u(50)*10)
        temp("temp_t3",6); temp("temp_t4",7); temp("temp_tp",8)
        temp("temp_tw_in",9); temp("temp_tw_out",10)
        temp("temp_t1",33); temp("temp_tw2",34)
        temp("temp_t2",35); temp("temp_t2b",36)
        temp("temp_t5",37); temp("temp_ta",38)
        temp("temp_th",46); temp("temp_tf",51)
        value("pressure_high",word(42))
        value("pressure_low",word(44))
        value("pump_i_running",if((u(32) and 0x08)!=0) 1 else 0)
        value("water_flow_raw",word(54)) // 0.01 m3/h, retained raw
        value("instant_power_raw",word(57))
        value("water_pressure",word(61))
        value("total_electricity0",word(68))
        value("total_thermal0",word(72))
        value("instant_power0",word(82))
        return data
    }
}

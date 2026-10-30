package pl.zuza.midea

/**
 * Read-only C3 X00 notify decoders from wuwentao/midea-lan
 * (midealan/devices/c3/message.py, model 17100007, MIT).
 * The offsets refer to the body starting with byte 0x00.
 * Unknown commands and truncated fields are not guessed.
 */
internal object C3X00 {
    private fun u(a: ByteArray, i: Int) = a[i].toInt() and 255
    private fun word(a: ByteArray, i: Int) = (u(a, i) shl 8) or u(a, i + 1)
    private fun dword(a: ByteArray, i: Int): Long =
        (0..3).fold(0L) { acc, n -> (acc shl 8) or u(a, i + n).toLong() }
    private fun signedTemp(a: ByteArray, i: Int): Double? {
        val n = word(a, i)
        if (n == 0xffff) return null
        return (if ((n and 0x8000) != 0) -(n and 0x7fff) else n).toDouble() / 10.0
    }

    fun command(frame: ByteArray): String? {
        if (frame.size < 15 || u(frame, 10) != 0x00) return null
        return "%02X%02X".format(u(frame, 11), u(frame, 12))
    }

    fun decode(frame: ByteArray, model: String): Map<String, Number> {
        require(frame.size >= 15 && u(frame, 0) == 0xaa && u(frame, 2) == 0xc3)
        require(u(frame, 1) == frame.size - 1 && u(frame, 9) == 4 && u(frame, 10) == 0)
        require((frame.drop(1).sumOf { it.toInt() and 255 } and 255) == 0)
        val body = frame.copyOfRange(10, frame.size - 1)
        val command = command(frame) ?: return emptyMap()
        val out = linkedMapOf<String, Number>()
        fun put8(key: String, offset: Int) {
            if (offset < body.size) out[key] = u(body, offset)
        }
        fun put16(key: String, offset: Int, multiplier: Int = 1) {
            if (offset + 1 < body.size) out[key] = word(body, offset) * multiplier
        }
        fun put32(key: String, offset: Int) {
            if (offset + 3 < body.size) out[key] = dword(body, offset)
        }
        fun putTemp(key: String, offset: Int) {
            if (offset + 1 < body.size) signedTemp(body, offset)?.let { out[key] = it }
        }
        when (command) {
            "2010" -> {
                if (body.size < 99) return emptyMap()
                if (model == "17100007") out["idu_t1s1"] = word(body, 14) / 10.0
                out["dhw_target_temp"] = word(body, 20) / 10.0
                out["tank_actual_temperature"] = word(body, 67) / 10.0
                out["temp_t4"] = word(body, 97) / 10.0
                out["outdoor_temperature"] = out.getValue("temp_t4")
            }
            "20B0", "2F10" -> {
                if (model != "17100007") return emptyMap()
                val b0 = command == "20B0"
                if (body.size < (if (b0) 26 else 23)) return emptyMap()
                val shift = if (b0) 0 else 1
                put8("comp_run_freq", 10)
                out["compressor_on"] = if ((out["comp_run_freq"]?.toInt() ?: 0) > 0) 1 else 0
                put16("fan_speed", if (b0) 12 else 11)
                putTemp("temp_t3", if (b0) 16 else 13)
                putTemp("temp_t4", if (b0) 18 else 15)
                out["temp_t4"]?.let { out["outdoor_temperature"] = it }
                putTemp("temp_tp", if (b0) 20 else 17)
                putTemp("temp_tw_in", if (b0) 22 else 19)
                putTemp("temp_tw_out", if (b0) 24 else 21)
                put16("odu_comp_current", if (b0) 33 else 23)
                put16("odu_voltage", if (b0) 35 else 25)
                put16("exv_current", if (b0) 37 else 127)
                put8("odu_model", if (b0) 39 else 122)
                putTemp("temp_t1", if (b0) 92 else 27)
                putTemp("temp_t2", if (b0) 96 else 31)
                putTemp("temp_t2b", if (b0) 98 else 33)
                putTemp("temp_t5", if (b0) 100 else 35)
                put16("pressure_high", if (b0) 109 else 39, 10) // kPa
                putTemp("temp_th", if (b0) 113 else 43)
                putTemp("temp_tf", if (b0) 119 else 46)
                if ((if (b0) 122 else 49) <= body.size)
                    put16("idu_t1s1_raw_decic", if (b0) 121 else 48)
                val flow = if (b0) 125 else 52
                if (flow + 1 < body.size) out["water_flower"] = word(body, flow) / 100.0
                val cap = if (b0) 129 else 54
                if (cap + 1 < body.size) out["current_unit_capacity_provisional"] = word(body, cap) / 100.0
                put32("total_energy_consumption", if (b0) 140 else 60)
                if (b0) {
                    put8("pump_i_pwm", 139)
                } else {
                    put32("comp_total_run_time", 88)
                    put8("pump_i_pwm", 92)
                    put16("comp_run_time_raw", 118)
                    put16("dc_bus_voltage", 141)
                    if (body.size > 144) out["dc_current_provisional"] = word(body, 143) / 100.0
                    put8("sv1_open", 145)
                    put8("sv2_open", 146)
                    put8("pump_i_running", 148)
                    put8("pump_o_running", 149)
                    put8("pump_d_running", 150)
                    put32("fan_total_run_time", 157)
                    put32("pump_i_total_run_time", 161)
                }
            }
        }
        return out
    }
}

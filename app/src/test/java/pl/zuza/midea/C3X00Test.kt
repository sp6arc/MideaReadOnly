package pl.zuza.midea

import org.junit.Test
import org.junit.Assert.*

class C3X00Test {
    private fun frame(command: Int, bodySize: Int): ByteArray {
        val a = ByteArray(10 + bodySize + 1)
        a[0] = 0xaa.toByte()
        a[1] = (a.size - 1).toByte()
        a[2] = 0xc3.toByte()
        a[9] = 4
        a[10] = 0
        a[11] = (command shr 8).toByte()
        a[12] = command.toByte()
        a[a.lastIndex] = (-a.drop(1).sumOf { it.toInt() and 255 }).toByte()
        return a
    }
    private fun set8(f: ByteArray, offset: Int, value: Int) { f[10+offset] = value.toByte() }
    private fun set16(f: ByteArray, offset: Int, value: Int) {
        set8(f, offset, value shr 8)
        set8(f, offset+1, value)
    }
    private fun finish(f: ByteArray) {
        f[f.lastIndex] = (-f.drop(1).dropLast(1).sumOf { it.toInt() and 255 }).toByte()
    }
    @Test fun decode20B0FromUpstreamDocument() {
        val f=frame(0x20B0,144)
        set8(f,10,55)
        set16(f,12,900)
        set16(f,16,0x800f)
        set16(f,18,51)
        set16(f,22,347)
        set16(f,24,386)
        set16(f,109,275)
        finish(f)
        val d=C3X00.decode(f,"17100007")
        assertEquals(55,d["comp_run_freq"])
        assertEquals(1,d["compressor_on"])
        assertEquals(900,d["fan_speed"])
        assertEquals(-1.5,d["temp_t3"])
        assertEquals(5.1,d["outdoor_temperature"])
        assertEquals(34.7,d["temp_tw_in"])
        assertEquals(38.6,d["temp_tw_out"])
        assertEquals(2750,d["pressure_high"])
    }
    @Test fun decode2F10RuntimeAndSensors() {
        val f=frame(0x2F10,165)
        set8(f,10,42)
        set16(f,11,750)
        set16(f,15,0x801e)
        set16(f,39,200)
        set16(f,141,380)
        set8(f,148,1)
        finish(f)
        val d=C3X00.decode(f,"17100007")
        assertEquals(42,d["comp_run_freq"])
        assertEquals(750,d["fan_speed"])
        assertEquals(-3.0,d["outdoor_temperature"])
        assertEquals(2000,d["pressure_high"])
        assertEquals(380,d["dc_bus_voltage"])
        assertEquals(1,d["pump_i_running"])
    }
    @Test fun unknownModelAndTruncatedFramesNotGuessed() {
        val f=frame(0x20B0,26)
        set8(f,10,35)
        finish(f)
        assertTrue(C3X00.decode(f,"171H120F").isEmpty())
        assertEquals(35,C3X00.decode(f,"17100007")["comp_run_freq"])
        assertFalse(C3X00.decode(f,"17100007").containsKey("pressure_high"))
    }
}

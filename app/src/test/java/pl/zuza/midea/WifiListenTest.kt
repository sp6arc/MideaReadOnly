package pl.zuza.midea

import org.junit.Test
import org.junit.Assert.*
import java.io.InputStream
import java.net.SocketTimeoutException

class WifiListenTest {
    private fun frame(mt:Int, sub:Int, data:ByteArray):ByteArray {
        val f=ByteArray(12+data.size)
        f[0]=0xaa.toByte();f[1]=(f.size-1).toByte();f[2]=0xc3.toByte()
        f[9]=mt.toByte();f[10]=sub.toByte()
        data.copyInto(f,11)
        f[f.lastIndex]=(-f.drop(1).sumOf{it.toInt() and 255}).toByte()
        return f
    }
    @Test fun unsolicitedX05UsesItsOwnLayoutAndRejectsSilenceReply() {
        val data=ByteArray(60)
        data[0]=46;data[1]=65;data[2]=31;data[5]=30;data[6]=35
        data[11]=127;data[12]=27;data[13]=40
        data[16]=0x06;data[17]=0xcc.toByte();data[18]=0x08;data[19]=0x34
        data[56]=0x09;data[57]=0x3d;data[58]=99
        val d=WifiC3.decodeNotification(frame(4,5,data),"171H120F")
        assertEquals(46,d["comp_run_freq"]);assertEquals(650,d["fan_speed"])
        assertEquals(30,d["temp_tw_in"]);assertEquals(35,d["temp_tw_out"])
        assertEquals(27,d["temp_t2"]);assertEquals(1740,d["pressure_high"])
        assertEquals(2100,d["pressure_low"]);assertEquals(2365,d["comp_total_run_time"])
        assertFalse(d.containsKey("temp_tw2"))
        assertThrows(IllegalArgumentException::class.java){WifiC3.decodeNotification(frame(3,5,data),"171H120F")}
        assertThrows(IllegalArgumentException::class.java){WifiC3.decodeNotification(frame(4,5,ByteArray(59)),"171H120F")}
        assertThrows(IllegalArgumentException::class.java){WifiC3.decodeNotification(frame(4,5,data),"17100007")}
    }
    @Test fun energyCountersAreUnsignedAndSetpointsAreNotWaterReadings() {
        val data=ByteArray(14);data[0]=1
        for(i in 1..4)data[i]=0xff.toByte()
        data[9]=(-8).toByte();data[10]=42
        val d=WifiC3.decodeNotification(frame(4,4,data),"171H120F")
        assertEquals(4294967295L,d["total_energy_consumption"])
        assertEquals(-8,d["outdoor_temperature"]);assertEquals(42,d["lan_zone1_temp_set"])
        assertFalse(d.containsKey("temp_tw_out"));assertFalse(d.containsKey("comp_run_freq"))
        val f=frame(4,4,data);f[15]=(f[15].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java){WifiC3.decodeNotification(f,"171H120F")}
    }
    @Test fun tcpFragmentSurvivesTimeoutAndCoalescedFramesAreSeparated() {
        val first=ByteArray(56).also{it[0]=0x5a;it[1]=0x5a;it[4]=56}
        val second=ByteArray(72).also{it[0]=0x83.toByte();it[1]=0x70;it[3]=64}
        var step=0
        val input=object:InputStream(){
            override fun read():Int=error("Use bulk reads")
            override fun read(b:ByteArray,off:Int,len:Int):Int {
                val chunk=when(step++) {
                    0 -> first.copyOfRange(0,3)
                    1 -> throw SocketTimeoutException()
                    2 -> first.copyOfRange(3,56)+second
                    else -> return -1
                }
                chunk.copyInto(b,off);return chunk.size
            }
        }
        val reader=WifiC3.FrameReader(input)
        assertThrows(SocketTimeoutException::class.java){reader.read()}
        assertArrayEquals(first,reader.read())
        assertArrayEquals(second,reader.read())
    }
}

package pl.zuza.midea

import org.junit.Test
import org.junit.Assert.*

/** Regression data: authentic, non-secret 171H120F request/reply frames. */
class RealLanLogTest {
    @Test fun x10MatchesTheUpstreamRequestVersionZero() {
        assertEquals("aa0bc300000000000003101f",Crypto.hex(C3.query()))
    }

    @Test fun x01RealReplyRetainsObservedValuesAndNoCompressorGuessing() {
        val real=Crypto.unhex("aa23c3000000000000030101759103032d2e2f2e41231905371919053c223c14130000a0")
        val result=WifiC3.decodeBasicX01(real,"171H120F")
        assertEquals(45, result["lan_zone1_temp_set"])
        assertEquals(46, result["lan_zone2_temp_set"])
        assertEquals(47, result["lan_dhw_temp_set"])
        assertEquals(19, result["lan_tank_actual_temp"])
        assertEquals(0, result["lan_error_code"])
        assertFalse(result.containsKey("comp_run_freq"))
        assertFalse(result.containsKey("temp_tw_in"))
    }

    @Test fun x00PushOnlyDecodedWhenReceivedAndModelMatches() {
        val frame=ByteArray(10+26+1)
        frame[0]=0xaa.toByte()
        frame[1]=(frame.size-1).toByte()
        frame[2]=0xc3.toByte()
        frame[9]=4
        frame[10]=0
        frame[11]=0x20
        frame[12]=0xb0.toByte()
        frame[20]=60
        frame[frame.lastIndex]=(-frame.drop(1).dropLast(1).sumOf { it.toInt() and 255 }).toByte()
        assertEquals(60,C3X00.decode(frame,"17100007")["comp_run_freq"])
        assertTrue(C3X00.decode(frame,"171H120F").isEmpty())
    }
}

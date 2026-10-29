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
    fun decode(frame: ByteArray, sn8: String): Map<String, Int> {
        validateFrame(frame)
        require(sn8 == "171H120F") { "Odebrano X10; profil $sn8 nie jest zweryfikowany" }
        require(frame.size >= 65) { "Niepełna odpowiedź X10" }
        fun u(i: Int)=frame[11+i].toInt() and 255
        fun word(i: Int)=(u(i) shl 8) + u(i+1)
        return linkedMapOf(
            "comp_run_freq" to u(0), "odu_target_fre" to u(48), "odu_comp_current" to u(16),
            "temp_t2" to u(35), "temp_t2b" to u(36), "temp_t3" to u(6),
            "temp_tp" to u(8), "temp_th" to u(46), "temp_tf" to u(51),
            "pressure_high" to word(42), "pressure_low" to word(44),
            "temp_tw_in" to u(9), "temp_tw_out" to u(10)
        )
    }
}

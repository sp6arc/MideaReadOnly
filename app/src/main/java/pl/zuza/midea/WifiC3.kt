package pl.zuza.midea

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Discovery and directly authenticated LAN read of one's own C3 device.
 * No HA, Modbus, local proxy, arbitrary LAN commands or controls.
 * Protocol based on wuwentao/midea-lan and xelhark/midea-cloud-relay (MIT).
 */
internal data class LanPump(val host: String, val port: Int, val id: Long, val type: Int, val protocol: Int, val model: String) {
    val display get() = "$host:$port · C3 · V$protocol" 
}
/** Only the inner C3 frame is exported. Limit to 88 bytes: X10 may contain serial
 * number blocks in its tail, which are deliberately NEVER exported. */
internal data class LanFrame(val request: String, val messageType: Int, val subtype: Int,
    val size: Int, val checksumOk: Boolean, val rawPrefixHex: String, val truncated: Boolean,
    val direction: String = "RX", val receivedAt: String = java.time.Instant.now().toString())
internal data class LanResult(val readings: Map<String, Number>, val rawX10: String,
    val responseType: String, val steps: List<String>, val frames: List<LanFrame>,
    val fieldSources: Map<String,String> = emptyMap(), val fieldTimes: Map<String,String> = emptyMap())
internal object WifiC3 {
    private val aesKey = Crypto.unhex("6a92ef406bad2f0359baad994171ea6d")
    private val signSalt = "xhdiwjnchekd4d512chdjx5d8e4c394D2D7S".toByteArray(Charsets.UTF_8)
    // Official Midea discovery request as implemented in wuwentao/midea-lan.
    private val discovery = Crypto.unhex("5a5a01114800920000000000000000000000000000000000000000000000000000000000000000007f75bd6b3e4f8b762e849c6e578d6590036e9d4342a50f1f569eb8ec918e92e5")
    private val random = SecureRandom()
    private fun u(b: Byte) = b.toInt() and 255
    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun aesEcb(input: ByteArray, encrypt: Boolean): ByteArray = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
        init(if(encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(aesKey,"AES")); doFinal(input)
    }
    private fun aesCbc(input: ByteArray, key: ByteArray, encrypt: Boolean): ByteArray = Cipher.getInstance("AES/CBC/NoPadding").run {
        init(if(encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key,"AES"),IvParameterSpec(ByteArray(16))); doFinal(input)
    }
    private fun uintLE(src: ByteArray, start: Int, count: Int): Long = (0 until count).fold(0L){r,i->r or ((u(src[start+i]).toLong()) shl (8*i))}
    private fun network(context: Context): Network? {
        val c=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return c.allNetworks.firstOrNull { n -> c.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }
    private fun localIp(ip: String): Boolean = try {
        val b=ip.split('.').map{it.toInt()}
        b.size==4 && b.all{it in 0..255} && ((b[0]==10) || (b[0]==172 && b[1] in 16..31) || (b[0]==192 && b[1]==168) || (b[0]==169 && b[1]==254)) && b[3] !in listOf(0,255)
    } catch (_:Exception){false}
    fun checkedIp(ip:String):String { require(localIp(ip)){"Podaj lokalny adres IPv4 pompy (np. 192.168.1.30)"}; return ip }
    private fun discoveryTargets(): Set<String> {
        val targets=linkedSetOf("255.255.255.255")
        for (nic in NetworkInterface.getNetworkInterfaces().toList()) try {
            if(nic.isUp && !nic.isLoopback) for (address in nic.interfaceAddresses) {
                val a=address.broadcast
                if (a is Inet4Address) targets.add(a.hostAddress ?: continue)
            }
        }catch (_:Exception){}
        return targets
    }
    private fun parseDiscovery(data: ByteArray, ip:String):LanPump? {
        val blob=if(data.size>112 && u(data[0])==0x83 && u(data[1])==0x70 && u(data[8])==0x5a && u(data[9])==0x5a) data.copyOfRange(8,data.size-16) else data
        if (blob.size<104 || u(blob[0])!=0x5a || u(blob[1])!=0x5a) return null
        val length=u(blob[4]) or (u(blob[5]) shl 8)
        if (length > blob.size || length<104) return null
        val id=uintLE(blob,20,6)
        val encrypted=blob.copyOfRange(40,length-16)
        val decrypted=runCatching { aesEcb(encrypted,false) }.getOrNull()
        val candidate=listOfNotNull(decrypted, encrypted).firstOrNull { b ->
            b.size>41 && (u(b[40]) in 1..45) && (41+u(b[40])<=b.size) &&
                runCatching{ String(b,41,u(b[40]),Charsets.US_ASCII).startsWith("net_") }.getOrDefault(false)
        } ?: return null
        val ssid=String(candidate,41,u(candidate[40]),Charsets.US_ASCII)
        val segments=ssid.split('_')
        if (segments.size<2) return null
        val type=segments[1].toIntOrNull(16) ?: return null
        if(type!=0xc3 || id<=0) return null
        val port=uintLE(candidate,4,4).toInt().let { if(it in 1..65535) it else 6444 }
        val model=runCatching { String(candidate,17,8,Charsets.US_ASCII).trim().filter{it.isLetterOrDigit()} }.getOrDefault("").take(8)
        // V3 outer discovery, or 5a5a with UDP v4 extension: prefer V3 on newer devices.
        val tail=41+u(candidate[40]); val udpVersion=if(candidate.size>=tail+8) uintLE(candidate,tail+4,4) else 0L
        val v=if(udpVersion>=4) 4 else if(u(data[0])==0x83) 3 else 2
        return LanPump(ip,port,id,type,v,model)
    }
    /** Broadcast is limited to the phone's connected Wi-Fi, and to Midea C3 responses. */
    fun discover(context:Context):List<LanPump> {
        val wifi=network(context) ?: throw IllegalStateException("Telefon nie jest połączony z siecią Wi-Fi")
        val manager=context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock=manager.createMulticastLock("midea-readonly-discovery").apply{setReferenceCounted(false)}
        val candidates=linkedMapOf<Long,LanPump>()
        lock.acquire()
        try {
            val sockets=mutableListOf<DatagramSocket>()
            try {
                val sender=DatagramSocket(null).apply { reuseAddress=true; bind(InetSocketAddress(0)); broadcast=true; soTimeout=220 }
                wifi.bindSocket(sender)
                sockets.add(sender)
                for (port in listOf(7083,15000)) runCatching {
                    val recv=DatagramSocket(null).apply{reuseAddress=true;bind(InetSocketAddress(port));soTimeout=220}
                    wifi.bindSocket(recv)
                    sockets.add(recv)
                }
                for (ip in discoveryTargets()) for (port in listOf(6445,20086)) {
                    val host=InetAddress.getByName(ip)
                    sender.send(DatagramPacket(discovery,discovery.size,host,port))
                }
                val end=System.currentTimeMillis()+5200
                while(System.currentTimeMillis()<end) for(s in sockets){
                    val buf=ByteArray(2048);val dp=DatagramPacket(buf,buf.size)
                    try {
                        s.receive(dp)
                        val src=dp.address.hostAddress ?: continue
                        if(!localIp(src))continue
                        val found=parseDiscovery(dp.data.copyOf(dp.length),src) ?: continue
                        candidates[found.id]=found
                    }catch(_:SocketTimeoutException){}catch(_:java.io.IOException){}
                }
            } finally{sockets.forEach { runCatching {it.close()} }}
        } finally { if(lock.isHeld)lock.release() }
        return candidates.values.toList()
    }
    private fun packV2(id:Long, command:ByteArray): ByteArray {
        require(id in 1..0xffffffffffffL)
        val h=ByteArray(40)
        h[0]=0x5a; h[1]=0x5a;h[2]=1;h[3]=0x11;h[6]=0x20
        for(i in 0..7)h[20+i]=(id ushr (8*i)).toByte()
        // Date/time bytes as specified in Midea PacketBuilder.
        val now=java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
        val stamp="%04d%02d%02d%02d%02d%02d00".format(java.util.Locale.US,now.year,now.monthValue,now.dayOfMonth,now.hour,now.minute,now.second)
        for(i in 0..7)h[12+i]=stamp.substring(i*2,i*2+2).toInt().toByte()
        for(i in 0..3) { val b=h[12+i];h[12+i]=h[19-i];h[19-i]=b }
        val enc=aesEcb(command,true)
        val length=40+enc.size+16
        h[4]=length.toByte();h[5]=(length shr 8).toByte()
        val packet=h+enc
        return packet+md5(packet+signSalt)
    }
    private fun unpackV2(data:ByteArray, id:Long):ByteArray {
        require(data.size>=56 && u(data[0])==0x5a && u(data[1])==0x5a){"Niepoprawna odpowiedź V2"}
        val length=u(data[4]) or (u(data[5]) shl 8)
        require(length==data.size && uintLE(data,20,6)==id){"Nieprawidłowa długość/ID V2"}
        require(MessageDigest.isEqual(md5(data.copyOfRange(0,data.size-16)+signSalt), data.copyOfRange(data.size-16,data.size))){"Nieprawidłowy podpis V2"}
        return aesEcb(data.copyOfRange(40,data.size-16),false)
    }
    /** Retains partial frames across socket timeouts; consumes exactly one frame at a time. */
    internal class FrameReader(private val input: InputStream) {
        private var pending = ByteArray(0)
        fun read(): ByteArray {
            while (true) {
                if (pending.size >= 6) {
                    val n = when {
                        u(pending[0]) == 0x5a && u(pending[1]) == 0x5a ->
                            (u(pending[4]) or (u(pending[5]) shl 8)).also { require(it >= 56) }
                        u(pending[0]) == 0x83 && u(pending[1]) == 0x70 ->
                            ((u(pending[2]) shl 8) or u(pending[3])) + 8
                        else -> error("Nieznany nagłówek transportu")
                    }
                    require(n in 8..16384) { "Nieprawidłowa długość transportu" }
                    if (pending.size >= n) {
                        val frame = pending.copyOfRange(0,n)
                        pending = pending.copyOfRange(n,pending.size)
                        return frame
                    }
                }
                val chunk = ByteArray(4096)
                val count = input.read(chunk)
                if (count < 0) throw java.io.EOFException("Moduł zamknął połączenie TCP")
                if (count > 0) pending += chunk.copyOf(count)
            }
        }
    }
    private class AuthV3(val token:ByteArray,val key:ByteArray){
        var tcpKey:ByteArray?=null
        var seq=0
        fun encode(message:ByteArray, type:Int):ByteArray {
            var data=message;var size=data.size;var padding=0
            if(type==6){ if((size+2)%16!=0){padding=16-((size+2)%16);data+=ByteArray(padding).also{random.nextBytes(it)}};size=data.size+32 }
            val header=byteArrayOf(0x83.toByte(),0x70.toByte(),(size shr 8).toByte(),size.toByte(),0x20.toByte(),((padding shl 4) or type).toByte())
            val payload=byteArrayOf((seq shr 8).toByte(),seq.toByte())+data
            seq=(seq+1) and 65535
            return if(type==6) {
                val k=tcpKey ?: throw IllegalStateException("Brak uwierzytelnienia V3")
                header+aesCbc(payload,k,true)+sha(header+payload)
            }else header+payload
        }
        fun authenticate(response:ByteArray){
            require(response.size>=72 && u(response[0])==0x83 && u(response[1])==0x70 && (u(response[5]) and 0x0f)==1){"Odmowa autoryzacji V3 / niepoprawny handshake"}
            val d=response.copyOfRange(8,72)
            require(d.size==64 && key.size==32){"Nieprawidłowy klucz V3"}
            val plain=aesCbc(d.copyOfRange(0,32),key,false)
            require(MessageDigest.isEqual(sha(plain),d.copyOfRange(32,64))){"Odmowa autoryzacji V3 (klucz/token)"}
            tcpKey=ByteArray(32){i->(plain[i].toInt() xor key[i].toInt()).toByte()};seq=0
        }
        fun decode(frame:ByteArray):ByteArray {
            require(frame.size>=8 && u(frame[0])==0x83 && u(frame[1])==0x70){"Niepoprawna odpowiedź 8370"}
            val type=u(frame[5]) and 15;val pad=u(frame[5]) shr 4
            require(type==3){"Nieoczekiwana odpowiedź V3: $type"}
            val payload=frame.copyOfRange(6,frame.size-32);val plain=aesCbc(payload,tcpKey ?: error("Brak klucza V3"),false)
            require(MessageDigest.isEqual(sha(frame.copyOfRange(0,6)+plain),frame.copyOfRange(frame.size-32,frame.size))){"Błąd podpisu V3"}
            return plain.copyOfRange(2,plain.size-pad)
        }
    }
    /** As in midea-lan: A0 appliance query precedes status queries.
     * Exactly 19 zero body bytes, type 0xA0; never changes pump settings. */
    internal fun queryAppliance(): ByteArray {
        val frame=ByteArray(30)
        frame[0]=0xaa.toByte();frame[1]=29;frame[2]=0xc3.toByte()
        frame[9]=0xa0.toByte()
        frame[29]=(-frame.drop(1).sumOf { u(it) }).toByte()
        return frame
    }
    private fun queryC3(subType:Int):ByteArray {
        require(subType in listOf(1,5,7,9,10,16)){"Tylko dozwolone zapytania C3"}
        val frame=Crypto.unhex("aa0bc3000000000000030000")
        frame[10]=subType.toByte()
        frame[11]=(-frame.drop(1).sumOf{u(it)}).toByte()
        return frame
    }
    private fun parseX10(body:ByteArray, id:String):Map<String,Int> {
        require(body.size>63 && u(body[0])==0xaa && u(body[2])==0xc3 && u(body[10])==0x10){"Brak odpowiedzi X10"}
        require((u(body[9]) in listOf(3,4)) && body.drop(1).sumOf{u(it)} and 255==0){"Niepoprawna suma X10"}
        return C3.decode(body,id)
    }
    /** The native 171H120F Lua X01 layout. This is basic state, NOT compressor telemetry.
     * For 36-byte messages, 24 bytes of the state body are present. Reject other profiles,
     * invalid checksum, short or unexpected frame types. */
    fun decodeBasicX01(frame: ByteArray, profile: String): Map<String,Int> {
        require(profile == "171H120F") { "Niezweryfikowany profil X01" }
        require(frame.size >= 36 && frame.size <= 256 && u(frame[0]) == 0xaa &&
            u(frame[2]) == 0xc3 && u(frame[1]) == frame.size - 1 &&
            u(frame[9]) in listOf(3,4) && u(frame[10]) == 1 &&
            frame.drop(1).sumOf { u(it) } and 255 == 0) { "Nieprawidłowa ramka X01" }
        // Lua _bodyBytes[1] starts at frame[11]. The last byte is checksum.
        fun b(index: Int) = u(frame[10 + index])
        fun bit(index: Int, mask: Int) = if (b(index) and mask != 0) 1 else 0
        return linkedMapOf(
            "lan_zone1_power_state" to bit(1,1),
            "lan_zone2_power_state" to bit(1,2),
            "lan_dhw_power_state" to bit(1,4),
            "lan_heat_enable" to bit(2,1),
            "lan_cool_enable" to bit(2,2),
            "lan_dhw_enable" to bit(2,4),
            "lan_mode_set_raw" to b(4),
            "lan_zone1_temp_set" to b(6),
            "lan_zone2_temp_set" to b(7),
            "lan_dhw_temp_set" to b(8),
            "lan_tank_actual_temp" to b(22),
            "lan_error_code" to b(23)
        )
    }
    private fun frameValid(body: ByteArray): Boolean = body.size >= 12 &&
        u(body[0]) == 0xaa && u(body[2]) == 0xc3 && u(body[1]) == body.size - 1 &&
        (body.drop(1).sumOf { u(it) } and 255) == 0
    /** Verified standard 171H120F notifications. Values retain protocol units. */
    fun decodeNotification(frame: ByteArray, profile: String): Map<String,Number> {
        require(profile == "171H120F" && frameValid(frame) && u(frame[9]) == 4)
        fun b(i:Int) = u(frame[11+i])
        fun word(i:Int) = (b(i) shl 8) or b(i+1)
        fun counter(i:Int) = (0..3).fold(0L) { value,j -> (value shl 8) or b(i+j).toLong() }
        val values = linkedMapOf<String,Number>()
        when(u(frame[10])) {
            4 -> {
                require(frame.size >= 26) { "Niepełna ramka X04" }
                values["status_heating"] = b(0) and 1
                values["status_cool"] = (b(0) shr 1) and 1
                values["status_dhw"] = (b(0) shr 2) and 1
                values["total_energy_consumption"] = counter(1)
                values["total_produced_energy"] = counter(5)
                if(b(9) != 127) values["outdoor_temperature"] = frame[20].toInt()
                values["lan_zone1_temp_set"] = b(10)
                values["lan_zone2_temp_set"] = b(11)
                values["lan_dhw_temp_set"] = b(12)
                values["lan_room_temp_set_raw"] = b(13)
            }
            5 -> {
                require(frame.size >= 72) { "Niepełna ramka UnitPara notify X05" }
                values["comp_run_freq"] = b(0)
                values["compressor_on"] = if(b(0)>0) 1 else 0
                values["fan_speed"] = b(1)*10
                mapOf("temp_t3" to 2,"temp_t4" to 3,"temp_tp" to 4,
                    "temp_tw_in" to 5,"temp_tw_out" to 6,"temp_t1" to 10,
                    "temp_tw2" to 11,"temp_t2" to 12,"temp_t2b" to 13,
                    "temp_t5" to 14,"temp_ta" to 15,"temp_th" to 20,"temp_tf" to 22
                ).forEach { (name,offset) -> if(b(offset)!=127) values[name]=b(offset) }
                values["odu_comp_current"] = b(7)
                values["odu_voltage"] = word(8)
                values["pressure_high"] = word(16)
                values["pressure_low"] = word(18)
                values["odu_target_fre"] = b(21)
                values["water_flow_raw"] = word(25)
                values["current_unit_capacity"] = word(27)
                values["water_pressure"] = word(29)
                values["total_electricity0"] = counter(32)
                values["total_thermal0"] = counter(36)
                values["instant_power0"] = word(48)
                values["comp_total_run_time"] = word(56)
                values["pump_i_pwm"] = b(58)
                values["unit_mode_run"] = b(59)
            }
            else -> error("Nieobsługiwany typ powiadomienia")
        }
        return values
    }

    /** One Wi-Fi connection, bounded read-only session, receiving unsolicited notifications too. */
    fun poll(context:Context, pump:LanPump, profile:String, credentials:Pair<ByteArray,ByteArray>?,
        durationSeconds:Int = 30, cancelled:()->Boolean = {false},
        progress:(LanResult,Int)->Unit = {_,_->}):LanResult {
        checkedIp(pump.host)
        require(pump.type==0xc3 && pump.protocol in 2..3)
        require(durationSeconds in 30..180)
        val net=network(context) ?: error("Brak połączenia telefonu z Wi-Fi")
        val steps=mutableListOf("Wi-Fi: połączono", "Transport V${pump.protocol}; profil ${profile}",
            "Sesja ${durationSeconds}s; A0 → X01 → X09 → X05 → X07 → X10 → X0A; nasłuch X04/X05")
        val frames=mutableListOf<LanFrame>()
        val decoded=linkedMapOf<String,Number>()
        val sources=linkedMapOf<String,String>();val times=linkedMapOf<String,String>()
        val families=linkedSetOf<String>()
        var raw=""
        fun snapshot()=LanResult(decoded.toMap(),raw,families.joinToString(" + ").ifEmpty{"brak"},
            steps.toList(),frames.toList(),sources.toMap(),times.toMap())
        val socket=Socket()
        try {
            net.bindSocket(socket);socket.connect(InetSocketAddress(pump.host,pump.port),4000);socket.soTimeout=5000
            steps.add("TCP: połączono")
            val output=socket.getOutputStream();val reader=FrameReader(socket.getInputStream())
            val auth=if(pump.protocol==3){
                val pair=credentials ?: error("Brak kluczy LAN z konta SmartHome")
                AuthV3(pair.first,pair.second).also {
                    output.write(it.encode(pair.first,0));output.flush()
                    it.authenticate(reader.read());steps.add("V3: uwierzytelniono")
                }
            } else null
            socket.soTimeout=1000
            val start=android.os.SystemClock.elapsedRealtime()
            val end=start+durationSeconds*1000L
            // Query order from the real 171H120F midea-lan integration.
            // X09 can time out: allow 5s before moving to the next read-only query.
            // Never have more than one query outstanding on this protocol.
            val schedule=mutableListOf(
                0L to 0xA0, 1500L to 1, 3000L to 9, 8500L to 5,
                10500L to 7, 12500L to 16, 20500L to 10
            )
            if(durationSeconds>30) {
                for(t in 30 until durationSeconds step 30) schedule.add(t*1000L to 1)
                for(t in listOf(37000, 57000, 87000, 117000, 147000, 170000))
                    if(t < durationSeconds*1000L-2000) schedule.add(t.toLong() to 16)
            }
            schedule.sortBy { it.first }
            var next=0;var lastProgress=0L
            val replies=mutableSetOf<Int>()
            while(android.os.SystemClock.elapsedRealtime()<end && !cancelled()) {
                val elapsed=android.os.SystemClock.elapsedRealtime()-start
                if(next<schedule.size && elapsed>=schedule[next].first) {
                    val sub=schedule[next++].second
                    val cmd=if(sub==0xA0) queryAppliance() else queryC3(sub)
                    val label=if(sub==0xA0) "A0" else "X%02X".format(sub)
                    val packet=packV2(pump.id,cmd)
                    output.write(auth?.encode(packet,6) ?: packet);output.flush()
                    frames.add(LanFrame(label,if(sub==0xA0) 0xA0 else 3,sub,
                        cmd.size,true,Crypto.hex(cmd.copyOfRange(0,minOf(cmd.size,12))),
                        cmd.size>12,"TX"))
                    steps.add("Wysłano $label (tylko odczyt)")
                }
                try {
                    val wire=reader.read()
                    val packet=auth?.decode(wire) ?: wire
                    // V2 heartbeat/ack carries no C3 payload.
                    if(packet.size>=6 && (u(packet[2]) or (u(packet[3]) shl 8)) in listOf(0x1001,0x0001)) continue
                    val body=unpackV2(packet,pump.id)
                    val valid=frameValid(body)
                    val mt=if(body.size>9)u(body[9]) else -1
                    val sub=if(body.size>10)u(body[10]) else -1
                    val at=java.time.Instant.now().toString()
                    val label=when(mt) {
                        0xA0 -> "appliance/A0"
                        4 -> "notify/X%02X".format(sub)
                        5 -> "notify2/X%02X".format(sub)
                        else -> "reply/X%02X".format(sub)
                    }
                    // A0 may include identifiers: never export its full payload.
                    val safePrefix=if(mt==0xA0) 11 else if(sub==16) 88 else 72
                    frames.add(LanFrame(label,mt,sub,body.size,valid,
                        Crypto.hex(body.copyOfRange(0,minOf(body.size,safePrefix))),
                        body.size>safePrefix,"RX",at))
                    if(frames.size>600) frames.removeAt(0)
                    if(!valid) { steps.add("Odrzucono błędną sumę/długość C3");continue }
                    if(mt==3 || mt==0xA0) replies.add(if(mt==0xA0) 0xA0 else sub)
                    try {
                        val values:Map<String,Number> = when {
                            sub==1 && mt in listOf(3,4) -> decodeBasicX01(body,profile)
                            sub==16 && mt==3 -> {
                                raw=Crypto.hex(body.copyOfRange(0,minOf(body.size,88)))
                                parseX10(body,profile)
                            }
                            mt==4 && sub in listOf(4,5) -> decodeNotification(body,profile)
                            else -> emptyMap()
                        }
                        if(values.isNotEmpty()) {
                            decoded.putAll(values);families.add(label)
                            values.keys.forEach { sources[it]=label;times[it]=at }
                        }
                        steps.add("$label: ${body.size} B, rozpoznano ${values.size} pól")
                    } catch(e:Exception) { steps.add("$label: błąd dekodera ${e.javaClass.simpleName}; zachowano ramkę") }
                    if(steps.size>300) steps.removeAt(0)
                } catch(_:SocketTimeoutException) {
                    // Keep partial transport bytes and keep listening until the session deadline.
                }
                if(android.os.SystemClock.elapsedRealtime()-lastProgress>=1000) {
                    lastProgress=android.os.SystemClock.elapsedRealtime()
                    progress(snapshot(),((end-lastProgress)/1000).coerceAtLeast(0).toInt())
                }
            }
            for(sub in listOf(0xA0,1,9,5,7,16,10)) if(sub !in replies)
                steps.add((if(sub==0xA0) "A0" else "X%02X".format(sub)) + ": brak pasującej odpowiedzi w tej sesji")
            steps.add(if(cancelled()) "Sesja zatrzymana; zachowano odebrane dane" else "Sesja zakończona")
        } catch(e:Exception) {
            steps.add("Przerwano sesję: ${e.javaClass.simpleName}; zachowano odebrane dane. Ponów nasłuch po ponownym połączeniu.")
        } finally { runCatching{socket.close()} }
        return snapshot()
    }
}

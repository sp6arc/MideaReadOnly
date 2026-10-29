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
    val size: Int, val checksumOk: Boolean, val rawPrefixHex: String, val truncated: Boolean)
internal data class LanResult(val readings: Map<String, Int>, val rawX10: String,
    val responseType: String, val steps: List<String>, val frames: List<LanFrame>)
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
    private fun readExactly(input:InputStream,n:Int):ByteArray {
        require(n in 1..65536){"Błędny rozmiar ramki"}
        val out=ByteArray(n); var p=0
        while(p<n){ val count=input.read(out,p,n-p); if(count<0)throw java.io.EOFException("Przerwane połączenie TCP");p+=count }
        return out
    }
    private fun readFrame(input:InputStream):ByteArray {
        val h=readExactly(input,6)
        if(u(h[0])==0x5a && u(h[1])==0x5a){
            val n=u(h[4]) or (u(h[5]) shl 8)
            require(n in 56..16384){"Niepoprawna długość V2 $n"}
            return h+readExactly(input,n-6)
        }
        if(u(h[0])==0x83 && u(h[1])==0x70){
            val n=((u(h[2]) shl 8) or u(h[3]))+8
            require(n in 8..16384){"Niepoprawna długość V3 $n"}
            return h+readExactly(input,n-6)
        }
        throw IllegalStateException("Nieznana odpowiedź TCP ${Crypto.hex(h).take(12)}")
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
    private fun queryC3(subType:Int):ByteArray {
        require(subType in listOf(1,10,16)){"Tylko dozwolone zapytania C3"}
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
    /** TCP connection on Wi-Fi network only. V3 authenticates with the per-device cloud-issued key.
     * X01 is polled FIRST as a known-successful baseline; X10 may be unsupported or delayed. */
    fun poll(context:Context, pump:LanPump, profile:String, credentials:Pair<ByteArray,ByteArray>?):LanResult {
        checkedIp(pump.host)
        require(pump.type==0xc3){"To nie jest Midea C3"}
        require(pump.protocol in 2..3){"Moduł zgłasza lokalny transport V${pump.protocol}. Obsługujemy TCP V2 i V3; TLS V4 wymaga dalszej implementacji."}
        val net=network(context) ?: throw IllegalStateException("Brak połączenia telefonu z Wi-Fi")
        val steps=mutableListOf("Wi‑Fi: połączono z siecią", "Cel: ${pump.host}:${pump.port} · V${pump.protocol}")
        val frames=mutableListOf<LanFrame>()
        val socket=Socket()
        try {
            net.bindSocket(socket);socket.connect(InetSocketAddress(pump.host,pump.port),4000);socket.soTimeout=5000
            steps.add("TCP: połączono")
            val output=socket.getOutputStream();val input=socket.getInputStream()
            val auth=if(pump.protocol==3){
                val pair=credentials ?: throw IllegalStateException("V3 wymaga tokenu z SmartHome — pobierz dostęp LAN")
                val a=AuthV3(pair.first,pair.second)
                output.write(a.encode(pair.first,0));output.flush()
                a.authenticate(readFrame(input));steps.add("V3: uwierzytelniono");a
            } else null
            val decoded=linkedMapOf<String,Int>()
            var raw=""; var resultType="brak"
            // Warm up the C3 exchange through the successful X01 before trying unit parameters.
            for (sub in listOf(0x01,0x10,0x0a)) {
                val request="X${sub.toString(16).uppercase(java.util.Locale.US).padStart(2,'0')}"
                val q=packV2(pump.id,queryC3(sub))
                var matching=false
                try {
                    output.write(auth?.encode(q,6) ?: q);output.flush()
                    val deadline=android.os.SystemClock.elapsedRealtime()+if(sub==0x10) 10500L else 6500L
                    var received=0
                    while (received<4 && android.os.SystemClock.elapsedRealtime()<deadline && !matching) {
                        val remain=(deadline-android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                        socket.soTimeout=remain.coerceAtMost(6500L).toInt()
                        val wire=readFrame(input)
                        val packet=auth?.decode(wire) ?: wire
                        val body=unpackV2(packet,pump.id)
                        val valid=frameValid(body)
                        val messageType=if(body.size>9)u(body[9]) else -1
                        val typ=if(body.size>10)u(body[10]) else -1
                        frames.add(LanFrame(request,messageType,typ,body.size,valid,
                            Crypto.hex(body.copyOfRange(0,minOf(body.size,88))),body.size>88))
                        received++
                        if(!valid){steps.add("$request: ramka C3, ale nieprawidłowa suma/długość");continue}
                        steps.add("$request: otrzymano C3 0x${messageType.toString(16)}/0x${typ.toString(16)} (${body.size} bajtów)")
                        if(typ==0x01 && messageType in listOf(3,4)) {
                            try {
                                val x01=decodeBasicX01(body,profile)
                                decoded.putAll(x01)
                                if(resultType=="brak") resultType="X01"
                                steps.add("Dekoder X01: ${x01.size} pól podstawowych, bez danych sprężarki")
                            } catch (e:Exception) {steps.add("X01: ${e.message?.take(80)}")}
                        }
                        if(typ==0x10 && messageType==3) {
                            raw=Crypto.hex(body.copyOfRange(0,minOf(body.size,88)))
                            try {
                                val x10=parseX10(body,profile)
                                decoded.putAll(x10);resultType="X10"
                                steps.add("Dekoder X10: ${x10.size} parametrów pracy")
                            }catch(e:Exception){steps.add("X10: ${e.message?.take(80)}")}
                        }
                        matching=(typ==sub && messageType==3)
                        if(!matching) steps.add("$request: odpowiedź nie odpowiada zapytaniu — oczekuję dalej")
                    }
                    if(!matching) steps.add("$request: brak pasującej odpowiedzi")
                }catch(e:SocketTimeoutException){steps.add("$request: brak odpowiedzi (timeout)")}
                catch(e:Exception){steps.add("$request: ${e.message?.take(110) ?: e.javaClass.simpleName}")}
            }
            return LanResult(decoded,raw,resultType,steps,frames)
        }finally{runCatching{socket.close()}}
    }
}

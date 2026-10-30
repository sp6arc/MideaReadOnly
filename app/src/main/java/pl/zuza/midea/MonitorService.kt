package pl.zuza.midea

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.AtomicFile
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the real Midea LAN connection independently of Activity and screen state. */
class MonitorService : Service() {
    companion object {
        private const val CHANNEL="midea_lua_monitor"
        private const val NOTIFICATION=241
        private const val ACTION_START="pl.zuza.midea.START_MONITORING"
        private const val ACTION_STOP="pl.zuza.midea.STOP_MONITORING"

        fun start(context:Context) {
            context.startForegroundService(Intent(context,MonitorService::class.java)
                .setAction(ACTION_START))
        }
        fun stop(context:Context) {
            context.startService(Intent(context,MonitorService::class.java)
                .setAction(ACTION_STOP))
        }

        fun archiveFile(context:Context,deviceId:String):File {
            val digest=MessageDigest.getInstance("SHA-256")
                .digest(deviceId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return File(context.filesDir,"lua_capture_"+digest.take(16)+".json")
        }

        fun archived(context:Context,deviceId:String):String? {
            val atomic=AtomicFile(archiveFile(context,deviceId))
            return try {
                if(atomic.baseFile.exists()) {
                    atomic.openRead().bufferedReader(Charsets.UTF_8).use{it.readText()}
                } else null
            } catch(_:Exception) { null }
        }
    }

    private val worker=Executors.newSingleThreadExecutor()
    private val timer=Executors.newSingleThreadScheduledExecutor()
    private val connecting=AtomicBoolean(false)
    @Volatile private var stopping=false
    @Volatile private var lastAttemptMs=0L
    private var cpuLock:PowerManager.WakeLock?=null
    private var wifiLock:WifiManager.WifiLock?=null

    override fun onBind(intent:Intent?):IBinder?=null

    override fun onCreate() {
        super.onCreate()
        val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL,"MideaRemonte — monitoring pompy",NotificationManager.IMPORTANCE_LOW
        ))
        timer.scheduleWithFixedDelay({
            try {
                if(!stopping && SecurePumpSettings.enabled(this)) {
                    ensureWifi()
                    saveCapture()
                    val py=if(Python.isStarted()) Python.getInstance() else null
                    val snap=py?.getModule("midea_bridge")?.callAttr("snapshot")?.toString()
                    val live=if(snap!=null) JSONObject(snap) else null
                    if((live==null || !live.optBoolean("running")) &&
                        SystemClock.elapsedRealtime()-lastAttemptMs>120_000L) {
                        requestConnect()
                    }
                }
            }catch(_:Exception) {
                // No raw authentication exceptions in public notifications.
            }
        },30,30,TimeUnit.SECONDS)
    }

    private fun notification(text:String):Notification {
        val open=PendingIntent.getActivity(this,241,
            Intent(this,MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,242,
            Intent(this,MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("MideaRemonte — odczyt pompy")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause,"Zatrzymaj",stop)
            .build()
    }

    private fun updateNotice(text:String) {
        val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION,notification(text))
    }

    private fun acquireLocks() {
        if(cpuLock==null) {
            val power=getSystemService(POWER_SERVICE) as PowerManager
            cpuLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MideaRemonte:LanMonitor").apply{
                setReferenceCounted(false)
                acquire()
            }
        }
        if(wifiLock==null) {
            val wifi=getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock=wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "MideaRemonte:WiFi").apply{
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        cpuLock?.let{if(it.isHeld)it.release()}
        wifiLock?.let{if(it.isHeld)it.release()}
        cpuLock=null
        wifiLock=null
    }

    private fun ensureWifi() {
        val cm=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network=cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        } ?: throw IllegalStateException("Brak połączenia Wi-Fi")
        @Suppress("DEPRECATION")
        cm.bindProcessToNetwork(network)
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action) {
            ACTION_STOP -> {
                stopping=true
                SecurePumpSettings.setEnabled(this,false)
                worker.execute{
                    try {
                        saveCapture()
                        if(Python.isStarted()) Python.getInstance()
                            .getModule("midea_bridge").callAttr("stop")
                    }catch(_:Exception){}finally {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_START,null -> {
                if(!SecurePumpSettings.enabled(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                stopping=false
                startForeground(NOTIFICATION,notification("Łączenie z pompą przez Wi-Fi…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                acquireLocks()
                requestConnect()
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun requestConnect() {
        if(stopping || !connecting.compareAndSet(false,true)) return
        lastAttemptMs=SystemClock.elapsedRealtime()
        worker.execute {
            try {
                val cfg=SecurePumpSettings.load(this)
                    ?:throw IllegalStateException("Brak zapisanych danych połączenia")
                ensureWifi()
                val py=PythonEngine.get(this)
                val result=py.getModule("midea_bridge").callAttr(
                    "start_monitor",cfg.host,cfg.port,cfg.deviceId,cfg.model,
                    cfg.protocol,cfg.cloud,cfg.username,cfg.password).toString()
                if(result=="OK") {
                    archived(this,cfg.deviceId)?.let {
                        py.getModule("midea_bridge").callAttr("restore_archive",it)
                    }
                    updateNotice("Monitoring aktywny • zapis co 30 sekund")
                    saveCapture()
                }else{
                    updateNotice("Połączenie niedostępne • automatyczne ponowienie")
                }
            }catch(_:Exception){
                if(!stopping) updateNotice("Brak połączenia • automatyczne ponowienie")
            }finally{connecting.set(false)}
        }
    }

    private fun saveCapture(){
        if(!Python.isStarted())return
        val cfg=SecurePumpSettings.load(this)?:return
        val bridge=Python.getInstance().getModule("midea_bridge")
        val json=bridge.callAttr("export_snapshot").toString()
        if(!JSONObject(json).optBoolean("running"))return
        val atomic=AtomicFile(archiveFile(this,cfg.deviceId))
        var stream:java.io.FileOutputStream?=null
        try {
            stream=atomic.startWrite()
            stream.write(json.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        }catch(_:Exception){
            if(stream!=null) atomic.failWrite(stream)
        }
    }

    override fun onDestroy(){
        stopping=true
        timer.shutdownNow()
        try{saveCapture()}catch(_:Exception){}
        try{
            if(Python.isStarted()) Python.getInstance().getModule("midea_bridge")
                .callAttr("stop")
        }catch(_:Exception){}
        worker.shutdownNow()
        releaseLocks()
        val cm=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.bindProcessToNetwork(null)
        super.onDestroy()
    }
}

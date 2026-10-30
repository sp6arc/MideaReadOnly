package pl.zuza.midea

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import java.io.File
import java.security.MessageDigest
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.Executors

/**
 * MideaRemonte 2.4.1: foreground monitor, encrypted login, durable capture.
 * Original wuwentao/midea-lan transport, V3 authentication and decoder.
 * Only X01, X05 and X07 polls; passive X04 energy notifications.
 */
class MainActivity : Activity() {
    private val work = Executors.newSingleThreadExecutor()
    private val statusWork = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val tiles = mutableMapOf<String, Pair<TextView,TextView>>()
    private lateinit var state: TextView
    private lateinit var version: TextView
    private lateinit var values: TextView
    private lateinit var events: TextView
    private lateinit var frames: TextView
    private lateinit var cloudResult: TextView
    private lateinit var luaValues: TextView
    private lateinit var counters: TextView
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var id: EditText
    private lateinit var model: EditText
    private lateinit var proto: EditText
    private lateinit var account: EditText
    private lateinit var password: EditText
    private lateinit var cloud: Spinner
    private lateinit var found: Spinner
    private var pumpList = JSONArray()
    private val requestSaveLua = 2301
    private val requestSaveDiagnostics = 2402
    private var pendingDiagnosticsJson: String? = null
    @Volatile private var luaDownloadInProgress = false
    private var pendingLuaPath: String? = null
    private var pendingLuaHash: String? = null
    private var luaSavedName: String? = null
    private var active = true
    private var refreshing = false
    private var wifiNetwork: Network? = null
    private var wifiLock: WifiManager.MulticastLock? = null

    private val ink = Color.rgb(24, 40, 60)
    private val muted = Color.rgb(92, 108, 128)
    private val accent = Color.rgb(30, 105, 175)
    private fun px(v:Int) = (resources.displayMetrics.density*v+0.5f).toInt()
    private fun shape(color:Int,radius:Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius=px(radius).toFloat()
    }
    private fun label(root:LinearLayout, text:String, size:Float=13f, bold:Boolean=false):TextView {
        val v=TextView(this)
        v.text=text
        v.textSize=size
        v.setTextColor(if(bold) ink else muted)
        if(bold)v.setTypeface(null,Typeface.BOLD)
        v.setTextIsSelectable(true)
        v.setPadding(0,px(4),0,px(4))
        root.addView(v)
        return v
    }
    private fun heading(root:LinearLayout, text:String) {
        val v=label(root,text,18f,true)
        v.setPadding(0,px(24),0,px(10))
    }
    private fun field(root:LinearLayout, hint:String, initial:String,
                      inputMode:Int=InputType.TYPE_CLASS_TEXT):EditText {
        val v=EditText(this)
        v.hint=hint
        v.setSingleLine(true)
        v.setText(initial)
        v.inputType=inputMode
        v.textSize=14f
        root.addView(v,LinearLayout.LayoutParams(-1,px(50)))
        return v
    }
    private fun button(root:LinearLayout, title:String, action:()->Unit) {
        val v=Button(this)
        v.text=title
        v.isAllCaps=false
        v.setOnClickListener{action()}
        root.addView(v,LinearLayout.LayoutParams(-1,px(50)))
    }
    private fun tile(root:LinearLayout, title:String, key:String) {
        val card=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(px(14),px(12),px(12),px(12))
            background=shape(Color.WHITE,14)
            elevation=px(2).toFloat()
        }
        val titleView=label(card,title,12f)
        titleView.setTextColor(muted)
        val value=label(card,"—",22f,true)
        value.setTextColor(accent)
        val subtitle=label(card,"Brak odebranej ramki",11f)
        tiles[key]=value to subtitle
        val p=LinearLayout.LayoutParams(0,px(116),1f)
        p.setMargins(px(3),px(5),px(3),px(5))
        root.addView(card,p)
    }
    private fun tileRow(root:LinearLayout, a:Pair<String,String>, b:Pair<String,String>) {
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        root.addView(row)
        tile(row,a.first,a.second)
        tile(row,b.first,b.second)
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val outer=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(px(16),px(16),px(16),px(28))
            setBackgroundColor(Color.rgb(245,248,253))
        }
        val scroll=ScrollView(this).apply{isFillViewport=true}
        scroll.addView(outer)
        setContentView(scroll)
        val brand=label(outer,"MideaRemonte 2.4.1",24f,true)
        brand.setTextColor(accent)
        label(outer,"MONITORING POTWIERDZONYCH ODCZYTÓW • TYLKO ODCZYT",11f)
        version=label(outer,"Ładowanie biblioteki Midea…",12f)
        state=label(outer,"Oczekiwanie na połączenie",14f,true)

        heading(outer,"Bieżące dane pompy")
        label(outer,"Wartości z X01/X05/X07 odświeżają się cyklicznie. Dane 04-04 są pokazywane z czasem ostatniego raportu.",12f)
        tileRow(outer,"Ogrzewanie" to "status_heating","Temperatura zewnętrzna" to "outdoor_temperature")
        tileRow(outer,"Strefa 1 — zadana" to "zone1_target","Tryb pompy" to "mode")
        tileRow(outer,"Energia pobrana¹" to "total_energy_consumption","Energia oddana¹" to "total_produced_energy")
        tileRow(outer,"Tryb cichy" to "silent_level","Tryb ECO" to "eco_function_state")
        tileRow(outer,"Błąd sterownika" to "error_code","Strefa 1" to "zone1_power")
        label(outer,"¹ Surowe liczniki z 04-04. Jednostki i skala nie są jeszcze zweryfikowane; nie obliczamy COP.",11f)
        counters=label(outer,"Odebrane pola: 0 · Historia: 0 próbek",13f,true)

        heading(outer,"Diagnostyka serwisowa z Lua — pasywny odczyt")
        label(outer,"Źródło: oryginalny Lua 171H120F. Raporty 04-04 i 04-05 tylko odbieramy; nie wysyłamy nowych poleceń. Wartości surowe, jeśli jednostka nie została potwierdzona.",12f)
        tileRow(outer,"Sprężarka (raw)" to "compRunFreq","Wentylator (x10)" to "fanSpeed")
        tileRow(outer,"Woda wejście" to "tempTwin","Woda wyjście" to "tempTwout")
        tileRow(outer,"Ciśnienie wysokie (raw)" to "pressureHigh","Ciśnienie niskie (raw)" to "pressureLow")
        tileRow(outer,"Pobór chwilowy (raw)" to "SysInstantPower","COP/EER (raw)" to "SysInstantCopEER")
        tileRow(outer,"Odszranianie" to "fgDefrost","Przepływ wody (raw)" to "waterFlow")
        heading(outer,"Połączenie z pompą")
        host=field(outer,"IP pompy (puste = wykryj)", "")
        button(outer,"Wykryj pompę przez Wi-Fi"){discover()}
        found=Spinner(this)
        outer.addView(found)
        found.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,itemId:Long){
                val d=pumpList.optJSONObject(position)?:return
                host.setText(d.optString("host"))
                port.setText(d.optString("port"))
                id.setText(d.optString("id"))
                model.setText(d.optString("model"))
                proto.setText(d.optString("protocol"))
            }
        }
        port=field(outer,"Port TCP", "6444",InputType.TYPE_CLASS_NUMBER)
        id=field(outer,"Device ID", "",InputType.TYPE_CLASS_NUMBER)
        model=field(outer,"Model z wykrywania (171H120F)", "")
        proto=field(outer,"Transport (2 lub 3)", "3",InputType.TYPE_CLASS_NUMBER)
        cloud=Spinner(this)
        outer.addView(cloud)
        cloud.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,
            arrayOf("SmartHome","美的美居","Midea Air","NetHome Plus","Ariston Clima","TSmartLife","Toshiba Iolife"))
        account=field(outer,"Login chmury", "")
        password=field(outer,"Hasło chmury", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        restoreSavedSettings()
        label(outer,"Dane połączenia zapisujemy zaszyfrowane w magazynie kluczy Androida. Po zgaszeniu ekranu monitor nadal działa jako usługa z powiadomieniem. Jeśli system całkowicie wymusi zatrzymanie aplikacji, zapisane próbki pozostaną na telefonie.",12f)
        button(outer,"Uruchom monitoring w tle (X01 / X05 / X07)"){startMonitor()}
        button(outer,"Zatrzymaj monitoring"){stopMonitor()}
        button(outer,"Usuń zapisane dane logowania"){clearSavedSettings()}
        label(outer,"Nie wysyłamy X09, X10, protokołu ITS ani zapytań eksperymentalnych. Nie zmieniamy ustawień pompy.",12f)

        heading(outer,"Raporty odebrane przez Wi-Fi")
        frames=label(outer,"Oczekiwanie na ramki X01/X05/X07 i 04-04…",13f)
        heading(outer,"Plik protokołu Lua z chmury")
        label(outer,"Można pobrać profil dla 171H120F. To metadane protokołu, nie odczyt temperatur na żywo.",12f)
        button(outer,"Sprawdź profil Lua (rozmiar i SHA)"){cloudProfile()}
        button(outer,"Pobierz oryginalny plik Lua i ZAPISZ .lua"){downloadLuaAndSave()}
        label(outer,"Po zapisaniu pliku wybierz w ChatGPT: + → Pliki → wybierz .lua. Pobieramy oryginalny plik bez zmian; nie uruchamiamy jego kodu.",12f)
        cloudResult=label(outer,"Nie sprawdzono pliku Lua",13f)

        heading(outer,"Pola dekodowane na podstawie Lua")
        luaValues=label(outer,"Brak pasywnej ramki 04-04 / 04-05 / 03-10",12f)
        heading(outer,"Wszystkie odebrane pola")
        values=label(outer,"Brak danych",12f)
        heading(outer,"Historia i eksport")
        button(outer,"ZAPISZ diagnostykę Lua + surowe ramki (JSON)"){export()}
        label(outer,"Monitoring zapisuje dane lokalnie co 30 sekund. Eksport działa również po zamknięciu usługi, używając ostatniego automatycznego zapisu.",12f)
        label(outer,"Eksportuje do pliku JSON: dane, ostatnie 800 próbek i pełne bajty odebranych ramek 04-04/04-05 (maks. 30). Ramki 03-10 z numerami seryjnymi nie są eksportowane. Przed publicznym udostępnieniem sprawdź plik.",12f)
        heading(outer,"Zdarzenia")
        events=label(outer,"Brak zdarzeń",11f)

        job("Weryfikacja biblioteki Python"){
            val self=JSONObject(getPython().getModule("midea_bridge").callAttr("selfcheck").toString())
            onUI{
                version.text="wuwentao/midea-lan " + self.optString("version") +
                    " • zapytania X01/X05/X07 • odbiór 04-04"
                state.text="Silnik załadowany; monitoring nieuruchomiony"
            }
        }
    }

    override fun onResume(){
        super.onResume()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick,1000)
    }

    override fun onStop(){
        handler.removeCallbacks(tick)
        super.onStop()
    }

    private fun getPython():Python=PythonEngine.get(this)
    private fun onUI(action:()->Unit){runOnUiThread{if(active)action()}}
    private fun job(name:String,action:()->Unit){
        state.text=name
        work.execute{
            try{action()}catch(ex:Exception){
                val message=ex.message?.take(200)?:ex.javaClass.simpleName
                onUI{
                    state.text="Błąd: $message"
                    Toast.makeText(this,message,Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun bindWifi(){
        val connectivity=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiNetwork=connectivity.allNetworks.firstOrNull{
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        }
        requireNotNull(wifiNetwork){"Telefon musi być połączony przez Wi-Fi z siecią pompy"}
        @Suppress("DEPRECATION")
        connectivity.bindProcessToNetwork(wifiNetwork)
    }
    private fun discover(){
        val ip=host.text.toString().trim()
        job("Wykrywanie urządzeń C3"){
            bindWifi()
            val wifi=getSystemService(WIFI_SERVICE) as WifiManager
            val lock=wifi.createMulticastLock("midearemonte-monitor").apply{setReferenceCounted(false)}
            wifiLock=lock
            lock.acquire()
            try{
                val result=JSONArray(getPython().getModule("midea_bridge").callAttr("scan",ip).toString())
                onUI{
                    pumpList=result
                    val names=(0 until result.length()).map{
                        val d=result.getJSONObject(it)
                        d.optString("host")+":"+d.optString("port")+" • "+d.optString("model")
                    }
                    found.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,names)
                    state.text="Znaleziono pomp C3: "+result.length()
                }
            }finally{
                if(lock.isHeld)lock.release()
                wifiLock=null
            }
        }
    }
    private fun restoreSavedSettings(){
        try {
            val cfg=SecurePumpSettings.load(this)?:return
            host.setText(cfg.host)
            port.setText(cfg.port)
            id.setText(cfg.deviceId)
            model.setText(cfg.model)
            proto.setText(cfg.protocol)
            account.setText(cfg.username)
            password.setText(cfg.password)
            for(i in 0 until cloud.count){
                if(cloud.getItemAtPosition(i).toString()==cfg.cloud){
                    cloud.setSelection(i)
                    break
                }
            }
            state.text=if(SecurePumpSettings.enabled(this))
                "Przywrócono dane • usługa monitoringu była aktywna"
                else "Przywrócono zapisane dane połączenia"
        }catch(_:Exception){
            state.text="Zapisane dane logowania nie mogą zostać odszyfrowane; wpisz je ponownie"
        }
    }

    private fun clearSavedSettings(){
        stopMonitor()
        SecurePumpSettings.clear(this)
        account.setText("")
        password.setText("")
        state.text="Zapisane konto i konfiguracja usunięte z pamięci telefonu"
    }

    private fun startMonitor(){
        val h=host.text.toString().trim()
        val p=port.text.toString().trim()
        val devId=id.text.toString().trim()
        val m=model.text.toString().trim()
        val pr=proto.text.toString().trim()
        val cn=cloud.selectedItem.toString()
        val user=account.text.toString().trim()
        val pass=password.text.toString()
        if(h.isBlank()||devId.isBlank()||m.length<6){
            state.text="Wykryj pompę lub podaj IP, Device ID i model"
            return
        }
        if(pr=="3" && (user.isBlank()||pass.isBlank())){
            state.text="Podaj login i hasło SmartHome dla bezpiecznego połączenia V3"
            return
        }
        try{
            SecurePumpSettings.save(this,PumpSettings(h,p,devId,m,pr,cn,user,pass))
            SecurePumpSettings.setEnabled(this,true)
            if(Build.VERSION.SDK_INT>=33 &&
               checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),241)
            }
            MonitorService.start(this)
            state.text="Uruchomiono usługę monitoringu w tle • obserwuj powiadomienie Androida"
        }catch(ex:Exception){
            SecurePumpSettings.setEnabled(this,false)
            state.text="Nie udało się uruchomić usługi ("+ex.javaClass.simpleName+")"
        }
    }

    private fun stopMonitor(){
        SecurePumpSettings.setEnabled(this,false)
        MonitorService.stop(this)
        state.text="Zlecono zatrzymanie monitoringu (historia na telefonie pozostaje)"
    }
    private fun cloudProfile(){
        val cn=cloud.selectedItem.toString()
        val un=account.text.toString().trim()
        val pw=password.text.toString()
        val devId=id.text.toString().trim()
        job("Sprawdzanie profilu Lua w chmurze"){
            val result=getPython().getModule("midea_bridge").callAttr(
                "cloud_profile",cn,un,pw,devId).toString()
            onUI{state.text="Cloud Lua: $result"}
        }
    }
    private fun downloadLuaAndSave(){
        if(luaDownloadInProgress){
            state.text="Pobieranie lub zapis Lua już trwa"
            return
        }
        val cn=cloud.selectedItem.toString()
        val un=account.text.toString().trim()
        val pw=password.text.toString()
        val devId=id.text.toString().trim()
        if(un.isBlank()||pw.isBlank()||devId.isBlank()){
            state.text="Podaj login chmury, hasło i Device ID"
            return
        }
        luaDownloadInProgress=true
        job("Pobieram oryginalny Lua z chmury Midea"){
            try{
                val response=JSONObject(getPython().getModule("midea_bridge").callAttr(
                    "download_lua_file",cn,un,pw,devId,cacheDir.absolutePath
                ).toString())
                val path=response.getString("path")
                val meta=response.getJSONObject("metadata")
                val hash=meta.getString("luaSha256")
                onUI {
                    pendingLuaPath=path
                    pendingLuaHash=hash
                    try {
                        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type="text/plain"
                            putExtra(Intent.EXTRA_TITLE,"Midea_171H120F_cloud_protocol.lua")
                        }
                        startActivityForResult(intent,requestSaveLua)
                        state.text="Wybierz folder i zatwierdź zapis pliku .lua"
                    } catch(ex:Exception) {
                        File(path).delete()
                        pendingLuaPath=null
                        pendingLuaHash=null
                        luaDownloadInProgress=false
                        state.text="Nie można otworzyć okna zapisu: "+ex.javaClass.simpleName
                    }
                }
            }catch(ex:Exception){
                luaDownloadInProgress=false
                // Do not show raw cloud errors: they can contain identifiers.
                onUI { state.text="Pobranie Lua nieudane ("+ex.javaClass.simpleName+")" }
            }
        }
    }

    @Deprecated("Android document picker result")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==requestSaveDiagnostics){
            val payload=pendingDiagnosticsJson
            pendingDiagnosticsJson=null
            val uri=data?.data
            if(resultCode!=RESULT_OK || uri==null || payload==null){
                state.text="Zapis raportu anulowany"
                return
            }
            work.execute{
                try {
                    val stream=contentResolver.openOutputStream(uri,"w")
                        ?:throw IllegalStateException("Nie można otworzyć pliku")
                    stream.use{it.write(payload.toByteArray(Charsets.UTF_8)); it.flush()}
                    onUI {
                        state.text="Raport Lua / surowe ramki zapisany ("+payload.length+" znaków)"
                        Toast.makeText(this,"Dołącz zapisany plik JSON do rozmowy.",Toast.LENGTH_LONG).show()
                    }
                }catch(ex:Exception){
                    onUI{state.text="Błąd zapisu diagnostyki: "+ex.javaClass.simpleName}
                }
            }
            return
        }
        if(requestCode!=requestSaveLua)return
        val path=pendingLuaPath
        val expected=pendingLuaHash
        pendingLuaPath=null
        pendingLuaHash=null
        val uri=data?.data
        if(resultCode!=RESULT_OK || uri==null || path==null || expected==null){
            if(path!=null)File(path).delete()
            luaDownloadInProgress=false
            state.text="Zapis pliku Lua anulowany"
            return
        }
        state.text="Zapisuję pobrany plik Lua…"
        work.execute {
            val file=File(path)
            try {
                require(file.canonicalFile.parentFile==cacheDir.canonicalFile){
                    "Nieprawidłowy katalog pliku"
                }
                val sha=MessageDigest.getInstance("SHA-256")
                val output=contentResolver.openOutputStream(uri,"w")
                    ?:throw IllegalStateException("Brak dostępu do zapisu pliku")
                var length=0L
                file.inputStream().use { input ->
                    output.use { target ->
                        val buffer=ByteArray(32768)
                        while(true){
                            val n=input.read(buffer)
                            if(n<0)break
                            target.write(buffer,0,n)
                            sha.update(buffer,0,n)
                            length+=n
                        }
                        target.flush()
                    }
                }
                val actual=sha.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
                check(actual.equals(expected,ignoreCase=true)){"Niezgodny SHA-256 pliku Lua"}
                onUI {
                    luaSavedName="Midea_171H120F_cloud_protocol.lua"
                    state.text="Lua zapisany: $length bajtów • SHA-256 zgodny"
                    Toast.makeText(this,"Plik Lua zapisany. Dołącz go tutaj w ChatGPT.",Toast.LENGTH_LONG).show()
                }
            }catch(ex:Exception){
                onUI {
                    state.text="Błąd zapisu Lua: "+ex.javaClass.simpleName
                    Toast.makeText(this,"Nie udało się zapisać pliku Lua",Toast.LENGTH_LONG).show()
                }
            }finally {
                file.delete()
                luaDownloadInProgress=false
            }
        }
    }

    private val tick=object:Runnable{
        override fun run(){
            if(!active)return
            if(!refreshing){
                refreshing=true
                statusWork.execute{
                    try{
                        if(!Python.isStarted())return@execute
                        val bridge=getPython().getModule("midea_bridge")
                        val snap=JSONObject(bridge.callAttr("snapshot").toString())
                        val diagnostics=JSONObject(bridge.callAttr("diagnostic_snapshot").toString())
                        onUI{render(snap,diagnostics)}
                    }catch(_:Exception){}finally{refreshing=false}
                }
            }
            handler.postDelayed(this,3000)
        }
    }
    private fun age(timestamp:String):String{
        if(timestamp.isBlank())return "Brak aktualizacji"
        return try{
            val minutes=Duration.between(OffsetDateTime.parse(timestamp).toInstant(),
                java.time.Instant.now()).toMinutes().coerceAtLeast(0)
            when{
                minutes<1 -> "teraz"
                minutes<60 -> "$minutes min temu"
                else -> "${minutes/60} h ${minutes%60} min temu"
            }
        }catch(_:Exception){"Odczyt: "+timestamp.take(19).replace("T"," ")}
    }
    private fun format(value:Any?):String = when(value){
        null,JSONObject.NULL -> "—"
        is Boolean -> if(value)"TAK" else "NIE"
        else -> value.toString()
    }
    private fun setTile(key:String,display:String,source:String,updatedAt:String) {
        tiles[key]?.let{
            it.first.text=display
            it.second.text=if(updatedAt.isNotEmpty()) "$source • "+age(updatedAt)
                 else "$source • brak raportu"
        }
    }
    private fun render(snap:JSONObject,diag:JSONObject){
        val data=snap.optJSONObject("values")?:JSONObject()
        val times=snap.optJSONObject("updatesAt")?:JSONObject()
        val x01=times.optString("zone1_power","")
        val x04=times.optString("outdoor_temperature","")
        val x05=times.optString("silent_level","")
        val x07=times.optString("eco_function_state","")
        val zone=data.optJSONArray("zone_target_temp")
        val target=if(zone!=null && zone.length()>0)format(zone.opt(0))+"°C" else "—"
        val mode=when(data.optInt("mode",-1)){
            3->"Ogrzewanie"
            2->"Chłodzenie"
            else->format(data.opt("mode"))
        }
        val heat=if(data.has("status_heating"))
            if(data.optBoolean("status_heating")) "AKTYWNE" else "NIEAKTYWNE"
            else "—"
        setTile("status_heating",heat,"Raport 04-04",x04)
        val outdoor=if(data.has("outdoor_temperature"))
            format(data.opt("outdoor_temperature"))+"°C" else "—"
        setTile("outdoor_temperature",outdoor,"Raport 04-04",x04)
        setTile("zone1_target",target,"X01",x01)
        setTile("mode",mode,"X01",x01)
        setTile("zone1_power",format(data.opt("zone1_power")),"X01",x01)
        setTile("silent_level",format(data.opt("silent_level")),"X05",x05)
        setTile("eco_function_state",format(data.opt("eco_function_state")),"X07",x07)
        setTile("error_code",format(data.opt("error_code")),"X01",x01)
        setTile("total_energy_consumption",format(data.opt("total_energy_consumption")),"Raport 04-04 • surowe",x04)
        setTile("total_produced_energy",format(data.opt("total_produced_energy")),"Raport 04-04 • surowe",x04)

        val keys=data.keys().asSequence().toList().sorted()
        values.text=keys.joinToString("\n"){k->k+" = "+format(data.opt(k))}
            .ifEmpty{"Oczekiwanie na potwierdzone wartości…"}
        counters.text="Pola odebrane: ${snap.optInt("presentCount")} / ${snap.optInt("allCount")}"+
            " • Historia: ${snap.optInt("observationsCount")} próbek"
        val stats=diag.optJSONObject("frameStats")?:JSONObject()
        frames.text=listOf("03-01","03-05","03-07","04-03","04-04","04-05","03-10").joinToString("\n"){key->
            val row=stats.optJSONObject(key)
            "$key: ${row?.optInt("count")?:0} pakietów"+
                (row?.optInt("lastBytes",0)?.takeIf{it>0}?.let{" • $it B"}?:"")+
                (row?.optString("lastSeen")?.let{" • "+age(it)}?:"")
        }+"\nPełne ramki do eksportu: "+diag.optInt("rawFramesStored",0)+" (tylko 04-04/04-05)"
        val lua=diag.optJSONObject("luaDecoded")?:JSONObject()
        val luaData=lua.optJSONObject("values")?:JSONObject()
        val luaTimes=lua.optJSONObject("updatesAt")?:JSONObject()
        val luaSources=lua.optJSONObject("sources")?:JSONObject()
        val luaKeys=luaData.keys().asSequence().toList().sorted()
        luaValues.text=luaKeys.joinToString("\n"){ k ->
            val source=luaSources.optString(k,"?")
            k+" = "+format(luaData.opt(k))+"  ["+source+"; "+age(luaTimes.optString(k,""))+"]"
        }.ifEmpty{"Brak ramek z danymi Lua; oczekuję pasywnie na 04-04 lub 04-05"}
        for(key in listOf("compRunFreq","fanSpeed","tempTwin","tempTwout",
                           "pressureHigh","pressureLow","SysInstantPower",
                           "SysInstantCopEER","fgDefrost","waterFlow")){
            val value=if(luaData.has(key))format(luaData.opt(key)) else "—"
            setTile(key,value,"Lua "+luaSources.optString(key,"04-05"),
                    luaTimes.optString(key,""))
        }
        val cloud=diag.optJSONObject("cloud")?:JSONObject()
        val luaMeta=cloud.optJSONObject("values")
        cloudResult.text=when(cloud.optString("status")){
            "received"->"Odebrano profil dla ${luaMeta?.optString("model")}"+
                " • ${luaMeta?.optInt("luaSize")} B\nSHA256: ${luaMeta?.optString("luaSha256")}"
            "error"->"Błąd: "+cloud.optString("note")
            "checking"->"Pobieranie pliku protokołu…"
            else->"Nie sprawdzono"
        } + (luaSavedName?.let{"\nZapisano: "+it+" — dołącz plik do czatu"}?:"")
        val logs=snap.optJSONArray("events")?:JSONArray()
        events.text=(maxOf(0,logs.length()-20) until logs.length()).joinToString("\n"){
            logs.optString(it)
        }
        if(snap.optBoolean("running")){
            state.text=if(snap.optBoolean("available"))
                "Pompa ONLINE • X01/X05/X07 • ${snap.optInt("presentCount")} pól"
            else "Ponowne łączenie z pompą…"
        }
    }
    private fun export(){
        job("Przygotowanie raportu JSON i surowych ramek"){
            val live=getPython().getModule("midea_bridge").callAttr("export_snapshot").toString()
            val cfg=try{SecurePumpSettings.load(this)}catch(_:Exception){null}
            val payload=if(JSONObject(live).optBoolean("running"))live
                else cfg?.let{MonitorService.archived(this,it.deviceId)}?:live
            onUI {
                pendingDiagnosticsJson=JSONObject(payload).toString(2)
                val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type="application/json"
                    putExtra(Intent.EXTRA_TITLE,"MideaRemonte_2.4.1_background_diagnostic.json")
                }
                try {
                    startActivityForResult(intent,requestSaveDiagnostics)
                    state.text="Wybierz folder zapisu raportu JSON"
                }catch(ex:Exception){
                    pendingDiagnosticsJson=null
                    state.text="Nie można otworzyć okna zapisu JSON"
                }
            }
        }
    }
    override fun onDestroy(){
        active=false
        pendingLuaPath?.let{File(it).delete()}
        handler.removeCallbacks(tick)
        work.shutdownNow()
        statusWork.shutdownNow()
        wifiLock?.let{if(it.isHeld)it.release()}
        // A foreground service owns the LAN session and network binding.
        // NEVER stop the device or unbind process Wi-Fi when Activity dies.
        super.onDestroy()
    }
}

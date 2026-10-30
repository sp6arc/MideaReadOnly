package pl.zuza.midea

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
 * MideaRemonte 2.2.0: read-only monitor for confirmed LAN C3 queries.
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
        val brand=label(outer,"MideaRemonte 2.2",24f,true)
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
        button(outer,"Uruchom monitoring (X01 / X05 / X07)"){startMonitor()}
        button(outer,"Zatrzymaj monitoring"){stopMonitor()}
        label(outer,"Nie wysyłamy X09, X10, protokołu ITS ani zapytań eksperymentalnych. Nie zmieniamy ustawień pompy.",12f)

        heading(outer,"Raporty odebrane przez Wi-Fi")
        frames=label(outer,"Oczekiwanie na ramki X01/X05/X07 i 04-04…",13f)
        heading(outer,"Plik protokołu Lua z chmury")
        label(outer,"Można pobrać profil dla 171H120F. To metadane protokołu, nie odczyt temperatur na żywo.",12f)
        button(outer,"Sprawdź plik Lua na moim koncie"){cloudProfile()}
        cloudResult=label(outer,"Nie sprawdzono pliku Lua",13f)

        heading(outer,"Wszystkie odebrane pola")
        values=label(outer,"Brak danych",12f)
        heading(outer,"Historia i eksport")
        button(outer,"Eksportuj odczyty i historię (JSON)"){export()}
        label(outer,"Eksport obejmuje znaczniki czasu, ostatnie wartości i do 800 ostatnich próbek. Bez haseł, tokenów, numerów seryjnych i surowych ramek.",12f)
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
        handler.postDelayed(tick,1800)
    }

    private fun getPython():Python{
        if(!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        return Python.getInstance()
    }
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
        job("Łączenie przez Wi-Fi — tylko działające zapytania"){
            bindWifi()
            val result=getPython().getModule("midea_bridge").callAttr(
                "start_monitor",h,p,devId,m,pr,cn,user,pass).toString()
            onUI{state.text="Monitoring: $result"}
        }
    }
    private fun stopMonitor(){
        job("Zatrzymywanie monitoringu"){
            getPython().getModule("midea_bridge").callAttr("stop")
            onUI{state.text="Monitoring zatrzymany"}
        }
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
        frames.text=listOf("03-01","03-05","03-07","04-04").joinToString("\n"){key->
            val row=stats.optJSONObject(key)
            "$key: ${row?.optInt("count")?:0} pakietów"+
                (row?.optString("lastSeen")?.let{" • "+age(it)}?:"")
        }
        val cloud=diag.optJSONObject("cloud")?:JSONObject()
        val lua=cloud.optJSONObject("values")
        cloudResult.text=when(cloud.optString("status")){
            "received"->"Odebrano profil dla ${lua?.optString("model")}"+
                " • ${lua?.optInt("luaSize")} B\nSHA256: ${lua?.optString("luaSha256")}"
            "error"->"Błąd: "+cloud.optString("note")
            "checking"->"Pobieranie pliku protokołu…"
            else->"Nie sprawdzono"
        }
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
        job("Przygotowanie raportu JSON z historią"){
            val json=getPython().getModule("midea_bridge").callAttr("export_snapshot").toString()
            onUI{
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{
                    type="application/json"
                    putExtra(Intent.EXTRA_TEXT,JSONObject(json).toString(2))
                },"Eksport potwierdzonych odczytów"))
                state.text="Raport gotowy do udostępnienia"
            }
        }
    }
    override fun onDestroy(){
        active=false
        handler.removeCallbacks(tick)
        if(Python.isStarted()){
            try{getPython().getModule("midea_bridge").callAttr("stop")}catch(_:Exception){}
        }
        work.shutdownNow()
        statusWork.shutdownNow()
        wifiLock?.let{if(it.isHeld)it.release()}
        val connectivity=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        connectivity.bindProcessToNetwork(null)
        super.onDestroy()
    }
}

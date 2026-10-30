package pl.zuza.midea

import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Android shell ONLY. Every network packet and every C3 decode comes from
 * unmodified wuwentao/midea-lan Python, pinned in the GitHub Actions build.
 */
class MainActivity : Activity() {
    private val work = Executors.newSingleThreadExecutor()
    private val statusWork = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var events: TextView
    private lateinit var values: TextView
    private lateinit var state: TextView
    private lateinit var version: TextView
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
    private var lastSnapshot = JSONObject()
    private var lastDiagnostic = JSONObject()
    private val diagDisplays = mutableMapOf<String,TextView>()
    private val tabPanes = mutableMapOf<String, LinearLayout>()
    private var selectedTab = "BASE"
    private var active = true
    private var refreshing = false
    private var wifiNetwork: Network? = null
    private var wifiLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val scroll=ScrollView(this)
        val outer=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(px(16),px(18),px(16),px(28))
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        scroll.addView(outer)
        setContentView(scroll)
        heading(outer,"MideaRemonte — silnik autora")
        version=line(outer,"Inicjalizowanie oryginalnej biblioteki Python…",12)
        state=line(outer,"Stan: uruchamianie",14)

        heading(outer,"1. Wykrywanie — oryginalne discover()")
        host=field(outer,"IP pompy (puste = skan sieci)", "")
        button(outer,"Wykryj przez lokalne Wi-Fi") { discover() }
        found=Spinner(this)
        outer.addView(found)
        found.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p:android.widget.AdapterView<*>?){}
            override fun onItemSelected(p:android.widget.AdapterView<*>?,v:android.view.View?,pos:Int,rowId:Long){
                val d=pumpList.optJSONObject(pos)?:return
                host.setText(d.optString("host"))
                port.setText(d.optString("port"))
                id.setText(d.optString("id"))
                model.setText(d.optString("model"))
                proto.setText(d.optString("protocol"))
            }
        }

        heading(outer,"2. Identyfikacja lokalna")
        port=field(outer,"Port TCP", "6444",InputType.TYPE_CLASS_NUMBER)
        id=field(outer,"Device ID", "",InputType.TYPE_CLASS_NUMBER)
        model=field(outer,"Model/SN8 z discover, np. 171H120F", "")
        proto=field(outer,"Wersja transportu (2/3)", "3",InputType.TYPE_CLASS_NUMBER)

        heading(outer,"3. Chmura — wyłącznie logowanie i klucze LAN")
        cloud=Spinner(this)
        outer.addView(cloud)
        cloud.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,
            arrayOf("SmartHome","美的美居","Midea Air","NetHome Plus","Ariston Clima","TSmartLife","Toshiba Iolife"))
        account=field(outer,"Login chmury", "")
        password=field(outer,"Hasło chmury", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        line(outer,"Hasło przechowywane tylko w pamięci podczas działania aplikacji.",12)

        heading(outer,"4. Odczyt — oryginalny MideaDevice.open()")
        button(outer,"URUCHOM silnik autora (LAN)") { startMonitor() }
        button(outer,"Zatrzymaj odczyt") { stopMonitor() }
        button(outer,"Eksportuj raport bez haseł i tokenów") { export() }
        heading(outer,"5. Karty testow diagnostycznych — TYLKO ODCZYT")
        line(outer,"Kazdy test ma osobna karte. Zapytania lokalne wysylaja JEDNA ramke typu QUERY 03, nigdy sterowanie 02. Wyniki sa eksperymentalne: nie zmieniaja ustawien pompy.",12)
        val tabScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=true }
        val tabRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        tabScroll.addView(tabRow)
        outer.addView(tabScroll)
        val tabs=listOf(
            "BASE" to "Baza LAN",
            "X10" to "X10",
            "PUSH" to "04-05",
            "ITS" to "ITS 02",
            "PAGE10" to "2F10",
            "PAGE12" to "2F12",
            "X08" to "X08",
            "X0A" to "X0A",
            "X0D" to "X0D",
            "CLOUD" to "Cloud Lua"
        )
        val panes=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        outer.addView(panes)
        tabs.forEach { (key, title) ->
            val tabButton=Button(this).apply {
                text=title
                textSize=12f
                isAllCaps=false
                setOnClickListener { showTab(key) }
            }
            tabRow.addView(tabButton,LinearLayout.LayoutParams(px(110),px(49)))
            val pane=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                visibility=if(key=="BASE") android.view.View.VISIBLE else android.view.View.GONE
            }
            tabPanes[key]=pane
            panes.addView(pane)
            if(key=="BASE"){
                heading(pane,"Oryginalne parametry z pompy")
                values=line(pane,"Brak odczytow",13)
                heading(pane,"Dziennik biblioteki")
                events=line(pane,"Brak operacji",12)
            } else {
                heading(pane,title)
                val notes=when(key) {
                    "X10" -> "Standard 03-10: odczyt czestotliwosci, T3/T4/Tp i cisnien. Wczesniej timeout."
                    "PUSH" -> "360 sekund nasluchu spontanicznego raportu 04-05. ZERO wysylanych zapytan."
                    "ITS" -> "Eksperymentalne 03-02-01 z innego OEM C3 (iletcomfort). To NIE jest standardowy format M-Thermal."
                    "PAGE10" -> "Eksperymentalna strona 2F10 z modelu 17100007. Rozpoznanie odpowiedzi wymaga zgodnego naglowka."
                    "PAGE12" -> "Eksperymentalna strona 2F12: moc elektryczna z modelu 17100007."
                    "X08" -> "Odczyt konfiguracji INSTALL przez 03-08. Nie wysyla zmian ustawien."
                    "X0A" -> "Odczyt parametrow kontrolera HMI przez 03-0A."
                    "X0D" -> "Diagnostyczny 03-0D, zarejestrowany dla innych kontrolerow C3."
                    else -> "Odczyt profilu Lua z chmury SmartHome, niezalezny od polaczenia LAN. Wymaga Device ID i loginu; NIE jest odczytem telemetrii na zywo."
                }
                line(pane,notes,13)
                button(pane,"Uruchom pojedynczy test: $title"){runDiagnostic(key)}
                val view=line(pane,"Jeszcze nie testowano",13)
                diagDisplays[key]=view
            }
        }
        line(outer,"Eksport zawiera wyniki wszystkich kart. Hasla, tokeny, numery seryjne i pelne ramki nie sa eksportowane.",12)

        job("Weryfikacja pakietu Python") {
            val py=getPython()
            val b=py.getModule("midea_bridge")
            val obj=JSONObject(b.callAttr("selfcheck").toString())
            onUI {
                version.text="Autor: wuwentao/midea-lan · " +
                    obj.optString("version") + " · Python 3.12 · oryginalny X10: " +
                    obj.optString("x10")
                state.text="Silnik źródłowy załadowany"
            }
        }
        handler.postDelayed(tick,1800)
    }

    private fun showTab(key:String){
        selectedTab=key
        tabPanes.forEach { (id,pane) ->
            pane.visibility=if(id==key) android.view.View.VISIBLE else android.view.View.GONE
        }
    }
    private fun runDiagnostic(name:String) {
        job("Test diagnostyczny $name") {
            if(name!="CLOUD") bindWifi()
            val reply=getPython().getModule("midea_bridge").callAttr(
                "diagnostic_probe",name, cloud.selectedItem.toString(),
                account.text.toString().trim(),password.text.toString(),id.text.toString().trim()
            ).toString()
            onUI {
                diagDisplays[name]?.text=reply + "\\nOczekiwanie na odpowiedz; wynik zostanie zaktualizowany automatycznie."
                state.text=reply
            }
        }
    }

    private fun getPython():Python {
        if(!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        return Python.getInstance()
    }
    private fun px(x:Int)=(resources.displayMetrics.density*x+.5f).toInt()
    private fun heading(root:LinearLayout,t:String){
        val v=line(root,t,17)
        v.setTypeface(null,android.graphics.Typeface.BOLD)
        v.setPadding(0,px(22),0,px(10))
    }
    private fun line(root:LinearLayout,t:String,size:Int)=TextView(this).apply{
        text=t;textSize=size.toFloat();setTextColor(android.graphics.Color.rgb(22,32,41))
        setTextIsSelectable(true)
        setPadding(0,px(4),0,px(4))
        root.addView(this)
    }
    private fun field(root:LinearLayout, hintValue:String,default:String,
        inputMode:Int=InputType.TYPE_CLASS_TEXT)=EditText(this).apply{
        hint=hintValue
        setText(default)
        inputType=inputMode
        textSize=14f
        setSingleLine(true)
        root.addView(this,LinearLayout.LayoutParams(-1,px(52)))
    }
    private fun button(root:LinearLayout,t:String,action:()->Unit)=Button(this).apply{
        text=t;isAllCaps=false;setOnClickListener{action()}
        root.addView(this,LinearLayout.LayoutParams(-1,px(49)))
    }
    private fun onUI(doIt:()->Unit){runOnUiThread { if(active) doIt() }}
    private fun job(task:String,doIt:()->Unit){
        state.text=task
        work.execute{
            try{doIt()}catch(ex:Exception){
                val description=ex.message?.take(250)?:ex.javaClass.simpleName
                onUI{state.text="Błąd: $description";Toast.makeText(this,description,Toast.LENGTH_LONG).show()}
            }
        }
    }
    private fun bindWifi(){
        val connectivity=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiNetwork=connectivity.allNetworks.firstOrNull { n ->
            connectivity.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        }
        requireNotNull(wifiNetwork){"Telefon musi być połączony z Wi-Fi pompy"}
        // Native Python sockets use the same selected Wi-Fi as the Android shell.
        @Suppress("DEPRECATION")
        connectivity.bindProcessToNetwork(wifiNetwork)
    }
    private fun discover(){
        val address=host.text.toString().trim()
        job("Oryginalne discover() — 5 sekund"){
            bindWifi()
            val wifi=getSystemService(WIFI_SERVICE) as WifiManager
            val lock=wifi.createMulticastLock("midearemote-upstream").apply{setReferenceCounted(false)}
            wifiLock=lock
            lock.acquire()
            try{
                val json=getPython().getModule("midea_bridge").callAttr("scan",address).toString()
                val list=JSONArray(json)
                onUI{
                    pumpList=list
                    val names=(0 until list.length()).map{
                        val d=list.getJSONObject(it)
                        d.optString("host") + ":" + d.optString("port") +
                            " · " + d.optString("model") + " · V" + d.optString("protocol")
                    }
                    found.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,names)
                    state.text="Oryginalne discover() znalazło: "+list.length()
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
        val idText=id.text.toString().trim()
        val m=model.text.toString().trim()
        val pr=proto.text.toString().trim()
        val cn=cloud.selectedItem.toString()
        val user=account.text.toString().trim()
        val pass=password.text.toString()
        if(h.isBlank()||idText.isBlank()||m.isBlank()){
            state.text="Najpierw wykryj pompę lub wpisz IP, ID i model";return
        }
        if(m.length < 6 || m == p) {
            state.text="Nieprawidłowy model: '$m' — $p to port TCP. Użyj Wykryj i wybierz model np. 171H120F."
            Toast.makeText(this,"Model musi pochodzić z wykrywania pompy, np. 171H120F",Toast.LENGTH_LONG).show()
            return
        }
        job("Uruchamiam oryginalny MideaDevice.run()"){
            bindWifi()
            val msg=getPython().getModule("midea_bridge").callAttr(
                "start_monitor",h,p,idText,m,pr,cn,user,pass).toString()
            onUI{state.text="Silnik autora: $msg"}
        }
    }
    private fun stopMonitor(){
        job("Zamykanie oryginalnego wątku"){
            getPython().getModule("midea_bridge").callAttr("stop")
            onUI{state.text="Odczyt zatrzymany"}
        }
    }
    private val tick=object:Runnable {
        override fun run(){
            if(!active)return
            if(!refreshing){
                refreshing=true
                statusWork.execute{
                    try{
                        if(!Python.isStarted())return@execute
                        val json=getPython().getModule("midea_bridge").callAttr("snapshot").toString()
                        val snap=JSONObject(json)
                        val diag=JSONObject(getPython().getModule("midea_bridge").callAttr("diagnostic_snapshot").toString())
                        lastSnapshot=snap
                        lastDiagnostic=diag
                        onUI{render(snap); renderDiagnostic(diag)}
                    }catch(_:Exception){}finally{refreshing=false}
                }
            }
            handler.postDelayed(this,2500)
        }
    }
    private fun render(snap:JSONObject){
        val data=snap.optJSONObject("values")?:JSONObject()
        val keys=data.keys().asSequence().toList().sorted()
        val items=keys.joinToString("\n"){k ->
            k+" = "+when(val value=data.opt(k)){
                is JSONArray, is JSONObject -> value.toString()
                else -> value?.toString()?:"null"
            }
        }
        values.text="Parametry z oryginalnego dekodera: "+snap.optInt("presentCount")+
            " / "+snap.optInt("allCount")+"\n\n"+
            items.ifEmpty{"Brak nowych danych; silnik autora może nadal próbować połączyć pompę."}
        val recent=snap.optJSONArray("events")?:JSONArray()
        events.text=(0 until recent.length()).joinToString("\n"){recent.optString(it)}
        state.text=if(snap.optBoolean("running"))
            "Silnik uruchomiony · "+(if(snap.optBoolean("available"))"pompa online" else "inicjalizacja/ponowne łączenie")+
                " · protokół wiadomości "+snap.optInt("messageProtocol")
        else state.text.toString()
    }
    private fun renderDiagnostic(diag:JSONObject){
        val tests=diag.optJSONObject("tests")?:JSONObject()
        diagDisplays.forEach { (id,view) ->
            val result=tests.optJSONObject(id)
            if(result!=null){
                val pretty=buildString {
                    append("Metoda: ").append(result.optString("title")).append("\\n")
                    append("Stan: ").append(result.optString("status")).append("\\n")
                    append("Start: ").append(result.optString("started")).append("\\n")
                    append(result.optString("note")).append("\\n")
                    val values=result.optJSONObject("values")?:JSONObject()
                    if(values.length()>0){
                        append("\\nODCZYTANE DANE:\\n")
                        values.keys().asSequence().toList().sorted().forEach { k ->
                            append(k).append(" = ").append(values.opt(k)).append("\\n")
                        }
                    }
                    val response=result.optJSONObject("response")
                    if(response!=null) append("\\nRAMKA: ").append(response.toString()).append("\\n")
                }
                view.text=pretty
            }
        }
    }

    private fun export(){
        val obj=JSONObject(lastSnapshot.toString())
        obj.put("application","MideaRemonte")
        obj.put("diagnostics",lastDiagnostic)
        obj.put("upstream","wuwentao/midea-lan")
        obj.put("upstreamRevision","061df03f27a409bddaa5f5f50e371de5c00098b5")
        val text=obj.toString(2)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{
            type="application/json"
            putExtra(Intent.EXTRA_TEXT,text)
        },"Udostępnij odczyt oryginalnego silnika"))
    }
    override fun onDestroy(){
        active=false
        handler.removeCallbacks(tick)
        if(Python.isStarted()){
            try { getPython().getModule("midea_bridge").callAttr("stop") } catch(_:Exception){}
        }
        work.shutdownNow()
        statusWork.shutdownNow()
        wifiLock?.let{ if(it.isHeld)it.release() }
        val connectivity=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        connectivity.bindProcessToNetwork(null)
        super.onDestroy()
    }
}

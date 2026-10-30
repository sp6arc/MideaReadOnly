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
        heading(outer,"Oryginalne parametry z pompy")
        values=line(outer,"Brak odczytów",13)
        heading(outer,"Dziennik biblioteki")
        events=line(outer,"Brak operacji",12)

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
                        lastSnapshot=snap
                        onUI{render(snap)}
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
    private fun export(){
        val obj=JSONObject(lastSnapshot.toString())
        obj.put("application","MideaRemonte")
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

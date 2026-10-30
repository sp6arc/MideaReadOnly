package pl.zuza.midea

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MideaRemonte: plain-text, read-only diagnostic frontend for a Midea C3 heat pump.
 * No controls, HA, Modbus or extra hardware. Raw frames have identifying tails stripped.
 */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cloud = CloudClient()
    private val cancel = AtomicBoolean(false)
    private val events = mutableListOf<String>()
    private var loggedIn = false
    private var cloudPumps = listOf<Pump>()
    private var localPumps = listOf<LanPump>()
    private var chosenCloud: Pump? = null
    private var chosenLan: LanPump? = null
    private var lastLan: LanResult? = null
    private var lastCloud: JSONObject? = null
    private var lastLua: JSONObject? = null
    private var lastRelay: DiagnosticResult? = null
    private lateinit var store: SecretsStore

    private lateinit var account: EditText
    private lateinit var password: EditText
    private lateinit var remember: CheckBox
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var deviceId: EditText
    private lateinit var protocol: EditText
    private lateinit var model: EditText
    private lateinit var cloudPicker: Spinner
    private lateinit var lanPicker: Spinner
    private lateinit var identity: TextView
    private lateinit var readings: TextView
    private lateinit var cloudReadings: TextView
    private lateinit var frames: TextView
    private lateinit var logView: TextView
    private lateinit var state: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Color.rgb(245, 245, 245)
        window.navigationBarColor = Color.rgb(245, 245, 245)
        store = SecretsStore(this)
        val saved = runCatching { store.load() }.getOrNull()

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(18), px(16), px(28))
            setBackgroundColor(Color.WHITE)
        }
        scroll.addView(content)
        setContentView(scroll)

        title(content, "MideaRemonte")
        label(content, "Diagnostyka pompy C3 — tylko odczyt. Wykrywanie UDP → Token/Key → TCP/V3 → A0 → X01/X09/X05/X07/X10 → powiadomienia.", 13)
        state = label(content, "Stan: niepołączono", 14)

        title(content, "1. Identyfikacja pompy w Wi-Fi")
        button(content, "Wykryj pompy w sieci Wi-Fi") { discover() }
        lanPicker = Spinner(this).also { content.addView(it) }
        lanPicker.onItemSelectedListener = selection { index ->
            chosenLan = localPumps.getOrNull(index)
            chosenLan?.let { lan ->
                host.setText(lan.host); port.setText(lan.port.toString())
                deviceId.setText(lan.id.toString()); protocol.setText(lan.protocol.toString())
                if (lan.model.isNotBlank()) model.setText(lan.model)
                showIdentity()
            }
        }
        host = edit(content, "Adres IP pompy (ręczne połączenie)", "")
        port = edit(content, "Port TCP", "6444", InputType.TYPE_CLASS_NUMBER)
        deviceId = edit(content, "Device ID (liczba)", "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        protocol = edit(content, "Wersja LAN (2 lub 3)", "3", InputType.TYPE_CLASS_NUMBER)
        model = edit(content, "Profil/model (np. 171H120F lub 17100007)", "")
        identity = label(content, "Wykryte urządzenie: brak", 12)

        title(content, "2. Konto chmurowe SmartHome (klucze V3)")
        account = edit(content, "Login SmartHome", saved?.account.orEmpty())
        password = edit(content, "Hasło SmartHome", saved?.password.orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        remember = CheckBox(this).apply {
            text = "Zapamiętaj login i hasło w Android Keystore"
            isChecked = saved != null
        }
        content.addView(remember)
        button(content, "Zaloguj i pobierz listę pomp") { login() }
        cloudPicker = Spinner(this).also { content.addView(it) }
        cloudPicker.onItemSelectedListener = selection { index ->
            chosenCloud = cloudPumps.getOrNull(index)
            chosenCloud?.let {
                if (deviceId.text.isBlank()) deviceId.setText(it.id.toString())
                if (model.text.isBlank() && it.sn8.isNotBlank()) model.setText(it.sn8)
                showIdentity()
            }
        }

        title(content, "3. Diagnostyka lokalna LAN")
        label(content, "Sesja nasłuchuje również powiadomień push X04, X05 i X00/2010/20B0/2F10; nie wysyła komend sterujących.", 12)
        button(content, "Uruchom odczyt LAN – 45 s") { readLan(45) }
        button(content, "Dłuższy nasłuch LAN – 90 s") { readLan(90) }
        button(content, "Zatrzymaj nasłuch") { cancel.set(true); log("Żądanie zatrzymania nasłuchu") }
        readings = label(content, "Brak danych LAN", 13)

        title(content, "4. Odczyt chmury SmartHome")
        button(content, "Odczytaj status z chmury") { readCloud() }
        button(content, "Sprawdź UNITPARA (query_type=16)") { readLua() }
        button(content, "Sprawdź transparentny odczyt X10") { readRelay() }
        cloudReadings = label(content, "Brak danych chmurowych", 13)

        title(content, "5. Odebrane ramki i dziennik")
        button(content, "Udostępnij raport JSON") { exportReport() }
        frames = label(content, "Brak ramek", 11)
        logView = label(content, "Nie wykonano jeszcze operacji", 11)
        log("MideaRemonte 1.0: oczekiwanie na działanie użytkownika")
    }

    private fun px(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun title(parent: LinearLayout, text: String) {
        val item = label(parent, text, 17)
        item.setTextColor(Color.BLACK)
        item.setPadding(0, px(23), 0, px(8))
        item.setTypeface(null, android.graphics.Typeface.BOLD)
    }
    private fun label(parent: LinearLayout, text: String, size: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(Color.rgb(36, 45, 52))
            setTextIsSelectable(true)
            setPadding(0, px(5), 0, px(6))
            parent.addView(this)
        }
    private fun edit(parent: LinearLayout, hintValue: String, initial: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply {
            hint = hintValue
            setText(initial)
            inputType = type
            textSize = 14f
            setSingleLine(true)
            parent.addView(this, LinearLayout.LayoutParams(-1, px(53)))
        }
    private fun button(parent: LinearLayout, textValue: String, action: () -> Unit) {
        Button(this).apply {
            text = textValue
            isAllCaps = false
            setOnClickListener { action() }
            parent.addView(this, LinearLayout.LayoutParams(-1, px(47)))
        }
    }
    private fun selection(callback: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { callback(position) }
    }
    private fun setSpinner(spinner: Spinner, items: List<String>) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
    }
    private fun showIdentity() {
        identity.text = "LAN: " + (chosenLan?.display ?: "ręcznie") +
            " | ID: " + deviceId.text + " | model: " + model.text +
            " | chmura: " + (chosenCloud?.name ?: "nie wybrano")
    }
    private fun log(message: String) {
        runOnUiThread {
            events.add(java.time.LocalTime.now().withNano(0).toString() + " " + message)
            while (events.size > 220) events.removeAt(0)
            logView.text = events.joinToString("\n")
            state.text = "Stan: " + message
        }
    }
    private fun task(name: String, block: () -> Unit) {
        log("START: " + name)
        worker.execute {
            try { block(); log("OK: " + name) }
            catch (t: Throwable) { log("BŁĄD " + name + ": " + (t.message ?: t.javaClass.simpleName)) }
        }
    }
    private fun discover() {
        task("Odkrywanie UDP 6445/20086, odbiór 7083/15000") {
            val list = WifiC3.discover(this)
            runOnUiThread {
                localPumps = list
                setSpinner(lanPicker, list.map { it.display + " · " + it.model })
                if (list.isEmpty()) identity.text = "Nie znaleziono C3; można wprowadzić IP/ID ręcznie."
            }
            log("Znaleziono urządzeń C3: " + list.size)
        }
    }
    private fun login() {
        val u = account.text.toString().trim()
        val p = password.text.toString()
        val storePassword = remember.isChecked
        if (u.isBlank() || p.isBlank()) { log("Podaj login i hasło"); return }
        task("Logowanie do SmartHome") {
            cloud.login(u, p)
            val pumps = cloud.pumps()
            loggedIn = true
            if (storePassword) store.save(SavedLogin(u, p, null)) else store.forget()
            runOnUiThread {
                cloudPumps = pumps
                setSpinner(cloudPicker, pumps.map { it.name + " | ID " + it.id + " | SN8 " + it.sn8 })
            }
            log("Zalogowano; znaleziono pomp C3: " + pumps.size)
        }
    }
    private fun configuredLan(): LanPump {
        val ip = WifiC3.checkedIp(host.text.toString().trim())
        val id = deviceId.text.toString().trim().toLong()
        val pr = protocol.text.toString().trim().toInt()
        require(id > 0 && pr in 2..3) { "Sprawdź deviceId i wersję LAN" }
        return LanPump(ip, port.text.toString().trim().toInt(), id, 0xc3, pr, model.text.toString().trim())
    }
    private fun readLan(seconds: Int) {
        val chosen = runCatching { configuredLan() }.getOrElse { log(it.message ?: "Nieprawidłowe parametry LAN"); return }
        val profile = model.text.toString().trim().ifBlank { chosenCloud?.sn8.orEmpty() }
        cancel.set(false)
        task("LAN " + seconds + " s; model " + profile) {
            val keys = if (chosen.protocol == 3) {
                check(loggedIn) { "Dla V3 zaloguj się do SmartHome w kroku 2" }
                log("Pobieram token/key z chmury dla ID " + chosen.id)
                cloud.lanKeys(chosen.id)
            } else null
            log("Łączenie TCP i handshake V" + chosen.protocol)
            val result = WifiC3.poll(this, chosen, profile, keys, seconds, { cancel.get() }) { partial, left ->
                runOnUiThread {
                    lastLan = partial
                    displayLan(partial, left)
                }
            }
            runOnUiThread { lastLan = result; displayLan(result, 0) }
            log("Odebrano " + result.frames.count { it.direction == "RX" } +
                " ramek; rozpoznano " + result.readings.size + " parametrów")
        }
    }
    private fun displayLan(result: LanResult, secondsLeft: Int) {
        val values = result.readings.toSortedMap().entries.joinToString("\n") { (key, value) ->
            key + " = " + value + "   [" + (result.fieldSources[key] ?: "unknown") + "]"
        }
        readings.text = "Parametry LAN: " + result.readings.size +
            " | ramki: " + result.frames.size + " | pozostało: " + secondsLeft + " s\n" +
            (values.ifBlank { "Brak rozpoznanych danych, przejrzyj odebrane ramki." })
        frames.text = result.frames.takeLast(100).joinToString("\n") { frame ->
            frame.receivedAt + " " + frame.direction + " " + frame.request +
                " type=" + frame.messageType + " bytes=" + frame.size +
                " checksum=" + frame.checksumOk + " HEX=" + frame.rawPrefixHex +
                (if (frame.truncated) " [ucięto dane identyfikacyjne]" else "")
        } + "\n\nPRZEBIEG:\n" + result.steps.takeLast(150).joinToString("\n")
    }
    private fun selectedPump(): Pump = chosenCloud ?: error("Wybierz pompę z konta SmartHome")
    private fun displayCloud(data: JSONObject, label: String) {
        val snapshot = Telemetry.capture(data)
        val txt = snapshot.fields.sortedBy { it.path }.joinToString("\n") {
            it.path + " = " + it.shownValue
        }
        runOnUiThread {
            cloudReadings.text = label + " | pól: " + snapshot.fields.size + "\n" + txt
        }
    }
    private fun readCloud() = task("Status chmury") {
        val result = cloud.status(selectedPump())
        lastCloud = Telemetry.safeValues(result)
        displayCloud(result, "Chmura – status")
    }
    private fun readLua() = task("Chmura UNITPARA / query_type=16") {
        val result = cloud.unitDiagnosticsLua(selectedPump())
        lastLua = Telemetry.safeValues(result)
        displayCloud(result, "Chmura – UNITPARA")
    }
    private fun readRelay() = task("Chmura transparentne X10") {
        val result = cloud.diagnostic(selectedPump())
        lastRelay = result
        runOnUiThread {
            cloudReadings.text = "X10 chmura | dekoder poprawnego profilu: " + result.profileDecoded +
                "\n" + result.readings.toSortedMap().entries.joinToString("\n") { it.key + " = " + it.value } +
                "\nSurowy hex odpowiedzi: " + result.rawFrameHex.take(176)
        }
    }
    private fun exportReport() {
        val data = JSONObject()
        data.put("app", "MideaRemonte")
        data.put("timestamp", java.time.Instant.now().toString())
        data.put("model", model.text.toString())
        val lan = lastLan
        if (lan != null) {
            data.put("lanValues", JSONObject(lan.readings))
            data.put("lanSources", JSONObject(lan.fieldSources))
            data.put("lanSteps", JSONArray(lan.steps))
            data.put("lanFrames", JSONArray().apply {
                lan.frames.forEach {
                    put(JSONObject().put("direction", it.direction).put("request", it.request)
                        .put("messageType", it.messageType).put("length", it.size)
                        .put("valid", it.checksumOk).put("hexPrefix", it.rawPrefixHex)
                        .put("truncated", it.truncated))
                }
            })
        }
        lastCloud?.let { data.put("cloudStatus", it) }
        lastLua?.let { data.put("cloudUnitpara", it) }
        lastRelay?.let {
            data.put("cloudRelayDecoded", JSONObject(it.readings))
            data.put("cloudRelayRawPrefix", it.rawFrameHex.take(176))
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, data.toString(2))
        }
        startActivity(Intent.createChooser(intent, "Udostępnij raport"))
    }
    override fun onDestroy() {
        cancel.set(true)
        worker.shutdownNow()
        cloud.clear()
        super.onDestroy()
    }
}

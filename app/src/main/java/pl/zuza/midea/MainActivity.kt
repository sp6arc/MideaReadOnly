package pl.zuza.midea

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executors

/** Offline-dependency-free Android UI inspired by Home Assistant's sections and tiles.
 * It DOES NOT connect to Home Assistant; all Midea requests are read-only HTTPS. */
class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val cloud = CloudClient()
    private lateinit var secrets: SecretsStore
    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private var busy = false
    private var selected: Pump? = null
    private var devices = listOf<Pump>()
    private var snapshot = Telemetry.capture(JSONObject())
    private var source = "brak"
    private var fetchedAt = ""
    private var rawX10 = ""
    private var luaSnapshot = Telemetry.capture(JSONObject())
    private var luaFetchedAt = ""
    private var lastCode: Int? = null
    private var errorEndpoint: String? = null
    private var errorAt = ""
    private var feedback = ""
    private var lastStatus = -60_000L
    private var lastDiagnostic = -60_000L
    private var exportText = ""
    private var currentAccount = ""
    private var savedPassword = ""
    private var rememberLogin = true
    private var rememberedPumpId: Long? = null
    private var tab = 0 // 0 panel, 1 cloud, 2 Wi-Fi, 3 comparison, 4 settings
    private var localPump: LanPump? = null
    private var lanDiscoveries = emptyList<LanPump>()
    private var lanSnapshot = Telemetry.capture(JSONObject())
    private var lanFetchedAt = ""
    private var lanRawX10 = ""
    private var lanFrames = emptyList<LanFrame>()
    private var lanResultType = "brak"
    private var lanSteps = listOf<String>()
    private var lanStatus = "Nie testowano połączenia Wi-Fi"
    private var cloudChecks = mutableListOf<String>()
    private var lastWifiIp = ""
    private var localProtocol = 3
    private var lastWifiRead = -60_000L

    private var onlyUnknown = false
    private var searchQuery = ""

    private val ink = Color.rgb(28, 46, 62)
    private val muted = Color.rgb(105, 121, 136)
    private val blue = Color.rgb(28, 119, 203)
    private val bluePale = Color.rgb(229, 241, 253)
    private val background = Color.rgb(245, 247, 250)
    private val warning = Color.rgb(166, 68, 38)
    private val white = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) // screenshots always enabled
        window.statusBarColor = background
        window.navigationBarColor = background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        secrets = SecretsStore(this)
        val saved = secrets.load()
        if (saved == null) loginScreen()
        else {
            currentAccount = saved.account
            savedPassword = saved.password
            rememberLogin = true
            rememberedPumpId = saved.pumpId
            baseScreen("Midea M-Thermal", "Przywracanie połączenia SmartHome")
            card(root) { c -> caption(c, "Zapisane konto jest szyfrowane lokalnie. Łączenie z Midea…") }
            connect(saved.account, saved.password, true, saved.pumpId, auto = true)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun shape(color: Int, radius: Int = 18, borderColor: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (borderColor != null) setStroke(dp(1), borderColor)
    }
    private fun spacing(parent: LinearLayout, h: Int = 12) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))
    }
    private fun label(parent: LinearLayout, value: String, size: Int = 16, color: Int = ink,
                      bold: Boolean = false, padBottom: Int = 0): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(0, 0, 0, dp(padBottom))
        setTextIsSelectable(true)
        parent.addView(this)
    }
    private fun caption(parent: LinearLayout, value: String) = label(parent, value, 13, muted)
    private fun title(parent: LinearLayout, value: String) { label(parent, value, 19, ink, true, 10) }

    private fun card(host: LinearLayout, paint: Int = white, content: (LinearLayout) -> Unit): LinearLayout {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = shape(paint)
            elevation = dp(1).toFloat()
        }
        val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        p.bottomMargin = dp(12)
        host.addView(holder, p)
        content(holder)
        return holder
    }
    private fun action(parent: LinearLayout, value: String, primary: Boolean = false,
                       destructive: Boolean = false, onClick: () -> Unit): TextView {
        val view = TextView(this).apply {
            text = value
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minimumHeight = dp(48)
            setTextColor(if (primary) white else if (destructive) warning else blue)
            background = shape(if (primary) blue else if (destructive) Color.rgb(255, 240, 235) else bluePale, 13)
            isClickable = !busy
            alpha = if (busy) 0.6f else 1f
            setOnClickListener { if (!busy) onClick() }
        }
        parent.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return view
    }
    private fun twoActions(parent: LinearLayout, left: String, right: String, l: () -> Unit, r: () -> Unit) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        parent.addView(line)
        val a = action(line, left, true, onClick = l)
        val b = action(line, right, onClick = r)
        a.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(7) }
        b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(7) }
    }
    private fun baseScreen(head: String, sub: String) {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(this@MainActivity.background)
            fitsSystemWindows = true
            clipToPadding = false
            isFillViewport = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(24), dp(17), dp(30))
        }
        scroll.addView(root)
        setContentView(scroll)
        label(root, "MIDEA  •  MONITOR", 12, blue, true, 8)
        label(root, head, 27, ink, true, 6)
        caption(root, sub)
        spacing(root, 16)
        statusText = label(root, feedback, 13, if (lastCode == null) muted else warning)
        if (feedback.isNotBlank()) spacing(root, 9)
    }

    private fun loginScreen(account: String = "", password: String = "", message: String = "") {
        selected = null
        devices = emptyList()
        feedback = message
        lastCode = null
        baseScreen("Twoja pompa ciepła", "Chmura Midea SmartHome • oryginalny moduł Wi-Fi • tylko odczyt")
        card(root) { c ->
            title(c, "Zaloguj się")
            caption(c, "Konto SmartHome, na którym jest zarejestrowana pompa.")
            spacing(c, 12)
            val email = EditText(this).apply {
                hint = "E-mail / login SmartHome"; setText(account)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                textSize = 16f; isSingleLine = true
            }
            val pass = EditText(this).apply {
                hint = "Hasło"; setText(password)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                textSize = 16f; isSingleLine = true
            }
            c.addView(email); c.addView(pass)
            val remember = CheckBox(this).apply {
                text = "Zapamiętaj konto i wybraną pompę"
                textSize = 14f; isChecked = rememberLogin
                setTextColor(ink)
            }
            c.addView(remember)
            caption(c, "Hasło zapisuje się wyłącznie po udanym logowaniu, zaszyfrowane przez Android Keystore.")
            spacing(c, 10)
            action(c, "Połącz z Midea", primary = true) {
                val user = email.text.toString().trim()
                val secret = pass.text.toString()
                if (user.isBlank() || secret.isBlank()) {
                    statusText.text = "Wpisz login oraz hasło."; return@action
                }
                val previousPump = if (user == currentAccount) rememberedPumpId else null
                connect(user, secret, remember.isChecked, previousPump, auto = false)
            }
        }
        card(root, bluePale) { c ->
            title(c, "Co otrzymasz?")
            label(c, "• Panel parametrów na wzór Home Assistant", 14)
            label(c, "• Dostęp do wszystkich odebranych pól", 14)
            label(c, "• Zrzuty ekranu i eksport JSON", 14)
            label(c, "• Brak poleceń sterujących", 14)
        }
    }

    private fun connect(account: String, password: String, remember: Boolean, preferred: Long?, auto: Boolean) {
        runTask("Łączenie z kontem SmartHome…", {
            try {
                cloud.login(account, password)
                cloud.pumps()
            } catch (e: Exception) { cloud.clear(); throw e }
        }, { found ->
            devices = found
            currentAccount = account
            rememberLogin = remember
            rememberedPumpId = preferred
            savedPassword = if (remember) password else ""
            if (remember) {
                try { secrets.save(SavedLogin(account, password, preferred)) }
                catch (_: Exception) { feedback = "Połączono, ale nie udało się bezpiecznie zapisać logowania." }
            } else secrets.forget()
            if (devices.isEmpty()) {
                loginScreen(account, "", "Konto działa, ale nie znaleziono urządzenia C3.")
            } else {
                val chosen = devices.firstOrNull { it.id == preferred } ?: devices.singleOrNull()
                if (chosen == null) chooseScreen() else choose(chosen, initialRead = true)
            }
        }, { error ->
            // Do not delete saved credentials on transient network failures.
            val msg = errorMessage(error)
            loginScreen(account, if (auto) password else "", msg)
        })
    }

    private fun chooseScreen() {
        feedback = ""
        baseScreen("Wybierz pompę", "Urządzenia Midea C3 przypisane do Twojego konta")
        devices.forEach { p ->
            card(root) { c ->
                label(c, p.name, 19, ink, true, 5)
                caption(c, "Profil: ${p.sn8}  •  Status z listy: ${if (p.online) "online" else "offline"}")
                spacing(c, 12)
                action(c, "Otwórz panel pompy") { choose(p, initialRead = true) }
            }
        }
        card(root) { c -> action(c, "Wyloguj i zapomnij dane", destructive = true) { logout() } }
    }

    private fun choose(p: Pump, initialRead: Boolean) {
        selected = p
        snapshot = Telemetry.capture(JSONObject())
        source = "brak"; fetchedAt = ""; rawX10 = ""
        luaSnapshot = Telemetry.capture(JSONObject()); luaFetchedAt = ""
        lanSnapshot = Telemetry.capture(JSONObject());lanFetchedAt = "";lanRawX10="";lanSteps=emptyList()
        lanDiscoveries=emptyList();localPump=null;lanStatus="Nie testowano połączenia Wi-Fi";lanFrames=emptyList();lanResultType="brak"
        cloudChecks.clear()
        lastCode = null; errorEndpoint = null; errorAt = ""; feedback = ""
        lastStatus = -60_000L; lastDiagnostic = -60_000L;lastWifiRead=-60_000L
        onlyUnknown = false; searchQuery = ""; tab = 0
        rememberedPumpId = p.id
        if (rememberLogin && savedPassword.isNotBlank()) {
            try { secrets.save(SavedLogin(currentAccount, savedPassword, p.id)) }
            catch (_: Exception) { feedback = "Wybrano pompę, ale nie zapisano wyboru na urządzeniu." }
        }
        dashboard()
        if (initialRead) fetchStatus()
    }

    private fun cloudValue(key: String): String? = snapshot.values.optString(key, "").takeIf { it.isNotBlank() }
    private fun temp(key: String): String = cloudValue(key)?.let { v ->
        if (v.matches(Regex("-?[0-9]+([.][0-9]+)?"))) "$v °C" else "—"
    } ?: "—"
    private fun diagValue(vararg keys: String): String = keys.firstNotNullOfOrNull { name ->
        luaSnapshot.values.optString(name, "").takeIf { it.isNotBlank() }
    } ?: "—"
    private fun state(key: String): String = when (cloudValue(key)?.lowercase()) {
        "on" -> "Włączone"
        "off" -> "Wyłączone"
        else -> "Nieznane"
    }
    private fun modeName(): String = when (cloudValue("run_mode_set")) {
        "heat" -> "OGRZEWANIE"
        "cool" -> "CHŁODZENIE"
        "auto" -> "AUTO"
        else -> "TRYB NIEZNANY"
    }
    private fun fieldRow(parent: LinearLayout, name: String, value: String, dim: Boolean = false) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        label(line, name, 13, muted).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        label(line, value, 14, if (dim) muted else ink, true)
    }
    private fun metricTile(host: LinearLayout, heading: String, value: String, footer: String, accent: Int = blue) {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(15), dp(11), dp(14))
            minimumHeight = dp(125)
            background = shape(white, 18)
            elevation = dp(1).toFloat()
        }
        host.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4); rightMargin = dp(4); bottomMargin = dp(8) })
        label(tile, heading, 12, muted, false, 9)
        label(tile, value, 24, accent, true, 6)
        label(tile, footer, 11, muted)
    }
    private fun metricPair(firstName: String, firstValue: String, firstNote: String,
                           secondName: String, secondValue: String, secondNote: String,
                           firstAccent: Int = blue, secondAccent: Int = blue) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row)
        metricTile(row, firstName, firstValue, firstNote, firstAccent)
        metricTile(row, secondName, secondValue, secondNote, secondAccent)
    }
    private fun section(name: String, subtitle: String = "") {
        spacing(root, 13)
        label(root, name, 19, ink, true, 6)
        if (subtitle.isNotBlank()) caption(root, subtitle)
        spacing(root, 8)
    }
    private fun hero(pump: Pump) {
        val deep = Color.rgb(10, 78, 151)
        val pale = Color.rgb(209, 237, 255)
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(13, 124, 200), deep)).apply { cornerRadius = dp(25).toFloat() }
            elevation = dp(3).toFloat()
        }
        root.addView(holder, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(15) })
        label(holder, "M-THERMAL   R32   •   ${pump.sn8}", 12, pale, true, 10)
        label(holder, modeName(), 26, white, true, 5)
        label(holder, "Tryb wybrany na sterowniku, nie potwierdzenie pracy sprężarki", 11, pale)
        spacing(holder, 18)
        val mainRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        holder.addView(mainRow)
        val target = temp("zone1_temp_set")
        val circle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(20,255,255,255))
                setStroke(dp(3), Color.argb(175,255,255,255))
            }
        }
        mainRow.addView(circle, LinearLayout.LayoutParams(dp(158), dp(158)))
        label(circle, "ZADANA · OBIEG 1", 10, pale, true, 6)
        label(circle, target, if (target.length > 6) 31 else 35, white, true)
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        mainRow.addView(side, LinearLayout.LayoutParams(0, -2, 1f))
        label(side, "●  ${if (pump.online) "ONLINE" else "OFFLINE"}", 12, pale, true, 9)
        label(side, "Obieg 1", 12, pale)
        label(side, state("zone1_power_state"), 17, white, true, 11)
        label(side, "CWU", 12, pale)
        label(side, state("dhw_power_state"), 17, white, true)
        spacing(holder, 17)
        val foot = "Odczyt: " + (if (fetchedAt.isBlank()) "brak" else fetchedAt.replace('T', ' ').take(19) + " UTC")
        label(holder, foot, 12, pale)
    }

    private fun dashboard() {
        val pump = selected ?: return
        baseScreen(pump.name, "Twoja pompa ciepła  •  Midea SmartHome  •  tylko podgląd")
        hero(pump)
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = shape(Color.rgb(231, 238, 246), 16)
        }
        val tabScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false;addView(tabs) }
        root.addView(tabScroll, LinearLayout.LayoutParams(-1, dp(54)))
        listOf("Panel", "Chmura", "Wi-Fi", "Porównaj", "Opcje").forEachIndexed { index, name ->
            val cell = TextView(this).apply {
                text = name
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT, if (tab == index) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (tab == index) blue else muted)
                background = shape(if (tab == index) white else Color.TRANSPARENT, 13)
                setOnClickListener { if (!busy) { tab = index; dashboard() } }
            }
            tabs.addView(cell, LinearLayout.LayoutParams(dp(87), -1))
        }
        spacing(root, 12)
        if (lastCode != null) {
            card(root, Color.rgb(255, 241, 236)) { c ->
                label(c, "Diagnostyka chmury: $lastCode", 15, warning, true, 4)
                label(c, "Błąd zapytania ${errorEndpoint ?: "API"}. Nie jest to kod awarii pompy.", 12, warning)
                if (tab == 1) caption(c, "Ostatni poprawny odczyt pozostaje dostępny. $errorAt")
            }
        }
        when (tab) {
            0 -> overview(pump)
            1 -> rawData(pump)
            2 -> wifiTab(pump)
            3 -> comparisonTab(pump)
            else -> settings(pump)
        }
        if (feedback.isNotBlank()) {
            statusText.text = feedback
            statusText.setTextColor(if (lastCode == null) muted else warning)
        }
    }

    private fun overview(pump: Pump) {
        if (snapshot.fields.isEmpty()) {
            card(root) { c ->
                title(c, "Brak odczytów")
                caption(c, "Odczyt SmartHome zostanie pobrany po połączeniu.")
                spacing(c, 12)
                action(c, "↻  Pobierz dane", primary = true) { fetchStatus() }
            }
            return
        }
        if (luaSnapshot.fields.isNotEmpty()) {
            section("Aktualna praca urządzenia", "Odczyt diagnostyczny Lua 0x10 — wartości surowe")
            metricPair("Sprężarka · Hz (surowe)", diagValue("compRunFreq", "comp_run_freq"), "Tylko gdy chmura odda parametr",
                "Zasilanie Tw_out", diagValue("tempTwout", "temp_tw_out"), "Surowa temperatura")
            metricPair("Powrót Tw_in", diagValue("tempTwin", "temp_tw_in"), "Surowa temperatura",
                "Wymiennik T3", diagValue("tempT3", "temp_t3"), "Surowa temperatura")
        }
        section("Ciepła woda użytkowa", "Temperatura zasobnika a nastawa")
        metricPair("Woda w zasobniku", temp("tank_actual_temp"), "Odczyt SmartHome",
            "Temperatura zadana", temp("dhw_temp_set"), "Nastawa CWU")
        card(root, bluePale) { c ->
            fieldRow(c, "CWU — włączona", state("dhw_power_state"))
            fieldRow(c, "Funkcja CWU dopuszczona", state("dhw_enable"))
            caption(c, "Stan funkcji i aktualne grzanie wody to dwie różne informacje. Nie mamy sygnału faktycznej pracy sprężarki.")
        }
        section("Obiegi grzewcze", "Nastawy i konfiguracja, nie temperatura zasilania")
        metricPair("Obieg 1 · grzejniki", temp("zone1_temp_set"), state("zone1_power_state") + " · zadana",
            "Obieg 2 · podłogówka", temp("zone2_temp_set"), state("zone2_power_state") + " · zadana",
            blue, Color.rgb(121, 137, 156))
        card(root) { c ->
            fieldRow(c, "Dwa obiegi w konfiguracji", state("double_zone_enable"))
            fieldRow(c, "Krzywa pogodowa obieg 1", state("zone1_curve_state"))
            fieldRow(c, "Krzywa pogodowa obieg 2", state("zone2_curve_state"))
            caption(c, "Odczyt 46 °C w obiegu 2 jest zapamiętaną nastawą. Obieg 2 jest wyłączony, a funkcja dwóch obiegów nieaktywna.")
        }
        section("Komfort i ustawienia")
        metricPair("Temperatura pokojowa", temp("room_temp_set"), "Zadana · nie pomiar",
            "Kod błędu pompy", cloudValue("error_code") ?: "—", "Wartość sterownika",
            blue, if (cloudValue("error_code") == "0") Color.rgb(26, 145, 103) else warning)
        card(root) { c ->
            fieldRow(c, "ECO", state("eco_on_state"))
            fieldRow(c, "Tryb cichy", state("silence_on_state"))
            fieldRow(c, "Tryb urlopowy", state("holiday_on_state"))
            fieldRow(c, "Szybkie CWU", state("fastdhw_state"))
        }
        section("Odczyt i połączenie")
        card(root) { c ->
            label(c, "${snapshot.fields.size} pól odebranych  •  ${snapshot.recognized} opisanych", 15, ink, true, 6)
            caption(c, "Dane ze status/lua/get. Czas pomiaru w urządzeniu jest nieznany.")
            caption(c, "Jednostki °C są interpretacją nazw pól i wymagają porównania ze sterownikiem.")
            spacing(c, 13)
            action(c, "↻  Odśwież odczyt", primary = true) { fetchStatus() }
            spacing(c, 8)
            action(c, "Pełna diagnostyka i pola surowe") { tab = 1; dashboard() }
        }
    }

    private fun showTiles(header: String, fields: List<TelemetryField>) {
        title(root, header)
        fields.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            root.addView(row)
            pair.forEach { field ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(14), dp(10), dp(14))
                    background = shape(white, 14)
                    minimumHeight = dp(118)
                }
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    bottomMargin = dp(10); rightMargin = dp(5)
                }
                row.addView(tile, params)
                label(tile, field.recognizedLabel ?: field.key, 12, muted, false, 5)
                label(tile, field.shownValue.take(28), 25, blue, true, 4)
                caption(tile, "Surowe • ${field.key}")
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
        spacing(root, 6)
    }

    private fun rawData(pump: Pump) {
        card(root, bluePale) { c ->
            title(c, "Diagnostyka układu chłodniczego")
            label(c, "Sprężarka · wymienniki · ciśnienia", 14, ink, true, 7)
            caption(c, "W obecnym status/lua/get nie ma częstotliwości sprężarki, temperatur T2/T2B/T3/TP/TH ani ciśnień. Nie podstawiamy w ich miejsce nastaw.")
            spacing(c, 10)
            action(c, "Odczytaj parametry serwisowe — Lua 0x10", primary = true) { fetchLuaDiagnostic() }
            caption(c, "Eksperymentalne zapytanie odczytowe query_type=16 przez działające API status/lua/get. Nie zmienia nastaw.")
            spacing(c, 9)
            action(c, "X10  •  Stary test transparent/send") { fetchDiagnostic() }
            caption(c, "Zapytanie X10 może zwrócić błąd API 9999. Nie oznacza to alarmu pompy.")
        }
        if (luaSnapshot.fields.isNotEmpty()) {
            card(root) { c ->
                title(c, "Odczyt serwisowy Lua 0x10")
                caption(c, "Odebrano ${luaSnapshot.fields.size} pól • ${luaFetchedAt.take(19)} UTC. Poniżej bez domyślania jednostek.")
                val technical = listOf(
                    "Sprężarka — częstotliwość" to diagValue("compRunFreq", "comp_run_freq"),
                    "Sprężarka — prąd" to diagValue("oduCompCurrent", "odu_comp_current"),
                    "Woda — wejście Tw_in" to diagValue("tempTwin", "temp_tw_in"),
                    "Woda — wyjście Tw_out" to diagValue("tempTwout", "temp_tw_out"),
                    "Wymiennik T2" to diagValue("tempT2", "temp_t2"),
                    "Wymiennik T2B" to diagValue("tempT2b", "temp_t2b"),
                    "Wymiennik zewnętrzny T3" to diagValue("tempT3", "temp_t3"),
                    "Temperatura tłoczenia TP" to diagValue("tempTp", "temp_tp"),
                    "Zewnętrzna T4" to diagValue("tempT4", "temp_t4", "t4"),
                    "Wysokie ciśnienie" to diagValue("pressureHigh", "pressure_high"),
                    "Niskie ciśnienie" to diagValue("pressureLow", "pressure_low"),
                    "Prędkość wentylatora" to diagValue("fanSpeed", "fan_speed"),
                    "Zawór EXV" to diagValue("exvCurrent", "exv_current")
                )
                technical.forEach { (name, value) -> fieldRow(c, name, value, value == "—") }
                caption(c, "— oznacza brak parametru, nie wartość zero. Ciśnienia i częstotliwości nie są zgadywane.")
                spacing(c, 9)
                action(c, "Eksportuj raport (z Lua 0x10)") { export(pump) }
            }
        }
        card(root) { c ->
            label(c, "Wszystkie odebrane pola: ${snapshot.fields.size}", 16, ink, true, 6)
            caption(c, "Widok techniczny z mapowaniem pól do nazw. Zachowuje wartości surowe i klucze z chmury.")
            spacing(c, 10)
            action(c, "Eksportuj pełny JSON") { export(pump) }
        }
        card(root) { c ->
            title(c, "Lista pól (${snapshot.fields.size})")
            caption(c, "Widoczne są również pola bez opisów i wartości zero. Dane prywatne zostały ukryte.")
            spacing(c, 11)
            val query = EditText(this).apply {
                hint = "Szukaj nazwy lub wartości…"
                setSingleLine(true)
                textSize = 15f
                inputType = InputType.TYPE_CLASS_TEXT
                setText(searchQuery)
            }
            c.addView(query)
            spacing(c, 9)
            action(c, if (onlyUnknown) "Filtr: nierozpoznane  •  pokaż wszystko" else "Filtr: wszystkie  •  tylko nierozpoznane") {
                onlyUnknown = !onlyUnknown
                dashboard()
            }
            spacing(c, 9)
            action(c, "Zapisz raport JSON") { export(pump) }
            val count = label(c, "", 13, muted)
            val data = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(data)
            fun update() {
                data.removeAllViews()
                val selectedFields = snapshot.fields.filter { f ->
                    (!onlyUnknown || (!f.recognized && !f.redacted)) &&
                        (searchQuery.isBlank() || f.path.contains(searchQuery, true) ||
                            f.shownValue.contains(searchQuery, true) ||
                            (f.recognizedLabel?.contains(searchQuery, true) ?: false))
                }
                count.text = "Widoczne: ${selectedFields.size} / ${snapshot.fields.size}"
                if (selectedFields.isEmpty()) caption(data, "Brak pól pasujących do filtra.")
                selectedFields.forEach { f ->
                    spacing(data, 9)
                    label(data, f.path, 14, ink, true, 4)
                    label(data, f.shownValue.take(1000), 16, if (f.recognized) blue else ink)
                    caption(data, when {
                        f.redacted -> "UKRYTE • dane prywatne"
                        f.recognized -> "OPISANE • ${f.recognizedLabel}"
                        else -> "NIEROZPOZNANE • bez przypisanej jednostki"
                    })
                    val divider = View(this).apply { setBackgroundColor(this@MainActivity.background) }
                    data.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
                }
            }
            query.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    update()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            update()
        }
        if (rawX10.isNotBlank()) card(root) { c ->
            title(c, "Odpowiedź X10 — HEX")
            label(c, rawX10, 12)
        }
    }

    private fun wifiTab(pump: Pump) {
        card(root, bluePale) { c ->
            title(c,"Wi-Fi lokalne · Midea C3")
            caption(c,"Telefon musi być połączony z tą samą siecią Wi-Fi co fabryczny moduł pompy. Brak Home Assistant, Modbus i dodatkowych urządzeń.")
            spacing(c,9)
            label(c,"Połączenie: $lanStatus",14,ink,true)
            if(lanFetchedAt.isNotBlank())caption(c,"Odczyt: ${lanFetchedAt.take(19)} UTC • ${lanSnapshot.fields.size} pól")
            spacing(c,10)
            action(c,"Wyszukaj pompę w sieci Wi-Fi", primary=true){ findLan() }
        }
        if(lanDiscoveries.isNotEmpty()) {
            card(root){ c ->
                title(c,"Znalezione urządzenia C3")
                lanDiscoveries.forEach { d ->
                    fieldRow(c,d.display,if(d.id==pump.id)"TA SAMA POMPA" else "Inny identyfikator")
                    spacing(c,6)
                    action(c, "Użyj ${d.host} (${if(d.protocol==3)"V3" else "V2"})"){
                        if(d.id != pump.id) { statusText.text="To inne urządzenie. Można odczytać tylko wybraną pompę." }
                        else { localPump=d;localProtocol=d.protocol;lanStatus="Wybrano ${d.display}";dashboard() }
                    }
                    spacing(c,10)
                }
            }
        }
        card(root) { c ->
            title(c,"Połączenie po adresie IP")
            caption(c,"Gdy router blokuje broadcast, wpisz lokalny IP fabrycznego modułu. Port z wykrywania jest preferowany; dla ręcznego IP: 6444.")
            val ip=EditText(this).apply {
                hint="192.168.1.50";setText(lastWifiIp.ifBlank{localPump?.host.orEmpty()});textSize=16f;setSingleLine(true)
                inputType=InputType.TYPE_CLASS_TEXT
            }
            c.addView(ip)
            spacing(c,7)
            action(c,"Użyj tego adresu i wybranego protokołu"){
                val value=ip.text.toString().trim()
                try { WifiC3.checkedIp(value)
                    lastWifiIp=value
                    localPump=LanPump(value,6444,pump.id,0xc3,localProtocol,pump.sn8)
                    lanStatus="Adres ręczny: $value · V$localProtocol";dashboard()
                } catch(e:Exception) { statusText.text=e.message.orEmpty() }
            }
            spacing(c,8)
            action(c,"Protokół: V${localProtocol} — zmień V2/V3"){
                localProtocol=if(localProtocol==3)2 else 3
                localPump=localPump?.copy(protocol=localProtocol)
                dashboard()
            }
            caption(c,"V3 wymaga kluczy urządzenia z konta SmartHome. V2 korzysta z historycznego protokołu Midea; wiele nowych modułów odrzuca V2.")
        }
        card(root){ c ->
            title(c,"Diagnostyka lokalna")
            caption(c,"Najpierw X01 (działające podstawowe odczyty), następnie X10 (sprężarka i wymienniki) i X0A (HMI). Same odczyty, bez sterowania.")
            spacing(c,9)
            action(c,"Odczytaj parametry bezpośrednio",primary=true){ readLan() }
            if(lanSnapshot.fields.isNotEmpty()){
                spacing(c,11)
                label(c, "Źródło danych: $lanResultType · ${lanSnapshot.fields.size} pól", 13, ink, true)
                if(lanResultType=="X01") caption(c,"X01 to dane stanu i nastaw, nie aktualna praca sprężarki. X10 nie odpowiedziało.")
                lanSnapshot.fields.forEach{ f->fieldRow(c,f.recognizedLabel?:f.key,f.shownValue)}
            }
        }
        if(lanFrames.isNotEmpty())card(root){ c ->
            title(c,"Surowe odpowiedzi C3")
            caption(c,"Zapisujemy maksymalnie 88 pierwszych bajtów każdej ramki. Usuwamy dalszą część, która może zawierać numer seryjny pompy.")
            lanFrames.forEach { f ->
                label(c,"${f.request} → 0x${f.messageType.toString(16)}/0x${f.subtype.toString(16)} · ${f.size} B · suma ${if(f.checksumOk) "OK" else "NOK"}",12,ink,true,5)
                label(c,f.rawPrefixHex+(if(f.truncated)" [obcięto]" else ""),11,muted)
                spacing(c,9)
            }
        }
        if(lanSteps.isNotEmpty())card(root){ c ->
            title(c,"Dziennik połączenia Wi-Fi")
            lanSteps.forEach{caption(c,"• $it")}
            spacing(c,10)
            action(c,"Eksportuj wyniki testu") { export(pump) }
        }
    }

    private fun readLan(){
        val p=selected ?:return
        val l=localPump ?: run{statusText.text="Najpierw wyszukaj moduł lub podaj jego IP";return}
        if(l.id!=p.id){statusText.text="Identyfikator urządzenia różni się od wybranej pompy";return}
        val now=SystemClock.elapsedRealtime()
        if(now-lastWifiRead in 0..29_999L){statusText.text="Ponów odczyt Wi-Fi po 30 sekundach.";return}
        lastWifiRead=now
        runTask("Łączenie Wi-Fi z ${l.host}…",{
            // Never store or print LAN token/key. Obtained using currently authenticated cloud session.
            val keys= if(l.protocol==3) cloud.lanKeys(p.id) else null
            WifiC3.poll(this,l,p.sn8,keys)
        },{result->
            lanSnapshot=Telemetry.capture(JSONObject(result.readings));lanRawX10=result.rawX10
            lanFrames=result.frames;lanResultType=result.responseType
            lanFetchedAt=Instant.now().toString()
            lanSteps=result.steps
            lanStatus=when(result.responseType) {
                "X10" -> "Sukces: X10 — ${result.readings.size} parametrów (w tym praca urządzenia)"
                "X01" -> "X01 działa: ${result.readings.size} pól podstawowych. X10 nadal bez pomiarów."
                else -> "Połączono, lecz nie udało się zdekodować danych pompy"
            }
            feedback=lanStatus;dashboard()
        },{e ->
            lanStatus="Błąd Wi-Fi: ${e.message?.take(150)?:e.javaClass.simpleName}"
            lanSteps=lanSteps+listOf(lanStatus)
            feedback=lanStatus;dashboard()
        })
    }
    private fun findLan(){
        runTask("Wyszukiwanie fabrycznego Wi-Fi C3…",{WifiC3.discover(this)},{found ->
            lanDiscoveries=found
            val p=selected
            localPump=found.firstOrNull{it.id==p?.id}
            if(localPump!=null) localProtocol=localPump!!.protocol
            lanStatus=if(found.isEmpty())"Nie wykryto modułu — spróbuj podać IP" else "Wykryto ${found.size} urządzeń C3"
            feedback=lanStatus;dashboard()
        },{e ->lanStatus="Błąd wykrywania: ${e.message?.take(130)}";feedback=lanStatus;dashboard()})
    }
    private fun comparisonTab(pump:Pump){
        card(root,bluePale){ c->
            title(c,"Chmura kontra Wi-Fi")
            caption(c,"Porównanie tylko rzeczywiście odebranych wartości. Znak — oznacza brak parametru, nie wartość 0.")
            fieldRow(c,"Chmura SmartHome", "${snapshot.fields.size} pól")
            fieldRow(c,"Chmura Lua serwisowa", "${luaSnapshot.fields.size} pól")
            fieldRow(c,"Wi-Fi ($lanResultType)", "${lanSnapshot.fields.size} pól")
        }
        val mapped=listOf(
            Triple("Sprężarka – częstotliwość","comp_run_freq","compRunFreq"),
            Triple("Sprężarka – prąd","odu_comp_current","oduCompCurrent"),
            Triple("Woda – wejście Tw_in","temp_tw_in","tempTwin"),
            Triple("Woda – wyjście Tw_out","temp_tw_out","tempTwout"),
            Triple("Wymiennik T2","temp_t2","tempT2"),
            Triple("Wymiennik T2B","temp_t2b","tempT2b"),
            Triple("Wymiennik T3","temp_t3","tempT3"),
            Triple("Temperatura tłoczenia TP","temp_tp","tempTp"),
            Triple("Zewnętrzna T4","temp_t4","tempT4"),
            Triple("Ciśnienie wysokie","pressure_high","pressureHigh"),
            Triple("Ciśnienie niskie","pressure_low","pressureLow"),
            Triple("Wentylator","fan_speed","fanSpeed")
        )
        card(root){c->
            title(c,"Diagnostyka pracy pompy")
            caption(c,"Format: Wi-Fi / Chmura serwisowa. Surowe wartości nie są utożsamiane z temperaturami zadanymi.")
            mapped.forEach{(name,local,remote)->
                val l=lanSnapshot.values.optString(local,"").ifBlank{"—"}
                val cr=luaSnapshot.values.optString(remote,"").ifBlank{luaSnapshot.values.optString(local,"").ifBlank{"—"}}
                fieldRow(c,name,"$l / $cr",l=="—"&&cr=="—")
            }
        }
        card(root){ c ->
            title(c,"Stan konfiguracji z chmury")
            fieldRow(c,"Tryb ustawiony",cloudValue("run_mode_set")?:"—")
            fieldRow(c,"Obieg 1 (nastawa)",cloudValue("zone1_temp_set")?:"—")
            fieldRow(c,"CWU (odczyt)",cloudValue("tank_actual_temp")?:"—")
            fieldRow(c,"Kod błędu",cloudValue("error_code")?:"—")
            caption(c,"Te pola nie są równoważne diagnostyce sprężarki. Odczyty z LAN i chmury mogą mieć różne czasy i skale.")
            spacing(c,10)
            action(c,"Eksportuj porównanie JSON",primary=true){export(pump)}
        }
    }

    private fun settings(pump: Pump) {
        card(root) { c ->
            title(c, "Połączona pompa")
            label(c, pump.name, 18, ink, true, 6)
            caption(c, "Profil ${pump.sn8} • podtyp ${pump.subtype}")
            spacing(c, 12)
            action(c, "Wybierz inną pompę") { chooseScreen() }
        }
        card(root) { c ->
            title(c, "Konto SmartHome")
            caption(c, currentAccount)
            spacing(c, 6)
            label(c, if (rememberLogin) "Zapamiętywanie: włączone" else "Zapamiętywanie: wyłączone", 14, ink)
            caption(c, "Hasło przechowywane lokalnie jako zaszyfrowany zapis Android Keystore; token sesji tylko w pamięci.")
            spacing(c, 10)
            action(c, "Wyloguj i usuń zapisane dane", destructive = true) { logout() }
        }
        card(root) { c ->
            title(c, "Prywatność i odczyt")
            caption(c, "Zrzuty ekranu są dozwolone. Raport JSON nie zawiera danych logowania ani tokenów.")
            spacing(c, 6)
            caption(c, "Nie uruchamiamy Home Assistant ani Modbus. Lokalny odczyt korzysta wyłącznie z fabrycznego Wi-Fi. Bez zmiany nastaw pompy.")
            spacing(c, 9)
            caption(c, "Wersja 0.5.1 • Chmura + Wi-Fi · diagnostyka X01")
        }
    }

    private fun fetchStatus() {
        val p = selected ?: return
        if (!cooldown(false)) return
        runTask("Pobieram status SmartHome…", { cloud.status(p) }, { result ->
            snapshot = Telemetry.capture(result)
            source = "status/lua/get"
            fetchedAt = Instant.now().toString()
            rawX10 = ""; clearError()
            feedback = ""
            dashboard()
        }, { error -> failedRead(error) })
    }
    private fun fetchLuaDiagnostic() {
        val p = selected ?: return
        if (!cooldown(true)) return
        runTask("Pobieram parametry pracy Lua 0x10…", { cloud.unitDiagnosticsLua(p) }, { result ->
            luaSnapshot = Telemetry.capture(result)
            luaFetchedAt = Instant.now().toString()
            clearError()
            feedback = "Lua 0x10: odebrano ${luaSnapshot.fields.size} pól. Jeśli brakuje sprężarki, chmura nie przekazała diagnostyki."
            cloudChecks.add("Lua 0x10: ${luaSnapshot.fields.size} pól — ${Instant.now()}")
            tab = 1
            dashboard()
        }, { error ->
            cloudChecks.add("Lua 0x10: ${error.message?.take(100) ?: error.javaClass.simpleName}")
            failedRead(error)
        })
    }
    private fun fetchDiagnostic() {
        val p = selected ?: return
        if (!cooldown(true)) return
        runTask("Jednorazowa diagnostyka X10…", { cloud.diagnostic(p) }, { result ->
            snapshot = Telemetry.capture(JSONObject(result.readings))
            rawX10 = result.rawFrameHex
            source = "transparent/send • X10"
            fetchedAt = Instant.now().toString()
            clearError()
            feedback = if (result.profileDecoded) "X10: wartości surowe. Zweryfikuj skale na sterowniku."
                else "Otrzymano X10; profil nie ma pewnego dekodera. Surowy HEX w zakładce Dane."
            dashboard()
        }, { error -> failedRead(error) })
    }
    private fun failedRead(error: Throwable) {
        val e = error as? CloudException
        lastCode = e?.code
        errorEndpoint = e?.endpoint
        errorAt = Instant.now().toString()
        feedback = errorMessage(error)
        dashboard()
    }
    private fun errorMessage(t: Throwable): String = when ((t as? CloudException)?.code) {
        9999 -> "SmartHome zwrócił 9999 — błąd odpowiedzi API, a nie kod alarmu pompy."
        3176, 1306 -> "Chmura nie udostępniła odpowiedzi diagnostycznej (${(t as CloudException).code})."
        1014 -> "Chmura nie odczytała statusu Lua (1014)."
        3105, 3106, 3301, 3001, 7400, 40002 -> "Sesja została odrzucona. Wróć do logowania."
        null -> if (t is java.io.IOException) "Problem z połączeniem z Midea: ${t.message.orEmpty().take(80)}"
            else "Nie udało się odczytać danych (${t.javaClass.simpleName})."
        else -> "SmartHome odrzucił zapytanie: ${(t as CloudException).code}."
    }
    private fun clearError() { lastCode = null; errorEndpoint = null; errorAt = "" }
    private fun cooldown(diagnostic: Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        val previous = if (diagnostic) lastDiagnostic else lastStatus
        val duration = 60_000L
        if (now - previous in 0 until duration) {
            statusText.text = "Ponowny odczyt dostępny za ${((duration - (now - previous)) / 1000) + 1} s."
            return false
        }
        if (diagnostic) lastDiagnostic = now else lastStatus = now
        return true
    }
    private fun <T> runTask(message: String, task: () -> T, success: (T) -> Unit,
                            failure: (Throwable) -> Unit) {
        if (busy) return
        busy = true
        statusText.text = message
        executor.submit {
            val result = runCatching(task)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                result.fold(onSuccess = success, onFailure = failure)
            }
        }
    }
    private fun export(p: Pump) {
        exportText = Telemetry.report(snapshot, source, fetchedAt, p.sn8,
            lastCode, errorEndpoint, errorAt, rawX10)
            .put("luaUnitDiagnostics", luaSnapshot.values)
            .put("luaUnitDiagnosticsFetchedAt", luaFetchedAt)
            .put("luaUnitDiagnosticsFields", luaSnapshot.fields.size)
            .put("wifi", org.json.JSONObject().put("receivedFields",lanSnapshot.fields.size)
                .put("responseType",lanResultType)
                .put("rawFrames",JSONArray().apply {
                    lanFrames.forEach { f -> put(org.json.JSONObject()
                        .put("request",f.request).put("messageType",f.messageType)
                        .put("subtype",f.subtype).put("size",f.size)
                        .put("checksumValid",f.checksumOk)
                        .put("hexPrefix",f.rawPrefixHex).put("truncated",f.truncated)) }
                })
                .put("values",lanSnapshot.values).put("readAt",lanFetchedAt)
                .put("result",lanStatus).put("steps",JSONArray(lanSteps)))
            .put("cloudTests", JSONArray(cloudChecks)).toString(2)
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "midea-diagnostic-v0.5.1.json")
        }, 10)
    }
    @Deprecated("Native activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            feedback = try {
                contentResolver.openOutputStream(uri)?.use { it.write(exportText.toByteArray(Charsets.UTF_8)) }
                    ?: error("Brak dostępu do pliku")
                "Raport został zapisany."
            } catch (_: Exception) { "Nie udało się zapisać raportu." }
            statusText.text = feedback
        }
        exportText = ""
    }
    private fun logout() {
        cloud.clear()
        secrets.forget()
        currentAccount = ""; savedPassword = ""; rememberLogin = true
        rememberedPumpId = null; selected = null; devices = emptyList()
        feedback = "Zapisane dane konta i wybór pompy zostały usunięte."
        loginScreen(message = feedback)
    }
    @Deprecated("Native back navigation")
    override fun onBackPressed() {
        if (selected != null && tab != 0 && !busy) { tab = 0; dashboard() }
        else super.onBackPressed()
    }
    override fun onDestroy() {
        executor.submit { cloud.clear() }
        executor.shutdown()
        super.onDestroy()
    }
}

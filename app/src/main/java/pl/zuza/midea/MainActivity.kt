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
    private var tab = 0 // 0 overview, 1 all data, 2 settings
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
        lastCode = null; errorEndpoint = null; errorAt = ""; feedback = ""
        lastStatus = -60_000L; lastDiagnostic = -60_000L
        onlyUnknown = false; searchQuery = ""; tab = 0
        rememberedPumpId = p.id
        if (rememberLogin && savedPassword.isNotBlank()) {
            try { secrets.save(SavedLogin(currentAccount, savedPassword, p.id)) }
            catch (_: Exception) { feedback = "Wybrano pompę, ale nie zapisano wyboru na urządzeniu." }
        }
        dashboard()
        if (initialRead) fetchStatus()
    }

    private fun dashboard() {
        val pump = selected ?: return
        baseScreen(pump.name, "M-Thermal R32 • profil ${pump.sn8} • tylko odczyt")
        card(root, bluePale) { c ->
            label(c, "●  ${if (pump.online) "ONLINE" else "OFFLINE"} — przy pobraniu listy urządzeń", 13, blue, true, 6)
            label(c, if (fetchedAt.isBlank()) "Brak aktualnego odczytu" else "Ostatni udany odczyt: ${fetchedAt.replace('T', ' ').take(19)} UTC", 13, ink)
            caption(c, "Źródło: $source • Czas pomiaru w urządzeniu nieznany")
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(50)))
        listOf("Przegląd", "Wszystkie dane", "Ustawienia").forEachIndexed { index, title ->
            val cell = TextView(this).apply {
                text = title; textSize = 13f; gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT, if (tab == index) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (tab == index) blue else muted)
                background = shape(if (tab == index) bluePale else this@MainActivity.background, 10)
                setOnClickListener { if (!busy) { tab = index; dashboard() } }
            }
            tabs.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        }
        spacing(root, 15)
        if (lastCode != null) {
            card(root, Color.rgb(255, 241, 236)) { c ->
                label(c, "Błąd odpowiedzi chmury: $lastCode", 17, warning, true, 6)
                caption(c, "${errorEndpoint ?: "nieznany endpoint"} • $errorAt")
                label(c, "To kod API SmartHome, nie potwierdzona awaria pompy. Ostatni poprawny odczyt pozostaje dostępny.", 13, warning)
            }
        }
        when (tab) {
            0 -> overview(pump)
            1 -> rawData(pump)
            else -> settings(pump)
        }
        if (feedback.isNotBlank()) { statusText.text = feedback; statusText.setTextColor(if (lastCode == null) muted else warning) }
    }

    private fun overview(pump: Pump) {
        card(root) { c ->
            title(c, "Połączenie i diagnostyka")
            twoActions(c, "↻  Odśwież", "X10  •  Test", { fetchStatus() }, { fetchDiagnostic() })
            spacing(c, 9)
            caption(c, "X10 to pojedyncze zapytanie diagnostyczne. Błąd 9999 nie usuwa ostatnich odczytów.")
        }
        card(root) { c ->
            title(c, "Otrzymane dane")
            label(c, "${snapshot.fields.size} pól  •  ${snapshot.recognized} opisanych  •  ${snapshot.unknown} bez mapowania", 15, ink, true)
            if (snapshot.fields.isEmpty()) caption(c, "Nie ma jeszcze wartości. Po połączeniu aplikacja spróbuje pobrać status.")
            else caption(c, "Etykiety parametrów są orientacyjne; wartości pozostają surowe i nieprzeskalowane.")
        }
        if (snapshot.fields.isNotEmpty() && snapshot.recognized == 0) {
            card(root, Color.rgb(255, 248, 229)) { c ->
                title(c, "Wszystkie pola są na razie nierozpoznane")
                label(c, "To nie znaczy, że pompa nie pracuje. Chmura zwróciła ${snapshot.fields.size} wartości, ale nie mamy jeszcze pewnego mapowania do czujników.", 14)
                spacing(c, 8)
                action(c, "Otwórz wszystkie wartości") { tab = 1; dashboard() }
            }
        }
        val groups = listOf(
            "Sprężarka i jednostka" to listOf("comp_run_freq", "odu_target_fre", "odu_comp_current", "compressor_on", "unit_mode_run", "fan_speed", "odu_voltage", "exv_current", "fg_capacity_need"),
            "Wymienniki i czujniki" to listOf("temp_t1", "temp_t2", "temp_t2b", "temp_t3", "temp_tp", "temp_th", "temp_tf", "temp_t4", "outdoor_temperature", "t4"),
            "Ciśnienia i obieg" to listOf("pressure_high", "pressure_low", "water_pressure", "water_flower", "temp_tw_in", "temp_tw_out", "pump_i_running"),
            "CWU i ogrzewanie" to listOf("tank_actual_temp", "tank_actual_temperature", "temp_t5", "zone1_temp_set", "zone2_temp_set", "status_dhw", "status_heating", "status_cool"),
            "Pozostałe odczyty" to listOf("error_code", "instant_power0")
        )
        val grouped = mutableSetOf<String>()
        for ((name, keys) in groups) {
            val fields = snapshot.fields.filter { it.recognized && it.key.lowercase() in keys }.also { grouped.addAll(it.map { f -> f.path }) }
            if (fields.isNotEmpty()) showTiles(name, fields)
        }
        val rest = snapshot.fields.filter { it.recognized && it.path !in grouped }
        if (rest.isNotEmpty()) showTiles("Inne rozpoznane parametry", rest)
        if (snapshot.unknown > 0) card(root) { c ->
            title(c, "Nierozpoznane pola")
            snapshot.fields.filter { !it.recognized && !it.redacted }.take(4).forEach { f ->
                label(c, "${f.path}  =  ${f.shownValue.take(64)}", 13, muted)
            }
            spacing(c, 9)
            action(c, "Zobacz wszystkie (${snapshot.unknown})") { tab = 1; dashboard() }
        }
        if (snapshot.fields.isNotEmpty()) card(root) { c ->
            action(c, "Eksportuj pełny raport JSON") { export(pump) }
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
        card(root) { c ->
            title(c, "Wszystkie pola (${snapshot.fields.size})")
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
            caption(c, "Nie uruchamiamy Home Assistant. Nie korzystamy z Modbus, LAN ani dodatkowych modułów. Brak zmiany nastaw pompy.")
            spacing(c, 9)
            caption(c, "Wersja 0.3.0 • układ kafelków inspirowany Home Assistant")
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
            feedback = "Odebrano ${snapshot.fields.size} pól • ${snapshot.recognized} opisanych • ${snapshot.unknown} nierozpoznanych."
            dashboard()
        }, { error -> failedRead(error) })
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
            lastCode, errorEndpoint, errorAt, rawX10).toString(2)
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "midea-diagnostic-v0.3.json")
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

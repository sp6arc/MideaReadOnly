package pl.zuza.midea

import org.json.JSONArray
import org.json.JSONObject

/** Raw cloud values are retained, never scaled or given guessed units. */
internal data class TelemetryField(
    val path: String,
    val key: String,
    val value: Any?,
    val recognizedLabel: String?,
    val redacted: Boolean
) {
    val recognized get() = recognizedLabel != null && !redacted
    val shownValue: String get() = when (value) {
        JSONObject.NULL, null -> "null"
        is String -> value
        else -> value.toString()
    }
}
internal data class TelemetrySnapshot(val values: JSONObject, val fields: List<TelemetryField>) {
    val recognized get() = fields.count { it.recognized }
    val unknown get() = fields.count { !it.recognized && !it.redacted }
    val hidden get() = fields.count { it.redacted }
}

internal object Telemetry {
    val priorities = linkedMapOf(
        // Klucze oryginalnego Lua T_0000_C3_171H120F (case insensitive).
        "comprunfreq" to "Sprężarka — aktualna częstotliwość (odczyt surowy)",
        "odutargetfre" to "Sprężarka — częstotliwość zadana (odczyt surowy)",
        "oducompcurrent" to "Prąd sprężarki (odczyt surowy)",
        "unitmoderun" to "Rzeczywisty tryb jednostki (odczyt surowy)",
        "fanspeed" to "Wentylator jednostki zewnętrznej (odczyt surowy)",
        "temptwin" to "Temperatura powrotu wody Tw_in (odczyt surowy)",
        "temptwout" to "Temperatura zasilania wody Tw_out (odczyt surowy)",
        "tempt1" to "Temperatura T1 (odczyt surowy)",
        "tempt2" to "Temperatura T2 wymiennika (odczyt surowy)",
        "tempt2b" to "Temperatura T2B wymiennika (odczyt surowy)",
        "tempt3" to "Temperatura wymiennika zewnętrznego T3 (odczyt surowy)",
        "tempt4" to "Temperatura zewnętrzna T4 (odczyt surowy)",
        "temptp" to "Temperatura tłoczenia TP (odczyt surowy)",
        "tempth" to "Temperatura TH (odczyt surowy)",
        "temptf" to "Temperatura TF (odczyt surowy)",
        "pressurehigh" to "Ciśnienie strony wysokiej (odczyt surowy)",
        "pressurelow" to "Ciśnienie strony niskiej (odczyt surowy)",
        "oduvoltage" to "Napięcie jednostki zewnętrznej (odczyt surowy)",
        "exvcurrent" to "Zawór rozprężny EXV (odczyt surowy)",
        "waterflow" to "Przepływ wody (odczyt surowy)",
        "waterpressure" to "Ciśnienie wody (odczyt surowy)",
        "fgdefrost" to "Odszranianie (odczyt surowy)",
        "fgdhwrun" to "Grzanie CWU w trakcie (odczyt surowy)",
        "total_electricity0" to "Łączne zużycie energii (odczyt surowy)",
        "total_thermal0" to "Łączna energia cieplna (odczyt surowy)",
        "totalelectricity0" to "Łączne zużycie energii (odczyt surowy)",
        "totalthermal0" to "Łączna energia cieplna (odczyt surowy)",
        "lan_zone1_power_state" to "Wi-Fi X01 · Obieg 1 (1=włączony)",
        "lan_zone2_power_state" to "Wi-Fi X01 · Obieg 2 (1=włączony)",
        "lan_dhw_power_state" to "Wi-Fi X01 · CWU (1=włączona)",
        "lan_heat_enable" to "Wi-Fi X01 · Zezwolenie na grzanie",
        "lan_cool_enable" to "Wi-Fi X01 · Zezwolenie na chłodzenie",
        "lan_dhw_enable" to "Wi-Fi X01 · Zezwolenie na CWU",
        "lan_mode_set_raw" to "Wi-Fi X01 · Nastawa trybu (kod)",
        "lan_zone1_temp_set" to "Wi-Fi X01 · Obieg 1 zadana °C",
        "lan_zone2_temp_set" to "Wi-Fi X01 · Obieg 2 zadana °C",
        "lan_dhw_temp_set" to "Wi-Fi X01 · CWU zadana °C",
        "lan_tank_actual_temp" to "Wi-Fi X01 · Temperatura zasobnika °C",
        "lan_error_code" to "Wi-Fi X01 · Kod błędu pompy",
        "comp_run_freq" to "Częstotliwość sprężarki (surowa)",
        "compressor_on" to "Sprężarka pracuje (surowe)",
        "unit_mode_run" to "Tryb pracy jednostki (surowy)",
        "fan_speed" to "Prędkość wentylatora (surowa)",
        "odu_voltage" to "Napięcie jednostki zewnętrznej (surowe)",
        "exv_current" to "Zawór rozprężny (surowy)",
        "fg_capacity_need" to "Zapotrzebowanie na moc (surowe)",
        "odu_target_fre" to "Zadana częstotliwość sprężarki (surowa)",
        "odu_comp_current" to "Prąd sprężarki (surowy)",
        "temp_t2" to "Temperatura T2 (surowa)",
        "temp_t1" to "Temperatura T1 (surowa)",
        "temp_t2b" to "Temperatura T2B (surowa)",
        "temp_t3" to "Temperatura T3 (surowa)",
        "temp_tp" to "Temperatura TP (surowa)",
        "temp_th" to "Temperatura TH (surowa)",
        "temp_tf" to "Temperatura TF (surowa)",
        "pressure_high" to "Ciśnienie wysokie (surowe)",
        "pressure_low" to "Ciśnienie niskie (surowe)",
        "temp_tw_in" to "Temperatura wejścia wody (surowa)",
        "water_flower" to "Przepływ wody (surowy)",
        "water_pressure" to "Ciśnienie wody (surowe)",
        "temp_t4" to "Temperatura T4 (surowa)",
        "temp_t5" to "Temperatura zasobnika T5 (surowa)",
        "outdoor_temperature" to "Temperatura zewnętrzna (surowa)",
        "tank_actual_temperature" to "Temperatura zasobnika (surowa)",
        "zone1_temp_set" to "Nastawa strefy 1 (surowa)",
        "zone2_temp_set" to "Nastawa strefy 2 (surowa)",
        "error_code" to "Kod pompy (surowy)",
        "status_heating" to "Ogrzewanie (surowe)",
        "status_cool" to "Chłodzenie (surowe)",
        "status_dhw" to "CWU (surowe)",
        "pump_i_running" to "Pompa obiegowa (surowe)",
        "instant_power0" to "Pobór mocy (surowy)",
        "temp_tw_out" to "Temperatura wyjścia wody (surowa)",
        "t4" to "Temperatura zewnętrzna T4 (surowa)",
        "tank_actual_temp" to "Temperatura zasobnika CWU (odczyt)",
        "dhw_temp_set" to "CWU — temperatura zadana",
        "dhw_power_state" to "CWU — włączenie",
        "dhw_enable" to "CWU — funkcja dostępna",
        "dhw_max_set_temp" to "CWU — maksymalna nastawa",
        "dhw_min_set_temp" to "CWU — minimalna nastawa",
        "run_mode_set" to "Ustawiony tryb pracy",
        "runmode_under_auto" to "Tryb w automatyce",
        "zone1_power_state" to "Obieg 1 — włączenie",
        "zone2_power_state" to "Obieg 2 — włączenie",
        "double_zone_enable" to "Konfiguracja dwóch obiegów",
        "zone1_terminal_type" to "Obieg 1 — typ odbiornika",
        "zone2_terminal_type" to "Obieg 2 — typ odbiornika",
        "zone1_temp_type" to "Obieg 1 — regulacja temperatury",
        "zone2_temp_type" to "Obieg 2 — regulacja temperatury",
        "zone1_curve_state" to "Obieg 1 — krzywa pogodowa",
        "zone2_curve_state" to "Obieg 2 — krzywa pogodowa",
        "zone1_heat_max_set_temp" to "Obieg 1 — maksymalna nastawa ogrzewania",
        "zone1_heat_min_set_temp" to "Obieg 1 — minimalna nastawa ogrzewania",
        "zone2_heat_max_set_temp" to "Obieg 2 — maksymalna nastawa ogrzewania",
        "zone2_heat_min_set_temp" to "Obieg 2 — minimalna nastawa ogrzewania",
        "zone1_cool_max_set_temp" to "Obieg 1 — maksymalna nastawa chłodzenia",
        "zone1_cool_min_set_temp" to "Obieg 1 — minimalna nastawa chłodzenia",
        "zone2_cool_max_set_temp" to "Obieg 2 — maksymalna nastawa chłodzenia",
        "zone2_cool_min_set_temp" to "Obieg 2 — minimalna nastawa chłodzenia",
        "room_temp_set" to "Temperatura pokojowa — nastawa (nie pomiar)",
        "room_min_set_temp" to "Nastawa pokojowa — minimum",
        "room_max_set_temp" to "Nastawa pokojowa — maksimum",
        "room_thermalen_state" to "Regulacja pokojowa — możliwość",
        "room_thermalmode_state" to "Regulacja pokojowa — tryb aktywny",
        "heat_enable" to "Ogrzewanie — dostępne",
        "cool_enable" to "Chłodzenie — dostępne",
        "fastdhw_state" to "CWU — szybkie grzanie",
        "forcetbh_state" to "Grzałka zasobnika — wymuszenie",
        "boostertbh_en" to "Grzałka zasobnika — możliwość",
        "eco_on_state" to "Tryb ECO",
        "silence_on_state" to "Tryb cichy",
        "holiday_on_state" to "Tryb urlopowy",
        "SysEnergyAnaEN".lowercase() to "Analiza energii — system",
        "HMIEnergyAnaSetEN".lowercase() to "Analiza energii — HMI",
        "time_set_state" to "Program czasowy — stan",
        "remote_onoff" to "Zdalne włączenie — opcja",
        "version" to "Numer wersji protokołu (surowy)"
    )

    // Some cloud responses contain identifiers/session metadata. Keep key presence but not the secret.
    private val privateNames = setOf(
        "id", "uid", "userid", "user_id", "deviceid", "device_id", "appliancecode",
        "appliance_code", "appliancesn", "appliance_sn", "sn", "sn8", "mac", "macaddress",
        "username", "loginaccount", "account", "name", "location", "latitude", "longitude",
        "address", "phone", "email", "token", "key", "secret", "randomdata", "random_data",
        "authorization", "accesstoken", "refresh_token", "password", "pwd"
    )
    private fun isPrivate(key: String): Boolean {
        val s = key.lowercase().replace("-", "_")
        return s in privateNames || listOf("token", "password", "passwd", "secret", "credential", "serial", "email", "phone", "authkey", "sessionkey").any { s.contains(it) }
    }

    /** Creates an independent, redacted tree without deleting or interpreting unknown values. */
    fun capture(response: JSONObject): TelemetrySnapshot {
        val clean = sanitizeObject(response, 0)
        val leaves = mutableListOf<TelemetryField>()
        flatten(clean, "", leaves)
        return TelemetrySnapshot(clean, leaves.sortedWith(compareBy<TelemetryField> { !it.recognized }.thenBy { it.path }))
    }

    private fun sanitizeObject(obj: JSONObject, depth: Int): JSONObject = JSONObject().apply {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = if (isPrivate(key)) "[ukryto]" else sanitize(obj.opt(key), depth + 1)
            put(key, value)
        }
    }

    private fun sanitize(value: Any?, depth: Int): Any = when {
        depth > 24 -> "[zbyt głęboka struktura]"
        value is JSONObject -> sanitizeObject(value, depth)
        value is JSONArray -> JSONArray().apply {
            for (i in 0 until value.length()) put(sanitize(value.opt(i), depth + 1))
        }
        value == null -> JSONObject.NULL
        else -> value
    }

    private fun flatten(node: Any?, path: String, out: MutableList<TelemetryField>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    flatten(node.opt(key), if (path.isEmpty()) key else "$path.$key", out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) flatten(node.opt(i), "$path[$i]", out)
            else -> {
                val name = path.substringAfterLast('.').substringBefore('[')
                val hidden = node == "[ukryto]"
                out.add(TelemetryField(path, name, node, priorities[name.lowercase()], hidden))
            }
        }
    }

    /** Kept for backwards compatibility with old tests; NOT the export path. */
    fun safeValues(source: JSONObject): JSONObject = JSONObject().apply {
        priorities.keys.forEach { k ->
            val v = source.opt(k)
            if (v is Number && v.toDouble().isFinite()) put(k, v)
        }
    }

    fun report(
        snapshot: TelemetrySnapshot,
        source: String,
        fetchedAt: String,
        sn8: String,
        code: Int?,
        errorEndpoint: String?,
        errorAt: String,
        rawX10: String = ""
    ): JSONObject {
        val fieldRows = JSONArray()
        snapshot.fields.forEach { field ->
            fieldRows.put(obj(
                "path" to field.path,
                "value" to (field.value ?: JSONObject.NULL),
                "recognized" to field.recognized,
                "label" to field.recognizedLabel,
                "redacted" to field.redacted
            ))
        }
        return obj(
            "app" to "Midea ReadOnly 0.4.0",
            "source" to source,
            "fetchedAt" to fetchedAt,
            "measurementTimeKnown" to false,
            "sn8Profile" to sn8,
            "receivedFields" to snapshot.fields.size,
            "recognizedFields" to snapshot.recognized,
            "unrecognizedFields" to snapshot.unknown,
            "redactedFields" to snapshot.hidden,
            "values" to snapshot.values,
            "fields" to fieldRows,
            "lastCloudError" to code,
            "errorEndpoint" to errorEndpoint,
            "errorAt" to errorAt,
            "rawX10Hex" to (if (rawX10.isEmpty()) JSONObject.NULL else rawX10),
            "notice" to "Pola status/lua/get obejmują głównie nastawy i stany sterownika, nie pełne pomiary układu chłodniczego. Temperatury przedstawiane w UI jako °C według semantyki pól, lecz skala nie była niezależnie potwierdzona. Czas pobrania nie jest czasem pomiaru. Błąd 9999 jest odpowiedzią API chmury, a nie alarmem urządzenia."
        )
    }
}

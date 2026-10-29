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
        "tank_actual_temp" to "Temperatura zasobnika (surowa)"
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
            "app" to "Midea ReadOnly 0.3.0",
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
            "notice" to "Surowe dane odczytowe SmartHome. Nieznane pola pozostają nieopisane; jednostki i skale wymagają weryfikacji. Czas pobrania nie oznacza czasu pomiaru. Błąd 9999 jest odpowiedzią chmury, nie kodem usterki pompy."
        )
    }
}

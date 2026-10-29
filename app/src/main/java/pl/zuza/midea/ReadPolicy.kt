package pl.zuza.midea

import java.net.URI
import org.json.JSONObject

/** Closed allowlist. A POST is not necessarily a device write. No control endpoint exists here. */
internal object ReadPolicy {
    const val ROUTE = "/v1/unitcenter/router/user/name"
    const val LOGIN_ID = "/v1/user/login/id/get"
    const val LOGIN = "/mj/user/login"
    const val LIST = "/v1/appliance/user/list/get"
    const val STATUS = "/v1/device/status/lua/get"
    const val RELAY = "/v1/appliance/transparent/send"
    fun check(endpoint: String, body: JSONObject, frame: ByteArray? = null) {
        require(endpoint in setOf(ROUTE, LOGIN_ID, LOGIN, LIST, STATUS, RELAY)) { "Zablokowane żądanie" }
        if (endpoint == STATUS) {
            val command = body.getJSONObject("command")
            require(command.length() == 1 && command.has("query"))
            val query = command.getJSONObject("query")
            require(query.length() == 0 ||
                (query.length() == 1 && query.has("query_type") && query.optString("query_type") == "16")) {
                "Dozwolone są wyłącznie odczyty: podstawowy oraz Lua 0x10"
            }
        }
        if (endpoint == RELAY) require(frame != null && frame.contentEquals(C3.query())) { "Dozwolony tylko odczyt X10" }
    }
    fun validateBase(base: String): String {
        val u = URI(base)
        val host = u.host?.lowercase() ?: error("Brak hosta")
        require(u.scheme == "https" && (u.port == -1 || u.port == 443) && u.userInfo == null && u.fragment == null)
        require(host == "appsmb.com" || host.endsWith(".appsmb.com")) { "Nieobsługiwany region chmury" }
        require(u.path == "/mas/v5/app/proxy" && u.rawQuery == "alias=")
        return base
    }
}

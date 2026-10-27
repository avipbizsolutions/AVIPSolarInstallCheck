package com.avip.installcheck

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A readable error from the office sheet. [sessionExpired] = log in again. */
class ApiException(message: String, val sessionExpired: Boolean = false) : Exception(message)

data class User(val id: String, val name: String, val mobile: String, val team: String, val designation: String) {
    val isSales get() = team == Team.SALES
    val isManager get() = team == Team.MANAGER
    val isDispatch get() = team == Team.DISPATCH
    val isInstaller get() = team == Team.INSTALLATION

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("mobile", mobile)
        .put("team", team).put("designation", designation)

    companion object {
        fun from(o: JSONObject) = User(
            o.optString("id"), o.optString("name"), o.optString("mobile"),
            o.optString("team"), o.optString("designation"),
        )
    }
}

object Team {
    const val SALES = "Sales"
    const val MANAGER = "Manager"
    const val DISPATCH = "Dispatch"
    const val INSTALLATION = "Installation"

    fun label(team: String) = when (team) {
        SALES -> "Sales Team"
        MANAGER -> "Manager"
        DISPATCH -> "Dispatch · Godown"
        INSTALLATION -> "Installation Team"
        else -> team
    }

    fun color(team: String) = when (team) {
        SALES -> Ui.BLUE
        MANAGER -> Ui.PURPLE
        DISPATCH -> Ui.TEAL
        else -> Ui.SUN_TEXT
    }
}

/** Who is logged in on this phone. */
object Session {
    private const val PREFS = "session"

    fun token(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", "") ?: ""

    fun user(c: Context): User? {
        val s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("user", "") ?: ""
        return if (s.isBlank()) null else try { User.from(JSONObject(s)) } catch (_: Exception) { null }
    }

    fun mustSetPin(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("mustSetPin", false)

    fun save(c: Context, token: String, user: User, mustSetPin: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", token).putString("user", user.toJson().toString())
            .putBoolean("mustSetPin", mustSetPin).apply()
    }

    fun clear(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/**
 * Calls the Apps Script web app behind the office Google Sheet.
 * Its address comes from config.json in the app's GitHub repo, so it can be
 * changed without a new app version.
 */
object Api {
    /** Must match SHARED_KEY in apps-script/Code.gs. */
    private const val SHARED_KEY = "avip-install-9Kp2"
    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/avipbizsolutions/AVIPSolarInstallCheck/main/config.json"
    private const val PREFS = "config"

    fun endpoint(c: Context): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("endpoint", "") ?: ""

    /** Reads config.json; keeps the last good address when offline. Returns true when an address is known. */
    fun refreshConfig(c: Context): Boolean {
        try {
            val conn = URL(CONFIG_URL + "?t=" + System.currentTimeMillis() / 60000).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            try {
                if (conn.responseCode == 200) {
                    val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val ep = o.optString("endpoint").trim()
                    if (ep.startsWith("https://")) {
                        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("endpoint", ep).apply()
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
        }
        return endpoint(c).startsWith("https://")
    }

    /** Blocking call; run it off the main thread. Throws [ApiException] with a message for the user. */
    fun call(c: Context, kind: String, body: JSONObject = JSONObject(), timeoutMs: Int = 60000): JSONObject {
        val ep = endpoint(c)
        if (!ep.startsWith("https://")) throw ApiException("Office server is not set up yet. Please tell the office.")
        body.put("key", SHARED_KEY).put("kind", kind)
        if (!body.has("token")) body.put("token", Session.token(c))
        val text = try {
            val conn = URL(ep).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            throw ApiException("No internet connection. Please try again.")
        }
        val o = try { JSONObject(text) } catch (_: Exception) {
            throw ApiException("Office server did not answer properly. Try again in a minute.")
        }
        if (!o.optBoolean("ok")) {
            val msg = o.optString("error").ifBlank { "Something went wrong." }
            if (msg.startsWith("SESSION:")) throw ApiException(msg.removePrefix("SESSION:").trim(), sessionExpired = true)
            if (msg == "unknown kind") throw ApiException("Office server needs updating (new Apps Script version).")
            throw ApiException(msg)
        }
        return o
    }
}

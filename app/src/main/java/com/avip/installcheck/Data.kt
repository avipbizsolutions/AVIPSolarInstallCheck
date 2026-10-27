package com.avip.installcheck

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Site statuses, exactly as in the sheet's Lists tab. */
object Status {
    const val DRAFT = "Draft"
    const val SUBMITTED = "Submitted for Verification"
    const val SENT_BACK = "Sent Back to Sales"
    const val APPROVED = "Approved for Dispatch"
    const val DISPATCHED = "Dispatched"
    const val BALANCE = "Balance Pending"
    const val IN_PROGRESS = "Installation In Progress"
    const val INSTALL_SUBMITTED = "Installation Submitted"
    const val REWORK = "Rework"
    const val CLOSED = "QC Approved (Closed)"

    /** Short Hindi-English label for cards. */
    fun label(s: String) = when (s) {
        DRAFT -> "Draft"
        SUBMITTED -> "Verify में"
        SENT_BACK -> "वापस आई"
        APPROVED -> "Dispatch के लिए"
        DISPATCHED -> "Dispatch हुआ"
        BALANCE -> "Balance बाकी"
        IN_PROGRESS -> "Installation चल रहा"
        INSTALL_SUBMITTED -> "QC Review"
        REWORK -> "Rework"
        CLOSED -> "Closed ✓"
        else -> s
    }

    /** Background and text colour of the status pill. */
    fun colors(s: String): Pair<Int, Int> = when (s) {
        SENT_BACK, REWORK -> Ui.DANGER_LIGHT to Ui.DANGER
        CLOSED -> Ui.GREEN_LIGHT to 0xFF145E39.toInt()
        INSTALL_SUBMITTED -> 0xFFF1E9FA.toInt() to 0xFF5A2F8C.toInt()
        DRAFT -> Ui.BRAND_LIGHT to Ui.TEXT
        else -> Ui.SUN_LIGHT to Ui.SUN_TEXT
    }

    /** Pipeline step 0..4 (Sales, Verify, Dispatch, Install, QC) for the progress bar. */
    fun step(s: String) = when (s) {
        DRAFT, SENT_BACK -> 0
        SUBMITTED -> 1
        APPROVED -> 2
        DISPATCHED, BALANCE, IN_PROGRESS, REWORK -> 3
        INSTALL_SUBMITTED -> 4
        CLOSED -> 5
        else -> 0
    }
}

data class BomItem(
    val sr: String, val section: String, val item: String, val make: String,
    val unit: String, val qty: Double, val serial: Boolean,
)

data class CheckItem(val id: String, val section: String, val text: String, val critical: Boolean, val photoOnNotOk: Boolean) {
    val isPhoto get() = id.startsWith("PH")
}

/** Lists from the sheet (BOM Master, Checklist Master, teams). Cached for offline use. */
object Ref {
    var bom: List<BomItem> = emptyList(); private set
    var checklist: List<CheckItem> = emptyList(); private set
    var installers: List<String> = emptyList(); private set
    var salesPeople: List<String> = emptyList(); private set

    val isLoaded get() = bom.isNotEmpty()

    fun apply(c: Context, o: JSONObject, save: Boolean = true) {
        bom = list(o.optJSONArray("bom")).map {
            BomItem(it.optString("sr"), it.optString("section"), it.optString("item"), it.optString("make"),
                it.optString("unit"), it.optDouble("qty", 0.0), it.optBoolean("serial"))
        }
        checklist = list(o.optJSONArray("checklist")).map {
            CheckItem(it.optString("id"), it.optString("section"), it.optString("text"),
                it.optBoolean("critical"), it.optBoolean("photoOnNotOk"))
        }
        installers = strings(o.optJSONArray("installers"))
        salesPeople = strings(o.optJSONArray("salesPeople"))
        if (save) c.getSharedPreferences("ref", Context.MODE_PRIVATE).edit().putString("data", o.toString()).apply()
    }

    fun loadCached(c: Context): Boolean {
        val s = c.getSharedPreferences("ref", Context.MODE_PRIVATE).getString("data", "") ?: ""
        if (s.isBlank()) return false
        return try { apply(c, JSONObject(s), save = false); true } catch (_: Exception) { false }
    }

    /** Fetches fresh lists; keeps the cached copy when offline. */
    fun refresh(c: Context) {
        try {
            val o = Api.call(c, "bootstrap")
            apply(c, o)
            o.optJSONObject("user")?.let { u ->
                Session.save(c, Session.token(c), User.from(u), Session.mustSetPin(c))
            }
        } catch (e: ApiException) {
            if (e.sessionExpired) throw e
            loadCached(c)
        }
    }
}

fun list(a: JSONArray?): List<JSONObject> =
    if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

fun strings(a: JSONArray?): List<String> =
    if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }

/** One site row from the sheet, with its materials, serials, checklist, photos and sign-offs. */
class Site(val o: JSONObject) {
    fun s(k: String): String = o.optString(k).trim()
    fun d(k: String): Double = s(k).toDoubleOrNull() ?: 0.0

    val id get() = s("Site ID")
    val status get() = s("Status")
    val customer get() = s("Customer Name").ifBlank { "(नाम नहीं)" }
    val mobile get() = s("Mobile")
    val address get() = s("Address")
    val district get() = s("District")
    val kw get() = s("Site Size (kW)")
    val systemType get() = s("System Type")
    val salesPerson get() = s("Sales Person")
    val installTeam get() = s("Installation Team")
    val lat get() = s("Latitude").toDoubleOrNull()
    val lng get() = s("Longitude").toDoubleOrNull()
    val fromSalesApp get() = s("Source") == "AVIP Solar Sales"

    /** "3 kW On-grid · Naubatpur" */
    val subtitle get() = listOf(
        if (kw.isNotBlank()) "$kw kW" else "", systemType, district,
    ).filter { it.isNotBlank() }.joinToString(" · ")

    val materials get() = list(o.optJSONArray("materials"))
    val serials get() = list(o.optJSONArray("serials"))
    val checklistRows get() = list(o.optJSONArray("checklist"))
    val photos get() = list(o.optJSONArray("photos"))
    val pending get() = list(o.optJSONArray("pending"))
    val signoffs get() = list(o.optJSONArray("signoffs"))

    /** Install round: 1, then 2 after the first rework, and so on. */
    val round: Int
        get() = if (status == Status.INSTALL_SUBMITTED || status == Status.CLOSED) signoffs.size.coerceAtLeast(1)
        else signoffs.size + 1

    val lastSignoff get() = signoffs.lastOrNull()

    fun balanceTotal() = materials.sumOf { it.optDouble("Balance", 0.0).coerceAtLeast(0.0) }

    fun mapsUrl(): String = s("Google Map Link").ifBlank {
        val la = lat; val ln = lng
        if (la != null && ln != null) "https://maps.google.com/?q=$la,$ln" else ""
    }
}

/** Sites the logged-in user can see, kept in memory between screens. */
object Sites {
    var all: List<Site> = emptyList(); private set
    var loadedAt = 0L; private set

    fun byId(id: String?) = all.firstOrNull { it.id == id }

    /** Blocking. Throws ApiException. */
    fun reload(c: Context) {
        val o = Api.call(c, "sites", timeoutMs = 90000)
        all = list(o.optJSONArray("sites")).map { Site(it) }
        loadedAt = System.currentTimeMillis()
    }
}

object Fmt {
    private val day = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

    /** "2026-10-30 00:00" → "30 Oct 2026" */
    fun date(v: String): String {
        if (v.length < 10) return v
        return try { day.format(iso.parse(v.substring(0, 10))!!) } catch (_: Exception) { v }
    }

    fun isoDay(ms: Long): String = iso.format(Date(ms))

    fun parseIso(v: String): Long? = try { iso.parse(v.substring(0, 10))?.time } catch (_: Exception) { null }

    fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date())

    fun qty(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.1f", v)
}

/** Files this phone keeps per site (photos, signatures, report). */
object Work {
    fun dir(c: Context, siteId: String): File =
        File(c.filesDir, "work/" + siteId.replace(Regex("[^A-Za-z0-9_-]"), "_")).apply { mkdirs() }
}

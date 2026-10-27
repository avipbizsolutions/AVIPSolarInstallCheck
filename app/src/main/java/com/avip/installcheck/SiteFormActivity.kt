package com.avip.installcheck

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

/** Customer, address, Google Map location and PM Surya Ghar details (Sales / Manager). */
class SiteFormActivity : AppCompatActivity() {

    private var site: Site? = null
    private lateinit var user: User
    private val values = LinkedHashMap<String, String>()
    private lateinit var locText: TextView
    private lateinit var lm: LocationManager
    private var listener: LocationListener? = null

    private val perm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) takeLocation() else toast("Location की अनुमति चाहिए")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        user = Session.user(this) ?: run { finish(); return }
        site = Sites.byId(intent.getStringExtra(SiteActivity.EXTRA_ID))
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val keys = listOf("Customer Name", "Mobile", "WhatsApp", "Address", "District", "Latitude", "Longitude",
            "Google Map Link", "Site Size (kW)", "System Type", "PM Surya Ghar App No.", "DISCOM", "CA No.",
            "Survey By", "Survey Date", "Sales Note", "Sales Person")
        keys.forEach { values[it] = site?.s(it) ?: "" }
        if (site == null) {
            values["Survey By"] = user.name
            values["Survey Date"] = Fmt.isoDay(System.currentTimeMillis())
            values["System Type"] = "On-grid"
            if (user.isSales) values["Sales Person"] = user.name
        }
        if (values["Survey Date"]!!.length > 10) values["Survey Date"] = values["Survey Date"]!!.substring(0, 10)
        build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocation()
    }

    private fun build() {
        val save = pillButton(if (site == null) "Save करें, अगला: Material List →" else "Save करें", Ui.SUN, Ui.TEXT, heightDp = 54) { save() }
        val body = screen(if (site == null) "नई Site जोड़ें" else "Customer & Site", site?.id, bottom = save)

        body.addView(section("Customer") {
            fieldTo(this, "Customer का नाम *", "Customer Name", "पूरा नाम (बिजली बिल के अनुसार)")
            fieldTo(this, "Mobile Number *", "Mobile", "10 अंकों का number", InputType.TYPE_CLASS_PHONE)
            fieldTo(this, "WhatsApp Number", "WhatsApp", "खाली छोड़ें अगर यही number है", InputType.TYPE_CLASS_PHONE)
            fieldTo(this, "पूरा पता *", "Address", "मकान, गली, मोहल्ला, थाना", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            fieldTo(this, "ज़िला", "District", "जैसे Patna")
        })

        body.addView(section("Google Map Location *", "Installation team इसी location पर navigation करेगी") {
            locText = text(locationLabel(), 14.5f, Ui.TEXT) {
                background = rounded(Ui.BRAND_LIGHT, dpf(12f))
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            addView(locText, lp())
            addView(pillButton("Site पर हूँ, Location लें", Ui.BRAND, android.graphics.Color.WHITE, iconRes = R.drawable.ic_location) {
                if (hasLocation()) takeLocation() else perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }, lp().margins(context, t = 10))
            addView(pillButton("Google Map Link Paste करें", android.graphics.Color.WHITE, Ui.TEXT) { pasteLink() }.apply {
                background = rounded(android.graphics.Color.WHITE, dpf(25f), 0xFFB9C5D0.toInt(), dp(1))
            }, lp().margins(context, t = 8))
            if (values["Latitude"]!!.isNotBlank()) addView(text("Map में देखें ›", 14f, Ui.BRAND, bold = true) {
                setPadding(0, dp(10), 0, 0)
                setOnClickListener { openUrl(mapUrl()) }
            })
        })

        body.addView(section("System & PM Surya Ghar") {
            fieldTo(this, "Site Size (kW) *", "Site Size (kW)", "जैसे 3", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            addView(text("System Type", 13f, Ui.MUTED, bold = true), lp().margins(context, t = 12, b = 6))
            addView(chips(listOf("On-grid", "Hybrid", "Off-grid"), values["System Type"] ?: "") { values["System Type"] = it })
            fieldTo(this, "PM Surya Ghar App No.", "PM Surya Ghar App No.", "Portal application number")
            addView(text("DISCOM", 13f, Ui.MUTED, bold = true), lp().margins(context, t = 12, b = 6))
            addView(chips(listOf("NBPDCL", "SBPDCL"), values["DISCOM"] ?: "") { values["DISCOM"] = it })
            fieldTo(this, "CA No. (Consumer No.)", "CA No.", "बिजली बिल पर")
            fieldTo(this, "Site Survey by", "Survey By", "")
            addView(text("Survey Date", 13f, Ui.MUTED, bold = true), lp().margins(context, t = 12, b = 6))
            val dateView = text(Fmt.date(values["Survey Date"] ?: ""), 16f, Ui.TEXT) {
                background = rounded(android.graphics.Color.WHITE, dpf(12f), 0xFFB9C5D0.toInt(), dp(1))
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            dateView.setOnClickListener { pickDate(values["Survey Date"] ?: "") { d -> values["Survey Date"] = d; dateView.text = Fmt.date(d) } }
            addView(dateView, lp())
            if (user.isManager && Ref.salesPeople.isNotEmpty()) {
                addView(text("Sales Person", 13f, Ui.MUTED, bold = true), lp().margins(context, t = 12, b = 6))
                addView(chips(Ref.salesPeople, values["Sales Person"] ?: "") { values["Sales Person"] = it })
            }
            fieldTo(this, "Manager / Dispatch के लिए Note", "Sales Note", "जैसे: छत तीसरी मंज़िल पर, लंबा DC cable लगेगा")
        })
    }

    private fun fieldTo(parent: LinearLayout, label: String, key: String, hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
        field(parent, label, values[key] ?: "", hint, type) { values[key] = it.trim() }

    private fun locationLabel(): String {
        val la = values["Latitude"] ?: ""
        val ln = values["Longitude"] ?: ""
        return when {
            la.isNotBlank() && ln.isNotBlank() -> "✓ Pin लगा: $la, $ln"
            (values["Google Map Link"] ?: "").isNotBlank() -> "✓ Map link: ${values["Google Map Link"]}"
            else -> "अभी Location नहीं लगी है"
        }
    }

    private fun mapUrl(): String = values["Google Map Link"]?.ifBlank { null }
        ?: "https://maps.google.com/?q=${values["Latitude"]},${values["Longitude"]}"

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun takeLocation() {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) { toast("Phone की Location (GPS) चालू करें"); return }
        locText.text = "Location ली जा रही है… (छत पर / खुले में खड़े हों)"
        stopLocation()
        var best: Location? = null
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (best == null || loc.accuracy < best!!.accuracy) best = loc
                val b = best!!
                setLocation(b)
                if (b.accuracy <= 15f) stopLocation()
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        listener = l
        providers.forEach { p ->
            try { lm.requestLocationUpdates(p, 1000L, 0f, l, Looper.getMainLooper()) } catch (_: Exception) {}
        }
        // stop after 30 s with the best fix so far
        locText.postDelayed({ stopLocation() }, 30000)
    }

    private fun setLocation(loc: Location) {
        values["Latitude"] = String.format(Locale.US, "%.6f", loc.latitude)
        values["Longitude"] = String.format(Locale.US, "%.6f", loc.longitude)
        values["Google Map Link"] = ""
        locText.text = locationLabel() + "  (±${loc.accuracy.toInt()} m)"
    }

    private fun stopLocation() {
        listener?.let { try { lm.removeUpdates(it) } catch (_: Exception) {} }
        listener = null
    }

    private fun pasteLink() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
        val fromClip = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        askText("Google Map Link", "maps.google.com/… या maps.app.goo.gl/…", fromClip.takeIf { it.contains("map", true) || it.contains("goo.gl") } ?: "") { link ->
            val m = Regex("(-?\\d{1,2}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)").find(link)
            if (m != null) {
                values["Latitude"] = m.groupValues[1]
                values["Longitude"] = m.groupValues[2]
            } else {
                values["Latitude"] = ""
                values["Longitude"] = ""
            }
            values["Google Map Link"] = link
            locText.text = locationLabel()
        }
    }

    private fun save() {
        val err = when {
            values["Customer Name"].isNullOrBlank() -> "Customer का नाम भरें"
            (values["Mobile"] ?: "").filter { it.isDigit() }.length < 10 -> "10 अंकों का Mobile number भरें"
            values["Address"].isNullOrBlank() -> "पता भरें"
            values["Latitude"].isNullOrBlank() && values["Google Map Link"].isNullOrBlank() -> "Google Map location लगाएँ"
            values["Site Size (kW)"].isNullOrBlank() -> "Site Size (kW) भरें"
            else -> null
        }
        if (err != null) { AlertDialog.Builder(this).setMessage(err).setPositiveButton("OK", null).show(); return }
        val mob = values["Mobile"]!!.filter { it.isDigit() }.takeLast(10)
        values["Mobile"] = mob
        val wa = (values["WhatsApp"] ?: "").filter { it.isDigit() }.takeLast(10)
        values["WhatsApp"] = wa.ifBlank { mob }

        val siteJson = JSONObject()
        values.forEach { (k, v) -> if (k != "Sales Person" || user.isManager) siteJson.put(k, v) }
        site?.let { siteJson.put("Site ID", it.id) }
        val isNew = site == null
        val body = JSONObject().put("site", siteJson)
        if (isNew) {
            // start with the default quantities for every site (BOM Master)
            val mats = org.json.JSONArray()
            Ref.bom.forEach { b ->
                mats.put(JSONObject().put("sr", b.sr).put("item", b.item).put("make", b.make).put("unit", b.unit).put("qty", b.qty))
            }
            body.put("materials", mats).put("asSales", true)
        }
        busy("Save हो रहा है…", {
            val o = Api.call(this, "saveSite", body)
            Sites.reload(this)
            o.optString("siteId")
        }) { id ->
            toast("Save हो गया")
            if (isNew) {
                startActivity(Intent(this, SiteActivity::class.java).putExtra(SiteActivity.EXTRA_ID, id))
                startActivity(Intent(this, MaterialsActivity::class.java).putExtra(SiteActivity.EXTRA_ID, id)
                    .putExtra(MaterialsActivity.EXTRA_MODE, MaterialsActivity.MODE_SALES))
            }
            finish()
        }
    }
}

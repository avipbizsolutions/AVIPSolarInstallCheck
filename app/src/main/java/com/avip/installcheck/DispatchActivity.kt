package com.avip.installcheck

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

/** Godown: serial numbers with label photos, quantities sent, vehicle details, then Dispatch Done. */
class DispatchActivity : AppCompatActivity() {

    private lateinit var site: Site
    private lateinit var lines: List<MatLine>
    private lateinit var serialBox: LinearLayout
    private lateinit var loadingNote: android.widget.TextView
    private lateinit var vehicle: EditText
    private lateinit var driver: EditText
    private var pendingSerial: Pair<String, String>? = null // (item, serial) waiting for its photo

    private val camera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val files = r.data?.getStringArrayListExtra(CameraActivity.EXTRA_FILES).orEmpty()
        val ps = pendingSerial
        pendingSerial = null
        if (files.isEmpty()) return@registerForActivityResult
        val f = File(files.last())
        if (ps != null) {
            val make = lines.firstOrNull { it.item == ps.first }?.make ?: ""
            Uploads.serial(this, site.id, ps.first, make, ps.second, f)
            toast("Serial ${ps.second} जोड़ा गया")
        } else {
            Uploads.photo(this, site.id, f, "Dispatch", "Loading",
                lat = r.data?.getDoubleExtra(CameraActivity.EXTRA_LAT, 0.0)?.takeIf { it != 0.0 },
                lng = r.data?.getDoubleExtra(CameraActivity.EXTRA_LNG, 0.0)?.takeIf { it != 0.0 })
            toast("Loading photo जोड़ी गई")
        }
        renderSerials(); renderLoading()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = Sites.byId(intent.getStringExtra(SiteActivity.EXTRA_ID)) ?: run { finish(); return }
        if (!Ref.isLoaded) { toast("BOM list load नहीं हुई"); finish(); return }
        lines = Materials.lines(site, MaterialsActivity.MODE_DISPATCH)

        val done = pillButton("Dispatch Done", Ui.GREEN, Color.WHITE, heightDp = 54) { finishDispatch() }
        val body = screen("Dispatch: ${site.customer}", "${site.subtitle} · Install ${Fmt.date(site.s("Installation Date"))}", bottom = done)

        if (site.s("Sales Note").isNotBlank()) body.addView(note("Sales Note: " + site.s("Sales Note"), Ui.SUN_LIGHT, 0xFF4A3300.toInt()))
        if (site.s("Manager Note").isNotBlank()) body.addView(note("Manager: " + site.s("Manager Note")))

        body.addView(section("Serial No. + Label Photo", "Panel, Inverter और Battery के हर piece का") {
            serialBox = vertical { }
            addView(serialBox)
        })

        body.addView(text("Material Dispatch (BOM)", 16f, Ui.TEXT, bold = true), lp().margins(this, t = 4, b = 4))
        body.addView(text("माँगी गई Qty पहले से भरी है। जो कम भेज रहे हैं वह कम करें, वह Balance में जाएगा।", 13.5f, Ui.MUTED), lp().margins(this, b = 10))
        body.addView(MaterialEditor(this, lines, MaterialsActivity.MODE_DISPATCH).view())

        body.addView(section("गाड़ी की जानकारी") {
            vehicle = field(this, "Vehicle No.", "", "BR 01 XX 0000", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) {}
            driver = field(this, "Driver Mobile", "", "10 अंकों का number", InputType.TYPE_CLASS_PHONE) {}
            loadingNote = text("", 13.5f, Ui.MUTED)
            addView(loadingNote, lp().margins(context, t = 10))
            addView(pillButton("गाड़ी में लोड Material की Photo", Color.WHITE, Ui.TEXT, iconRes = R.drawable.ic_camera) {
                openCamera("गाड़ी में लोड Material", "loading")
            }.apply { background = rounded(Color.WHITE, dpf(25f), 0xFFB9C5D0.toInt(), dp(1)) }, lp().margins(context, t = 8))
        })
        renderSerials()
        renderLoading()
    }

    override fun onResume() {
        super.onResume()
        Uploads.onChange = { renderSerials(); renderLoading() }
        Uploads.flush(this)
    }

    override fun onPause() {
        super.onPause()
        Uploads.onChange = null
    }

    private fun serialItems() = lines.filter { it.serial && ((it.reference ?: 0.0) > 0 || it.qty > 0) }

    /** Serial numbers already in the sheet plus those still uploading, per item. */
    private fun serialsFor(item: String): List<Pair<String, Boolean>> {
        val done = site.serials.filter { it.optString("Item Type") == item }.map { it.optString("Serial No.") to true }
        val waiting = Uploads.pendingFor(this, site.id).filter { it.optString("kind") == "serial" && it.optString("itemType") == item }
            .map { it.optString("serial") to false }
        return done + waiting
    }

    private fun renderSerials() {
        serialBox.removeAllViews()
        val items = serialItems()
        if (items.isEmpty()) {
            serialBox.addView(text("इस site में Panel / Inverter / Battery नहीं हैं", 14f, Ui.MUTED))
        }
        items.forEach { l ->
            val have = serialsFor(l.item)
            val need = l.qty.toInt()
            serialBox.addView(horizontal {
                addView(text(l.item + if (l.make.isNotBlank()) " · ${l.make}" else "", 15f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
                addView(text("${have.size} / $need", 15f, if (have.size >= need) Ui.GREEN else Ui.SUN_TEXT, bold = true))
            }, lp().margins(this, t = 8, b = 6))
            have.chunked(2).forEach { pair ->
                serialBox.addView(horizontal {
                    pair.forEach { (sn, uploaded) ->
                        addView(text((if (uploaded) "✓ " else "⏳ ") + sn, 13.5f, if (uploaded) 0xFF145E39.toInt() else Ui.SUN_TEXT, bold = true) {
                            background = rounded(if (uploaded) Ui.GREEN_LIGHT else Ui.SUN_LIGHT, dpf(10f))
                            setPadding(dp(10), dp(8), dp(10), dp(8))
                            maxLines = 1
                            if (uploaded) setOnLongClickListener { removeSerial(sn); true }
                        }, lp(0, WRAP, 1f).margins(context, r = 6))
                    }
                    if (pair.size == 1) addView(View(context), lp(0, 1, 1f))
                }, lp().margins(this, b = 6))
            }
            if (have.size < need) serialBox.addView(pillButton("+ ${l.item} Serial जोड़ें", Ui.BRAND, Color.WHITE, heightDp = 46, iconRes = R.drawable.ic_camera) {
                addSerial(l)
            }, lp().margins(this, b = 6))
        }
        val errs = Uploads.errors(this, site.id)
        if (errs.isNotEmpty()) serialBox.addView(note(errs.take(3).joinToString("\n"), Ui.DANGER_LIGHT, Ui.DANGER), lp().margins(this, t = 8))
        serialBox.addView(text("गलत serial हटाने के लिए उस पर देर तक दबाएँ।", 12f, Ui.MUTED), lp().margins(this, t = 4))
    }

    private fun renderLoading() {
        val n = site.photos.count { it.optString("Photo Type") == "Loading" } +
            Uploads.pendingFor(this, site.id).count { it.optString("type") == "Loading" }
        loadingNote.text = if (n == 0) "Loading photo अभी नहीं ली" else "✓ $n loading photo"
    }

    private fun addSerial(l: MatLine) {
        askText("${l.item} Serial No.", "Label पर लिखा Serial No.") { sn ->
            val clean = sn.trim().uppercase()
            val all = lines.filter { it.serial }.flatMap { x -> serialsFor(x.item).map { it.first.uppercase() } }
            if (clean in all) { toast("यह Serial पहले से जुड़ा है"); return@askText }
            pendingSerial = l.item to clean
            openCamera("Label photo: $clean", "serial_" + clean.replace(Regex("[^A-Za-z0-9]"), "_"))
        }
    }

    private fun removeSerial(sn: String) {
        confirm("Serial हटाएँ?", "$sn को इस site से हटाना है?", "हटाएँ") {
            busy("हटाया जा रहा है…", {
                Api.call(this, "serialDelete", JSONObject().put("siteId", site.id).put("serial", sn))
                Sites.reload(this)
            }) {
                site = Sites.byId(site.id) ?: site
                renderSerials()
            }
        }
    }

    private fun openCamera(title: String, prefix: String) {
        camera.launch(Intent(this, CameraActivity::class.java)
            .putExtra(CameraActivity.EXTRA_DIR, Work.dir(this, site.id).absolutePath)
            .putExtra(CameraActivity.EXTRA_TITLE, title)
            .putExtra(CameraActivity.EXTRA_SUBTITLE, "${site.customer} · Dispatch")
            .putExtra(CameraActivity.EXTRA_PREFIX, prefix))
    }

    private fun finishDispatch() {
        val missing = serialItems().filter { serialsFor(it.item).size < it.qty.toInt() }
        if (missing.isNotEmpty()) {
            showError(ApiException("इनके Serial No. बाकी हैं:\n" + missing.joinToString("\n") { "${it.item}: ${serialsFor(it.item).size} / ${it.qty.toInt()}" }))
            return
        }
        val v = vehicle.text.toString().trim()
        if (v.isBlank()) { vehicle.error = "Vehicle No. भरें"; vehicle.requestFocus(); return }
        val short = lines.filter { (it.reference ?: 0.0) > it.qty }
        val msg = if (short.isEmpty()) "सारा material भेजा जा रहा है।"
        else "कम भेजा (Balance में जाएगा):\n" + short.joinToString("\n") { "${it.item}: ${Fmt.qty((it.reference ?: 0.0) - it.qty)} ${it.unit}" }
        confirm("Dispatch Done?", msg, "Dispatch Done") {
            busy("Dispatch save हो रहा है…", {
                Api.call(this, "dispatch", JSONObject().put("siteId", site.id)
                    .put("materials", Materials.toJson(lines)).put("vehicle", v)
                    .put("driver", driver.text.toString().filter { c -> c.isDigit() }.takeLast(10)))
            }) { o ->
                Uploads.flush(this)
                busy("Refresh…", { Sites.reload(this) }) {
                    toast(if (o.optBoolean("balancePending")) "Dispatch हुआ · Balance बाकी" else "Dispatch हो गया")
                    finish()
                }
            }
        }
    }
}

package com.avip.installcheck

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The installation team's work at one site, saved on the phone as it goes
 * (so nothing is lost without signal): checklist, photos, pending work and signatures.
 */
class InstallDraft(private val c: Context, val siteId: String, val round: Int) {
    private val key = "draft_${siteId}_$round"
    private val o: JSONObject = try {
        JSONObject(c.getSharedPreferences("drafts", Context.MODE_PRIVATE).getString(key, "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    private fun obj(name: String): JSONObject = o.optJSONObject(name) ?: JSONObject().also { o.put(name, it) }

    fun result(id: String): String = obj("results").optJSONObject(id)?.optString("r") ?: ""
    fun remark(id: String): String = obj("results").optJSONObject(id)?.optString("remark") ?: ""
    fun setResult(id: String, r: String) { val x = obj("results").optJSONObject(id) ?: JSONObject(); obj("results").put(id, x.put("r", r)); save() }
    fun setRemark(id: String, v: String) { val x = obj("results").optJSONObject(id) ?: JSONObject(); obj("results").put(id, x.put("remark", v)); save() }

    fun photos(id: String): List<String> = strings(obj("photos").optJSONArray(id)).filter { File(it).exists() }
    fun addPhoto(id: String, path: String) { val a = obj("photos").optJSONArray(id) ?: JSONArray(); a.put(path); obj("photos").put(id, a); save() }

    var demo: Boolean
        get() = o.optBoolean("demo")
        set(v) { o.put("demo", v); save() }

    val tlSign get() = File(Work.dir(c, siteId), "sign_team_leader_r$round.png")
    val customerSign get() = File(Work.dir(c, siteId), "sign_customer_r$round.png")

    fun save() { c.getSharedPreferences("drafts", Context.MODE_PRIVATE).edit().putString(key, o.toString()).apply() }

    companion object {
        /** Latest draft of a site, if this phone has one. */
        fun latest(c: Context, site: Site): InstallDraft? {
            val p = c.getSharedPreferences("drafts", Context.MODE_PRIVATE)
            for (r in site.round downTo 1) if (p.contains("draft_${site.id}_$r")) return InstallDraft(c, site.id, r)
            return null
        }
    }
}

class InstallActivity : AppCompatActivity() {

    private lateinit var site: Site
    private lateinit var draft: InstallDraft
    private lateinit var body: LinearLayout
    private var photoFor: CheckItem? = null

    private val camera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val item = photoFor
        photoFor = null
        val files = r.data?.getStringArrayListExtra(CameraActivity.EXTRA_FILES).orEmpty()
        if (item == null || files.isEmpty()) return@registerForActivityResult
        val lat = r.data?.getDoubleExtra(CameraActivity.EXTRA_LAT, 0.0)?.takeIf { it != 0.0 }
        val lng = r.data?.getDoubleExtra(CameraActivity.EXTRA_LNG, 0.0)?.takeIf { it != 0.0 }
        files.forEach { path ->
            draft.addPhoto(item.id, path)
            Uploads.photo(this, site.id, File(path), "Installation", if (item.isPhoto) "Required" else "Checklist", item.id, lat, lng)
        }
        render()
    }

    private val signer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = Sites.byId(intent.getStringExtra(SiteActivity.EXTRA_ID)) ?: run { finish(); return }
        if (Ref.checklist.isEmpty()) { toast("Checklist load नहीं हुई। Internet check करें।"); finish(); return }
        draft = InstallDraft(this, site.id, site.round)
        // start from what is already in the sheet for this round
        site.checklistRows.filter { it.optString("Round") == site.round.toString() }.forEach { row ->
            val id = row.optString("Item ID")
            if (draft.result(id).isBlank()) {
                draft.setResult(id, row.optString("Result"))
                draft.setRemark(id, row.optString("Remark"))
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        Uploads.onChange = { render() }
        Uploads.flush(this)
    }

    override fun onPause() {
        super.onPause()
        Uploads.onChange = null
    }

    // ------------------------------------------------------------ state helpers

    private fun serverPhotos(id: String) = site.photos.count { it.optString("Linked Item ID") == id }
    private fun hasPhoto(id: String) = serverPhotos(id) > 0 || draft.photos(id).isNotEmpty()
    private fun checkItems() = Ref.checklist.filter { !it.isPhoto }
    private fun photoItems() = Ref.checklist.filter { it.isPhoto }

    // ------------------------------------------------------------ UI

    private fun render() {
        val scrollY = (if (::body.isInitialized) (body.parent as? View)?.scrollY else null) ?: 0
        val submit = pillButton("Office को Submit करें", Ui.GREEN, Color.WHITE, heightDp = 54) { submit() }
        body = screen(site.customer, "Installation · Round ${site.round}", bottom = vertical {
            addView(horizontal {
                addView(pillButton("Save", Color.WHITE, Ui.TEXT, heightDp = 54) { saveProgress(false) }.apply {
                    background = rounded(Color.WHITE, dpf(27f), 0xFFB9C5D0.toInt(), dp(1))
                }, lp(dp(100), WRAP).margins(context, r = 8))
                addView(submit, lp(0, WRAP, 1f))
            })
        })
        (body.parent as? View)?.post { (body.parent as? View)?.scrollTo(0, scrollY) }

        val done = checkItems().count { draft.result(it.id).isNotBlank() }
        val photosDone = photoItems().count { hasPhoto(it.id) }
        body.addView(card(16) {
            addView(horizontal {
                addView(vertical {
                    addView(text(site.subtitle, 14f, Ui.MUTED))
                    addView(text("Checklist $done / ${checkItems().size} · Photos $photosDone / ${photoItems().size}", 15.5f, Ui.TEXT, bold = true))
                }, lp(0, WRAP, 1f))
                addView(icon(R.drawable.ic_location, Ui.BRAND, 22).apply {
                    background = rounded(Color.WHITE, dpf(22f), Ui.LINE, dp(1)); setPadding(dp(10), dp(10), dp(10), dp(10))
                    setOnClickListener { openUrl(site.mapsUrl()) }
                }, lp(dp(44), dp(44)).margins(context, r = 8))
                addView(icon(R.drawable.ic_call, Ui.GREEN, 22).apply {
                    background = rounded(Color.WHITE, dpf(22f), Ui.LINE, dp(1)); setPadding(dp(10), dp(10), dp(10), dp(10))
                    setOnClickListener { call(site.mobile) }
                }, lp(dp(44), dp(44)))
            })
            val total = checkItems().size + photoItems().size
            addView(horizontal {
                val filled = done + photosDone
                addView(View(context).apply { background = rounded(Ui.SUN, dpf(4f)) }, lp(0, dp(8), filled.toFloat().coerceAtLeast(0.01f)))
                if (total - filled > 0) addView(View(context).apply { background = rounded(Ui.LINE, dpf(4f)) }, lp(0, dp(8), (total - filled).toFloat()))
            }, lp().margins(context, t = 10))
        }, lp().margins(this, b = 12))

        if (site.status == Status.REWORK) {
            body.addView(note("QC Rework: " + (site.lastSignoff?.optString("QC Remarks") ?: ""), Ui.DANGER_LIGHT, Ui.DANGER))
        }
        if (site.balanceTotal() > 0) {
            body.addView(note("Godown से कुछ material अभी बाकी है (Balance). ज़रूरत हो तो Pending Work में लिखें।", Ui.SUN_LIGHT, 0xFF4A3300.toInt()))
        }
        val pendingUp = Uploads.pendingFor(this, site.id).size
        if (pendingUp > 0) body.addView(note("⏳ $pendingUp photos upload हो रही हैं…", Ui.SUN_LIGHT, Ui.SUN_TEXT))
        Uploads.errors(this, site.id).takeIf { it.isNotEmpty() }?.let { body.addView(note(it.take(3).joinToString("\n"), Ui.DANGER_LIGHT, Ui.DANGER)) }

        renderSerials()
        checkItems().groupBy { it.section }.forEach { (section, items) ->
            body.addView(text(section, 16f, Ui.TEXT, bold = true), lp().margins(this, t = 6, b = 8))
            items.forEach { body.addView(checkCard(it), lp().margins(this, b = 10)) }
        }
        renderPhotos()
        renderPending()
        renderSignoff()
    }

    private fun renderSerials() {
        val serials = site.serials
        if (serials.isEmpty()) return
        body.addView(section("Serial No. मिलान", "Godown से आए Panel / Inverter के Serial site पर check करें") {
            serials.forEach { r ->
                val matched = r.optString("Matched at Site") == "Yes"
                addView(horizontal {
                    setPadding(0, dp(4), 0, dp(4))
                    addView(vertical {
                        addView(text(r.optString("Serial No."), 14.5f, Ui.TEXT, bold = true))
                        addView(text(r.optString("Item Type"), 12.5f, Ui.MUTED))
                    }, lp(0, WRAP, 1f))
                    addView(if (matched) pill("मिला ✓", Ui.GREEN_LIGHT, 0xFF145E39.toInt()) else pill("बाकी", Ui.SUN_LIGHT, Ui.SUN_TEXT))
                })
            }
            addView(pillButton("Serial No. मिलाएँ", Ui.BRAND, Color.WHITE, heightDp = 46) { matchSerial() }, lp().margins(context, t = 10))
        })
    }

    private fun matchSerial() {
        askText("Serial No. मिलाएँ", "Panel / Inverter पर लिखा Serial No.") { sn ->
            busy("Check हो रहा है…", {
                val o = Api.call(this, "serialMatch", JSONObject().put("siteId", site.id).put("serial", sn.trim()))
                Sites.reload(this)
                o
            }) { o ->
                site = Sites.byId(site.id) ?: site
                toast("✓ ${o.optString("itemType")} मिला")
                render()
            }
        }
    }

    private fun checkCard(item: CheckItem): View = card(14) {
        val r = draft.result(item.id)
        if (r == "Not OK") background = rounded(Color.WHITE, dpf(16f), Ui.DANGER, dp(2))
        addView(horizontal {
            gravity = Gravity.TOP
            addView(text(item.text, 15f, Ui.TEXT), lp(0, WRAP, 1f))
            if (item.critical) addView(pill("ज़रूरी", Ui.DANGER_LIGHT, Ui.DANGER), lp(WRAP, WRAP).margins(context, l = 6))
        })
        addView(horizontal {
            addView(okNa(r) { v -> draft.setResult(item.id, v); render() }, lp(0, WRAP, 1f))
            val n = draft.photos(item.id).size + serverPhotos(item.id)
            addView(text(if (n > 0) "📷$n" else "📷", 15f, Ui.TEXT, bold = true) {
                gravity = Gravity.CENTER
                minHeight = dp(46)
                background = rounded(if (n > 0) Ui.GREEN_LIGHT else Color.WHITE, dpf(10f), 0xFFB9C5D0.toInt(), dp(1))
                contentDescription = "Photo"
                setOnClickListener { takePhoto(item) }
            }, lp(dp(56), WRAP))
        }, lp().margins(context, t = 10))
        if (r == "Not OK") {
            val remark = input("क्या कमी है? (ज़रूरी)", InputType.TYPE_CLASS_TEXT, draft.remark(item.id))
            remark.onChange { draft.setRemark(item.id, it.trim()) }
            addView(remark, lp().margins(context, t = 10))
            if (item.photoOnNotOk && !hasPhoto(item.id)) {
                addView(note("Not OK पर Photo ज़रूरी है — 📷 दबाएँ", Ui.DANGER_LIGHT, Ui.DANGER), lp().margins(context, t = 8))
            }
        }
    }

    private fun renderPhotos() {
        body.addView(text("ज़रूरी GPS Photos", 16f, Ui.TEXT, bold = true), lp().margins(this, t = 6, b = 4))
        body.addView(text("App के camera से — AVIP GPSCam जैसा stamp अपने आप लगेगा", 13f, Ui.MUTED), lp().margins(this, b = 8))
        val waiting = Uploads.pendingLinks(this, site.id)
        photoItems().chunked(2).forEach { pair ->
            body.addView(horizontal {
                gravity = Gravity.TOP
                pair.forEach { item ->
                    val local = draft.photos(item.id).lastOrNull()
                    val uploaded = serverPhotos(item.id) > 0
                    addView(vertical {
                        addView(android.widget.FrameLayout(context).apply {
                            background = if (local != null || uploaded) rounded(0xFF2E4A63.toInt(), dpf(12f))
                            else rounded(Color.WHITE, dpf(12f), 0xFF8A99A8.toInt(), dp(2))
                            clipToOutline = true
                            if (local != null) {
                                addView(ImageView(context).apply {
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    setImageBitmap(thumb(local))
                                }, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
                            } else {
                                addView(text(if (uploaded) "✓ Uploaded" else "📷\nPhoto लें", 15f, if (uploaded) Color.WHITE else Ui.TEXT, bold = true) {
                                    gravity = Gravity.CENTER
                                }, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
                            }
                            if (local != null || uploaded) {
                                addView(text(if (item.id in waiting) "⏳ upload" else "✓", 12f, Color.WHITE, bold = true) {
                                    background = rounded(if (item.id in waiting) Ui.SUN_TEXT else Ui.GREEN, dpf(10f))
                                    setPadding(dp(8), dp(3), dp(8), dp(3))
                                }, android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
                            }
                            setOnClickListener { takePhoto(item) }
                        }, lp(MATCH, dp(124)))
                        addView(text(item.text, 13.5f, Ui.TEXT) { maxLines = 3 }, lp().margins(context, t = 6))
                    }, lp(0, WRAP, 1f).margins(context, r = 10))
                }
                if (pair.size == 1) addView(View(context), lp(0, 1, 1f))
            }, lp().margins(this, b = 12))
        }
    }

    private fun thumb(path: String) = try {
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 8 })
    } catch (_: Exception) { null }

    private fun renderPending() {
        body.addView(section("बाकी काम या समस्या", "जो काम पूरा नहीं हुआ (जैसे Net meter, material कम)") {
            val open = site.pending.filter { it.optString("Status") == "Open" }
            if (open.isEmpty()) addView(text("कोई काम बाकी नहीं", 14f, Ui.MUTED))
            open.forEach { p ->
                addView(note(p.optString("Pending Item") +
                    (if (p.optString("Reason").isNotBlank()) " · ${p.optString("Reason")}" else "") +
                    (if (p.optString("Expected Date").isNotBlank()) " · ${Fmt.date(p.optString("Expected Date"))}" else ""),
                    Ui.SUN_LIGHT, 0xFF4A3300.toInt()).apply {
                    setOnLongClickListener { closePending(p.optString("Pending Item")); true }
                })
            }
            addView(pillButton("+ बाकी काम जोड़ें", Color.WHITE, Ui.TEXT, heightDp = 44) { addPending() }.apply {
                background = rounded(Color.WHITE, dpf(22f), 0xFF8A99A8.toInt(), dp(1))
            }, lp().margins(context, t = 6))
            if (open.isNotEmpty()) addView(text("काम पूरा होने पर उस पर देर तक दबाएँ।", 12f, Ui.MUTED), lp().margins(context, t = 6))
        })
    }

    private fun addPending() {
        val item = input("क्या बाकी है? जैसे Net meter")
        val reason = input("कारण, जैसे DISCOM से")
        var date = ""
        val dateView = text("कब तक? (तारीख चुनें)", 15f, Ui.TEXT) {
            background = rounded(Color.WHITE, dpf(12f), 0xFFB9C5D0.toInt(), dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        dateView.setOnClickListener { pickDate(Fmt.isoDay(System.currentTimeMillis())) { d -> date = d; dateView.text = Fmt.date(d) } }
        val box = vertical(16) {
            addView(item, lp()); addView(reason, lp().margins(context, t = 8)); addView(dateView, lp().margins(context, t = 8))
        }
        val d = AlertDialog.Builder(this).setTitle("बाकी काम जोड़ें").setView(box)
            .setPositiveButton("जोड़ें", null).setNegativeButton("Cancel", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (item.text.isBlank()) { item.error = "ज़रूरी"; return@setOnClickListener }
                d.dismiss()
                val arr = JSONArray().put(JSONObject().put("item", item.text.toString().trim()).put("reason", reason.text.toString().trim()).put("date", date))
                busy("Save हो रहा है…", {
                    Api.call(this, "pending", JSONObject().put("siteId", site.id).put("items", arr))
                    Sites.reload(this)
                }) { site = Sites.byId(site.id) ?: site; render() }
            }
        }
        d.show()
    }

    private fun closePending(item: String) {
        confirm("काम पूरा हुआ?", "\"$item\" को पूरा mark करें?", "पूरा हुआ") {
            busy("Save हो रहा है…", {
                Api.call(this, "pending", JSONObject().put("siteId", site.id).put("close", JSONArray().put(item)))
                Sites.reload(this)
            }) { site = Sites.byId(site.id) ?: site; render() }
        }
    }

    private fun renderSignoff() {
        body.addView(section("ग्राहक की पुष्टि और Sign") {
            addView(CheckBox(context).apply {
                text = "ग्राहक को ON/OFF करना और देखभाल बता दी"
                textSize = 15f
                isChecked = draft.demo
                setOnCheckedChangeListener { _, v -> draft.demo = v }
            })
            addView(signRow("Team Leader Sign", draft.tlSign, Session.user(context)?.name ?: ""), lp().margins(context, t = 10))
            addView(signRow("ग्राहक का Sign", draft.customerSign, site.customer), lp().margins(context, t = 10))
            addView(text("मैं पुष्टि करता हूँ कि ऊपर लिखा सारा काम AVIP के मानक के अनुसार पूरा किया गया है।", 13f, Ui.MUTED), lp().margins(context, t = 10))
        })
    }

    private fun signRow(label: String, file: File, name: String): View = vertical {
        addView(text(label, 13.5f, Ui.MUTED, bold = true))
        addView(android.widget.FrameLayout(context).apply {
            background = rounded(Color.WHITE, dpf(12f), if (file.exists()) Ui.LINE else 0xFF8A99A8.toInt(), dp(if (file.exists()) 1 else 2))
            clipToOutline = true
            if (file.exists()) addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
            }, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            else addView(text("यहाँ दबाकर Sign करें", 15f, Ui.MUTED) { gravity = Gravity.CENTER }, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            setOnClickListener {
                signer.launch(Intent(this@InstallActivity, SignActivity::class.java)
                    .putExtra(SignActivity.EXTRA_FILE, file.absolutePath)
                    .putExtra(SignActivity.EXTRA_NAME, name)
                    .putExtra(SignActivity.EXTRA_TITLE, label))
            }
        }, lp(MATCH, dp(90)).margins(context, t = 6))
    }

    private fun takePhoto(item: CheckItem) {
        photoFor = item
        val n = photoItems().indexOfFirst { it.id == item.id }
        camera.launch(Intent(this, CameraActivity::class.java)
            .putExtra(CameraActivity.EXTRA_DIR, Work.dir(this, site.id).absolutePath)
            .putExtra(CameraActivity.EXTRA_TITLE, item.text)
            .putExtra(CameraActivity.EXTRA_SUBTITLE, site.customer + if (n >= 0) " · Photo ${n + 1} / ${photoItems().size}" else " · ${item.section}")
            .putExtra(CameraActivity.EXTRA_PREFIX, item.id + "_r${site.round}"))
    }

    // ------------------------------------------------------------ save & submit

    private fun resultsJson(): JSONArray = JSONArray().apply {
        checkItems().filter { draft.result(it.id).isNotBlank() }.forEach {
            put(JSONObject().put("id", it.id).put("text", it.text).put("result", draft.result(it.id)).put("remark", draft.remark(it.id)))
        }
    }

    private fun saveProgress(quiet: Boolean) {
        val res = resultsJson()
        if (res.length() == 0) { if (!quiet) toast("अभी कुछ भरा नहीं"); return }
        busy("Save हो रहा है…", {
            Api.call(this, "checklist", JSONObject().put("siteId", site.id).put("round", site.round.toString()).put("results", res))
            Sites.reload(this)
        }) {
            site = Sites.byId(site.id) ?: site
            if (!quiet) toast("Save हो गया")
            render()
        }
    }

    private fun problems(): List<String> {
        val p = mutableListOf<String>()
        checkItems().forEach { item ->
            val r = draft.result(item.id)
            if (r.isBlank()) p += "Checklist बाकी: ${item.text}"
            if (r == "Not OK" && draft.remark(item.id).isBlank()) p += "Remark लिखें: ${item.text}"
            if (r == "Not OK" && item.photoOnNotOk && !hasPhoto(item.id)) p += "Photo लें: ${item.text}"
        }
        photoItems().filter { !hasPhoto(it.id) }.forEach { p += "Photo बाकी: ${it.text}" }
        val inverters = site.serials.filter { it.optString("Item Type").contains("Inverter", true) }
        if (inverters.isNotEmpty() && inverters.none { it.optString("Matched at Site") == "Yes" }) p += "Inverter का Serial No. मिलाएँ"
        if (!draft.customerSign.exists()) p += "ग्राहक का Sign बाकी"
        if (!draft.tlSign.exists()) p += "Team Leader का Sign बाकी"
        return p
    }

    private fun submit() {
        val p = problems()
        if (p.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("पहले यह पूरा करें")
                .setMessage(p.take(12).joinToString("\n") + if (p.size > 12) "\n…और ${p.size - 12}" else "")
                .setPositiveButton("OK", null).show()
            return
        }
        confirm("Office को Submit करें?", "Checklist, photos और sign Office को जाएँगे और Manager QC करेंगे।", "Submit") {
            busy("Submit हो रहा है… (photos upload होने तक रुकें)", {
                Api.call(this, "checklist", JSONObject().put("siteId", site.id).put("round", site.round.toString()).put("results", resultsJson()))
                Uploads.flush(this)
                val start = System.currentTimeMillis()
                while (Uploads.pendingFor(this, site.id).isNotEmpty() && System.currentTimeMillis() - start < 240_000) {
                    Thread.sleep(2000)
                    Uploads.flush(this)
                }
                val left = Uploads.pendingFor(this, site.id).size
                if (left > 0) throw ApiException("$left photos अभी upload नहीं हुईं। Internet ठीक होने पर फिर Submit दबाएँ। (सब कुछ phone में save है)")
                fun b64(f: File) = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                Api.call(this, "submitInstall", JSONObject().put("siteId", site.id).put("demo", draft.demo)
                    .put("tlSignature", b64(draft.tlSign)).put("customerSignature", b64(draft.customerSign)), timeoutMs = 120000)
                Sites.reload(this)
                // the Installation Report goes to the office (and so to the customer's AVIP Solar app) every time
                val fresh = Sites.byId(site.id) ?: site
                try { ReportPdf.saveToOffice(this, fresh) } catch (e: ApiException) { if (e.sessionExpired) throw e; null } catch (_: Exception) { null }
            }) { report ->
                site = Sites.byId(site.id) ?: site
                AlertDialog.Builder(this).setTitle("Submit हो गया ✓")
                    .setMessage("Report Office को भेज दी गई और ग्राहक की AVIP Solar App में भी दिखेगी। Manager QC करेंगे।\n\nअब ग्राहक को Installation Report WhatsApp पर भी भेजें?")
                    .setCancelable(false)
                    .setPositiveButton("WhatsApp पर भेजें") { _, _ ->
                        if (report != null) { ReportPdf.sendOnWhatsApp(this, site, report); finish() }
                        else ReportPdf.shareFor(this, site) { finish() }
                    }
                    .setNegativeButton("बाद में") { _, _ -> finish() }
                    .show()
            }
        }
    }
}

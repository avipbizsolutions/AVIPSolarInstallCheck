package com.avip.installcheck

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** One site: everything known about it, and the next step for the logged-in team. */
class SiteActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "siteId"
    }

    private lateinit var siteId: String
    private lateinit var user: User

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        siteId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        user = Session.user(this) ?: run { finish(); return }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val s = Sites.byId(siteId) ?: run { finish(); return }
        val st = s.status
        val actions = vertical { }
        val body = screen(s.customer, "${s.id} · ${Status.label(st)}", right = listOf(barButton(R.drawable.ic_refresh) { reload() }), bottom = actions)

        // ---- status
        body.addView(card(16) {
            addView(horizontal {
                addView(vertical {
                    addView(text(s.customer, 18f, Ui.TEXT, bold = true))
                    addView(text(s.subtitle, 14f, Ui.MUTED))
                }, lp(0, WRAP, 1f))
                addView(statusPill(st))
            })
            val remark = when (st) {
                Status.SENT_BACK -> s.s("Manager Note").let { if (it.isBlank()) "" else "Manager: $it" }
                Status.REWORK -> (s.lastSignoff?.optString("QC Remarks") ?: "").let { if (it.isBlank()) "" else "QC Rework: $it" }
                else -> ""
            }
            if (remark.isNotBlank()) addView(note(remark, Ui.DANGER_LIGHT, Ui.DANGER), lp().margins(context, t = 10))
            if (s.fromSalesApp) addView(note("AVIP Solar Sales से आया · Visit ${s.s("Sales Visit No.")}" +
                (if (s.s("Quoted System").isNotBlank()) "\nQuoted: ${s.s("Quoted System")}" else ""),
                Ui.GREEN_LIGHT, 0xFF145E39.toInt()), lp().margins(context, t = 10))
        }, lp().margins(this, b = 12))

        // ---- customer & location
        val canEditSite = (user.isSales && (st == Status.DRAFT || st == Status.SENT_BACK)) || user.isManager
        body.addView(section("Customer & Site", null) {
            if (canEditSite) addView(linkRow("Customer / Location बदलें", R.drawable.ic_edit) {
                startActivity(Intent(this@SiteActivity, SiteFormActivity::class.java).putExtra(EXTRA_ID, s.id))
            })
            addView(actionRow("Mobile", s.mobile.ifBlank { "—" }, R.drawable.ic_call) { call(s.mobile) })
            val wa = s.s("WhatsApp")
            if (wa.isNotBlank() && wa != s.mobile) addView(kv("WhatsApp", wa))
            addView(actionRow("Address", listOf(s.address, s.district).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "—" }, R.drawable.ic_location) {
                if (s.mapsUrl().isBlank()) toast("Map location नहीं लगी है") else openUrl(s.mapsUrl())
            })
            if (s.mapsUrl().isBlank()) addView(note("Google Map location नहीं लगी है", Ui.DANGER_LIGHT, Ui.DANGER))
            addView(kv("PM Surya Ghar App No.", s.s("PM Surya Ghar App No.").ifBlank { "—" }))
            addView(kv("DISCOM · CA No.", listOf(s.s("DISCOM"), s.s("CA No.")).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" }))
            addView(kv("Sales", s.salesPerson))
            if (s.s("Sales Note").isNotBlank()) addView(note("Sales Note: " + s.s("Sales Note"), Ui.SUN_LIGHT, 0xFF4A3300.toInt()))
            if (s.s("Site Report PDF").isNotBlank()) addView(linkRow("Sales Site Visit Report (PDF)", R.drawable.ic_pdf) { openUrl(s.s("Site Report PDF")) })
        })

        // ---- plan
        if (Status.step(st) >= 2 || s.installTeam.isNotBlank()) {
            body.addView(section("Dispatch & Installation", null) {
                addView(kv("Dispatch Date", Fmt.date(s.s("Dispatch Date")).ifBlank { "—" }))
                addView(kv("Installation Date", Fmt.date(s.s("Installation Date")).ifBlank { "—" }))
                addView(kv("Installation Team", s.installTeam.ifBlank { "—" }))
                addView(kv("Verified by", listOf(s.s("Verified By"), Fmt.date(s.s("Verified On"))).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" }))
                if (s.s("Manager Note").isNotBlank() && st != Status.SENT_BACK) addView(note("Manager: " + s.s("Manager Note")))
            })
        }

        // ---- materials
        val mats = s.materials
        val canEditMaterials = (user.isSales && (st == Status.DRAFT || st == Status.SENT_BACK)) || (user.isManager && Status.step(st) <= 2)
        body.addView(section("Material List", if (mats.isEmpty()) "अभी कोई material नहीं" else "${mats.count { qtyNeeded(it) > 0 }} items") {
            if (canEditMaterials) addView(linkRow(if (mats.isEmpty()) "Material List बनाएँ" else "Material List बदलें", R.drawable.ic_edit) {
                startActivity(Intent(this@SiteActivity, MaterialsActivity::class.java).putExtra(EXTRA_ID, s.id)
                    .putExtra(MaterialsActivity.EXTRA_MODE, if (user.isManager) MaterialsActivity.MODE_MANAGER else MaterialsActivity.MODE_SALES))
            })
            if (mats.isNotEmpty()) addView(materialTable(mats, Status.step(st) >= 2))
        })

        // ---- serials
        if (s.serials.isNotEmpty()) body.addView(section("Serial Numbers", "${s.serials.size} scanned at godown") {
            s.serials.forEach { r ->
                val matched = r.optString("Matched at Site") == "Yes"
                addView(horizontal {
                    addView(vertical {
                        addView(text(r.optString("Serial No."), 14.5f, Ui.TEXT, bold = true))
                        addView(text(r.optString("Item Type"), 12.5f, Ui.MUTED))
                    }, lp(0, WRAP, 1f))
                    if (matched) addView(pill("Site पर मिला ✓", Ui.GREEN_LIGHT, 0xFF145E39.toInt()))
                    val link = r.optString("Label Photo Link")
                    if (link.isNotBlank()) addView(icon(R.drawable.ic_camera, Ui.BRAND, 22).apply {
                        setPadding(dp(4), dp(4), dp(4), dp(4)); setOnClickListener { openUrl(link) }
                    }, lp(dp(36), dp(36)).margins(context, l = 8))
                }, lp().margins(context, b = 8))
            }
        })

        // ---- installation
        if (s.checklistRows.isNotEmpty() || s.photos.isNotEmpty() || s.signoffs.isNotEmpty()) {
            body.addView(section("Installation", "Round ${s.round}") {
                val rows = s.checklistRows.filter { it.optString("Round") == s.round.toString() }
                val ok = rows.count { it.optString("Result") == "OK" }
                val notOk = rows.filter { it.optString("Result") == "Not OK" }
                val na = rows.count { it.optString("Result") == "NA" }
                addView(horizontal {
                    addView(countBox(ok.toString(), "OK", Ui.GREEN_LIGHT, 0xFF145E39.toInt()), lp(0, WRAP, 1f).margins(context, r = 6))
                    addView(countBox(notOk.size.toString(), "Not OK", Ui.DANGER_LIGHT, Ui.DANGER), lp(0, WRAP, 1f).margins(context, r = 6))
                    addView(countBox(na.toString(), "NA", Ui.BRAND_LIGHT, Ui.TEXT), lp(0, WRAP, 1f).margins(context, r = 6))
                    addView(countBox(s.photos.size.toString(), "Photos", Ui.SUN_LIGHT, Ui.SUN_TEXT), lp(0, WRAP, 1f))
                })
                notOk.forEach { r ->
                    addView(note("Not OK: ${r.optString("Checklist Item")}" + (if (r.optString("Remark").isNotBlank()) "\n→ ${r.optString("Remark")}" else ""),
                        Ui.DANGER_LIGHT, Ui.DANGER), lp().margins(context, t = 8))
                }
                s.pending.filter { it.optString("Status") == "Open" }.forEach { p ->
                    addView(note("बाकी: ${p.optString("Pending Item")}" +
                        (if (p.optString("Reason").isNotBlank()) " · ${p.optString("Reason")}" else "") +
                        (if (p.optString("Expected Date").isNotBlank()) " · ${Fmt.date(p.optString("Expected Date"))}" else ""),
                        Ui.SUN_LIGHT, 0xFF4A3300.toInt()), lp().margins(context, t = 8))
                }
                s.lastSignoff?.let { so ->
                    addView(kv("Submitted", "${so.optString("Team Leader")} · ${Fmt.date(so.optString("Submitted On"))}"), lp().margins(context, t = 8))
                    addView(kv("Customer demo", so.optString("Customer Demo Given").ifBlank { "—" }))
                    if (so.optString("QC Result").isNotBlank()) addView(kv("QC", "${so.optString("QC Result")} · ${so.optString("QC By")}"))
                    if (so.optString("Customer Signature Link").isNotBlank()) addView(linkRow("Customer signature", R.drawable.ic_sign) { openUrl(so.optString("Customer Signature Link")) })
                    if (so.optString("Report PDF Link").isNotBlank()) addView(linkRow("Installation Report (PDF)", R.drawable.ic_pdf) { openUrl(so.optString("Report PDF Link")) })
                }
                if (s.photos.isNotEmpty() && (user.isManager || user.isInstaller)) {
                    addView(linkRow("सभी Photos Drive में देखें", R.drawable.ic_camera) { showPhotos(s) })
                }
            })
        }

        Uploads.errors(this, s.id).takeIf { it.isNotEmpty() }?.let { errs ->
            body.addView(note("Upload में दिक्कत:\n" + errs.take(5).joinToString("\n"), Ui.DANGER_LIGHT, Ui.DANGER))
        }

        // ---- next step for this team
        buildActions(actions, s)
        if (actions.childCount == 0) (actions.parent as? View)?.visibility = View.GONE
    }

    private fun qtyNeeded(m: JSONObject): Double {
        val a = m.optString("Qty Approved (Manager)")
        return if (a.isBlank()) m.optDouble("Qty Required (Sales)", 0.0) else m.optDouble("Qty Approved (Manager)", 0.0)
    }

    private fun materialTable(mats: List<JSONObject>, showDispatch: Boolean): View = vertical {
        addView(horizontal {
            addView(text("Item", 12f, Ui.MUTED, bold = true), lp(0, WRAP, 1f))
            addView(text("माँगा", 12f, Ui.MUTED, bold = true) { gravity = Gravity.END }, lp(dp(54), WRAP))
            if (showDispatch) {
                addView(text("भेजा", 12f, Ui.MUTED, bold = true) { gravity = Gravity.END }, lp(dp(50), WRAP))
                addView(text("बाकी", 12f, Ui.MUTED, bold = true) { gravity = Gravity.END }, lp(dp(46), WRAP))
            }
        }, lp().margins(context, b = 4))
        mats.filter { qtyNeeded(it) > 0 || it.optDouble("Qty Dispatched", 0.0) > 0 }.forEach { m ->
            val bal = m.optDouble("Balance", 0.0)
            addView(horizontal {
                setPadding(0, dp(6), 0, dp(6))
                addView(vertical {
                    addView(text(m.optString("Item"), 14f, Ui.TEXT))
                    val make = m.optString("Make / Size")
                    if (make.isNotBlank()) addView(text(make, 12f, Ui.MUTED))
                }, lp(0, WRAP, 1f))
                addView(text("${Fmt.qty(qtyNeeded(m))} ${m.optString("Unit")}", 13.5f, Ui.TEXT, bold = true) { gravity = Gravity.END }, lp(dp(54), WRAP))
                if (showDispatch) {
                    addView(text(Fmt.qty(m.optDouble("Qty Dispatched", 0.0)), 13.5f, Ui.TEXT) { gravity = Gravity.END }, lp(dp(50), WRAP))
                    addView(text(Fmt.qty(bal), 13.5f, if (bal > 0) Ui.DANGER else 0xFF145E39.toInt(), bold = true) { gravity = Gravity.END }, lp(dp(46), WRAP))
                }
            })
        }
    }

    private fun countBox(value: String, label: String, bg: Int, fg: Int): View = vertical {
        gravity = Gravity.CENTER
        background = rounded(bg, dpf(10f))
        setPadding(dp(4), dp(8), dp(4), dp(8))
        addView(text(value, 19f, fg, bold = true) { gravity = Gravity.CENTER })
        addView(text(label, 11.5f, fg) { gravity = Gravity.CENTER })
    }

    private fun linkRow(label: String, iconRes: Int, onClick: () -> Unit): View = horizontal {
        setPadding(0, dp(8), 0, dp(8))
        isClickable = true
        foreground = context.ripple()
        addView(icon(iconRes, Ui.BRAND, 20))
        addView(text(label, 14.5f, Ui.BRAND, bold = true), lp(0, WRAP, 1f).margins(context, l = 10))
        addView(text("›", 20f, Ui.MUTED))
        setOnClickListener { onClick() }
    }

    private fun actionRow(label: String, value: String, iconRes: Int, onClick: () -> Unit): View = horizontal {
        setPadding(0, dp(6), 0, dp(6))
        addView(vertical {
            addView(text(label, 12.5f, Ui.MUTED))
            addView(text(value, 15f, Ui.TEXT))
        }, lp(0, WRAP, 1f))
        addView(icon(iconRes, Ui.GREEN, 22).apply {
            background = rounded(Color.WHITE, dpf(22f), Ui.LINE, dp(1))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick() }
        }, lp(dp(44), dp(44)))
    }

    private fun showPhotos(s: Site) {
        val items = s.photos.map { "${it.optString("Photo Type")} ${it.optString("Linked Item ID")}".trim() + " · " + it.optString("Taken By") }
        AlertDialog.Builder(this).setTitle("Photos (${items.size})")
            .setItems(items.toTypedArray()) { _, i -> openUrl(s.photos[i].optString("Drive Link")) }
            .setPositiveButton("Close", null).show()
    }

    // ------------------------------------------------------------ actions

    private fun buildActions(box: LinearLayout, s: Site) {
        val st = s.status
        fun add(v: View) = box.addView(v, lp().margins(this, b = 8))

        if ((user.isSales || user.isManager) && (st == Status.DRAFT || st == Status.SENT_BACK)) {
            add(pillButton("Manager को Verify भेजें", Ui.GREEN, Color.WHITE, heightDp = 54) { submitToManager(s) })
        }
        if (user.isManager && st == Status.SUBMITTED) {
            add(pillButton("Verify करें", Ui.SUN, Ui.TEXT, heightDp = 54) {
                startActivity(Intent(this, VerifyActivity::class.java).putExtra(EXTRA_ID, s.id))
            })
        }
        val dispatchOpen = st == Status.APPROVED || (Status.step(st) == 3 && s.balanceTotal() > 0)
        if ((user.isDispatch || user.isManager) && dispatchOpen) {
            add(pillButton(if (st == Status.APPROVED) "Dispatch शुरू करें" else "Balance Dispatch करें", Ui.TEAL, Color.WHITE, heightDp = 54) {
                startActivity(Intent(this, DispatchActivity::class.java).putExtra(EXTRA_ID, s.id))
            })
        }
        if ((user.isInstaller || user.isManager) && st in listOf(Status.DISPATCHED, Status.BALANCE, Status.IN_PROGRESS, Status.REWORK)) {
            add(pillButton(if (st == Status.IN_PROGRESS || st == Status.REWORK) "Installation Checklist जारी रखें" else "Installation शुरू करें",
                Ui.SUN, Ui.TEXT, heightDp = 54) {
                startActivity(Intent(this, InstallActivity::class.java).putExtra(EXTRA_ID, s.id))
            })
        }
        if ((user.isInstaller || user.isManager) && (st == Status.INSTALL_SUBMITTED || st == Status.CLOSED)) {
            add(pillButton("Customer Report WhatsApp पर भेजें", Ui.GREEN, Color.WHITE, heightDp = 50) {
                ReportPdf.shareFor(this, s)
            })
        }
        if (user.isManager && st == Status.INSTALL_SUBMITTED) {
            add(horizontal {
                addView(pillButton("Rework", Ui.DANGER, Color.WHITE, heightDp = 52) { qc(s, false) }, lp(0, WRAP, 1f).margins(context, r = 8))
                addView(pillButton("QC Approve ✓", Ui.GREEN, Color.WHITE, heightDp = 52) { qc(s, true) }, lp(0, WRAP, 1.4f))
            })
        }
    }

    private fun submitToManager(s: Site) {
        if (s.materials.none { qtyNeeded(it) > 0 }) {
            toast("पहले Material List भरें")
            startActivity(Intent(this, MaterialsActivity::class.java).putExtra(EXTRA_ID, s.id)
                .putExtra(MaterialsActivity.EXTRA_MODE, if (user.isManager) MaterialsActivity.MODE_MANAGER else MaterialsActivity.MODE_SALES))
            return
        }
        if (s.mapsUrl().isBlank()) { toast("पहले Google Map location लगाएँ"); return }
        confirm("Manager को भेजें?", "Site details और Material List Manager के पास Verify के लिए जाएगी। उसके बाद आप बदल नहीं सकेंगे।", "भेजें") {
            busy("भेजा जा रहा है…", {
                Api.call(this, "saveSite", JSONObject().put("site", JSONObject().put("Site ID", s.id)).put("submit", true))
                Sites.reload(this)
            }) { toast("Manager को भेज दिया"); render() }
        }
    }

    private fun qc(s: Site, approve: Boolean) {
        val remarks = input(if (approve) "Remark (optional)" else "क्या ठीक करना है? (ज़रूरी)")
        val portal = CheckBox(this).apply { text = "PM Surya Ghar portal पर upload हो गया"; textSize = 14f }
        val box = vertical(16) {
            addView(remarks, lp())
            if (approve) addView(portal, lp().margins(context, t = 8))
        }
        val d = AlertDialog.Builder(this).setTitle(if (approve) "QC Approve करें?" else "Rework के लिए वापस भेजें")
            .setView(box).setPositiveButton(if (approve) "Approve" else "Rework भेजें", null).setNegativeButton("Cancel", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val r = remarks.text.toString().trim()
                if (!approve && r.isBlank()) { remarks.error = "ज़रूरी है"; return@setOnClickListener }
                d.dismiss()
                busy("Save हो रहा है…", {
                    Api.call(this, "qc", JSONObject().put("siteId", s.id).put("result", if (approve) "Approved" else "Rework")
                        .put("remarks", r).put("portalDone", portal.isChecked))
                    Sites.reload(this)
                }) { toast(if (approve) "QC Approved · Site closed" else "Rework के लिए भेजा"); render() }
            }
        }
        d.show()
    }

    private fun reload() {
        busy("Refresh…", { Sites.reload(this) }) { render() }
    }
}

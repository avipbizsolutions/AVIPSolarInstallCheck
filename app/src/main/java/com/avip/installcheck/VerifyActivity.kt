package com.avip.installcheck

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** Manager: check a site from Sales, set dates and team, then approve to Dispatch or send back. */
class VerifyActivity : AppCompatActivity() {

    private lateinit var site: Site
    private var dispatchDate = ""
    private var installDate = ""
    private var team = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        site = Sites.byId(intent.getStringExtra(SiteActivity.EXTRA_ID)) ?: run { finish(); return }
        dispatchDate = site.s("Dispatch Date").take(10)
        installDate = site.s("Installation Date").take(10)
        team = site.installTeam
    }

    override fun onResume() {
        super.onResume()
        site = Sites.byId(site.id) ?: site
        build()
    }

    private fun build() {
        val noteInput = input(tr("Note (Sales को वापस भेजने पर ज़रूरी)", "Note (required when sending back to sales)"))
        val buttons = vertical {
            addView(pillButton(tr("Approve → Dispatch को भेजें", "Approve → send to dispatch"), Ui.GREEN, Color.WHITE, heightDp = 54) { approve(noteInput.text.toString().trim()) }, lp())
            addView(pillButton(tr("Sales को वापस भेजें", "Send back to sales"), Color.WHITE, Ui.DANGER, heightDp = 48) { sendBack(noteInput.text.toString().trim()) }.apply {
                background = rounded(Color.WHITE, dpf(24f), Ui.DANGER, dp(1))
            }, lp().margins(context, t = 8))
        }
        val body = screen("Verify: ${site.customer}", "${site.subtitle} · Sales: ${site.salesPerson}", bottom = buttons)

        body.addView(section("Customer & Site") {
            addView(kv("Mobile", site.mobile))
            addView(kv("Address", listOf(site.address, site.district).filter { it.isNotBlank() }.joinToString(", ")))
            addView(kv("PM Surya Ghar App No.", site.s("PM Surya Ghar App No.").ifBlank { "—" }))
            addView(kv("DISCOM · CA", listOf(site.s("DISCOM"), site.s("CA No.")).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" }))
            if (site.mapsUrl().isBlank()) addView(note(tr("Map location नहीं लगी है", "Map location not set"), Ui.DANGER_LIGHT, Ui.DANGER))
            else addView(text(tr("Map खोलें ›", "Open map ›"), 14.5f, Ui.BRAND, bold = true) {
                setPadding(0, dp(8), 0, dp(4)); setOnClickListener { openUrl(site.mapsUrl()) }
            })
            addView(text(tr("Customer / Location बदलें ›", "Edit customer / location ›"), 14.5f, Ui.BRAND, bold = true) {
                setPadding(0, dp(8), 0, dp(4))
                setOnClickListener { startActivity(Intent(this@VerifyActivity, SiteFormActivity::class.java).putExtra(SiteActivity.EXTRA_ID, site.id)) }
            })
            if (site.s("Sales Note").isNotBlank()) addView(note("Sales Note: " + site.s("Sales Note"), Ui.SUN_LIGHT, 0xFF4A3300.toInt()))
        })

        body.addView(section("Material List", tr("Sales ने जो माँगा", "What sales requested")) {
            val mats = site.materials.filter { it.optDouble("Qty Required (Sales)", 0.0) > 0 || it.optString("Qty Approved (Manager)").isNotBlank() }
            if (mats.isEmpty()) addView(note(tr("Material List खाली है", "Material list is empty"), Ui.DANGER_LIGHT, Ui.DANGER))
            mats.forEach { m ->
                val approved = m.optString("Qty Approved (Manager)")
                addView(horizontal {
                    setPadding(0, dp(5), 0, dp(5))
                    addView(text(m.optString("Item") + (m.optString("Make / Size").let { if (it.isBlank()) "" else " · $it" }), 14f, Ui.TEXT), lp(0, WRAP, 1f))
                    addView(text((if (approved.isNotBlank()) approved else Fmt.qty(m.optDouble("Qty Required (Sales)", 0.0))) + " " + m.optString("Unit"),
                        14f, Ui.TEXT, bold = true) { gravity = Gravity.END })
                })
            }
            addView(text(tr("Qty बदलें (Approve) ›", "Change quantities (approve) ›"), 14.5f, Ui.BRAND, bold = true) {
                setPadding(0, dp(10), 0, dp(4))
                setOnClickListener {
                    startActivity(Intent(this@VerifyActivity, MaterialsActivity::class.java).putExtra(SiteActivity.EXTRA_ID, site.id)
                        .putExtra(MaterialsActivity.EXTRA_MODE, MaterialsActivity.MODE_MANAGER))
                }
            })
        })

        body.addView(section(tr("Dispatch & Installation तय करें", "Set dispatch & installation")) {
            addView(text("Dispatch Date", 13f, Ui.MUTED, bold = true), lp().margins(context, t = 4, b = 6))
            addView(dateBox(dispatchDate) { dispatchDate = it })
            addView(text("Installation Date", 13f, Ui.MUTED, bold = true), lp().margins(context, t = 12, b = 6))
            addView(dateBox(installDate) { installDate = it })
            addView(text("Installation Team / Site Incharge", 13f, Ui.MUTED, bold = true), lp().margins(context, t = 12, b = 6))
            if (Ref.installers.isEmpty()) addView(note(tr("Users tab में Installation team का कोई user नहीं है", "No one in the Team Directory has the Installation role"), Ui.DANGER_LIGHT, Ui.DANGER))
            else addView(chips(Ref.installers, team) { team = it })
        })
        body.addView(noteInput, lp())
    }

    private fun dateBox(initial: String, onPick: (String) -> Unit): TextView = text(if (initial.isBlank()) tr("तारीख चुनें", "Choose date") else Fmt.date(initial), 16f, Ui.TEXT) {
        background = rounded(Color.WHITE, dpf(12f), 0xFFB7C6D8.toInt(), dp(1))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        var cur = initial
        setOnClickListener {
            pickDate(cur.ifBlank { Fmt.isoDay(System.currentTimeMillis()) }) { d -> cur = d; text = Fmt.date(d); onPick(d) }
        }
    }

    private fun approve(note: String) {
        val err = when {
            dispatchDate.isBlank() -> tr("Dispatch Date चुनें", "Choose dispatch date")
            installDate.isBlank() -> tr("Installation Date चुनें", "Choose installation date")
            team.isBlank() -> tr("Installation Team चुनें", "Choose installation team")
            site.mapsUrl().isBlank() -> tr("Map location नहीं है; पहले लगवाएँ", "No map location; get it set first")
            else -> null
        }
        if (err != null) { toast(err); return }
        confirm(tr("Approve करें?", "Approve?"), tr("Site Dispatch team को जाएगी। Installation: ${Fmt.date(installDate)}, Team: $team", "The site goes to the dispatch team. Installation: ${Fmt.date(installDate)}, team: $team"), "Approve") {
            busy(tr("Approve हो रहा है…", "Approving…"), {
                Api.call(this, "verify", JSONObject().put("siteId", site.id).put("action", "approve").put("note", note)
                    .put("dispatchDate", dispatchDate).put("installationDate", installDate).put("installationTeam", team))
                Sites.reload(this)
            }) { toast(tr("Approved · Dispatch को भेज दिया", "Approved · sent to dispatch")); finish() }
        }
    }

    private fun sendBack(note: String) {
        if (note.isBlank()) { toast(tr("Sales के लिए Note लिखें (नीचे)", "Write a note for sales (below)")); return }
        busy(tr("भेजा जा रहा है…", "Sending…"), {
            Api.call(this, "verify", JSONObject().put("siteId", site.id).put("action", "sendBack").put("note", note))
            Sites.reload(this)
        }) { toast(tr("Sales को वापस भेजा", "Sent back to sales")); finish() }
    }
}

package com.avip.installcheck

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

/** One line of the material list being edited. */
class MatLine(
    val sr: String, val section: String, val item: String, var make: String, val unit: String,
    var qty: Double, val reference: Double?, val serial: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().put("sr", sr).put("item", item).put("make", make).put("unit", unit).put("qty", qty)
}

object Materials {
    private fun needed(m: JSONObject): Double {
        val a = m.optString("Qty Approved (Manager)")
        return if (a.isBlank()) m.optDouble("Qty Required (Sales)", 0.0) else m.optDouble("Qty Approved (Manager)", 0.0)
    }

    /**
     * BOM Master items merged with what the site already has.
     * [mode]: sales = Qty Required, manager = Qty Approved, dispatch = Qty Dispatched.
     */
    fun lines(site: Site, mode: String): List<MatLine> {
        val rows = site.materials.associateBy { it.optString("Sr. No.").trim() }
        val out = mutableListOf<MatLine>()
        val seen = HashSet<String>()
        Ref.bom.forEach { b ->
            val r = rows[b.sr]
            seen += b.sr
            out += line(b.sr, b.section, b.item, r?.optString("Make / Size")?.ifBlank { b.make } ?: b.make, b.unit, b.serial, r, mode, b.qty)
        }
        site.materials.filter { it.optString("Sr. No.").trim() !in seen }.forEach { r ->
            out += line(r.optString("Sr. No.").trim(), "Other", r.optString("Item"), r.optString("Make / Size"), r.optString("Unit"), false, r, mode, 0.0)
        }
        return out
    }

    private fun line(sr: String, section: String, item: String, make: String, unit: String, serial: Boolean, r: JSONObject?, mode: String, default: Double): MatLine {
        val qty: Double
        var ref: Double? = null
        when (mode) {
            MaterialsActivity.MODE_MANAGER -> {
                qty = if (r == null) default else needed(r)
                ref = r?.optDouble("Qty Required (Sales)", 0.0)
            }
            MaterialsActivity.MODE_DISPATCH -> {
                val sent = r?.optDouble("Qty Dispatched", 0.0) ?: 0.0
                val need = if (r == null) 0.0 else needed(r)
                qty = if (sent > 0) sent else need
                ref = need
            }
            else -> qty = if (r == null) default else r.optDouble("Qty Required (Sales)", 0.0)
        }
        return MatLine(sr, section, item, make, unit, qty, ref, serial)
    }

    fun toJson(lines: List<MatLine>): JSONArray = JSONArray().apply { lines.forEach { put(it.toJson()) } }
}

/**
 * Sections of the BOM with a − / + stepper per item. Sections open and close;
 * tapping the number lets you type it (e.g. 0.5 bag cement).
 */
class MaterialEditor(private val a: Activity, private val lines: List<MatLine>, private val mode: String) {

    fun view(): View = a.vertical {
        lines.groupBy { it.section }.forEach { (section, items) ->
            val box = a.vertical { visibility = if (openByDefault(items)) View.VISIBLE else View.GONE }
            val count = a.text("", 13f, Ui.MUTED)
            fun paintCount() { count.text = "${items.count { it.qty > 0 }} items" }
            paintCount()
            val arrow = a.text(if (box.visibility == View.VISIBLE) "▲" else "▼", 14f, Ui.MUTED)
            addView(a.card(0) {
                addView(a.horizontal {
                    setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
                    isClickable = true
                    foreground = a.ripple()
                    addView(a.vertical {
                        addView(a.text(section, 15.5f, Ui.TEXT, bold = true))
                        addView(count)
                    }, lp(0, WRAP, 1f))
                    addView(arrow)
                    setOnClickListener {
                        box.visibility = if (box.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        arrow.text = if (box.visibility == View.VISIBLE) "▲" else "▼"
                    }
                })
                items.forEach { l -> box.addView(row(l) { paintCount() }) }
                addView(box)
            }, lp().margins(a, b = 10))
        }
    }

    private fun openByDefault(items: List<MatLine>) = items.any { it.serial } ||
        (mode == MaterialsActivity.MODE_DISPATCH && items.any { (it.reference ?: 0.0) > 0 }) ||
        items.any { it.section.contains("Cable", true) }

    private fun row(l: MatLine, onChange: () -> Unit): View = a.horizontal {
        setPadding(a.dp(14), a.dp(8), a.dp(10), a.dp(8))
        background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE) }
        val qtyView: TextView = a.text(Fmt.qty(l.qty), 16f, Ui.TEXT, bold = true) { gravity = Gravity.CENTER }
        val info = a.vertical {
            addView(a.text(l.item, 14.5f, Ui.TEXT, bold = true))
            val sub = listOf(l.make, l.unit).filter { it.isNotBlank() }.joinToString(" · ") +
                (l.reference?.let { r -> if (mode == MaterialsActivity.MODE_DISPATCH) " · माँगा ${Fmt.qty(r)}" else if (r > 0 && Fmt.qty(r) != Fmt.qty(l.qty)) " · Sales: ${Fmt.qty(r)}" else "" } ?: "")
            val subView = a.text(sub, 12.5f, Ui.MUTED)
            addView(subView)
            if (mode != MaterialsActivity.MODE_DISPATCH) {
                isClickable = true
                setOnClickListener {
                    a.askText("Make / Size: ${l.item}", "जैसे Microtek 540 Wp", l.make, required = false) { v ->
                        l.make = v
                        subView.text = listOf(l.make, l.unit).filter { it.isNotBlank() }.joinToString(" · ")
                    }
                }
            }
        }
        addView(info, lp(0, WRAP, 1f))
        fun set(v: Double) {
            l.qty = v.coerceAtLeast(0.0)
            qtyView.text = Fmt.qty(l.qty)
            qtyView.setTextColor(if (mode == MaterialsActivity.MODE_DISPATCH && l.reference != null && l.qty < l.reference) Ui.DANGER else Ui.TEXT)
            onChange()
        }
        set(l.qty)
        addView(a.horizontal {
            background = rounded(Color.WHITE, a.dpf(10f), 0xFFB9C5D0.toInt(), a.dp(1))
            addView(a.text("−", 20f, Ui.TEXT) {
                gravity = Gravity.CENTER
                setOnClickListener { set(l.qty - 1) }
            }, lp(a.dp(42), a.dp(44)))
            qtyView.setOnClickListener {
                a.askText(l.item, "Qty (${l.unit})", Fmt.qty(l.qty)) { v -> v.replace(',', '.').toDoubleOrNull()?.let { set(it) } }
            }
            addView(qtyView, lp(a.dp(44), a.dp(44)))
            addView(a.text("+", 20f, Ui.TEXT) {
                gravity = Gravity.CENTER
                setOnClickListener { set(l.qty + 1) }
            }, lp(a.dp(42), a.dp(44)))
        })
    }
}

/** Material list of a site: Sales fills required qty, the Manager approved qty. */
class MaterialsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SALES = "sales"
        const val MODE_MANAGER = "manager"
        const val MODE_DISPATCH = "dispatch"
    }

    private lateinit var site: Site
    private lateinit var lines: List<MatLine>
    private lateinit var mode: String
    private lateinit var noteInput: android.widget.EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = Sites.byId(intent.getStringExtra(SiteActivity.EXTRA_ID)) ?: run { finish(); return }
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SALES
        if (!Ref.isLoaded) { toast("BOM list load नहीं हुई। Internet check करें।"); finish(); return }
        lines = Materials.lines(site, mode)

        val save = pillButton("Save करें", Ui.SUN, Ui.TEXT, heightDp = 54) { save() }
        val body = screen("Material List (BOM)", "${site.customer} · ${site.subtitle}", bottom = save)
        body.addView(note(if (mode == MODE_MANAGER)
            "Manager: यहाँ बदली Qty \"Approved\" मानी जाएगी और Dispatch इसी से होगा।"
        else "Default Qty (हर site के लिए) पहले से भरी है। Site के हिसाब से बदलें। Item पर दबाकर Make / Size लिखें।",
            Ui.SUN_LIGHT, 0xFF4A3300.toInt()))
        body.addView(MaterialEditor(this, lines, mode).view())
        if (mode == MODE_SALES) {
            body.addView(text("Manager / Dispatch के लिए Note", 13f, Ui.MUTED, bold = true), lp().margins(this, t = 6, b = 6))
            noteInput = input("जैसे: छत तीसरी मंज़िल पर, लंबा DC cable लगेगा", InputType.TYPE_CLASS_TEXT, site.s("Sales Note"))
            body.addView(noteInput, lp())
        }
    }

    private fun save() {
        val siteJson = JSONObject().put("Site ID", site.id)
        if (mode == MODE_SALES && ::noteInput.isInitialized) siteJson.put("Sales Note", noteInput.text.toString().trim())
        val body = JSONObject().put("site", siteJson).put("materials", Materials.toJson(lines))
        if (mode == MODE_SALES) body.put("asSales", true)
        busy("Save हो रहा है…", {
            Api.call(this, "saveSite", body)
            Sites.reload(this)
        }) { toast("Material List save हो गई"); finish() }
    }
}

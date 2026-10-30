package com.avip.installcheck

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Each team's home: the sites they work on, with filters. */
class HomeActivity : AppCompatActivity() {

    private lateinit var user: User
    private lateinit var listBox: LinearLayout
    private lateinit var stats: LinearLayout
    private lateinit var filterRow: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var uploadsNote: TextView
    private var filter = "Action"
    private var loadedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        user = Session.user(this) ?: run { startActivity(Intent(this, LoginActivity::class.java)); finish(); return }
        filter = filters().first().first
        Ref.loadCached(this)
        build()
    }

    override fun onResume() {
        super.onResume()
        Uploads.onChange = { showUploads() }
        Uploads.flush(this)
        showUploads()
        if (loadedOnce) render()
        load()
    }

    override fun onPause() {
        super.onPause()
        Uploads.onChange = null
    }

    /** (key, label) filters per team. */
    private fun filters(): List<Pair<String, String>> = when (user.team) {
        Team.MANAGER -> listOf("Action" to "आपके Action", "All" to "सभी Sites", "Closed" to "Closed")
        Team.SALES -> listOf("All" to "सभी", Status.DRAFT to "Draft", Status.SENT_BACK to "वापस आई", Status.SUBMITTED to "Verify में", "Later" to "आगे बढ़ी")
        Team.DISPATCH -> listOf("All" to "सभी", Status.APPROVED to "Dispatch के लिए", "Balance" to "Balance बाकी")
        else -> listOf("Open" to "आज के Jobs", Status.REWORK to "Rework", Status.INSTALL_SUBMITTED to "Submitted")
    }

    private fun matches(s: Site, key: String = filter): Boolean = when (key) {
        "All" -> true
        "Action" -> s.status == Status.SUBMITTED || s.status == Status.INSTALL_SUBMITTED
        "Closed" -> s.status == Status.CLOSED
        "Later" -> Status.step(s.status) >= 2
        "Balance" -> s.balanceTotal() > 0 && s.status != Status.APPROVED
        "Open" -> s.status in listOf(Status.DISPATCHED, Status.BALANCE, Status.IN_PROGRESS)
        else -> s.status == key
    }

    private fun build() {
        val header = vertical {
            setBackgroundColor(Ui.BRAND)
            setPadding(dp(18), dp(16), dp(10), dp(16))
            addView(horizontal {
                addView(badge(R.drawable.ic_sun, Ui.SUN, 40, square = true))
                addView(vertical {
                    addView(text("AVIP Solar", 12.5f, 0xFFC9D6E2.toInt()))
                    addView(text("Installation", 18f, Color.WHITE, bold = true))
                }, lp(0, WRAP, 1f).margins(context, l = 10))
                addView(pill(Team.label(user.team), Team.color(user.team), Color.WHITE))
                addView(barButton(R.drawable.ic_refresh) { load() })
                addView(barButton(R.drawable.ic_logout) { logout() })
            })
            addView(text("नमस्ते, ${user.name}", 15f, 0xFFC9D6E2.toInt()), lp().margins(context, t = 14))
            addView(text(when (user.team) {
                Team.MANAGER -> "सभी Sites का Status"
                Team.SALES -> "मेरी Sites"
                Team.DISPATCH -> "Dispatch के लिए Sites"
                else -> "मेरे Installation Jobs"
            }, 22f, Color.WHITE, bold = true), lp().margins(context, t = 2))
            stats = horizontal { }
            addView(stats, lp().margins(context, t = 12, r = 8))
        }
        filterRow = horizontal { setPadding(dp(12), dp(10), dp(12), dp(2)) }
        uploadsNote = text("", 13.5f, Ui.SUN_TEXT, bold = true) {
            background = rounded(Ui.SUN_LIGHT, dpf(10f))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            setOnClickListener { Uploads.flush(this@HomeActivity); toast("Upload फिर से शुरू…") }
        }
        loading = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        listBox = vertical { setPadding(dp(14), dp(8), dp(14), dp(100)) }
        val body = vertical {
            addView(header)
            addView(loading, lp(MATCH, dp(4)))
            addView(android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(filterRow)
            })
            addView(uploadsNote, lp().margins(context, l = 14, r = 14, t = 8))
            addView(listBox)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(ScrollView(context).apply { addView(body) }, FrameLayout.LayoutParams(MATCH, MATCH))
            if (user.isSales || user.isManager) {
                addView(pillButton("+  नई Site", Ui.SUN, Ui.TEXT, heightDp = 56) {
                    startActivity(Intent(this@HomeActivity, SiteFormActivity::class.java))
                }.apply { elevation = dpf(6f) }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, dp(18), dp(22))
                })
            }
        }
        setContentView(root)
        paintFilters()
    }

    private fun paintFilters() {
        filterRow.removeAllViews()
        filters().forEach { (key, label) ->
            val n = Sites.all.count { matches(it, key) }
            val on = key == filter
            filterRow.addView(text(if (loadedOnce) "$label $n" else label, 14f, if (on) Color.WHITE else Ui.TEXT, bold = true) {
                background = if (on) rounded(Ui.BRAND, dpf(18f)) else rounded(Color.WHITE, dpf(18f), Ui.LINE, dp(1))
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { filter = key; render() }
            }, lp(WRAP, WRAP).margins(this, r = 8))
        }
    }

    private fun stat(value: Int, label: String, highlight: Boolean = false): View = vertical {
        gravity = Gravity.CENTER
        background = rounded(0xFF1D3A52.toInt(), dpf(10f))
        setPadding(dp(4), dp(8), dp(4), dp(8))
        addView(text(value.toString(), 20f, if (highlight && value > 0) Ui.SUN else Color.WHITE, bold = true) { gravity = Gravity.CENTER })
        addView(text(label, 11.5f, 0xFFC9D6E2.toInt()) { gravity = Gravity.CENTER; maxLines = 1 })
    }

    private fun renderStats() {
        stats.removeAllViews()
        val all = Sites.all
        val items: List<Triple<Int, String, Boolean>> = when (user.team) {
            Team.MANAGER -> listOf(
                Triple(all.count { it.status == Status.SUBMITTED }, "Verify", true),
                Triple(all.count { it.status == Status.APPROVED || it.status == Status.BALANCE }, "Dispatch", false),
                Triple(all.count { it.status in listOf(Status.DISPATCHED, Status.IN_PROGRESS, Status.REWORK) }, "Install", false),
                Triple(all.count { it.status == Status.INSTALL_SUBMITTED }, "QC", true),
                Triple(all.count { it.status == Status.CLOSED }, "Closed", false),
            )
            Team.SALES -> listOf(
                Triple(all.count { it.status == Status.DRAFT }, "Draft", false),
                Triple(all.count { it.status == Status.SENT_BACK }, "वापस आई", true),
                Triple(all.count { Status.step(it.status) >= 2 }, "आगे बढ़ी", false),
            )
            Team.DISPATCH -> listOf(
                Triple(all.count { it.status == Status.APPROVED }, "Dispatch", true),
                Triple(all.count { it.balanceTotal() > 0 && it.status != Status.APPROVED }, "Balance", true),
            )
            else -> listOf(
                Triple(all.count { it.status in listOf(Status.DISPATCHED, Status.BALANCE, Status.IN_PROGRESS) }, "Jobs", false),
                Triple(all.count { it.status == Status.REWORK }, "Rework", true),
                Triple(all.count { it.status == Status.INSTALL_SUBMITTED }, "Submitted", false),
            )
        }
        items.forEach { (v, l, h) -> stats.addView(stat(v, l, h), lp(0, WRAP, 1f).margins(this, r = 6)) }
    }

    private fun render() {
        renderStats()
        paintFilters()
        listBox.removeAllViews()
        val shown = Sites.all.filter { matches(it) }
        if (shown.isEmpty()) {
            listBox.addView(text(if (loadedOnce) "यहाँ अभी कोई Site नहीं है।" else "Load हो रहा है…", 15f, Ui.MUTED) {
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, dp(40))
            }, lp())
        }
        shown.forEach { listBox.addView(siteCard(it), lp().margins(this, b = 12)) }
    }

    private fun siteCard(s: Site): View = card(16) {
        isClickable = true
        foreground = context.ripple()
        val danger = s.status == Status.SENT_BACK || s.status == Status.REWORK
        if (danger) background = rounded(Color.WHITE, dpf(16f), Ui.DANGER, dp(2))
        addView(horizontal {
            gravity = Gravity.TOP
            addView(vertical {
                addView(text(s.customer, 16.5f, Ui.TEXT, bold = true))
                addView(text(s.subtitle.ifBlank { s.id }, 14f, Ui.MUTED))
            }, lp(0, WRAP, 1f))
            addView(statusPill(s.status))
        })
        if (s.fromSalesApp && Status.step(s.status) == 0) {
            addView(note("AVIP Solar Sales से आया · Visit ${s.s("Sales Visit No.")}" +
                (if (s.s("Installation Date").isNotBlank()) " · Install ${Fmt.date(s.s("Installation Date"))}" else ""),
                Ui.GREEN_LIGHT, 0xFF145E39.toInt()), lp().margins(context, t = 10))
        }
        val remark = when (s.status) {
            Status.SENT_BACK -> s.s("Manager Note").let { if (it.isNotBlank()) "Manager: $it" else "" }
            Status.REWORK -> s.lastSignoff?.optString("QC Remarks")?.let { if (it.isNotBlank()) "QC: $it" else "" } ?: ""
            else -> ""
        }
        if (remark.isNotBlank()) addView(note(remark, Ui.DANGER_LIGHT, Ui.DANGER), lp().margins(context, t = 10))
        // pipeline bar
        val step = Status.step(s.status)
        addView(horizontal {
            for (i in 0 until 5) addView(View(context).apply {
                background = rounded(if (i < step || step == 5) Ui.GREEN else if (i == step) Ui.SUN else Ui.LINE, dpf(3f))
            }, lp(0, dp(5), 1f).margins(context, r = 4))
        }, lp().margins(context, t = 12))
        addView(horizontal {
            listOf("Sales", "Verify", "Dispatch", "Install", "QC").forEach {
                addView(text(it, 11f, Ui.MUTED), lp(0, WRAP, 1f))
            }
        }, lp().margins(context, t = 4))
        val extra = listOf(
            if (s.address.isNotBlank()) s.address else "",
            if (s.s("Installation Date").isNotBlank() && Status.step(s.status) >= 2) "Install: ${Fmt.date(s.s("Installation Date"))}" else "",
            if (user.isManager || user.isDispatch) "Sales: ${s.salesPerson}" else "",
            if (s.installTeam.isNotBlank() && !user.isInstaller) "Team: ${s.installTeam}" else "",
        ).filter { it.isNotBlank() }.joinToString(" · ")
        if (extra.isNotBlank()) addView(text(extra, 13f, Ui.MUTED) { maxLines = 2 }, lp().margins(context, t = 8))
        setOnClickListener {
            startActivity(Intent(this@HomeActivity, SiteActivity::class.java).putExtra(SiteActivity.EXTRA_ID, s.id))
        }
    }

    private fun showUploads() {
        val n = Uploads.pendingCount(this)
        uploadsNote.visibility = if (n > 0) View.VISIBLE else View.GONE
        uploadsNote.text = "$n photo upload बाकी हैं · Internet आने पर अपने आप जाएँगी (दबाकर फिर कोशिश करें)"
    }

    private fun load() {
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    Ref.refresh(this@HomeActivity)
                    Sites.reload(this@HomeActivity)
                    null
                } catch (e: Exception) {
                    e
                }
            }
            loading.visibility = View.GONE
            if (error != null) {
                if (error is ApiException && error.sessionExpired) { showError(error); return@launch }
                toast(error.message ?: "Load नहीं हुआ")
            } else {
                loadedOnce = true
            }
            render()
        }
    }

    private fun logout() {
        val pendingUploads = Uploads.pendingCount(this)
        confirm("Logout?", if (pendingUploads > 0) "$pendingUploads photos अभी upload नहीं हुईं। Logout के बाद वे login करने पर जाएँगी।" else "क्या आप Logout करना चाहते हैं?") {
            Session.clear(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}

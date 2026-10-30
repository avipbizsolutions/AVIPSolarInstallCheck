package com.avip.installcheck

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Login with mobile number + 4-digit PIN. The team (Sales, Manager, Dispatch,
 * Installation) comes from the Users tab of the office sheet. With the office
 * (temporary) PIN, the person must first set a PIN of their own.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var form: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session.mustSetPin(this) && Session.token(this).isNotBlank()) showSetPin() else showLogin()
    }

    private fun frame(title: String, subtitle: String): LinearLayout {
        val head = vertical {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(56), dp(28), dp(28))
            addView(badge(R.drawable.ic_sun, Ui.SUN, 72, square = true))
            addView(text(subtitle, 15f, 0xFFC9D6E2.toInt()) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 14))
            addView(text(title, 26f, Color.WHITE, bold = true) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 2))
        }
        form = vertical(24) {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(dpf(28f), dpf(28f), dpf(28f), dpf(28f), 0f, 0f, 0f, 0f)
            }
        }
        val root = vertical {
            setBackgroundColor(Ui.BRAND)
            addView(head)
            addView(form, lp(MATCH, 0, 1f))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.WHITE)
            addView(root)
        })
        return form
    }

    private fun showLogin() {
        val f = frame("Solar Installation", "AVIP Solar")
        f.addView(text("Login करें", 20f, Ui.TEXT, bold = true))
        val mobile = field(f, "Mobile Number", "", "10 अंकों का number", InputType.TYPE_CLASS_PHONE, R.drawable.ic_call, 13) {}
        val pin = field(f, "4 अंकों का PIN", "", "••••", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, R.drawable.ic_sign, 4) {}
        f.addView(pillButton("Login →", Ui.SUN, Ui.TEXT, heightDp = 56) {
            val m = mobile.text.toString().filter { it.isDigit() }.takeLast(10)
            val p = pin.text.toString().trim()
            if (m.length != 10) { mobile.error = "10 अंकों का number डालें"; return@pillButton }
            if (p.length != 4) { pin.error = "4 अंकों का PIN"; return@pillButton }
            busy("Login हो रहा है…", {
                Api.call(this, "login", JSONObject().put("mobile", m).put("pin", p).put("token", ""))
            }) { o ->
                val user = User.from(o.getJSONObject("user"))
                val must = o.optBoolean("mustSetPin")
                Session.save(this, o.optString("token"), user, must)
                if (must) showSetPin() else openHome()
            }
        }, lp().margins(this, t = 20))
        f.addView(note("आपकी Team (Sales, Manager, Dispatch, Installation) Office की Google Sheet से अपने आप तय होगी। आपको सिर्फ अपनी Team का काम दिखेगा।"), lp().margins(this, t = 18))
        f.addView(text("PIN भूल गए? Manager से नया temporary PIN लें।", 13f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 4))
    }

    private fun showSetPin() {
        val user = Session.user(this)
        val f = frame("अपना नया PIN बनाएँ", "नमस्ते, ${user?.name ?: ""} · ${Team.label(user?.team ?: "")}")
        f.addView(text("पहली बार Login पर Office वाला PIN बदलना ज़रूरी है।", 15f, Ui.TEXT))
        val numPass = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val pin1 = field(f, "नया 4 अंकों का PIN", "", "••••", numPass, R.drawable.ic_sign, 4) {}
        val pin2 = field(f, "PIN दोबारा डालें", "", "••••", numPass, R.drawable.ic_sign, 4) {}
        f.addView(vertical {
            listOf(
                "1234, 0000, 1111 जैसा आसान PIN नहीं चलेगा",
                "Office भी आपका PIN नहीं देख सकता",
                "भूल गए तो Manager नया temporary PIN देंगे",
            ).forEach { line ->
                addView(horizontal {
                    addView(text("✓", 14f, Ui.GREEN, bold = true), lp(WRAP, WRAP).margins(context, r = 8))
                    addView(text(line, 14f, Ui.TEXT), lp(0, WRAP, 1f))
                }, lp().margins(context, t = 6))
            }
        }, lp().margins(this, t = 16))
        f.addView(pillButton("PIN Save करें →", Ui.SUN, Ui.TEXT, heightDp = 56) {
            val a = pin1.text.toString().trim()
            val b = pin2.text.toString().trim()
            if (a.length != 4) { pin1.error = "4 अंक"; return@pillButton }
            if (a != b) { pin2.error = "दोनों PIN एक जैसे नहीं हैं"; return@pillButton }
            busy("PIN save हो रहा है…", {
                Api.call(this, "setPin", JSONObject().put("newPin", a))
            }) { o ->
                Session.save(this, o.optString("token"), User.from(o.getJSONObject("user")), false)
                toast("नया PIN save हो गया")
                openHome()
            }
        }, lp().margins(this, t = 22))
        f.addView(text("Logout", 14f, Ui.MUTED, bold = true) {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { Session.clear(this@LoginActivity); showLogin() }
        }, lp())
    }

    private fun openHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}

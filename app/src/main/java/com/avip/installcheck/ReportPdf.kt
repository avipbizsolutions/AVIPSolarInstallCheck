package com.avip.installcheck

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import kotlin.math.min

/**
 * The customer's Installation Report: AVIP Solar letterhead, customer and system,
 * checklist results, serial numbers, the GPS photos, pending work and both signatures.
 * Saved to the site's Drive folder and shared to the customer on WhatsApp.
 */
object ReportPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 36f

    private class Writer(private val c: Context, private val doc: PdfDocument) {
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f
        var n = 0

        fun newPage() {
            finish()
            n++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, n).create())
            page = p
            canvas = p.canvas
            y = M
            if (n > 1) {
                text("AVIP Solar · Installation Report (continued)", 9f, Ui.MUTED)
                y += 4f
            }
        }

        fun finish() {
            val p = page ?: return
            val cv = p.canvas
            val foot = paint(8.5f, Ui.MUTED)
            cv.drawText("AVIP Solar · AIIMS–Naubatpur New Nahar Road, Patna – 801109 · 92881 76289 · contact@avipsolar.in · avipsolar.in", M, H - 20f, foot)
            cv.drawText("Page $n", W - M - 30f, H - 20f, foot)
            doc.finishPage(p)
            page = null
        }

        fun ensure(h: Float) { if (page == null || y + h > H - 40f) newPage() }

        fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        fun layout(t: String, p: TextPaint, width: Int): StaticLayout =
            StaticLayout.Builder.obtain(t, 0, t.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

        fun text(t: String, size: Float = 10f, color: Int = Ui.TEXT, bold: Boolean = false, x: Float = M, width: Float = W - 2 * M) {
            val l = layout(t, paint(size, color, bold), width.toInt())
            ensure(l.height.toFloat())
            val cv = canvas!!
            cv.save(); cv.translate(x, y); l.draw(cv); cv.restore()
            y += l.height + 2f
        }

        fun heading(t: String) {
            ensure(30f)
            y += 8f
            val cv = canvas!!
            cv.drawRoundRect(RectF(M, y, W - M, y + 20f), 4f, 4f, Paint().apply { color = Ui.BRAND })
            cv.drawText(t, M + 8f, y + 14f, paint(11f, Color.WHITE, true))
            y += 26f
        }

        fun kv(k: String, v: String) {
            if (v.isBlank()) return
            val kp = paint(9.5f, Ui.MUTED)
            val vl = layout(v, paint(10f, Ui.TEXT, true), (W - 2 * M - 150).toInt())
            ensure(vl.height + 4f)
            val cv = canvas!!
            cv.drawText(k, M, y + 10f, kp)
            cv.save(); cv.translate(M + 150f, y); vl.draw(cv); cv.restore()
            y += vl.height + 4f
        }

        fun line() {
            val cv = canvas ?: return
            cv.drawLine(M, y, W - M, y, Paint().apply { color = Ui.LINE; strokeWidth = 0.8f })
            y += 3f
        }

        fun image(bmp: Bitmap, x: Float, w: Float, h: Float) {
            val cv = canvas!!
            val scale = min(w / bmp.width, h / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            cv.drawBitmap(bmp, null, RectF(x + (w - dw) / 2, y + (h - dh) / 2, x + (w + dw) / 2, y + (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun load(path: String, maxDim: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= maxDim || o.outHeight / (s * 2) >= maxDim) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (_: Exception) { null }

    /** Builds the PDF in the cache folder (blocking). */
    fun build(c: Context, site: Site): File {
        val draft = InstallDraft.latest(c, site)
        val doc = PdfDocument()
        val w = Writer(c, doc)
        w.newPage()

        // letterhead
        val logo = try { BitmapFactory.decodeResource(c.resources, R.drawable.avip_solar_logo) } catch (_: Exception) { null }
        if (logo != null) { w.image(logo, M, 150f, 50f); }
        val cv = w.canvas!!
        cv.drawText("Installation Report", W - M - 190f, w.y + 22f, w.paint(18f, Ui.TEXT, true))
        cv.drawText("Site ID: ${site.id}", W - M - 190f, w.y + 40f, w.paint(10f, Ui.MUTED))
        w.y += 60f
        w.line()

        val so = site.lastSignoff
        w.heading("Customer & System")
        w.kv("Customer", site.customer)
        w.kv("Mobile", site.mobile)
        w.kv("Address", listOf(site.address, site.district).filter { it.isNotBlank() }.joinToString(", "))
        w.kv("System", site.subtitle)
        w.kv("PM Surya Ghar App No.", site.s("PM Surya Ghar App No."))
        w.kv("DISCOM · CA No.", listOf(site.s("DISCOM"), site.s("CA No.")).filter { it.isNotBlank() }.joinToString(" · "))
        w.kv("GPS Location", site.mapsUrl())
        w.kv("Installation Date", Fmt.date(so?.optString("Submitted On") ?: site.s("Installation Date")))
        w.kv("Installation Team", so?.optString("Team Leader")?.ifBlank { null } ?: site.installTeam)

        // materials with serials
        val serials = site.serials
        if (serials.isNotEmpty()) {
            w.heading("Serial Numbers")
            serials.groupBy { it.optString("Item Type") }.forEach { (type, list) ->
                w.kv(type, list.joinToString(", ") { it.optString("Serial No.") })
            }
        }

        // checklist
        w.heading("Quality Checklist")
        val round = site.round.toString()
        val rows = site.checklistRows.filter { it.optString("Round") == round }.associateBy { it.optString("Item ID") }
        Ref.checklist.filter { !it.isPhoto }.forEach { item ->
            val r = rows[item.id]?.optString("Result")?.ifBlank { null } ?: draft?.result(item.id) ?: ""
            val remark = rows[item.id]?.optString("Remark")?.ifBlank { null } ?: draft?.remark(item.id) ?: ""
            val color = when (r) { "OK" -> Ui.GREEN; "Not OK" -> Ui.DANGER; else -> Ui.MUTED }
            val tl = w.layout(item.text + if (remark.isNotBlank()) "\n→ $remark" else "", w.paint(9.5f, Ui.TEXT), (W - 2 * M - 70).toInt())
            w.ensure(tl.height + 6f)
            val cvs = w.canvas!!
            cvs.save(); cvs.translate(M, w.y); tl.draw(cvs); cvs.restore()
            cvs.drawText(if (r.isBlank()) "—" else r, W - M - 55f, w.y + 10f, w.paint(10f, color, true))
            w.y += tl.height + 6f
            w.line()
        }

        // photos
        val photoItems = Ref.checklist.filter { it.isPhoto }
        val photos = photoItems.mapNotNull { item -> draft?.photos(item.id)?.lastOrNull()?.let { item to it } }
        if (photos.isNotEmpty()) {
            w.heading("Site Photos (GPS stamped)")
            val colW = (W - 2 * M - 12) / 2
            val imgH = colW * 0.75f
            photos.chunked(2).forEach { pair ->
                w.ensure(imgH + 30f)
                pair.forEachIndexed { i, (item, path) ->
                    val x = M + i * (colW + 12)
                    load(path, 900)?.let { bmp -> w.image(bmp, x, colW, imgH); bmp.recycle() }
                    val cap = w.layout(item.text, w.paint(8.5f, Ui.MUTED), colW.toInt())
                    val cvs = w.canvas!!
                    cvs.save(); cvs.translate(x, w.y + imgH + 3f); cap.draw(cvs); cvs.restore()
                }
                w.y += imgH + 28f
            }
        }

        // pending
        val open = site.pending.filter { it.optString("Status") == "Open" }
        if (open.isNotEmpty()) {
            w.heading("बाकी काम")
            open.forEach { p ->
                w.text("• " + p.optString("Pending Item") +
                    (if (p.optString("Reason").isNotBlank()) " — ${p.optString("Reason")}" else "") +
                    (if (p.optString("Expected Date").isNotBlank()) " (by ${Fmt.date(p.optString("Expected Date"))})" else ""), 10f)
            }
        }

        // signatures
        w.heading("Sign-off")
        w.text(if ((so?.optString("Customer Demo Given") ?: "") == "Yes" || draft?.demo == true)
            "✓ ग्राहक को system ON/OFF करना और देखभाल बताई गई।" else "", 10f)
        w.text("Panels को सुबह साफ पानी और मुलायम कपड़े से साफ करें। Detergent या कड़ा brush न लगाएँ। कोई दिक्कत हो तो AVIP Solar को call करें: 92881 76289", 9.5f, Ui.MUTED)
        w.ensure(110f)
        val sigW = (W - 2 * M - 20) / 2
        listOf("Team Leader" to draft?.tlSign, "Customer" to draft?.customerSign).forEachIndexed { i, (label, f) ->
            val x = M + i * (sigW + 20)
            val cvs = w.canvas!!
            cvs.drawRoundRect(RectF(x, w.y, x + sigW, w.y + 80f), 6f, 6f, Paint().apply { color = Ui.LINE; style = Paint.Style.STROKE; strokeWidth = 1f })
            if (f != null && f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.let { b ->
                val keepY = w.y
                w.y += 4f
                w.image(b, x + 4f, sigW - 8f, 72f)
                w.y = keepY
                b.recycle()
            }
            cvs.drawText(label, x, w.y + 94f, w.paint(9.5f, Ui.MUTED, true))
        }
        w.y += 104f
        w.finish()

        val dir = File(c.cacheDir, "shared").apply { mkdirs() }
        val out = File(dir, "Installation_Report_${site.id}.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    /** Builds the report, saves a copy to the office Drive and opens WhatsApp to the customer. */
    fun shareFor(a: AppCompatActivity, site: Site, after: (() -> Unit)? = null) {
        a.busy("Report बन रही है…", {
            val f = build(a, site)
            try {
                Api.call(a, "report", JSONObject().put("siteId", site.id)
                    .put("data", android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)), timeoutMs = 120000)
            } catch (e: ApiException) {
                if (e.sessionExpired) throw e
            }
            f
        }) { f ->
            val uri = FileProvider.getUriForFile(a, a.packageName + ".files", f)
            val number = site.s("WhatsApp").ifBlank { site.mobile }.filter { it.isDigit() }.takeLast(10)
            val base = Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, "नमस्ते ${site.customer} जी, AVIP Solar की ओर से आपके Solar installation की report संलग्न है। धन्यवाद! — AVIP Solar, 92881 76289")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (number.length == 10) base.putExtra("jid", "91$number@s.whatsapp.net")
            val opened = listOf("com.whatsapp", "com.whatsapp.w4b").any { pkg ->
                try { a.startActivity(Intent(base).setPackage(pkg)); true } catch (_: Exception) { false }
            }
            if (!opened) a.startActivity(Intent.createChooser(base, "Report भेजें"))
            Thread { try { Api.call(a, "sentToCustomer", JSONObject().put("siteId", site.id)) } catch (_: Exception) {} }.start()
            after?.invoke()
        }
    }
}

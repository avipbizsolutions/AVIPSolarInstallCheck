package com.avip.installcheck

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Photos and serial labels are uploaded in the background, one by one, and kept
 * in a queue on the phone until the office sheet confirms them. So work at a site
 * with weak signal is never lost; it goes up when the signal comes back.
 */
object Uploads {
    private const val PREFS = "uploads"
    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread whenever the queue changes. */
    var onChange: (() -> Unit)? = null

    private fun read(c: Context): JSONArray =
        JSONArray(c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("queue", "[]") ?: "[]")

    private fun write(c: Context, a: JSONArray) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("queue", a.toString()).apply()
    }

    private fun changed() = main.post { onChange?.invoke() }

    /** A GPS photo for a site. [linkedItem] = checklist item (ST1, PH1 …) or empty. Returns its id. */
    fun photo(
        c: Context, siteId: String, file: File, stage: String, type: String,
        linkedItem: String = "", lat: Double? = null, lng: Double? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        add(c, JSONObject().put("id", id).put("kind", "photo").put("siteId", siteId).put("path", file.absolutePath)
            .put("stage", stage).put("type", type).put("linkedItem", linkedItem)
            .put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL)
            .put("takenAt", Fmt.now()).put("name", file.name))
        return id
    }

    /** A serial number with its label photo (Dispatch). */
    fun serial(c: Context, siteId: String, itemType: String, make: String, serial: String, file: File?) {
        add(c, JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "serial").put("siteId", siteId)
            .put("itemType", itemType).put("make", make).put("serial", serial).put("path", file?.absolutePath ?: ""))
    }

    private fun add(c: Context, item: JSONObject) {
        synchronized(this) {
            val a = read(c)
            a.put(item)
            write(c, a)
        }
        changed()
        flush(c)
    }

    fun pendingFor(c: Context, siteId: String): List<JSONObject> = synchronized(this) {
        list(read(c)).filter { it.optString("siteId") == siteId }
    }

    fun pendingCount(c: Context): Int = synchronized(this) { read(c).length() }

    /** Linked items (PH1, ST1 …) still waiting to upload for this site. */
    fun pendingLinks(c: Context, siteId: String): Set<String> =
        pendingFor(c, siteId).map { it.optString("linkedItem") }.filter { it.isNotBlank() }.toSet()

    /** Problems the office rejected (e.g. a serial already used elsewhere), newest first. */
    fun errors(c: Context, siteId: String): List<String> {
        val a = JSONArray(c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("errors", "[]") ?: "[]")
        return list(a).filter { it.optString("siteId") == siteId }.map { it.optString("message") }.reversed()
    }

    fun clearErrors(c: Context, siteId: String) {
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val a = JSONArray(p.getString("errors", "[]") ?: "[]")
            val keep = JSONArray()
            list(a).filter { it.optString("siteId") != siteId }.forEach { keep.put(it) }
            p.edit().putString("errors", keep.toString()).apply()
        }
        changed()
    }

    private fun recordError(c: Context, siteId: String, message: String) {
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val a = JSONArray(p.getString("errors", "[]") ?: "[]")
            a.put(JSONObject().put("siteId", siteId).put("message", message))
            val trimmed = JSONArray()
            for (i in maxOf(0, a.length() - 50) until a.length()) trimmed.put(a.get(i))
            p.edit().putString("errors", trimmed.toString()).apply()
        }
    }

    private fun removeFirst(c: Context, id: String) = synchronized(this) {
        val a = read(c)
        val rest = JSONArray()
        list(a).filter { it.optString("id") != id }.forEach { rest.put(it) }
        write(c, rest)
    }

    /** Sends everything waiting. Safe to call often. */
    fun flush(context: Context) {
        val c = context.applicationContext
        if (running) return
        running = true
        Thread {
            try {
                while (true) {
                    val item = synchronized(this) { read(c).optJSONObject(0) } ?: break
                    val body = JSONObject(item.toString())
                    val path = body.optString("path")
                    body.remove("path")
                    if (path.isNotBlank()) {
                        val f = File(path)
                        if (f.exists()) body.put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                        else if (body.optString("kind") == "photo") {
                            removeFirst(c, item.optString("id")); changed(); continue
                        }
                    }
                    if (body.optString("kind") == "photo") body.put("photoId", item.optString("id"))
                    try {
                        Api.call(c, body.optString("kind"), body, timeoutMs = 120000)
                        removeFirst(c, item.optString("id"))
                        changed()
                    } catch (e: ApiException) {
                        if (e.sessionExpired) break
                        if (e.message?.startsWith("No internet") == true || e.message?.contains("did not answer") == true) break
                        // the office refused it: drop it and tell the user
                        recordError(c, item.optString("siteId"), (if (item.optString("kind") == "serial") "Serial ${item.optString("serial")}: " else "") + (e.message ?: ""))
                        removeFirst(c, item.optString("id"))
                        changed()
                    }
                }
            } catch (_: Exception) {
            } finally {
                running = false
            }
        }.start()
    }
}

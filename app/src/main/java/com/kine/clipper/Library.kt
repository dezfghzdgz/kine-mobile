package com.kine.clipper

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Klip v knihovně appky (soubor je v Galerii - MediaStore). */
data class Clip(
    val id: String,
    val uri: String,
    var title: String,
    val createdAt: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    /** none / queued / uploading / done / error */
    var uploadState: String = "none",
    var uploadPercent: Int = 0,
    var videoId: String? = null,
    var url: String? = null,
    var error: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("uri", uri); put("title", title); put("createdAt", createdAt); put("durationMs", durationMs)
        put("sizeBytes", sizeBytes); put("width", width); put("height", height); put("uploadState", uploadState)
        put("uploadPercent", uploadPercent); put("videoId", videoId ?: JSONObject.NULL); put("url", url ?: JSONObject.NULL); put("error", error ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): Clip = Clip(
            id = o.getString("id"), uri = o.getString("uri"), title = o.optString("title", "Kine"), createdAt = o.optLong("createdAt"),
            durationMs = o.optLong("durationMs"), sizeBytes = o.optLong("sizeBytes"), width = o.optInt("width"), height = o.optInt("height"),
            uploadState = o.optString("uploadState", "none"), uploadPercent = o.optInt("uploadPercent"),
            videoId = o.optString("videoId").ifEmpty { null }.takeIf { !o.isNull("videoId") }, url = o.optString("url").ifEmpty { null }.takeIf { !o.isNull("url") },
            error = o.optString("error").ifEmpty { null }.takeIf { !o.isNull("error") },
        )
    }
}

/** Seznam klipů (files/library.json), nejnovější první. Změny hlásí posluchačům (okno). */
class Library private constructor(context: Context) {
    private val file = File(context.filesDir, "library.json")
    private val clips = ArrayList<Clip>()
    private val listeners = ArrayList<() -> Unit>()

    init {
        load()
    }

    @Synchronized private fun load() {
        clips.clear()
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) clips.add(Clip.fromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            Log.e("knihovna: čtení", e)
        }
    }

    @Synchronized private fun save() {
        try {
            val arr = JSONArray()
            for (c in clips) arr.put(c.toJson())
            file.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e("knihovna: zápis", e)
        }
    }

    @Synchronized fun list(): List<Clip> = ArrayList(clips)
    @Synchronized fun get(id: String): Clip? = clips.firstOrNull { it.id == id }

    @Synchronized fun add(clip: Clip) {
        clips.add(0, clip)
        save()
        notifyChanged()
    }

    @Synchronized fun update(id: String, change: (Clip) -> Unit) {
        val c = clips.firstOrNull { it.id == id } ?: return
        change(c)
        save()
        notifyChanged()
    }

    @Synchronized fun remove(context: Context, id: String) {
        val c = clips.firstOrNull { it.id == id } ?: return
        try {
            context.contentResolver.delete(Uri.parse(c.uri), null, null)
        } catch (e: Exception) {
            Log.w("knihovna: soubor z Galerie se nesmazal: ${e.message}")
        }
        clips.remove(c)
        save()
        notifyChanged()
    }

    @Synchronized fun queued(): List<Clip> = clips.filter { it.uploadState == "queued" }

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun notifyChanged() {
        val copy = synchronized(listeners) { ArrayList(listeners) }
        for (l in copy) try { l() } catch (_: Exception) { }
    }

    companion object {
        @Volatile private var instance: Library? = null
        fun get(context: Context): Library = instance ?: synchronized(this) { instance ?: Library(context.applicationContext).also { instance = it } }
    }
}

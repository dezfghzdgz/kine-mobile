package com.kine.clipper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class KineApiError(message: String, val status: Int, val code: String? = null) : IOException(message)

/**
 * Spojení s Kine (stejné API jako appka do PC): přihlášení přes /connect
 * (jednorázový token -> vlastní relace Supabase), obnova relace, profil,
 * nahrání klipu (lístek + tus do Cloudflare Stream + potvrzení) a stav
 * zpracování. Jen HttpURLConnection, žádné knihovny. Každý požadavek nese
 * "X-Kine-App: android/<verze>", ať Kine pozná appku a verzi.
 */
class KineApi(private val context: Context, private val settings: Settings) {
    private val appVersion: String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName?.substringBefore('-') ?: "0.0.0"
    } catch (_: Exception) {
        "0.0.0"
    }

    data class Config(val supabaseUrl: String, val supabaseAnonKey: String, val siteUrl: String)
    data class UploadTarget(val mode: String, val uploadUrl: String, val videoId: String)

    private var config: Config? = null

    // ---- základ ----

    private fun open(url: String, method: String, timeoutMs: Int = 20_000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        if (method == "PATCH") {
            // HttpURLConnection PATCH neumí - tus i Cloudflare berou POST s X-HTTP-Method-Override.
            c.requestMethod = "POST"
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        } else c.requestMethod = method
        c.setRequestProperty("X-Kine-App", "android/$appVersion")
        c.setRequestProperty("User-Agent", "KineClipper/$appVersion (Android)")
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    private fun readBody(c: HttpURLConnection): String {
        val stream: InputStream? = try { if (c.responseCode >= 400) c.errorStream else c.inputStream } catch (_: Exception) { c.errorStream }
        return stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    }

    private fun jsonCall(url: String, method: String, body: JSONObject?, auth: String?, extra: Map<String, String> = emptyMap(), timeoutMs: Int = 20_000): JSONObject {
        val c = open(url, method, timeoutMs)
        if (auth != null) c.setRequestProperty("Authorization", "Bearer $auth")
        for ((k, v) in extra) c.setRequestProperty(k, v)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
        }
        val status = c.responseCode
        val text = readBody(c)
        c.disconnect()
        val json = try { if (text.trim().startsWith("{")) JSONObject(text) else JSONObject() } catch (_: Exception) { JSONObject() }
        if (status == 426) throw KineApiError(json.optString("error", "This app version is too old - update it on Kine."), 426, "update-required")
        if (status < 200 || status >= 300) throw KineApiError(json.optString("error", "Kine answered $status."), status, json.optString("code").ifEmpty { null })
        return json
    }

    // ---- konfigurace a přihlášení ----

    fun config(): Config {
        config?.let { return it }
        val j = jsonCall("${settings.siteUrl}/api/desktop/config", "GET", null, null)
        val cfg = Config(j.getString("supabaseUrl").trimEnd('/'), j.getString("supabaseAnonKey"), j.optString("siteUrl", settings.siteUrl).trimEnd('/'))
        config = cfg
        return cfg
    }

    /** Jednorázový token z /connect -> vlastní relace (Supabase verify, type magiclink). */
    fun finishLogin(tokenHash: String) {
        val cfg = config()
        val j = jsonCall(
            "${cfg.supabaseUrl}/auth/v1/verify", "POST",
            JSONObject().put("type", "magiclink").put("token_hash", tokenHash),
            null, mapOf("apikey" to cfg.supabaseAnonKey),
        )
        storeSession(j)
        try {
            val me = me()
            settings.username = me.optString("username").ifEmpty { me.optString("display_name").ifEmpty { "kine" } }
        } catch (e: Exception) {
            Log.w("profil po přihlášení: ${e.message}")
            settings.username = settings.username ?: "kine"
        }
    }

    private fun storeSession(j: JSONObject) {
        val access = j.optString("access_token")
        val refresh = j.optString("refresh_token")
        if (access.isEmpty() || refresh.isEmpty()) throw KineApiError("Login did not return a session.", 500)
        settings.accessToken = access
        settings.refreshToken = refresh
        settings.tokenExpiresAt = System.currentTimeMillis() + (j.optLong("expires_in", 3600) * 1000L)
    }

    /** Platný přístupový token (před vypršením se obnoví obnovovacím tokenem). */
    @Synchronized fun token(): String {
        val access = settings.accessToken
        val refresh = settings.refreshToken ?: throw KineApiError("Not signed in.", 401)
        if (access != null && System.currentTimeMillis() < settings.tokenExpiresAt - 60_000) return access
        val cfg = config()
        try {
            val j = jsonCall("${cfg.supabaseUrl}/auth/v1/token?grant_type=refresh_token", "POST", JSONObject().put("refresh_token", refresh), null, mapOf("apikey" to cfg.supabaseAnonKey))
            storeSession(j)
        } catch (e: KineApiError) {
            // Obnovovací token už neplatí (odhlášení jinde, dlouho nepoužitý) - hráč se přihlásí znovu.
            if (e.status == 400 || e.status == 401 || e.status == 403) {
                settings.clearSession()
                throw KineApiError("Signed out - sign in again on Kine.", 401)
            }
            throw e
        }
        return settings.accessToken!!
    }

    fun me(): JSONObject = jsonCall("${settings.siteUrl}/api/desktop/me", "GET", null, token())

    fun logout() {
        settings.clearSession()
    }

    // ---- nahrání klipu ----

    fun createUploadUrl(fileSize: Long, durationSeconds: Double?): UploadTarget {
        val body = JSONObject().put("fileSize", fileSize).put("preferResumable", true)
        if (durationSeconds != null && durationSeconds > 0) body.put("durationSeconds", durationSeconds)
        val j = jsonCall("${settings.siteUrl}/api/videos/create-upload-url", "POST", body, token(), timeoutMs = 60_000)
        val url = j.optString("uploadURL")
        val id = j.optString("videoId")
        if (url.isEmpty() || id.isEmpty()) throw KineApiError("Kine did not return an upload address.", 500)
        return UploadTarget(if (j.optString("mode") == "tus") "tus" else "basic", url, id)
    }

    /** Kolik bajtů už Cloudflare má (tus HEAD). */
    fun tusOffset(url: String): Long {
        val c = open(url, "HEAD")
        c.setRequestProperty("Tus-Resumable", "1.0.0")
        val status = c.responseCode
        val offset = c.getHeaderField("Upload-Offset")?.toLongOrNull()
        c.disconnect()
        if (status >= 400 || offset == null) throw KineApiError("Upload offset unknown ($status).", status)
        return offset
    }

    /**
     * Jeden kus souboru PATCHem; vrací nový offset ze serveru. HttpURLConnection
     * PATCH neumí, tak jde požadavek přes vlastní HTTPS spojení (RawHttp); kdyby
     * to neprošlo (proxy), zkusí se POST s X-HTTP-Method-Override, který tus zná.
     */
    fun tusPatch(url: String, offset: Long, chunk: ByteArray, length: Int): Long {
        val headers = mapOf(
            "Tus-Resumable" to "1.0.0",
            "Upload-Offset" to offset.toString(),
            "Content-Type" to "application/offset+octet-stream",
            "X-Kine-App" to "android/$appVersion",
            "User-Agent" to "KineClipper/$appVersion (Android)",
        )
        val res = try {
            RawHttp.request(url, "PATCH", headers, chunk, length)
        } catch (e: Exception) {
            Log.w("tus PATCH napřímo nešel (${e.message}) - zkouším přes POST")
            val c = open(url, "PATCH", 120_000)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.doOutput = true
            c.setFixedLengthStreamingMode(length)
            c.outputStream.use { it.write(chunk, 0, length) }
            val status = c.responseCode
            val next = c.getHeaderField("Upload-Offset")
            val text = if (status >= 400) readBody(c) else ""
            c.disconnect()
            RawHttp.Response(status, mapOf("upload-offset" to (next ?: "")), text)
        }
        if (res.status == 409 || res.status == 400) return tusOffset(url)
        if (res.status < 200 || res.status >= 300) throw KineApiError("Cloudflare refused the chunk (${res.status}) ${res.body.take(120)}", res.status)
        return res.headers["upload-offset"]?.toLongOrNull() ?: (offset + length)
    }

    /** Nahrání jedním požadavkem (mode "basic", malé soubory). */
    fun basicUpload(url: String, size: Long, read: (OutputStream) -> Unit) {
        val boundary = "----kine" + System.currentTimeMillis()
        val c = open(url, "POST", 600_000)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.mp4\"\r\nContent-Type: video/mp4\r\n\r\n".toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        c.setFixedLengthStreamingMode(head.size + size + tail.size)
        c.outputStream.use { out ->
            out.write(head)
            read(out)
            out.write(tail)
        }
        val status = c.responseCode
        c.disconnect()
        if (status < 200 || status >= 300) throw KineApiError("Cloudflare refused the file ($status).", status)
    }

    fun confirm(clip: Clip, visibility: String): String {
        val body = JSONObject()
            .put("title", clip.title.trim().take(150).ifEmpty { "Clip" })
            .put("description", "Clip · Kine")
            .put("cloudflareVideoId", clip.videoId)
            .put("madeForKids", false)
            .put("hasPaidPromotion", false)
            .put("isAiGenerated", false)
            .put("language", Locale.getDefault().language.ifEmpty { "en" })
            // Klíč kategorie jako na webu a v appce do PC ("gaming" Kine neznala - klip nebyl v Hrách).
            .put("category", "catGaming")
            .put("visibility", visibility)
            .put("isPremiere", false)
            .put("scheduledAt", JSONObject.NULL)
            .put("width", clip.width)
            .put("height", clip.height)
            .put("chapters", JSONArray())
            .put("captions", JSONArray())
            .put("hashtags", JSONArray().put("klip"))
        val j = jsonCall("${settings.siteUrl}/api/videos/confirm", "POST", body, token(), timeoutMs = 60_000)
        val id = j.optJSONObject("video")?.optString("id") ?: ""
        if (id.isEmpty()) throw KineApiError("Kine did not save the video.", 500)
        return id
    }

    /**
     * Stav videa na Kine: "ready" / "processing" / "failed" / "not-found". [videoId] = id videa
     * NA KINE (z potvrzení). Do 0.4.1 se sem posílalo id od Cloudflare - Kine video nenašla,
     * nikdo ho nepřepnul na hotové a na Kine nebylo vidět (u Cloudflare ano).
     */
    fun status(videoId: String): String {
        val c = open("${settings.siteUrl}/api/videos/status", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        settings.accessToken?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        val bytes = JSONObject().put("videoId", videoId).toString().toByteArray()
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        val status = c.responseCode
        val text = readBody(c)
        c.disconnect()
        if (status == 404) return "not-found"
        if (status >= 400) throw KineApiError("Kine answered $status.", status)
        return when (try { JSONObject(text).optString("status") } catch (_: Exception) { "" }) {
            "ready" -> "ready"
            "failed" -> "failed"
            else -> "processing"
        }
    }

    fun cancelUpload(videoId: String) {
        try {
            jsonCall("${settings.siteUrl}/api/videos/cancel-upload", "POST", JSONObject().put("cloudflareVideoId", videoId), token())
        } catch (e: KineApiError) {
            if (e.status != 404 && e.status != 409) throw e
        }
    }

    companion object {
        fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}

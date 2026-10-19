package com.kine.clipper

import android.content.Context
import android.content.SharedPreferences

/**
 * Nastavení (SharedPreferences). Hodnoty mají stejný smysl jako v appce do
 * PC: délka klipu zpětně, kvalita, kamera (žádná / přední / zadní, velikost,
 * roh, tvar), zvuk hry a mikrofon, bublina přes hru, kam se klipy nahrávají.
 */
class Settings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("kine", Context.MODE_PRIVATE)
    /** Testovací sestavení smí mluvit s jakoukoli Kine (vývoj); ostré jen s oficiální - jako appka do PC. */
    private val debuggable: Boolean = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Kolik sekund zpětně klip bere (zásobník drží o něco víc). */
    var clipSeconds: Int
        get() = p.getInt("clipSeconds", 30).coerceIn(10, 120)
        set(v) = p.edit().putInt("clipSeconds", v.coerceIn(10, 120)).apply()

    /** Výška obrazu: 720 nebo 1080 (podle obrazovky se dopočítá šířka). */
    var maxHeight: Int
        get() = p.getInt("maxHeight", 1080)
        set(v) = p.edit().putInt("maxHeight", v).apply()

    var fps: Int
        get() = p.getInt("fps", 30).coerceIn(15, 60)
        set(v) = p.edit().putInt("fps", v.coerceIn(15, 60)).apply()

    /** Datový tok obrazu v Mb/s (výchozí podle výšky). */
    val videoMbps: Int get() = if (maxHeight >= 1080) 10 else 6

    /** Kamera v klipu: "off" / "front" / "back". */
    var camera: String
        get() = p.getString("camera", "off") ?: "off"
        set(v) = p.edit().putString("camera", v).apply()

    /** Šířka kamery jako podíl šířky obrazu (0,12-0,5). */
    var cameraScale: Float
        get() = p.getFloat("cameraScale", 0.28f).coerceIn(0.12f, 0.5f)
        set(v) = p.edit().putFloat("cameraScale", v.coerceIn(0.12f, 0.5f)).apply()

    /** Roh: "top-left" / "top-right" / "bottom-left" / "bottom-right". */
    var cameraCorner: String
        get() = p.getString("cameraCorner", "bottom-right") ?: "bottom-right"
        set(v) = p.edit().putString("cameraCorner", v).apply()

    /** Tvar: "rect" / "circle". */
    var cameraShape: String
        get() = p.getString("cameraShape", "circle") ?: "circle"
        set(v) = p.edit().putString("cameraShape", v).apply()

    /** Zrcadlit (přední kamera jako selfie). */
    var cameraMirror: Boolean
        get() = p.getBoolean("cameraMirror", true)
        set(v) = p.edit().putBoolean("cameraMirror", v).apply()

    /** Otočení obrazu kamery: -1 = samo (podle čipu a otočení telefonu), jinak 0 / 90 / 180 / 270. */
    var cameraRotation: Int
        get() = p.getInt("cameraRotation", -1)
        set(v) = p.edit().putInt("cameraRotation", v).apply()

    var gameAudio: Boolean
        get() = p.getBoolean("gameAudio", true)
        set(v) = p.edit().putBoolean("gameAudio", v).apply()

    var microphone: Boolean
        get() = p.getBoolean("microphone", true)
        set(v) = p.edit().putBoolean("microphone", v).apply()

    /** Plovoucí tlačítko Klip přes hru (potřebuje "zobrazit přes jiné aplikace"). */
    var bubble: Boolean
        get() = p.getBoolean("bubble", true)
        set(v) = p.edit().putBoolean("bubble", v).apply()

    /** Klipy nahrát na Kine samy, až snímání skončí (ne během hry). */
    var autoUpload: Boolean
        get() = p.getBoolean("autoUpload", false)
        set(v) = p.edit().putBoolean("autoUpload", v).apply()

    /** Viditelnost nahraných klipů na Kine: public / subscribers / private. */
    var visibility: String
        get() = p.getString("visibility", "private") ?: "private"
        set(v) = p.edit().putString("visibility", v).apply()

    var siteUrl: String
        get() {
            val raw = (p.getString("siteUrl", DEFAULT_SITE) ?: DEFAULT_SITE).trimEnd('/')
            return if (debuggable || isOfficialSite(raw)) raw else DEFAULT_SITE
        }
        set(v) = p.edit().putString("siteUrl", v.trim().trimEnd('/')).apply()

    // ---- přihlášení (vlastní relace Supabase, jako appka do PC) ----
    var accessToken: String?
        get() = p.getString("accessToken", null)
        set(v) = p.edit().putString("accessToken", v).apply()
    var refreshToken: String?
        get() = p.getString("refreshToken", null)
        set(v) = p.edit().putString("refreshToken", v).apply()
    var tokenExpiresAt: Long
        get() = p.getLong("tokenExpiresAt", 0L)
        set(v) = p.edit().putLong("tokenExpiresAt", v).apply()
    var username: String?
        get() = p.getString("username", null)
        set(v) = p.edit().putString("username", v).apply()
    /** Náhodný řetězec pro probíhající přihlášení (state v kine://link). */
    var linkState: String?
        get() = p.getString("linkState", null)
        set(v) = p.edit().putString("linkState", v).apply()

    val loggedIn: Boolean get() = !refreshToken.isNullOrEmpty()

    fun clearSession() {
        p.edit().remove("accessToken").remove("refreshToken").remove("tokenExpiresAt").remove("username").apply()
    }

    companion object {
        const val DEFAULT_SITE = "https://www.playkine.com"
        private val OFFICIAL_HOSTS = setOf("www.playkine.com", "playkine.com", "kine-lac.vercel.app")

        /** Jen oficiální Kine (https) - účet a nahrávání nikam jinam. */
        fun isOfficialSite(url: String): Boolean = try {
            val u = java.net.URL(url)
            u.protocol == "https" && u.host.lowercase() in OFFICIAL_HOSTS
        } catch (_: Exception) {
            false
        }
    }
}

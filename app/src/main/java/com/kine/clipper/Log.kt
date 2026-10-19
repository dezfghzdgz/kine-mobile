package com.kine.clipper

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deník appky: do logcatu i do souboru (files/kine.log, nejvýš ~512 kB, pak se
 * zkrátí) - ať se dá po problému poslat, co se dělo.
 */
object Log {
    private const val TAG = "KineClipper"
    private var file: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(context: Context) {
        file = File(context.filesDir, "kine.log")
    }

    fun i(message: String) = write("I", message)
    fun w(message: String) = write("W", message)
    fun e(message: String, t: Throwable? = null) = write("E", if (t != null) "$message: ${t::class.java.simpleName}: ${t.message}" else message)

    private fun write(level: String, message: String) {
        when (level) {
            "E" -> android.util.Log.e(TAG, message)
            "W" -> android.util.Log.w(TAG, message)
            else -> android.util.Log.i(TAG, message)
        }
        val f = file ?: return
        synchronized(lock) {
            try {
                if (f.exists() && f.length() > 512 * 1024) {
                    val tail = f.readText().takeLast(256 * 1024)
                    f.writeText(tail)
                }
                f.appendText("${fmt.format(Date())} $level $message\n")
            } catch (_: Exception) {
                // deník není důležitý
            }
        }
    }

    fun text(): String = try { file?.readText() ?: "" } catch (_: Exception) { "" }
}

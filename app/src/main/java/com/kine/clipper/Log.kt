package com.kine.clipper

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * Deník appky: do logcatu hned, do souboru (files/kine.log, nejvýš ~512 kB, pak se
 * zkrátí) na vlastním vlákně - zápis na disk nikdy nebrzdí obraz ani rozhraní (na
 * starších telefonech s pomalou pamětí to bylo znát). Pád appky se zapíše hned
 * (ať je po „Poslat deník“ vidět, co se stalo).
 */
object Log {
    private const val TAG = "KineClipper"
    @Volatile private var file: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val queue = LinkedBlockingQueue<String>(2000)
    private val lock = Any()

    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "kine.log")
        Thread({ loop() }, "kine-log").apply {
            isDaemon = true
            start()
        }
    }

    fun i(message: String) = write("I", message)
    fun w(message: String) = write("W", message)
    fun e(message: String, t: Throwable? = null) = write("E", if (t != null) "$message: ${describe(t)}" else message)

    /** Pád (nezachycená výjimka) - zapsat hned, appka za chvíli skončí. */
    fun crash(thread: String, t: Throwable) {
        val line = line("E", "PÁD ve vlákně $thread: ${describe(t)}")
        android.util.Log.e(TAG, line, t)
        synchronized(lock) {
            val pending = ArrayList<String>()
            queue.drainTo(pending)
            append(pending.joinToString("") + line)
        }
    }

    private fun describe(t: Throwable): String {
        val where = t.stackTrace.take(5).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        val cause = t.cause?.let { " | příčina: ${it::class.java.simpleName}: ${it.message}" } ?: ""
        return "${t::class.java.simpleName}: ${t.message} @ $where$cause"
    }

    private fun line(level: String, message: String): String = synchronized(fmt) { "${fmt.format(Date())} $level $message\n" }

    private fun write(level: String, message: String) {
        when (level) {
            "E" -> android.util.Log.e(TAG, message)
            "W" -> android.util.Log.w(TAG, message)
            else -> android.util.Log.i(TAG, message)
        }
        if (file == null) return
        // Plná fronta (zaseknutý disk) - radši zahodit než brzdit.
        queue.offer(line(level, message))
    }

    private fun loop() {
        while (true) {
            try {
                val first = queue.take()
                val sb = StringBuilder(first)
                while (true) {
                    val next = queue.poll() ?: break
                    sb.append(next)
                }
                synchronized(lock) { append(sb.toString()) }
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                // deník není důležitý
            }
        }
    }

    private fun append(text: String) {
        val f = file ?: return
        try {
            if (f.exists() && f.length() > 512 * 1024) {
                val tail = f.readText().takeLast(256 * 1024)
                f.writeText(tail)
            }
            FileOutputStream(f, true).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (_: Exception) {
            // deník není důležitý
        }
    }

    /** Celý deník (i řádky, které ještě čekají na zápis). */
    fun text(): String = try {
        synchronized(lock) {
            val pending = ArrayList<String>()
            queue.drainTo(pending)
            if (pending.isNotEmpty()) append(pending.joinToString(""))
            file?.readText() ?: ""
        }
    } catch (_: Exception) {
        ""
    }
}

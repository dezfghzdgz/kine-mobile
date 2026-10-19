package com.kine.clipper

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Malý HTTPS klient pro metody, které HttpURLConnection neumí (PATCH u tus):
 * jedno spojení, hlavičky, tělo, odpověď (stav, hlavičky, krátké tělo).
 * Jméno serveru se ověřuje stejně jako u HttpsURLConnection.
 */
object RawHttp {
    data class Response(val status: Int, val headers: Map<String, String>, val body: String)

    fun request(url: String, method: String, headers: Map<String, String>, body: ByteArray, length: Int): Response {
        val u = URL(url)
        if (u.protocol != "https") throw IllegalArgumentException("https only")
        val host = u.host
        val port = if (u.port > 0) u.port else 443
        val path = (if (u.path.isNullOrEmpty()) "/" else u.path) + (if (u.query != null) "?" + u.query else "")
        val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(host, port) as SSLSocket
        socket.soTimeout = 120_000
        try {
            socket.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)) throw SecurityException("certificate does not match $host")
            val head = StringBuilder()
            head.append("$method $path HTTP/1.1\r\n")
            head.append("Host: $host\r\n")
            for ((k, v) in headers) head.append("$k: $v\r\n")
            head.append("Content-Length: $length\r\n")
            head.append("Connection: close\r\n\r\n")
            val out = socket.outputStream
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(body, 0, length)
            out.flush()
            val input = BufferedInputStream(socket.inputStream)
            val statusLine = readLine(input)
            val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: throw IllegalStateException("bad status line: $statusLine")
            val respHeaders = HashMap<String, String>()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) respHeaders[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            // Tělo jen krátce (chyba), zbytek se zahodí.
            val bytes = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (bytes.size() < 8192) {
                val n = input.read(buf)
                if (n <= 0) break
                bytes.write(buf, 0, n)
            }
            return Response(status, respHeaders, bytes.toString(Charsets.UTF_8.name()))
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
                // nevadí
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String {
        val sb = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.write(b)
            if (sb.size() > 16384) throw IllegalStateException("header too long")
        }
        return sb.toString(Charsets.ISO_8859_1.name())
    }
}

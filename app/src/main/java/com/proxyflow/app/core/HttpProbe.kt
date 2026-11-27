package com.proxyflow.app.core

import java.io.ByteArrayOutputStream
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class HttpResponse(val code: Int, val body: String)

/** Minimal HTTP/1.1 client that runs over an already established tunnel socket. */
object HttpProbe {
    fun get(tunnel: Socket, host: String, path: String, tls: Boolean, port: Int = if (tls) 443 else 80): HttpResponse {
        val sock: Socket = if (tls) {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(tunnel, host, port, true) as SSLSocket
            ssl.soTimeout = tunnel.soTimeout
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                ssl.close()
                throw ProxyException("TLS certificate does not match $host")
            }
            ssl
        } else tunnel
        sock.use { s ->
            val hostHeader = if ((tls && port == 443) || (!tls && port == 80)) host else "$host:$port"
            val req = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUser-Agent: ProxyFlow/1.0\r\n" +
                "Accept: */*\r\nConnection: close\r\n\r\n"
            s.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
            s.getOutputStream().flush()
            val inp = s.getInputStream()
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (bos.size() < 256 * 1024) {
                val n = inp.read(buf)
                if (n < 0) break
                bos.write(buf, 0, n)
                // generate_204 style probes: stop as soon as the status line + headers arrived
                val txt = bos.toString("ISO-8859-1")
                val headEnd = txt.indexOf("\r\n\r\n")
                if (headEnd > 0) {
                    val code = txt.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                    val len = Regex("(?i)content-length:\\s*(\\d+)").find(txt.substring(0, headEnd))?.groupValues?.get(1)?.toIntOrNull()
                    if (code == 204 || code == 304) break
                    if (len != null && bos.size() >= headEnd + 4 + len) break
                }
            }
            val raw = bos.toString("UTF-8")
            val status = raw.substringBefore("\r\n")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw ProxyException("No valid HTTP response through the proxy")
            var body = raw.substringAfter("\r\n\r\n", "")
            if (raw.substringBefore("\r\n\r\n").contains("transfer-encoding: chunked", ignoreCase = true)) {
                body = dechunk(body)
            }
            return HttpResponse(code, body)
        }
    }

    private fun dechunk(s: String): String {
        val out = StringBuilder()
        var rest = s
        while (true) {
            val line = rest.substringBefore("\r\n")
            val size = line.trim().substringBefore(';').toIntOrNull(16) ?: break
            if (size == 0) break
            rest = rest.substringAfter("\r\n")
            if (rest.length < size) { out.append(rest); break }
            out.append(rest, 0, size)
            rest = rest.substring(size).removePrefix("\r\n")
        }
        return out.toString()
    }
}

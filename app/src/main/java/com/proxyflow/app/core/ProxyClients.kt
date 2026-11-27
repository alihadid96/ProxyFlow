package com.proxyflow.app.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/** A failure reported by an upstream proxy, with a message suitable for the user. */
class ProxyException(message: String, val kind: Kind = Kind.PROTOCOL) : IOException(message) {
    enum class Kind { AUTH, PROTOCOL, TARGET, TIMEOUT, UNREACHABLE }
}

object AddressUtil {
    private val ipv4 = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")
    private val hostname = Regex("""^(?=.{1,253}$)([A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?)(\.[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?)*\.?$""")

    fun isIpv4(s: String) = ipv4.matches(s)
    fun isIpv6(s: String) = s.contains(':') && runCatching { InetAddress.getByName(s) is java.net.Inet6Address }.getOrDefault(false)
    fun isIp(s: String) = isIpv4(s) || isIpv6(s)
    fun isValidHost(s: String) = s.isNotBlank() && (isIp(s) || hostname.matches(s))
    fun hostPort(host: String, port: Int) = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

internal fun openTcp(host: String, port: Int, timeoutMs: Int): Socket {
    val s = Socket()
    try {
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        return s
    } catch (e: java.net.SocketTimeoutException) {
        s.close(); throw ProxyException("Timed out connecting to proxy $host:$port", ProxyException.Kind.TIMEOUT)
    } catch (e: java.net.UnknownHostException) {
        s.close(); throw ProxyException("Cannot resolve proxy host \"$host\"", ProxyException.Kind.UNREACHABLE)
    } catch (e: IOException) {
        s.close(); throw ProxyException("Cannot reach proxy $host:$port (${e.message})", ProxyException.Kind.UNREACHABLE)
    }
}

private fun InputStream.readFully(n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = read(buf, off, n - off)
        if (r < 0) throw ProxyException("Proxy closed the connection unexpectedly")
        off += r
    }
    return buf
}

/** RFC 1928 / RFC 1929 SOCKS5 client (CONNECT command). */
object Socks5Client {
    fun connect(
        proxyHost: String, proxyPort: Int, username: String?, password: String?,
        destHost: String, destPort: Int, timeoutMs: Int,
    ): Socket {
        val s = openTcp(proxyHost, proxyPort, timeoutMs)
        try {
            handshake(s, username, password)
            request(s, destHost, destPort)
            return s
        } catch (e: java.net.SocketTimeoutException) {
            s.close(); throw ProxyException("SOCKS5 proxy did not answer in time", ProxyException.Kind.TIMEOUT)
        } catch (e: Exception) {
            s.close(); throw e
        }
    }

    private fun handshake(s: Socket, user: String?, pass: String?) {
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        val withAuth = !user.isNullOrEmpty()
        out.write(if (withAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        out.flush()
        val reply = inp.readFully(2)
        if (reply[0].toInt() != 5) throw ProxyException("Server is not a SOCKS5 proxy")
        when (reply[1].toInt() and 0xFF) {
            0x00 -> Unit
            0x02 -> {
                if (!withAuth) throw ProxyException("SOCKS5 proxy requires a username and password", ProxyException.Kind.AUTH)
                val u = user!!.toByteArray(Charsets.UTF_8)
                val p = (pass ?: "").toByteArray(Charsets.UTF_8)
                if (u.size > 255 || p.size > 255) throw ProxyException("Username or password is too long for SOCKS5", ProxyException.Kind.AUTH)
                val msg = ByteArrayOutputStream().apply {
                    write(1); write(u.size); write(u); write(p.size); write(p)
                }.toByteArray()
                out.write(msg); out.flush()
                val st = inp.readFully(2)
                if (st[1].toInt() != 0) throw ProxyException("SOCKS5 authentication failed: wrong username or password", ProxyException.Kind.AUTH)
            }
            0xFF -> throw ProxyException(
                if (withAuth) "SOCKS5 proxy rejected all authentication methods" else "SOCKS5 proxy requires authentication",
                ProxyException.Kind.AUTH
            )
            else -> throw ProxyException("SOCKS5 proxy selected an unsupported auth method")
        }
    }

    private fun request(s: Socket, host: String, port: Int) {
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        val req = ByteArrayOutputStream()
        req.write(byteArrayOf(5, 1, 0))
        when {
            AddressUtil.isIpv4(host) -> { req.write(1); req.write(InetAddress.getByName(host).address) }
            AddressUtil.isIpv6(host) -> { req.write(4); req.write(InetAddress.getByName(host).address) }
            else -> {
                val b = host.toByteArray(Charsets.UTF_8)
                req.write(3); req.write(b.size); req.write(b)
            }
        }
        req.write(port shr 8 and 0xFF); req.write(port and 0xFF)
        out.write(req.toByteArray()); out.flush()
        val head = inp.readFully(4)
        val rep = head[1].toInt() and 0xFF
        if (rep != 0) throw ProxyException("SOCKS5 CONNECT failed: " + replyText(rep), ProxyException.Kind.TARGET)
        when (head[3].toInt()) {
            1 -> inp.readFully(4 + 2)
            4 -> inp.readFully(16 + 2)
            3 -> { val len = inp.readFully(1)[0].toInt() and 0xFF; inp.readFully(len + 2) }
            else -> throw ProxyException("SOCKS5 reply has an invalid address type")
        }
    }

    private fun replyText(code: Int) = when (code) {
        1 -> "general server failure"
        2 -> "connection not allowed by ruleset"
        3 -> "network unreachable"
        4 -> "host unreachable"
        5 -> "connection refused"
        6 -> "TTL expired"
        7 -> "command not supported"
        8 -> "address type not supported"
        else -> "error code $code"
    }
}

/** HTTP CONNECT tunnelling client (RFC 7231 §4.3.6) with Basic proxy authentication. */
object HttpConnectClient {
    fun connect(
        proxyHost: String, proxyPort: Int, username: String?, password: String?,
        destHost: String, destPort: Int, timeoutMs: Int,
    ): Socket {
        val s = openTcp(proxyHost, proxyPort, timeoutMs)
        try {
            val target = AddressUtil.hostPort(destHost, destPort)
            val sb = StringBuilder()
            sb.append("CONNECT ").append(target).append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(target).append("\r\n")
            if (!username.isNullOrEmpty()) {
                val token = Base64.getEncoder().encodeToString("$username:${password ?: ""}".toByteArray(Charsets.UTF_8))
                sb.append("Proxy-Authorization: Basic ").append(token).append("\r\n")
            }
            sb.append("Proxy-Connection: Keep-Alive\r\nUser-Agent: ProxyFlow/1.0\r\n\r\n")
            s.getOutputStream().write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            s.getOutputStream().flush()
            val head = readHead(s.getInputStream())
            val statusLine = head.lineSequence().firstOrNull().orEmpty()
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw ProxyException("Server is not an HTTP proxy (bad response)")
            when {
                code in 200..299 -> return s
                code == 407 -> throw ProxyException(
                    if (username.isNullOrEmpty()) "HTTP proxy requires authentication (407)"
                    else "HTTP proxy authentication failed: wrong username or password (407)",
                    ProxyException.Kind.AUTH
                )
                code == 403 -> throw ProxyException("HTTP proxy refused the tunnel (403 Forbidden)", ProxyException.Kind.TARGET)
                code == 502 || code == 503 || code == 504 ->
                    throw ProxyException("HTTP proxy could not reach the target ($code)", ProxyException.Kind.TARGET)
                code == 405 || code == 501 -> throw ProxyException("HTTP proxy does not allow CONNECT tunnelling ($code)")
                else -> throw ProxyException("HTTP proxy returned: $statusLine")
            }
        } catch (e: java.net.SocketTimeoutException) {
            s.close(); throw ProxyException("HTTP proxy did not answer in time", ProxyException.Kind.TIMEOUT)
        } catch (e: Exception) {
            s.close(); throw e
        }
    }

    /** Reads response head byte-by-byte so no tunnel payload is consumed. */
    private fun readHead(inp: InputStream): String {
        val bos = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = inp.read()
            if (b < 0) throw ProxyException("HTTP proxy closed the connection")
            bos.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (bos.size() > 16 * 1024) throw ProxyException("HTTP proxy response header too large")
        }
        return bos.toString("ISO-8859-1")
    }
}

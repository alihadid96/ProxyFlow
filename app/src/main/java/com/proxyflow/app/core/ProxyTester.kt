package com.proxyflow.app.core

import android.content.Context
import com.proxyflow.app.data.AppSettings
import com.proxyflow.app.data.DnsMode
import com.proxyflow.app.data.Protocol
import com.proxyflow.app.data.ProxyProfile
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class TestResult(val latencyMs: Int, val error: String?) {
    val ok get() = error == null
}

data class ExitInfo(
    val ip: String = "",
    val city: String = "",
    val region: String = "",
    val country: String = "",
    val org: String = "",
) {
    val location: String get() = listOf(city, region, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
}

/** A function that opens a TCP tunnel to host:port through some proxy. */
fun interface Tunnel {
    fun open(host: String, port: Int, timeoutMs: Int): Socket
}

object ProxyTester {
    const val PROBE_HOST = "www.gstatic.com"
    const val PROBE_PATH = "/generate_204"

    /** Tunnel through the profile's upstream proxy directly (HTTP / SOCKS5 only). */
    fun directTunnel(p: ProxyProfile): Tunnel = Tunnel { host, port, timeout ->
        val target = if (p.dnsMode == DnsMode.LOCAL && !AddressUtil.isIp(host))
            InetAddress.getByName(host).hostAddress ?: host else host
        val user = if (p.hasAuth) p.username else null
        val pass = if (p.hasAuth) p.password else null
        when (p.protocol) {
            Protocol.HTTP -> HttpConnectClient.connect(p.host.trim(), p.port, user, pass, target, port, timeout)
            Protocol.SOCKS5 -> Socks5Client.connect(p.host.trim(), p.port, user, pass, target, port, timeout)
            Protocol.VLESS -> throw IllegalStateException("VLESS requires Xray-core")
        }
    }

    /** Tunnel through a local Xray SOCKS inbound. */
    fun localTunnel(inbound: LocalInbound): Tunnel = Tunnel { host, port, timeout ->
        Socks5Client.connect("127.0.0.1", inbound.port, inbound.user, inbound.pass, host, port, timeout)
    }

    /** Real "URL test": opens a tunnel and fetches http://www.gstatic.com/generate_204 (expects HTTP 204). */
    fun urlTest(tunnel: Tunnel, timeoutMs: Int): Int {
        val start = System.nanoTime()
        val sock = tunnel.open(PROBE_HOST, 80, timeoutMs)
        val resp = HttpProbe.get(sock, PROBE_HOST, PROBE_PATH, tls = false)
        if (resp.code != 204 && resp.code != 200) throw ProxyException("Unexpected HTTP ${resp.code} from test URL")
        return ((System.nanoTime() - start) / 1_000_000).toInt()
    }

    /** Tests a saved profile without touching the VPN. VLESS uses a temporary Xray-core instance. */
    fun test(context: Context, p: ProxyProfile, settings: AppSettings): TestResult {
        val timeoutMs = (if (p.timeoutSec > 0) p.timeoutSec else settings.connectTimeoutSec) * 1000
        return try {
            when (p.protocol) {
                Protocol.HTTP, Protocol.SOCKS5 -> TestResult(urlTest(directTunnel(p), timeoutMs), null)
                Protocol.VLESS -> {
                    val inbound = XrayProcess.newInbound()
                    val xray = XrayProcess(context, p.secretValues() + inbound.pass) {}
                    try {
                        xray.start(XrayConfigBuilder.build(p, settings, inbound, forTest = true))
                        xray.awaitReady(inbound.port)
                        TestResult(urlTest(localTunnel(inbound), timeoutMs), null)
                    } catch (e: Exception) {
                        val extra = if (!xray.isAlive) "" else xray.recentOutput().lineSequence()
                            .lastOrNull { it.contains("failed", true) }?.let { " — $it" }.orEmpty()
                        TestResult(-1, friendly(e) + extra.take(200))
                    } finally {
                        xray.stop()
                    }
                }
            }
        } catch (e: Exception) {
            TestResult(-1, friendly(e))
        }
    }

    /** Direct TCP handshake time to the proxy server itself (no proxying). */
    fun tcpPing(host: String, port: Int, timeoutMs: Int = 3000): Int = try {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        ((System.nanoTime() - start) / 1_000_000).toInt()
    } catch (_: Exception) { -1 }

    /** Looks up the public exit IP and its location through the tunnel (ipinfo.io over HTTPS). */
    fun exitInfo(tunnel: Tunnel, timeoutMs: Int): ExitInfo {
        val sock = tunnel.open("ipinfo.io", 443, timeoutMs)
        val resp = HttpProbe.get(sock, "ipinfo.io", "/json", tls = true)
        if (resp.code != 200) throw ProxyException("IP lookup returned HTTP ${resp.code}")
        val j = JSONObject(resp.body.substring(resp.body.indexOf('{')))
        return ExitInfo(
            ip = j.optString("ip"), city = j.optString("city"), region = j.optString("region"),
            country = j.optString("country"), org = j.optString("org"),
        )
    }

    fun friendly(e: Throwable): String = when (e) {
        is ProxyException -> e.message ?: "Proxy error"
        is java.net.SocketTimeoutException -> "Timed out"
        is java.net.UnknownHostException -> "Cannot resolve host ${e.message ?: ""}".trim()
        is java.net.ConnectException -> "Connection refused"
        is javax.net.ssl.SSLException -> "TLS error: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }
}

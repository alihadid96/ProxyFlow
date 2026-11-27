package com.proxyflow.app.core

import com.proxyflow.app.data.AppSettings
import com.proxyflow.app.data.DnsMode
import com.proxyflow.app.data.Ipv6Mode
import com.proxyflow.app.data.Protocol
import com.proxyflow.app.data.ProxyProfile
import com.proxyflow.app.data.Security
import com.proxyflow.app.data.Transport
import org.json.JSONArray
import org.json.JSONObject

data class LocalInbound(val port: Int, val user: String, val pass: String)

/**
 * Builds an Xray-core JSON configuration:
 *   [hev-socks5-tunnel] --SOCKS5(auth, UDP)--> Xray inbound "socks-in"
 *     -> routing (DNS hijack, LAN bypass, IPv6 / UDP policy)
 *     -> outbound "proxy" (HTTP CONNECT / SOCKS5 / VLESS)
 */
object XrayConfigBuilder {

    val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "127.0.0.0/8",
        "100.64.0.0/10", "224.0.0.0/4", "255.255.255.255/32", "fc00::/7", "fe80::/10", "::1/128",
    )

    fun build(p: ProxyProfile, s: AppSettings, inbound: LocalInbound, forTest: Boolean = false): String {
        val remoteDns = p.dnsMode == DnsMode.REMOTE
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning").put("access", "none").put("dnsLog", false))

        // ---- DNS ----
        val dns = JSONObject()
        dns.put("tag", "dns-module")
        dns.put("queryStrategy", if (s.ipv6Mode == Ipv6Mode.PROXY) "UseIP" else "UseIPv4")
        dns.put("servers", JSONArray().put(if (remoteDns) s.remoteDnsServer() else s.dnsIp()))
        root.put("dns", dns)

        // ---- Inbound ----
        val sniffing = JSONObject().put("enabled", remoteDns && !forTest)
            .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
            .put("routeOnly", false)
        root.put(
            "inbounds", JSONArray().put(
                JSONObject()
                    .put("tag", "socks-in")
                    .put("listen", "127.0.0.1")
                    .put("port", inbound.port)
                    .put("protocol", "socks")
                    .put(
                        "settings", JSONObject()
                            .put("auth", "password")
                            .put("accounts", JSONArray().put(JSONObject().put("user", inbound.user).put("pass", inbound.pass)))
                            .put("udp", true)
                            .put("ip", "127.0.0.1")
                    )
                    .put("sniffing", sniffing)
            )
        )

        // ---- Outbounds (first one is the default) ----
        val outbounds = JSONArray()
        outbounds.put(proxyOutbound(p))
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom")
            .put("streamSettings", JSONObject().put("sockopt",
                JSONObject().put("domainStrategy", if (s.ipv6Mode == Ipv6Mode.PROXY) "UseIP" else "UseIPv4"))))
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        root.put("outbounds", outbounds)

        // ---- Routing ----
        val rules = JSONArray()
        rules.put(JSONObject().put("inboundTag", JSONArray().put("dns-module")).put("outboundTag", if (remoteDns) "proxy" else "direct"))
        if (!forTest) {
            rules.put(JSONObject().put("inboundTag", JSONArray().put("socks-in")).put("port", "53").put("outboundTag", "dns-out"))
            if (s.bypassLan) rules.put(JSONObject().put("ip", JSONArray(PRIVATE_CIDRS)).put("outboundTag", "direct"))
            if (s.ipv6Mode == Ipv6Mode.BLOCK) rules.put(JSONObject().put("ip", JSONArray().put("::/0")).put("outboundTag", "block"))
        }
        if (p.protocol == Protocol.HTTP) {
            // HTTP proxies can only carry TCP (CONNECT). Reject UDP fast so apps fall back to TCP (e.g. QUIC -> TLS).
            rules.put(JSONObject().put("network", "udp").put("outboundTag", "block"))
        }
        root.put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
        root.put("policy", JSONObject().put("levels", JSONObject().put("0",
            JSONObject().put("handshake", (if (p.timeoutSec > 0) p.timeoutSec else s.connectTimeoutSec).coerceIn(3, 120))
                .put("connIdle", 300).put("uplinkOnly", 2).put("downlinkOnly", 5))))
        return root.toString()
    }

    private fun proxyOutbound(p: ProxyProfile): JSONObject {
        val o = JSONObject().put("tag", "proxy")
        when (p.protocol) {
            Protocol.HTTP -> {
                val st = JSONObject().put("address", p.host.trim()).put("port", p.port)
                if (p.hasAuth) st.put("user", p.username).put("pass", p.password)
                o.put("protocol", "http").put("settings", st)
            }
            Protocol.SOCKS5 -> {
                val st = JSONObject().put("address", p.host.trim()).put("port", p.port)
                if (p.hasAuth) st.put("user", p.username).put("pass", p.password)
                o.put("protocol", "socks").put("settings", st)
            }
            Protocol.OPENVPN -> throw IllegalArgumentException("OpenVPN profiles do not use Xray-core")
            Protocol.VLESS -> {
                val st = JSONObject()
                    .put("address", p.host.trim())
                    .put("port", p.port)
                    .put("id", p.uuid.trim())
                    .put("encryption", p.encryption.ifEmpty { "none" })
                if (p.flow.isNotEmpty()) st.put("flow", p.flow)
                o.put("protocol", "vless").put("settings", st).put("streamSettings", streamSettings(p))
            }
        }
        return o
    }

    private fun splitList(s: String) = JSONArray(s.split(',').map { it.trim() }.filter { it.isNotEmpty() })

    private fun streamSettings(p: ProxyProfile): JSONObject {
        val ss = JSONObject().put("network", if (p.transport == Transport.TCP) "raw" else p.transport)
        ss.put("security", p.security)
        when (p.security) {
            Security.TLS -> {
                val tls = JSONObject().put("serverName", p.sni.ifEmpty { p.hostHeader.ifEmpty { p.host } })
                if (p.fingerprint.isNotEmpty()) tls.put("fingerprint", p.fingerprint)
                if (p.alpn.isNotEmpty()) tls.put("alpn", splitList(p.alpn))
                // Xray-core removed "allowInsecure"; certificate pinning is the supported replacement.
                if (p.pinnedCertSha256.isNotEmpty()) tls.put("pinnedPeerCertSha256", p.pinnedCertSha256)
                ss.put("tlsSettings", tls)
            }
            Security.REALITY -> {
                val r = JSONObject()
                    .put("serverName", p.sni)
                    .put("fingerprint", p.fingerprint.ifEmpty { "chrome" })
                    .put("publicKey", p.realityPublicKey.trim())
                    .put("shortId", p.realityShortId.trim())
                if (p.spiderX.isNotEmpty()) r.put("spiderX", p.spiderX)
                ss.put("realitySettings", r)
            }
        }
        when (p.transport) {
            Transport.WS -> ss.put("wsSettings", JSONObject().put("path", p.path.ifEmpty { "/" }).apply {
                if (p.hostHeader.isNotEmpty()) put("host", p.hostHeader)
            })
            Transport.HTTPUPGRADE -> ss.put("httpupgradeSettings", JSONObject().put("path", p.path.ifEmpty { "/" }).apply {
                if (p.hostHeader.isNotEmpty()) put("host", p.hostHeader)
            })
            Transport.XHTTP -> ss.put("xhttpSettings", JSONObject().put("path", p.path.ifEmpty { "/" }).put("mode", p.xhttpMode).apply {
                if (p.hostHeader.isNotEmpty()) put("host", p.hostHeader)
            })
            Transport.GRPC -> ss.put("grpcSettings", JSONObject().put("serviceName", p.serviceName).put("multiMode", p.grpcMultiMode).apply {
                if (p.hostHeader.isNotEmpty()) put("authority", p.hostHeader)
            })
        }
        return ss
    }
}

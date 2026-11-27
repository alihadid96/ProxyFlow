package com.proxyflow.app.data

import org.json.JSONObject

enum class Protocol(val label: String) {
    HTTP("HTTP"), SOCKS5("SOCKS5"), VLESS("VLESS");

    companion object {
        fun from(value: String?): Protocol = entries.firstOrNull { it.name.equals(value, true) } ?: HTTP
    }
}

enum class DnsMode { REMOTE, LOCAL }

object Transport {
    const val TCP = "tcp"
    const val WS = "ws"
    const val GRPC = "grpc"
    const val XHTTP = "xhttp"
    const val HTTPUPGRADE = "httpupgrade"
    val all = listOf(TCP, WS, GRPC, XHTTP, HTTPUPGRADE)
    fun label(t: String) = when (t) {
        TCP -> "TCP"; WS -> "WebSocket"; GRPC -> "gRPC"; XHTTP -> "XHTTP (HTTP/2 · H3)"; HTTPUPGRADE -> "HTTPUpgrade"; else -> t
    }
}

object Security {
    const val NONE = "none"
    const val TLS = "tls"
    const val REALITY = "reality"
    val all = listOf(NONE, TLS, REALITY)
}

val FLOWS = listOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")
val FINGERPRINTS = listOf("chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized")
val XHTTP_MODES = listOf("auto", "packet-up", "stream-up", "stream-one")

/**
 * Decrypted, in-memory representation of a proxy profile.
 * Secrets (username, password, uuid) are only ever persisted encrypted (see [ProfileRepository]).
 */
data class ProxyProfile(
    val id: Long = 0,
    val name: String = "",
    val protocol: Protocol = Protocol.HTTP,
    val host: String = "",
    val port: Int = 0,
    // HTTP / SOCKS5
    val authEnabled: Boolean = false,
    val username: String = "",
    val password: String = "",
    val dnsMode: DnsMode = DnsMode.REMOTE,
    val timeoutSec: Int = 0, // 0 = use global setting
    // VLESS
    val uuid: String = "",
    val encryption: String = "none",
    val flow: String = "",
    val transport: String = Transport.TCP,
    val security: String = Security.NONE,
    val sni: String = "",
    val fingerprint: String = "chrome",
    val alpn: String = "",
    val allowInsecure: Boolean = false,
    val pinnedCertSha256: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val spiderX: String = "",
    val path: String = "",
    val hostHeader: String = "",
    val serviceName: String = "",
    val grpcMultiMode: Boolean = false,
    val xhttpMode: String = "auto",
    // test results (not part of the configuration)
    val lastLatencyMs: Int = -1,
    val lastTestError: String? = null,
    val lastTestAt: Long = 0,
) {
    val hasAuth get() = protocol != Protocol.VLESS && authEnabled && username.isNotEmpty()

    /** Non-secret fields as JSON (stored in plaintext). */
    fun publicJson(): String = JSONObject().apply {
        put("authEnabled", authEnabled)
        put("dnsMode", dnsMode.name)
        put("timeoutSec", timeoutSec)
        put("encryption", encryption)
        put("flow", flow)
        put("transport", transport)
        put("security", security)
        put("sni", sni)
        put("fingerprint", fingerprint)
        put("alpn", alpn)
        put("allowInsecure", allowInsecure)
        put("pinnedCertSha256", pinnedCertSha256)
        put("realityPublicKey", realityPublicKey)
        put("realityShortId", realityShortId)
        put("spiderX", spiderX)
        put("path", path)
        put("hostHeader", hostHeader)
        put("serviceName", serviceName)
        put("grpcMultiMode", grpcMultiMode)
        put("xhttpMode", xhttpMode)
    }.toString()

    /** Secret fields as JSON (encrypted before storage). */
    fun secretJson(): String = JSONObject().apply {
        put("username", username)
        put("password", password)
        put("uuid", uuid)
    }.toString()

    fun withPublicJson(json: String): ProxyProfile {
        val o = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        return copy(
            authEnabled = o.optBoolean("authEnabled", false),
            dnsMode = runCatching { DnsMode.valueOf(o.optString("dnsMode", "REMOTE")) }.getOrDefault(DnsMode.REMOTE),
            timeoutSec = o.optInt("timeoutSec", 0),
            encryption = o.optString("encryption", "none"),
            flow = o.optString("flow", ""),
            transport = o.optString("transport", Transport.TCP),
            security = o.optString("security", Security.NONE),
            sni = o.optString("sni", ""),
            fingerprint = o.optString("fingerprint", "chrome"),
            alpn = o.optString("alpn", ""),
            allowInsecure = o.optBoolean("allowInsecure", false),
            pinnedCertSha256 = o.optString("pinnedCertSha256", ""),
            realityPublicKey = o.optString("realityPublicKey", ""),
            realityShortId = o.optString("realityShortId", ""),
            spiderX = o.optString("spiderX", ""),
            path = o.optString("path", ""),
            hostHeader = o.optString("hostHeader", ""),
            serviceName = o.optString("serviceName", ""),
            grpcMultiMode = o.optBoolean("grpcMultiMode", false),
            xhttpMode = o.optString("xhttpMode", "auto"),
        )
    }

    fun withSecretJson(json: String): ProxyProfile {
        val o = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        return copy(
            username = o.optString("username", ""),
            password = o.optString("password", ""),
            uuid = o.optString("uuid", ""),
        )
    }

    /** Strings that must never appear in logs. */
    fun secretValues(): List<String> = listOf(username, password, uuid).filter { it.length >= 3 }

    fun displayServer(): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

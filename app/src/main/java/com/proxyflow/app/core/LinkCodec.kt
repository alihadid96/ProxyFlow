package com.proxyflow.app.core

import com.proxyflow.app.data.DnsMode
import com.proxyflow.app.data.FINGERPRINTS
import com.proxyflow.app.data.FLOWS
import com.proxyflow.app.data.Protocol
import com.proxyflow.app.data.ProxyProfile
import com.proxyflow.app.data.Security
import com.proxyflow.app.data.Transport
import com.proxyflow.app.data.XHTTP_MODES
import java.io.ByteArrayOutputStream
import java.util.Base64

data class ImportResult(val profiles: List<ProxyProfile>, val errors: List<String>)

/**
 * Parses and builds share links:
 *  vless://uuid@host:port?type=..&security=..#name
 *  socks5://[user:pass@]host:port#name  (also socks://, socks5h://, v2rayN base64 userinfo)
 *  http://[user:pass@]host:port#name
 * A blob may contain several links (one per line) or be base64 encoded (subscription format).
 */
object LinkCodec {

    fun parseMany(text: String): ImportResult {
        var input = text.trim()
        if (input.isEmpty()) return ImportResult(emptyList(), listOf("Nothing to import"))
        if (!input.contains("://")) {
            decodeBase64(input)?.takeIf { it.contains("://") }?.let { input = it }
        }
        val profiles = mutableListOf<ProxyProfile>()
        val errors = mutableListOf<String>()
        input.split('\n', '\r', ' ').map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            try {
                val p = parse(line)
                val problems = ProfileValidator.validate(p)
                if (problems.isEmpty()) profiles += p
                else errors += "${shorten(line)}: ${problems.joinToString("; ")}"
            } catch (e: IllegalArgumentException) {
                errors += "${shorten(line)}: ${e.message}"
            }
        }
        return ImportResult(profiles, errors)
    }

    private fun shorten(s: String): String {
        val scheme = s.substringBefore("://", "")
        return if (scheme.isNotEmpty()) "$scheme://…" else s.take(12) + "…"
    }

    fun parse(link: String): ProxyProfile {
        val scheme = link.substringBefore("://", "").lowercase()
        require(scheme.isNotEmpty()) { "Not a link (missing scheme://)" }
        var rest = link.substringAfter("://")
        val fragment = rest.substringAfter('#', "").let(::percentDecode)
        rest = rest.substringBefore('#')
        val query = parseQuery(rest.substringAfter('?', ""))
        var authority = rest.substringBefore('?').trimEnd('/')
        if (authority.contains('/')) authority = authority.substringBefore('/')
        val userInfo = if (authority.contains('@')) authority.substringBeforeLast('@') else ""
        val hostPort = authority.substringAfterLast('@')
        val (host, port) = splitHostPort(hostPort)

        return when (scheme) {
            "vless" -> parseVless(percentDecode(userInfo), host, port, query, fragment)
            "socks", "socks5", "socks5h" -> {
                val (u, p) = credentials(userInfo)
                ProxyProfile(
                    name = fragment.ifEmpty { "SOCKS5 $host" }, protocol = Protocol.SOCKS5, host = host, port = port,
                    authEnabled = u.isNotEmpty(), username = u, password = p,
                    dnsMode = if (scheme == "socks5" && query["dns"] == "local") DnsMode.LOCAL else DnsMode.REMOTE,
                )
            }
            "http" -> {
                val (u, p) = credentials(userInfo)
                ProxyProfile(
                    name = fragment.ifEmpty { "HTTP $host" }, protocol = Protocol.HTTP, host = host, port = port,
                    authEnabled = u.isNotEmpty(), username = u, password = p,
                    dnsMode = if (query["dns"] == "local") DnsMode.LOCAL else DnsMode.REMOTE,
                )
            }
            "https" -> throw IllegalArgumentException("HTTPS (TLS) proxies are not supported; use HTTP, SOCKS5 or VLESS")
            else -> throw IllegalArgumentException("Unsupported link type \"$scheme\"")
        }
    }

    private fun parseVless(uuid: String, host: String, port: Int, q: Map<String, String>, name: String): ProxyProfile {
        require(uuid.isNotEmpty()) { "VLESS link has no UUID" }
        val type = when (val t = (q["type"] ?: "tcp").lowercase()) {
            "raw", "tcp" -> Transport.TCP
            "ws", "websocket" -> Transport.WS
            "grpc", "gun" -> Transport.GRPC
            "xhttp", "splithttp" -> Transport.XHTTP
            "httpupgrade" -> Transport.HTTPUPGRADE
            "h2", "http" -> throw IllegalArgumentException("HTTP/2 transport was removed from Xray-core; ask your provider for an XHTTP link")
            else -> throw IllegalArgumentException("Unsupported transport \"$t\"")
        }
        val security = when (val s = (q["security"] ?: "none").lowercase()) {
            "", "none" -> Security.NONE
            "tls" -> Security.TLS
            "reality" -> Security.REALITY
            "xtls" -> throw IllegalArgumentException("Legacy XTLS security is no longer supported by Xray-core")
            else -> throw IllegalArgumentException("Unsupported security \"$s\"")
        }
        return ProxyProfile(
            name = name.ifEmpty { "VLESS $host" },
            protocol = Protocol.VLESS,
            host = host, port = port, uuid = uuid,
            encryption = q["encryption"]?.ifEmpty { null } ?: "none",
            flow = q["flow"] ?: "",
            transport = type,
            security = security,
            sni = q["sni"] ?: q["peer"] ?: "",
            fingerprint = q["fp"]?.ifEmpty { null } ?: "chrome",
            alpn = q["alpn"] ?: "",
            allowInsecure = q["allowInsecure"] == "1" || q["allowInsecure"] == "true" || q["insecure"] == "1",
            pinnedCertSha256 = q["pcs"] ?: "",
            realityPublicKey = q["pbk"] ?: "",
            realityShortId = q["sid"] ?: "",
            spiderX = q["spx"] ?: "",
            path = q["path"] ?: "",
            hostHeader = q["host"] ?: "",
            serviceName = q["serviceName"] ?: q["path"].takeIf { type == Transport.GRPC } ?: "",
            grpcMultiMode = q["mode"] == "multi",
            xhttpMode = q["mode"]?.takeIf { type == Transport.XHTTP && it in XHTTP_MODES } ?: "auto",
            dnsMode = DnsMode.REMOTE,
        ).let { if (type == Transport.GRPC) it.copy(path = "") else it }
    }

    /** Builds a share link. If [includeSecrets] is false, username/password are omitted (HTTP/SOCKS5 only). */
    fun export(p: ProxyProfile, includeSecrets: Boolean = true): String {
        val hp = AddressUtil.hostPort(p.host, p.port)
        val name = percentEncode(p.name)
        return when (p.protocol) {
            Protocol.HTTP, Protocol.SOCKS5 -> {
                val scheme = if (p.protocol == Protocol.HTTP) "http" else "socks5"
                val auth = if (includeSecrets && p.hasAuth) "${percentEncode(p.username)}:${percentEncode(p.password)}@" else ""
                val dns = if (p.dnsMode == DnsMode.LOCAL) "?dns=local" else ""
                "$scheme://$auth$hp$dns#$name"
            }
            Protocol.OPENVPN -> p.ovpnConfig
            Protocol.VLESS -> {
                val q = linkedMapOf<String, String>()
                q["encryption"] = p.encryption.ifEmpty { "none" }
                if (p.flow.isNotEmpty()) q["flow"] = p.flow
                q["type"] = p.transport
                q["security"] = p.security
                if (p.security != Security.NONE) {
                    if (p.sni.isNotEmpty()) q["sni"] = p.sni
                    if (p.fingerprint.isNotEmpty()) q["fp"] = p.fingerprint
                }
                if (p.security == Security.TLS) {
                    if (p.alpn.isNotEmpty()) q["alpn"] = p.alpn
                    if (p.pinnedCertSha256.isNotEmpty()) q["pcs"] = p.pinnedCertSha256
                }
                if (p.security == Security.REALITY) {
                    q["pbk"] = p.realityPublicKey
                    if (p.realityShortId.isNotEmpty()) q["sid"] = p.realityShortId
                    if (p.spiderX.isNotEmpty()) q["spx"] = p.spiderX
                }
                when (p.transport) {
                    Transport.WS, Transport.HTTPUPGRADE, Transport.XHTTP -> {
                        if (p.path.isNotEmpty()) q["path"] = p.path
                        if (p.hostHeader.isNotEmpty()) q["host"] = p.hostHeader
                        if (p.transport == Transport.XHTTP) q["mode"] = p.xhttpMode
                    }
                    Transport.GRPC -> {
                        if (p.serviceName.isNotEmpty()) q["serviceName"] = p.serviceName
                        if (p.grpcMultiMode) q["mode"] = "multi"
                    }
                }
                val qs = q.entries.joinToString("&") { "${it.key}=${percentEncode(it.value)}" }
                "vless://${percentEncode(p.uuid)}@$hp?$qs#$name"
            }
        }
    }

    private fun credentials(userInfo: String): Pair<String, String> {
        if (userInfo.isEmpty()) return "" to ""
        if (userInfo.contains(':')) {
            return percentDecode(userInfo.substringBefore(':')) to percentDecode(userInfo.substringAfter(':'))
        }
        val decoded = decodeBase64(percentDecode(userInfo))
        if (decoded != null && decoded.contains(':')) return decoded.substringBefore(':') to decoded.substringAfter(':')
        return percentDecode(userInfo) to ""
    }

    private fun splitHostPort(hp: String): Pair<String, Int> {
        val host: String
        val portStr: String
        if (hp.startsWith("[")) {
            host = hp.substringAfter('[').substringBefore(']')
            portStr = hp.substringAfter("]:", "")
        } else {
            require(hp.count { it == ':' } == 1) { "Missing or invalid port" }
            host = hp.substringBefore(':')
            portStr = hp.substringAfter(':')
        }
        val port = portStr.toIntOrNull()
        require(host.isNotEmpty()) { "Missing server address" }
        require(port != null && port in 1..65535) { "Invalid port \"$portStr\"" }
        return host to port
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate {
            it.substringBefore('=') to percentDecode(it.substringAfter('='))
        }

    fun decodeBase64(s: String): String? {
        val clean = s.trim().replace("\n", "").replace("\r", "")
        if (clean.isEmpty()) return null
        return listOf(Base64.getDecoder(), Base64.getUrlDecoder()).firstNotNullOfOrNull { dec ->
            runCatching {
                val pad = (4 - clean.length % 4) % 4
                String(dec.decode(clean + "=".repeat(pad)), Charsets.UTF_8)
            }.getOrNull()?.takeIf { str -> str.all { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() } }
        }
    }

    /** Percent-decoding that, unlike URLDecoder, leaves '+' untouched. */
    fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && s.substring(i + 1, i + 3).toIntOrNull(16) != null) {
                out.write(s.substring(i + 1, i + 3).toInt(16)); i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8)); i++
            }
        }
        return out.toString("UTF-8")
    }

    fun percentEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(String.format("%02X", c))
            }
        }
        return sb.toString()
    }
}

object ProfileValidator {
    private val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val shortIdRegex = Regex("^[0-9a-fA-F]{0,16}$")
    private val base64UrlKey = Regex("^[A-Za-z0-9_-]{42,44}={0,1}$")

    /** Returns a list of human readable problems; empty when the profile is usable. */
    fun validate(p: ProxyProfile): List<String> {
        val e = mutableListOf<String>()
        if (p.name.isBlank()) e += "Profile name is required"
        if (p.protocol != Protocol.OPENVPN) {
            if (!AddressUtil.isValidHost(p.host.trim())) e += "Server address is not a valid host name or IP"
            if (p.port !in 1..65535) e += "Port must be between 1 and 65535"
        }
        if (p.timeoutSec != 0 && p.timeoutSec !in 3..120) e += "Timeout must be 3–120 seconds"
        when (p.protocol) {
            Protocol.HTTP, Protocol.SOCKS5 -> {
                if (p.authEnabled && p.username.isEmpty()) e += "Username is required when authentication is enabled"
                if (p.protocol == Protocol.SOCKS5 && (p.username.toByteArray().size > 255 || p.password.toByteArray().size > 255))
                    e += "SOCKS5 username/password must be at most 255 bytes"
            }
            Protocol.OPENVPN -> {
                val parsed = OvpnConfig.parse(p.ovpnConfig)
                if (p.ovpnConfig.isBlank()) e += "OpenVPN configuration is empty"
                else e += parsed.errors
                if (parsed.needsUserPass && p.username.isEmpty()) e += "This server requires a username and password"
            }
            Protocol.VLESS -> {
                if (p.uuid.isBlank()) e += "UUID is required"
                else if (!uuidRegex.matches(p.uuid) && p.uuid.toByteArray().size > 30)
                    e += "UUID must be a UUID or an ID string of at most 30 bytes"
                if (p.flow !in FLOWS) e += "Unknown flow \"${p.flow}\""
                if (p.flow.isNotEmpty() && p.transport != Transport.TCP) e += "Flow (Vision) requires the TCP transport"
                if (p.flow.isNotEmpty() && p.security == Security.NONE) e += "Flow (Vision) requires TLS or REALITY"
                if (p.transport !in Transport.all) e += "Unsupported transport"
                if (p.security !in Security.all) e += "Unsupported security"
                if (p.security != Security.NONE && p.fingerprint.isNotEmpty() && p.fingerprint !in FINGERPRINTS)
                    e += "Unknown TLS fingerprint \"${p.fingerprint}\""
                if (p.security == Security.REALITY) {
                    if (p.realityPublicKey.isBlank()) e += "REALITY public key is required"
                    else if (!base64UrlKey.matches(p.realityPublicKey)) e += "REALITY public key looks invalid"
                    if (!shortIdRegex.matches(p.realityShortId)) e += "REALITY short ID must be up to 16 hex characters"
                    if (p.realityShortId.length % 2 != 0) e += "REALITY short ID must have an even length"
                    if (p.transport !in listOf(Transport.TCP, Transport.XHTTP, Transport.GRPC))
                        e += "REALITY works with TCP, XHTTP or gRPC transports"
                }
                if (p.pinnedCertSha256.isNotEmpty() && !Regex("^[0-9a-fA-F:,~]+$").matches(p.pinnedCertSha256))
                    e += "Pinned certificate SHA-256 must be hex"
            }
        }
        return e
    }
}

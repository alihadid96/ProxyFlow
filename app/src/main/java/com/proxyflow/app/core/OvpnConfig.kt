package com.proxyflow.app.core

/**
 * Parses, validates and sanitises OpenVPN (.ovpn) client configurations.
 *
 * - Certificates/keys must be embedded inline (<ca>…</ca>); references to external files are rejected.
 * - Directives that run programs, write files or would hijack the management interface are removed.
 * - `auth-user-pass` / `askpass` are answered by ProxyFlow over the management interface
 *   (credentials come from the encrypted profile and are never written to disk).
 */
object OvpnConfig {

    data class Remote(val host: String, val port: Int, val proto: String)

    data class Parsed(
        val remotes: List<Remote>,
        val needsUserPass: Boolean,
        val keyEncrypted: Boolean,
        val hasTlsAuthOrCrypt: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
        val sanitized: String,
    ) {
        val firstRemote: Remote? get() = remotes.firstOrNull()
    }

    /** Blocks whose content is copied verbatim. */
    private val INLINE_BLOCKS = setOf(
        "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "extra-certs", "pkcs12", "secret",
        "dh", "crl-verify", "http-proxy-user-pass", "auth-user-pass", "peer-fingerprint",
    )

    /** Options whose first argument is a file name unless it is "[inline]". */
    private val FILE_OPTIONS = setOf(
        "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "extra-certs", "pkcs12", "secret",
        "dh", "crl-verify", "capath",
    )

    /** Removed for safety (they execute programs, touch the filesystem or conflict with ProxyFlow). */
    private val BLOCKED = setOf(
        "management", "management-client", "management-hold", "management-query-passwords",
        "management-query-proxy", "management-query-remote", "management-signal", "management-forget-disconnect",
        "management-external-key", "management-external-cert", "management-client-auth", "management-up-down",
        "management-log-cache", "management-client-user", "management-client-group",
        "script-security", "up", "down", "route-up", "route-pre-down", "ipchange", "tls-verify",
        "auth-user-pass-verify", "client-connect", "client-disconnect", "learn-address", "plugin",
        "log", "log-append", "daemon", "writepid", "status", "chroot", "user", "group", "cd",
        "dev-node", "syslog", "iproute", "tmp-dir", "machine-readable-output", "config",
        "up-delay", "down-pre", "up-restart", "setenv-safe",
        // Windows / desktop-only options that would only produce warnings
        "block-outside-dns", "register-dns", "route-method", "ip-win32", "tap-sleep", "dhcp-renew",
        "dhcp-release", "service", "win-sys", "allow-pull-fqdn", "service-mode",
    )

    fun parse(text: String): Parsed {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val out = StringBuilder()
        val remotes = mutableListOf<Remote>()
        var defaultPort = 1194
        var defaultProto = "udp"
        var needsUserPass = false
        var keyEncrypted = false
        var tlsAuth = false
        var inlineUserPass = false
        var inlineBlock: String? = null
        var inConnection = false
        val pendingRemoteLines = mutableListOf<List<String>>()
        val removed = linkedSetOf<String>()

        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (raw in lines) {
            val line = raw.trim()
            if (inlineBlock != null) {
                out.append(raw.trimEnd()).append('\n')
                if (line.equals("</$inlineBlock>", true)) {
                    inlineBlock = null
                } else if (line.contains("ENCRYPTED")) keyEncrypted = true
                continue
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue

            val open = Regex("^<([a-zA-Z0-9-]+)>$").find(line)
            if (open != null) {
                val tag = open.groupValues[1].lowercase()
                when {
                    tag == "connection" -> { inConnection = true; out.append("<connection>\n") }
                    tag in INLINE_BLOCKS -> {
                        inlineBlock = tag
                        if (tag == "tls-auth" || tag == "tls-crypt" || tag == "tls-crypt-v2") tlsAuth = true
                        if (tag == "auth-user-pass") inlineUserPass = true
                        out.append("<$tag>\n")
                    }
                    else -> errors += "Unsupported inline block <$tag>"
                }
                continue
            }
            if (line.equals("</connection>", true)) { inConnection = false; out.append("</connection>\n"); continue }

            val tokens = tokenize(line)
            if (tokens.isEmpty()) continue
            val name = tokens[0].lowercase().removePrefix("--")
            when {
                name in BLOCKED -> { removed += name; continue }
                name == "dev" -> {
                    if (tokens.getOrNull(1)?.lowercase()?.startsWith("tap") == true)
                        errors += "TAP (layer 2) mode is not supported on Android; the server must use \"dev tun\""
                    out.append("dev tun\n"); continue
                }
                name == "dev-type" && tokens.getOrNull(1)?.lowercase() == "tap" -> {
                    errors += "TAP (layer 2) mode is not supported on Android"; continue
                }
                name == "auth-user-pass" -> {
                    needsUserPass = true
                    if (tokens.size > 1) warnings += "auth-user-pass file is ignored; enter the username and password in ProxyFlow"
                    out.append("auth-user-pass\n"); continue
                }
                name == "askpass" -> { keyEncrypted = true; out.append("askpass\n"); continue }
                name in FILE_OPTIONS && tokens.size > 1 && tokens[1] != "[inline]" -> {
                    errors += "\"$name ${tokens[1]}\" refers to an external file. Use an .ovpn file with the " +
                        "certificate/key embedded (<$name>…</$name>)."
                    continue
                }
                name == "tls-auth" || name == "tls-crypt" || name == "tls-crypt-v2" -> tlsAuth = true
                name == "port" -> tokens.getOrNull(1)?.toIntOrNull()?.let { defaultPort = it }
                name == "rport" -> tokens.getOrNull(1)?.toIntOrNull()?.let { defaultPort = it }
                name == "proto" -> tokens.getOrNull(1)?.let { defaultProto = it.lowercase() }
                name == "remote" -> {
                    if (tokens.size < 2) { errors += "\"remote\" without a server address"; continue }
                    pendingRemoteLines += tokens
                    // Placeholder replaced once all defaults (port/proto) are known.
                    out.append("\u0000REMOTE${pendingRemoteLines.size - 1}\n")
                    continue
                }
            }
            out.append(line).append('\n')
        }
        if (inlineBlock != null) errors += "Inline block <$inlineBlock> is not closed"

        var result = out.toString()
        pendingRemoteLines.forEachIndexed { i, t ->
            val host = t[1]
            val port = t.getOrNull(2)?.toIntOrNull() ?: defaultPort
            val proto = (t.getOrNull(3) ?: defaultProto).lowercase()
            if (port !in 1..65535) errors += "Invalid port in \"remote $host\""
            if (!AddressUtil.isValidHost(host)) errors += "Invalid server address \"$host\""
            remotes += Remote(host, port, proto)
            result = result.replace("\u0000REMOTE$i\n", "remote $host $port ${t.getOrNull(3) ?: ""}".trimEnd() + "\n")
        }
        if (remotes.isEmpty()) errors += "No \"remote\" server found in the configuration"
        if (!result.contains("<ca>") && !Regex("(?m)^(secret|peer-fingerprint)\\b").containsMatchIn(result) &&
            !result.contains("<secret>") && !result.contains("<peer-fingerprint>") && !result.contains("<pkcs12>")
        ) errors += "No CA certificate found (<ca>…</ca>)"
        if (removed.isNotEmpty()) warnings += "Removed unsupported/unsafe options: ${removed.joinToString(", ")}"
        return Parsed(remotes, needsUserPass && !inlineUserPass, keyEncrypted, tlsAuth, errors.distinct(), warnings, result)
    }

    /**
     * Builds the configuration actually handed to OpenVPN (over stdin).
     * [resolved] maps remote host names to IP addresses resolved on the physical network.
     */
    fun runtimeConfig(parsed: Parsed, managementSocket: String, resolved: Map<String, String>, versionTag: String): String {
        val sb = StringBuilder()
        sb.append("# Generated by ProxyFlow\n")
        sb.append("dev tun\n")
        sb.append("verb 3\n")
        var body = parsed.sanitized
        for ((host, ip) in resolved) {
            body = body.replace(Regex("(?m)^remote ${Regex.escape(host)} "), "remote $ip ")
        }
        sb.append(body)
        sb.append("\n# ProxyFlow control interface\n")
        sb.append("management ").append(managementSocket).append(" unix\n")
        sb.append("management-client\n")
        sb.append("management-query-passwords\n")
        sb.append("management-hold\n")
        sb.append("ifconfig-nowarn\n")
        sb.append("allow-recursive-routing\n")
        sb.append("setenv IV_GUI_VER \"").append(versionTag).append("\"\n")
        return sb.toString()
    }

    /** OpenVPN-style tokenizer (handles "quoted strings" and 'single quotes'). */
    fun tokenize(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var i = 0
        var any = false
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null && c == '\\' && i + 1 < line.length && quote == '"' -> { cur.append(line[i + 1]); i++ }
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> { quote = c; any = true }
                quote == null && c.isWhitespace() -> {
                    if (cur.isNotEmpty() || any) { out += cur.toString(); cur.clear(); any = false }
                }
                quote == null && (c == '#' || c == ';') && cur.isEmpty() -> break
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty() || any) out += cur.toString()
        return out
    }

    /** Escapes a value for the management protocol / OpenVPN config. */
    fun escape(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    // ---------------------------------------------------------------- CIDR helpers

    fun maskToPrefix(mask: String): Int? {
        if (!AddressUtil.isIpv4(mask)) return null
        val v = ipv4ToLong(mask)
        val bits = java.lang.Long.bitCount(v)
        // must be contiguous
        val expected = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return if (v == expected) bits else null
    }

    fun ipv4ToLong(ip: String): Long =
        ip.split('.').fold(0L) { acc, p -> (acc shl 8) or (p.toLong() and 0xFF) }

    fun networkAddress(ip: String, prefix: Int): String {
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val n = ipv4ToLong(ip) and mask
        return "${n shr 24 and 0xFF}.${n shr 16 and 0xFF}.${n shr 8 and 0xFF}.${n and 0xFF}"
    }

    fun cidrContains(network: String, prefix: Int, ip: String): Boolean {
        if (!AddressUtil.isIpv4(ip)) return false
        return networkAddress(network, prefix) == networkAddress(ip, prefix)
    }

    /** True if the two IPv4 networks overlap. */
    fun overlaps(a: String, aPrefix: Int, b: String, bPrefix: Int): Boolean {
        val p = minOf(aPrefix, bPrefix)
        return networkAddress(a, p) == networkAddress(b, p)
    }
}

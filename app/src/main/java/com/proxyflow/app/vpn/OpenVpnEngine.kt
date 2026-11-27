package com.proxyflow.app.vpn

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import com.proxyflow.app.BuildConfig
import com.proxyflow.app.core.AddressUtil
import com.proxyflow.app.core.OvpnConfig
import com.proxyflow.app.data.ProxyProfile
import java.io.File
import java.io.FileDescriptor
import java.util.ArrayDeque
import kotlin.concurrent.thread

/** Network settings OpenVPN asked for, collected from NEED-OK requests before OPENTUN. */
data class TunConfig(
    val ipv4: String? = null,
    val prefix4: Int = 32,
    val mtu: Int = 1500,
    val ipv6: String? = null,
    val prefix6: Int = 128,
    val routes: List<Pair<String, Int>> = emptyList(),
    val excludedRoutes: List<Pair<String, Int>> = emptyList(),
    val routes6: List<Pair<String, Int>> = emptyList(),
    val dns: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
)

/**
 * Runs the OpenVPN 2.6 core (lib/<abi>/libovpnexec.so + libopenvpn.so, built from ics-openvpn) as a child
 * process and drives it over the management interface (unix socket):
 *  - TUN creation (IFCONFIG/ROUTE/DNSSERVER… then OPENTUN, answered with the TUN fd via SCM_RIGHTS)
 *  - socket protection (PROTECTFD) so OpenVPN's own connection bypasses the tunnel
 *  - credentials (username/password, private key password) from the encrypted profile
 *  - state and byte counters.
 */
class OpenVpnEngine(
    private val context: VpnService,
    private val profile: ProxyProfile,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(name: String, detail: String, localIp: String, remoteIp: String)
        fun onBytes(inBytes: Long, outBytes: Long)
        fun onLog(line: String)
        fun onFatal(message: String)
        fun onExit(code: Int)
        /** Creates (or replaces) the TUN interface. Return null to refuse. */
        fun buildTun(cfg: TunConfig): ParcelFileDescriptor?
    }

    private val tag = "ProxyFlowOvpn"
    private val socketFile = File(context.cacheDir, "ovpn-mgmt")
    private var serverSocket: LocalServerSocket? = null
    private var bindSocket: LocalSocket? = null
    @Volatile private var mgmt: LocalSocket? = null
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    private val fdQueue = ArrayDeque<FileDescriptor>()
    private var pending = TunConfig()
    private var lastTun: TunConfig? = null
    private val secrets = profile.secretValues()
    private val recent = ArrayDeque<String>()

    val isRunning get() = process?.isAlive == true

    fun lastLogLines(): List<String> = synchronized(recent) { recent.toList() }

    /** Starts OpenVPN. [network] is the physical network used to resolve server names. */
    fun start(network: Network?) {
        val parsed = OvpnConfig.parse(profile.ovpnConfig)
        if (parsed.errors.isNotEmpty()) throw IllegalArgumentException(parsed.errors.first())
        val libDir = context.applicationInfo.nativeLibraryDir
        val exe = File(libDir, "libovpnexec.so")
        if (!exe.exists()) throw IllegalStateException("OpenVPN binary is missing from this build")

        // Resolve server names on the physical network now: once the tunnel is up (and during
        // reconnects) lookups from this app would otherwise go into the tunnel.
        val resolved = mutableMapOf<String, String>()
        for (r in parsed.remotes) {
            if (AddressUtil.isIp(r.host) || resolved.containsKey(r.host)) continue
            runCatching {
                val addrs = network?.getAllByName(r.host) ?: java.net.InetAddress.getAllByName(r.host)
                (addrs.firstOrNull { it is java.net.Inet4Address } ?: addrs.first()).hostAddress
            }.getOrNull()?.let { resolved[r.host] = it }
        }
        if (resolved.isNotEmpty()) listener.onLog("Resolved ${resolved.size} server name(s) on the physical network")

        // Management socket (we are the server; OpenVPN connects with --management-client).
        socketFile.delete()
        val ls = LocalSocket()
        ls.bind(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        bindSocket = ls
        serverSocket = LocalServerSocket(ls.fileDescriptor)

        val config = OvpnConfig.runtimeConfig(parsed, socketFile.absolutePath, resolved, "ProxyFlow ${BuildConfig.VERSION_NAME}")
        val pb = ProcessBuilder(exe.absolutePath, "--config", "stdin")
            .directory(context.cacheDir)
            .redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = libDir
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        val proc = pb.start()
        process = proc
        proc.outputStream.use { it.write(config.toByteArray(Charsets.UTF_8)) }

        thread(name = "ovpn-out", isDaemon = true) {
            runCatching { proc.inputStream.bufferedReader().forEachLine { log(it) } }
        }
        thread(name = "ovpn-mgmt", isDaemon = true) { managementLoop() }
        thread(name = "ovpn-wait", isDaemon = true) {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            runCatching { serverSocket?.close() } // unblocks accept() if OpenVPN never connected
            if (!stopping) listener.onExit(code)
        }
    }

    fun networkChange() = command("network-change\n")

    fun stop() {
        stopping = true
        command("signal SIGINT\n")
        val p = process
        if (p != null) {
            runCatching {
                if (!p.waitFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly()
            }
        }
        process = null
        runCatching { mgmt?.close() }
        runCatching { serverSocket?.close() }
        runCatching { bindSocket?.close() }
        socketFile.delete()
    }

    // --------------------------------------------------------------- management protocol

    private fun managementLoop() {
        try {
            val sock = serverSocket?.accept() ?: return
            mgmt = sock
            runCatching { serverSocket?.close() }
            command("version 3\n")
            val buf = ByteArray(4096)
            var pendingText = ""
            val inp = sock.inputStream
            while (!stopping) {
                val n = inp.read(buf)
                if (n < 0) break
                sock.ancillaryFileDescriptors?.let { fds -> synchronized(fdQueue) { fds.forEach { fdQueue.add(it) } } }
                pendingText += String(buf, 0, n, Charsets.UTF_8)
                while (true) {
                    val idx = pendingText.indexOf('\n')
                    if (idx < 0) break
                    val line = pendingText.substring(0, idx).trimEnd('\r')
                    pendingText = pendingText.substring(idx + 1)
                    handle(line)
                }
            }
        } catch (e: Exception) {
            if (!stopping) Log.i(tag, "management closed: ${e.javaClass.simpleName}")
        }
    }

    private fun command(cmd: String): Boolean = synchronized(this) {
        val s = mgmt ?: return false
        runCatching { s.outputStream.write(cmd.toByteArray(Charsets.UTF_8)); s.outputStream.flush() }.isSuccess
    }

    private fun handle(line: String) {
        when {
            line.startsWith(">") && line.contains(':') -> {
                val type = line.substring(1, line.indexOf(':'))
                val arg = line.substring(line.indexOf(':') + 1)
                when (type) {
                    "HOLD" -> {
                        command("hold release\n")
                        command("bytecount 1\n")
                        command("state on\n")
                    }
                    "PASSWORD" -> handlePassword(arg)
                    "NEED-OK" -> handleNeedOk(arg)
                    "BYTECOUNT" -> {
                        val parts = arg.split(',')
                        val i = parts.getOrNull(0)?.toLongOrNull(); val o = parts.getOrNull(1)?.toLongOrNull()
                        if (i != null && o != null) listener.onBytes(i, o)
                    }
                    "STATE" -> {
                        val p = arg.split(',')
                        listener.onState(p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, p.getOrElse(3) { "" }, p.getOrElse(4) { "" })
                    }
                    "FATAL" -> listener.onFatal(sanitize(arg))
                    "INFO", "LOG", "INFOMSG", "ACC" -> Unit
                    else -> Unit
                }
            }
            line.startsWith("PROTECTFD:") -> pollFd()?.let(::protect)
            line.startsWith("SUCCESS:") || line.startsWith("ERROR:") -> {
                if (line.startsWith("ERROR:")) log("management: $line")
            }
        }
    }

    private fun handlePassword(arg: String) {
        if (arg.startsWith("Auth-Token:")) return
        val p1 = arg.indexOf('\''); val p2 = arg.indexOf('\'', p1 + 1)
        if (p1 < 0 || p2 < 0) return
        val needed = arg.substring(p1 + 1, p2)
        if (arg.startsWith("Verification Failed")) {
            listener.onFatal(
                if (needed == "Auth") "Authentication failed: the server rejected the username or password"
                else "Verification failed for '$needed'"
            )
            command("signal SIGTERM\n")
            return
        }
        when (needed) {
            "Auth" -> {
                if (profile.username.isEmpty()) {
                    listener.onFatal("The server requires a username and password — edit the profile")
                    command("signal SIGTERM\n"); return
                }
                command("username 'Auth' ${OvpnConfig.escape(profile.username)}\n")
                command("password 'Auth' ${OvpnConfig.escape(profile.password)}\n")
            }
            "Private Key" -> {
                if (profile.keyPassword.isEmpty()) {
                    listener.onFatal("The private key is encrypted — enter its password in the profile")
                    command("signal SIGTERM\n"); return
                }
                command("password 'Private Key' ${OvpnConfig.escape(profile.keyPassword)}\n")
            }
            else -> {
                listener.onFatal("OpenVPN asked for '$needed' credentials, which ProxyFlow cannot provide")
                command("signal SIGTERM\n")
            }
        }
    }

    private fun handleNeedOk(arg: String) {
        val p1 = arg.indexOf('\''); val p2 = arg.indexOf('\'', p1 + 1)
        if (p1 < 0 || p2 < 0) return
        val needed = arg.substring(p1 + 1, p2)
        val extra = arg.substringAfter("MSG:", arg.substringAfter(':', "")).trim()
        var status = "ok"
        when (needed) {
            "PROTECTFD" -> pollFd()?.let(::protect)
            "DNSSERVER", "DNS6SERVER" -> pending = pending.copy(dns = pending.dns + extra)
            "DNSDOMAIN" -> pending = pending.copy(domains = pending.domains + extra)
            "IFCONFIG" -> {
                // "<local> <netmask|remote> <mtu> <topology>"
                val p = extra.split(' ')
                val prefix = OvpnConfig.maskToPrefix(p.getOrElse(1) { "" })?.takeIf { p.getOrNull(3) != "p2p" } ?: 32
                pending = pending.copy(ipv4 = p[0], prefix4 = if (p.getOrNull(3) == "net30") 30 else prefix,
                    mtu = p.getOrNull(2)?.toIntOrNull() ?: pending.mtu)
            }
            "IFCONFIG6" -> {
                // "<addr>/<prefix> <mtu>"
                val p = extra.split(' ')
                pending = pending.copy(ipv6 = p[0].substringBefore('/'), prefix6 = p[0].substringAfter('/', "128").toIntOrNull() ?: 128,
                    mtu = p.getOrNull(1)?.toIntOrNull() ?: pending.mtu)
            }
            "ROUTE" -> {
                // "<network> <netmask> <gateway> [dev <iface>]"
                val p = extra.split(' ')
                val prefix = OvpnConfig.maskToPrefix(p.getOrElse(1) { "" })
                if (prefix != null && AddressUtil.isIpv4(p[0])) {
                    val dev = if (p.size >= 5) p[4] else null
                    val route = OvpnConfig.networkAddress(p[0], prefix) to prefix
                    pending = if (isTunDevice(dev)) pending.copy(routes = pending.routes + route)
                    else pending.copy(excludedRoutes = pending.excludedRoutes + route)
                }
            }
            "ROUTE6" -> {
                val net = extra.split(' ')[0]
                val prefix = net.substringAfter('/', "128").toIntOrNull() ?: 128
                pending = pending.copy(routes6 = pending.routes6 + (net.substringBefore('/') to prefix))
            }
            "PERSIST_TUN_ACTION" -> {
                status = if (pending == lastTun) "NOACTION" else "OPEN_BEFORE_CLOSE"
                if (status == "NOACTION") pending = TunConfig()
            }
            "OPENTUN" -> {
                if (extra != "tun") {
                    log("OpenVPN requested a '$extra' device; only tun is possible on Android")
                    command("needok 'OPENTUN' cancel\n"); return
                }
                val cfg = pending
                val pfd = runCatching { listener.buildTun(cfg) }.onFailure { log("Could not create VPN interface: ${it.message}") }.getOrNull()
                if (pfd == null) { command("needok 'OPENTUN' cancel\n"); return }
                lastTun = cfg
                pending = TunConfig()
                synchronized(this) {
                    val s = mgmt ?: return
                    runCatching {
                        s.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                        s.outputStream.write("needok 'OPENTUN' ok\n".toByteArray())
                        s.outputStream.flush()
                        s.setFileDescriptorsForSend(null)
                    }.onFailure { log("Could not pass the VPN interface to OpenVPN: ${it.message}") }
                }
                return
            }
            "HTTPPROXY" -> Unit
            else -> { log("Unknown OpenVPN request '$needed'"); return }
        }
        command("needok '$needed' $status\n")
    }

    /**
     * Routes through the VPN are reported with OpenVPN's Android tun name ("vpnservice-tun"); routes via
     * any other device (e.g. "dev wlan0" for net_gateway) must bypass the tunnel.
     */
    private fun isTunDevice(dev: String?) =
        dev == null || dev == "(null)" || dev == "vpnservice-tun" || dev.startsWith("tun")

    private fun pollFd(): FileDescriptor? = synchronized(fdQueue) { fdQueue.pollFirst() }

    /** Excludes OpenVPN's server socket from the VPN, then closes our duplicate of the descriptor. */
    private fun protect(fd: FileDescriptor) {
        runCatching {
            ParcelFileDescriptor.dup(fd).use { dup ->
                if (!context.protect(dup.fd)) log("Could not protect the OpenVPN socket")
            }
        }.onFailure { log("protect failed: ${it.message}") }
        runCatching { Os.close(fd) }
    }

    private fun sanitize(s: String): String {
        var out = s
        for (secret in secrets) out = out.replace(secret, "***")
        return out
    }

    private fun log(raw: String) {
        val line = sanitize(raw)
        synchronized(recent) { recent.addLast(line); while (recent.size > 30) recent.removeFirst() }
        listener.onLog(line)
    }
}

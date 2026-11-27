package com.proxyflow.app.vpn

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.proxyflow.app.ProxyFlowApp
import com.proxyflow.app.R
import com.proxyflow.app.core.LocalInbound
import com.proxyflow.app.core.ProfileValidator
import com.proxyflow.app.core.ProxyTester
import com.proxyflow.app.core.XrayConfigBuilder
import com.proxyflow.app.core.XrayProcess
import com.proxyflow.app.data.AppSettings
import com.proxyflow.app.data.Ipv6Mode
import com.proxyflow.app.data.PerAppMode
import com.proxyflow.app.data.ProxyProfile
import com.proxyflow.app.ui.MainActivity
import com.proxyflow.app.ui.formatBytes
import com.proxyflow.app.ui.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Rootless VPN: TUN interface -> hev-socks5-tunnel (lwIP, in-process via JNI)
 *   -> local authenticated SOCKS5 inbound of Xray-core (child process)
 *   -> HTTP CONNECT / SOCKS5 / VLESS upstream.
 * The app's own UID is excluded from the VPN so Xray's upstream sockets never loop back into the tunnel.
 */
class ProxyVpnService : VpnService() {

    companion object {
        private const val TAG = "ProxyFlowVpn"
        const val ACTION_CONNECT = "com.proxyflow.app.action.CONNECT"
        const val ACTION_DISCONNECT = "com.proxyflow.app.action.DISCONNECT"
        const val ACTION_MEASURE = "com.proxyflow.app.action.MEASURE"
        const val EXTRA_PROFILE = "profile_id"
        private const val NOTIFICATION_ID = 1001
        private const val EVENT_NOTIFICATION_ID = 1002
        private const val MTU = 1500
        private const val TUN_IPV4 = "172.19.0.1"
        private const val TUN_IPV6 = "fdfe:dcba:9876::1"

        fun connect(context: Context, profileId: Long) {
            val i = Intent(context, ProxyVpnService::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_PROFILE, profileId)
            ContextCompat.startForegroundService(context, i)
        }

        fun disconnect(context: Context) {
            if (VpnState.status.value.state == ConnState.DISCONNECTED) return
            runCatching {
                context.startService(Intent(context, ProxyVpnService::class.java).setAction(ACTION_DISCONNECT))
            }.onFailure { Log.w(TAG, "disconnect: ${it.message}") }
        }

        fun measure(context: Context) {
            if (VpnState.status.value.state != ConnState.CONNECTED) return
            runCatching { context.startService(Intent(context, ProxyVpnService::class.java).setAction(ACTION_MEASURE)) }
        }
    }

    private val app get() = application as ProxyFlowApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private var tun: ParcelFileDescriptor? = null
    private var xray: XrayProcess? = null
    private var inbound: LocalInbound? = null
    private var profile: ProxyProfile? = null
    private var settings: AppSettings = AppSettings()
    private var hevStarted = false
    private val sessionJobs = mutableListOf<Job>()
    private var watchdog: Job? = null

    @Volatile private var stopping = false
    @Volatile private var active = false
    @Volatile private var currentNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var unsavedDown = 0L
    private var unsavedUp = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                scope.launch { stop(null) }
                return START_NOT_STICKY
            }
            ACTION_MEASURE -> {
                scope.launch { runMeasurements() }
                return START_STICKY
            }
            else -> {
                // ACTION_CONNECT, the system's always-on intent ("android.net.VpnService"), or a sticky restart (null).
                startInForeground(buildNotification(ConnState.CONNECTING, "Starting…"))
                val requested = intent?.getLongExtra(EXTRA_PROFILE, -1L) ?: -1L
                val restartedBySystem = intent == null
                scope.launch {
                    if (restartedBySystem && !app.settings.current().autoReconnect) {
                        stop(null); return@launch
                    }
                    connect(requested)
                }
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------ connect

    private suspend fun connect(requestedId: Long) = lock.withLock {
        stopping = false
        val s = app.settings.current()
        val id = if (requestedId > 0) requestedId else s.selectedProfileId
        if (active && profile?.id == id) return@withLock
        if (active) teardown(keepForeground = true)

        try {
            settings = s
            val p = app.profiles.get(id) ?: throw IllegalStateException("No proxy profile selected")
            profile = p
            VpnState.clearLogs()
            VpnState.setStatus {
                ConnectionStatus(ConnState.CONNECTING, p.id, p.name, p.protocol, p.displayServer(), message = "Starting Xray-core…")
            }
            VpnState.setTraffic(TrafficStats())
            VpnState.setMeasure { Measurements() }
            updateNotification(ConnState.CONNECTING, p.name)

            ProfileValidator.validate(p).takeIf { it.isNotEmpty() }?.let { throw IllegalArgumentException(it.first()) }
            if (prepare(this) != null) throw IllegalStateException("VPN permission has not been granted — open ProxyFlow and connect once")
            TProxyService.loadError?.let { throw IllegalStateException("Tunnel library failed to load: ${it.message}") }

            val ib = XrayProcess.newInbound()
            inbound = ib
            startXray(p, ib)

            VpnState.setStatus { it.copy(message = "Creating VPN interface…", localSocksPort = ib.port) }
            val fd = establishTun(p, s)
            tun = fd
            startHev(ib, fd)

            active = true
            VpnState.setStatus {
                it.copy(state = ConnState.CONNECTED, connectedAt = System.currentTimeMillis(), message = null,
                    networkType = networkType(currentNetwork))
            }
            VpnState.log("Connected via ${p.protocol.label} ${p.displayServer()}")
            updateNotification(ConnState.CONNECTED, p.name)
            eventNotification("Connected", "${p.name} · ${p.protocol.label}")
            registerNetworkCallback()
            startSessionJobs()
        } catch (e: Exception) {
            Log.w(TAG, "connect failed: ${e.javaClass.simpleName}")
            val msg = ProxyTester.friendly(e)
            VpnState.log("Connection failed: $msg")
            teardown(keepForeground = false)
            VpnState.setStatus { it.copy(state = ConnState.ERROR, message = msg) }
            eventNotification("Connection failed", msg)
            stopSelf()
        }
    }

    private fun startXray(p: ProxyProfile, ib: LocalInbound) {
        val x = XrayProcess(this, p.secretValues() + ib.pass) { VpnState.log(it) }
        xray = x
        x.start(XrayConfigBuilder.build(p, settings, ib))
        x.awaitReady(ib.port)
        startWatchdog(x)
    }

    private fun establishTun(p: ProxyProfile, s: AppSettings): ParcelFileDescriptor {
        val b = Builder()
            .setSession("ProxyFlow · ${p.name}")
            .setMtu(MTU)
            .addAddress(TUN_IPV4, 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(s.dnsIp())
        if (s.ipv6Mode != Ipv6Mode.BYPASS) {
            b.addAddress(TUN_IPV6, 126)
            b.addRoute("::", 0)
        }
        val pm = packageManager
        fun installed(pkg: String) = runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)
        val selected = s.selectedApps.filter { it != packageName && installed(it) }
        when {
            s.perAppMode == PerAppMode.ONLY_SELECTED && selected.isNotEmpty() ->
                selected.forEach { b.addAllowedApplication(it) }
            s.perAppMode == PerAppMode.BYPASS_SELECTED -> {
                b.addDisallowedApplication(packageName)
                selected.forEach { b.addDisallowedApplication(it) }
            }
            else -> {
                if (s.perAppMode == PerAppMode.ONLY_SELECTED) VpnState.log("No apps selected for \"only selected apps\" — routing all apps")
                b.addDisallowedApplication(packageName)
            }
        }
        b.setMetered(false)
        val cm = getSystemService(ConnectivityManager::class.java)
        currentNetwork = cm.activeNetwork
        currentNetwork?.let { b.setUnderlyingNetworks(arrayOf(it)) }
        b.setConfigureIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        return b.establish() ?: throw IllegalStateException("Android refused to create the VPN interface (permission revoked?)")
    }

    private fun startHev(ib: LocalInbound, fd: ParcelFileDescriptor) {
        val timeoutMs = settings.connectTimeoutSec * 1000
        val yaml = """
            |tunnel:
            |  mtu: $MTU
            |  ipv4: $TUN_IPV4
            |  ipv6: '$TUN_IPV6'
            |socks5:
            |  port: ${ib.port}
            |  address: 127.0.0.1
            |  udp: 'udp'
            |  username: '${ib.user}'
            |  password: '${ib.pass}'
            |misc:
            |  task-stack-size: 81920
            |  connect-timeout: $timeoutMs
            |  log-level: warn
            |""".trimMargin()
        val f = File(cacheDir, "tunnel.yml")
        f.writeText(yaml)
        f.setReadable(false, false); f.setReadable(true, true)
        if (!TProxyService.TProxyStartService(f.absolutePath, fd.fd)) {
            throw IllegalStateException("Failed to start the packet forwarding engine")
        }
        hevStarted = true
    }

    // ------------------------------------------------------------------ monitoring

    private fun startWatchdog(x: XrayProcess) {
        watchdog?.cancel()
        watchdog = scope.launch {
            val code = x.waitFor()
            if (!stopping && xray === x && active) {
                VpnState.log("Xray-core stopped unexpectedly (exit $code)")
                reconnect("Proxy engine stopped")
            }
        }
    }

    private fun startSessionJobs() {
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        // Real traffic counters from the TUN engine.
        sessionJobs += scope.launch {
            var lastUp = 0L
            var lastDown = 0L
            var tick = 0
            while (isActive) {
                val st = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
                if (st != null && st.size >= 4) {
                    val up = st[1]
                    val down = st[3]
                    val du = (up - lastUp).coerceAtLeast(0)
                    val dd = (down - lastDown).coerceAtLeast(0)
                    unsavedUp += du; unsavedDown += dd
                    lastUp = up; lastDown = down
                    VpnState.setTraffic(TrafficStats(up, down, du, dd))
                }
                tick++
                if (tick % 2 == 0) {
                    val stt = VpnState.status.value
                    updateNotification(stt.state, profile?.name.orEmpty())
                }
                if (tick % 30 == 0) flushTraffic()
                delay(1000)
            }
        }
        // Health check + measurements.
        sessionJobs += scope.launch {
            delay(800)
            runMeasurements()
            var failures = 0
            while (isActive) {
                delay(30_000)
                if (VpnState.status.value.state != ConnState.CONNECTED) continue
                val ib = inbound ?: continue
                val ok = runCatching {
                    val ms = ProxyTester.urlTest(ProxyTester.localTunnel(ib), settings.connectTimeoutSec * 1000)
                    VpnState.setMeasure { it.copy(urlLatencyMs = ms, measuredAt = System.currentTimeMillis()) }
                }.isSuccess
                failures = if (ok) 0 else failures + 1
                if (failures >= 3 && currentNetwork != null) {
                    failures = 0
                    VpnState.log("Proxy did not respond to 3 health checks")
                    reconnect("Proxy not responding")
                }
            }
        }
    }

    private suspend fun runMeasurements() {
        val ib = inbound ?: return
        val p = profile ?: return
        VpnState.setMeasure { it.copy(measuring = true) }
        val timeout = settings.connectTimeoutSec * 1000
        val rtt = ProxyTester.tcpPing(p.host.trim(), p.port)
        val latency = runCatching { ProxyTester.urlTest(ProxyTester.localTunnel(ib), timeout) }
        var exit = VpnState.measure.value.exit
        var exitErr: String? = null
        if (settings.lookupExitIp) {
            runCatching { ProxyTester.exitInfo(ProxyTester.localTunnel(ib), timeout) }
                .onSuccess { exit = it }
                .onFailure { exitErr = ProxyTester.friendly(it) }
        }
        VpnState.setMeasure {
            it.copy(
                exit = exit, exitError = exitErr ?: latency.exceptionOrNull()?.let(ProxyTester::friendly),
                urlLatencyMs = latency.getOrDefault(-1), serverRttMs = rtt,
                measuredAt = System.currentTimeMillis(), measuring = false,
            )
        }
    }

    private suspend fun flushTraffic() {
        val d = unsavedDown; val u = unsavedUp
        if (d == 0L && u == 0L) return
        unsavedDown = 0; unsavedUp = 0
        runCatching { app.settings.addLifetimeTraffic(d, u) }
    }

    // ------------------------------------------------------------------ reconnect

    private fun reconnect(reason: String) {
        scope.launch {
            lock.withLock {
                if (stopping || !active) return@withLock
                val p = profile ?: return@withLock
                val ib = inbound ?: return@withLock
                var attempt = 0
                while (!stopping) {
                    attempt++
                    VpnState.setStatus { it.copy(state = ConnState.RECONNECTING, message = reason, reconnectAttempt = attempt) }
                    updateNotification(ConnState.RECONNECTING, reason)
                    xray?.stop()
                    xray = null
                    if (currentNetwork == null) {
                        VpnState.setStatus { it.copy(message = "Waiting for network…") }
                        sleepUnlessStopping(2000)
                        if (attempt > 1) attempt-- // waiting for a network is not a failed attempt
                        continue
                    }
                    try {
                        startXray(p, ib)
                        VpnState.setStatus { it.copy(state = ConnState.CONNECTED, message = null, reconnectAttempt = 0,
                            networkType = networkType(currentNetwork)) }
                        VpnState.log("Reconnected ($reason)")
                        updateNotification(ConnState.CONNECTED, p.name)
                        scope.launch { delay(500); runMeasurements() }
                        return@withLock
                    } catch (e: Exception) {
                        val msg = ProxyTester.friendly(e)
                        VpnState.log("Reconnect attempt $attempt failed: $msg")
                        val giveUp = !settings.autoReconnect || (!settings.killSwitch && attempt >= 5)
                        if (giveUp) {
                            teardown(keepForeground = false)
                            VpnState.setStatus { it.copy(state = ConnState.ERROR, message = "Connection lost: $msg") }
                            eventNotification("Connection lost", msg)
                            stopSelf()
                            return@withLock
                        }
                        // Kill switch: the TUN interface stays up, so traffic is blocked instead of leaking.
                        sleepUnlessStopping(minOf(30_000L, 1000L shl minOf(attempt, 5)))
                    }
                }
            }
        }
    }

    private suspend fun sleepUnlessStopping(ms: Long) {
        var left = ms
        while (left > 0 && !stopping) {
            delay(minOf(250L, left)); left -= 250
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = currentNetwork
                if (previous == network) return
                currentNetwork = network
                setUnderlyingNetworks(arrayOf(network))
                VpnState.log("Network available: ${networkType(network)}")
                VpnState.setStatus { it.copy(networkType = networkType(network)) }
                // Restart Xray so stale upstream connections from the old network are dropped.
                if (active) reconnect(if (previous == null) "Network restored" else "Network changed")
            }

            override fun onLost(network: Network) {
                if (network != currentNetwork) return
                currentNetwork = null
                setUnderlyingNetworks(null)
                VpnState.log("Network lost")
                if (active && !stopping) {
                    VpnState.setStatus { it.copy(state = ConnState.RECONNECTING, message = "Waiting for network…") }
                    updateNotification(ConnState.RECONNECTING, "Waiting for network…")
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { networkCallback = cb }
    }

    private fun networkType(network: Network?): String {
        if (network == null) return "No network"
        val caps = getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network) ?: return "Unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
    }

    // ------------------------------------------------------------------ stop / cleanup

    private suspend fun stop(error: String?) {
        stopping = true
        lock.withLock {
            val wasActive = active || tun != null
            teardown(keepForeground = false)
            VpnState.setStatus {
                if (error != null) it.copy(state = ConnState.ERROR, message = error)
                else ConnectionStatus(ConnState.DISCONNECTED)
            }
            if (wasActive) eventNotification("Disconnected", error ?: "VPN stopped")
            stopSelf()
        }
    }

    /** Releases everything. Must be called with [lock] held (or from onDestroy). */
    private fun teardown(keepForeground: Boolean) {
        active = false
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        watchdog?.cancel()
        watchdog = null
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        if (hevStarted) {
            runCatching { TProxyService.TProxyStopService() }
            hevStarted = false
        }
        xray?.stop()
        xray = null
        runCatching { tun?.close() }
        tun = null
        inbound = null
        runCatching { File(cacheDir, "tunnel.yml").delete() }
        runBlocking { flushTraffic() }
        if (!keepForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        VpnState.log("VPN permission revoked (another VPN started or the user revoked it)")
        scope.launch { stop("VPN was turned off by the system or another VPN app") }
    }

    override fun onDestroy() {
        if (active || tun != null) {
            stopping = true
            teardown(keepForeground = false)
            VpnState.setStatus { ConnectionStatus(ConnState.DISCONNECTED) }
        }
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notifications

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun buildNotification(state: ConnState, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProxyVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val t = VpnState.traffic.value
        val body = if (state == ConnState.CONNECTED)
            "$text  ↓ ${formatSpeed(t.downloadSpeed)}  ↑ ${formatSpeed(t.uploadSpeed)}  · ${formatBytes(t.downloadBytes + t.uploadBytes)}"
        else text
        return NotificationCompat.Builder(this, ProxyFlowApp.CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ProxyFlow · ${state.label}")
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Disconnect", stop)
            .build()
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun updateNotification(state: ConnState, text: String) {
        if (!canNotify() || stopping) return
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state, text)) }
    }

    private fun eventNotification(title: String, text: String) {
        if (!settings.connectionNotifications || !canNotify()) return
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, ProxyFlowApp.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setTimeoutAfter(15_000)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(EVENT_NOTIFICATION_ID, n) }
    }
}

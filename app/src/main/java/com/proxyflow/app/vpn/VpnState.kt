package com.proxyflow.app.vpn

import com.proxyflow.app.core.ExitInfo
import com.proxyflow.app.data.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnState(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    RECONNECTING("Reconnecting"),
    ERROR("Error"),
}

data class ConnectionStatus(
    val state: ConnState = ConnState.DISCONNECTED,
    val profileId: Long = -1,
    val profileName: String = "",
    val protocol: Protocol? = null,
    val server: String = "",
    val connectedAt: Long = 0,
    val message: String? = null,
    val reconnectAttempt: Int = 0,
    val networkType: String = "",
    val localSocksPort: Int = 0,
)

data class TrafficStats(
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val uploadSpeed: Long = 0,
    val downloadSpeed: Long = 0,
)

data class Measurements(
    val exit: ExitInfo? = null,
    val exitError: String? = null,
    val urlLatencyMs: Int = -1,
    val serverRttMs: Int = -1,
    val measuredAt: Long = 0,
    val measuring: Boolean = false,
)

/** Process-wide connection state shared by the VPN service and the UI. */
object VpnState {
    private val _status = MutableStateFlow(ConnectionStatus())
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _traffic = MutableStateFlow(TrafficStats())
    val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()

    private val _measure = MutableStateFlow(Measurements())
    val measure: StateFlow<Measurements> = _measure.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun setStatus(transform: (ConnectionStatus) -> ConnectionStatus) { _status.value = transform(_status.value) }
    fun setTraffic(t: TrafficStats) { _traffic.value = t }
    fun setMeasure(transform: (Measurements) -> Measurements) { _measure.value = transform(_measure.value) }

    fun log(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val l = _logs.value
        _logs.value = (if (l.size >= 200) l.drop(l.size - 199) else l) + "$ts  $line"
    }

    fun clearLogs() { _logs.value = emptyList() }
}

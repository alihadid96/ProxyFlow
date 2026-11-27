@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.proxyflow.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.proxyflow.app.data.DnsMode
import com.proxyflow.app.data.Ipv6Mode
import com.proxyflow.app.data.PerAppMode
import com.proxyflow.app.data.Protocol
import com.proxyflow.app.ui.MainViewModel
import com.proxyflow.app.ui.components.BackTopBar
import com.proxyflow.app.ui.components.GlassCard
import com.proxyflow.app.ui.components.GradientBackground
import com.proxyflow.app.ui.components.InfoRow
import com.proxyflow.app.ui.components.SectionTitle
import com.proxyflow.app.ui.countryFlag
import com.proxyflow.app.ui.formatBytes
import com.proxyflow.app.ui.formatDuration
import com.proxyflow.app.ui.formatSpeed
import com.proxyflow.app.ui.theme.MonoStyle
import com.proxyflow.app.vpn.ConnState
import kotlinx.coroutines.delay

@Composable
fun DetailsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val status by vm.status.collectAsStateWithLifecycle()
    val traffic by vm.traffic.collectAsStateWithLifecycle()
    val measure by vm.measure.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val profile = profiles.firstOrNull { it.id == status.profileId }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    GradientBackground {
        Scaffold(containerColor = Color.Transparent, topBar = { BackTopBar("Connection details", onBack) }) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 640.dp)) {
                    SectionTitle("Session")
                    GlassCard {
                        InfoRow("Status", status.state.label, stateColor(status.state))
                        status.message?.let { InfoRow("Message", it) }
                        InfoRow("Profile", status.profileName.ifEmpty { "—" })
                        InfoRow("Protocol", status.protocol?.label ?: "—")
                        InfoRow("Server", status.server.ifEmpty { "—" })
                        InfoRow("Duration", if (status.state == ConnState.CONNECTED && status.connectedAt > 0) formatDuration(now - status.connectedAt) else "—")
                        InfoRow("Network", status.networkType.ifEmpty { "—" })
                        if (status.localSocksPort > 0) InfoRow("Local engine", "SOCKS5 127.0.0.1:${status.localSocksPort} (authenticated)")
                        if (status.reconnectAttempt > 0) InfoRow("Reconnect attempt", status.reconnectAttempt.toString())
                    }
                    SectionTitle("Traffic (from the TUN interface)")
                    GlassCard {
                        InfoRow("Download speed", formatSpeed(traffic.downloadSpeed))
                        InfoRow("Upload speed", formatSpeed(traffic.uploadSpeed))
                        if (profile?.protocol == Protocol.OPENVPN) Text("OpenVPN reports bytes on the encrypted link (includes protocol overhead).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        InfoRow("Downloaded this session", formatBytes(traffic.downloadBytes))
                        InfoRow("Uploaded this session", formatBytes(traffic.uploadBytes))
                        InfoRow("Downloaded (all time)", formatBytes(settings.lifetimeDownload + 0))
                        InfoRow("Uploaded (all time)", formatBytes(settings.lifetimeUpload + 0))
                    }
                    SectionTitle("Measurements")
                    GlassCard {
                        val exit = measure.exit
                        InfoRow("Exit IP", exit?.ip?.ifEmpty { null } ?: "—")
                        InfoRow("Location", exit?.location?.ifEmpty { null }?.let { "${countryFlag(exit.country)} $it" } ?: "—")
                        InfoRow("Network / ISP", exit?.org?.ifEmpty { null } ?: "—")
                        InfoRow("URL test latency", if (measure.urlLatencyMs >= 0) "${measure.urlLatencyMs} ms" else "—")
                        InfoRow("Server TCP RTT", if (measure.serverRttMs >= 0) "${measure.serverRttMs} ms" else "—")
                        measure.exitError?.let { InfoRow("Last error", it) }
                        Text(
                            "Latency = time to fetch http://www.gstatic.com/generate_204 through the tunnel. Exit IP comes from ipinfo.io, queried through the proxy.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    SectionTitle("Routing")
                    GlassCard {
                        if (profile?.protocol == Protocol.OPENVPN) InfoRow("DNS", "Pushed by the server (fallback ${settings.dnsIp()})")
                        else InfoRow("DNS", if (profile?.dnsMode == DnsMode.LOCAL) "Local · ${settings.dnsIp()}" else "Remote · ${settings.remoteDnsServer()}")
                        InfoRow("IPv6", when (settings.ipv6Mode) { Ipv6Mode.BLOCK -> "Blocked (no leaks)"; Ipv6Mode.PROXY -> "Proxied"; Ipv6Mode.BYPASS -> "Bypasses VPN" })
                        InfoRow("Apps", when (settings.perAppMode) {
                            PerAppMode.ALL -> "All apps"
                            PerAppMode.BYPASS_SELECTED -> "All except ${settings.selectedApps.size}"
                            PerAppMode.ONLY_SELECTED -> "Only ${settings.selectedApps.size} selected"
                        })
                        InfoRow("LAN traffic", if (settings.bypassLan) "Direct" else "Proxied")
                        InfoRow("UDP", when (profile?.protocol) {
                            Protocol.HTTP -> "Not supported by HTTP proxies (blocked)"
                            Protocol.SOCKS5 -> "SOCKS5 UDP ASSOCIATE"
                            Protocol.VLESS -> "VLESS UDP over the tunnel"
                            Protocol.OPENVPN -> "Carried natively by OpenVPN"
                            null -> "—"
                        })
                        InfoRow("Kill switch", if (settings.killSwitch) "On (tunnel kept during reconnect)" else "Off")
                    }
                    SectionTitle("Engine log")
                    GlassCard {
                        val text = if (logs.isEmpty()) "No log entries yet." else logs.takeLast(80).joinToString("\n")
                        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            Text(text, style = MonoStyle, modifier = Modifier.horizontalScroll(rememberScrollState()))
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

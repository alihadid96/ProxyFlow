@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.proxyflow.app.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.proxyflow.app.core.AddressUtil
import com.proxyflow.app.data.DNS_PRESETS
import com.proxyflow.app.data.Ipv6Mode
import com.proxyflow.app.data.PerAppMode
import com.proxyflow.app.data.ThemeMode
import com.proxyflow.app.ui.MainViewModel
import com.proxyflow.app.ui.components.AppTextField
import com.proxyflow.app.ui.components.BackTopBar
import com.proxyflow.app.ui.components.DropdownField
import com.proxyflow.app.ui.components.GlassCard
import com.proxyflow.app.ui.components.GradientBackground
import com.proxyflow.app.ui.components.NavRow
import com.proxyflow.app.ui.components.SectionTitle
import com.proxyflow.app.ui.components.SegmentedChoice
import com.proxyflow.app.ui.components.SwitchRow
import com.proxyflow.app.ui.components.WarningBox
import com.proxyflow.app.ui.formatBytes
import com.proxyflow.app.vpn.ConnState

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onPickApps: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }
    var customDns by remember(s.customDns) { mutableStateOf(s.customDns) }
    var timeout by remember(s.connectTimeoutSec) { mutableFloatStateOf(s.connectTimeoutSec.toFloat()) }
    val connected = status.state != ConnState.DISCONNECTED && status.state != ConnState.ERROR
    val pm = context.getSystemService(PowerManager::class.java)
    val ignoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)

    GradientBackground {
        Scaffold(containerColor = Color.Transparent, topBar = { BackTopBar("Settings", onBack) }) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 640.dp)) {
                    if (connected) WarningBox("Routing and DNS changes apply the next time you connect.")

                    SectionTitle("Connection")
                    GlassCard {
                        SwitchRow("Auto-connect on app launch", s.autoConnectOnLaunch, { v -> vm.settingsAction { it.setAutoConnectOnLaunch(v) } }, icon = Icons.Filled.PlayArrow)
                        SwitchRow("Auto-connect when the device starts", s.autoConnectOnBoot, { v -> vm.settingsAction { it.setAutoConnectOnBoot(v) } },
                            subtitle = "Requires VPN permission granted once", icon = Icons.Filled.PowerSettingsNew)
                        SwitchRow("Auto-reconnect", s.autoReconnect, { v -> vm.settingsAction { it.setAutoReconnect(v) } },
                            subtitle = "Restart the engine after network changes or connection loss", icon = Icons.Filled.Refresh)
                        Text("Connection timeout: ${timeout.toInt()} s", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                        Slider(value = timeout, onValueChange = { timeout = it }, valueRange = 3f..60f, steps = 56,
                            onValueChangeFinished = { vm.settingsAction { it.setConnectTimeout(timeout.toInt()) } })
                    }

                    SectionTitle("Kill switch")
                    GlassCard {
                        SwitchRow("Block traffic while reconnecting", s.killSwitch, { v -> vm.settingsAction { it.setKillSwitch(v) } },
                            subtitle = "Keeps the VPN interface up (so nothing leaks) while the proxy is unreachable", icon = Icons.Filled.Shield)
                        NavRow("Always-on VPN & block without VPN", "Android's system kill switch — enable both for ProxyFlow in system settings",
                            Icons.Filled.PhonelinkLock, onClick = {
                                runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                            })
                    }

                    SectionTitle("DNS")
                    GlassCard {
                        DropdownField("DNS server", DNS_PRESETS.map { it.id }, s.dnsPreset, { id -> DNS_PRESETS.first { it.id == id }.label },
                            { v -> vm.settingsAction { it.setDnsPreset(v) } })
                        if (s.dnsPreset == "custom") {
                            AppTextField(customDns, { customDns = it.trim() }, "Custom DNS (IPv4)", leading = Icons.Filled.Dns,
                                isError = customDns.isNotEmpty() && !AddressUtil.isIpv4(customDns),
                                supporting = "Used over TCP through the proxy in remote-DNS mode")
                            TextButton(onClick = { vm.settingsAction { it.setCustomDns(customDns) } }, enabled = AddressUtil.isIpv4(customDns)) { Text("Save DNS") }
                        }
                        Text("All DNS queries from apps are captured by the VPN. With remote DNS they are resolved through the proxy using DNS-over-HTTPS / TCP, preventing DNS leaks.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    SectionTitle("IPv6")
                    GlassCard {
                        SegmentedChoice(Ipv6Mode.entries.toList(), s.ipv6Mode, { when (it) { Ipv6Mode.BLOCK -> "Block"; Ipv6Mode.PROXY -> "Proxy"; Ipv6Mode.BYPASS -> "Bypass" } },
                            { v -> vm.settingsAction { it.setIpv6Mode(v) } })
                        Text(when (s.ipv6Mode) {
                            Ipv6Mode.BLOCK -> "IPv6 is captured and dropped, apps use IPv4 — recommended, no leaks."
                            Ipv6Mode.PROXY -> "IPv6 destinations are sent through the proxy (the server must support IPv6)."
                            Ipv6Mode.BYPASS -> "IPv6 traffic does not enter the VPN and may leak your real address."
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    SectionTitle("Per-app routing")
                    GlassCard {
                        SegmentedChoice(PerAppMode.entries.toList(), s.perAppMode,
                            { when (it) { PerAppMode.ALL -> "All apps"; PerAppMode.BYPASS_SELECTED -> "Bypass"; PerAppMode.ONLY_SELECTED -> "Only" } },
                            { v -> vm.settingsAction { it.setPerAppMode(v) } })
                        Text(when (s.perAppMode) {
                            PerAppMode.ALL -> "Route all applications through the proxy."
                            PerAppMode.BYPASS_SELECTED -> "Selected apps bypass the proxy; everything else is proxied."
                            PerAppMode.ONLY_SELECTED -> "Only selected apps use the proxy."
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s.perAppMode != PerAppMode.ALL) {
                            NavRow("Choose apps", "${s.selectedApps.size} selected", Icons.Filled.Apps, onPickApps)
                        }
                        SwitchRow("Bypass local network (LAN)", s.bypassLan, { v -> vm.settingsAction { it.setBypassLan(v) } },
                            subtitle = "Printers, routers and local devices are reached directly", icon = Icons.Filled.Home)
                    }

                    SectionTitle("Appearance")
                    GlassCard {
                        SegmentedChoice(ThemeMode.entries.toList(), s.theme, { when (it) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" } },
                            { v -> vm.settingsAction { it.setTheme(v) } })
                    }

                    SectionTitle("Notifications & privacy")
                    GlassCard {
                        SwitchRow("Connection notifications", s.connectionNotifications, { v -> vm.settingsAction { it.setConnectionNotifications(v) } },
                            subtitle = "Alerts when connected, disconnected or on errors", icon = Icons.Filled.Notifications)
                        SwitchRow("Look up exit IP & location", s.lookupExitIp, { v -> vm.settingsAction { it.setLookupExitIp(v) } },
                            subtitle = "Queries ipinfo.io through the proxy after connecting. No credentials are ever sent.", icon = Icons.Filled.Public)
                        NavRow(
                            if (ignoringBattery) "Battery optimisation disabled" else "Disable battery optimisation",
                            "Prevents Android from stopping the VPN in the background",
                            Icons.Filled.BatteryChargingFull,
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        if (ignoringBattery) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                                    )
                                }
                            },
                        )
                    }

                    SectionTitle("Statistics")
                    GlassCard {
                        NavRow("Reset traffic statistics",
                            "All time: ↓ ${formatBytes(s.lifetimeDownload)} · ↑ ${formatBytes(s.lifetimeUpload)}",
                            Icons.Filled.DeleteSweep, onClick = { confirmReset = true })
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset traffic statistics?") },
            text = { Text("All-time download and upload totals will be set to zero.") },
            confirmButton = { TextButton(onClick = { vm.settingsAction { it.resetTrafficStats() }; confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.proxyflow.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.proxyflow.app.R
import com.proxyflow.app.ui.MainViewModel
import com.proxyflow.app.ui.components.GlassCard
import com.proxyflow.app.ui.components.GradientBackground
import com.proxyflow.app.ui.components.IconCircle
import com.proxyflow.app.ui.components.ProtocolBadge
import com.proxyflow.app.ui.countryFlag
import com.proxyflow.app.ui.formatBytes
import com.proxyflow.app.ui.formatDuration
import com.proxyflow.app.ui.formatSpeed
import com.proxyflow.app.ui.theme.Cyan
import com.proxyflow.app.ui.theme.DeepBlue
import com.proxyflow.app.ui.theme.ErrorRed
import com.proxyflow.app.ui.theme.Success
import com.proxyflow.app.ui.theme.Warning
import com.proxyflow.app.vpn.ConnState
import kotlinx.coroutines.delay

fun stateColor(s: ConnState): Color = when (s) {
    ConnState.CONNECTED -> Success
    ConnState.CONNECTING, ConnState.RECONNECTING -> Warning
    ConnState.ERROR -> ErrorRed
    ConnState.DISCONNECTED -> Color(0xFF64748B)
}

@Composable
fun DashboardScreen(
    vm: MainViewModel,
    onConnect: () -> Unit,
    onProfiles: () -> Unit,
    onSettings: () -> Unit,
    onDetails: () -> Unit,
    onAbout: () -> Unit,
) {
    val status by vm.status.collectAsStateWithLifecycle()
    val traffic by vm.traffic.collectAsStateWithLifecycle()
    val measure by vm.measure.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val selected = profiles.firstOrNull { it.id == settings.selectedProfileId } ?: profiles.firstOrNull()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.state) {
        while (status.state == ConnState.CONNECTED || status.state == ConnState.RECONNECTING) {
            now = System.currentTimeMillis(); delay(1000)
        }
    }

    val busy = status.state == ConnState.CONNECTING || status.state == ConnState.RECONNECTING
    val on = status.state == ConnState.CONNECTED || busy

    GradientBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_logo), null, Modifier.size(44.dp))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text("ProxyFlow", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text("Rootless proxy agent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onAbout) { Icon(Icons.Filled.Info, "About") }
                IconButton(onClick = onProfiles) { Icon(Icons.Filled.List, "Profiles") }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
            }

            Spacer(Modifier.height(18.dp))
            ConnectButton(state = status.state, onClick = {
                if (on) vm.disconnect() else onConnect()
            })
            Spacer(Modifier.height(14.dp))

            val color by animateColorAsState(stateColor(status.state), label = "stateColor")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(status.state.label, style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
            }
            val sub = when {
                status.state == ConnState.CONNECTED && status.connectedAt > 0 -> formatDuration(now - status.connectedAt)
                status.message != null -> status.message!!
                status.state == ConnState.DISCONNECTED -> "Tap to connect"
                else -> ""
            }
            Text(
                sub, style = if (status.state == ConnState.CONNECTED) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
                color = if (status.state == ConnState.ERROR) ErrorRed else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp).widthIn(max = 420.dp),
            )

            Spacer(Modifier.height(20.dp))

            // Selected profile
            GlassCard(onClick = onProfiles, modifier = Modifier.widthIn(max = 560.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(Icons.Filled.Dns, DeepBlue, size = 42)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Current profile", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (selected != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(selected.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(8.dp))
                                ProtocolBadge(selected.protocol)
                            }
                            Text(selected.displayServer(), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            Text("No profile yet — tap to add one", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }

            Spacer(Modifier.height(12.dp))
            // Traffic grid
            Column(Modifier.widthIn(max = 560.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(Modifier.weight(1f), Icons.Filled.ArrowDownward, "Download", formatSpeed(traffic.downloadSpeed), Success, on)
                    StatTile(Modifier.weight(1f), Icons.Filled.ArrowUpward, "Upload", formatSpeed(traffic.uploadSpeed), Cyan, on)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(Modifier.weight(1f), Icons.Filled.CloudDownload, "Downloaded", formatBytes(traffic.downloadBytes), DeepBlue, on)
                    StatTile(Modifier.weight(1f), Icons.Filled.CloudUpload, "Uploaded", formatBytes(traffic.uploadBytes), Color(0xFF8B5CF6), on)
                }
            }

            Spacer(Modifier.height(12.dp))
            // Exit info
            GlassCard(modifier = Modifier.widthIn(max = 560.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (measure.measuring) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { vm.refreshMeasurements() }, enabled = status.state == ConnState.CONNECTED) {
                        Icon(Icons.Filled.Refresh, "Refresh")
                    }
                }
                val connected = status.state == ConnState.CONNECTED
                val exit = measure.exit
                MetricLine(Icons.Filled.Language, "Exit IP",
                    when { !connected -> "—"; exit != null && exit.ip.isNotEmpty() -> exit.ip; !settings.lookupExitIp -> "Lookup disabled"; else -> measure.exitError ?: "Checking…" })
                MetricLine(Icons.Filled.Place, "Location",
                    if (connected && exit != null && exit.location.isNotEmpty()) "${countryFlag(exit.country)} ${exit.location}".trim() else "—")
                MetricLine(Icons.Filled.Speed, "Latency (URL test)",
                    if (connected && measure.urlLatencyMs >= 0) "${measure.urlLatencyMs} ms" else "—")
                MetricLine(Icons.Filled.Dns, "Server RTT (TCP)",
                    if (connected && measure.serverRttMs >= 0) "${measure.serverRttMs} ms" else "—")
                TextButton(onClick = onDetails, modifier = Modifier.align(Alignment.End)) {
                    Text("Connection details")
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MetricLine(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun StatTile(modifier: Modifier, icon: ImageVector, label: String, value: String, tint: Color, active: Boolean) {
    GlassCard(modifier = modifier, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        IconCircle(icon, tint, size = 32)
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.alpha(if (active) 1f else 0.5f), maxLines = 1)
    }
}

@Composable
private fun ConnectButton(state: ConnState, onClick: () -> Unit) {
    val busy = state == ConnState.CONNECTING || state == ConnState.RECONNECTING
    val connected = state == ConnState.CONNECTED
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (busy) 1200 else 2400, easing = LinearEasing), RepeatMode.Restart), label = "p",
    )
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "s")
    val pressScale by animateFloatAsState(if (connected) 1f else 0.96f, tween(400, easing = FastOutSlowInEasing), label = "scale")

    val colors = when (state) {
        ConnState.CONNECTED -> listOf(Color(0xFF10B981), Color(0xFF06B6D4))
        ConnState.CONNECTING, ConnState.RECONNECTING -> listOf(Color(0xFFF59E0B), Color(0xFF2563EB))
        ConnState.ERROR -> listOf(Color(0xFFEF4444), Color(0xFF7C3AED))
        ConnState.DISCONNECTED -> listOf(DeepBlue, Cyan)
    }
    val ringColor = colors.first()

    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        if (connected || busy) {
            Canvas(Modifier.fillMaxSize()) {
                for (i in 0..1) {
                    val f = (pulse + i * 0.5f) % 1f
                    drawCircle(ringColor.copy(alpha = (1f - f) * 0.35f), radius = size.minDimension / 2 * (0.62f + 0.38f * f))
                }
                if (busy) {
                    drawArc(
                        color = ringColor, startAngle = spin, sweepAngle = 90f, useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.17f, size.height * 0.17f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.66f, size.height * 0.66f),
                        style = Stroke(width = 6f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
            }
        }
        Box(
            Modifier.size(150.dp).scale(pressScale)
                .shadow(24.dp, CircleShape, ambientColor = ringColor, spotColor = ringColor)
                .background(Brush.linearGradient(colors), CircleShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = androidx.compose.material3.ripple(), onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.PowerSettingsNew, contentDescription = if (connected) "Disconnect" else "Connect",
                    tint = Color.White, modifier = Modifier.size(56.dp))
                Text(
                    when { connected -> "DISCONNECT"; busy -> "CANCEL"; else -> "CONNECT" },
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                )
            }
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    AnimatedVisibility(true) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
    }
}

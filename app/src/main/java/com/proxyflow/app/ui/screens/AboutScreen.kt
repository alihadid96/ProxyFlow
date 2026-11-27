@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.proxyflow.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.proxyflow.app.BuildConfig
import com.proxyflow.app.R
import com.proxyflow.app.core.XrayProcess
import com.proxyflow.app.ui.components.BackTopBar
import com.proxyflow.app.ui.components.GlassCard
import com.proxyflow.app.ui.components.GradientBackground
import com.proxyflow.app.ui.components.InfoRow
import com.proxyflow.app.ui.components.SectionTitle
import com.proxyflow.app.vpn.TProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var xrayVersion by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) { xrayVersion = withContext(Dispatchers.IO) { XrayProcess.version(context) } }
    GradientBackground {
        Scaffold(containerColor = Color.Transparent, topBar = { BackTopBar("About", onBack) }) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(painterResource(R.drawable.ic_logo), null, Modifier.size(112.dp))
                Text("ProxyFlow", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Version ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.widthIn(max = 640.dp)) {
                    SectionTitle("Engine")
                    GlassCard {
                        InfoRow("Xray-core", xrayVersion.ifEmpty { BuildConfig.XRAY_VERSION })
                        InfoRow("hev-socks5-tunnel", BuildConfig.HEV_VERSION + if (TProxyService.loadError == null) "" else " (failed to load)")
                        InfoRow("Device ABI", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
                        InfoRow("Android", "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                    }
                    SectionTitle("How it works")
                    GlassCard {
                        Text(
                            "ProxyFlow uses Android's VpnService to create a TUN interface. hev-socks5-tunnel turns the captured IP packets " +
                                "back into TCP/UDP connections and hands them to a local, password-protected Xray-core instance, which forwards them " +
                                "to your HTTP (CONNECT), SOCKS5 or VLESS server. No root is required. ProxyFlow itself is excluded from the VPN so " +
                                "its upstream connections never loop.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    SectionTitle("Protocol limitations")
                    GlassCard {
                        Text("• HTTP proxies carry TCP only. UDP (QUIC, voice, games) is blocked so apps fall back to TCP.\n" +
                            "• SOCKS5 UDP needs a server that supports UDP ASSOCIATE.\n" +
                            "• VLESS HTTP/2 transport was removed from Xray-core — use XHTTP.\n" +
                            "• \"Allow insecure\" TLS is no longer supported by Xray-core; use certificate pinning.\n" +
                            "• Android only lets one VPN run at a time.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    SectionTitle("Privacy")
                    GlassCard {
                        Text("No analytics, tracking or cloud sync. Profiles stay on this device; usernames, passwords and UUIDs are encrypted " +
                            "with an Android Keystore key and never logged. The only network request ProxyFlow makes on its own is the optional exit-IP " +
                            "lookup (ipinfo.io) and the latency probe (gstatic.com/generate_204), both sent through your proxy.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    SectionTitle("Open-source licenses")
                    GlassCard {
                        InfoRow("Xray-core", "MPL-2.0")
                        InfoRow("hev-socks5-tunnel", "MIT")
                        InfoRow("lwIP", "BSD-3-Clause")
                        InfoRow("ZXing / zxing-android-embedded", "Apache-2.0")
                        InfoRow("AndroidX, Jetpack Compose", "Apache-2.0")
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

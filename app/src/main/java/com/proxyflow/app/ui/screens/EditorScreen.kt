@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.proxyflow.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.proxyflow.app.core.LinkCodec
import com.proxyflow.app.core.ProfileValidator
import com.proxyflow.app.data.DnsMode
import com.proxyflow.app.data.FINGERPRINTS
import com.proxyflow.app.data.FLOWS
import com.proxyflow.app.data.Protocol
import com.proxyflow.app.data.ProxyProfile
import com.proxyflow.app.data.Security
import com.proxyflow.app.data.Transport
import com.proxyflow.app.data.XHTTP_MODES
import com.proxyflow.app.ui.MainViewModel
import com.proxyflow.app.ui.components.AppTextField
import com.proxyflow.app.ui.components.BackTopBar
import com.proxyflow.app.ui.components.DropdownField
import com.proxyflow.app.ui.components.GlassCard
import com.proxyflow.app.ui.components.GradientBackground
import com.proxyflow.app.ui.components.SectionTitle
import com.proxyflow.app.ui.components.SegmentedChoice
import com.proxyflow.app.ui.components.SwitchRow
import com.proxyflow.app.ui.components.WarningBox
import com.proxyflow.app.ui.theme.ErrorRed

fun qrScanOptions(): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt("Point the camera at a vless:// / socks5:// / http:// QR code")
    .setBeepEnabled(false)
    .setOrientationLocked(false)

@Composable
fun EditorScreen(vm: MainViewModel, profileId: Long?, onDone: () -> Unit) {
    val context = LocalContext.current
    var p by remember { mutableStateOf(ProxyProfile(name = "", protocol = Protocol.HTTP, port = 8080)) }
    var loaded by remember { mutableStateOf(profileId == null) }
    var portText by remember { mutableStateOf("8080") }
    var timeoutText by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var importMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profileId) {
        if (profileId != null) {
            vm.load(profileId)?.let {
                p = it; portText = it.port.toString(); timeoutText = if (it.timeoutSec > 0) it.timeoutSec.toString() else ""
            }
            loaded = true
        }
    }

    fun applyLink(text: String?) {
        if (text.isNullOrBlank()) { importMsg = "Nothing found"; return }
        try {
            val parsed = LinkCodec.parse(text.trim())
            p = parsed.copy(id = p.id, name = if (p.name.isNotBlank() && profileId != null) p.name else parsed.name)
            portText = parsed.port.toString()
            timeoutText = ""
            importMsg = "Fields filled from link"
        } catch (e: IllegalArgumentException) {
            importMsg = e.message
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> if (r.contents != null) applyLink(r.contents) }

    GradientBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { BackTopBar(if (profileId == null) "Add proxy" else "Edit proxy", onDone) },
        ) { pad ->
            if (!loaded) return@Scaffold
            Column(
                Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 640.dp)) {
                    // Quick import
                    GlassCard {
                        Text("Quick fill", style = MaterialTheme.typography.titleMedium)
                        Text("Paste or scan a vless://, socks5:// or http:// link to fill every field automatically.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                applyLink(cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
                            }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("Clipboard") }
                            OutlinedButton(onClick = { scanner.launch(qrScanOptions()) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text("Scan QR")
                            }
                        }
                        importMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
                    }

                    SectionTitle("Protocol")
                    SegmentedChoice(Protocol.entries.toList(), p.protocol, { it.label }, { proto ->
                        p = p.copy(protocol = proto)
                        if (portText.isBlank() || portText == "8080" || portText == "1080" || portText == "443") {
                            portText = when (proto) { Protocol.HTTP -> "8080"; Protocol.SOCKS5 -> "1080"; Protocol.VLESS -> "443" }
                        }
                    })

                    SectionTitle("Server")
                    GlassCard {
                        AppTextField(p.name, { p = p.copy(name = it) }, "Profile name", leading = Icons.Filled.Label, placeholder = "My proxy")
                        AppTextField(p.host, { p = p.copy(host = it.trim()) }, "Server address / host", leading = Icons.Filled.Dns,
                            placeholder = "proxy.example.com or 203.0.113.10", keyboardType = KeyboardType.Uri)
                        AppTextField(portText, { portText = it.filter(Char::isDigit).take(5) }, "Port", leading = Icons.Filled.Tag,
                            keyboardType = KeyboardType.Number)
                    }

                    when (p.protocol) {
                        Protocol.HTTP, Protocol.SOCKS5 -> HttpSocksFields(p, timeoutText, { timeoutText = it }) { p = it }
                        Protocol.VLESS -> VlessFields(p) { p = it }
                    }

                    if (errors.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        WarningBox(errors.joinToString("\n• ", prefix = "• "), color = ErrorRed)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val candidate = p.copy(
                                port = portText.toIntOrNull() ?: 0,
                                timeoutSec = timeoutText.toIntOrNull() ?: 0,
                                name = p.name.ifBlank { "${p.protocol.label} ${p.host}" },
                            )
                            val problems = ProfileValidator.validate(candidate)
                            errors = problems
                            if (problems.isEmpty()) vm.save(candidate) { onDone() }
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Icon(Icons.Filled.Save, null); Spacer(Modifier.width(8.dp)); Text("Save profile") }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

@Composable
private fun HttpSocksFields(p: ProxyProfile, timeoutText: String, onTimeout: (String) -> Unit, update: (ProxyProfile) -> Unit) {
    SectionTitle("Authentication")
    GlassCard {
        SegmentedChoice(listOf(false, true), p.authEnabled, { if (it) "Username & password" else "None" }, { update(p.copy(authEnabled = it)) })
        if (p.authEnabled) {
            AppTextField(p.username, { update(p.copy(username = it)) }, "Username", leading = Icons.Filled.Person)
            AppTextField(p.password, { update(p.copy(password = it)) }, "Password", leading = Icons.Filled.Lock, password = true)
            Text("Credentials are encrypted with a key stored in the Android Keystore.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    SectionTitle("DNS & connection")
    GlassCard {
        Text("DNS resolution", style = MaterialTheme.typography.labelLarge)
        SegmentedChoice(DnsMode.entries.toList(), p.dnsMode, { if (it == DnsMode.REMOTE) "Remote (via proxy)" else "Local (device)" }, { update(p.copy(dnsMode = it)) })
        Text(
            if (p.dnsMode == DnsMode.REMOTE) "Host names are sent to the proxy and DNS queries travel through it — no DNS leaks."
            else "DNS is resolved directly from this device; the proxy only sees IP addresses.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppTextField(timeoutText, { onTimeout(it.filter(Char::isDigit).take(3)) }, "Connection timeout (seconds, optional)",
            keyboardType = KeyboardType.Number, placeholder = "Use global setting")
        if (p.protocol == Protocol.HTTP) {
            WarningBox("HTTP proxies only tunnel TCP (CONNECT). UDP traffic such as QUIC or games is blocked so apps fall back to TCP; DNS is carried over TCP/DoH through the proxy.")
        } else {
            Text("UDP is relayed with SOCKS5 UDP ASSOCIATE when the server supports it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun VlessFields(p: ProxyProfile, update: (ProxyProfile) -> Unit) {
    SectionTitle("VLESS")
    GlassCard {
        AppTextField(p.uuid, { update(p.copy(uuid = it.trim())) }, "UUID", leading = Icons.Filled.Key, password = true,
            placeholder = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
        AppTextField(p.encryption, { update(p.copy(encryption = it.trim())) }, "Encryption", supporting = "Usually \"none\"")
        DropdownField("Flow", FLOWS, p.flow, { it.ifEmpty { "none" } }, { update(p.copy(flow = it)) })
    }
    SectionTitle("Transport")
    GlassCard {
        DropdownField("Transport", Transport.all, p.transport, Transport::label, { update(p.copy(transport = it)) })
        when (p.transport) {
            Transport.WS, Transport.HTTPUPGRADE, Transport.XHTTP -> {
                AppTextField(p.path, { update(p.copy(path = it)) }, if (p.transport == Transport.WS) "WebSocket path" else "Path", placeholder = "/")
                AppTextField(p.hostHeader, { update(p.copy(hostHeader = it.trim())) }, "Host header (optional)")
                if (p.transport == Transport.XHTTP) DropdownField("XHTTP mode", XHTTP_MODES, p.xhttpMode, { it }, { update(p.copy(xhttpMode = it)) })
            }
            Transport.GRPC -> {
                AppTextField(p.serviceName, { update(p.copy(serviceName = it.trim())) }, "gRPC service name")
                AppTextField(p.hostHeader, { update(p.copy(hostHeader = it.trim())) }, "Authority (optional)")
                SwitchRow("Multi mode", p.grpcMultiMode, { update(p.copy(grpcMultiMode = it)) })
            }
        }
        Text("HTTP/2 transport was removed from Xray-core; XHTTP is its replacement (runs over HTTP/2 or HTTP/3).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionTitle("Security")
    GlassCard {
        SegmentedChoice(Security.all, p.security, { it.replaceFirstChar(Char::uppercase).replace("Tls", "TLS").replace("Reality", "REALITY") },
            { update(p.copy(security = it)) })
        if (p.security != Security.NONE) {
            AppTextField(p.sni, { update(p.copy(sni = it.trim())) }, "Server name (SNI)", placeholder = if (p.security == Security.TLS) p.host else "www.example.com")
            DropdownField("Fingerprint", FINGERPRINTS, p.fingerprint.ifEmpty { "chrome" }, { it }, { update(p.copy(fingerprint = it)) })
        }
        if (p.security == Security.TLS) {
            AppTextField(p.alpn, { update(p.copy(alpn = it.trim())) }, "ALPN (optional)", placeholder = "h2,http/1.1")
            SwitchRow("Allow insecure", p.allowInsecure, { update(p.copy(allowInsecure = it)) },
                subtitle = "Skip certificate verification")
            if (p.allowInsecure) {
                WarningBox("Xray-core no longer supports disabling certificate verification, so this switch has no effect and the certificate is still verified. " +
                    "For self-signed servers, enter the certificate's SHA-256 below (certificate pinning), which is secure.", color = ErrorRed)
            }
            AppTextField(p.pinnedCertSha256, { update(p.copy(pinnedCertSha256 = it.trim())) }, "Pinned certificate SHA-256 (optional)")
        }
        if (p.security == Security.REALITY) {
            AppTextField(p.realityPublicKey, { update(p.copy(realityPublicKey = it.trim())) }, "REALITY public key")
            AppTextField(p.realityShortId, { update(p.copy(realityShortId = it.trim())) }, "REALITY short ID")
            AppTextField(p.spiderX, { update(p.copy(spiderX = it)) }, "SpiderX (optional)", placeholder = "/")
        }
        if (p.security == Security.NONE) {
            WarningBox("Without TLS or REALITY the connection to the server is not encrypted.")
        }
    }
    SectionTitle("DNS")
    GlassCard {
        SegmentedChoice(DnsMode.entries.toList(), p.dnsMode, { if (it == DnsMode.REMOTE) "Remote (via proxy)" else "Local (device)" }, { update(p.copy(dnsMode = it)) })
    }
}

@Suppress("unused")
private val keyboardNumber = KeyboardOptions(keyboardType = KeyboardType.Number)

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.proxyflow.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.proxyflow.app.core.ImportResult
import com.proxyflow.app.core.LinkCodec
import com.proxyflow.app.core.QrCodes
import com.proxyflow.app.ui.MainViewModel
import com.proxyflow.app.ui.components.AppTextField
import com.proxyflow.app.ui.components.BackTopBar
import com.proxyflow.app.ui.components.GlassCard
import com.proxyflow.app.ui.components.GradientBackground
import com.proxyflow.app.ui.components.InfoRow
import com.proxyflow.app.ui.components.ProtocolBadge
import com.proxyflow.app.ui.components.SectionTitle
import com.proxyflow.app.ui.components.WarningBox
import com.proxyflow.app.ui.theme.ErrorRed
import com.proxyflow.app.ui.theme.Success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImportScreen(vm: MainViewModel, initialText: String?, onBack: () -> Unit, onDone: () -> Unit, onOpenOvpn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf(initialText.orEmpty()) }
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val preview = remember(text) { if (text.isBlank()) null else LinkCodec.parseMany(text) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { r ->
        if (r.contents != null) text = (text.trim() + "\n" + r.contents).trim()
    }
    val ovpnPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { com.proxyflow.app.ui.readTextDocument(context, uri) }
            .onSuccess { (n, t) -> vm.setPendingOvpn(n, t); onOpenOvpn() }
            .onFailure { info = it.message }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val decoded = withContext(Dispatchers.Default) { runCatching { QrCodes.decode(context, uri) }.getOrNull() }
            if (decoded != null) text = (text.trim() + "\n" + decoded).trim() else info = "No QR code found in that image"
        }
    }

    GradientBackground {
        Scaffold(containerColor = Color.Transparent, topBar = { BackTopBar("Import configuration", onBack) }) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 640.dp)) {
                    GlassCard {
                        Text("OpenVPN", style = MaterialTheme.typography.titleMedium)
                        Text("Import an .ovpn configuration file from your VPN provider or server.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { ovpnPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Open .ovpn file")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    GlassCard {
                        Text("Proxy links", style = MaterialTheme.typography.titleMedium)
                        Text("Supported: vless://, socks5://, socks://, http:// links — one per line — or a base64 subscription blob.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                if (clip.isNullOrBlank()) info = "Clipboard is empty" else text = (text.trim() + "\n" + clip).trim()
                            }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.ContentPaste, null) }
                            OutlinedButton(onClick = { scanner.launch(qrScanOptions()) }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.QrCodeScanner, null) }
                            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Image, null) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            Text("Clipboard", style = MaterialTheme.typography.labelSmall)
                            Text("Camera QR", style = MaterialTheme.typography.labelSmall)
                            Text("QR image", style = MaterialTheme.typography.labelSmall)
                        }
                        AppTextField(text, { text = it; result = null }, "Links", singleLine = false,
                            modifier = Modifier.heightIn(min = 140.dp), placeholder = "vless://…")
                        info?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                    }

                    preview?.let { pr ->
                        SectionTitle("Preview (validated)")
                        GlassCard {
                            if (pr.profiles.isEmpty()) Text("No valid profiles found", color = ErrorRed)
                            pr.profiles.forEach { p ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                    ProtocolBadge(p.protocol)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(p.displayServer(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (p.allowInsecure) WarningBox("${p.name}: link requests insecure TLS. Xray-core always verifies certificates; use certificate pinning for self-signed servers.")
                            }
                            pr.errors.forEach { InfoRow("Rejected", it, ErrorRed) }
                        }
                    }

                    result?.let { r ->
                        Spacer(Modifier.height(8.dp))
                        Text("Imported ${r.profiles.size} profile(s)", color = Success, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val r = vm.importText(text)
                            result = r
                            if (r.profiles.isNotEmpty() && r.errors.isEmpty()) {
                                vm.toast("Imported ${r.profiles.size} profile(s)"); onDone()
                            }
                        },
                        enabled = (preview?.profiles?.isNotEmpty() == true) && result == null,
                        modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                    ) { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(8.dp)); Text("Import ${preview?.profiles?.size ?: 0} profile(s)") }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

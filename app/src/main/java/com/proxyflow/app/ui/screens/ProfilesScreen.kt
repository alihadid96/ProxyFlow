@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.proxyflow.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.proxyflow.app.core.LinkCodec
import com.proxyflow.app.core.QrCodes
import com.proxyflow.app.data.Protocol
import com.proxyflow.app.data.ProxyProfile
import com.proxyflow.app.ui.MainViewModel
import com.proxyflow.app.ui.components.AppTextField
import com.proxyflow.app.ui.components.BackTopBar
import com.proxyflow.app.ui.components.GlassCard
import com.proxyflow.app.ui.components.GradientBackground
import com.proxyflow.app.ui.components.ProtocolBadge
import com.proxyflow.app.ui.components.WarningBox
import com.proxyflow.app.ui.theme.ErrorRed
import com.proxyflow.app.ui.theme.Success
import com.proxyflow.app.ui.theme.Warning
import com.proxyflow.app.vpn.ConnState

@Composable
fun ProfilesScreen(vm: MainViewModel, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (Long) -> Unit, onImport: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ProxyProfile?>(null) }
    var deleting by remember { mutableStateOf<ProxyProfile?>(null) }
    var exporting by remember { mutableStateOf<ProxyProfile?>(null) }

    val filtered = profiles.filter {
        query.isBlank() || it.name.contains(query, true) || it.host.contains(query, true) || it.protocol.label.contains(query, true)
    }
    val selectedId = settings.selectedProfileId.takeIf { id -> profiles.any { it.id == id } } ?: profiles.firstOrNull()?.id

    GradientBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                BackTopBar("Proxy profiles", onBack) {
                    IconButton(onClick = onImport) { Icon(Icons.Filled.Download, "Import") }
                    IconButton(onClick = { vm.testAll() }, enabled = profiles.isNotEmpty()) { Icon(Icons.Filled.NetworkCheck, "Test all") }
                }
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add proxy") })
            },
        ) { pad ->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    AppTextField(query, { query = it }, "Search profiles", leading = Icons.Filled.Search)
                }
                if (filtered.isEmpty()) {
                    item { EmptyHint(if (profiles.isEmpty()) "No profiles yet.\nAdd one manually or import a vless:// / socks5:// / http:// link." else "No profile matches \"$query\"") }
                }
                items(filtered, key = { it.id }) { p ->
                    ProfileCard(
                        p = p,
                        selected = p.id == selectedId,
                        active = status.profileId == p.id && status.state == ConnState.CONNECTED,
                        testing = p.id in testing,
                        onSelect = { vm.select(p.id) },
                        onEdit = { onEdit(p.id) },
                        onTest = { vm.test(p) },
                        onRename = { renaming = p },
                        onDuplicate = { vm.duplicate(p.id) },
                        onExport = { exporting = p },
                        onDelete = { deleting = p },
                    )
                }
            }
        }
    }

    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename profile") },
            text = { AppTextField(name, { name = it }, "Profile name") },
            confirmButton = { TextButton(onClick = { vm.rename(p.id, name); renaming = null }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("The profile and its encrypted credentials will be removed from this device.") },
            confirmButton = { TextButton(onClick = { vm.delete(p.id); deleting = null }) { Text("Delete", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    exporting?.let { p -> ExportDialog(p) { exporting = null } }
}

@Composable
private fun ProfileCard(
    p: ProxyProfile, selected: Boolean, active: Boolean, testing: Boolean,
    onSelect: () -> Unit, onEdit: () -> Unit, onTest: () -> Unit, onRename: () -> Unit,
    onDuplicate: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    GlassCard(onClick = onSelect, padding = PaddingValues(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    ProtocolBadge(p.protocol)
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.background(Success.copy(alpha = 0.15f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text("ACTIVE", color = Success, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                val detail = buildString {
                    append(p.displayServer())
                    if (p.protocol == Protocol.VLESS) append(" · ${p.transport.uppercase()} · ${p.security.uppercase()}")
                    else if (p.hasAuth) append(" · auth")
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        testing -> { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)); Text("Testing…", style = MaterialTheme.typography.labelMedium) }
                        p.lastTestAt == 0L -> Text("Not tested", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        p.lastTestError != null -> Text(p.lastTestError, style = MaterialTheme.typography.labelMedium, color = ErrorRed, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        else -> {
                            val c = when { p.lastLatencyMs < 300 -> Success; p.lastLatencyMs < 800 -> Warning; else -> ErrorRed }
                            Icon(Icons.Filled.Speed, null, Modifier.size(14.dp), tint = c)
                            Spacer(Modifier.width(4.dp))
                            Text("${p.lastLatencyMs} ms", style = MaterialTheme.typography.labelMedium, color = c, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Test connectivity & latency") }, leadingIcon = { Icon(Icons.Filled.NetworkCheck, null) }, onClick = { menu = false; onTest() })
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Export / share") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("Delete", color = ErrorRed) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = ErrorRed) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
fun ExportDialog(p: ProxyProfile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val canStrip = p.protocol != Protocol.VLESS && p.hasAuth
    var includeSecrets by remember { mutableStateOf(!canStrip) }
    val link = LinkCodec.export(p, includeSecrets)
    val qr = remember(link) { QrCodes.encode(link, 640) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export \"${p.name}\"") },
        text = {
            Column {
                if (canStrip) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Include username & password", Modifier.weight(1f))
                        Switch(checked = includeSecrets, onCheckedChange = { includeSecrets = it })
                    }
                }
                if (includeSecrets && (p.protocol == Protocol.VLESS || p.hasAuth)) {
                    WarningBox(
                        if (p.protocol == Protocol.VLESS) "This link contains the UUID, which grants access to the server. Share it only with people you trust."
                        else "This link contains your proxy password in readable form."
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
                    Image(qr.asImageBitmap(), "QR code", Modifier.size(220.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Share proxy"))
                onDismiss()
            }) { Text("Share") }
        },
        dismissButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ProxyFlow profile", link).apply {
                    description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                })
                onDismiss()
            }) { Text("Copy") }
        },
    )
}

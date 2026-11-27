@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.proxyflow.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.proxyflow.app.data.PerAppMode
import com.proxyflow.app.ui.MainViewModel
import com.proxyflow.app.ui.components.AppTextField
import com.proxyflow.app.ui.components.BackTopBar
import com.proxyflow.app.ui.components.GradientBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)

@Composable
fun AppPickerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    val current = selected ?: settings.selectedApps

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != context.packageName }
                .map { ai ->
                    AppEntry(ai.packageName, ai.loadLabel(pm).toString(),
                        runCatching { ai.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull())
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    fun toggle(pkg: String) {
        val next = if (pkg in current) current - pkg else current + pkg
        selected = next
        vm.settingsAction { it.setSelectedApps(next) }
    }

    GradientBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                BackTopBar(if (settings.perAppMode == PerAppMode.ONLY_SELECTED) "Apps using the proxy" else "Apps bypassing the proxy", onBack) {
                    TextButton(onClick = { selected = emptySet(); vm.settingsAction { it.setSelectedApps(emptySet()) } }) { Text("Clear") }
                }
            },
        ) { pad ->
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Scaffold
            }
            val filtered = list.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                .sortedByDescending { it.pkg in current }
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                item { AppTextField(query, { query = it }, "Search apps", leading = Icons.Filled.Search) }
                items(filtered, key = { it.pkg }) { app ->
                    Row(Modifier.fillMaxWidth().clickable { toggle(app.pkg) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (app.icon != null) Image(app.icon, null, Modifier.size(40.dp)) else Spacer(Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Checkbox(checked = app.pkg in current, onCheckedChange = { toggle(app.pkg) })
                    }
                }
            }
        }
    }
}

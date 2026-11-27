package com.proxyflow.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.proxyflow.app.ui.screens.AboutScreen
import com.proxyflow.app.ui.screens.AppPickerScreen
import com.proxyflow.app.ui.screens.DashboardScreen
import com.proxyflow.app.ui.screens.DetailsScreen
import com.proxyflow.app.ui.screens.EditorScreen
import com.proxyflow.app.ui.screens.ImportScreen
import com.proxyflow.app.ui.screens.ProfilesScreen
import com.proxyflow.app.ui.screens.SettingsScreen
import com.proxyflow.app.ui.theme.ProxyFlowTheme
import com.proxyflow.app.vpn.ConnState
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var pendingImport by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingImport = extractImport(intent)

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val message by vm.messages.collectAsStateWithLifecycle()
            val snackbar = remember { SnackbarHostState() }
            val nav = rememberNavController()

            val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                if (r.resultCode == RESULT_OK) vm.connect()
                else vm.toast("VPN permission was denied — ProxyFlow cannot route traffic without it")
            }
            val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

            val requestConnect: () -> Unit = {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                val prep = VpnService.prepare(this)
                if (prep == null) vm.connect() else runCatching { vpnPermission.launch(prep) }
                    .onFailure { vm.toast("This device does not allow VPN apps") }
            }

            LaunchedEffect(message) {
                message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
            }
            LaunchedEffect(Unit) {
                // Auto-connect on launch (only when permission is already granted, never prompts unexpectedly).
                val s = vm.settingsRepo.settings.first()
                if (savedInstanceState == null && s.autoConnectOnLaunch && vm.status.value.state == ConnState.DISCONNECTED &&
                    VpnService.prepare(this@MainActivity) == null
                ) vm.connect()
            }
            LaunchedEffect(pendingImport) {
                if (pendingImport != null) nav.navigate("import")
            }

            ProxyFlowTheme(settings.theme) {
                Box(Modifier.fillMaxSize()) {
                    NavHost(
                        nav, startDestination = "dashboard",
                        enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
                        exitTransition = { fadeOut() },
                        popEnterTransition = { fadeIn() },
                        popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
                    ) {
                        composable("dashboard") {
                            DashboardScreen(
                                vm, onConnect = requestConnect,
                                onProfiles = { nav.navigate("profiles") },
                                onSettings = { nav.navigate("settings") },
                                onDetails = { nav.navigate("details") },
                                onAbout = { nav.navigate("about") },
                            )
                        }
                        composable("profiles") {
                            ProfilesScreen(vm, onBack = { nav.popBackStack() }, onAdd = { nav.navigate("add") },
                                onEdit = { nav.navigate("edit/$it") }, onImport = { nav.navigate("import") })
                        }
                        composable("add") { EditorScreen(vm, null) { nav.popBackStack() } }
                        composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                            EditorScreen(vm, e.arguments?.getLong("id")) { nav.popBackStack() }
                        }
                        composable("details") { DetailsScreen(vm) { nav.popBackStack() } }
                        composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }, onPickApps = { nav.navigate("apps") }) }
                        composable("apps") { AppPickerScreen(vm) { nav.popBackStack() } }
                        composable("import") {
                            val initial = remember { pendingImport.also { pendingImport = null } }
                            ImportScreen(vm, initial, onBack = { nav.popBackStack() }, onDone = {
                                nav.popBackStack("dashboard", false); nav.navigate("profiles")
                            })
                        }
                        composable("about") { AboutScreen { nav.popBackStack() } }
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractImport(intent)?.let { pendingImport = it }
    }

    private fun extractImport(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString?.takeIf { it.startsWith("vless://", true) }
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.contains("://") }
        else -> null
    }
}

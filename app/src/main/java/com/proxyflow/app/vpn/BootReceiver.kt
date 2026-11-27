package com.proxyflow.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.proxyflow.app.ProxyFlowApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Optional auto-connect after the device boots (only if VPN permission was granted before). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as ProxyFlowApp
                val s = app.settings.current()
                val ok = s.autoConnectOnBoot && s.selectedProfileId > 0 && VpnService.prepare(context) == null &&
                    (intent.action == Intent.ACTION_BOOT_COMPLETED)
                if (ok) ProxyVpnService.connect(context, s.selectedProfileId)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}

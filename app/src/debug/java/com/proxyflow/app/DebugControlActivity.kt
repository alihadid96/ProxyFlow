package com.proxyflow.app

import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.proxyflow.app.core.LinkCodec
import com.proxyflow.app.core.OvpnConfig
import com.proxyflow.app.core.ProfileValidator
import com.proxyflow.app.data.Protocol
import com.proxyflow.app.data.ProxyProfile
import com.proxyflow.app.vpn.ProxyVpnService
import kotlinx.coroutines.launch

/**
 * DEBUG BUILD ONLY (not present in release APKs).
 *   adb shell am start -n com.proxyflow.app.debug/com.proxyflow.app.DebugControlActivity --es cmd connect --es link 'socks5://…'
 *   … --es cmd connect --es ovpn <base64 .ovpn> [--es user u --es pass p]
 *   … --es cmd disconnect
 */
class DebugControlActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ProxyFlowApp
        lifecycleScope.launch {
            try {
                when (intent.getStringExtra("cmd")) {
                    "connect" -> {
                        val link = intent.getStringExtra("link")
                        val ovpn = intent.getStringExtra("ovpn")?.let { String(Base64.decode(it, Base64.DEFAULT)) }
                        val profile = if (ovpn != null) {
                            val r = OvpnConfig.parse(ovpn).firstRemote
                            ProxyProfile(
                                name = "ci-openvpn", protocol = Protocol.OPENVPN, ovpnConfig = ovpn,
                                host = r?.host ?: "", port = r?.port ?: 1194, ovpnProto = r?.proto ?: "udp",
                                username = intent.getStringExtra("user").orEmpty(), password = intent.getStringExtra("pass").orEmpty(),
                                authEnabled = intent.hasExtra("user"),
                            )
                        } else LinkCodec.parse(link ?: error("link or ovpn extra required"))
                        val problems = ProfileValidator.validate(profile)
                        if (problems.isNotEmpty()) Log.i("ProxyFlowState", "STATE INVALID ${problems.joinToString()}")
                        val id = app.profiles.save(profile)
                        app.settings.setSelectedProfile(id)
                        ProxyVpnService.connect(this@DebugControlActivity, id)
                    }
                    "disconnect" -> ProxyVpnService.disconnect(this@DebugControlActivity)
                }
            } catch (e: Exception) {
                Log.i("ProxyFlowState", "STATE CONTROL_ERROR ${e.message}")
            }
            finish()
        }
    }
}

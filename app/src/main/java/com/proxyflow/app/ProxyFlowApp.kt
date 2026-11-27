package com.proxyflow.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.proxyflow.app.data.AppDatabase
import com.proxyflow.app.data.ProfileRepository
import com.proxyflow.app.data.SettingsRepository

class ProxyFlowApp : Application() {
    lateinit var profiles: ProfileRepository
        private set
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        profiles = ProfileRepository(AppDatabase.create(this).profiles())
        settings = SettingsRepository(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_VPN, "VPN status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent notification while ProxyFlow is connected"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "Connection events", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Connected, disconnected and error alerts"
            }
        )
    }

    companion object {
        const val CHANNEL_VPN = "vpn_status"
        const val CHANNEL_EVENTS = "connection_events"
        lateinit var instance: ProxyFlowApp
            private set
    }
}

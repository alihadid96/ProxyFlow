package com.proxyflow.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class Ipv6Mode { BLOCK, PROXY, BYPASS }
enum class PerAppMode { ALL, BYPASS_SELECTED, ONLY_SELECTED }

data class DnsPreset(val id: String, val label: String, val ip: String, val doh: String?)

val DNS_PRESETS = listOf(
    DnsPreset("cloudflare", "Cloudflare (1.1.1.1)", "1.1.1.1", "https://1.1.1.1/dns-query"),
    DnsPreset("google", "Google (8.8.8.8)", "8.8.8.8", "https://8.8.8.8/dns-query"),
    DnsPreset("quad9", "Quad9 (9.9.9.9)", "9.9.9.9", "https://9.9.9.9/dns-query"),
    DnsPreset("adguard", "AdGuard (94.140.14.14)", "94.140.14.14", "https://94.140.14.14/dns-query"),
    DnsPreset("custom", "Custom", "", null),
)

data class AppSettings(
    val autoConnectOnLaunch: Boolean = false,
    val autoConnectOnBoot: Boolean = false,
    val autoReconnect: Boolean = true,
    val connectTimeoutSec: Int = 10,
    val dnsPreset: String = "cloudflare",
    val customDns: String = "",
    val ipv6Mode: Ipv6Mode = Ipv6Mode.BLOCK,
    val perAppMode: PerAppMode = PerAppMode.ALL,
    val selectedApps: Set<String> = emptySet(),
    val killSwitch: Boolean = true,
    val bypassLan: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val connectionNotifications: Boolean = true,
    val lookupExitIp: Boolean = true,
    val selectedProfileId: Long = -1,
    val lifetimeDownload: Long = 0,
    val lifetimeUpload: Long = 0,
    val statsResetAt: Long = 0,
) {
    /** The plain DNS server IP the VPN advertises (all port-53 traffic is intercepted anyway). */
    fun dnsIp(): String {
        val preset = DNS_PRESETS.firstOrNull { it.id == dnsPreset } ?: DNS_PRESETS.first()
        return if (preset.id == "custom") customDns.trim().ifEmpty { "1.1.1.1" } else preset.ip
    }

    /** DNS server string for Xray when DNS is resolved through the proxy (TCP based, works over HTTP proxies). */
    fun remoteDnsServer(): String {
        val preset = DNS_PRESETS.firstOrNull { it.id == dnsPreset } ?: DNS_PRESETS.first()
        return preset.doh ?: "tcp://${dnsIp()}:53"
    }
}

class SettingsRepository(private val context: Context) {
    private object K {
        val autoLaunch = booleanPreferencesKey("auto_connect_launch")
        val autoBoot = booleanPreferencesKey("auto_connect_boot")
        val autoReconnect = booleanPreferencesKey("auto_reconnect")
        val timeout = intPreferencesKey("connect_timeout")
        val dnsPreset = stringPreferencesKey("dns_preset")
        val customDns = stringPreferencesKey("custom_dns")
        val ipv6 = stringPreferencesKey("ipv6_mode")
        val perApp = stringPreferencesKey("per_app_mode")
        val apps = stringSetPreferencesKey("selected_apps")
        val killSwitch = booleanPreferencesKey("kill_switch")
        val bypassLan = booleanPreferencesKey("bypass_lan")
        val theme = stringPreferencesKey("theme")
        val notifications = booleanPreferencesKey("connection_notifications")
        val lookupIp = booleanPreferencesKey("lookup_exit_ip")
        val selected = longPreferencesKey("selected_profile")
        val lifeDown = longPreferencesKey("lifetime_down")
        val lifeUp = longPreferencesKey("lifetime_up")
        val resetAt = longPreferencesKey("stats_reset_at")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            autoConnectOnLaunch = p[K.autoLaunch] ?: false,
            autoConnectOnBoot = p[K.autoBoot] ?: false,
            autoReconnect = p[K.autoReconnect] ?: true,
            connectTimeoutSec = p[K.timeout] ?: 10,
            dnsPreset = p[K.dnsPreset] ?: "cloudflare",
            customDns = p[K.customDns] ?: "",
            ipv6Mode = runCatching { Ipv6Mode.valueOf(p[K.ipv6] ?: "BLOCK") }.getOrDefault(Ipv6Mode.BLOCK),
            perAppMode = runCatching { PerAppMode.valueOf(p[K.perApp] ?: "ALL") }.getOrDefault(PerAppMode.ALL),
            selectedApps = p[K.apps] ?: emptySet(),
            killSwitch = p[K.killSwitch] ?: true,
            bypassLan = p[K.bypassLan] ?: true,
            theme = runCatching { ThemeMode.valueOf(p[K.theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            connectionNotifications = p[K.notifications] ?: true,
            lookupExitIp = p[K.lookupIp] ?: true,
            selectedProfileId = p[K.selected] ?: -1,
            lifetimeDownload = p[K.lifeDown] ?: 0,
            lifetimeUpload = p[K.lifeUp] ?: 0,
            statsResetAt = p[K.resetAt] ?: 0,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setAutoConnectOnLaunch(v: Boolean) = context.dataStore.edit { it[K.autoLaunch] = v }
    suspend fun setAutoConnectOnBoot(v: Boolean) = context.dataStore.edit { it[K.autoBoot] = v }
    suspend fun setAutoReconnect(v: Boolean) = context.dataStore.edit { it[K.autoReconnect] = v }
    suspend fun setConnectTimeout(v: Int) = context.dataStore.edit { it[K.timeout] = v.coerceIn(3, 60) }
    suspend fun setDnsPreset(v: String) = context.dataStore.edit { it[K.dnsPreset] = v }
    suspend fun setCustomDns(v: String) = context.dataStore.edit { it[K.customDns] = v.trim() }
    suspend fun setIpv6Mode(v: Ipv6Mode) = context.dataStore.edit { it[K.ipv6] = v.name }
    suspend fun setPerAppMode(v: PerAppMode) = context.dataStore.edit { it[K.perApp] = v.name }
    suspend fun setSelectedApps(v: Set<String>) = context.dataStore.edit { it[K.apps] = v }
    suspend fun setKillSwitch(v: Boolean) = context.dataStore.edit { it[K.killSwitch] = v }
    suspend fun setBypassLan(v: Boolean) = context.dataStore.edit { it[K.bypassLan] = v }
    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setConnectionNotifications(v: Boolean) = context.dataStore.edit { it[K.notifications] = v }
    suspend fun setLookupExitIp(v: Boolean) = context.dataStore.edit { it[K.lookupIp] = v }
    suspend fun setSelectedProfile(id: Long) = context.dataStore.edit { it[K.selected] = id }

    suspend fun addLifetimeTraffic(down: Long, up: Long) = context.dataStore.edit {
        it[K.lifeDown] = (it[K.lifeDown] ?: 0) + down
        it[K.lifeUp] = (it[K.lifeUp] ?: 0) + up
    }

    suspend fun resetTrafficStats() = context.dataStore.edit {
        it[K.lifeDown] = 0
        it[K.lifeUp] = 0
        it[K.resetAt] = System.currentTimeMillis()
    }
}

package com.proxyflow.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.proxyflow.app.ProxyFlowApp
import com.proxyflow.app.core.ImportResult
import com.proxyflow.app.core.LinkCodec
import com.proxyflow.app.core.ProxyTester
import com.proxyflow.app.data.AppSettings
import com.proxyflow.app.data.ProxyProfile
import com.proxyflow.app.vpn.ConnState
import com.proxyflow.app.vpn.ProxyVpnService
import com.proxyflow.app.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ProxyFlowApp
    val settingsRepo = app.settings

    val profiles: StateFlow<List<ProxyProfile>> =
        app.profiles.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val status = VpnState.status
    val traffic = VpnState.traffic
    val measure = VpnState.measure
    val logs = VpnState.logs

    private val _testing = MutableStateFlow<Set<Long>>(emptySet())
    val testing: StateFlow<Set<Long>> = _testing.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()
    fun toast(msg: String) { _messages.value = msg }
    fun consumeMessage() { _messages.value = null }

    fun selectedProfile(): ProxyProfile? {
        val list = profiles.value
        return list.firstOrNull { it.id == settings.value.selectedProfileId } ?: list.firstOrNull()
    }

    fun select(id: Long) = viewModelScope.launch {
        settingsRepo.setSelectedProfile(id)
        val st = status.value
        if ((st.state == ConnState.CONNECTED || st.state == ConnState.RECONNECTING) && st.profileId != id) {
            ProxyVpnService.connect(app, id) // switch servers while connected
        }
    }

    /** Called after VPN permission is granted. Reads profiles from the database so it also works right at launch. */
    fun connect() = viewModelScope.launch {
        val list = app.profiles.all()
        val selectedId = settingsRepo.current().selectedProfileId
        val p = list.firstOrNull { it.id == selectedId } ?: list.firstOrNull()
        if (p == null) { toast("Add a proxy profile first"); return@launch }
        if (selectedId != p.id) settingsRepo.setSelectedProfile(p.id)
        ProxyVpnService.connect(app, p.id)
    }

    fun disconnect() = ProxyVpnService.disconnect(app)
    fun refreshMeasurements() = ProxyVpnService.measure(app)

    suspend fun load(id: Long): ProxyProfile? = app.profiles.get(id)

    fun save(p: ProxyProfile, onSaved: (Long) -> Unit) = viewModelScope.launch {
        val trimmed = p.copy(name = p.name.trim(), host = p.host.trim(), uuid = p.uuid.trim())
        val id = app.profiles.save(trimmed)
        if (settings.value.selectedProfileId <= 0 || profiles.value.isEmpty()) settingsRepo.setSelectedProfile(id)
        onSaved(id)
    }

    fun delete(id: Long) = viewModelScope.launch {
        if (status.value.profileId == id && status.value.state != ConnState.DISCONNECTED) disconnect()
        app.profiles.delete(id)
        if (settings.value.selectedProfileId == id) {
            settingsRepo.setSelectedProfile(profiles.value.firstOrNull { it.id != id }?.id ?: -1)
        }
    }

    fun duplicate(id: Long) = viewModelScope.launch { app.profiles.duplicate(id); toast("Profile duplicated") }
    fun rename(id: Long, name: String) = viewModelScope.launch { if (name.isNotBlank()) app.profiles.rename(id, name.trim()) }

    private val testSlots = Semaphore(3)

    fun test(p: ProxyProfile) = viewModelScope.launch {
        if (p.id in _testing.value) return@launch
        _testing.value = _testing.value + p.id
        try {
            testSlots.withPermit {
                val r = withContext(Dispatchers.IO) { ProxyTester.test(app, p, settings.value) }
                app.profiles.setTestResult(p.id, r.latencyMs, r.error)
            }
        } finally {
            _testing.value = _testing.value - p.id
        }
    }

    fun testAll() = profiles.value.forEach { test(it) }

    fun importText(text: String): ImportResult {
        val result = LinkCodec.parseMany(text)
        viewModelScope.launch {
            var firstId = -1L
            result.profiles.forEach { val id = app.profiles.save(it); if (firstId < 0) firstId = id }
            if (firstId > 0 && settings.value.selectedProfileId <= 0) settingsRepo.setSelectedProfile(firstId)
        }
        return result
    }

    fun settingsAction(block: suspend (com.proxyflow.app.data.SettingsRepository) -> Unit) =
        viewModelScope.launch { block(settingsRepo) }
}

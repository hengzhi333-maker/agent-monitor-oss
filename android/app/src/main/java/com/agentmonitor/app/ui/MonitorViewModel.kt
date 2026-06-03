package com.agentmonitor.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import android.net.Uri
import com.agentmonitor.app.MonitorEngine
import com.agentmonitor.app.data.Alert
import com.agentmonitor.app.data.HostConfig
import com.agentmonitor.app.data.MonitorRepository
import com.agentmonitor.app.data.Snapshot
import com.agentmonitor.app.data.WorkbenchEventData
import kotlinx.coroutines.flow.StateFlow

data class HostRuntime(
    val config: HostConfig,
    val connection: ConnState = ConnState.Connecting,
    val snapshot: Snapshot? = null,
    val lastError: String? = null,
    val lastUpdateMs: Long = 0
)

enum class ConnState { Connecting, Online, Offline }

data class UiState(
    val hosts: List<HostRuntime> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    val workbenchEvents: Map<String, List<Pair<String, WorkbenchEventData>>> = emptyMap()
)

class MonitorViewModel : ViewModel() {
    val ui: StateFlow<UiState> = MonitorEngine.ui

    init {
        MonitorEngine.start()
    }

    fun hosts(): List<HostConfig> = MonitorEngine.hosts()

    fun repository(): MonitorRepository = MonitorEngine.repository()

    fun workbenchEvents(hostId: String): List<Pair<String, WorkbenchEventData>> =
        MonitorEngine.workbenchEvents(hostId)

    fun addOrUpdateHost(host: HostConfig) = MonitorEngine.addOrUpdateHost(host)

    fun removeHost(id: String) = MonitorEngine.removeHost(id)

    fun retry(id: String) = MonitorEngine.retry(id)

    fun clearAlerts() = MonitorEngine.clearAlerts()

    fun exportHosts(): String = MonitorEngine.exportHosts()

    fun exportHosts(password: String): String = MonitorEngine.exportHosts(password)

    fun importHosts(raw: String, merge: Boolean = true): Int = MonitorEngine.importHosts(raw, merge)

    fun importHosts(raw: String, password: String, merge: Boolean = true): Int =
        MonitorEngine.importHosts(raw, password, merge)

    fun cleanupUsbHosts(): Int = MonitorEngine.cleanupUsbHosts()

    fun importHostUri(raw: String): Boolean = runCatching {
        MonitorEngine.importHostUri(Uri.parse(raw.trim()))
    }.getOrDefault(false)

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MonitorViewModel() as T
    }
}

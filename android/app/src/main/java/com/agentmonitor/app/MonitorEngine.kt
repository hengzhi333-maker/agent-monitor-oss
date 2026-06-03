package com.agentmonitor.app

import android.content.Context
import android.net.Uri
import com.agentmonitor.app.data.Alert
import com.agentmonitor.app.data.ConnEvent
import com.agentmonitor.app.data.HostConfig
import com.agentmonitor.app.data.HostStore
import com.agentmonitor.app.data.MonitorRepository
import com.agentmonitor.app.data.Snapshot
import com.agentmonitor.app.data.WorkbenchEventData
import com.agentmonitor.app.notifications.AlertNotifier
import com.agentmonitor.app.ui.ConnState
import com.agentmonitor.app.ui.HostRuntime
import com.agentmonitor.app.ui.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val AlertNotificationDedupeMs = 5 * 60 * 1000L
private const val HostOfflineNotificationGraceMs = 60_000L
private const val HostOfflineNotificationDedupeMs = 10 * 60 * 1000L
private const val WorkbenchLongRunningNotificationMs = 10 * 60 * 1000L

object MonitorEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()
    private val offlineNotifyJobs = mutableMapOf<String, Job>()
    private val workbenchNotifyJobs = mutableMapOf<String, Job>()
    private val lastWorkbenchState = mutableMapOf<String, String>()
    private val lastOfflineNotificationAt = mutableMapOf<String, Long>()
    private val lastAlertNotificationAt = mutableMapOf<String, Long>()
    private val _ui = MutableStateFlow(UiState())

    private lateinit var repo: MonitorRepository
    private lateinit var store: HostStore
    private lateinit var notifier: AlertNotifier
    private var initialized = false
    private var started = false

    val ui: StateFlow<UiState> = _ui.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        repo = MonitorRepository()
        store = HostStore(appContext)
        notifier = AlertNotifier(appContext)
        val saved = store.load()
        _ui.update { it.copy(hosts = saved.map { host -> HostRuntime(host) }) }
        initialized = true
    }

    @Synchronized
    fun start() {
        ensureInitialized()
        if (started) return
        started = true
        _ui.value.hosts.forEach { connect(it.config) }
    }

    fun hosts(): List<HostConfig> {
        ensureInitialized()
        return _ui.value.hosts.map { it.config }
    }

    fun onlineCount(): Int = _ui.value.hosts.count { it.connection == ConnState.Online }

    fun repository(): MonitorRepository {
        ensureInitialized()
        return repo
    }

    fun workbenchEvents(hostId: String): List<Pair<String, WorkbenchEventData>> =
        _ui.value.workbenchEvents[hostId].orEmpty()

    fun addOrUpdateHost(host: HostConfig) {
        ensureInitialized()
        val hosts = store.upsert(host)
        replaceHosts(hosts)
        hosts.find { it.sameLogicalHost(host) }?.let { connect(it) }
    }

    fun removeHost(id: String) {
        ensureInitialized()
        store.remove(id)
        jobs.remove(id)?.cancel()
        offlineNotifyJobs.remove(id)?.cancel()
        workbenchNotifyJobs.keys.filter { it.startsWith("$id:") }.forEach { workbenchNotifyJobs.remove(it)?.cancel() }
        lastWorkbenchState.keys.filter { it.startsWith("$id:") }.forEach { lastWorkbenchState.remove(it) }
        _ui.update { state ->
            state.copy(
                hosts = state.hosts.filterNot { it.config.id == id },
                workbenchEvents = state.workbenchEvents - id
            )
        }
    }

    fun retry(id: String) {
        ensureInitialized()
        _ui.value.hosts.find { it.config.id == id }?.let { connect(it.config) }
    }

    fun clearAlerts() = _ui.update { it.copy(alerts = emptyList()) }

    fun exportHosts(): String {
        ensureInitialized()
        return store.exportPlain()
    }

    fun exportHosts(password: String): String {
        ensureInitialized()
        return store.exportBackup(password)
    }

    fun importHosts(raw: String, merge: Boolean = true): Int {
        ensureInitialized()
        val hosts = store.importPlain(raw, merge)
        replaceHosts(hosts)
        hosts.forEach { connect(it) }
        return hosts.size
    }

    fun importHosts(raw: String, password: String, merge: Boolean = true): Int {
        ensureInitialized()
        val hosts = store.importBackup(raw, password, merge)
        replaceHosts(hosts)
        hosts.forEach { connect(it) }
        return hosts.size
    }

    fun cleanupUsbHosts(): Int {
        ensureInitialized()
        val before = store.load()
        val hosts = store.cleanupUsbHosts()
        replaceHosts(hosts)
        val afterIds = hosts.map { it.id }.toSet()
        return before.count { it.isUsb && it.id !in afterIds }
    }

    fun importHostUri(uri: Uri): Boolean {
        ensureInitialized()
        if (uri.scheme != "agentmonitor" || uri.host != "host") return false
        val address = uri.getQueryParameter("address").orEmpty().trim()
        val token = uri.getQueryParameter("token").orEmpty().trim()
        if (address.isBlank() || token.isBlank()) return false
        val rawIdentity = uri.getQueryParameter("identityKey")
            ?: uri.getQueryParameter("hostId")
            ?: uri.getQueryParameter("daemonId")
            ?: uri.getQueryParameter("id")
            ?: ""
        val identity = rawIdentity.trim()
        val name = uri.getQueryParameter("name")?.takeIf { it.isNotBlank() } ?: address
        val host = HostConfig(
            id = if (identity.isNotBlank()) stableHostId(identity) else uri.getQueryParameter("id") ?: System.currentTimeMillis().toString(),
            name = name,
            address = address,
            port = uri.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8765,
            token = token,
            secure = uri.getQueryParameter("secure") == "1" || uri.getQueryParameter("secure") == "true",
            group = uri.getQueryParameter("group").orEmpty(),
            pinned = uri.getQueryParameter("pinned") == "1" || uri.getQueryParameter("pinned") == "true",
            identityKey = identity
        )
        addOrUpdateHost(host)
        return true
    }

    private fun replaceHosts(hosts: List<HostConfig>) {
        val ids = hosts.map { it.id }.toSet()
        jobs.keys.filterNot { it in ids }.forEach { jobs.remove(it)?.cancel() }
        offlineNotifyJobs.keys.filterNot { it in ids }.forEach { offlineNotifyJobs.remove(it)?.cancel() }
        workbenchNotifyJobs.keys.filterNot { it.substringBefore(":") in ids }.forEach { workbenchNotifyJobs.remove(it)?.cancel() }
        lastWorkbenchState.keys.filterNot { it.substringBefore(":") in ids }.forEach { lastWorkbenchState.remove(it) }
        _ui.update { state ->
            val existing = state.hosts.associateBy { it.config.id }
            state.copy(
                hosts = hosts.map { host ->
                    existing[host.id]?.copy(config = host) ?: HostRuntime(host)
                },
                workbenchEvents = state.workbenchEvents.filterKeys { it in ids }
            )
        }
    }

    private fun connect(host: HostConfig) {
        jobs.remove(host.id)?.cancel()
        jobs[host.id] = scope.launch {
            var backoff = 2_000L
            while (isActive) {
                setConn(host.id, ConnState.Connecting, null)
                try {
                    val snap = repo.fetchSnapshot(host)
                    applySnapshot(host.id, snap)
                    setConn(host.id, ConnState.Online, null)
                    backoff = 2_000L
                } catch (e: Exception) {
                    setConn(host.id, ConnState.Offline, e.message)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                    continue
                }

                try {
                    repo.stream(host).collect { event ->
                        when (event) {
                            is ConnEvent.Open -> setConn(host.id, ConnState.Online, null)
                            is ConnEvent.SnapshotMsg -> {
                                applySnapshot(host.id, event.snapshot)
                                setConn(host.id, ConnState.Online, null)
                            }
                            is ConnEvent.AlertMsg -> pushAlert(event.alert)
                            is ConnEvent.WorkbenchMsg -> pushWorkbenchEvent(host.id, event.type, event.data)
                            is ConnEvent.Closed -> setConn(host.id, ConnState.Offline, event.reason)
                        }
                    }
                } catch (e: Exception) {
                    setConn(host.id, ConnState.Offline, e.message)
                }

                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun applySnapshot(id: String, snap: Snapshot) {
        _ui.update { state ->
            state.copy(hosts = state.hosts.map {
                if (it.config.id == id) {
                    it.copy(snapshot = snap, lastUpdateMs = System.currentTimeMillis(), lastError = null)
                } else {
                    it
                }
            })
        }
    }

    private fun setConn(id: String, state: ConnState, err: String?) {
        _ui.update { current ->
            current.copy(hosts = current.hosts.map {
                if (it.config.id == id) it.copy(connection = state, lastError = err) else it
            })
        }
        if (state == ConnState.Offline) {
            scheduleOfflineNotification(id, err)
        } else if (state == ConnState.Online) {
            offlineNotifyJobs.remove(id)?.cancel()
        }
    }

    private fun scheduleOfflineNotification(id: String, err: String?) {
        if (offlineNotifyJobs[id]?.isActive == true) return
        offlineNotifyJobs[id] = scope.launch {
            delay(HostOfflineNotificationGraceMs)
            val host = _ui.value.hosts.find { it.config.id == id } ?: return@launch
            if (host.connection != ConnState.Offline) return@launch
            val now = System.currentTimeMillis()
            val last = lastOfflineNotificationAt[id] ?: 0L
            if (now - last < HostOfflineNotificationDedupeMs) return@launch
            lastOfflineNotificationAt[id] = now
            pushAlert(
                Alert(
                    level = "warn",
                    agent = id,
                    title = "${host.config.name} offline",
                    body = err ?: "The workstation daemon has been unreachable for more than 60 seconds.",
                    ts = now
                )
            )
        }
    }

    private fun pushAlert(alert: Alert) {
        _ui.update { it.copy(alerts = (listOf(alert) + it.alerts).take(50)) }
        if (shouldNotify(alert)) notifier.show(alert)
    }

    @Synchronized
    private fun shouldNotify(alert: Alert): Boolean {
        val key = listOf(alert.agent, alert.level, alert.title).joinToString("|")
        val now = System.currentTimeMillis()
        val last = lastAlertNotificationAt[key] ?: 0L
        if (now - last < AlertNotificationDedupeMs) return false
        lastAlertNotificationAt[key] = now
        return true
    }

    private fun pushWorkbenchEvent(hostId: String, type: String, data: WorkbenchEventData) {
        _ui.update { state ->
            val current = state.workbenchEvents[hostId].orEmpty()
            state.copy(
                workbenchEvents = state.workbenchEvents + (hostId to ((current + (type to data)).takeLast(300)))
            )
        }
        handleWorkbenchNotification(hostId, data)
    }

    private fun handleWorkbenchNotification(hostId: String, data: WorkbenchEventData) {
        val session = data.session
        val sessionId = data.sessionId.ifBlank { session?.id.orEmpty() }
        if (sessionId.isBlank()) return
        val state = session?.state?.ifBlank { data.state } ?: data.state
        if (state.isBlank()) return

        val key = "$hostId:$sessionId"
        val previous = lastWorkbenchState.put(key, state)
        if (state == "running") {
            if (previous != "running") scheduleLongRunningWorkbenchAlert(hostId, sessionId, session?.title.orEmpty())
            return
        }

        workbenchNotifyJobs.remove(key)?.cancel()
        if (previous != "running") return
        val host = _ui.value.hosts.find { it.config.id == hostId } ?: return
        val title = session?.title?.ifBlank { sessionId.take(8) } ?: sessionId.take(8)
        val level = if (state == "error") "warn" else "info"
        val label = when (state) {
            "error" -> "工作台任务失败"
            "stopped" -> "工作台任务已停止"
            else -> "工作台任务完成"
        }
        pushAlert(
            Alert(
                level = level,
                agent = hostId,
                title = label,
                body = "${host.config.name} · $title",
                ts = System.currentTimeMillis()
            )
        )
    }

    private fun scheduleLongRunningWorkbenchAlert(hostId: String, sessionId: String, title: String) {
        val key = "$hostId:$sessionId"
        if (workbenchNotifyJobs[key]?.isActive == true) return
        workbenchNotifyJobs[key] = scope.launch {
            delay(WorkbenchLongRunningNotificationMs)
            if (lastWorkbenchState[key] != "running") return@launch
            val host = _ui.value.hosts.find { it.config.id == hostId } ?: return@launch
            pushAlert(
                Alert(
                    level = "info",
                    agent = hostId,
                    title = "工作台仍在运行",
                    body = "${host.config.name} · ${title.ifBlank { sessionId.take(8) }} 已运行超过 10 分钟",
                    ts = System.currentTimeMillis()
                )
            )
        }
    }

    private fun ensureInitialized() {
        check(initialized) { "MonitorEngine.init(context) must be called before use" }
    }

    private fun stableHostId(identity: String): String {
        val clean = identity.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')
        return "host_${clean.ifBlank { Integer.toUnsignedString(identity.hashCode(), 36) }}"
    }
}

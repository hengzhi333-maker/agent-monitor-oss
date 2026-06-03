package com.agentmonitor.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentmonitor.app.data.HistoryEvent
import com.agentmonitor.app.data.HistoryResponse
import com.agentmonitor.app.data.HistoryTrendPoint
import com.agentmonitor.app.data.HostConfig
import com.agentmonitor.app.data.MonitorRepository
import com.agentmonitor.app.ui.ConnState
import com.agentmonitor.app.ui.Fmt
import com.agentmonitor.app.ui.HostRuntime
import com.agentmonitor.app.ui.UiState
import com.agentmonitor.app.ui.components.AgentCard
import com.agentmonitor.app.ui.components.ServiceRow
import com.agentmonitor.app.ui.components.stateColor
import com.agentmonitor.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: UiState,
    repo: MonitorRepository,
    onAddHost: () -> Unit,
    onEditHost: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpenAgentSessions: (String, String) -> Unit,
    onOpenSession: (String, String, String) -> Unit,
    onOpenWorkbench: (String, String) -> Unit,
    onOpenDiagnostics: (String) -> Unit,
    onOpenSecurity: (String) -> Unit,
    onOpenAlerts: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val visibleHosts = remember(state.hosts, query) {
        val q = query.trim()
        state.hosts
            .filter { host ->
                q.isBlank() ||
                    host.config.name.contains(q, ignoreCase = true) ||
                    host.config.address.contains(q, ignoreCase = true) ||
                    host.config.group.contains(q, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<HostRuntime> { it.config.pinned }
                    .thenBy { it.config.group.ifBlank { "未分组" } }
                    .thenBy { it.config.name.lowercase() }
            )
    }
    val groupedHosts = visibleHosts.groupBy { it.config.group.ifBlank { "未分组" } }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("Agent 监测", color = TextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                actions = {
                    BadgedBox(
                        badge = {
                            if (state.alerts.isNotEmpty())
                                Badge { Text(state.alerts.size.toString()) }
                        }
                    ) {
                        IconButton(onClick = onOpenAlerts) {
                            Icon(Icons.Default.Notifications, "告警", tint = TextPrimary)
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddHost, containerColor = Accent) {
                Icon(Icons.Default.Add, "添加主机", tint = Bg)
            }
        }
    ) { pad ->
        if (state.hosts.isEmpty()) {
            EmptyHosts(Modifier.padding(pad), onAddHost)
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索主机、地址或分组") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = Accent
                    )
                )
            }
            if (visibleHosts.isEmpty()) {
                item {
                    Text("没有匹配的主机", color = TextSecondary, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
            groupedHosts.forEach { (group, hosts) ->
                item {
                    Text(group, color = TextSecondary, style = MaterialTheme.typography.titleSmall)
                }
                items(hosts, key = { it.config.id }) { host ->
                    HostSection(
                        host,
                        repo,
                        onEditHost,
                        onRetry,
                        onOpenAgentSessions,
                        onOpenSession,
                        onOpenWorkbench,
                        onOpenDiagnostics,
                        onOpenSecurity
                    )
                }
            }
            item { Spacer(Modifier.height(64.dp)) }
        }
    }
}

@Composable
private fun HostSection(
    host: HostRuntime,
    repo: MonitorRepository,
    onEditHost: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpenAgentSessions: (String, String) -> Unit,
    onOpenSession: (String, String, String) -> Unit,
    onOpenWorkbench: (String, String) -> Unit,
    onOpenDiagnostics: (String) -> Unit,
    onOpenSecurity: (String) -> Unit
) {
    val dotColor by animateColorAsState(
        when (host.connection) {
            ConnState.Online -> StateActive
            ConnState.Connecting -> StateIdle
            ConnState.Offline -> StateError
        }, label = "dot"
    )
    Column {
        // 主机标题行
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                host.config.name,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onEditHost(host.config.id) }) {
                Text("设置", color = Accent)
            }
            IconButton(onClick = { onOpenSecurity(host.config.id) }) {
                Icon(Icons.Default.Security, "安全中心", tint = Accent)
            }
        }
        Text(
            "${host.config.connectionLabel} · ${host.config.address}:${host.config.port}${host.config.group.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}${if (host.config.pinned) " · pinned" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 18.dp)
        )

        Spacer(Modifier.height(12.dp))

        when {
            host.connection == ConnState.Offline -> OfflineBanner(
                host = host,
                onRetry = { onRetry(host.config.id) },
                onDiagnostics = { onOpenDiagnostics(host.config.id) }
            )
            host.snapshot == null -> LoadingBanner()
            else -> {
                val snap = host.snapshot
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    snap.agents.forEach { agent ->
                        AgentCard(
                            agent = agent,
                            onOpenSessions = { onOpenAgentSessions(host.config.id, agent.id) },
                            onOpenSession = { sessionId -> onOpenSession(host.config.id, agent.id, sessionId) },
                            onOpenWorkbench = { onOpenWorkbench(host.config.id, agent.id) }
                        )
                    }
                    if (snap.services.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "账号 / 服务健康",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary
                        )
                        snap.services.forEach { ServiceRow(it) }
                    }
                    HostHistoryCard(
                        host = host.config,
                        repo = repo,
                        snapshotTs = snap.ts
                    )
                }
            }
        }
    }
}

@Composable
private fun HostHistoryCard(
    host: HostConfig,
    repo: MonitorRepository,
    snapshotTs: Long
) {
    var history by remember(host.id) { mutableStateOf<HistoryResponse?>(null) }
    var loading by remember(host.id) { mutableStateOf(false) }
    var error by remember(host.id) { mutableStateOf<String?>(null) }
    var refreshKey by remember(host.id) { mutableStateOf(0) }

    LaunchedEffect(host.id, snapshotTs, refreshKey) {
        loading = history == null
        error = null
        try {
            history = repo.fetchHistory(host)
        } catch (e: Exception) {
            error = e.message ?: "历史加载失败"
        } finally {
            loading = false
        }
    }

    val latest = history?.samples?.lastOrNull()
    val recentEvents = history?.events.orEmpty().takeLast(3).asReversed()

    Surface(shape = RoundedCornerShape(12.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("近期变化", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text(
                        latest?.let { "采样 ${Fmt.ago(it.ts)} · ${history?.samples?.size ?: 0} 条" } ?: "等待采样",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                } else {
                    IconButton(onClick = { refreshKey += 1 }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Refresh, "刷新历史", tint = Accent)
                    }
                }
            }

            latest?.let { sample ->
                Text(
                    "${agentHistorySummary(sample.agentCounts)} · ${serviceHistorySummary(sample.serviceCounts)}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            history?.trend?.takeIf { it.size >= 2 }?.let { trend ->
                TrendStrip(trend)
            }

            if (error != null && history == null) {
                Text(error.orEmpty(), color = StateError, style = MaterialTheme.typography.bodySmall)
            } else if (recentEvents.isEmpty()) {
                Text("暂无告警或恢复事件", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            } else {
                recentEvents.forEach { event ->
                    HistoryEventRow(event)
                }
            }
        }
    }
}

@Composable
private fun HistoryEventRow(event: HistoryEvent) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(stateColor(historyLevelState(event.level)))
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                event.title.ifBlank { event.sourceId.ifBlank { "状态变化" } },
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${Fmt.ago(event.ts)} · ${event.body}",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TrendStrip(points: List<HistoryTrendPoint>) {
    val latest = points.lastOrNull() ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
        ) {
            val maxValue = points.maxOf {
                maxOf(
                    it.inputTokens.toFloat(),
                    it.outputTokens.toFloat(),
                    it.cacheTokens.toFloat(),
                    1f
                )
            }
            fun y(value: Float): Float = size.height - (value / maxValue) * size.height
            fun line(values: List<Float>, color: androidx.compose.ui.graphics.Color) {
                if (values.size < 2) return
                val step = size.width / (values.size - 1)
                for (i in 0 until values.lastIndex) {
                    drawLine(
                        color = color,
                        start = Offset(i * step, y(values[i])),
                        end = Offset((i + 1) * step, y(values[i + 1])),
                        strokeWidth = 3f
                    )
                }
            }
            line(points.map { it.inputTokens.toFloat() }, Accent)
            line(points.map { it.outputTokens.toFloat() }, StateActive)
            line(points.map { it.cacheTokens.toFloat() }, StateIdle)
        }
        Text(
            "趋势: 今日 ${latest.sessionsToday} 会话 · in ${Fmt.tokens(latest.inputTokens)} / out ${Fmt.tokens(latest.outputTokens)} · 异常服务 ${latest.downServices}",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun agentHistorySummary(counts: Map<String, Int>): String {
    val active = (counts["active"] ?: 0) + (counts["idle"] ?: 0)
    val offline = counts["offline"] ?: 0
    return "Agent 在线 $active / 离线 $offline"
}

private fun serviceHistorySummary(counts: Map<String, Int>): String {
    val up = counts["up"] ?: 0
    val down = counts["down"] ?: 0
    return "服务正常 $up / 异常 $down"
}

private fun historyLevelState(level: String): String = when (level) {
    "error" -> "error"
    "warn", "warning" -> "warning"
    else -> "healthy"
}

private fun connectionAdvice(host: HostRuntime): String {
    val error = host.lastError.orEmpty()
    val address = host.config.address
    return when {
        error.contains("401") -> "Token 不正确。重新导入主机二维码，或复制 daemon config.json 里的 token。"
        error.contains("403") -> "daemon 拒绝此手机。把手机 Tailscale IP 或 100.64.0.0/10 加入 allowlist。"
        error.contains("UnknownHost", ignoreCase = true) || error.contains("Unable to resolve", ignoreCase = true) ->
            "主机名无法解析。确认手机 Tailscale 已连接，或改用 100.x 地址。"
        looksLikeTailscaleAddress(address) || !address.contains('.') ->
            "检查手机和电脑的 Tailscale 都是 Connected，然后运行自检。"
        address == "127.0.0.1" ->
            "127.0.0.1 只适合 USB 调试。拔线后请切换到 Tailscale。"
        else -> "检查 daemon、地址、端口、防火墙，以及手机是否在同一网络。"
    }
}

private fun looksLikeTailscaleAddress(address: String): Boolean {
    val parts = address.split('.').mapNotNull { it.toIntOrNull() }
    return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
}

@Composable
private fun OfflineBanner(host: HostRuntime, onRetry: () -> Unit, onDiagnostics: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("连接不上", color = StateError, style = MaterialTheme.typography.titleMedium)
                Text(
                    host.lastError ?: "请检查 daemon 是否运行、token 是否正确",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    connectionAdvice(host),
                    color = StateIdle,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onDiagnostics) {
                Text("自检", color = Accent)
            }
            IconButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, "重试", tint = Accent)
            }
        }
    }
}

@Composable
private fun LoadingBanner() {
    Surface(shape = RoundedCornerShape(12.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
            Spacer(Modifier.width(12.dp))
            Text("连接中…", color = TextSecondary)
        }
    }
}

@Composable
private fun EmptyHosts(modifier: Modifier, onAddHost: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("还没有添加主机", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "在跑 agent 的电脑上启动 daemon,然后点下方按钮添加它的地址和 token。",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAddHost, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
            Icon(Icons.Default.Add, null, tint = Bg)
            Spacer(Modifier.width(8.dp))
            Text("添加主机", color = Bg)
        }
    }
}

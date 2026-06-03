package com.agentmonitor.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentmonitor.app.data.HostConfig
import com.agentmonitor.app.data.MonitorRepository
import com.agentmonitor.app.data.WorkbenchSession
import com.agentmonitor.app.ui.Fmt
import com.agentmonitor.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchListScreen(
    host: HostConfig?,
    agentId: String,
    repo: MonitorRepository,
    onOpenSession: (String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<WorkbenchSession>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var includeArchived by remember { mutableStateOf(false) }
    var selectedPermissionMode by remember(host?.id, agentId) { mutableStateOf("standard") }
    var dangerousAllowed by remember(host?.id, agentId) { mutableStateOf(false) }
    var modeLoaded by remember(host?.id, agentId) { mutableStateOf(false) }
    var confirmDangerousCreate by remember(host?.id, agentId) { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<WorkbenchSession?>(null) }

    val visibleSessions = remember(sessions, query) {
        val q = query.trim()
        sessions
            .filter { it.agentId == agentId }
            .filter {
                q.isBlank() ||
                    it.title.contains(q, ignoreCase = true) ||
                    it.cwd.contains(q, ignoreCase = true) ||
                    it.state.contains(q, ignoreCase = true)
            }
            .sortedByDescending { it.updatedAt }
    }

    fun createSession() {
        val currentHost = host ?: return
        scope.launch {
            loading = true
            error = null
            try {
                val created = repo.createWorkbenchSession(
                    currentHost,
                    agentId,
                    "",
                    "${agentLabel(agentId)} 工作台",
                    selectedPermissionMode
                ).session
                onOpenSession(created.id)
            } catch (e: Exception) {
                error = e.message ?: "创建失败"
            } finally {
                loading = false
            }
        }
    }

    fun setArchived(session: WorkbenchSession, archived: Boolean) {
        val currentHost = host ?: return
        scope.launch {
            loading = true
            error = null
            try {
                if (archived) {
                    repo.archiveWorkbenchSession(currentHost, session.id)
                    status = "已归档 ${session.title.ifBlank { session.id.take(8) }}"
                } else {
                    repo.unarchiveWorkbenchSession(currentHost, session.id)
                    status = "已恢复 ${session.title.ifBlank { session.id.take(8) }}"
                }
                refreshKey++
            } catch (e: Exception) {
                error = e.message ?: "更新失败"
            } finally {
                loading = false
            }
        }
    }

    fun deleteSession(session: WorkbenchSession) {
        val currentHost = host ?: return
        scope.launch {
            loading = true
            error = null
            try {
                repo.deleteWorkbenchSession(currentHost, session.id)
                status = "已删除 ${session.title.ifBlank { session.id.take(8) }}"
                refreshKey++
            } catch (e: Exception) {
                error = e.message ?: "删除失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(host?.id, agentId, refreshKey, includeArchived) {
        if (host == null) return@LaunchedEffect
        loading = true
        error = null
        try {
            sessions = repo.fetchWorkbenchSessions(host, includeArchived = includeArchived).sessions
            runCatching { repo.fetchDiagnostics(host) }.getOrNull()?.let { diagnostics ->
                dangerousAllowed = diagnostics.workbench.allowDangerousPermissions
                if (!modeLoaded) {
                    selectedPermissionMode = normalizePermissionMode(diagnostics.workbench.defaultPermissionMode)
                    modeLoaded = true
                }
                if (!dangerousAllowed && selectedPermissionMode == "dangerous") {
                    selectedPermissionMode = "standard"
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    if (confirmDangerousCreate) {
        AlertDialog(
            onDismissRequest = { confirmDangerousCreate = false },
            title = { Text("确认高权限工作台") },
            text = { Text("高权限模式只对当前会话临时生效，到期后会自动降回普通权限。") },
            confirmButton = {
                TextButton(onClick = { confirmDangerousCreate = false; createSession() }) {
                    Text("确认开启", color = StateError)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDangerousCreate = false }) {
                    Text("取消")
                }
            }
        )
    }

    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除工作台会话") },
            text = { Text("会删除该会话的消息索引和本地附件文件。运行中的会话需要先停止。") },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; deleteSession(session) }) {
                    Text("删除", color = StateError)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("${agentLabel(agentId)} 工作台", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Default.Refresh, "刷新", tint = Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
        ) {
            PermissionModeSelector(
                selected = selectedPermissionMode,
                dangerousAllowed = dangerousAllowed,
                onSelected = { selectedPermissionMode = it }
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (selectedPermissionMode == "dangerous") confirmDangerousCreate = true else createSession()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("新建 ${agentLabel(agentId)} 工作台", color = Bg)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索标题、目录或状态") },
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("显示归档", color = TextSecondary, modifier = Modifier.weight(1f))
                Switch(
                    checked = includeArchived,
                    onCheckedChange = { includeArchived = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Accent)
                )
            }
            if (status.isNotBlank()) {
                WorkbenchListInlineState(status)
                Spacer(Modifier.height(8.dp))
            }
            when {
                host == null -> WorkbenchListInlineState("主机不存在")
                error != null -> WorkbenchListInlineState(error.orEmpty())
                loading && visibleSessions.isEmpty() -> WorkbenchListInlineState("加载中...")
                visibleSessions.isEmpty() -> WorkbenchListInlineState(if (query.isBlank()) "暂无工作台" else "没有匹配的工作台")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visibleSessions, key = { it.id }) { session ->
                        WorkbenchRow(
                            session = session,
                            onClick = { onOpenSession(session.id) },
                            onArchiveToggle = { setArchived(session, !session.archived) },
                            onDelete = { deleteTarget = session }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkbenchRow(
    session: WorkbenchSession,
    onClick: () -> Unit,
    onArchiveToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (session.archived) BgCard.copy(alpha = 0.72f) else BgCard
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title.ifBlank { session.id.take(8) },
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(stateLabel(session), color = stateColor(session.state), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = onArchiveToggle, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (session.archived) Icons.Default.Unarchive else Icons.Default.Archive,
                        if (session.archived) "恢复" else "归档",
                        tint = TextSecondary
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, "删除", tint = StateError)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                Fmt.shortPath(session.cwd),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
            if (session.lastError.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(session.lastError, color = StateError, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                listOf(
                    permissionModeLabel(session.permissionMode),
                    dangerousExpiryText(session),
                    "${session.attachmentCount} 附件",
                    Fmt.ago(session.updatedAt)
                ).filter { it.isNotBlank() }.joinToString(" · "),
                color = if (session.permissionMode == "dangerous") StateError else TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PermissionModeSelector(
    selected: String,
    dangerousAllowed: Boolean,
    onSelected: (String) -> Unit
) {
    Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("权限模式", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PermissionChip("read-only", "只读", selected, true, onSelected, Modifier.weight(1f))
                PermissionChip("standard", "普通", selected, true, onSelected, Modifier.weight(1f))
                PermissionChip("dangerous", "高权限", selected, dangerousAllowed, onSelected, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                permissionModeHint(selected, dangerousAllowed),
                color = if (selected == "dangerous") StateError else TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PermissionChip(
    mode: String,
    label: String,
    selected: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected == mode,
        enabled = enabled,
        onClick = { onSelected(mode) },
        label = { Text(label, maxLines = 1) },
        modifier = modifier
    )
}

@Composable
private fun WorkbenchListInlineState(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = TextSecondary, modifier = Modifier.padding(14.dp))
    }
}

private fun stateColor(state: String) = when (state) {
    "running" -> StateActive
    "error" -> StateError
    "stopped" -> StateIdle
    else -> TextSecondary
}

private fun stateLabel(session: WorkbenchSession): String = when {
    session.archived -> "archived"
    else -> session.state
}

private fun normalizePermissionMode(mode: String): String = when (mode) {
    "read-only", "readonly", "read" -> "read-only"
    "dangerous", "full-access", "bypass" -> "dangerous"
    else -> "standard"
}

private fun permissionModeLabel(mode: String): String = when (mode) {
    "read-only" -> "只读"
    "dangerous" -> "高权限"
    else -> "普通"
}

private fun permissionModeHint(mode: String, dangerousAllowed: Boolean): String = when (mode) {
    "read-only" -> "只允许 agent 查看上下文，适合巡检和分析。"
    "dangerous" -> if (dangerousAllowed) "仅 admin token 可创建，高权限窗口到期后会自动降级。" else "daemon 当前未允许高权限模式。"
    else -> "默认工作模式，限制在允许的工作目录内执行。"
}

private fun dangerousExpiryText(session: WorkbenchSession): String {
    if (session.permissionMode != "dangerous" || session.dangerousExpiresAt <= 0) return ""
    val remaining = session.dangerousExpiresAt - System.currentTimeMillis()
    if (remaining <= 0) return "高权限已过期"
    val minutes = (remaining + 59_999) / 60_000
    return if (minutes < 60) "高权限剩余 ${minutes}min" else "高权限剩余 ${minutes / 60}h"
}

private fun agentLabel(agentId: String): String = when (agentId) {
    "claude-code" -> "Claude Code"
    "codex" -> "Codex"
    else -> agentId
}

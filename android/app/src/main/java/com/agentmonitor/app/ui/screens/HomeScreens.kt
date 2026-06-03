package com.agentmonitor.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentmonitor.app.data.HostConfig
import com.agentmonitor.app.data.MonitorRepository
import com.agentmonitor.app.data.WorkbenchAttachmentIndex
import com.agentmonitor.app.ui.ConnState
import com.agentmonitor.app.ui.Fmt
import com.agentmonitor.app.ui.UiState
import com.agentmonitor.app.ui.components.AgentCard
import com.agentmonitor.app.ui.components.StatusChip
import com.agentmonitor.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsOverviewScreen(
    state: UiState,
    onOpenAgentSessions: (String, String) -> Unit,
    onOpenSession: (String, String, String) -> Unit,
    onOpenWorkbench: (String, String) -> Unit
) {
    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("Agent", color = TextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val hosts = state.hosts.filter { it.snapshot?.agents?.isNotEmpty() == true }
            if (hosts.isEmpty()) {
                item { InlineState("暂无 agent 数据") }
            }
            hosts.forEach { host ->
                item {
                    Text(host.config.name, color = TextSecondary, style = MaterialTheme.typography.titleSmall)
                }
                items(host.snapshot?.agents.orEmpty(), key = { "${host.config.id}:${it.id}" }) { agent ->
                    AgentCard(
                        agent = agent,
                        onOpenSessions = { onOpenAgentSessions(host.config.id, agent.id) },
                        onOpenSession = { sessionId -> onOpenSession(host.config.id, agent.id, sessionId) },
                        onOpenWorkbench = { onOpenWorkbench(host.config.id, agent.id) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchHubScreen(
    state: UiState,
    onOpenWorkbench: (String, String) -> Unit
) {
    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("工作台", color = TextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.hosts.isEmpty()) {
                item { InlineState("暂无主机") }
            }
            state.hosts.forEach { host ->
                item {
                    Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(host.config.name, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                                    Text(host.config.displayEndpoint, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                                StatusChip(if (host.connection == ConnState.Online) "up" else "down")
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WorkbenchButton("Codex") { onOpenWorkbench(host.config.id, "codex") }
                                WorkbenchButton("Claude") { onOpenWorkbench(host.config.id, "claude-code") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.WorkbenchButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) {
        Text(label, color = Accent)
    }
}

private data class AttachmentRow(
    val host: HostConfig,
    val attachment: WorkbenchAttachmentIndex
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    hosts: List<HostConfig>,
    repo: MonitorRepository
) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<AttachmentRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmCleanup by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AttachmentRow?>(null) }

    suspend fun loadRows(): List<AttachmentRow> =
        hosts.flatMap { host ->
            repo.fetchWorkbenchAttachments(host).attachments.map { AttachmentRow(host, it) }
        }.sortedByDescending { it.attachment.createdAt }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                rows = loadRows()
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    fun cleanupAll() {
        scope.launch {
            loading = true
            error = null
            status = null
            try {
                var removed = 0
                for (host in hosts) {
                    removed += repo.cleanupWorkbenchAttachments(host, all = true).removedAttachments
                }
                rows = loadRows()
                status = if (removed > 0) "已清理 $removed 个附件" else "没有需要清理的附件"
            } catch (e: Exception) {
                error = e.message ?: "清理失败"
            } finally {
                loading = false
            }
        }
    }

    fun deleteAttachment(row: AttachmentRow) {
        scope.launch {
            try {
                repo.deleteWorkbenchAttachment(row.host, row.attachment.sessionId, row.attachment.id)
                rows = rows.filterNot { it.host.id == row.host.id && it.attachment.id == row.attachment.id }
                status = "已删除 ${row.attachment.name}"
                error = null
            } catch (e: Exception) {
                error = e.message ?: "删除失败"
            }
        }
    }

    LaunchedEffect(hosts) { refresh() }

    if (confirmCleanup) {
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            title = { Text("清理全部附件") },
            text = { Text("会删除所有主机上工作台已上传的附件文件和索引，历史消息仍会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCleanup = false
                        cleanupAll()
                    }
                ) {
                    Text("清理", color = StateError)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCleanup = false }) {
                    Text("取消")
                }
            }
        )
    }

    deleteTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除附件") },
            text = { Text("删除 ${row.attachment.name} 的本地上传文件和索引。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        deleteAttachment(row)
                    }
                ) {
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
                title = { Text("文件", color = TextPrimary) },
                actions = {
                    IconButton(enabled = !loading && hosts.isNotEmpty(), onClick = { confirmCleanup = true }) {
                        Icon(Icons.Default.Delete, "清理附件", tint = if (!loading) StateError else TextSecondary)
                    }
                    IconButton(enabled = !loading, onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, "刷新", tint = Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (status != null) {
                item { InlineState(status.orEmpty()) }
            }
            if (error != null && rows.isNotEmpty()) {
                item { InlineState(error.orEmpty()) }
            }
            when {
                loading && rows.isEmpty() -> item { InlineState("加载文件中...") }
                error != null && rows.isEmpty() -> item { InlineState(error.orEmpty()) }
                rows.isEmpty() -> item { InlineState("暂无已上传附件") }
                else -> items(rows, key = { "${it.host.id}:${it.attachment.id}" }) { row ->
                    AttachmentIndexRow(
                        row = row,
                        onDelete = { deleteTarget = row }
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentIndexRow(row: AttachmentRow, onDelete: () -> Unit) {
    val attachment = row.attachment
    Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    attachment.name,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(attachmentKindLabel(attachment.kind), color = Accent, style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, "删除附件", tint = TextSecondary)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${row.host.name} · ${agentLabel(attachment.agentId)} · ${formatBytes(attachment.size)} · ${Fmt.ago(attachment.createdAt)}",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (attachment.textPreview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    attachment.textPreview,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onAddHost: () -> Unit,
    onEditHost: (String) -> Unit,
    onOpenDiagnostics: (String) -> Unit,
    onOpenSecurity: (String) -> Unit,
    onOpenBackup: () -> Unit,
    onRetry: (String) -> Unit,
    onCleanupUsbHosts: () -> Int
) {
    var query by remember { mutableStateOf("") }
    var cleanupStatus by remember { mutableStateOf("") }
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
                compareByDescending<com.agentmonitor.app.ui.HostRuntime> { it.config.pinned }
                    .thenBy { it.config.group.ifBlank { "未分组" } }
                    .thenBy { it.config.name.lowercase() }
            )
    }
    val groupedHosts = visibleHosts.groupBy { it.config.group.ifBlank { "未分组" } }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("设置", color = TextPrimary) },
                actions = {
                    IconButton(onClick = onAddHost) {
                        Icon(Icons.Default.Add, "添加主机", tint = Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("配置迁移", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text("导出/导入主机配置，支持粘贴二维码深链。", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                val removed = onCleanupUsbHosts()
                                cleanupStatus = if (removed > 0) {
                                    "已清理 $removed 个旧 USB 主机"
                                } else {
                                    "没有需要清理的旧 USB 主机"
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("清理旧 USB 主机", color = Accent)
                        }
                        if (cleanupStatus.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(cleanupStatus, color = StateActive, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Samsung Secure Folder 是独立空间；如里面装过旧版，需要在 Secure Folder 内单独卸载。",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenBackup, modifier = Modifier.fillMaxWidth()) {
                            Text("备份 / 导入", color = Accent)
                        }
                    }
                }
            }
            if (state.hosts.isNotEmpty()) {
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
            }
            if (state.hosts.isEmpty()) {
                item { InlineState("暂无主机") }
            }
            if (state.hosts.isNotEmpty() && visibleHosts.isEmpty()) {
                item { InlineState("没有匹配的主机") }
            }
            items(visibleHosts, key = { it.config.id }) { host ->
                Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(host.config.name, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                                Text(host.config.displayEndpoint, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            StatusChip(if (host.connection == ConnState.Online) "up" else "down")
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallAction("编辑") { onEditHost(host.config.id) }
                            SmallAction("自检") { onOpenDiagnostics(host.config.id) }
                            SmallAction("安全") { onOpenSecurity(host.config.id) }
                            SmallAction("重连") { onRetry(host.config.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.SmallAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Accent,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    )
}

@Composable
private fun InlineState(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = TextSecondary, modifier = Modifier.padding(14.dp))
    }
}

private fun attachmentKindLabel(kind: String): String = when (kind) {
    "image" -> "图片"
    "text" -> "文本"
    "code" -> "代码"
    "pdf" -> "PDF"
    "word" -> "Word"
    "spreadsheet" -> "表格"
    else -> "文件"
}

private fun agentLabel(agentId: String): String = when (agentId) {
    "claude-code" -> "Claude"
    "codex" -> "Codex"
    else -> agentId.ifBlank { "Agent" }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

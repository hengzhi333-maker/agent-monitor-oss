package com.agentmonitor.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentmonitor.app.data.AuditEntry
import com.agentmonitor.app.data.HostConfig
import com.agentmonitor.app.data.MonitorRepository
import com.agentmonitor.app.data.SecurityCheck
import com.agentmonitor.app.data.SecurityStatusResponse
import com.agentmonitor.app.ui.Fmt
import com.agentmonitor.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityCenterScreen(
    host: HostConfig?,
    repo: MonitorRepository,
    onHostUpdated: (HostConfig) -> Unit,
    onOpenDevices: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<SecurityStatusResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tokenRotated by remember { mutableStateOf(false) }
    var confirmTokenRotate by remember { mutableStateOf(false) }
    var pendingRemoteEnabled by remember { mutableStateOf<Boolean?>(null) }

    fun refresh() {
        val currentHost = host ?: return
        scope.launch {
            loading = true
            error = null
            try {
                status = repo.fetchSecurityStatus(currentHost)
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(host?.id) { refresh() }

    pendingRemoteEnabled?.let { enabled ->
        AlertDialog(
            onDismissRequest = { pendingRemoteEnabled = null },
            title = { Text(if (enabled) "开启远程工作台" else "关闭远程工作台") },
            text = { Text(if (enabled) "开启后手机可以创建工作台并让 agent 执行任务。" else "关闭后手机不能再创建或发送工作台任务。") },
            confirmButton = {
                TextButton(onClick = {
                    val currentHost = host ?: return@TextButton
                    pendingRemoteEnabled = null
                    scope.launch {
                        loading = true
                        error = null
                        try {
                            status = repo.setRemoteControlEnabled(currentHost, enabled)
                        } catch (e: Exception) {
                            error = e.message ?: "更新失败"
                        } finally {
                            loading = false
                        }
                    }
                }) { Text("确认", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoteEnabled = null }) { Text("取消") }
            }
        )
    }

    if (confirmTokenRotate) {
        AlertDialog(
            onDismissRequest = { confirmTokenRotate = false },
            title = { Text("轮换访问 Token") },
            text = { Text("轮换后旧 token 会立即失效，App 会自动保存新 token 到本机加密配置。") },
            confirmButton = {
                TextButton(onClick = {
                    val currentHost = host ?: return@TextButton
                    confirmTokenRotate = false
                    scope.launch {
                        loading = true
                        error = null
                        try {
                            val rotated = repo.rotateToken(currentHost)
                            status = rotated.status
                            tokenRotated = true
                            onHostUpdated(currentHost.copy(token = rotated.token))
                        } catch (e: Exception) {
                            error = e.message ?: "轮换失败"
                        } finally {
                            loading = false
                        }
                    }
                }) { Text("轮换", color = StateError) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTokenRotate = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("安全中心", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(enabled = !loading && host != null, onClick = { refresh() }) {
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
            when {
                host == null -> InlineState("主机不存在")
                error != null && status == null -> InlineState(error.orEmpty())
                loading && status == null -> InlineState("加载安全状态...")
                else -> SecurityContent(
                    host = host,
                    status = status,
                    error = error,
                    tokenRotated = tokenRotated,
                    onToggleRemote = { pendingRemoteEnabled = it },
                    onRotateToken = { confirmTokenRotate = true },
                    onOpenDevices = onOpenDevices
                )
            }
        }
    }
}

@Composable
private fun SecurityContent(
    host: HostConfig,
    status: SecurityStatusResponse?,
    error: String?,
    tokenRotated: Boolean,
    onToggleRemote: (Boolean) -> Unit,
    onRotateToken: () -> Unit,
    onOpenDevices: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, "安全状态", tint = Accent)
                        Spacer(Modifier.width(8.dp))
                        Text(host.name, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(host.baseUrl, color = TextSecondary, fontFamily = FontFamily.Monospace, maxLines = 1)
                    status?.let {
                        Text(
                            "监听 ${it.bindHost}:${it.port}",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                    if (tokenRotated) {
                        Spacer(Modifier.height(6.dp))
                        Text("Token 已轮换并保存到手机配置。", color = StateActive, style = MaterialTheme.typography.bodySmall)
                    }
                    if (error != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(error, color = StateError, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        status?.let { current ->
            item {
                Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("远程工作台", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (current.remoteControl.enabled) "手机可创建和发送工作台任务" else "工作台远程控制已关闭",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = current.remoteControl.enabled,
                                onCheckedChange = onToggleRemote,
                                colors = SwitchDefaults.colors(checkedThumbColor = Accent)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "允许目录 ${current.remoteControl.allowedCwds.size} 个 · 输出上限 ${current.remoteControl.maxOutputChars}",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "默认权限 ${permissionModeLabel(current.remoteControl.defaultPermissionMode)} · 设备白名单 ${current.remoteControl.allowedRemoteAddresses.size} 条",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "高权限 TTL ${formatDuration(current.remoteControl.dangerousSessionTtlMs)} · ${if (current.remoteControl.allowDangerousPermissions) "高权限可用" else "高权限关闭"}",
                            color = if (current.remoteControl.allowDangerousPermissions) StateIdle else TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Token 角色 ${current.remoteControl.tokenRoles.joinToString { roleLabel(it.role) }.ifBlank { "admin" }}",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (current.remoteControl.allowedRemoteAddresses.isEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("未配置远端地址白名单，长期使用前建议只放行手机 Tailscale IP。", color = StateIdle, style = MaterialTheme.typography.bodySmall)
                        } else {
                            Spacer(Modifier.height(4.dp))
                            current.remoteControl.allowedRemoteAddresses.take(4).forEach { address ->
                                Text(address, color = TextSecondary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = onOpenDevices,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                ) {
                    Text("管理设备 Token")
                }
            }
            item {
                OutlinedButton(
                    onClick = onRotateToken,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StateError)
                ) {
                    Text("轮换访问 Token")
                }
            }
            item {
                Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("告警规则", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Agent 离线延迟 ${formatDuration(current.alertRules.agentOfflineGraceMs)}",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "服务连续失败 ${current.alertRules.serviceFailureCount} 次后通知",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            if (current.alertRules.recoveryNotifications) "恢复通知已开启" else "恢复通知已关闭",
                            color = if (current.alertRules.recoveryNotifications) StateActive else TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "重复告警冷却 ${formatDuration(current.alertRules.cooldownMs)}",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            if (current.alertRules.quietHours.enabled) {
                                "静默时段 ${current.alertRules.quietHours.start}-${current.alertRules.quietHours.end}，保留 ${current.alertRules.quietHours.suppressBelow}+"
                            } else {
                                "静默时段未开启"
                            },
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            items(current.checks, key = { it.id }) { check ->
                SecurityCheckRow(check)
            }
            item {
                AuditLogCard(current.audit.recent)
            }
        }
    }
}

@Composable
private fun AuditLogCard(entries: List<AuditEntry>) {
    Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("最近操作审计", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) {
                Text("暂无审计记录", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            } else {
                entries.take(8).forEach { entry ->
                    AuditEntryRow(entry)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun AuditEntryRow(entry: AuditEntry) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (entry.ok) "OK" else "失败", color = if (entry.ok) StateActive else StateError, modifier = Modifier.width(42.dp))
            Text(entry.action, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(Fmt.ago(entry.ts), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        val detail = auditDetail(entry)
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SecurityCheckRow(check: SecurityCheck) {
    val color = if (check.state == "ok") StateActive else StateIdle
    Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Text(if (check.state == "ok") "OK" else "注意", color = color, modifier = Modifier.width(42.dp))
            Column(Modifier.weight(1f)) {
                Text(check.title, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                Text(check.detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (check.fix.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(check.fix, color = color, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun permissionModeLabel(mode: String): String = when (mode) {
    "read-only" -> "只读"
    "dangerous" -> "高权限"
    else -> "普通"
}

private fun roleLabel(role: String): String = when (role) {
    "read-only" -> "只读"
    "operator" -> "操作"
    "admin" -> "管理"
    else -> role
}

private fun formatDuration(ms: Long): String = when {
    ms <= 0 -> "立即"
    ms < 60_000 -> "${ms / 1000}s"
    ms < 3_600_000 -> "${ms / 60_000}min"
    else -> "${ms / 3_600_000}h"
}

private fun auditDetail(entry: AuditEntry): String =
    listOf(
        entry.remoteAddress,
        entry.method,
        entry.path,
        entry.agentId,
        entry.permissionMode.takeIf { it.isNotBlank() }?.let { permissionModeLabel(it) } ?: "",
        entry.errorCode
    ).filter { it.isNotBlank() }.joinToString(" · ")

@Composable
private fun InlineState(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = TextSecondary, modifier = Modifier.padding(14.dp))
    }
}

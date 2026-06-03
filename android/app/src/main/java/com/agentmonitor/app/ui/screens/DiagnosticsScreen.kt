package com.agentmonitor.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.agentmonitor.app.BuildConfig
import com.agentmonitor.app.data.DiagnosticsResponse
import com.agentmonitor.app.data.HostConfig
import com.agentmonitor.app.data.MonitorRepository
import com.agentmonitor.app.data.VersionResponse
import com.agentmonitor.app.ui.theme.*
import kotlinx.coroutines.launch

private data class DiagnosticStep(
    val title: String,
    val state: String,
    val detail: String,
    val fix: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    host: HostConfig?,
    repo: MonitorRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var steps by remember { mutableStateOf<List<DiagnosticStep>>(emptyList()) }
    var diagnostics by remember { mutableStateOf<DiagnosticsResponse?>(null) }
    var version by remember { mutableStateOf<VersionResponse?>(null) }
    var diagnosticPackage by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    fun runCheck() {
        val currentHost = host ?: return
        scope.launch {
            running = true
            diagnostics = null
            version = null
            val next = mutableListOf<DiagnosticStep>()
            fun publish(step: DiagnosticStep) {
                next += step
                steps = next.toList()
            }

            publish(
                DiagnosticStep(
                    "App 版本",
                    "ok",
                    "Android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · build ${BuildConfig.BUILD_TIME}"
                )
            )
            publish(
                DiagnosticStep(
                    "通知权限",
                    if (canPostNotifications(context)) "ok" else "warn",
                    if (canPostNotifications(context)) "系统允许 Agent Monitor 发送通知。" else "系统未授予通知权限，离线和工作台完成提醒不会弹出。",
                    "在 Android 设置里打开 Agent Monitor 的通知权限。"
                )
            )
            publish(
                DiagnosticStep(
                    "连接方式",
                    if (currentHost.address.isNotBlank() && currentHost.port in 1..65535) "ok" else "error",
                    "${currentHost.connectionLabel} · ${currentHost.baseUrl}",
                    if (currentHost.isUsb) "USB 只适合调试；长期使用建议切到 Tailscale。" else "Tailscale 推荐使用 100.x IP 或 MagicDNS 主机名。"
                )
            )

            val pingOk = repo.ping(currentHost)
            publish(
                DiagnosticStep(
                    "/ping 探测",
                    if (pingOk) "ok" else "error",
                    if (pingOk) "daemon 基础 HTTP 入口可达。" else "手机无法访问 daemon 的 /ping。",
                    if (currentHost.isTailscale) "打开手机 Tailscale，并确认 VPN 图标存在。" else "检查 daemon 是否运行、地址和端口是否正确。"
                )
            )

            try {
                val daemonVersion = repo.fetchVersion(currentHost)
                version = daemonVersion
                publish(
                    DiagnosticStep(
                        "daemon 版本",
                        if (daemonVersion.apiVersion >= 2) "ok" else "warn",
                        "${daemonVersion.name} ${daemonVersion.version} · API ${daemonVersion.apiVersion} · Node ${daemonVersion.node}",
                        "如果 API 版本过旧，请更新电脑端 daemon。"
                    )
                )
            } catch (e: Exception) {
                publish(DiagnosticStep("daemon 版本", "warn", e.message ?: "/version 不可用", "更新电脑端 daemon 后再试。"))
            }

            try {
                val diag = repo.fetchDiagnostics(currentHost)
                diagnostics = diag
                publish(
                    DiagnosticStep(
                        "Token 鉴权",
                        "ok",
                        "已通过 /diagnostics 鉴权，daemon 主机 ${diag.host}。"
                    )
                )
                publish(
                    DiagnosticStep(
                        "监听范围",
                        if (diag.bindHost != "0.0.0.0" && diag.bindHost != "::") "ok" else "warn",
                        "daemon 监听 ${diag.bindHost}:${diag.port}",
                        "长期远程使用建议绑定到 Tailscale IP。"
                    )
                )
                publish(
                    DiagnosticStep(
                        "Tailscale 路径",
                        if (diag.tailscale.hint.isBlank() && (currentHost.isTailscale || diag.tailscale.targetLooksLikeTailnet)) "ok" else "warn",
                        diag.tailscale.hint.ifBlank { "当前目标看起来${if (currentHost.isTailscale) "是" else "不是"} Tailscale 路径。" },
                        "确认手机和电脑都在同一个 tailnet，且电脑端 bindHost/allowlist 放行手机。"
                    )
                )
                publish(
                    DiagnosticStep(
                        "Remote allowlist",
                        if (diag.remoteAccess.allowed) "ok" else "error",
                        if (diag.remoteAccess.configured) {
                            "${diag.remoteAccess.remoteAddress.ifBlank { "unknown" }} matched ${diag.remoteAccess.allowedRemoteAddresses.size} rule(s)."
                        } else {
                            "No remote address allowlist is configured."
                        },
                        "把手机 Tailscale IP 或严格的 tailnet CIDR 加到 remoteControl.allowedRemoteAddresses。"
                    )
                )
                publish(commandStep("Git CLI", diag.commands.git.command, diag.commands.git.found))
                publish(commandStep("Codex CLI", diag.commands.codex.command, diag.commands.codex.found))
                publish(commandStep("Claude CLI", diag.commands.claudeCode.command, diag.commands.claudeCode.found))
                publish(
                    DiagnosticStep(
                        "工作台权限",
                        if (diag.workbench.enabled) "ok" else "warn",
                        "默认 ${diag.workbench.defaultPermissionMode} · 高权限 ${if (diag.workbench.allowDangerousPermissions) "允许" else "关闭"} · TTL ${formatDuration(diag.workbench.dangerousSessionTtlMs)}",
                        "只在需要手机控制 agent 时开启远程工作台。"
                    )
                )
            } catch (e: Exception) {
                publish(
                    DiagnosticStep(
                        "Token 鉴权",
                        "error",
                        e.message ?: "鉴权接口不可用",
                        "如果 /ping 正常但这里失败，通常是 token 不一致或角色权限不足。"
                    )
                )
            }

            try {
                repo.fetchSnapshot(currentHost)
                publish(DiagnosticStep("主面板数据", "ok", "/snapshot 可读取，主面板可以拿到真实数据。"))
            } catch (e: Exception) {
                publish(DiagnosticStep("主面板数据", "error", e.message ?: "读取失败", "检查 token 和 daemon 采集状态。"))
            }

            val wsOk = repo.probeWebSocket(currentHost)
            publish(
                DiagnosticStep(
                    "实时通道",
                    if (wsOk) "ok" else "error",
                    if (wsOk) "WebSocket 可连接。" else "WebSocket 无法连接。",
                    "如果 HTTP 正常但 WebSocket 失败，检查代理、VPN 或 daemon 日志。"
                )
            )

            running = false
        }
    }

    fun loadPackage() {
        val currentHost = host ?: return
        scope.launch {
            diagnosticPackage = try {
                repo.fetchDiagnosticPackage(currentHost)
            } catch (e: Exception) {
                e.message ?: "诊断包导出失败"
            }
        }
    }

    diagnosticPackage?.let { raw ->
        AlertDialog(
            onDismissRequest = { diagnosticPackage = null },
            title = { Text("诊断包") },
            text = {
                Text(
                    raw.take(3000),
                    color = TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.heightIn(max = 420.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { diagnosticPackage = null }) { Text("关闭") }
            }
        )
    }

    LaunchedEffect(host?.id) {
        steps = emptyList()
        runCheck()
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("连接自检", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(enabled = !running && host != null, onClick = { runCheck() }) {
                        Icon(Icons.Default.Refresh, "重新自检", tint = Accent)
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
            if (host == null) {
                InlineState("主机不存在")
                return@Column
            }
            HostSummary(host, diagnostics, version, running)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { loadPackage() }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                Text("导出诊断包", color = Accent)
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                if (steps.isEmpty()) item { InlineState("准备自检...") }
                items(steps) { StepRow(it) }
            }
        }
    }
}

@Composable
private fun HostSummary(host: HostConfig, diagnostics: DiagnosticsResponse?, version: VersionResponse?, running: Boolean) {
    Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(host.name, color = TextPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (running) "检测中" else "就绪", color = if (running) StateIdle else StateActive)
            }
            Spacer(Modifier.height(4.dp))
            Text(host.displayEndpoint, color = TextSecondary, fontFamily = FontFamily.Monospace, maxLines = 1)
            version?.let {
                Spacer(Modifier.height(4.dp))
                Text("daemon ${it.version} · API ${it.apiVersion}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            diagnostics?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "远端 ${it.remoteAddress.ifBlank { "未知" }} · 运行 ${it.uptimeSec}s",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StepRow(step: DiagnosticStep) {
    val color = when (step.state) {
        "ok" -> StateActive
        "warn" -> StateIdle
        else -> StateError
    }
    Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Text(if (step.state == "ok") "OK" else if (step.state == "warn") "注意" else "失败", color = color, modifier = Modifier.width(42.dp))
            Column(Modifier.weight(1f)) {
                Text(step.title, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                Text(step.detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (step.state != "ok" && step.fix.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(step.fix, color = color, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun InlineState(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = TextSecondary, modifier = Modifier.padding(14.dp))
    }
}

private fun commandStep(title: String, command: String, found: Boolean): DiagnosticStep =
    DiagnosticStep(
        title = title,
        state = if (found) "ok" else "warn",
        detail = if (found) command.ifBlank { "available" } else "${command.ifBlank { title }} was not found on the daemon host.",
        fix = "在电脑端安装 CLI，或把它加入 PATH。"
    )

private fun canPostNotifications(context: android.content.Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
}

private fun formatDuration(ms: Long): String = when {
    ms <= 0 -> "关闭"
    ms < 60_000 -> "${ms / 1000}s"
    ms < 3_600_000 -> "${ms / 60_000}min"
    else -> "${ms / 3_600_000}h"
}

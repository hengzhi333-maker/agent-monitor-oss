package com.agentmonitor.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentmonitor.app.data.DeviceInfo
import com.agentmonitor.app.data.DeviceUpdateRequest
import com.agentmonitor.app.data.HostConfig
import com.agentmonitor.app.data.MonitorRepository
import com.agentmonitor.app.ui.Fmt
import com.agentmonitor.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceManagementScreen(
    host: HostConfig?,
    repo: MonitorRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newRole by remember { mutableStateOf("read-only") }
    var revealedToken by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<DeviceInfo?>(null) }

    fun refresh() {
        val currentHost = host ?: return
        scope.launch {
            loading = true
            error = null
            try {
                devices = repo.fetchDevices(currentHost).devices
            } catch (e: Exception) {
                error = e.message ?: "加载设备失败"
            } finally {
                loading = false
            }
        }
    }

    fun createDevice() {
        val currentHost = host ?: return
        scope.launch {
            loading = true
            error = null
            try {
                val result = repo.createDevice(currentHost, newName.ifBlank { "phone" }, newRole)
                revealedToken = result.token
                newName = ""
                devices = repo.fetchDevices(currentHost).devices
            } catch (e: Exception) {
                error = e.message ?: "创建设备失败"
            } finally {
                loading = false
            }
        }
    }

    fun updateDevice(device: DeviceInfo, enabled: Boolean) {
        val currentHost = host ?: return
        scope.launch {
            try {
                repo.updateDevice(currentHost, device.id, DeviceUpdateRequest(enabled = enabled))
                devices = repo.fetchDevices(currentHost).devices
            } catch (e: Exception) {
                error = e.message ?: "更新设备失败"
            }
        }
    }

    fun rotateDevice(device: DeviceInfo) {
        val currentHost = host ?: return
        scope.launch {
            try {
                val result = repo.rotateDevice(currentHost, device.id)
                revealedToken = result.token
                devices = repo.fetchDevices(currentHost).devices
            } catch (e: Exception) {
                error = e.message ?: "轮换设备 Token 失败"
            }
        }
    }

    fun deleteDevice(device: DeviceInfo) {
        val currentHost = host ?: return
        scope.launch {
            try {
                repo.deleteDevice(currentHost, device.id)
                devices = devices.filterNot { it.id == device.id }
            } catch (e: Exception) {
                error = e.message ?: "删除设备失败"
            }
        }
    }

    LaunchedEffect(host?.id) { refresh() }

    revealedToken?.let { token ->
        AlertDialog(
            onDismissRequest = { revealedToken = null },
            title = { Text("新设备 Token") },
            text = {
                Text(token, fontFamily = FontFamily.Monospace)
            },
            confirmButton = {
                TextButton(onClick = { revealedToken = null }) { Text("关闭") }
            }
        )
    }

    deleteTarget?.let { device ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除设备") },
            text = { Text("删除 ${device.name.ifBlank { device.id }} 后，它会立刻失去访问权限。") },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; deleteDevice(device) }) {
                    Text("删除", color = StateError)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("设备管理", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(enabled = host != null && !loading, onClick = { refresh() }) {
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (host == null) {
                item { InlineState("主机不存在") }
                return@LazyColumn
            }
            error?.let { item { InlineState(it) } }
            item {
                Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(host.name, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("设备名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = fieldColors()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RoleChip("read-only", newRole) { newRole = it }
                            RoleChip("operator", newRole) { newRole = it }
                            RoleChip("admin", newRole) { newRole = it }
                        }
                        Button(
                            onClick = { createDevice() },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            Icon(Icons.Default.Add, null, tint = Bg)
                            Spacer(Modifier.width(8.dp))
                            Text("创建设备 Token", color = Bg)
                        }
                    }
                }
            }
            if (loading && devices.isEmpty()) {
                item { InlineState("加载设备中...") }
            }
            items(devices, key = { it.id }) { device ->
                DeviceRow(
                    device = device,
                    onToggle = { updateDevice(device, !device.enabled) },
                    onRotate = { rotateDevice(device) },
                    onDelete = { deleteTarget = device }
                )
            }
        }
    }
}

@Composable
private fun RoleChip(role: String, selected: String, onPick: (String) -> Unit) {
    FilterChip(
        selected = selected == role,
        onClick = { onPick(role) },
        label = { Text(roleLabel(role)) }
    )
}

@Composable
private fun DeviceRow(
    device: DeviceInfo,
    onToggle: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.name.ifBlank { device.id }, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${roleLabel(device.role)} · ${device.tokenPreview}",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(checked = device.enabled, onCheckedChange = { onToggle() })
            }
            Text(
                if (device.lastSeen > 0) "最近访问 ${Fmt.ago(device.lastSeen)} · ${device.remoteAddress}" else "还没有访问记录",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRotate) { Text("轮换", color = Accent) }
                TextButton(onClick = onDelete) { Text("删除", color = StateError) }
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

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = TextSecondary,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Accent
)

private fun roleLabel(role: String): String = when (role) {
    "read-only" -> "只读"
    "operator" -> "操作"
    "admin" -> "管理"
    else -> role
}

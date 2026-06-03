package com.agentmonitor.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.agentmonitor.app.data.HostConfig
import com.agentmonitor.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    existing: HostConfig?,
    onSave: (HostConfig) -> Unit,
    onDelete: ((String) -> Unit)?,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 8765).toString()) }
    var token by remember { mutableStateOf(existing?.token ?: "") }
    var secure by remember { mutableStateOf(existing?.secure ?: false) }
    var group by remember { mutableStateOf(existing?.group ?: "") }
    var pinned by remember { mutableStateOf(existing?.pinned ?: false) }

    val parsedPort = port.toIntOrNull()
    val canSave = name.isNotBlank() &&
        address.isNotBlank() &&
        token.isNotBlank() &&
        parsedPort != null &&
        parsedPort in 1..65535

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "添加主机" else "编辑主机", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TextPrimary)
                    }
                },
                actions = {
                    if (existing != null && onDelete != null) {
                        IconButton(onClick = { onDelete(existing.id) }) {
                            Icon(Icons.Default.Delete, "删除", tint = StateError)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(color = BgCard, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("连接方式", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        PresetButton("Tailscale", Modifier.weight(1f)) {
                            if (name.isBlank()) name = "workstation"
                            address = name.ifBlank { "workstation" }
                            port = "8765"
                            secure = false
                        }
                        PresetButton("USB", Modifier.weight(1f)) {
                            if (name.isBlank()) name = "USB daemon"
                            address = "127.0.0.1"
                            port = "8765"
                            secure = false
                        }
                        PresetButton("LAN", Modifier.weight(1f)) {
                            if (name.isBlank()) name = "LAN daemon"
                            if (address.isBlank() || address == "127.0.0.1") address = "192.168.0.243"
                            port = "8765"
                            secure = false
                        }
                    }
                    Text("Use a Tailscale MagicDNS name such as workstation, or use USB with adb reverse.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }

            Field("主机名称", name, "例如:工作站 / 家里台式机") { name = it }
            Field(
                "地址(Tailscale IP / 局域网 IP / 域名)",
                address,
                "例如:100.x.x.x 或 192.168.1.20"
            ) { address = it }
            Field("端口", port, "默认 8765", keyboard = KeyboardType.Number) {
                port = it.filter { c -> c.isDigit() }
            }
            Field(
                "访问 Token",
                token,
                "与 daemon config.json 中相同",
                keyboard = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation()
            ) { token = it }
            Field("主机分组", group, "例如: 家里 / 公司 / 服务器") { group = it }

            Surface(color = BgCard, shape = MaterialTheme.shapes.medium) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("HTTPS / WSS", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text("使用反向代理或证书终端时开启", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = secure,
                        onCheckedChange = { secure = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Accent)
                    )
                }
            }

            Surface(color = BgCard, shape = MaterialTheme.shapes.medium) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("置顶", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text("在多主机列表中优先显示", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = pinned,
                        onCheckedChange = { pinned = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Accent)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Surface(color = BgCard, shape = MaterialTheme.shapes.medium) {
                Text(
                    "提示:在电脑上进入 daemon 目录运行 `npm start` 启动采集服务," +
                        "config.json 里的 token 填到这里。出门在外用 Tailscale 分配的 100.x 地址即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onSave(
                        HostConfig(
                            id = existing?.id ?: System.currentTimeMillis().toString(),
                            name = name.trim(),
                            address = normalizeAddress(address),
                            port = parsedPort ?: 8765,
                            token = token.trim(),
                            secure = secure,
                            group = group.trim(),
                            pinned = pinned,
                            identityKey = existing?.identityKey.orEmpty()
                        )
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("保存", color = Bg)
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = visualTransformation,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = TextSecondary,
            focusedLabelColor = Accent,
            unfocusedLabelColor = TextSecondary,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = Accent
        )
    )
}

@Composable
private fun PresetButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text(label, color = Accent)
    }
}

private fun normalizeAddress(raw: String): String =
    raw.trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .removePrefix("ws://")
        .removePrefix("wss://")
        .substringBefore("/")

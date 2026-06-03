package com.agentmonitor.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.agentmonitor.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    exportHosts: (String) -> String,
    importHosts: (String, String) -> Int,
    importHostUri: (String) -> Boolean,
    onScan: () -> Unit,
    onBack: () -> Unit
) {
    var backupPassword by remember { mutableStateOf("") }
    var exported by remember { mutableStateOf(exportHosts("")) }
    var importText by remember { mutableStateOf(TextFieldValue("")) }
    var status by remember { mutableStateOf("") }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("备份 / 导入", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TextPrimary)
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("本机主机配置备份", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Text("内容包含主机地址和 token，请只保存在你信任的位置。", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = backupPassword,
                onValueChange = { backupPassword = it },
                label = { Text("Backup password") },
                placeholder = { Text("Optional encryption password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            OutlinedTextField(
                value = exported,
                onValueChange = {},
                readOnly = true,
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            OutlinedButton(onClick = { exported = exportHosts(backupPassword); status = "已刷新导出内容" }, modifier = Modifier.fillMaxWidth()) {
                Text(if (backupPassword.isBlank()) "刷新导出" else "刷新加密导出", color = Accent)
            }

            Spacer(Modifier.height(8.dp))
            Text("导入配置或二维码链接", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                Text("扫码导入", color = Accent)
            }
            OutlinedTextField(
                value = importText,
                onValueChange = { importText = it },
                placeholder = { Text("粘贴 JSON 备份，或 agentmonitor://host?... 链接") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            Button(
                onClick = {
                    val raw = importText.text.trim()
                    try {
                        status = if (raw.startsWith("agentmonitor://")) {
                            if (importHostUri(raw)) "已导入二维码链接" else "链接格式不完整"
                        } else {
                            val count = importHosts(raw, backupPassword)
                            "已导入 $count 个主机配置"
                        }
                        exported = exportHosts(backupPassword)
                    } catch (e: Exception) {
                        status = e.message ?: "导入失败"
                    }
                },
                enabled = importText.text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("导入", color = Bg)
            }
            if (status.isNotBlank()) {
                Text(status, color = StateActive, style = MaterialTheme.typography.bodySmall)
            }
        }
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

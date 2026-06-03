package com.agentmonitor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.agentmonitor.app.data.ConversationMessage
import com.agentmonitor.app.data.ConversationResponse
import com.agentmonitor.app.data.HostConfig
import com.agentmonitor.app.data.MonitorRepository
import com.agentmonitor.app.ui.Fmt
import com.agentmonitor.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    host: HostConfig?,
    agentId: String,
    sessionId: String,
    repo: MonitorRepository,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf<ConversationResponse?>(null) }

    LaunchedEffect(host?.id, agentId, sessionId, refreshKey) {
        if (host == null) return@LaunchedEffect
        while (true) {
            loading = data == null
            try {
                data = repo.fetchConversation(host, agentId, sessionId)
                error = null
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
            delay(4000)
        }
    }

    val messages = remember(data, query) {
        val q = query.trim()
        val all = data?.messages.orEmpty()
        if (q.isBlank()) all else all.filter {
            it.role.contains(q, ignoreCase = true) ||
                it.kind.contains(q, ignoreCase = true) ||
                it.text.contains(q, ignoreCase = true)
        }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("对话详情", color = TextPrimary)
                        Text(agentLabel(agentId), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                },
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
            ConversationHeader(data, sessionId)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索消息", color = TextSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = BgCardAlt,
                    cursorColor = Accent
                )
            )
            Spacer(Modifier.height(12.dp))
            when {
                host == null -> ConversationInlineState("主机不存在")
                loading && data == null -> ConversationInlineState("加载对话中…")
                error != null && data == null -> ConversationInlineState(error.orEmpty())
                messages.isEmpty() -> ConversationInlineState(error ?: "暂无消息")
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(msg)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationInlineState(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = TextSecondary, modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun ConversationHeader(data: ConversationResponse?, fallbackSessionId: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            val session = data?.session
            Text(
                session?.title?.ifBlank { Fmt.shortPath(session.cwd) } ?: fallbackSessionId.take(8),
                color = TextPrimary,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(5.dp))
            val meta = listOf(session?.model.orEmpty(), session?.cwd?.let { Fmt.shortPath(it) }.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            Text(
                meta.ifBlank { fallbackSessionId },
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            data?.updatedAt?.takeIf { it > 0 }?.let {
                Spacer(Modifier.height(5.dp))
                Text("最后刷新 ${Fmt.ago(it)} · 自动刷新", color = Accent, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ConversationMessage) {
    val isTool = message.role == "tool"
    var expanded by remember(message.id) { mutableStateOf(!isTool) }
    val color = when (message.role) {
        "user" -> Accent.copy(alpha = 0.14f)
        "assistant" -> BgCard
        else -> BgCardAlt
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(roleLabel(message), color = roleColor(message.role), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Text(Fmt.ago(message.ts), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (isTool) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (expanded) "收起" else "展开",
                        color = Accent,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { expanded = !expanded }
                    )
                }
            }
            if (message.title.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(message.title, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    message.text,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (isTool) FontFamily.Monospace else FontFamily.Default,
                    maxLines = if (expanded) 1000 else 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (isTool) Modifier.background(Bg.copy(alpha = 0.28f)).padding(8.dp) else Modifier)
                )
            }
        }
    }
}

private fun roleLabel(message: ConversationMessage): String = when (message.role) {
    "user" -> "用户"
    "assistant" -> when (message.kind) {
        "commentary" -> "助手 · 过程"
        "final_answer" -> "助手 · 最终"
        else -> "助手"
    }
    "tool" -> when (message.kind) {
        "call" -> "工具调用"
        "output" -> "工具输出"
        else -> "工具"
    }
    else -> message.role
}

private fun roleColor(role: String) = when (role) {
    "user" -> Accent
    "assistant" -> StateActive
    else -> TextSecondary
}

private fun agentLabel(agentId: String): String = when (agentId) {
    "claude-code" -> "Claude Code"
    "codex" -> "Codex"
    else -> agentId
}

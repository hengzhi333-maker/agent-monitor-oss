package com.agentmonitor.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentmonitor.app.data.AgentSessionsResponse
import com.agentmonitor.app.data.HostConfig
import com.agentmonitor.app.data.MonitorRepository
import com.agentmonitor.app.data.Session
import com.agentmonitor.app.ui.Fmt
import com.agentmonitor.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSessionsScreen(
    host: HostConfig?,
    agentId: String,
    repo: MonitorRepository,
    onOpenSession: (String) -> Unit,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf<AgentSessionsResponse?>(null) }

    LaunchedEffect(host?.id, agentId, refreshKey) {
        if (host == null) return@LaunchedEffect
        loading = true
        error = null
        try {
            data = repo.fetchAgentSessions(host, agentId)
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    val sessions = remember(data, query) {
        val q = query.trim()
        val all = data?.sessions.orEmpty()
        if (q.isBlank()) all else all.filter {
            listOf(it.title, it.cwd, it.model, it.id).any { field -> field.contains(q, ignoreCase = true) }
        }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("${agentLabel(agentId)} 会话", color = TextPrimary) },
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索会话", color = TextSecondary) },
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
                host == null -> InlineState("主机不存在")
                loading && data == null -> InlineState("加载会话中…")
                error != null -> InlineState(error.orEmpty())
                sessions.isEmpty() -> InlineState("暂无会话")
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        SessionRow(session, onClick = { onOpenSession(session.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: Session, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = BgCard
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                session.title.ifBlank { Fmt.shortPath(session.cwd).ifBlank { session.id.take(8) } },
                color = TextPrimary,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            val meta = listOf(Fmt.shortPath(session.cwd), session.model)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            Text(
                meta.ifBlank { session.id },
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.ago(session.lastActivity), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (session.messageCount > 0) {
                    Spacer(Modifier.width(10.dp))
                    Text("${session.messageCount} 条消息", color = Accent, style = MaterialTheme.typography.bodySmall)
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

private fun agentLabel(agentId: String): String = when (agentId) {
    "claude-code" -> "Claude Code"
    "codex" -> "Codex"
    else -> agentId
}

package com.agentmonitor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentmonitor.app.data.AgentStatus
import com.agentmonitor.app.data.Session
import com.agentmonitor.app.ui.Fmt
import com.agentmonitor.app.ui.theme.*

// 单个 agent 的状态卡片
@Composable
fun AgentCard(
    agent: AgentStatus,
    modifier: Modifier = Modifier,
    onOpenSessions: () -> Unit = {},
    onOpenSession: (String) -> Unit = {},
    onOpenWorkbench: () -> Unit = {}
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = BgCard
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    agent.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (agent.id == "codex" || agent.id == "claude-code") {
                    TextButton(onClick = onOpenWorkbench) {
                        Text("工作台", color = Accent)
                    }
                }
                if (agent.sessions.isNotEmpty()) {
                    TextButton(onClick = onOpenSessions) {
                        Text("会话", color = Accent)
                    }
                }
                StatusChip(agent.state)
            }

            Spacer(Modifier.height(6.dp))
            Text(
                agent.summary.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // token 指标(CLI agent 才有)
            val t = agent.metrics.tokensToday
            if (t.input > 0 || t.output > 0) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricPill("输入", Fmt.tokens(t.input))
                    MetricPill("输出", Fmt.tokens(t.output))
                    if (t.cacheRead > 0) MetricPill("缓存", Fmt.tokens(t.cacheRead))
                }
            }

            // hermes 进程数
            if (agent.metrics.processes > 0) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricPill("进程", agent.metrics.processes.toString())
                    if (agent.metrics.model.isNotBlank()) MetricPill("模型", agent.metrics.model)
                }
            } else if (agent.metrics.model.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                MetricPill("模型", agent.metrics.model)
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "最近活动 ${Fmt.ago(agent.lastActivity)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (agent.metrics.sessionsToday > 0) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "今日 ${agent.metrics.sessionsToday} 会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            if (agent.sessions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    agent.sessions.take(3).forEach { session ->
                        SessionPreviewRow(session, onClick = { onOpenSession(session.id) })
                    }
                    if (agent.sessions.size > 3) {
                        TextButton(
                            onClick = onOpenSessions,
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                        ) {
                            Text("查看全部 ${agent.sessions.size} 个最近会话", color = Accent)
                        }
                    }
                }
            }

            // hermes 文本进度
            agent.detail?.status?.takeIf { it.isNotBlank() }?.let { status ->
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = BgCardAlt,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionPreviewRow(session: Session, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = BgCardAlt,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                sessionTitle(session),
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val meta = listOf(Fmt.shortPath(session.cwd), session.model)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                Text(
                    meta.ifBlank { session.id.take(8) },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(Fmt.ago(session.lastActivity), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

private fun sessionTitle(session: Session): String =
    session.title.ifBlank { Fmt.shortPath(session.cwd).ifBlank { session.id.take(8) } }

@Composable
private fun MetricPill(label: String, value: String) {
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BgCardAlt)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}

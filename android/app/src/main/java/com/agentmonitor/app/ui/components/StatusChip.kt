package com.agentmonitor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentmonitor.app.ui.theme.*

fun stateColor(state: String): Color = when (state) {
    "active", "up", "healthy", "ok" -> StateActive
    "idle", "warning", "not_configured" -> StateIdle
    "error", "down", "unauthorized" -> StateError
    "disabled" -> StateOffline
    else -> StateOffline
}

fun stateLabel(state: String): String = when (state) {
    "active" -> "活跃"
    "idle" -> "空闲"
    "offline" -> "离线"
    "up" -> "正常"
    "down" -> "异常"
    "healthy", "ok" -> "健康"
    "warning" -> "警告"
    "error" -> "错误"
    "disabled" -> "禁用"
    "not_configured" -> "未配置"
    "unauthorized" -> "未授权"
    else -> state
}

// 圆点 + 文案的状态标签
@Composable
fun StatusChip(state: String, modifier: Modifier = Modifier) {
    val c = stateColor(state)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(c)
        )
        Spacer(Modifier.width(6.dp))
        Text(stateLabel(state), color = c, fontSize = 12.sp)
    }
}

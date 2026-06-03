package com.agentmonitor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentmonitor.app.data.Alert
import com.agentmonitor.app.ui.Fmt
import com.agentmonitor.app.ui.components.stateColor
import com.agentmonitor.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    alerts: List<Alert>,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("告警", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TextPrimary)
                    }
                },
                actions = {
                    if (alerts.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Default.Delete, "清空", tint = TextSecondary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }
    ) { pad ->
        if (alerts.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("暂无告警", color = TextSecondary)
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(alerts) { a -> AlertRow(a) }
        }
    }
}

@Composable
private fun AlertRow(a: Alert) {
    val color = when (a.level) {
        "error" -> StateError
        "warn" -> StateIdle
        else -> Accent
    }
    Surface(shape = RoundedCornerShape(12.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .width(4.dp)
                    .heightIn(min = 36.dp)
                    .background(color)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(a.body, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(Fmt.ago(a.ts), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

package com.agentmonitor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentmonitor.app.data.AccountGroup
import com.agentmonitor.app.data.AccountHealth
import com.agentmonitor.app.data.AccountStatus
import com.agentmonitor.app.data.ServiceStatus
import com.agentmonitor.app.ui.Fmt
import com.agentmonitor.app.ui.theme.*

private enum class AccountDisplayMode(val label: String) {
    Issues("异常"),
    Accounts("账号"),
    Groups("分组")
}

private data class GroupSummary(
    val id: String,
    val name: String,
    val platform: String,
    val total: Int,
    val healthy: Int,
    val warning: Int,
    val error: Int,
    val disabled: Int
) {
    val state: String
        get() = when {
            error > 0 -> "error"
            warning > 0 -> "warning"
            disabled > 0 -> "disabled"
            else -> "healthy"
    }
}

private fun stateRank(state: String): Int = when (state) {
    "error" -> 0
    "warning" -> 1
    "disabled" -> 2
    "healthy" -> 3
    else -> 4
}

// sub2api / litellm 服务健康行。sub2api 会额外展示账号健康摘要。
@Composable
fun ServiceRow(svc: ServiceStatus, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = BgCard
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(svc.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Spacer(Modifier.height(2.dp))
                    val sub = if (svc.state == "up")
                        "HTTP ${svc.httpCode} · ${svc.latencyMs}ms"
                    else
                        "不可用 · ${svc.error ?: "HTTP ${svc.httpCode}"}"
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusChip(svc.state)
            }

            svc.accountHealth?.let {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = BgCardAlt)
                Spacer(Modifier.height(10.dp))
                AccountHealthBlock(it)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountHealthBlock(health: AccountHealth) {
    var mode by remember { mutableStateOf(AccountDisplayMode.Issues) }
    var query by remember { mutableStateOf("") }
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    val accounts = health.accounts
    val groups = remember(accounts) { buildGroupSummaries(accounts) }
    val selectedGroup = remember(groups, selectedGroupId) {
        groups.find { groupKey(it) == selectedGroupId }
    }
    val filteredAccounts = remember(accounts, query, selectedGroupId) {
        accounts
            .filter { account -> selectedGroupId == null || accountMatchesGroup(account, selectedGroupId.orEmpty()) }
            .filter { account ->
                val q = query.trim()
                q.isBlank() ||
                    listOf(account.name, account.platform, account.type, account.status, account.error)
                        .any { it.contains(q, ignoreCase = true) } ||
                    account.groups.any {
                        it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true)
                    }
            }
            .sortedWith(compareBy<AccountStatus> { stateRank(it.state) }.thenBy { it.name.lowercase() })
    }
    val issueAccounts = remember(filteredAccounts) { filteredAccounts.filter { it.state != "healthy" } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "账号健康",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        StatusChip(health.state)
    }
    Text(
        "admin accounts · 刷新 ${Fmt.ago(health.checkedAt)} · ${health.latencyMs}ms",
        color = TextSecondary,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(8.dp))

    if (health.state == "ok") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MetricPill("总数", health.total.toString(), "healthy")
            MetricPill("健康", health.healthy.toString(), "healthy")
            if (health.warning > 0) MetricPill("警告", health.warning.toString(), "warning")
            if (health.error > 0) MetricPill("错误", health.error.toString(), "error")
            if (health.disabled > 0) MetricPill("禁用", health.disabled.toString(), "disabled")
        }

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索账号 / 分组", color = TextSecondary) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = Accent,
                unfocusedBorderColor = BgCardAlt,
                cursorColor = Accent
            )
        )

        selectedGroup?.let { group ->
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = Accent.copy(alpha = 0.12f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("分组: ${group.name}", color = Accent, modifier = Modifier.weight(1f))
                    Text(
                        "清除",
                        color = TextSecondary,
                        modifier = Modifier.clickable { selectedGroupId = null }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        AccountModeTabs(
            selected = mode,
            issueCount = issueAccounts.size,
            accountCount = filteredAccounts.size,
            groupCount = groups.size,
            onSelected = { mode = it }
        )

        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 340.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (mode) {
                AccountDisplayMode.Issues -> {
                    if (issueAccounts.isEmpty()) {
                        item { EmptyAccountText("未发现异常账号") }
                    } else {
                        items(issueAccounts) {
                            AccountRow(it, showGroups = true)
                        }
                    }
                }
                AccountDisplayMode.Accounts -> {
                    if (filteredAccounts.isEmpty()) {
                        item { EmptyAccountText("暂无匹配账号") }
                    } else {
                        items(filteredAccounts) {
                            AccountRow(it, showGroups = true)
                        }
                    }
                }
                AccountDisplayMode.Groups -> {
                    if (groups.isEmpty()) {
                        item { EmptyAccountText("暂无分组信息") }
                    } else {
                        items(groups, key = { groupKey(it) }) { group ->
                            GroupSummaryRow(group) {
                                selectedGroupId = groupKey(group)
                                mode = AccountDisplayMode.Accounts
                            }
                        }
                    }
                }
            }
        }
    } else {
        Text(
            health.message.ifBlank { "账号接口暂不可用" },
            style = MaterialTheme.typography.bodySmall,
            color = stateColor(health.state),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AccountModeTabs(
    selected: AccountDisplayMode,
    issueCount: Int,
    accountCount: Int,
    groupCount: Int,
    onSelected: (AccountDisplayMode) -> Unit
) {
    val counts = mapOf(
        AccountDisplayMode.Issues to issueCount,
        AccountDisplayMode.Accounts to accountCount,
        AccountDisplayMode.Groups to groupCount
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AccountDisplayMode.entries.forEach { mode ->
            val active = selected == mode
            val color = if (active) Accent else TextSecondary
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelected(mode) },
                color = if (active) Accent.copy(alpha = 0.14f) else BgCardAlt,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(mode.label, color = color, fontSize = 12.sp)
                    Spacer(Modifier.width(5.dp))
                    Text((counts[mode] ?: 0).toString(), color = color, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun EmptyAccountText(text: String) {
    Text(text, color = TextSecondary, fontSize = 12.sp)
}

@Composable
private fun MetricPill(label: String, value: String, state: String) {
    val c = stateColor(state)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.width(4.dp))
        Text(value, color = c, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountRow(account: AccountStatus, showGroups: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(stateColor(account.state))
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    account.name,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stateLabel(account.state),
                    color = stateColor(account.state),
                    fontSize = 12.sp
                )
            }
            val meta = listOf(account.platform, account.type, account.status)
                .filter { it.isNotBlank() && it != "unknown" }
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, color = TextSecondary, fontSize = 12.sp, maxLines = 1)
            }
            if (showGroups && account.groups.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                AccountGroupChips(account.groups)
            }
            Text(
                accountReason(account),
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountGroupChips(groups: List<AccountGroup>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        groups.take(4).forEach { group ->
            val label = group.name.ifBlank { group.id.ifBlank { "未命名分组" } }
            Text(
                label,
                color = Accent,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Accent.copy(alpha = 0.10f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
        if (groups.size > 4) {
            Text("+${groups.size - 4}", color = TextSecondary, fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupSummaryRow(group: GroupSummary, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(stateColor(group.state))
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    group.name,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${group.total} 个",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            val meta = listOf(group.platform, group.id.takeIf { it.isNotBlank() }?.let { "ID $it" })
                .filterNotNull()
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, color = TextSecondary, fontSize = 12.sp, maxLines = 1)
            }
            Spacer(Modifier.height(5.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MetricPill("健康", group.healthy.toString(), "healthy")
                if (group.warning > 0) MetricPill("警告", group.warning.toString(), "warning")
                if (group.error > 0) MetricPill("错误", group.error.toString(), "error")
                if (group.disabled > 0) MetricPill("禁用", group.disabled.toString(), "disabled")
            }
        }
    }
}

private fun accountReason(account: AccountStatus): String = when {
    account.error.isNotBlank() -> account.error
    account.state == "healthy" -> "正常"
    account.state == "disabled" -> "账号已禁用"
    !account.schedulable -> "调度暂停"
    account.rateLimitResetAt.isNotBlank() -> "429 限流中"
    account.overloadUntil.isNotBlank() -> "上游过载保护"
    account.tempUnschedulableUntil.isNotBlank() -> "临时不可调度"
    quotaExceeded(account) -> "额度已用尽"
    else -> account.status.ifBlank { "状态异常" }
}

private fun buildGroupSummaries(accounts: List<AccountStatus>): List<GroupSummary> {
    data class MutableGroup(
        val id: String,
        val name: String,
        val platform: String,
        var total: Int = 0,
        var healthy: Int = 0,
        var warning: Int = 0,
        var error: Int = 0,
        var disabled: Int = 0
    )

    val byKey = linkedMapOf<String, MutableGroup>()
    accounts.forEach { account ->
        val groups = account.groups.ifEmpty {
            listOf(AccountGroup(id = "ungrouped", name = "未分组", platform = account.platform))
        }
        groups.forEach { group ->
            val id = group.id.ifBlank { group.name.ifBlank { "ungrouped" } }
            val name = group.name.ifBlank { if (id == "ungrouped") "未分组" else "分组 $id" }
            val item = byKey.getOrPut(id) {
                MutableGroup(
                    id = if (id == "ungrouped") "" else id,
                    name = name,
                    platform = group.platform.ifBlank { account.platform }
                )
            }
            item.total += 1
            when (account.state) {
                "healthy" -> item.healthy += 1
                "warning" -> item.warning += 1
                "error" -> item.error += 1
                "disabled" -> item.disabled += 1
            }
        }
    }

    return byKey.values
        .map {
            GroupSummary(
                id = it.id,
                name = it.name,
                platform = it.platform,
                total = it.total,
                healthy = it.healthy,
                warning = it.warning,
                error = it.error,
                disabled = it.disabled
            )
        }
        .sortedWith(compareByDescending<GroupSummary> { it.error + it.warning + it.disabled }.thenBy { it.name })
}

private fun groupKey(group: GroupSummary): String =
    group.id.ifBlank { group.name.ifBlank { "ungrouped" } }

private fun accountMatchesGroup(account: AccountStatus, key: String): Boolean {
    if (key == "ungrouped") return account.groups.isEmpty()
    return account.groups.any {
        it.id == key || (it.id.isBlank() && it.name == key)
    }
}

private fun quotaExceeded(account: AccountStatus): Boolean {
    val q = account.quota
    return (q.limit > 0 && q.used >= q.limit) ||
        (q.dailyLimit > 0 && q.dailyUsed >= q.dailyLimit) ||
        (q.weeklyLimit > 0 && q.weeklyUsed >= q.weeklyLimit)
}

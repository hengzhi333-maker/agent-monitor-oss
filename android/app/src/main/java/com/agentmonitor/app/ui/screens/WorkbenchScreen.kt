package com.agentmonitor.app.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentmonitor.app.data.HostConfig
import com.agentmonitor.app.data.MonitorRepository
import com.agentmonitor.app.data.WorkbenchAttachment
import com.agentmonitor.app.data.WorkbenchEventData
import com.agentmonitor.app.data.WorkbenchGitStatus
import com.agentmonitor.app.data.WorkbenchMessage
import com.agentmonitor.app.data.WorkbenchSession
import com.agentmonitor.app.ui.Fmt
import com.agentmonitor.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException

private const val MaxAttachmentsPerMessage = 8
private const val MaxClientAttachmentBytes = 20L * 1024L * 1024L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
    host: HostConfig?,
    sessionId: String,
    repo: MonitorRepository,
    liveEvents: List<Pair<String, WorkbenchEventData>>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(WorkbenchSession(id = sessionId)) }
    var messages by remember { mutableStateOf<List<WorkbenchMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var pendingAttachments by remember(sessionId) { mutableStateOf<List<WorkbenchAttachment>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var gitStatus by remember { mutableStateOf<WorkbenchGitStatus?>(null) }
    var gitDiff by remember { mutableStateOf<String?>(null) }
    var gitDiffTitle by remember { mutableStateOf("Git diff") }
    var seenEventCount by remember(sessionId) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    fun applyTemplate(text: String) {
        input = if (input.isBlank()) text else input.trimEnd() + "\n\n" + text
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val currentHost = host ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            try {
                val slots = MaxAttachmentsPerMessage - pendingAttachments.size
                if (slots <= 0) return@launch
                val uploaded = mutableListOf<WorkbenchAttachment>()
                for (uri in uris.take(slots)) {
                    val picked = readPickedFileMetadata(context, uri)
                    if (picked.size > MaxClientAttachmentBytes) {
                        throw IllegalArgumentException("${picked.name} 超过 20MB")
                    }
                    uploaded += repo.uploadWorkbenchAttachment(
                        currentHost,
                        sessionId,
                        picked.name,
                        picked.mime,
                        picked.asRequestBody(context)
                    ).attachment
                }
                pendingAttachments = pendingAttachments + uploaded
                error = null
            } catch (e: Exception) {
                error = e.message ?: "上传失败"
            } finally {
                uploading = false
            }
        }
    }

    suspend fun refreshWorkbenchMessages(showLoading: Boolean) {
        val currentHost = host ?: return
        if (showLoading) loading = true
        try {
            val data = repo.fetchWorkbenchMessages(currentHost, sessionId)
            session = data.session
            messages = data.messages
            error = null
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            if (showLoading) loading = false
        }
    }

    suspend fun refreshGitStatus() {
        val currentHost = host ?: return
        gitStatus = runCatching { repo.fetchWorkbenchGitStatus(currentHost, sessionId).status }.getOrNull()
    }

    suspend fun showGitDiff(cached: Boolean) {
        val currentHost = host ?: return
        val result = runCatching { repo.fetchWorkbenchGitDiff(currentHost, sessionId, cached) }.getOrNull()
        gitDiffTitle = if (cached) "Staged diff" else "Git diff"
        val body = result?.error?.takeIf { it.isNotBlank() }
            ?: result?.diff?.ifBlank { "(no diff)" }
            ?: "Unable to load git diff."
        gitDiff = if (result?.truncated == true) body + "\n\n[truncated]" else body
    }

    LaunchedEffect(host?.id, sessionId) {
        if (host == null) return@LaunchedEffect
        refreshWorkbenchMessages(showLoading = true)
        refreshGitStatus()
    }

    LaunchedEffect(liveEvents, sessionId) {
        val relevant = liveEvents.filter { it.second.sessionId == sessionId }
        val newEvents = relevant.drop(seenEventCount)
        seenEventCount = relevant.size
        for ((_, data) in newEvents) {
            data.session?.let { session = it }
            data.message?.let { msg ->
                if (messages.none { it.id == msg.id }) messages = messages + msg
            }
            if (data.state.isNotBlank()) {
                session = session.copy(state = data.state, updatedAt = data.ts)
            }
        }
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    gitDiff?.let { diff ->
        AlertDialog(
            onDismissRequest = { gitDiff = null },
            title = { Text(gitDiffTitle) },
            text = {
                SelectionContainer {
                    Text(
                        diff,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { gitDiff = null }) { Text("Close") }
            }
        )
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            session.title.ifBlank { "工作台" },
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${agentLabel(session.agentId)} · ${session.state}",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TextPrimary)
                    }
                },
                actions = {
                    if (session.state == "running" && host != null) {
                        IconButton(onClick = {
                            scope.launch {
                                try {
                                    repo.stopWorkbenchSession(host, sessionId)
                                } catch (e: Exception) {
                                    error = e.message ?: "停止失败"
                                }
                            }
                        }) {
                            Icon(Icons.Default.Stop, "停止", tint = StateError)
                        }
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
                .padding(horizontal = 12.dp)
        ) {
            Text(
                Fmt.shortPath(session.cwd),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
            WorkbenchMetaStrip(session = session, messageCount = messages.size)
            Spacer(Modifier.height(8.dp))
            gitStatus?.let { status ->
                WorkbenchGitPanel(
                    status = status,
                    onRefresh = { scope.launch { refreshGitStatus() } },
                    onDiff = { scope.launch { showGitDiff(false) } },
                    onStagedDiff = { scope.launch { showGitDiff(true) } }
                )
                Spacer(Modifier.height(8.dp))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    host == null -> WorkbenchInlineState("主机不存在")
                    loading && messages.isEmpty() -> WorkbenchInlineState("加载中...")
                    error != null && messages.isEmpty() -> WorkbenchInlineState(error.orEmpty())
                    messages.isEmpty() -> WorkbenchInlineState("暂无消息")
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 10.dp)
                    ) {
                        items(messages, key = { it.id }) { message -> WorkbenchBubble(message) }
                    }
                }
            }
            if (error != null && messages.isNotEmpty()) {
                Text(error.orEmpty(), color = StateError, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
            }
            if (pendingAttachments.isNotEmpty() || uploading) {
                WorkbenchAttachmentQueue(
                    attachments = pendingAttachments,
                    uploading = uploading,
                    onRemove = { removeId ->
                        val currentHost = host
                        pendingAttachments = pendingAttachments.filterNot { it.id == removeId }
                        if (currentHost != null) {
                            scope.launch {
                                try {
                                    repo.deleteWorkbenchAttachment(currentHost, sessionId, removeId)
                                } catch (e: Exception) {
                                    error = e.message ?: "删除附件失败"
                                }
                            }
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
            if (session.state != "running") {
                PromptTemplateStrip(onPick = ::applyTemplate)
                Spacer(Modifier.height(8.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp)
            ) {
                IconButton(
                    enabled = host != null && session.state != "running" && !uploading && pendingAttachments.size < MaxAttachmentsPerMessage,
                    onClick = { filePicker.launch(arrayOf("*/*")) }
                ) {
                    Icon(
                        Icons.Default.AttachFile,
                        "添加附件",
                        tint = if (host != null && session.state != "running" && !uploading) Accent else TextSecondary
                    )
                }
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    enabled = session.state != "running",
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    minLines = 1,
                    maxLines = 4,
                    placeholder = { Text("发给 agent", color = TextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = BgCardAlt,
                        cursorColor = Accent
                    )
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    enabled = host != null &&
                        !uploading &&
                        session.state != "running" &&
                        (input.isNotBlank() || pendingAttachments.isNotEmpty()),
                    onClick = {
                        val text = input.trim()
                        val attachmentIds = pendingAttachments.map { it.id }
                        val currentHost = host
                        if (currentHost != null) {
                            scope.launch {
                                try {
                                    repo.sendWorkbenchMessage(currentHost, sessionId, text, attachmentIds)
                                    input = ""
                                    pendingAttachments = emptyList()
                                    refreshWorkbenchMessages(showLoading = false)
                                    var attempts = 0
                                    while (session.state == "running" && attempts < 30) {
                                        delay(1000)
                                        refreshWorkbenchMessages(showLoading = false)
                                        attempts++
                                    }
                                    refreshGitStatus()
                                } catch (e: Exception) {
                                    error = e.message ?: "发送失败"
                                }
                            }
                        }
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        "发送",
                        tint = if (input.isNotBlank() || pendingAttachments.isNotEmpty()) Accent else TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkbenchMetaStrip(session: WorkbenchSession, messageCount: Int) {
    Surface(shape = RoundedCornerShape(8.dp), color = BgCardAlt, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${agentLabel(session.agentId)} · ${stateLabel(session.state)}",
                color = stateColor(session.state),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${messageCount} 条",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.width(10.dp))
            Text(
                permissionModeLabel(session.permissionMode),
                color = if (session.permissionMode == "dangerous") StateError else TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            if (session.attachmentCount > 0) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "${session.attachmentCount} 个附件",
                    color = Accent,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (session.agentSessionId.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "线程 ${session.agentSessionId.take(8)}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WorkbenchGitPanel(
    status: WorkbenchGitStatus,
    onRefresh: () -> Unit,
    onDiff: () -> Unit,
    onStagedDiff: () -> Unit
) {
    Surface(shape = RoundedCornerShape(8.dp), color = BgCardAlt, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Git", color = TextPrimary, style = MaterialTheme.typography.labelLarge)
                    Text(
                        gitSummary(status),
                        color = if (status.isRepo) TextSecondary else StateIdle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onRefresh) { Text("Refresh") }
                TextButton(enabled = status.isRepo, onClick = onDiff) { Text("Diff") }
                TextButton(enabled = status.isRepo, onClick = onStagedDiff) { Text("Staged") }
            }
            if (status.isRepo && status.statusLines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    status.statusLines.take(5).joinToString("\n"),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PromptTemplateStrip(onPick: (String) -> Unit) {
    val templates = remember {
        listOf(
            PromptTemplate("Continue", "Continue the current task. First summarize the current state, then take the next concrete step."),
            PromptTemplate("Status", "Summarize what changed, what is still open, and the next recommended action."),
            PromptTemplate("Review diff", "Review the current git diff. Focus on bugs, regressions, security risks, and missing tests."),
            PromptTemplate("Run tests", "Inspect the project and run the most relevant tests. If anything fails, diagnose and fix it."),
            PromptTemplate("Fix failure", "Diagnose the latest failure from the visible output, make the smallest safe fix, and verify it."),
            PromptTemplate("Commit msg", "Write a concise commit message and bullet summary for the current changes.")
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        templates.forEach { template ->
            AssistChip(
                onClick = { onPick(template.prompt) },
                label = { Text(template.label, maxLines = 1) }
            )
        }
    }
}

private data class PromptTemplate(
    val label: String,
    val prompt: String
)

private fun gitSummary(status: WorkbenchGitStatus): String {
    if (!status.isRepo) return status.error.ifBlank { "Not a git work tree." }
    val dirty = status.statusLines.count { !it.startsWith("##") }
    val branch = status.branch.ifBlank { "unknown branch" }
    val commit = status.lastCommit.ifBlank { "no commits" }
    return "$branch - $dirty changed file(s) - $commit"
}

@Composable
private fun WorkbenchBubble(message: WorkbenchMessage) {
    val isTool = message.role == "tool" || message.kind.contains("function", ignoreCase = true)
    val isErrorOutput = message.kind == "stderr" || message.kind == "error"
    val collapsible = isTool || isErrorOutput
    var expanded by remember(message.id) { mutableStateOf(!collapsible) }
    val color = when (message.role) {
        "user" -> Accent.copy(alpha = 0.14f)
        "assistant" -> BgCard
        "system" -> if (isErrorOutput) StateError.copy(alpha = 0.10f) else BgCardAlt
        else -> BgCardAlt
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color,
        border = if (isErrorOutput) BorderStroke(1.dp, StateError.copy(alpha = 0.35f)) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(roleLabel(message), color = roleColor(message.role), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Text(Fmt.ago(message.ts), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (collapsible) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (expanded) "收起" else "展开",
                        color = Accent,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { expanded = !expanded }
                    )
                }
            }
            if (message.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    message.attachments.forEach { attachment ->
                        AttachmentLine(attachment = attachment, removable = false, onRemove = {})
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    message.text,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (isTool || isErrorOutput) FontFamily.Monospace else FontFamily.Default,
                    maxLines = if (expanded) 1000 else 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (isTool || isErrorOutput) Modifier.background(Bg.copy(alpha = 0.28f)).padding(8.dp) else Modifier)
                )
            }
        }
    }
}

@Composable
private fun WorkbenchAttachmentQueue(
    attachments: List<WorkbenchAttachment>,
    uploading: Boolean,
    onRemove: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        attachments.forEach { attachment ->
            AttachmentLine(attachment = attachment, removable = true, onRemove = onRemove)
        }
        if (uploading) {
            LinearProgressIndicator(
                color = Accent,
                trackColor = BgCardAlt,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
            )
        }
    }
}

@Composable
private fun AttachmentLine(
    attachment: WorkbenchAttachment,
    removable: Boolean,
    onRemove: (String) -> Unit
) {
    Surface(shape = RoundedCornerShape(8.dp), color = BgCardAlt, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(start = 10.dp, end = if (removable) 2.dp else 10.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Text(
                attachmentKindLabel(attachment.kind),
                color = Accent,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(42.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    attachment.name,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatBytes(attachment.size),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (removable) {
                IconButton(onClick = { onRemove(attachment.id) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, "移除附件", tint = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun WorkbenchInlineState(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = BgCard, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = TextSecondary, modifier = Modifier.padding(14.dp))
    }
}

private fun roleLabel(message: WorkbenchMessage): String = when (message.role) {
    "user" -> "你"
    "assistant" -> "Agent"
    "tool" -> "工具"
    "system" -> if (message.kind == "stderr") "错误输出" else "系统"
    else -> message.role
}

private fun roleColor(role: String) = when (role) {
    "user" -> Accent
    "assistant" -> StateActive
    "system" -> StateError
    else -> TextSecondary
}

private fun stateLabel(state: String): String = when (state) {
    "running" -> "运行中"
    "idle" -> "空闲"
    "error" -> "错误"
    "stopped" -> "已停止"
    else -> state.ifBlank { "未知" }
}

private fun stateColor(state: String) = when (state) {
    "running" -> StateActive
    "error" -> StateError
    "stopped" -> StateIdle
    else -> TextSecondary
}

private fun permissionModeLabel(mode: String): String = when (mode) {
    "read-only" -> "只读"
    "dangerous" -> "高权限"
    else -> "普通"
}

private fun agentLabel(agentId: String): String = when (agentId) {
    "claude-code" -> "Claude Code"
    "codex" -> "Codex"
    else -> agentId.ifBlank { "Agent" }
}

private data class PickedWorkbenchFile(
    val name: String,
    val mime: String,
    val size: Long,
    val uri: Uri
)

private suspend fun readPickedFileMetadata(context: Context, uri: Uri): PickedWorkbenchFile =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val metadata = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                name to size
            } else null
        }
        val name = metadata?.first?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
            ?: "attachment"
        val declaredSize = metadata?.second ?: -1L
        if (declaredSize > MaxClientAttachmentBytes) {
            throw IllegalArgumentException("$name 超过 20MB")
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        PickedWorkbenchFile(name = name, mime = mime, size = declaredSize, uri = uri)
    }

private fun PickedWorkbenchFile.asRequestBody(context: Context): RequestBody =
    object : RequestBody() {
        override fun contentType() = mime.ifBlank { "application/octet-stream" }.toMediaTypeOrNull()
        override fun contentLength(): Long = if (size >= 0) size else -1L

        override fun writeTo(sink: BufferedSink) {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取 $name")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            input.use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                }
            }
        }
    }

private fun attachmentKindLabel(kind: String): String = when (kind) {
    "image" -> "图片"
    "text" -> "文本"
    "code" -> "代码"
    "pdf" -> "PDF"
    "word" -> "Word"
    "spreadsheet" -> "表格"
    else -> "文件"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

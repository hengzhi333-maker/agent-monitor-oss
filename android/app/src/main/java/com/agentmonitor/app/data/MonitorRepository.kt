package com.agentmonitor.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// 与单台 daemon 通信:REST 拉快照 + WebSocket 实时流。
class MonitorRepository {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // 一次性拉取快照(REST)
    suspend fun fetchSnapshot(host: HostConfig): Snapshot =
        getJson(host, "/snapshot", Snapshot.serializer())

    suspend fun fetchHistory(host: HostConfig): HistoryResponse =
        getJson(host, "/history?samples=80&events=80", HistoryResponse.serializer())

    suspend fun fetchAgentSessions(host: HostConfig, agentId: String): AgentSessionsResponse =
        getJson(host, "/agents/${path(agentId)}/sessions?limit=80", AgentSessionsResponse.serializer())

    suspend fun fetchConversation(
        host: HostConfig,
        agentId: String,
        sessionId: String
    ): ConversationResponse =
        getJson(
            host,
            "/agents/${path(agentId)}/sessions/${path(sessionId)}/messages?limit=180",
            ConversationResponse.serializer()
        )

    suspend fun fetchWorkbenchSessions(host: HostConfig, includeArchived: Boolean = false): WorkbenchSessionsResponse =
        getJson(
            host,
            "/workbench/sessions${if (includeArchived) "?includeArchived=1" else ""}",
            WorkbenchSessionsResponse.serializer()
        )

    suspend fun fetchWorkbenchAttachments(host: HostConfig): WorkbenchAttachmentsResponse =
        getJson(host, "/workbench/attachments", WorkbenchAttachmentsResponse.serializer())

    suspend fun fetchDiagnostics(host: HostConfig): DiagnosticsResponse =
        getJson(host, "/diagnostics", DiagnosticsResponse.serializer())

    suspend fun fetchVersion(host: HostConfig): VersionResponse =
        getJson(host, "/version", VersionResponse.serializer())

    suspend fun fetchDiagnosticPackage(host: HostConfig): String =
        getRaw(host, "/diagnostics/package")

    suspend fun fetchSecurityStatus(host: HostConfig): SecurityStatusResponse =
        getJson(host, "/security/status", SecurityStatusResponse.serializer())

    suspend fun fetchDevices(host: HostConfig): DevicesResponse =
        getJson(host, "/devices", DevicesResponse.serializer())

    suspend fun createDevice(host: HostConfig, name: String, role: String): DeviceCreateResponse =
        postJson(
            host,
            "/devices",
            DeviceCreateRequest(name = name, role = role),
            DeviceCreateRequest.serializer(),
            DeviceCreateResponse.serializer()
        )

    suspend fun updateDevice(host: HostConfig, id: String, request: DeviceUpdateRequest): DeviceResponse =
        patchJson(
            host,
            "/devices/${path(id)}",
            request,
            DeviceUpdateRequest.serializer(),
            DeviceResponse.serializer()
        )

    suspend fun rotateDevice(host: HostConfig, id: String): DeviceRotateResponse =
        postJson(
            host,
            "/devices/${path(id)}/rotate",
            WorkbenchMessageRequest(""),
            WorkbenchMessageRequest.serializer(),
            DeviceRotateResponse.serializer()
        )

    suspend fun deleteDevice(host: HostConfig, id: String): DeviceDeleteResponse =
        deleteJson(host, "/devices/${path(id)}", DeviceDeleteResponse.serializer())

    suspend fun fetchSetupProfile(host: HostConfig, includeToken: Boolean = true): HostSetupResponse =
        getJson(
            host,
            "/setup/profile?includeToken=${if (includeToken) "1" else "0"}",
            HostSetupResponse.serializer()
        )

    suspend fun setRemoteControlEnabled(host: HostConfig, enabled: Boolean): SecurityStatusResponse =
        postJson(
            host,
            "/security/remote-control",
            SecurityRemoteControlRequest(enabled),
            SecurityRemoteControlRequest.serializer(),
            SecurityStatusResponse.serializer()
        )

    suspend fun rotateToken(host: HostConfig): TokenRotateResponse =
        postJson(
            host,
            "/security/token/rotate",
            TokenRotateRequest(),
            TokenRotateRequest.serializer(),
            TokenRotateResponse.serializer()
        )

    suspend fun createWorkbenchSession(
        host: HostConfig,
        agentId: String,
        cwd: String,
        title: String,
        permissionMode: String = "standard"
    ): WorkbenchSessionResponse =
        postJson(
            host,
            "/workbench/sessions",
            WorkbenchCreateRequest(agentId = agentId, cwd = cwd, title = title, permissionMode = permissionMode),
            WorkbenchCreateRequest.serializer(),
            WorkbenchSessionResponse.serializer()
        )

    suspend fun fetchWorkbenchMessages(host: HostConfig, sessionId: String): WorkbenchMessagesResponse =
        getJson(host, "/workbench/sessions/${path(sessionId)}/messages", WorkbenchMessagesResponse.serializer())

    suspend fun fetchWorkbenchGitStatus(host: HostConfig, sessionId: String): WorkbenchGitStatusResponse =
        getJson(host, "/workbench/sessions/${path(sessionId)}/git/status", WorkbenchGitStatusResponse.serializer())

    suspend fun fetchWorkbenchGitDiff(
        host: HostConfig,
        sessionId: String,
        cached: Boolean = false
    ): WorkbenchGitDiffResponse =
        getJson(
            host,
            "/workbench/sessions/${path(sessionId)}/git/diff?cached=${if (cached) "true" else "false"}",
            WorkbenchGitDiffResponse.serializer()
        )

    suspend fun sendWorkbenchMessage(
        host: HostConfig,
        sessionId: String,
        text: String,
        attachmentIds: List<String> = emptyList()
    ): WorkbenchAcceptedResponse =
        postJson(
            host,
            "/workbench/sessions/${path(sessionId)}/messages",
            WorkbenchMessageRequest(text = text, attachmentIds = attachmentIds),
            WorkbenchMessageRequest.serializer(),
            WorkbenchAcceptedResponse.serializer()
        )

    suspend fun uploadWorkbenchAttachment(
        host: HostConfig,
        sessionId: String,
        name: String,
        mime: String,
        body: RequestBody
    ): WorkbenchAttachmentResponse =
        postRaw(
            host,
            "/workbench/sessions/${path(sessionId)}/attachments?name=${path(name)}&mime=${path(mime)}",
            body,
            WorkbenchAttachmentResponse.serializer()
        )

    suspend fun deleteWorkbenchAttachment(
        host: HostConfig,
        sessionId: String,
        attachmentId: String
    ): WorkbenchAttachmentDeleteResponse =
        deleteJson(
            host,
            "/workbench/sessions/${path(sessionId)}/attachments/${path(attachmentId)}",
            WorkbenchAttachmentDeleteResponse.serializer()
        )

    suspend fun cleanupWorkbenchAttachments(host: HostConfig, all: Boolean = false): WorkbenchAttachmentCleanupResponse =
        postJson(
            host,
            "/workbench/attachments/cleanup",
            WorkbenchAttachmentCleanupRequest(all = all),
            WorkbenchAttachmentCleanupRequest.serializer(),
            WorkbenchAttachmentCleanupResponse.serializer()
        )

    suspend fun stopWorkbenchSession(host: HostConfig, sessionId: String): WorkbenchStopResponse =
        postJson(
            host,
            "/workbench/sessions/${path(sessionId)}/stop",
            WorkbenchMessageRequest(""),
            WorkbenchMessageRequest.serializer(),
            WorkbenchStopResponse.serializer()
        )

    suspend fun archiveWorkbenchSession(host: HostConfig, sessionId: String): WorkbenchSessionResponse =
        postJson(
            host,
            "/workbench/sessions/${path(sessionId)}/archive",
            WorkbenchMessageRequest(""),
            WorkbenchMessageRequest.serializer(),
            WorkbenchSessionResponse.serializer()
        )

    suspend fun unarchiveWorkbenchSession(host: HostConfig, sessionId: String): WorkbenchSessionResponse =
        postJson(
            host,
            "/workbench/sessions/${path(sessionId)}/unarchive",
            WorkbenchMessageRequest(""),
            WorkbenchMessageRequest.serializer(),
            WorkbenchSessionResponse.serializer()
        )

    suspend fun deleteWorkbenchSession(host: HostConfig, sessionId: String): WorkbenchDeleteResponse =
        deleteJson(host, "/workbench/sessions/${path(sessionId)}", WorkbenchDeleteResponse.serializer())

    private suspend fun <T> getJson(
        host: HostConfig,
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>
    ): T =
        suspendCancellableCoroutine { cont ->
            val req = Request.Builder()
                .url("${host.baseUrl}$path")
                .header("Authorization", "Bearer ${host.token}")
                .build()
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            cont.resumeWithException(httpException(it.code, body))
                            return
                        }
                        try {
                            cont.resume(json.decodeFromString(serializer, body))
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }
                }
            })
        }

    // 探测 daemon 是否在线(/ping 免鉴权)
    private suspend fun getRaw(host: HostConfig, path: String): String =
        suspendCancellableCoroutine { cont ->
            val req = Request.Builder()
                .url("${host.baseUrl}$path")
                .header("Authorization", "Bearer ${host.token}")
                .build()
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            cont.resumeWithException(httpException(it.code, body))
                        } else {
                            cont.resume(body)
                        }
                    }
                }
            })
        }

    private suspend fun <Req, Res> postJson(
        host: HostConfig,
        path: String,
        body: Req,
        requestSerializer: kotlinx.serialization.KSerializer<Req>,
        responseSerializer: kotlinx.serialization.KSerializer<Res>
    ): Res =
        suspendCancellableCoroutine { cont ->
            val encoded = json.encodeToString(requestSerializer, body)
            val req = Request.Builder()
                .url("${host.baseUrl}$path")
                .header("Authorization", "Bearer ${host.token}")
                .post(encoded.toRequestBody(jsonMediaType))
                .build()
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val raw = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            cont.resumeWithException(httpException(it.code, raw))
                            return
                        }
                        try {
                            cont.resume(json.decodeFromString(responseSerializer, raw))
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }
                }
            })
        }

    private suspend fun <Req, Res> patchJson(
        host: HostConfig,
        path: String,
        body: Req,
        requestSerializer: kotlinx.serialization.KSerializer<Req>,
        responseSerializer: kotlinx.serialization.KSerializer<Res>
    ): Res =
        suspendCancellableCoroutine { cont ->
            val encoded = json.encodeToString(requestSerializer, body)
            val req = Request.Builder()
                .url("${host.baseUrl}$path")
                .header("Authorization", "Bearer ${host.token}")
                .patch(encoded.toRequestBody(jsonMediaType))
                .build()
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val raw = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            cont.resumeWithException(httpException(it.code, raw))
                            return
                        }
                        try {
                            cont.resume(json.decodeFromString(responseSerializer, raw))
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }
                }
            })
        }

    private suspend fun <Res> postRaw(
        host: HostConfig,
        path: String,
        body: RequestBody,
        responseSerializer: kotlinx.serialization.KSerializer<Res>
    ): Res =
        suspendCancellableCoroutine { cont ->
            val req = Request.Builder()
                .url("${host.baseUrl}$path")
                .header("Authorization", "Bearer ${host.token}")
                .post(body)
                .build()
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val raw = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            cont.resumeWithException(httpException(it.code, raw))
                            return
                        }
                        try {
                            cont.resume(json.decodeFromString(responseSerializer, raw))
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }
                }
            })
        }

    private suspend fun <Res> deleteJson(
        host: HostConfig,
        path: String,
        responseSerializer: kotlinx.serialization.KSerializer<Res>
    ): Res =
        suspendCancellableCoroutine { cont ->
            val req = Request.Builder()
                .url("${host.baseUrl}$path")
                .header("Authorization", "Bearer ${host.token}")
                .delete()
                .build()
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val raw = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            cont.resumeWithException(httpException(it.code, raw))
                            return
                        }
                        try {
                            cont.resume(json.decodeFromString(responseSerializer, raw))
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }
                }
            })
        }

    suspend fun ping(host: HostConfig): Boolean =
        suspendCancellableCoroutine { cont ->
            val req = Request.Builder().url("${host.baseUrl}/ping").build()
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resume(false)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use { if (cont.isActive) cont.resume(it.isSuccessful) }
                }
            })
        }

    suspend fun probeWebSocket(host: HostConfig): Boolean =
        suspendCancellableCoroutine { cont ->
            val finished = AtomicBoolean(false)
            val req = Request.Builder()
                .url(host.wsUrl)
                .header("Authorization", "Bearer ${host.token}")
                .build()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (finished.compareAndSet(false, true) && cont.isActive) {
                        cont.resume(true)
                    }
                    webSocket.close(1000, "diagnostic complete")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (finished.compareAndSet(false, true) && cont.isActive) {
                        cont.resume(false)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (finished.compareAndSet(false, true) && cont.isActive) {
                        cont.resume(false)
                    }
                }
            }
            val ws = client.newWebSocket(req, listener)
            cont.invokeOnCancellation { ws.cancel() }
        }

    // WebSocket 实时事件流。发出 ConnEvent 序列。
    fun stream(host: HostConfig): Flow<ConnEvent> = callbackFlow {
        val req = Request.Builder()
            .url(host.wsUrl)
            .header("Authorization", "Bearer ${host.token}")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(ConnEvent.Open)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val env = json.decodeFromString<WsEnvelope>(text)
                    when (env.type) {
                        "snapshot" -> env.data?.let {
                            trySend(ConnEvent.SnapshotMsg(json.decodeFromJsonElement(Snapshot.serializer(), it)))
                        }
                        "alert" -> env.data?.let {
                            trySend(ConnEvent.AlertMsg(json.decodeFromJsonElement(Alert.serializer(), it)))
                        }
                        else -> if (env.type.startsWith("workbench.")) env.data?.let {
                            trySend(ConnEvent.WorkbenchMsg(env.type, json.decodeFromJsonElement(WorkbenchEventData.serializer(), it)))
                        }
                    }
                } catch (e: Exception) {
                    trySend(ConnEvent.Closed("实时消息解析失败: ${e.message ?: "unknown"}"))
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(ConnEvent.Closed(connectionFailureMessage(t, response)))
                close()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                trySend(ConnEvent.Closed(if (reason.isBlank()) "WebSocket 已关闭: $code" else reason))
                close()
            }
        }

        val ws = client.newWebSocket(req, listener)
        awaitClose { ws.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun path(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun httpException(code: Int, raw: String): RuntimeException {
        val error = parseDaemonError(raw)
        return RuntimeException(readableHttpMessage(code, error))
    }

    private fun parseDaemonError(raw: String): DaemonError {
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            root["error"]?.let { error ->
                val obj = runCatching { error.jsonObject }.getOrNull()
                val code = obj?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
                val message = obj?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: runCatching { error.jsonPrimitive.contentOrNull }.getOrNull()
                    ?: ""
                DaemonError(code = code, message = message)
            }
        }.getOrNull() ?: DaemonError()
    }

    private fun readableHttpMessage(code: Int, error: DaemonError): String {
        val detail = error.message.ifBlank { error.code }
        return when (code) {
            401 -> "Token 不正确或已过期 (HTTP 401)"
            403 -> if (error.code == "REMOTE_ADDRESS_NOT_ALLOWED") {
                "当前手机地址不在 daemon allowlist 中，请检查 Tailscale/LAN 地址 (HTTP 403)"
            } else {
                "daemon 拒绝访问${suffix(detail)} (HTTP 403)"
            }
            413 -> "请求体过大${suffix(detail)} (HTTP 413)"
            else -> if (detail.isBlank()) "HTTP $code" else "HTTP $code: $detail"
        }
    }

    private fun connectionFailureMessage(t: Throwable, response: Response?): String {
        val code = response?.code
        return when (code) {
            401 -> "WebSocket Token 不正确或已过期 (HTTP 401)"
            403 -> "WebSocket 被 daemon allowlist 拒绝，请检查手机 Tailscale/LAN 地址 (HTTP 403)"
            null -> t.message ?: "连接失败，请确认 daemon 已启动且地址可达"
            else -> "WebSocket 连接失败 (HTTP $code): ${t.message ?: "connection failed"}"
        }
    }

    private fun suffix(detail: String): String =
        if (detail.isBlank()) "" else ": $detail"

    private data class DaemonError(
        val code: String = "",
        val message: String = ""
    )
}

sealed interface ConnEvent {
    data object Open : ConnEvent
    data class SnapshotMsg(val snapshot: Snapshot) : ConnEvent
    data class AlertMsg(val alert: Alert) : ConnEvent
    data class WorkbenchMsg(val type: String, val data: WorkbenchEventData) : ConnEvent
    data class Closed(val reason: String) : ConnEvent
}

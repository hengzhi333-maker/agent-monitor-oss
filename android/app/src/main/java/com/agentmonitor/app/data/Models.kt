package com.agentmonitor.app.data

import kotlinx.serialization.Serializable

// 与 daemon 的 snapshot JSON 一一对应。所有字段给默认值,容忍不同 agent 的字段差异。

@Serializable
data class Snapshot(
    val host: String = "",
    val ts: Long = 0,
    val agents: List<AgentStatus> = emptyList(),
    val services: List<ServiceStatus> = emptyList()
)

@Serializable
data class AgentStatus(
    val id: String = "",
    val name: String = "",
    val kind: String = "",
    val state: String = "offline",
    val lastActivity: Long = 0,
    val summary: String = "",
    val metrics: Metrics = Metrics(),
    val detail: Detail? = null,
    val sessions: List<Session> = emptyList()
)

@Serializable
data class Metrics(
    val tokensToday: Tokens = Tokens(),
    val sessionsToday: Int = 0,
    val model: String = "",
    val processes: Int = 0
)

@Serializable
data class Tokens(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheCreate: Long = 0
)

@Serializable
data class Detail(
    val status: String = "",
    val statusUpdated: Long = 0
)

@Serializable
data class Session(
    val id: String = "",
    val title: String = "",
    val cwd: String = "",
    val model: String = "",
    val lastActivity: Long = 0,
    val tokens: Tokens = Tokens(),
    val messageCount: Int = 0
)

@Serializable
data class AgentSessionsResponse(
    val agentId: String = "",
    val sessions: List<Session> = emptyList()
)

@Serializable
data class ConversationResponse(
    val agentId: String = "",
    val session: Session = Session(),
    val updatedAt: Long = 0,
    val messages: List<ConversationMessage> = emptyList()
)

@Serializable
data class ConversationMessage(
    val id: String = "",
    val role: String = "",
    val kind: String = "",
    val title: String = "",
    val ts: Long = 0,
    val text: String = ""
)

@Serializable
data class ServiceStatus(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val state: String = "down",
    val httpCode: Int = 0,
    val latencyMs: Long = 0,
    val error: String? = null,
    val accountHealth: AccountHealth? = null
)

@Serializable
data class AccountHealth(
    val state: String = "",
    val total: Int = 0,
    val healthy: Int = 0,
    val warning: Int = 0,
    val error: Int = 0,
    val disabled: Int = 0,
    val checkedAt: Long = 0,
    val latencyMs: Long = 0,
    val message: String = "",
    val accounts: List<AccountStatus> = emptyList()
)

@Serializable
data class AccountStatus(
    val id: String = "",
    val name: String = "",
    val platform: String = "",
    val type: String = "",
    val status: String = "",
    val state: String = "",
    val schedulable: Boolean = true,
    val error: String = "",
    val rateLimitResetAt: String = "",
    val overloadUntil: String = "",
    val tempUnschedulableUntil: String = "",
    val groups: List<AccountGroup> = emptyList(),
    val quota: AccountQuota = AccountQuota()
)

@Serializable
data class AccountGroup(
    val id: String = "",
    val name: String = "",
    val platform: String = "",
    val status: String = ""
)

@Serializable
data class AccountQuota(
    val used: Long = 0,
    val limit: Long = 0,
    val dailyUsed: Long = 0,
    val dailyLimit: Long = 0,
    val weeklyUsed: Long = 0,
    val weeklyLimit: Long = 0
)

@Serializable
data class Alert(
    val level: String = "info",
    val agent: String = "",
    val title: String = "",
    val body: String = "",
    val ts: Long = 0
)

@Serializable
data class HistoryResponse(
    val samples: List<HistorySample> = emptyList(),
    val events: List<HistoryEvent> = emptyList(),
    val trend: List<HistoryTrendPoint> = emptyList()
)

@Serializable
data class HistorySample(
    val ts: Long = 0,
    val host: String = "",
    val agentCounts: Map<String, Int> = emptyMap(),
    val serviceCounts: Map<String, Int> = emptyMap(),
    val totals: HistoryTotals = HistoryTotals(),
    val agents: List<HistoryAgentSample> = emptyList(),
    val services: List<HistoryServiceSample> = emptyList(),
    val alertCount: Int = 0
)

@Serializable
data class HistoryTotals(
    val sessionsToday: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreateTokens: Long = 0
)

@Serializable
data class HistoryTrendPoint(
    val ts: Long = 0,
    val onlineAgents: Int = 0,
    val offlineAgents: Int = 0,
    val downServices: Int = 0,
    val sessionsToday: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheTokens: Long = 0,
    val alertCount: Int = 0
)

@Serializable
data class HistoryAgentSample(
    val id: String = "",
    val name: String = "",
    val state: String = "unknown",
    val lastActivity: Long = 0,
    val summary: String = ""
)

@Serializable
data class HistoryServiceSample(
    val id: String = "",
    val name: String = "",
    val state: String = "unknown",
    val httpCode: Int = 0,
    val latencyMs: Long = 0,
    val error: String = ""
)

@Serializable
data class HistoryEvent(
    val id: String = "",
    val kind: String = "",
    val sourceId: String = "",
    val level: String = "info",
    val title: String = "",
    val body: String = "",
    val ts: Long = 0
)

// WebSocket 信封
@Serializable
data class WsEnvelope(
    val type: String = "",
    val data: kotlinx.serialization.json.JsonElement? = null
)

@Serializable
data class WorkbenchSession(
    val id: String = "",
    val agentId: String = "",
    val title: String = "",
    val cwd: String = "",
    val permissionMode: String = "standard",
    val state: String = "idle",
    val agentSessionId: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val archived: Boolean = false,
    val dangerousExpiresAt: Long = 0,
    val lastError: String = "",
    val attachmentCount: Int = 0
)

@Serializable
data class WorkbenchSessionsResponse(
    val sessions: List<WorkbenchSession> = emptyList()
)

@Serializable
data class WorkbenchCreateRequest(
    val agentId: String,
    val cwd: String = "",
    val title: String = "",
    val permissionMode: String = "standard"
)

@Serializable
data class WorkbenchSessionResponse(
    val session: WorkbenchSession = WorkbenchSession()
)

@Serializable
data class WorkbenchMessageRequest(
    val text: String,
    val attachmentIds: List<String> = emptyList()
)

@Serializable
data class WorkbenchAttachment(
    val id: String = "",
    val name: String = "",
    val mime: String = "",
    val kind: String = "",
    val size: Long = 0,
    val status: String = "",
    val textPreview: String = "",
    val createdAt: Long = 0
)

@Serializable
data class WorkbenchAttachmentResponse(
    val attachment: WorkbenchAttachment = WorkbenchAttachment()
)

@Serializable
data class WorkbenchAttachmentDeleteResponse(
    val deleted: Boolean = false,
    val sessionId: String = "",
    val attachmentId: String = ""
)

@Serializable
data class WorkbenchAttachmentCleanupRequest(
    val all: Boolean = false,
    val ttlHours: Int? = null
)

@Serializable
data class WorkbenchAttachmentCleanupResponse(
    val removedAttachments: Int = 0,
    val ttlHours: Int = 0
)

@Serializable
data class WorkbenchAcceptedResponse(
    val accepted: Boolean = false,
    val sessionId: String = "",
    val turnId: String = ""
)

@Serializable
data class WorkbenchStopResponse(
    val stopped: Boolean = false,
    val sessionId: String = ""
)

@Serializable
data class WorkbenchDeleteResponse(
    val deleted: Boolean = false,
    val sessionId: String = ""
)

@Serializable
data class WorkbenchMessagesResponse(
    val session: WorkbenchSession = WorkbenchSession(),
    val messages: List<WorkbenchMessage> = emptyList()
)

@Serializable
data class WorkbenchMessage(
    val id: String = "",
    val turnId: String = "",
    val role: String = "",
    val kind: String = "",
    val text: String = "",
    val ts: Long = 0,
    val attachments: List<WorkbenchAttachment> = emptyList()
)

@Serializable
data class WorkbenchEventData(
    val sessionId: String = "",
    val turnId: String = "",
    val agentId: String = "",
    val state: String = "",
    val text: String = "",
    val stream: String = "",
    val message: WorkbenchMessage? = null,
    val session: WorkbenchSession? = null,
    val ts: Long = 0
)

@Serializable
data class DiagnosticsResponse(
    val host: String = "",
    val port: Int = 0,
    val bindHost: String = "",
    val requestHost: String = "",
    val remoteAddress: String = "",
    val uptimeSec: Long = 0,
    val platform: String = "",
    val tailscale: DiagnosticsTailscale = DiagnosticsTailscale(),
    val remoteAccess: DiagnosticsRemoteAccess = DiagnosticsRemoteAccess(),
    val commands: DiagnosticsCommands = DiagnosticsCommands(),
    val workbench: DiagnosticsWorkbench = DiagnosticsWorkbench(),
    val alertRules: AlertRules = AlertRules()
)

@Serializable
data class VersionResponse(
    val name: String = "",
    val version: String = "",
    val apiVersion: Int = 0,
    val build: String = "",
    val node: String = "",
    val startedAt: String = ""
)

@Serializable
data class DiagnosticsTailscale(
    val targetLooksLikeTailnet: Boolean = false,
    val bindLooksLikeTailnet: Boolean = false,
    val hint: String = ""
)

@Serializable
data class DiagnosticsRemoteAccess(
    val configured: Boolean = false,
    val allowed: Boolean = false,
    val remoteAddress: String = "",
    val allowedRemoteAddresses: List<String> = emptyList()
)

@Serializable
data class DiagnosticsCommand(
    val command: String = "",
    val found: Boolean = false
)

@Serializable
data class DiagnosticsCommands(
    val git: DiagnosticsCommand = DiagnosticsCommand(),
    val codex: DiagnosticsCommand = DiagnosticsCommand(),
    val claudeCode: DiagnosticsCommand = DiagnosticsCommand()
)

@Serializable
data class DiagnosticsWorkbench(
    val enabled: Boolean = false,
    val tokenRoles: List<TokenRoleSummary> = emptyList(),
    val defaultPermissionMode: String = "standard",
    val allowDangerousPermissions: Boolean = false,
    val allowedRemoteAddresses: List<String> = emptyList(),
    val allowedCwds: List<String> = emptyList(),
    val maxSessions: Int = 0,
    val maxOutputChars: Int = 0,
    val dangerousSessionTtlMs: Long = 0,
    val attachmentTtlHours: Int = 0,
    val maxRawUploadBytes: Long = 0,
    val maxAttachmentsPerMessage: Int = 0,
    val maxSessionAttachments: Int = 0
)

@Serializable
data class AlertRules(
    val agentOfflineGraceMs: Long = 0,
    val serviceFailureCount: Int = 1,
    val recoveryNotifications: Boolean = true,
    val cooldownMs: Long = 0,
    val quietHours: AlertQuietHours = AlertQuietHours()
)

@Serializable
data class AlertQuietHours(
    val enabled: Boolean = false,
    val start: String = "22:00",
    val end: String = "08:00",
    val timezoneOffsetMinutes: Int = 0,
    val suppressBelow: String = "error"
)

@Serializable
data class SecurityStatusResponse(
    val host: String = "",
    val port: Int = 0,
    val bindHost: String = "",
    val remoteControl: SecurityRemoteControl = SecurityRemoteControl(),
    val privacy: SecurityPrivacy = SecurityPrivacy(),
    val uploadLimits: SecurityUploadLimits = SecurityUploadLimits(),
    val alertRules: AlertRules = AlertRules(),
    val audit: SecurityAudit = SecurityAudit(),
    val checks: List<SecurityCheck> = emptyList()
)

@Serializable
data class SecurityRemoteControl(
    val enabled: Boolean = false,
    val tokenRoles: List<TokenRoleSummary> = emptyList(),
    val allowDangerousPermissions: Boolean = false,
    val defaultPermissionMode: String = "standard",
    val allowedRemoteAddresses: List<String> = emptyList(),
    val allowedCwds: List<String> = emptyList(),
    val maxSessions: Int = 0,
    val maxOutputChars: Int = 0,
    val dangerousSessionTtlMs: Long = 0,
    val attachmentTtlHours: Int = 0
)

@Serializable
data class TokenRoleSummary(
    val name: String = "",
    val role: String = "admin",
    val tokenPreview: String = ""
)

@Serializable
data class SecurityAudit(
    val path: String = "",
    val recent: List<AuditEntry> = emptyList()
)

@Serializable
data class AuditEntry(
    val ts: Long = 0,
    val action: String = "",
    val host: String = "",
    val remoteAddress: String = "",
    val ok: Boolean = true,
    val method: String = "",
    val path: String = "",
    val errorCode: String = "",
    val sessionId: String = "",
    val agentId: String = "",
    val cwd: String = "",
    val permissionMode: String = "",
    val name: String = "",
    val mime: String = "",
    val kind: String = "",
    val size: Long = 0,
    val textLength: Int = 0,
    val attachmentCount: Int = 0,
    val attachmentId: String = "",
    val removedAttachments: Int = 0,
    val all: Boolean = false,
    val enabled: Boolean = false
)

@Serializable
data class SecurityPrivacy(
    val maskAccountEmails: Boolean = false
)

@Serializable
data class SecurityUploadLimits(
    val maxRawUploadBytes: Long = 0,
    val maxAttachmentsPerMessage: Int = 0,
    val maxSessionAttachments: Int = 0,
    val attachmentTtlHours: Int = 0
)

@Serializable
data class SecurityCheck(
    val id: String = "",
    val state: String = "",
    val title: String = "",
    val detail: String = "",
    val fix: String = ""
)

@Serializable
data class SecurityRemoteControlRequest(
    val enabled: Boolean
)

@Serializable
data class TokenRotateRequest(
    val rotate: Boolean = true
)

@Serializable
data class TokenRotateResponse(
    val token: String = "",
    val status: SecurityStatusResponse = SecurityStatusResponse()
)

@Serializable
data class DevicesResponse(
    val devices: List<DeviceInfo> = emptyList()
)

@Serializable
data class DeviceInfo(
    val id: String = "",
    val name: String = "",
    val role: String = "read-only",
    val enabled: Boolean = true,
    val tokenPreview: String = "",
    val lastSeen: Long = 0,
    val remoteAddress: String = "",
    val userAgent: String = ""
)

@Serializable
data class DeviceCreateRequest(
    val name: String = "",
    val role: String = "read-only"
)

@Serializable
data class DeviceCreateResponse(
    val token: String = "",
    val device: DeviceInfo = DeviceInfo()
)

@Serializable
data class DeviceUpdateRequest(
    val name: String? = null,
    val role: String? = null,
    val enabled: Boolean? = null
)

@Serializable
data class DeviceResponse(
    val device: DeviceInfo = DeviceInfo()
)

@Serializable
data class DeviceRotateResponse(
    val token: String = "",
    val device: DeviceInfo = DeviceInfo()
)

@Serializable
data class DeviceDeleteResponse(
    val deleted: Boolean = false,
    val id: String = ""
)

@Serializable
data class WorkbenchAttachmentIndex(
    val id: String = "",
    val name: String = "",
    val mime: String = "",
    val kind: String = "",
    val size: Long = 0,
    val status: String = "",
    val textPreview: String = "",
    val createdAt: Long = 0,
    val sessionId: String = "",
    val sessionTitle: String = "",
    val agentId: String = ""
)

@Serializable
data class WorkbenchAttachmentsResponse(
    val attachments: List<WorkbenchAttachmentIndex> = emptyList()
)

@Serializable
data class WorkbenchGitStatusResponse(
    val sessionId: String = "",
    val status: WorkbenchGitStatus = WorkbenchGitStatus()
)

@Serializable
data class WorkbenchGitStatus(
    val isRepo: Boolean = false,
    val branch: String = "",
    val root: String = "",
    val statusLines: List<String> = emptyList(),
    val diffStat: String = "",
    val stagedDiffStat: String = "",
    val lastCommit: String = "",
    val error: String = ""
)

@Serializable
data class WorkbenchGitDiffResponse(
    val sessionId: String = "",
    val diff: String = "",
    val truncated: Boolean = false,
    val cached: Boolean = false,
    val error: String = ""
)

@Serializable
data class HostSetupResponse(
    val profile: HostSetupProfile = HostSetupProfile(),
    val uri: String = ""
)

@Serializable
data class HostSetupProfile(
    val format: String = "",
    val id: String = "",
    val identityKey: String = "",
    val name: String = "",
    val address: String = "",
    val port: Int = 8765,
    val secure: Boolean = false,
    val token: String = "",
    val hints: HostSetupHints = HostSetupHints()
)

@Serializable
data class HostSetupHints(
    val tailscale: HostSetupTailscale = HostSetupTailscale(),
    val lan: HostSetupLan = HostSetupLan(),
    val usb: HostSetupUsb = HostSetupUsb()
)

@Serializable
data class HostSetupTailscale(
    val magicDnsName: String = "",
    val ips: List<String> = emptyList()
)

@Serializable
data class HostSetupLan(
    val ips: List<String> = emptyList()
)

@Serializable
data class HostSetupUsb(
    val address: String = "127.0.0.1",
    val adbReverse: String = ""
)

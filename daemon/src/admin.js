import crypto from 'crypto';
import os from 'os';
import { config, persistConfigPatch } from './config.js';
import { auditLogPath, readAuditLog } from './auditLog.js';
import { isTailscaleAddress, normalizeRemoteAddress, remoteAccessState } from './accessControl.js';
import { historyStorageInfo } from './history.js';
import { inspectCommand } from './lib/commands.js';
import { logStatus } from './lib/logger.js';
import { resolveAgentCommand } from './workbench.js';
import {
  MAX_ATTACHMENTS_PER_MESSAGE,
  MAX_RAW_UPLOAD_BYTES,
  MAX_SESSION_ATTACHMENTS,
} from './workbenchAttachments.js';

export function buildDiagnostics(req) {
  const hostHeader = req.headers.host || '';
  const remoteAddress = normalizeRemoteAddress(req.socket?.remoteAddress || '');
  const targetHost = hostHeader.split(':')[0] || '';
  const tailscaleTarget = isTailscaleAddress(targetHost) || isTailscaleAddress(config.bindHost);
  const access = remoteAccessState(req);
  return {
    host: config.host,
    port: config.port,
    bindHost: config.bindHost,
    requestHost: hostHeader,
    remoteAddress,
    uptimeSec: Math.floor(process.uptime()),
    platform: `${os.type()} ${os.release()}`,
    tailscale: {
      targetLooksLikeTailnet: tailscaleTarget,
      bindLooksLikeTailnet: isTailscaleAddress(config.bindHost),
      hint:
        tailscaleTarget && !isTailscaleAddress(remoteAddress)
          ? 'Client does not appear to be reaching the daemon through Tailscale/VPN.'
          : '',
    },
    remoteAccess: {
      configured: access.configured,
      allowed: access.allowed,
      remoteAddress: access.remoteAddress,
      allowedRemoteAddresses: access.allowedRemoteAddresses,
    },
    commands: {
      git: inspectCommand('git'),
      codex: inspectCommand(resolveAgentCommand('codex')),
      claudeCode: inspectCommand(resolveAgentCommand('claude-code')),
    },
    workbench: {
      enabled: config.remoteControl.enabled,
      tokenRoles: summarizeTokenRoles(),
      defaultPermissionMode: config.remoteControl.defaultPermissionMode,
      allowDangerousPermissions: config.remoteControl.allowDangerousPermissions,
      allowedRemoteAddresses: config.remoteControl.allowedRemoteAddresses,
      allowedCwds: config.remoteControl.allowedCwds,
      maxSessions: config.remoteControl.maxSessions,
      maxOutputChars: config.remoteControl.maxOutputChars,
      dangerousSessionTtlMs: config.remoteControl.dangerousSessionTtlMs,
      attachmentTtlHours: config.remoteControl.attachmentTtlHours,
      maxRawUploadBytes: MAX_RAW_UPLOAD_BYTES,
      maxAttachmentsPerMessage: MAX_ATTACHMENTS_PER_MESSAGE,
      maxSessionAttachments: MAX_SESSION_ATTACHMENTS,
    },
    alertRules: config.alertRules,
    history: historyStorageInfo(),
    logs: logStatus(),
  };
}

export function buildSecurityStatus() {
  const checks = [
    check(
      'bind_host',
      config.bindHost && config.bindHost !== '0.0.0.0' && config.bindHost !== '::',
      'Bind host',
      `daemon listens on ${config.bindHost}:${config.port}`,
      'Bind only to the Tailscale IP or 127.0.0.1.'
    ),
    check(
      'remote_allowlist',
      config.remoteControl.allowedRemoteAddresses.length > 0,
      'Remote device allowlist',
      config.remoteControl.allowedRemoteAddresses.length
        ? `${config.remoteControl.allowedRemoteAddresses.length} trusted remote address rule(s) configured.`
        : 'No trusted remote address rules configured.',
      'Add the phone Tailscale IP or a strict tailnet CIDR before using remote workbench long term.'
    ),
    check(
      'token_strength',
      !config.tokens.includes('change-me-please') && config.tokens.every((token) => token.length >= 32),
      'Access token',
      `${config.tokens.length} token(s) configured; shortest length is ${Math.min(...config.tokens.map((token) => token.length))}.`,
      'Use long random tokens and rotate them after exposure.'
    ),
    check(
      'auth_rate_limit',
      config.authFailMax <= 20,
      'Authentication rate limit',
      `Blocks a source after ${config.authFailMax} failed attempt(s) within ${Math.round(config.authFailWindowMs / 1000)}s.`,
      'Keep failed authentication limits low enough to slow token guessing.'
    ),
    check(
      'tls',
      config.tls.enabled || config.bindHost === '127.0.0.1',
      'Transport security',
      config.tls.enabled ? 'TLS is enabled by daemon config.' : 'TLS is not enabled in daemon config.',
      'Use daemon TLS, a trusted reverse proxy, or a private Tailscale/LAN-only bind address.'
    ),
    check(
      'remote_control',
      config.remoteControl.enabled,
      'Remote workbench',
      config.remoteControl.enabled ? 'Remote workbench is enabled.' : 'Remote workbench is disabled.',
      'Enable only while the phone needs to control agents.'
    ),
    check(
      'dangerous_permissions',
      !config.remoteControl.allowDangerousPermissions,
      'Agent permission modes',
      config.remoteControl.allowDangerousPermissions
        ? `Dangerous mode is available; default session mode is ${config.remoteControl.defaultPermissionMode}.`
        : `Dangerous mode is disabled; default session mode is ${config.remoteControl.defaultPermissionMode}.`,
      'Keep dangerous mode disabled unless a single session explicitly needs it.'
    ),
    check(
      'workspace_scope',
      config.remoteControl.allowedCwds.length > 0,
      'Workspace scope',
      `${config.remoteControl.allowedCwds.length} allowed working directorie(s).`,
      'Keep only directories that the phone workbench should access.'
    ),
    check(
      'upload_limits',
      MAX_RAW_UPLOAD_BYTES <= 20 * 1024 * 1024,
      'Upload limits',
      `Single upload limit ${formatBytes(MAX_RAW_UPLOAD_BYTES)}, max ${MAX_ATTACHMENTS_PER_MESSAGE} attachment(s) per turn.`,
      'Keep upload limits small to reduce accidental sensitive file transfer.'
    ),
    check(
      'account_privacy',
      config.privacy.maskAccountEmails,
      'Account privacy',
      config.privacy.maskAccountEmails ? 'Account emails are masked.' : 'Account emails are not masked.',
      'Mask account emails when the dashboard is commonly viewed on a phone.'
    ),
  ];

  return {
    host: config.host,
    port: config.port,
    bindHost: config.bindHost,
    remoteControl: {
      enabled: config.remoteControl.enabled,
      tokenRoles: summarizeTokenRoles(),
      allowDangerousPermissions: config.remoteControl.allowDangerousPermissions,
      defaultPermissionMode: config.remoteControl.defaultPermissionMode,
      allowedRemoteAddresses: config.remoteControl.allowedRemoteAddresses,
      allowedCwds: config.remoteControl.allowedCwds,
      maxSessions: config.remoteControl.maxSessions,
      maxOutputChars: config.remoteControl.maxOutputChars,
      dangerousSessionTtlMs: config.remoteControl.dangerousSessionTtlMs,
      attachmentTtlHours: config.remoteControl.attachmentTtlHours,
    },
    privacy: {
      maskAccountEmails: config.privacy.maskAccountEmails,
      redactCwd: config.privacy.redactCwd,
      hermesStatusMaxLen: config.privacy.hermesStatusMaxLen,
    },
    uploadLimits: {
      maxRawUploadBytes: MAX_RAW_UPLOAD_BYTES,
      maxAttachmentsPerMessage: MAX_ATTACHMENTS_PER_MESSAGE,
      maxSessionAttachments: MAX_SESSION_ATTACHMENTS,
      attachmentTtlHours: config.remoteControl.attachmentTtlHours,
    },
    alertRules: config.alertRules,
    history: historyStorageInfo(),
    logs: logStatus(),
    audit: {
      path: auditLogPath(),
      recent: readAuditLog(50),
    },
    checks,
  };
}

function summarizeTokenRoles() {
  return (config.tokenRecords || []).map((record) => ({
    id: record.id || '',
    name: record.name || '',
    role: record.role || 'admin',
    enabled: record.enabled !== false,
    tokenPreview: previewToken(record.token),
  }));
}

function previewToken(token) {
  const value = String(token || '');
  if (value.length <= 8) return '***';
  return `${value.slice(0, 4)}...${value.slice(-4)}`;
}

export function setRemoteControlEnabled(enabled) {
  const value = enabled === true;
  persistConfigPatch((current) => ({
    ...current,
    remoteControl: {
      ...(current.remoteControl || {}),
      enabled: value,
    },
  }));
  config.remoteControl.enabled = value;
  return buildSecurityStatus();
}

export function rotateToken() {
  if (config.remoteControl.allowedRemoteAddresses.length === 0) {
    throw apiError(
      'TOKEN_ROTATE_REQUIRES_ALLOWLIST',
      'Token rotation requires a configured trusted remote address allowlist.',
      403
    );
  }
  const token = crypto.randomBytes(32).toString('hex');
  persistConfigPatch((current) => {
    const next = { ...current, token };
    delete next.tokens;
    return next;
  });
  config.token = token;
  config.tokens = [token];
  config.tokenRecords = [{ id: 'primary', name: 'primary', role: 'admin', token, enabled: true }];
  return { token, status: buildSecurityStatus() };
}

function check(id, ok, title, detail, fix) {
  return {
    id,
    state: ok ? 'ok' : 'warn',
    title,
    detail,
    fix: ok ? '' : fix,
  };
}

function apiError(code, message, status = 500) {
  const error = new Error(message);
  error.code = code;
  error.status = status;
  return error;
}

function formatBytes(bytes) {
  const n = Number(bytes) || 0;
  if (n >= 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  if (n >= 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${n} B`;
}

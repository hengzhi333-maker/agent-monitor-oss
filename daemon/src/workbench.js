import crypto from 'crypto';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { spawn } from 'child_process';
import { config } from './config.js';
import { redactText, truncateText } from './lib/redact.js';
import {
  MAX_ATTACHMENTS_PER_MESSAGE,
  MAX_SESSION_ATTACHMENTS,
  composePromptWithAttachments,
  formatAttachmentList,
  publicAttachment,
  removeAttachmentFiles,
  saveWorkbenchAttachment,
} from './workbenchAttachments.js';
import { inspectGitStatus, readGitDiff } from './workbenchGit.js';
import { loadWorkbenchState, persistWorkbenchState } from './workbenchStore.js';

const SUPPORTED_AGENTS = new Set(['codex', 'claude-code']);
const PERMISSION_MODES = new Set(['read-only', 'standard', 'dangerous']);
const sessions = new Map();
const home = os.homedir();
const NON_FATAL_CODEX_DIAGNOSTICS = [
  'codex_core_plugins::startup_remote_sync',
  'codex_core_plugins::startup_sync',
  'codex_core_plugins::loader: failed to load plugin',
  'codex_core_plugins::manager: failed to warm featured plugin ids cache',
  'codex_core_plugins::manager: failed to sync curated plugins repo',
  'codex_core_plugins::manager: failed to refresh curated plugin cache',
  'failed to back up plugin cache entry',
  'codex_core::client: falling back to HTTP',
  'codex_core::shell_snapshot: Failed to create shell snapshot for powershell',
  'codex_api::endpoint::responses_websocket: failed to connect to websocket: HTTP error: 426 Upgrade Required',
  'export archive fallback skipped because a local curated plugins snapshot already exists',
];
const CODEX_LIFECYCLE_EVENTS = new Set(['thread.started', 'turn.started', 'turn.completed']);
const CODEX_HIDDEN_ITEM_TYPES = new Set(['command_execution']);
const CLAUDE_DUPLICATE_ASSISTANT_KINDS = new Set(['assistant', 'result']);

for (const rawSession of loadWorkbenchState()) {
  const session = restoreWorkbenchSession(rawSession);
  if (session) sessions.set(session.id, session);
}
if (sessions.size) persistSessions();

let broadcaster = () => {};
cleanupWorkbenchData().catch(() => {});
const cleanupTimer = setInterval(() => {
  cleanupWorkbenchData().catch(() => {});
}, 60 * 60 * 1000);
cleanupTimer.unref?.();

export function setWorkbenchBroadcaster(fn) {
  broadcaster = typeof fn === 'function' ? fn : () => {};
}

export function listWorkbenchSessions() {
  return listWorkbenchSessionsWithOptions();
}

export function listWorkbenchSessionsWithOptions(options = {}) {
  const includeArchived = options.includeArchived === true;
  return {
    sessions: [...sessions.values()]
      .filter((session) => includeArchived || session.archived !== true)
      .map(publicSession),
  };
}

export function listWorkbenchAttachments() {
  const attachments = [...sessions.values()]
    .flatMap((session) =>
      (session.attachments || []).map((attachment) => ({
        ...publicAttachment(attachment),
        sessionId: session.id,
        sessionTitle: session.title,
        agentId: session.agentId,
      }))
    )
    .sort((a, b) => b.createdAt - a.createdAt);
  return { attachments };
}

export async function cleanupWorkbenchData(input = {}) {
  const all = input.all === true;
  const ttlHours = Number(input.ttlHours ?? config.remoteControl.attachmentTtlHours);
  const ttlMs = Number.isFinite(ttlHours) && ttlHours > 0 ? ttlHours * 60 * 60 * 1000 : 0;
  if (!all && ttlMs <= 0) return { removedAttachments: 0, ttlHours: 0 };

  const cutoff = Date.now() - ttlMs;
  const removed = [];
  for (const session of sessions.values()) {
    const before = session.attachments || [];
    const keep = [];
    for (const attachment of before) {
      const expired = ttlMs > 0 && Number(attachment.createdAt || 0) < cutoff;
      if (all || expired) removed.push(attachment);
      else keep.push(attachment);
    }
    if (keep.length !== before.length) {
      session.attachments = keep;
      session.updatedAt = Date.now();
      emit('workbench.session.updated', { session: publicSession(session) });
    }
  }

  if (removed.length) {
    await removeAttachmentFiles(removed);
    await persistSessions();
  }
  return { removedAttachments: removed.length, ttlHours: all ? 0 : ttlHours };
}

export async function deleteWorkbenchAttachment(sessionId, attachmentId) {
  const session = sessions.get(sessionId);
  if (!session) throw apiError('SESSION_NOT_FOUND', 'Workbench session was not found.', 404);
  if (session.child) throw apiError('SESSION_BUSY', 'Stop the running turn before deleting attachments.', 409);

  const index = (session.attachments || []).findIndex((attachment) => attachment.id === attachmentId);
  if (index < 0) throw apiError('ATTACHMENT_NOT_FOUND', 'Attachment was not found in this session.', 404);

  const [removed] = session.attachments.splice(index, 1);
  session.updatedAt = Date.now();
  await removeAttachmentFiles([removed]);
  await persistSessions();
  emit('workbench.attachment.deleted', {
    sessionId: session.id,
    agentId: session.agentId,
    attachmentId,
    ts: Date.now(),
  });
  emit('workbench.session.updated', { session: publicSession(session) });
  return { deleted: true, sessionId: session.id, attachmentId };
}

export function getWorkbenchMessages(sessionId) {
  const session = sessions.get(sessionId);
  if (!session) return null;
  return { session: publicSession(session), messages: session.messages };
}

export async function getWorkbenchGitStatus(sessionId) {
  const session = sessions.get(sessionId);
  if (!session) throw apiError('SESSION_NOT_FOUND', 'Workbench session was not found.', 404);
  return { sessionId, status: await inspectGitStatus(session.cwd) };
}

export async function getWorkbenchGitDiff(sessionId, options = {}) {
  const session = sessions.get(sessionId);
  if (!session) throw apiError('SESSION_NOT_FOUND', 'Workbench session was not found.', 404);
  return { sessionId, ...(await readGitDiff(session.cwd, options)) };
}

export function createWorkbenchSession(input = {}, context = {}) {
  ensureEnabled();
  const agentId = String(input.agentId || '');
  if (!SUPPORTED_AGENTS.has(agentId)) {
    throw apiError('UNSUPPORTED_AGENT', 'Only codex and claude-code can be controlled.', 400);
  }

  const cwd = normalizeAllowedCwd(input.cwd || config.remoteControl.defaultCwd);
  const permissionMode = resolvePermissionMode(input.permissionMode, { auth: context.auth });
  const now = Date.now();
  const session = {
    id: `wb_${crypto.randomUUID()}`,
    agentId,
    title: cleanText(input.title || agentLabel(agentId)),
    cwd,
    permissionMode,
    state: 'idle',
    agentSessionId: '',
    createdAt: now,
    updatedAt: now,
    archived: false,
    dangerousExpiresAt: permissionMode === 'dangerous' ? now + dangerousSessionTtlMs() : 0,
    lastError: '',
    messages: [],
    attachments: [],
    child: null,
  };

  sessions.set(session.id, session);
  trimSessions();
  persistSessions();
  emit('workbench.session.created', { session: publicSession(session) });
  return { session: publicSession(session) };
}

export async function uploadWorkbenchAttachment(sessionId, input = {}) {
  ensureEnabled();
  const session = sessions.get(sessionId);
  if (!session) throw apiError('SESSION_NOT_FOUND', 'Workbench session was not found.', 404);

  const attachment = await saveWorkbenchAttachment(session.id, input);
  session.attachments.push(attachment);
  while (session.attachments.length > MAX_SESSION_ATTACHMENTS) {
    const removed = session.attachments.shift();
    await removeAttachmentFiles([removed]);
  }
  session.updatedAt = Date.now();
  await persistSessions();
  const safe = publicAttachment(attachment);
  emit('workbench.attachment.created', { sessionId: session.id, agentId: session.agentId, attachment: safe });
  emit('workbench.session.updated', { session: publicSession(session) });
  return { attachment: safe };
}

export function sendWorkbenchMessage(sessionId, input = {}) {
  ensureEnabled();
  const session = sessions.get(sessionId);
  if (!session) throw apiError('SESSION_NOT_FOUND', 'Workbench session was not found.', 404);
  if (session.archived) throw apiError('SESSION_ARCHIVED', 'Archived workbench sessions cannot receive messages.', 409);
  if (session.child) throw apiError('SESSION_BUSY', 'This workbench session is already running.', 409);
  ensureDangerousSessionFresh(session);

  const text = cleanText(input.text || '');
  const attachments = resolveMessageAttachments(session, input.attachmentIds);
  if (!text.trim() && !attachments.length) throw apiError('EMPTY_MESSAGE', 'Message text or attachment is required.', 400);

  const turnId = `turn_${crypto.randomUUID()}`;
  const userText = formatUserMessageText(text, attachments);
  appendMessage(session, {
    id: `msg_${crypto.randomUUID()}`,
    turnId,
    role: 'user',
    kind: 'message',
    text: userText,
    attachments: attachments.map(publicAttachment),
    ts: Date.now(),
  });
  startTurn(session, turnId, composePromptWithAttachments(text, attachments), attachments);
  return { accepted: true, sessionId: session.id, turnId };
}

export function stopWorkbenchSession(sessionId) {
  const session = sessions.get(sessionId);
  if (!session) throw apiError('SESSION_NOT_FOUND', 'Workbench session was not found.', 404);
  if (session.child) {
    const child = session.child;
    session.child = null;
    updateState(session, 'stopped');
    child.kill();
  } else {
    updateState(session, 'stopped');
  }
  return { stopped: true, sessionId: session.id };
}

export async function archiveWorkbenchSession(sessionId, archived = true) {
  const session = sessions.get(sessionId);
  if (!session) throw apiError('SESSION_NOT_FOUND', 'Workbench session was not found.', 404);
  if (session.child) throw apiError('SESSION_BUSY', 'Stop the running turn before archiving this session.', 409);
  session.archived = archived === true;
  session.updatedAt = Date.now();
  await persistSessions();
  emit('workbench.session.updated', { session: publicSession(session) });
  return { session: publicSession(session) };
}

export async function deleteWorkbenchSession(sessionId) {
  const session = sessions.get(sessionId);
  if (!session) throw apiError('SESSION_NOT_FOUND', 'Workbench session was not found.', 404);
  if (session.child) throw apiError('SESSION_BUSY', 'Stop the running turn before deleting this session.', 409);
  sessions.delete(sessionId);
  await removeAttachmentFiles(session.attachments || []);
  await persistSessions();
  emit('workbench.session.deleted', {
    sessionId,
    agentId: session.agentId,
    ts: Date.now(),
  });
  return { deleted: true, sessionId };
}

function ensureEnabled() {
  if (!config.remoteControl.enabled) {
    throw apiError('REMOTE_CONTROL_DISABLED', 'Remote workbench is disabled.', 403);
  }
}

function normalizeAllowedCwd(value) {
  const resolved = path.resolve(String(value || config.remoteControl.defaultCwd));
  const ok = config.remoteControl.allowedCwds.some((root) => {
    const allowed = path.resolve(root);
    return resolved === allowed || resolved.startsWith(`${allowed}${path.sep}`);
  });
  if (!ok) throw apiError('CWD_NOT_ALLOWED', 'Working directory is not allowed.', 400);
  return resolved;
}

function normalizePermissionMode(value, fallback = defaultPermissionMode()) {
  const mode = String(value || '').trim().toLowerCase();
  if (mode === 'read-only' || mode === 'readonly' || mode === 'read') return 'read-only';
  if (mode === 'dangerous' || mode === 'full-access' || mode === 'bypass') return 'dangerous';
  if (mode === 'standard' || mode === 'workspace-write' || mode === 'default') return 'standard';
  return PERMISSION_MODES.has(fallback) ? fallback : 'standard';
}

function defaultPermissionMode() {
  const configured = normalizePermissionMode(config.remoteControl.defaultPermissionMode, 'standard');
  if (configured === 'dangerous' && !config.remoteControl.allowDangerousPermissions) return 'standard';
  return configured;
}

function resolvePermissionMode(value, options = {}) {
  const mode = normalizePermissionMode(value, defaultPermissionMode());
  if (mode === 'dangerous' && !config.remoteControl.allowDangerousPermissions) {
    if (options.strictDangerous === false) return 'standard';
    throw apiError('DANGEROUS_PERMISSION_DISABLED', 'Dangerous workbench permission mode is disabled.', 403);
  }
  if (mode === 'dangerous' && options.auth && options.auth.role !== 'admin') {
    if (options.strictDangerous === false) return 'standard';
    throw apiError('DANGEROUS_PERMISSION_REQUIRES_ADMIN', 'Dangerous workbench permission mode requires an admin token.', 403);
  }
  return mode;
}

function dangerousSessionTtlMs() {
  const value = Number(config.remoteControl.dangerousSessionTtlMs || 30 * 60 * 1000);
  return Number.isFinite(value) && value > 0 ? Math.min(value, 24 * 60 * 60 * 1000) : 30 * 60 * 1000;
}

function ensureDangerousSessionFresh(session) {
  if (session.permissionMode !== 'dangerous') return;
  const expiresAt = Number(session.dangerousExpiresAt || 0);
  if (expiresAt > Date.now()) return;
  session.permissionMode = 'standard';
  session.lastError = 'Dangerous permission window expired; create a new high-permission session if needed.';
  session.updatedAt = Date.now();
  persistSessions();
  emit('workbench.session.updated', { session: publicSession(session) });
  throw apiError('DANGEROUS_PERMISSION_EXPIRED', 'Dangerous permission window expired.', 403);
}

function publicSession(session) {
  const { child, messages, attachments, ...safe } = session;
  return { ...safe, attachmentCount: attachments?.length || 0 };
}

function apiError(code, message, status = 500) {
  const error = new Error(message);
  error.code = code;
  error.status = status;
  return error;
}

function cleanText(value, max = 20000) {
  return truncateText(redactText(String(value || ''), { maskEmails: true, maskLocalPaths: true }), max).trim();
}

function appendMessage(session, message) {
  const safe = { ...message, text: cleanText(message.text), ts: message.ts || Date.now() };
  if (Array.isArray(message.attachments)) safe.attachments = message.attachments.map(publicAttachment);
  if (isDuplicateWorkbenchMessage(session.messages, safe)) return null;
  session.messages.push(safe);
  enforceOutputLimit(session);
  session.updatedAt = Date.now();
  persistSessions();
  emit('workbench.message', { sessionId: session.id, agentId: session.agentId, message: safe });
  emit('workbench.session.updated', { session: publicSession(session) });
  return safe;
}

export function dedupeWorkbenchMessages(messages) {
  if (!Array.isArray(messages)) return [];
  const deduped = [];
  for (const message of messages) {
    if (!isDuplicateWorkbenchMessage(deduped, message)) deduped.push(message);
  }
  return deduped;
}

function isDuplicateWorkbenchMessage(messages, next) {
  if (!isClaudeAssistantSummaryCandidate(next)) return false;
  const nextText = normalizeDuplicateText(next.text);
  if (!nextText) return false;
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const previous = messages[index];
    if (!isSameTurn(previous, next)) continue;
    if (!isClaudeAssistantSummaryCandidate(previous)) continue;
    if (normalizeDuplicateText(previous.text) !== nextText) continue;
    if (isClaudeAssistantResultPair(previous, next)) return true;
  }
  return false;
}

function isClaudeAssistantSummaryCandidate(message) {
  return (
    message?.role === 'assistant' &&
    CLAUDE_DUPLICATE_ASSISTANT_KINDS.has(String(message.kind || '').toLowerCase())
  );
}

function isSameTurn(left, right) {
  const leftTurnId = String(left?.turnId || '');
  const rightTurnId = String(right?.turnId || '');
  return Boolean(leftTurnId && rightTurnId && leftTurnId === rightTurnId);
}

function isClaudeAssistantResultPair(left, right) {
  const leftKind = String(left?.kind || '').toLowerCase();
  const rightKind = String(right?.kind || '').toLowerCase();
  return leftKind === rightKind || (leftKind === 'assistant' && rightKind === 'result') || (leftKind === 'result' && rightKind === 'assistant');
}

function normalizeDuplicateText(text) {
  return String(text || '').replace(/\s+/g, ' ').trim();
}

function updateState(session, state, extra = {}) {
  session.state = state;
  session.updatedAt = Date.now();
  if (extra.lastError !== undefined) session.lastError = cleanText(extra.lastError);
  persistSessions();
  emit('workbench.state', { sessionId: session.id, agentId: session.agentId, state, ...extra, ts: Date.now() });
  emit('workbench.session.updated', { session: publicSession(session) });
}

function emit(type, data) {
  broadcaster({ type, data });
}

function agentLabel(agentId) {
  return agentId === 'claude-code' ? 'Claude Code Workbench' : 'Codex Workbench';
}

function startTurn(session, turnId, prompt, attachments = []) {
  updateState(session, 'running', { turnId });
  let child;
  try {
    const command = buildAgentCommand(session, prompt, attachments);
    child = spawn(command.cmd, command.args, {
      cwd: session.cwd,
      shell: false,
      windowsHide: true,
      env: process.env,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    child.stdin.end(command.stdin);
  } catch (err) {
    appendLaunchError(session, turnId, err);
    return;
  }
  session.child = child;

  wireStream(session, turnId, child.stdout, 'stdout');
  wireStream(session, turnId, child.stderr, 'stderr');

  child.on('error', (err) => {
    session.child = null;
    appendMessage(session, {
      id: `err_${crypto.randomUUID()}`,
      turnId,
      role: 'system',
      kind: 'error',
      text: err.message,
      ts: Date.now(),
    });
    updateState(session, 'error', { turnId, lastError: err.message });
    emit('workbench.error', {
      sessionId: session.id,
      turnId,
      agentId: session.agentId,
      message: cleanText(err.message),
      ts: Date.now(),
    });
  });

  child.on('close', (code, signal) => {
    if (session.child === child) session.child = null;
    const state = code === 0 ? 'idle' : session.state === 'stopped' ? 'stopped' : 'error';
    const lastError =
      code !== 0 && state === 'error'
        ? `Process exited with code ${code}${signal ? ` signal ${signal}` : ''}`
        : '';
    updateState(session, state, { turnId, exitCode: code, signal, lastError });
    emit('workbench.turn.completed', {
      sessionId: session.id,
      turnId,
      agentId: session.agentId,
      exitCode: code,
      signal,
      ts: Date.now(),
    });
  });
}

export function buildAgentCommand(session, prompt, attachments = []) {
  if (session.agentId === 'codex') return buildCodexCommand(session, prompt, attachments);
  return buildClaudeCommand(session, prompt);
}

export function resolveAgentCommand(agentId) {
  return resolveCommand(agentId);
}

function buildCodexCommand(session, prompt, attachments = []) {
  const permissionMode = resolvePermissionMode(session.permissionMode);
  const args = session.agentSessionId
    ? ['exec', 'resume', '--json', '--all', session.agentSessionId]
    : ['exec', '--json', '--skip-git-repo-check', '--cd', session.cwd];
  if (permissionMode === 'dangerous') {
    args.push('--dangerously-bypass-approvals-and-sandbox');
  } else if (!session.agentSessionId) {
    args.push('--sandbox', permissionMode === 'read-only' ? 'read-only' : 'workspace-write');
  }
  for (const attachment of attachments) {
    if (attachment.kind === 'image' && attachment.filePath) args.push('--image', attachment.filePath);
  }
  args.push('-');
  return { cmd: resolveCommand('codex'), args, stdin: prompt };
}

function buildClaudeCommand(session, prompt) {
  const permissionMode = resolvePermissionMode(session.permissionMode);
  const args = ['-p', '--output-format=stream-json', '--verbose'];
  if (session.agentSessionId) args.push('--resume', session.agentSessionId);
  if (permissionMode === 'dangerous') {
    args.push('--permission-mode', 'bypassPermissions');
  } else {
    args.push('--permission-mode', permissionMode === 'read-only' ? 'plan' : 'default');
  }
  return { cmd: resolveCommand('claude-code'), args, stdin: prompt };
}

function resolveCommand(agentId) {
  if (process.platform !== 'win32') return agentId === 'codex' ? 'codex' : 'claude';
  const candidates =
    agentId === 'codex'
      ? [
          path.join(home, 'Documents', 'Codex', 'apps', 'CodexDesktop-Rebuild', '26.519.21041', 'resources', 'codex.exe'),
          path.join(process.env.APPDATA || path.join(home, 'AppData', 'Roaming'), 'npm', 'node_modules', '@openai', 'codex', 'bin', 'codex.js'),
        ]
      : [
          path.join(process.env.APPDATA || path.join(home, 'AppData', 'Roaming'), 'npm', 'node_modules', '@anthropic-ai', 'claude-code', 'bin', 'claude.exe'),
        ];
  return candidates.find((candidate) => fs.existsSync(candidate)) || (agentId === 'codex' ? 'codex.cmd' : 'claude.cmd');
}

function appendLaunchError(session, turnId, err) {
  const message = err?.message || String(err);
  session.child = null;
  appendMessage(session, {
    id: `err_${crypto.randomUUID()}`,
    turnId,
    role: 'system',
    kind: 'error',
    text: message,
    ts: Date.now(),
  });
  updateState(session, 'error', { turnId, lastError: message });
  emit('workbench.error', {
    sessionId: session.id,
    turnId,
    agentId: session.agentId,
    message: cleanText(message),
    ts: Date.now(),
  });
}

function wireStream(session, turnId, stream, streamName) {
  let buffer = '';
  stream.on('data', (chunk) => {
    const text = chunk.toString('utf8');
    emit('workbench.output.delta', {
      sessionId: session.id,
      turnId,
      agentId: session.agentId,
      stream: streamName,
      text: cleanText(text),
      ts: Date.now(),
    });
    buffer += text;
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() || '';
    for (const line of lines) handleOutputLine(session, turnId, streamName, line);
  });
  stream.on('end', () => {
    if (buffer) handleOutputLine(session, turnId, streamName, buffer);
  });
}

function handleOutputLine(session, turnId, streamName, rawLine) {
  const line = cleanText(rawLine);
  if (!line) return;
  const message = messageFromOutputLine(session, turnId, streamName, line);
  if (message) appendMessage(session, message);
  emit('workbench.output.line', {
    sessionId: session.id,
    turnId,
    agentId: session.agentId,
    stream: streamName,
    text: line,
    ts: Date.now(),
  });
}

export function messageFromOutputLine(session, turnId, streamName, rawLine) {
  const line = cleanText(rawLine);
  if (!line) return null;
  captureAgentSessionId(session, line);
  const parsed = tryParseJson(line);
  if (parsed) return messageFromEvent(session, turnId, streamName, parsed, line);
  if (isNonUserVisibleOutputLine(session, streamName, line)) return null;
  return {
    id: `out_${crypto.randomUUID()}`,
    turnId,
    role: streamName === 'stderr' ? 'system' : 'assistant',
    kind: streamName === 'stderr' ? 'stderr' : 'output',
    text: line,
    ts: Date.now(),
  };
}

function isNonUserVisibleOutputLine(session, streamName, line) {
  if (session.agentId !== 'codex') return false;
  if (streamName === 'stdout' && isWindowsTaskCleanupLine(line)) return true;
  if (streamName !== 'stderr') return false;
  const normalized = line.toLowerCase();
  return NON_FATAL_CODEX_DIAGNOSTICS.some((pattern) => normalized.includes(pattern.toLowerCase()));
}

function isWindowsTaskCleanupLine(line) {
  return (
    /^SUCCESS: The process with PID \d+ .* has been terminated\.$/.test(line) ||
    /\bPID \d+\b.*\bPID \d+\b/i.test(line)
  );
}

function tryParseJson(line) {
  try {
    return JSON.parse(line);
  } catch {
    return null;
  }
}

export function messageFromEvent(session, turnId, streamName, event, fallback = '') {
  if (isProtocolOnlyEvent(event)) return null;
  const text = extractWorkbenchText(event) || fallback;
  if (!text) return null;
  const kind = event.type || event.event || (streamName === 'stderr' ? 'stderr' : 'event');
  const role =
    kind.includes('tool') || kind.includes('function')
      ? 'tool'
      : streamName === 'stderr'
        ? 'system'
        : 'assistant';
  if (role === 'tool') {
    emit('workbench.tool', { sessionId: session.id, turnId, agentId: session.agentId, event, ts: Date.now() });
  }
  return {
    id: `evt_${crypto.randomUUID()}`,
    turnId,
    role,
    kind,
    text,
    ts: Date.now(),
  };
}

export function extractWorkbenchText(value) {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (typeof value.result === 'string') return value.result;
  if (typeof value.text === 'string') return value.text;
  if (typeof value.message === 'string') return value.message;
  if (typeof value.delta === 'string') return value.delta;
  if (value.item) return extractWorkbenchText(value.item);
  if (Array.isArray(value.content)) return value.content.map(extractWorkbenchText).filter(Boolean).join('\n');
  if (value.payload) return extractWorkbenchText(value.payload);
  if (value.message && typeof value.message === 'object') return extractWorkbenchText(value.message);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function isProtocolOnlyEvent(event) {
  if (!event || typeof event !== 'object') return false;
  const type = event.type || event.event;
  const subtype = event.subtype || event.kind;
  if (CODEX_LIFECYCLE_EVENTS.has(type)) return true;
  if (type === 'item.started' && CODEX_HIDDEN_ITEM_TYPES.has(event.item?.type)) return true;
  if (type === 'item.completed' && CODEX_HIDDEN_ITEM_TYPES.has(event.item?.type)) return true;
  return type === 'system' && subtype === 'init';
}

function captureAgentSessionId(session, line) {
  const parsed = tryParseJson(line);
  if (!parsed) return;
  const id =
    parsed.thread_id ||
    parsed.threadId ||
    parsed.session_id ||
    parsed.sessionId ||
    parsed.conversation_id ||
    parsed.conversationId ||
    parsed.payload?.id ||
    parsed.payload?.session_id;
  if (id && !session.agentSessionId) {
    session.agentSessionId = String(id);
    session.updatedAt = Date.now();
    persistSessions();
  }
}

function enforceOutputLimit(session) {
  let total = session.messages.reduce((sum, item) => sum + String(item.text || '').length, 0);
  while (total > config.remoteControl.maxOutputChars && session.messages.length > 1) {
    const removed = session.messages.shift();
    total -= String(removed?.text || '').length;
  }
}

function trimSessions() {
  const max = config.remoteControl.maxSessions;
  const ordered = [...sessions.values()].sort((a, b) => b.updatedAt - a.updatedAt);
  for (const session of ordered.slice(max)) {
    if (session.child) session.child.kill();
    removeAttachmentFiles(session.attachments).catch(() => {});
    sessions.delete(session.id);
  }
  if (ordered.length > max) persistSessions();
}

function resolveMessageAttachments(session, attachmentIds) {
  if (attachmentIds == null) return [];
  if (!Array.isArray(attachmentIds)) {
    throw apiError('INVALID_ATTACHMENTS', 'attachmentIds must be an array.', 400);
  }
  const ids = [...new Set(attachmentIds.map((id) => String(id || '').trim()).filter(Boolean))];
  if (ids.length > MAX_ATTACHMENTS_PER_MESSAGE) {
    throw apiError('TOO_MANY_ATTACHMENTS', `At most ${MAX_ATTACHMENTS_PER_MESSAGE} attachments can be sent at once.`, 400);
  }
  const byId = new Map((session.attachments || []).map((item) => [item.id, item]));
  return ids.map((id) => {
    const attachment = byId.get(id);
    if (!attachment) throw apiError('ATTACHMENT_NOT_FOUND', 'Attachment was not found in this session.', 404);
    if (attachment.status !== 'ready') throw apiError('ATTACHMENT_NOT_READY', 'Attachment is not ready.', 409);
    return attachment;
  });
}

function formatUserMessageText(text, attachments) {
  const body = text.trim() || '请分析这些附件。';
  const list = formatAttachmentList(attachments);
  return list ? `${body}\n\n附件:\n${list}` : body;
}

function restoreWorkbenchSession(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const agentId = String(raw.agentId || '');
  if (!SUPPORTED_AGENTS.has(agentId)) return null;
  try {
    const state = normalizeSessionState(raw.state);
    const wasRunning = raw.state === 'running';
    const now = Date.now();
    return {
      id: String(raw.id || `wb_${crypto.randomUUID()}`),
      agentId,
      title: cleanText(raw.title || agentLabel(agentId)),
      cwd: normalizeAllowedCwd(raw.cwd || config.remoteControl.defaultCwd),
      permissionMode: resolvePermissionMode(raw.permissionMode, { strictDangerous: false }),
      state: wasRunning ? 'stopped' : state,
      agentSessionId: cleanText(raw.agentSessionId || '', 300),
      createdAt: normalizeTimestamp(raw.createdAt, now),
      updatedAt: normalizeTimestamp(raw.updatedAt, now),
      archived: raw.archived === true,
      dangerousExpiresAt: normalizeTimestamp(raw.dangerousExpiresAt, 0),
      lastError: wasRunning
        ? 'Daemon restarted before this workbench turn finished.'
        : cleanText(raw.lastError || '', 2000),
      messages: restoreMessages(raw.messages),
      attachments: restoreAttachments(raw.attachments),
      child: null,
    };
  } catch {
    return null;
  }
}

function normalizeSessionState(value) {
  const state = String(value || 'idle');
  return ['idle', 'running', 'stopped', 'error'].includes(state) ? state : 'idle';
}

function normalizeTimestamp(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : fallback;
}

function restoreMessages(messages) {
  if (!Array.isArray(messages)) return [];
  return dedupeWorkbenchMessages(messages.slice(-500).map((message) => ({
    id: String(message?.id || `msg_${crypto.randomUUID()}`),
    turnId: String(message?.turnId || ''),
    role: String(message?.role || 'system'),
    kind: String(message?.kind || 'message'),
    text: cleanText(message?.text || ''),
    ts: normalizeTimestamp(message?.ts, Date.now()),
    attachments: Array.isArray(message?.attachments) ? message.attachments.map(publicAttachment).filter(Boolean) : [],
  })));
}

function restoreAttachments(attachments) {
  if (!Array.isArray(attachments)) return [];
  return attachments.slice(-MAX_SESSION_ATTACHMENTS).map((attachment) => ({
    id: String(attachment?.id || `att_${crypto.randomUUID()}`),
    sessionId: String(attachment?.sessionId || ''),
    name: cleanText(attachment?.name || 'attachment', 300),
    mime: cleanText(attachment?.mime || 'application/octet-stream', 200),
    kind: cleanText(attachment?.kind || '', 50),
    ext: cleanText(attachment?.ext || '', 30),
    size: Number(attachment?.size) || 0,
    status: cleanText(attachment?.status || 'ready', 50),
    filePath: String(attachment?.filePath || ''),
    extractedText: cleanText(attachment?.extractedText || ''),
    textPreview: cleanText(attachment?.textPreview || '', 1000),
    createdAt: normalizeTimestamp(attachment?.createdAt, Date.now()),
  }));
}

function serializeWorkbenchSession(session) {
  return {
    id: session.id,
    agentId: session.agentId,
    title: session.title,
    cwd: session.cwd,
    permissionMode: session.permissionMode || 'standard',
    state: session.state,
    agentSessionId: session.agentSessionId || '',
    createdAt: session.createdAt,
    updatedAt: session.updatedAt,
    archived: session.archived === true,
    dangerousExpiresAt: session.dangerousExpiresAt || 0,
    lastError: session.lastError || '',
    messages: session.messages || [],
    attachments: (session.attachments || []).map((attachment) => ({
      id: attachment.id,
      sessionId: attachment.sessionId,
      name: attachment.name,
      mime: attachment.mime,
      kind: attachment.kind,
      ext: attachment.ext,
      size: attachment.size,
      status: attachment.status,
      filePath: attachment.filePath,
      extractedText: attachment.extractedText || '',
      textPreview: attachment.textPreview || '',
      createdAt: attachment.createdAt,
    })),
  };
}

function persistSessions() {
  const payload = [...sessions.values()].map(serializeWorkbenchSession);
  return persistWorkbenchState(payload).catch((err) => {
    console.warn('[workbench] failed to persist state:', err?.message || err);
  });
}

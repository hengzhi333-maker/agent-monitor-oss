import fs from 'fs';
import path from 'path';
import { config } from './config.js';
import { listFiles, mtime, readJsonlCached } from './lib/jsonl.js';
import { redactText, truncateText } from './lib/redact.js';

const SUPPORTED_AGENTS = new Set(['codex', 'claude-code']);

export function listAgentSessions(agentId, options = {}) {
  if (!SUPPORTED_AGENTS.has(agentId)) return null;
  const max = clamp(Number(options.limit || 50), 1, 100);
  const files = sessionFiles(agentId);
  return files
    .map((file) => summarizeSessionFile(agentId, file))
    .filter(Boolean)
    .sort((a, b) => b.lastActivity - a.lastActivity)
    .slice(0, max);
}

export function getSessionMessages(agentId, sessionId, options = {}) {
  if (!SUPPORTED_AGENTS.has(agentId)) return null;
  const entry = sessionFiles(agentId)
    .map((file) => summarizeSessionFile(agentId, file))
    .find((session) => session && session.id === sessionId);
  if (!entry) return null;

  const rows = readJsonlCached(entry.file);
  const limit = clamp(Number(options.limit || 120), 1, 300);
  const messages =
    agentId === 'codex'
      ? codexMessages(rows, limit)
      : claudeMessages(rows, limit);

  return {
    agentId,
    session: publicSession(entry),
    updatedAt: mtime(entry.file),
    messages,
  };
}

function sessionFiles(agentId) {
  if (agentId === 'codex') {
    const roots = Array.isArray(config.paths.codexSessions)
      ? config.paths.codexSessions
      : [config.paths.codexSessions];
    const seen = new Set();
    const files = [];
    for (const root of roots) {
      if (!root || !fs.existsSync(root)) continue;
      for (const file of listFiles(root, (f) => /rollout-.*\.jsonl$/.test(path.basename(f)))) {
        if (seen.has(file)) continue;
        seen.add(file);
        files.push(file);
      }
    }
    return files.sort((a, b) => mtime(b) - mtime(a)).slice(0, 200);
  }

  const root = config.paths.claudeProjects;
  if (!root || !fs.existsSync(root)) return [];
  return listFiles(root, (f) => f.endsWith('.jsonl'))
    .sort((a, b) => mtime(b) - mtime(a))
    .slice(0, 200);
}

function summarizeSessionFile(agentId, file) {
  const rows = readJsonlCached(file);
  if (!rows.length) return null;

  let id = path.basename(file, '.jsonl').replace(/^rollout-/, '');
  let cwd = '';
  let model = '';
  let title = '';
  let lastActivity = mtime(file);
  let messageCount = 0;

  for (const row of rows) {
    const ts = toMs(row.timestamp);
    if (ts > lastActivity) lastActivity = ts;

    if (agentId === 'codex') {
      const p = row.payload || {};
      if (row.type === 'session_meta') {
        if (p.id) id = String(p.id);
        if (p.cwd) cwd = String(p.cwd);
        if (p.model) model = String(p.model);
      }
      if (row.type === 'turn_context' && p.cwd && !cwd) cwd = String(p.cwd);
      if (p.model) model = String(p.model);
      if (p.type === 'thread_name_updated' && p.thread_name) title = String(p.thread_name);
      if (p.type === 'user_message' && !title) title = firstLine(p.message);
      if (p.type === 'user_message' || p.type === 'agent_message') messageCount += 1;
    } else {
      if (row.sessionId) id = String(row.sessionId);
      if (row.cwd && !cwd) cwd = String(row.cwd);
      if (row.message?.model) model = String(row.message.model);
      if (row.type === 'ai-title' && row.aiTitle) title = String(row.aiTitle);
      if (row.type === 'last-prompt' && !title) title = String(row.lastPrompt || '');
      if ((row.type === 'user' || row.type === 'assistant') && row.message) {
        messageCount += 1;
        if (!title && row.type === 'user') title = firstLine(textFromContent(row.message.content));
      }
    }
  }

  return {
    id,
    title: redactText(title || path.basename(file, '.jsonl'), { maskEmails: true, maskLocalPaths: true }),
    cwd,
    model,
    lastActivity,
    messageCount,
    file,
  };
}

function publicSession(session) {
  const { file, ...safe } = session;
  return safe;
}

function codexMessages(rows, limit) {
  const messages = [];
  for (const row of rows) {
    const p = row.payload || {};
    const ts = toMs(row.timestamp);
    if (row.type === 'event_msg' && p.type === 'user_message') {
      addMessage(messages, {
        id: messageId(row, messages.length),
        role: 'user',
        kind: 'message',
        ts,
        text: p.message || '',
      });
    } else if (row.type === 'event_msg' && p.type === 'agent_message') {
      addMessage(messages, {
        id: messageId(row, messages.length),
        role: 'assistant',
        kind: p.phase || 'message',
        ts,
        text: p.message || '',
      });
    } else if (row.type === 'response_item' && p.type === 'function_call') {
      addMessage(messages, {
        id: p.call_id || messageId(row, messages.length),
        role: 'tool',
        kind: 'call',
        ts,
        title: p.name || 'tool_call',
        text: `${p.name || 'tool'} ${p.arguments || ''}`,
      });
    } else if (row.type === 'response_item' && p.type === 'function_call_output') {
      addMessage(messages, {
        id: p.call_id ? `${p.call_id}-output` : messageId(row, messages.length),
        role: 'tool',
        kind: 'output',
        ts,
        title: 'tool_output',
        text: p.output || '',
      });
    } else if (row.type === 'response_item' && p.type === 'web_search_call') {
      addMessage(messages, {
        id: messageId(row, messages.length),
        role: 'tool',
        kind: 'web_search',
        ts,
        title: 'web_search',
        text: textFromContent(p.action || p),
      });
    }
  }
  return messages.slice(-limit);
}

function claudeMessages(rows, limit) {
  const messages = [];
  for (const row of rows) {
    if ((row.type !== 'user' && row.type !== 'assistant') || !row.message) continue;
    addMessage(messages, {
      id: row.uuid || messageId(row, messages.length),
      role: row.type,
      kind: 'message',
      ts: toMs(row.timestamp),
      title: row.message.model || '',
      text: textFromContent(row.message.content),
    });
  }
  return messages.slice(-limit);
}

function addMessage(messages, message) {
  const raw = textFromContent(message.text);
  const text = truncateText(redactText(raw, { maskEmails: true, maskLocalPaths: true }), 5000).trim();
  if (!text) return;
  messages.push({
    id: String(message.id || `${message.role}-${messages.length}`),
    role: message.role,
    kind: message.kind || 'message',
    title: message.title || '',
    ts: message.ts || 0,
    text,
  });
}

function textFromContent(content) {
  if (content == null) return '';
  if (typeof content === 'string') return content;
  if (typeof content === 'number' || typeof content === 'boolean') return String(content);
  if (Array.isArray(content)) return content.map(textFromContent).filter(Boolean).join('\n');
  if (typeof content !== 'object') return '';

  if (typeof content.text === 'string') return content.text;
  if (typeof content.message === 'string') return content.message;
  if (typeof content.output === 'string') return content.output;
  if (content.type === 'tool_use') {
    return `[tool_use] ${content.name || ''} ${safeJson(content.input)}`;
  }
  if (content.type === 'tool_result') {
    return `[tool_result] ${textFromContent(content.content)}`;
  }
  if (content.type === 'image' || content.type === 'input_image') return '[image]';
  return safeJson(content);
}

function safeJson(value) {
  try {
    return JSON.stringify(value);
  } catch {
    return String(value || '');
  }
}

function firstLine(value) {
  return truncateText(String(value || '').split(/\r?\n/)[0], 80);
}

function messageId(row, index) {
  return `${row.timestamp || 'row'}-${index}`;
}

function toMs(value) {
  if (!value) return 0;
  const n = new Date(value).getTime();
  return Number.isFinite(n) ? n : 0;
}

function clamp(n, min, max) {
  if (!Number.isFinite(n)) return min;
  return Math.max(min, Math.min(max, n));
}

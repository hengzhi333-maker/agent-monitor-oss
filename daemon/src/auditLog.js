import fs from 'fs';
import path from 'path';
import { config } from './config.js';
import { redactText, truncateText } from './lib/redact.js';
import { requestRemoteAddress } from './accessControl.js';

const MAX_DETAIL_CHARS = 1200;

export function auditLogPath() {
  return path.resolve(process.env.AM_AUDIT_LOG_FILE || path.join(process.cwd(), '.agent-monitor-audit.jsonl'));
}

export function appendAudit(req, action, detail = {}) {
  const entry = {
    ts: Date.now(),
    action: String(action || 'unknown'),
    host: config.host,
    remoteAddress: req ? requestRemoteAddress(req) : '',
    ok: detail.ok !== false,
    ...sanitizeDetail(detail),
  };
  delete entry.token;
  const line = `${JSON.stringify(entry)}\n`;
  fs.promises
    .mkdir(path.dirname(auditLogPath()), { recursive: true })
    .then(() => fs.promises.appendFile(auditLogPath(), line, 'utf8'))
    .catch(() => {});
}

export function readAuditLog(limit = 50) {
  const max = Math.max(1, Math.min(Number(limit) || 50, 200));
  try {
    const raw = fs.readFileSync(auditLogPath(), 'utf8');
    return raw
      .split(/\r?\n/)
      .filter(Boolean)
      .slice(-max)
      .map((line) => {
        try {
          return JSON.parse(line);
        } catch {
          return null;
        }
      })
      .filter(Boolean)
      .reverse();
  } catch {
    return [];
  }
}

function sanitizeDetail(value) {
  if (!value || typeof value !== 'object') return {};
  const clean = {};
  for (const [key, item] of Object.entries(value)) {
    if (/token|authorization|password|secret/i.test(key)) continue;
    if (item == null) continue;
    if (typeof item === 'string') clean[key] = truncateText(redactText(item, { maskEmails: true, maskLocalPaths: true }), MAX_DETAIL_CHARS);
    else if (typeof item === 'number' || typeof item === 'boolean') clean[key] = item;
    else if (Array.isArray(item)) clean[key] = item.map((child) => sanitizeAuditValue(child)).slice(0, 20);
    else clean[key] = sanitizeAuditValue(item);
  }
  return clean;
}

function sanitizeAuditValue(value) {
  if (value == null) return value;
  if (typeof value === 'string') return truncateText(redactText(value, { maskEmails: true, maskLocalPaths: true }), MAX_DETAIL_CHARS);
  if (typeof value === 'number' || typeof value === 'boolean') return value;
  if (Array.isArray(value)) return value.map((item) => sanitizeAuditValue(item)).slice(0, 20);
  if (typeof value === 'object') {
    const clean = {};
    for (const [key, item] of Object.entries(value)) {
      if (/token|authorization|password|secret/i.test(key)) continue;
      clean[key] = sanitizeAuditValue(item);
    }
    return clean;
  }
  return String(value);
}

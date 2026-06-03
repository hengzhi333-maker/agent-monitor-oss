import fs from 'fs';
import path from 'path';

const LOG_FILE = process.env.AM_LOG_FILE || path.join(process.cwd(), '.agent-monitor.log.jsonl');
const MAX_BYTES = Number(process.env.AM_LOG_ROTATE_BYTES || 5 * 1024 * 1024);

export function logInfo(message, fields = {}) {
  writeLog('info', message, fields);
}

export function logWarn(message, fields = {}) {
  writeLog('warn', message, fields);
}

export function logError(message, fields = {}) {
  writeLog('error', message, fields);
}

export function logStatus() {
  const stat = safeStat(LOG_FILE);
  return {
    path: LOG_FILE,
    rotateBytes: MAX_BYTES,
    size: stat?.size || 0,
    exists: Boolean(stat),
  };
}

export function readRecentLogs(limit = 100) {
  if (!fs.existsSync(LOG_FILE)) return [];
  const raw = fs.readFileSync(LOG_FILE, 'utf8');
  return raw
    .split(/\r?\n/)
    .filter(Boolean)
    .slice(-Math.max(1, Math.min(Number(limit) || 100, 500)))
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch {
        return { ts: '', level: 'info', message: line };
      }
    });
}

function writeLog(level, message, fields) {
  const entry = {
    ts: new Date().toISOString(),
    level,
    message: String(message || ''),
    ...fields,
  };
  const line = `${JSON.stringify(entry)}\n`;
  rotateIfNeeded(Buffer.byteLength(line));
  fs.appendFileSync(LOG_FILE, line, 'utf8');
  const printer = level === 'error' ? console.error : level === 'warn' ? console.warn : console.log;
  printer(`[${entry.level}] ${entry.message}`);
}

function rotateIfNeeded(incomingBytes) {
  const stat = safeStat(LOG_FILE);
  if (!stat || stat.size + incomingBytes <= MAX_BYTES) return;
  const rotated = `${LOG_FILE}.1`;
  try {
    if (fs.existsSync(rotated)) fs.unlinkSync(rotated);
    fs.renameSync(LOG_FILE, rotated);
  } catch {
    // Keep runtime logging best-effort.
  }
}

function safeStat(file) {
  try {
    return fs.statSync(file);
  } catch {
    return null;
  }
}

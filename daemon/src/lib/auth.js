import crypto from 'crypto';
import { config } from '../config.js';

export const ROLE_ORDER = {
  'read-only': 1,
  operator: 2,
  admin: 3,
};

export function tokenOk(provided) {
  return Boolean(tokenAccess(provided));
}

export function tokenAccess(provided) {
  if (!provided) return null;
  const candidate = Buffer.from(provided);
  const records = [...(config.tokenRecords || [])];
  for (const token of config.tokens || []) {
    if (!records.some((record) => record.token === token)) records.push({ token, role: 'admin' });
  }
  for (const record of records) {
    if (record.enabled === false) continue;
    const expected = Buffer.from(record.token);
    if (candidate.length === expected.length && crypto.timingSafeEqual(candidate, expected)) {
      return {
        deviceId: String(record.id || ''),
        role: normalizeRole(record.role),
        name: String(record.name || ''),
      };
    }
  }
  return null;
}

export function extractToken(req) {
  const auth = req.headers['authorization'] || '';
  if (auth.startsWith('Bearer ')) return auth.slice(7);
  return '';
}

const failures = new Map();

export function clientIp(req) {
  return req.socket?.remoteAddress || 'unknown';
}

export function isRateLimited(ip) {
  const record = failures.get(ip);
  if (!record) return false;
  if (Date.now() - record.windowStart > config.authFailWindowMs) {
    failures.delete(ip);
    return false;
  }
  return record.count >= config.authFailMax;
}

export function noteAuthFailure(ip) {
  const now = Date.now();
  const record = failures.get(ip);
  if (!record || now - record.windowStart > config.authFailWindowMs) {
    failures.set(ip, { count: 1, windowStart: now });
    return;
  }
  record.count += 1;
}

export function noteAuthSuccess(ip) {
  failures.delete(ip);
}

export function clearAuthFailures() {
  failures.clear();
}

export function normalizeRole(role) {
  const value = String(role || '').trim().toLowerCase();
  if (value === 'read-only' || value === 'readonly' || value === 'viewer') return 'read-only';
  if (value === 'operator' || value === 'ops') return 'operator';
  if (value === 'admin' || value === 'owner') return 'admin';
  return 'admin';
}

export function hasRole(access, minimumRole) {
  const current = ROLE_ORDER[normalizeRole(access?.role)] || 0;
  const required = ROLE_ORDER[normalizeRole(minimumRole)] || ROLE_ORDER.admin;
  return current >= required;
}

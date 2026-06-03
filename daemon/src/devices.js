import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { config, persistConfigPatch } from './config.js';

const STATE_FILE = path.join(process.cwd(), '.agent-monitor-devices.json');

export function noteDeviceAccess(auth, req) {
  if (!auth?.deviceId) return;
  const state = readState();
  state[auth.deviceId] = {
    lastSeen: Date.now(),
    remoteAddress: req.socket?.remoteAddress || '',
    userAgent: req.headers?.['user-agent'] || '',
  };
  writeState(state);
}

export function listDevices() {
  const state = readState();
  return {
    devices: (config.tokenRecords || []).map((record) => publicDevice(record, state[record.id])),
  };
}

export function createDevice(input = {}) {
  const role = normalizeRole(input.role || 'read-only');
  const name = cleanDeviceName(input.name || `${role}-device`);
  const token = crypto.randomBytes(32).toString('hex');
  const id = uniqueDeviceId(name);
  persistConfigPatch((current) => ({
    ...current,
    tokens: [
      ...normalizeConfigTokenRecords(current),
      { id, name, role, token, enabled: true },
    ],
  }));
  const record = { id, name, role, token, enabled: true };
  config.tokenRecords.push(record);
  config.tokens.push(token);
  return {
    token,
    device: publicDevice(record),
  };
}

export function updateDevice(id, patch = {}) {
  const target = config.tokenRecords.find((record) => record.id === id);
  if (!target) throw apiError('DEVICE_NOT_FOUND', 'Device token was not found.', 404);
  if (patch.name != null) target.name = cleanDeviceName(patch.name);
  if (patch.role != null) target.role = normalizeRole(patch.role);
  if (patch.enabled != null) target.enabled = patch.enabled === true;
  persistDeviceRecords();
  return { device: publicDevice(target, readState()[target.id]) };
}

export function rotateDevice(id) {
  const target = config.tokenRecords.find((record) => record.id === id);
  if (!target) throw apiError('DEVICE_NOT_FOUND', 'Device token was not found.', 404);
  const oldToken = target.token;
  target.token = crypto.randomBytes(32).toString('hex');
  config.tokens = config.tokens.map((token) => (token === oldToken ? target.token : token));
  persistDeviceRecords();
  return {
    token: target.token,
    device: publicDevice(target, readState()[target.id]),
  };
}

export function deleteDevice(id) {
  const index = config.tokenRecords.findIndex((record) => record.id === id);
  if (index < 0) throw apiError('DEVICE_NOT_FOUND', 'Device token was not found.', 404);
  const [removed] = config.tokenRecords.splice(index, 1);
  config.tokens = config.tokens.filter((token) => token !== removed.token);
  persistDeviceRecords();
  return { deleted: true, id };
}

function persistDeviceRecords() {
  persistConfigPatch((current) => ({
    ...current,
    token: current.token || config.token,
    tokens: config.tokenRecords.map((record) => ({
      id: record.id,
      name: record.name,
      role: record.role,
      token: record.token,
      enabled: record.enabled !== false,
    })),
  }));
}

function normalizeConfigTokenRecords(current) {
  return (current.tokens || []).map((item) => {
    if (typeof item === 'string') return { id: '', name: '', role: 'admin', token: item, enabled: true };
    return item;
  });
}

function publicDevice(record, state = {}) {
  return {
    id: record.id || '',
    name: record.name || '',
    role: record.role || 'admin',
    enabled: record.enabled !== false,
    tokenPreview: previewToken(record.token),
    lastSeen: Number(state?.lastSeen || 0),
    remoteAddress: state?.remoteAddress || '',
    userAgent: state?.userAgent || '',
  };
}

function uniqueDeviceId(name) {
  const base = cleanDeviceName(name).toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '') || 'device';
  const existing = new Set((config.tokenRecords || []).map((record) => record.id));
  if (!existing.has(base)) return base;
  for (let i = 2; i < 1000; i += 1) {
    const candidate = `${base}-${i}`;
    if (!existing.has(candidate)) return candidate;
  }
  return `${base}-${crypto.randomBytes(3).toString('hex')}`;
}

function cleanDeviceName(value) {
  return String(value || '').trim().slice(0, 80) || 'device';
}

function normalizeRole(value) {
  const role = String(value || '').trim().toLowerCase();
  if (role === 'read-only' || role === 'readonly' || role === 'viewer') return 'read-only';
  if (role === 'operator' || role === 'ops') return 'operator';
  return 'admin';
}

function previewToken(token) {
  const value = String(token || '');
  if (value.length <= 8) return '***';
  return `${value.slice(0, 4)}...${value.slice(-4)}`;
}

function readState() {
  try {
    return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
  } catch {
    return {};
  }
}

function writeState(state) {
  try {
    fs.writeFileSync(STATE_FILE, `${JSON.stringify(state, null, 2)}\n`, 'utf8');
  } catch {
    // Device telemetry is best-effort only.
  }
}

function apiError(code, message, status = 500) {
  const error = new Error(message);
  error.code = code;
  error.status = status;
  return error;
}

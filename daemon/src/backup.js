import { CONFIG_FILE, persistConfigPatch } from './config.js';
import fs from 'fs';

export function exportDaemonBackup(options = {}) {
  const includeSecrets = options.includeSecrets === true;
  const configJson = readConfigJson();
  return {
    format: 'agent-monitor.daemon-backup.v1',
    exportedAt: new Date().toISOString(),
    includeSecrets,
    config: includeSecrets ? configJson : redactConfig(configJson),
  };
}

export function importDaemonBackup(input = {}) {
  if (input.format !== 'agent-monitor.daemon-backup.v1' || !input.config || typeof input.config !== 'object') {
    throw apiError('INVALID_BACKUP', 'Backup payload is not an agent-monitor daemon backup.', 400);
  }
  const next = sanitizeImportedConfig(input.config);
  persistConfigPatch(() => next);
  return {
    ok: true,
    restartRequired: true,
    configFile: CONFIG_FILE,
  };
}

function readConfigJson() {
  try {
    return JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8').replace(/^\uFEFF/, ''));
  } catch {
    return {};
  }
}

function redactConfig(value) {
  const copy = structuredClone(value || {});
  if (copy.token) copy.token = '';
  if (Array.isArray(copy.tokens)) {
    copy.tokens = copy.tokens.map((item) => {
      if (typeof item === 'string') return '';
      if (item && typeof item === 'object') return { ...item, token: '' };
      return item;
    });
  }
  if (copy.tls) {
    copy.tls = { ...copy.tls, cert: copy.tls.cert || '', key: copy.tls.key || '' };
  }
  return copy;
}

function sanitizeImportedConfig(value) {
  const copy = structuredClone(value || {});
  delete copy.__proto__;
  delete copy.constructor;
  delete copy.prototype;
  return copy;
}

function apiError(code, message, status = 500) {
  const error = new Error(message);
  error.code = code;
  error.status = status;
  return error;
}

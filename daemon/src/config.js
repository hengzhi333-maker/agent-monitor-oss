import os from 'os';
import path from 'path';
import fs from 'fs';
import crypto from 'crypto';

const home = os.homedir();
export const CONFIG_FILE = path.join(process.cwd(), 'config.json');

function loadFileConfig() {
  try {
    return JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8').replace(/^\uFEFF/, ''));
  } catch {
    return {};
  }
}

const file = loadFileConfig();

export function persistConfigPatch(mutator) {
  const current = loadFileConfig();
  const next = mutator(structuredClone(current));
  fs.writeFileSync(CONFIG_FILE, `${JSON.stringify(next, null, 2)}\n`, 'utf8');
  return next;
}

function unique(items) {
  return [...new Set(items.filter(Boolean))];
}

function normalizePathList(value, fallback) {
  if (Array.isArray(value)) return unique(value);
  if (typeof value === 'string' && value.trim()) return [value];
  return fallback;
}

function normalizeStringList(value, fallback = []) {
  if (Array.isArray(value)) return unique(value.map((item) => String(item).trim()));
  if (typeof value === 'string' && value.trim()) {
    return unique(value.split(',').map((item) => item.trim()));
  }
  return fallback;
}

function resolveTokens() {
  return resolveTokenRecords().map((record) => record.token);
}

function normalizeTokenRole(value, fallback = 'admin') {
  const role = String(value || '').trim().toLowerCase();
  if (role === 'read-only' || role === 'readonly' || role === 'viewer' || role === 'view') return 'read-only';
  if (role === 'operator' || role === 'ops' || role === 'control') return 'operator';
  if (role === 'admin' || role === 'owner' || role === 'full') return 'admin';
  return fallback;
}

function roleWeight(role) {
  return { 'read-only': 1, operator: 2, admin: 3 }[normalizeTokenRole(role, 'read-only')] || 1;
}

function strongestRole(left, right) {
  return roleWeight(left) >= roleWeight(right) ? normalizeTokenRole(left) : normalizeTokenRole(right);
}

function addTokenRecord(records, token, role = 'admin', name = '') {
  const value = String(token || '').trim();
  if (!value) return;
  const existing = records.find((record) => record.token === value);
  if (existing) {
    existing.role = strongestRole(existing.role, role);
    if (!existing.name && name) existing.name = String(name).trim();
    return;
  }
  records.push({
    id: tokenId(value, name),
    token: value,
    role: normalizeTokenRole(role),
    name: String(name || '').trim(),
    enabled: true,
  });
}

function addTokenList(records, value, role) {
  for (const token of normalizeStringList(value, [])) addTokenRecord(records, token, role);
}

function resolveTokenRecords() {
  const records = [];
  addTokenRecord(records, process.env.AM_TOKEN || file.token, 'admin', 'primary');
  addTokenList(records, process.env.AM_ADMIN_TOKENS, 'admin');
  addTokenList(records, process.env.AM_OPERATOR_TOKENS, 'operator');
  addTokenList(records, process.env.AM_READONLY_TOKENS, 'read-only');

  if (Array.isArray(file.tokens)) {
    for (const item of file.tokens) {
      if (typeof item === 'string') {
        addTokenRecord(records, item, 'admin');
      } else if (item && typeof item === 'object') {
        addTokenRecord(records, item.token, item.role || item.scope || 'admin', item.name || item.id || '');
        const added = records.find((record) => record.token === String(item.token || '').trim());
        if (added) {
          added.id = String(item.id || added.id);
          added.enabled = item.enabled !== false;
        }
      }
    }
  }

  const nonDefault = records.filter((record) => record.token !== 'change-me-please');
  return nonDefault.length
    ? nonDefault
    : [{ id: 'default', token: 'change-me-please', role: 'admin', name: 'default', enabled: true }];
}

function tokenId(token, name = '') {
  const preferred = String(name || '').trim().toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '');
  if (preferred) return preferred;
  return `dev_${crypto.createHash('sha256').update(String(token)).digest('hex').slice(0, 12)}`;
}

function resolveTls() {
  const certPath = process.env.AM_TLS_CERT || file.tls?.cert;
  const keyPath = process.env.AM_TLS_KEY || file.tls?.key;
  if (!certPath || !keyPath) return { enabled: false };
  try {
    return {
      enabled: true,
      cert: fs.readFileSync(certPath),
      key: fs.readFileSync(keyPath),
      certPath,
      keyPath,
    };
  } catch (err) {
    throw new Error(`TLS certificate read failed: ${err.message}`);
  }
}

function normalizePermissionMode(value, fallback = 'standard') {
  const mode = String(value || '').trim().toLowerCase();
  if (mode === 'read-only' || mode === 'readonly' || mode === 'read') return 'read-only';
  if (mode === 'dangerous' || mode === 'full-access' || mode === 'bypass') return 'dangerous';
  return fallback;
}

function defaultWorkbenchRoot() {
  return path.join(home, 'Documents', 'Codex');
}

function clampNumber(value, min, max, fallback) {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.max(min, Math.min(max, Math.floor(n)));
}

function normalizeRemoteControl(value = {}) {
  const defaultRoot = defaultWorkbenchRoot();
  const allowed = normalizePathList(value.allowedCwds, [defaultRoot])
    .map((item) => path.resolve(String(item)));
  const defaultCwd = path.resolve(String(value.defaultCwd || allowed[0] || defaultRoot));
  return {
    enabled: process.env.AM_REMOTE_CONTROL === '1' || value.enabled === true,
    allowDangerousPermissions:
      process.env.AM_REMOTE_DANGEROUS === '1' || value.allowDangerousPermissions === true,
    defaultPermissionMode: normalizePermissionMode(value.defaultPermissionMode, 'standard'),
    allowedRemoteAddresses: normalizeStringList(
      process.env.AM_ALLOWED_REMOTE_ADDRESSES || value.allowedRemoteAddresses,
      []
    ),
    defaultCwd,
    allowedCwds: allowed,
    maxSessions: clampNumber(value.maxSessions, 1, 100, 20),
    maxOutputChars: clampNumber(value.maxOutputChars, 10000, 1000000, 200000),
    dangerousSessionTtlMs: clampNumber(
      process.env.AM_DANGEROUS_SESSION_TTL_MS || value.dangerousSessionTtlMs,
      60 * 1000,
      24 * 60 * 60 * 1000,
      30 * 60 * 1000
    ),
    attachmentTtlHours: clampNumber(
      process.env.AM_WORKBENCH_ATTACHMENT_TTL_HOURS || value.attachmentTtlHours,
      0,
      8760,
      168
    ),
  };
}

function normalizeAlertRules(value = {}) {
  const recoveryEnv = process.env.AM_ALERT_RECOVERY_NOTIFICATIONS;
  const quiet = value.quietHours || {};
  return {
    agentOfflineGraceMs: clampNumber(
      process.env.AM_ALERT_AGENT_OFFLINE_GRACE_MS ?? value.agentOfflineGraceMs,
      0,
      60 * 60 * 1000,
      0
    ),
    serviceFailureCount: clampNumber(
      process.env.AM_ALERT_SERVICE_FAILURE_COUNT ?? value.serviceFailureCount,
      1,
      20,
      1
    ),
    recoveryNotifications:
      recoveryEnv === '0' || recoveryEnv === 'false' ? false : value.recoveryNotifications !== false,
    cooldownMs: clampNumber(
      process.env.AM_ALERT_COOLDOWN_MS ?? value.cooldownMs,
      0,
      24 * 60 * 60 * 1000,
      5 * 60 * 1000
    ),
    quietHours: {
      enabled: process.env.AM_ALERT_QUIET_HOURS === '1' || quiet.enabled === true,
      start: String(process.env.AM_ALERT_QUIET_START || quiet.start || '22:00'),
      end: String(process.env.AM_ALERT_QUIET_END || quiet.end || '08:00'),
      timezoneOffsetMinutes: clampNumber(
        process.env.AM_ALERT_QUIET_TZ_OFFSET_MINUTES ?? quiet.timezoneOffsetMinutes,
        -14 * 60,
        14 * 60,
        -new Date().getTimezoneOffset()
      ),
      suppressBelow: normalizeAlertLevel(process.env.AM_ALERT_QUIET_SUPPRESS_BELOW || quiet.suppressBelow, 'error'),
    },
  };
}

function normalizeAlertLevel(value, fallback = 'warn') {
  const level = String(value || '').trim().toLowerCase();
  if (level === 'info' || level === 'warn' || level === 'warning' || level === 'error') {
    return level === 'warning' ? 'warn' : level;
  }
  return fallback;
}

function defaultCodexSessionRoots() {
  const appData = process.env.APPDATA || path.join(home, 'AppData', 'Roaming');
  return unique([
    path.join(home, '.codex', 'sessions'),
    path.join(appData, 'CodexDesktop-Rebuild', 'codex-home', 'sessions'),
  ]);
}

const defaultServices = [
  { id: 'litellm', name: 'LiteLLM 代理', url: 'http://127.0.0.1:4000/health/liveliness' },
  { id: 'sub2api-8320', name: 'sub2api (8320)', url: 'http://127.0.0.1:8320/health' },
  { id: 'sub2api-8330', name: 'sub2api (8330)', url: 'http://127.0.0.1:8330/health', accountHealth: false },
];

function normalizeServices(services) {
  return services.map((service) => {
    if (service.accountHealth === false) {
      const { accountHealth, ...rest } = service;
      return rest;
    }
    if (service.accountHealth) return service;
    if (!looksLikeSub2Api(service)) return service;

    const accountHealth = defaultSub2ApiAccountHealth(service.url);
    return accountHealth ? { ...service, accountHealth } : service;
  });
}

function looksLikeSub2Api(service) {
  const text = `${service.id || ''} ${service.name || ''} ${service.url || ''}`.toLowerCase();
  return text.includes('sub2api') || text.includes('sub2ai');
}

function defaultSub2ApiAccountHealth(healthUrl) {
  try {
    const url = new URL(healthUrl);
    const port = url.port || (url.protocol === 'https:' ? '443' : '80');
    url.pathname = '/api/v1/admin/accounts';
    url.search = '';
    return {
      url: url.toString(),
      tokenEnv: `SUB2API_${port}_TOKEN`,
      fallbackTokenEnv: 'SUB2API_ADMIN_TOKEN',
      pageSize: 100,
      maxPages: 5,
      maxAccounts: 200,
    };
  } catch {
    return null;
  }
}

const tokenRecords = resolveTokenRecords();
const tokens = tokenRecords.map((record) => record.token);

export const config = {
  // 这台主机在 app 里显示的名字
  host: process.env.AM_HOST_NAME || file.host || os.hostname(),
  // daemon 监听端口
  port: Number(process.env.AM_PORT || file.port || 8765),
  // 默认监听全部网卡;长期使用时可设为 Tailscale IP 或 127.0.0.1
  bindHost: process.env.AM_BIND_HOST || file.bindHost || '0.0.0.0',
  // 鉴权 token —— 手机端必须带其中一个 token 才能连。务必修改默认值!
  token: tokens[0],
  tokens,
  tokenRecords,
  tls: resolveTls(),
  authFailWindowMs: clampNumber(file.authFailWindowMs, 1000, 60 * 60 * 1000, 60 * 1000),
  authFailMax: clampNumber(file.authFailMax, 1, 1000, 10),
  corsOrigin: process.env.AM_CORS_ORIGIN || file.corsOrigin || '',
  pingExposeHost: process.env.AM_PING_EXPOSE_HOST === '1' || file.pingExposeHost === true,
  allowDefaultToken: process.env.AM_ALLOW_DEFAULT_TOKEN === '1' || file.allowDefaultToken === true,
  privacy: {
    maskAccountEmails:
      process.env.AM_MASK_ACCOUNT_EMAILS === '1' || file.privacy?.maskAccountEmails === true,
    redactCwd:
      process.env.AM_REDACT_CWD === '1' || file.privacy?.redactCwd === true || file.redactCwd === true,
    hermesStatusMaxLen: clampNumber(
      process.env.AM_HERMES_STATUS_MAX_LEN ?? file.privacy?.hermesStatusMaxLen ?? file.hermesStatusMaxLen,
      0,
      10000,
      600
    ),
  },
  // 轮询采集间隔(毫秒)
  remoteControl: normalizeRemoteControl(file.remoteControl),
  alertRules: normalizeAlertRules(file.alertRules),
  pollIntervalMs: Number(process.env.AM_POLL_MS || file.pollIntervalMs || 4000),
  // 判定 active/idle 的时间窗(毫秒)
  activeWindowMs: Number(file.activeWindowMs || 2 * 60 * 1000),
  idleWindowMs: Number(file.idleWindowMs || 30 * 60 * 1000),
  paths: {
    claudeProjects: file.claudeProjects || path.join(home, '.claude', 'projects'),
    codexSessions: normalizePathList(file.codexSessions, defaultCodexSessionRoots()),
    hermesStatusFile: file.hermesStatusFile || path.join(home, '.hermes_tmp_status.txt'),
  },
  // sub2api / litellm 健康探测端点(/health 类端点免鉴权)
  services: normalizeServices(file.services || defaultServices),
};

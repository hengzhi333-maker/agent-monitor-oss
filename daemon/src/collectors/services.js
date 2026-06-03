import { config } from '../config.js';
import { maskEmail, redactText } from '../lib/redact.js';

// sub2api / litellm health checks. sub2api can also expose account-level health
// when an authenticated admin accounts endpoint is configured.
const loginTokenCache = new Map();

export async function collectServices() {
  const checks = config.services.map(async (service) => {
    const base = await collectServiceHealth(service);
    if (service.accountHealth) {
      base.accountHealth = await collectAccountHealth(service.accountHealth);
    }
    return base;
  });
  return Promise.all(checks);
}

async function collectServiceHealth(service) {
  const t0 = Date.now();
  try {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 3000);
    const res = await fetch(service.url, { signal: ctrl.signal });
    clearTimeout(timer);
    return {
      id: service.id,
      name: service.name,
      url: service.url,
      state: res.ok ? 'up' : 'down',
      httpCode: res.status,
      latencyMs: Date.now() - t0,
    };
  } catch (e) {
    return {
      id: service.id,
      name: service.name,
      url: service.url,
      state: 'down',
      httpCode: 0,
      latencyMs: Date.now() - t0,
      error: e.name === 'AbortError' ? 'timeout' : String(e.message || e),
    };
  }
}

async function collectAccountHealth(accountHealth) {
  const started = Date.now();
  if (!accountHealth.url) {
    return emptyAccountHealth('not_configured', '未配置账号接口');
  }

  let token;
  try {
    token = await resolveToken(accountHealth);
  } catch (e) {
    const status = e && typeof e.status === 'number' ? e.status : 0;
    return emptyAccountHealth(
      status === 401 || status === 403 ? 'unauthorized' : 'error',
      status ? `HTTP ${status}` : String(e.message || e),
      Date.now() - started
    );
  }

  if (!token) {
    return emptyAccountHealth('not_configured', '未配置 sub2api 管理 token');
  }

  try {
    const accounts = await fetchAccountsWithRetry(accountHealth, token);
    const normalized = accounts.map(normalizeAccount);
    const counts = countAccounts(normalized);
    const maxAccounts = Number(accountHealth.maxAccounts || 200);
    return {
      state: 'ok',
      total: counts.total,
      healthy: counts.healthy,
      warning: counts.warning,
      error: counts.error,
      disabled: counts.disabled,
      checkedAt: Date.now(),
      latencyMs: Date.now() - started,
      accounts: normalized
        .filter((account) => account.state !== 'healthy')
        .concat(normalized.filter((account) => account.state === 'healthy'))
        .slice(0, clamp(maxAccounts, 0, 500)),
    };
  } catch (e) {
    const status = e && typeof e.status === 'number' ? e.status : 0;
    return emptyAccountHealth(
      status === 401 || status === 403 ? 'unauthorized' : 'error',
      status ? `HTTP ${status}` : String(e.message || e),
      Date.now() - started
    );
  }
}

async function fetchAccountsWithRetry(accountHealth, token) {
  try {
    return await fetchAccounts(accountHealth, token);
  } catch (e) {
    const status = e && typeof e.status === 'number' ? e.status : 0;
    if ((status === 401 || status === 403) && hasLoginCredentials(accountHealth)) {
      clearLoginToken(accountHealth);
      const retryToken = await resolveLoginToken(accountHealth, { force: true });
      return fetchAccounts(accountHealth, retryToken);
    }
    throw e;
  }
}

async function fetchAccounts(accountHealth, token) {
  const pageSize = clamp(Number(accountHealth.pageSize || 100), 1, 500);
  const maxPages = clamp(Number(accountHealth.maxPages || 5), 1, 20);
  const out = [];

  for (let page = 1; page <= maxPages; page += 1) {
    const url = new URL(accountHealth.url);
    url.searchParams.set('page', String(page));
    url.searchParams.set('page_size', String(pageSize));

    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), Number(accountHealth.timeoutMs || 5000));
    let res;
    try {
      res = await fetch(url, {
        signal: ctrl.signal,
        headers: {
          Authorization: `Bearer ${token}`,
          Accept: 'application/json',
        },
      });
    } finally {
      clearTimeout(timer);
    }

    if (!res.ok) {
      const err = new Error(`HTTP ${res.status}`);
      err.status = res.status;
      throw err;
    }

    const raw = await res.json();
    const pageData = unwrapApiResponse(raw);
    const items = extractItems(pageData);
    out.push(...items);

    const total = extractTotal(pageData);
    if (items.length < pageSize || (total && out.length >= total)) break;
  }

  return out;
}

async function resolveToken(accountHealth) {
  if (accountHealth.token) return accountHealth.token;
  if (accountHealth.tokenEnv && process.env[accountHealth.tokenEnv]) {
    return process.env[accountHealth.tokenEnv];
  }
  if (accountHealth.fallbackTokenEnv && process.env[accountHealth.fallbackTokenEnv]) {
    return process.env[accountHealth.fallbackTokenEnv];
  }
  return resolveLoginToken(accountHealth);
}

async function resolveLoginToken(accountHealth, options = {}) {
  const login = resolveLoginConfig(accountHealth);
  if (!login) return '';

  const cached = loginTokenCache.get(login.cacheKey);
  if (!options.force && cached && cached.expiresAt > Date.now() + 60 * 1000) {
    return cached.token;
  }

  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), Number(login.timeoutMs || accountHealth.timeoutMs || 5000));
  let res;
  try {
    res = await fetch(login.url, {
      method: 'POST',
      signal: ctrl.signal,
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify({ email: login.email, password: login.password }),
    });
  } finally {
    clearTimeout(timer);
  }

  if (!res.ok) {
    const err = new Error(`HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }

  const raw = await res.json();
  const data = unwrapApiResponse(raw);
  if (data && data.requires_2fa) {
    throw new Error('sub2api admin login requires 2FA');
  }

  const token = String((data && (data.access_token || data.token || data.jwt)) || '');
  if (!token) {
    throw new Error('sub2api admin login did not return access_token');
  }

  const expiresIn = Number(data.expires_in || data.expiresIn || 3600);
  loginTokenCache.set(login.cacheKey, {
    token,
    expiresAt: Date.now() + Math.max(60, Number.isFinite(expiresIn) ? expiresIn : 3600) * 1000,
  });
  return token;
}

function clearLoginToken(accountHealth) {
  const login = resolveLoginConfig(accountHealth);
  if (login) loginTokenCache.delete(login.cacheKey);
}

function hasLoginCredentials(accountHealth) {
  return Boolean(resolveLoginConfig(accountHealth));
}

function resolveLoginConfig(accountHealth) {
  const login = accountHealth.login || {};
  const url = login.url || accountHealth.loginUrl || defaultLoginUrl(accountHealth.url);
  const email = login.email || accountHealth.email || envValue(login.emailEnv, accountHealth.emailEnv, 'SUB2API_ADMIN_EMAIL');
  const password =
    login.password ||
    accountHealth.password ||
    envValue(login.passwordEnv, accountHealth.passwordEnv, 'SUB2API_ADMIN_PASSWORD');

  if (!url || !email || !password) return null;
  return {
    url,
    email,
    password,
    timeoutMs: login.timeoutMs || accountHealth.loginTimeoutMs,
    cacheKey: `${url}\n${email}`,
  };
}

function defaultLoginUrl(accountsUrl) {
  try {
    const url = new URL(accountsUrl);
    url.pathname = '/api/v1/auth/login';
    url.search = '';
    return url.toString();
  } catch {
    return '';
  }
}

function envValue(...names) {
  for (const name of names) {
    if (name && process.env[name]) return process.env[name];
  }
  return '';
}

function unwrapApiResponse(raw) {
  if (raw && typeof raw === 'object' && 'code' in raw && 'data' in raw) return raw.data;
  return raw;
}

function extractItems(data) {
  if (Array.isArray(data)) return data;
  if (!data || typeof data !== 'object') return [];
  for (const key of ['items', 'accounts', 'list', 'rows', 'data']) {
    if (Array.isArray(data[key])) return data[key];
  }
  return [];
}

function extractTotal(data) {
  if (!data || typeof data !== 'object') return 0;
  return Number(
    data.total ||
      data.total_items ||
      data.totalItems ||
      (data.pagination && (data.pagination.total || data.pagination.totalItems)) ||
      0
  );
}

function normalizeAccount(account) {
  const status = String(account.status || '').toLowerCase();
  const groups = normalizeGroups(account);
  const disabled =
    status === 'inactive' ||
    status === 'disabled' ||
    account.enabled === false ||
    account.is_enabled === false ||
    account.disabled === true;
  const hasError =
    status === 'error' || Boolean(account.error_message || account.last_error || account.error);
  const rateLimited = future(account.rate_limit_reset_at);
  const overloaded = future(account.overload_until);
  const tempUnschedulable = future(account.temp_unschedulable_until);
  const paused = account.schedulable === false;
  const quotaExceeded =
    quotaReached(account.quota_used, account.quota_limit) ||
    quotaReached(account.quota_daily_used, account.quota_daily_limit) ||
    quotaReached(account.quota_weekly_used, account.quota_weekly_limit);

  let state = 'healthy';
  if (disabled) state = 'disabled';
  else if (hasError) state = 'error';
  else if (rateLimited || overloaded || tempUnschedulable || paused || quotaExceeded) {
    state = 'warning';
  }

  return {
    id: String(account.id || account.account_id || account.name || ''),
    name: accountName(account),
    platform: String(account.platform || ''),
    type: String(account.type || ''),
    status: status || 'unknown',
    state,
    schedulable: account.schedulable !== false,
    error: redactText(account.error_message || account.last_error || account.error || '', {
      maskEmails: config.privacy.maskAccountEmails,
    }),
    rateLimitResetAt: account.rate_limit_reset_at || '',
    overloadUntil: account.overload_until || '',
    tempUnschedulableUntil: account.temp_unschedulable_until || '',
    groups,
    groupIds: groups.map((group) => group.id).filter(Boolean),
    groupNames: groups.map((group) => group.name).filter(Boolean),
    quota: {
      used: num(account.quota_used),
      limit: num(account.quota_limit),
      dailyUsed: num(account.quota_daily_used),
      dailyLimit: num(account.quota_daily_limit),
      weeklyUsed: num(account.quota_weekly_used),
      weeklyLimit: num(account.quota_weekly_limit),
    },
  };
}

function accountName(account) {
  const name = String(account.name || account.email || account.account_name || account.id || '未命名账号').trim();
  return config.privacy.maskAccountEmails ? maskEmail(name) : name;
}

function normalizeGroups(account) {
  const groups = [];
  const add = (group) => {
    if (!group) return;
    const id = String(group.id || group.group_id || '').trim();
    const name = String(group.name || group.group_name || '').trim();
    if (!id && !name) return;
    groups.push({
      id,
      name: name || (id ? `分组 ${id}` : ''),
      platform: String(group.platform || '').trim(),
      status: String(group.status || '').trim(),
    });
  };

  if (Array.isArray(account.groups)) {
    account.groups.forEach(add);
  }
  if (Array.isArray(account.account_groups)) {
    account.account_groups.forEach(add);
  }
  if (Array.isArray(account.group_ids)) {
    account.group_ids.forEach((id) => add({ id }));
  }

  const seen = new Set();
  return groups.filter((group) => {
    const key = group.id || group.name;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function countAccounts(accounts) {
  return accounts.reduce(
    (acc, account) => {
      acc.total += 1;
      if (account.state === 'healthy') acc.healthy += 1;
      else if (account.state === 'warning') acc.warning += 1;
      else if (account.state === 'error') acc.error += 1;
      else if (account.state === 'disabled') acc.disabled += 1;
      return acc;
    },
    { total: 0, healthy: 0, warning: 0, error: 0, disabled: 0 }
  );
}

function emptyAccountHealth(state, message, latencyMs = 0) {
  return {
    state,
    total: 0,
    healthy: 0,
    warning: 0,
    error: state === 'ok' ? 0 : 1,
    disabled: 0,
    checkedAt: Date.now(),
    latencyMs,
    message,
    accounts: [],
  };
}

function future(value) {
  if (!value) return false;
  const ts = new Date(value).getTime();
  return Number.isFinite(ts) && ts > Date.now();
}

function quotaReached(used, limit) {
  return typeof used === 'number' && typeof limit === 'number' && limit > 0 && used >= limit;
}

function num(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function clamp(value, min, max) {
  if (!Number.isFinite(value)) return min;
  return Math.max(min, Math.min(max, Math.trunc(value)));
}

import path from 'path';
import { collectClaude } from './collectors/claude.js';
import { collectCodex } from './collectors/codex.js';
import { collectHermes } from './collectors/hermes.js';
import { collectServices } from './collectors/services.js';
import { config } from './config.js';

const alertState = {
  agents: new Map(),
  services: new Map(),
  emittedAt: new Map(),
};

export async function buildSnapshot() {
  const [claude, codex, hermes, services] = await Promise.all([
    Promise.resolve().then(collectClaude),
    Promise.resolve().then(collectCodex),
    collectHermes(),
    collectServices(),
  ]);

  return redactSnapshot({
    host: config.host,
    ts: Date.now(),
    agents: [claude, codex, hermes],
    services,
  });
}

export function redactSnapshot(snap) {
  if (!config.privacy.redactCwd && config.privacy.hermesStatusMaxLen >= 600) return snap;
  for (const agent of snap.agents || []) {
    if (config.privacy.redactCwd && Array.isArray(agent.sessions)) {
      for (const session of agent.sessions) {
        if (session.cwd) session.cwd = path.basename(session.cwd) || session.cwd;
      }
    }
    if (agent.detail && typeof agent.detail.status === 'string') {
      if (config.privacy.hermesStatusMaxLen <= 0) agent.detail.status = '';
      else agent.detail.status = agent.detail.status.slice(0, config.privacy.hermesStatusMaxLen);
    }
  }
  return snap;
}

export function clearAlertState() {
  alertState.agents.clear();
  alertState.services.clear();
  alertState.emittedAt.clear();
}

export function diffAlerts(prev, next) {
  const alerts = [];
  if (!prev || !next) return alerts;
  const ts = Number(next.ts || Date.now());
  const rules = config.alertRules || {};

  const prevAgents = Object.fromEntries((prev.agents || []).map((agent) => [agent.id, agent]));
  for (const agent of next.agents || []) {
    const before = prevAgents[agent.id];
    if (!before) continue;
    const key = agent.id || agent.name;

    if (agent.state === 'offline') {
      const existing = alertState.agents.get(key) || {};
      const offlineSince = before.state !== 'offline' || !existing.offlineSince ? ts : existing.offlineSince;
      const state = {
        offlineSince,
        offlineAlerted: existing.offlineAlerted === true,
      };
      const graceMs = Number(rules.agentOfflineGraceMs || 0);
      if (!state.offlineAlerted && ts - offlineSince >= graceMs) {
        addAlert(alerts, `agent:${key}:offline`, {
          level: 'warn',
          agent: agent.id,
          title: `${agent.name || agent.id} offline`,
          body: `${config.host}: ${agent.name || agent.id} changed from ${before.state} to offline`,
          ts,
        }, ts);
        state.offlineAlerted = true;
      }
      alertState.agents.set(key, state);
      continue;
    }

    if (before.state === 'offline' && agent.state !== 'offline' && rules.recoveryNotifications !== false) {
      addAlert(alerts, `agent:${key}:recovered`, {
        level: 'info',
        agent: agent.id,
        title: `${agent.name || agent.id} recovered`,
        body: `${config.host}: ${agent.name || agent.id} changed from offline to ${agent.state}`,
        ts,
      }, ts);
    }
    alertState.agents.delete(key);
  }

  const prevServices = Object.fromEntries((prev.services || []).map((service) => [service.id, service]));
  for (const service of next.services || []) {
    const before = prevServices[service.id];
    if (!before) continue;
    const key = service.id || service.name;

    if (service.state === 'down') {
      const existing = alertState.services.get(key) || {};
      const downCount = before.state === 'down' ? Number(existing.downCount || 0) + 1 : 1;
      const state = {
        downCount,
        downAlerted: existing.downAlerted === true,
      };
      if (!state.downAlerted && downCount >= Number(rules.serviceFailureCount || 1)) {
        addAlert(alerts, `service:${key}:down`, {
          level: 'error',
          agent: service.id,
          title: `${service.name || service.id} unavailable`,
          body: `${config.host}: ${service.name || service.id} health check failed (${service.error || service.httpCode || 'down'})`,
          ts,
        }, ts);
        state.downAlerted = true;
      }
      alertState.services.set(key, state);
      continue;
    }

    if (before.state === 'down' && service.state === 'up' && rules.recoveryNotifications !== false) {
      addAlert(alerts, `service:${key}:recovered`, {
        level: 'info',
        agent: service.id,
        title: `${service.name || service.id} recovered`,
        body: `${config.host}: ${service.name || service.id} health check recovered`,
        ts,
      }, ts);
    }
    alertState.services.delete(key);
  }

  return alerts;
}

function addAlert(alerts, key, alert, ts) {
  if (!shouldEmitAlert(key, alert.level, ts)) return;
  alerts.push(alert);
}

function shouldEmitAlert(key, level, ts) {
  const rules = config.alertRules || {};
  if (isQuieted(level, ts, rules.quietHours)) return false;

  const cooldownMs = Number(rules.cooldownMs || 0);
  if (cooldownMs <= 0) return true;
  const last = alertState.emittedAt.get(key) || 0;
  if (last && ts - last < cooldownMs) return false;
  alertState.emittedAt.set(key, ts);
  return true;
}

function isQuieted(level, ts, quiet = {}) {
  if (!quiet?.enabled) return false;
  return alertLevelWeight(level) < alertLevelWeight(quiet.suppressBelow || 'error') && isInsideQuietHours(ts, quiet);
}

function alertLevelWeight(level) {
  const normalized = String(level || '').toLowerCase();
  if (normalized === 'error') return 3;
  if (normalized === 'warn' || normalized === 'warning') return 2;
  return 1;
}

function isInsideQuietHours(ts, quiet) {
  const start = parseClockMinutes(quiet.start, 22 * 60);
  const end = parseClockMinutes(quiet.end, 8 * 60);
  const offset = Number(quiet.timezoneOffsetMinutes || 0);
  const local = new Date(Number(ts || Date.now()) + offset * 60 * 1000);
  const minute = local.getUTCHours() * 60 + local.getUTCMinutes();
  if (start === end) return false;
  if (start < end) return minute >= start && minute < end;
  return minute >= start || minute < end;
}

function parseClockMinutes(value, fallback) {
  const match = /^(\d{1,2}):(\d{2})$/.exec(String(value || ''));
  if (!match) return fallback;
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  if (!Number.isInteger(hours) || !Number.isInteger(minutes) || hours > 23 || minutes > 59) return fallback;
  return hours * 60 + minutes;
}

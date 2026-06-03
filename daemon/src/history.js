import { DatabaseSync } from 'node:sqlite';
import path from 'path';

const DEFAULT_SAMPLE_LIMIT = 120;
const DEFAULT_EVENT_LIMIT = 80;
const MAX_SAMPLES = 2000;
const MAX_EVENTS = 500;
const DB_FILE = process.env.AM_HISTORY_DB || path.join(process.cwd(), '.agent-monitor-history.sqlite');

let db = null;

export function recordSnapshotHistory(prev, next, alerts = []) {
  if (!next) return;
  const database = historyDb();
  const sample = summarizeSnapshot(next, alerts);
  database.prepare('INSERT INTO samples (ts, host, payload) VALUES (?, ?, ?)').run(
    sample.ts,
    sample.host,
    JSON.stringify(sample)
  );

  const insertEvent = database.prepare('INSERT INTO events (event_id, ts, kind, source_id, level, title, body, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?)');
  for (const alert of alerts) {
    const event = {
      id: `${alert.ts || next.ts || Date.now()}:${alert.agent || 'system'}:${Math.random().toString(36).slice(2, 8)}`,
      kind: 'alert',
      sourceId: alert.agent || '',
      level: alert.level || 'info',
      title: alert.title || '',
      body: alert.body || '',
      ts: alert.ts || next.ts || Date.now(),
    };
    insertEvent.run(event.id, event.ts, event.kind, event.sourceId, event.level, event.title, event.body, JSON.stringify(event));
  }

  trimTable(database, 'samples', MAX_SAMPLES);
  trimTable(database, 'events', MAX_EVENTS);
}

export function getHistory(options = {}) {
  const database = historyDb();
  const sampleLimit = boundedLimit(options.samples ?? options.limitSamples, DEFAULT_SAMPLE_LIMIT, MAX_SAMPLES);
  const eventLimit = boundedLimit(options.events ?? options.limitEvents, DEFAULT_EVENT_LIMIT, MAX_EVENTS);
  const samples = database
    .prepare('SELECT payload FROM samples ORDER BY id DESC LIMIT ?')
    .all(sampleLimit)
    .reverse()
    .map((row) => parsePayload(row.payload))
    .filter(Boolean);
  const events = database
    .prepare('SELECT payload FROM events ORDER BY id DESC LIMIT ?')
    .all(eventLimit)
    .reverse()
    .map((row) => parsePayload(row.payload))
    .filter(Boolean);

  return {
    storage: {
      kind: 'sqlite',
      path: DB_FILE,
      samples: countRows(database, 'samples'),
      events: countRows(database, 'events'),
    },
    samples,
    events,
    trend: buildTrend(samples),
  };
}

export function clearHistory() {
  const database = historyDb();
  database.exec('DELETE FROM samples; DELETE FROM events;');
}

export function historyStorageInfo() {
  const database = historyDb();
  return {
    kind: 'sqlite',
    path: DB_FILE,
    samples: countRows(database, 'samples'),
    events: countRows(database, 'events'),
  };
}

function historyDb() {
  if (db) return db;
  db = new DatabaseSync(DB_FILE);
  db.exec(`
    CREATE TABLE IF NOT EXISTS samples (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      ts INTEGER NOT NULL,
      host TEXT NOT NULL,
      payload TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_samples_ts ON samples(ts);
    CREATE TABLE IF NOT EXISTS events (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      event_id TEXT NOT NULL,
      ts INTEGER NOT NULL,
      kind TEXT NOT NULL,
      source_id TEXT NOT NULL,
      level TEXT NOT NULL,
      title TEXT NOT NULL,
      body TEXT NOT NULL,
      payload TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts);
  `);
  return db;
}

function summarizeSnapshot(snap, alerts) {
  const agents = (snap.agents || []).map((agent) => ({
    id: agent.id || '',
    name: agent.name || agent.id || '',
    state: agent.state || 'unknown',
    lastActivity: Number(agent.lastActivity || 0),
    summary: agent.summary || '',
  }));

  const services = (snap.services || []).map((service) => ({
    id: service.id || '',
    name: service.name || service.id || '',
    state: service.state || 'unknown',
    httpCode: Number(service.httpCode || 0),
    latencyMs: Number(service.latencyMs || 0),
    error: service.error || '',
  }));

  return {
    ts: snap.ts || Date.now(),
    host: snap.host || '',
    agentCounts: countByState(agents),
    serviceCounts: countByState(services),
    totals: summarizeTotals(snap.agents || []),
    agents,
    services,
    alertCount: alerts.length,
  };
}

function summarizeTotals(agents) {
  return agents.reduce(
    (acc, agent) => {
      const metrics = agent.metrics || {};
      const tokens = metrics.tokensToday || {};
      acc.sessionsToday += Number(metrics.sessionsToday || 0);
      acc.inputTokens += Number(tokens.input || 0);
      acc.outputTokens += Number(tokens.output || 0);
      acc.cacheReadTokens += Number(tokens.cacheRead || 0);
      acc.cacheCreateTokens += Number(tokens.cacheCreate || 0);
      return acc;
    },
    {
      sessionsToday: 0,
      inputTokens: 0,
      outputTokens: 0,
      cacheReadTokens: 0,
      cacheCreateTokens: 0,
    }
  );
}

function buildTrend(items) {
  return items.map((sample) => ({
    ts: sample.ts,
    onlineAgents: Number(sample.agentCounts.active || 0) + Number(sample.agentCounts.idle || 0),
    offlineAgents: Number(sample.agentCounts.offline || 0),
    downServices: Number(sample.serviceCounts.down || 0),
    sessionsToday: Number(sample.totals?.sessionsToday || 0),
    inputTokens: Number(sample.totals?.inputTokens || 0),
    outputTokens: Number(sample.totals?.outputTokens || 0),
    cacheTokens: Number(sample.totals?.cacheReadTokens || 0) + Number(sample.totals?.cacheCreateTokens || 0),
    alertCount: Number(sample.alertCount || 0),
  }));
}

function countByState(items) {
  return items.reduce((acc, item) => {
    const key = item.state || 'unknown';
    acc[key] = (acc[key] || 0) + 1;
    return acc;
  }, {});
}

function boundedLimit(raw, fallback, max) {
  const value = Number.parseInt(raw, 10);
  if (!Number.isFinite(value)) return fallback;
  return Math.min(Math.max(value, 1), max);
}

function trimTable(database, table, max) {
  database.prepare(`DELETE FROM ${table} WHERE id NOT IN (SELECT id FROM ${table} ORDER BY id DESC LIMIT ?)`).run(max);
}

function countRows(database, table) {
  return Number(database.prepare(`SELECT COUNT(*) AS count FROM ${table}`).get().count || 0);
}

function parsePayload(raw) {
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

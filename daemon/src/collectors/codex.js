import fs from 'fs';
import path from 'path';
import { config } from '../config.js';
import { listFiles, readJsonlCached, mtime, isToday, deriveState } from '../lib/jsonl.js';
import { redactText } from '../lib/redact.js';

// codex:读 ~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl
// 行结构 { timestamp, type, payload }。token 用量出现在含 total_token_usage 的 payload 中。
export function collectCodex() {
  const roots = Array.isArray(config.paths.codexSessions)
    ? config.paths.codexSessions
    : [config.paths.codexSessions];
  const now = Date.now();
  const result = {
    id: 'codex',
    name: 'Codex',
    kind: 'cli-agent',
    state: 'offline',
    lastActivity: 0,
    summary: '',
    metrics: {
      tokensToday: { input: 0, output: 0, cacheRead: 0, cacheCreate: 0 },
      sessionsToday: 0,
      model: '',
    },
    sessions: [],
  };

  const existingRoots = roots.filter((root) => root && fs.existsSync(root));
  if (!existingRoots.length) {
    result.summary = '未找到 ~/.codex/sessions';
    return result;
  }

  const cutoff = now - 24 * 60 * 60 * 1000;
  const seenFiles = new Set();
  const files = [];
  for (const root of existingRoots) {
    for (const file of listFiles(
      root,
      (f) => /rollout-.*\.jsonl$/.test(path.basename(f)) && mtime(f) >= cutoff
    )) {
      if (seenFiles.has(file)) continue;
      seenFiles.add(file);
      files.push(file);
    }
  }

  let latestTs = 0;
  let latestModel = '';

  for (const file of files) {
    const rows = readJsonlCached(file);
    if (!rows.length) continue;

    const fileMtime = mtime(file);
    let sLastTs = 0;
    let sModel = '';
    let sCwd = '';
    let sTitle = '';
    let messageCount = 0;
    let lastUsage = null; // codex 的 total_token_usage 是累计值,取该会话最后一次
    let sid = path.basename(file, '.jsonl').replace(/^rollout-/, '');

    for (const r of rows) {
      const tsMs = r.timestamp ? new Date(r.timestamp).getTime() : 0;
      if (tsMs > sLastTs) sLastTs = tsMs;

      const p = r.payload || {};
      if (r.type === 'session_meta') {
        if (p.id) sid = String(p.id);
        if (p.cwd) sCwd = p.cwd;
        if (p.model) sModel = p.model;
      }
      if (p.model) sModel = p.model;
      if (p.type === 'thread_name_updated' && p.thread_name) sTitle = String(p.thread_name);
      if (p.type === 'user_message') {
        messageCount += 1;
        if (!sTitle && p.message) sTitle = String(p.message).split(/\r?\n/)[0].slice(0, 80);
      }
      if (p.type === 'agent_message') messageCount += 1;

      const usage = findTokenUsage(r);
      if (usage) lastUsage = usage;
    }

    const lastTs = sLastTs || fileMtime;
    if (lastTs > latestTs) {
      latestTs = lastTs;
      latestModel = sModel;
    }

    // 仅把今日活跃会话的 token 计入今日合计
    if (isToday(lastTs) && lastUsage) {
      result.metrics.tokensToday.input += lastUsage.input_tokens || 0;
      result.metrics.tokensToday.output += lastUsage.output_tokens || 0;
      result.metrics.tokensToday.cacheRead += lastUsage.cached_input_tokens || 0;
    }
    if (isToday(lastTs)) result.metrics.sessionsToday += 1;

    result.sessions.push({
      id: sid,
      title: redactText(sTitle, { maskEmails: true }),
      cwd: sCwd,
      model: sModel,
      lastActivity: lastTs,
      messageCount,
      tokens: lastUsage
        ? {
            input: lastUsage.input_tokens || 0,
            output: lastUsage.output_tokens || 0,
            cacheRead: lastUsage.cached_input_tokens || 0,
            cacheCreate: 0,
          }
        : { input: 0, output: 0, cacheRead: 0, cacheCreate: 0 },
    });
  }

  result.sessions.sort((a, b) => b.lastActivity - a.lastActivity);
  result.sessions = result.sessions.slice(0, 20);

  result.lastActivity = latestTs;
  result.metrics.model = latestModel;
  result.state = deriveState(latestTs, now, config.activeWindowMs, config.idleWindowMs);

  const t = result.metrics.tokensToday;
  result.summary =
    result.state === 'offline'
      ? '今日无活动'
      : `今日 ${result.metrics.sessionsToday} 会话 · in ${fmt(t.input)} / out ${fmt(t.output)} tok`;

  return result;
}

// 在一行记录里深度查找 total_token_usage(结构可能嵌套在 payload.info 等位置)
function findTokenUsage(row) {
  const seen = new Set();
  const stack = [row];
  while (stack.length) {
    const cur = stack.pop();
    if (!cur || typeof cur !== 'object' || seen.has(cur)) continue;
    seen.add(cur);
    if (cur.total_token_usage && typeof cur.total_token_usage === 'object') {
      return cur.total_token_usage;
    }
    // 有些版本直接平铺 input_tokens/output_tokens
    if (
      typeof cur.input_tokens === 'number' &&
      typeof cur.output_tokens === 'number' &&
      cur.total_tokens !== undefined
    ) {
      return cur;
    }
    for (const k in cur) {
      const v = cur[k];
      if (v && typeof v === 'object') stack.push(v);
    }
  }
  return null;
}

function fmt(n) {
  if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
  if (n >= 1e3) return (n / 1e3).toFixed(1) + 'k';
  return String(n);
}

import fs from 'fs';
import path from 'path';
import { config } from '../config.js';
import { listFiles, readJsonlCached, mtime, isToday, deriveState } from '../lib/jsonl.js';
import { redactText } from '../lib/redact.js';

// claude code:读 ~/.claude/projects/*/*.jsonl
// 每个 jsonl 文件 ≈ 一个会话。assistant 消息里带 message.usage(input/output/cache token)。
export function collectClaude() {
  const root = config.paths.claudeProjects;
  const now = Date.now();
  const result = {
    id: 'claude-code',
    name: 'Claude Code',
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

  if (!fs.existsSync(root)) {
    result.summary = '未找到 ~/.claude/projects';
    return result;
  }

  // 只看 24h 内改动过的会话文件,避免重读历史大文件
  const cutoff = now - 24 * 60 * 60 * 1000;
  const files = listFiles(root, (f) => f.endsWith('.jsonl') && mtime(f) >= cutoff);

  let latestModel = '';
  let latestTs = 0;

  for (const file of files) {
    const rows = readJsonlCached(file);
    if (!rows.length) continue;

    const fileMtime = mtime(file);
    let sInput = 0,
      sOutput = 0,
      sCacheRead = 0,
      sCacheCreate = 0;
    let sModel = '';
    let sCwd = '';
    let sLastTs = 0;
    let sTitle = '';
    let messageCount = 0;
    let sid = path.basename(file, '.jsonl');

    for (const r of rows) {
      if (r.cwd && !sCwd) sCwd = r.cwd;
      if (r.sessionId) sid = r.sessionId;
      const tsMs = r.timestamp ? new Date(r.timestamp).getTime() : 0;
      if (tsMs > sLastTs) sLastTs = tsMs;

      const usage = r.message && r.message.usage;
      if (usage && isToday(r.timestamp)) {
        sInput += usage.input_tokens || 0;
        sOutput += usage.output_tokens || 0;
        sCacheRead += usage.cache_read_input_tokens || 0;
        sCacheCreate += usage.cache_creation_input_tokens || 0;
      }
      if (r.message && r.message.model) sModel = r.message.model;
      if (r.type === 'ai-title' && r.aiTitle) sTitle = String(r.aiTitle);
      if (r.type === 'last-prompt' && !sTitle) sTitle = String(r.lastPrompt || '').split(/\r?\n/)[0].slice(0, 80);
      if ((r.type === 'user' || r.type === 'assistant') && r.message) {
        messageCount += 1;
        if (!sTitle && r.type === 'user') {
          sTitle = String(textFromContent(r.message.content)).split(/\r?\n/)[0].slice(0, 80);
        }
      }
    }

    const lastTs = sLastTs || fileMtime;
    if (lastTs > latestTs) {
      latestTs = lastTs;
      latestModel = sModel;
    }

    result.metrics.tokensToday.input += sInput;
    result.metrics.tokensToday.output += sOutput;
    result.metrics.tokensToday.cacheRead += sCacheRead;
    result.metrics.tokensToday.cacheCreate += sCacheCreate;

    if (isToday(lastTs)) result.metrics.sessionsToday += 1;

    result.sessions.push({
      id: sid,
      title: redactText(sTitle, { maskEmails: true }),
      cwd: sCwd,
      model: sModel,
      lastActivity: lastTs,
      messageCount,
      tokens: { input: sInput, output: sOutput, cacheRead: sCacheRead, cacheCreate: sCacheCreate },
    });
  }

  // 会话按最近活动排序,最多回传 20 条
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

function fmt(n) {
  if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
  if (n >= 1e3) return (n / 1e3).toFixed(1) + 'k';
  return String(n);
}

function textFromContent(content) {
  if (content == null) return '';
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.map(textFromContent).join('\n');
  if (typeof content === 'object' && typeof content.text === 'string') return content.text;
  return '';
}

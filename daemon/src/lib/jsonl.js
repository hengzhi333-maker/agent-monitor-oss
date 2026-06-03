import fs from 'fs';
import path from 'path';

// 递归列出目录下满足条件的文件
export function listFiles(dir, filterFn) {
  let out = [];
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of entries) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) out = out.concat(listFiles(full, filterFn));
    else if (!filterFn || filterFn(full)) out.push(full);
  }
  return out;
}

export function mtime(file) {
  try {
    return Math.floor(fs.statSync(file).mtimeMs);
  } catch {
    return 0;
  }
}

// 按 mtime 缓存解析结果,文件没变就不重复读取/解析(避免每次轮询重读大文件)。
// 同时用 LRU 上限约束长期运行内存占用。
function cacheMaxEntries() {
  const value = Number(process.env.AM_CACHE_MAX || 500);
  if (!Number.isFinite(value) || value < 1) return 500;
  return Math.floor(value);
}

const CACHE_MAX_ENTRIES = cacheMaxEntries();
const cache = new Map();

export function readJsonlCached(file) {
  const m = mtime(file);
  const c = cache.get(file);
  if (c && c.mtime === m) {
    cache.delete(file);
    cache.set(file, c);
    return c.data;
  }
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch {
    return [];
  }
  const data = [];
  for (const ln of raw.split(/\r?\n/)) {
    if (!ln.trim()) continue;
    try {
      data.push(JSON.parse(ln));
    } catch {
      /* 跳过坏行 */
    }
  }
  cache.delete(file);
  cache.set(file, { mtime: m, data });
  while (cache.size > CACHE_MAX_ENTRIES) {
    const oldest = cache.keys().next().value;
    cache.delete(oldest);
  }
  return data;
}

export function cacheSize() {
  return cache.size;
}

export function clearCache() {
  cache.clear();
}

export function isToday(ts) {
  if (!ts) return false;
  const d = new Date(ts);
  if (isNaN(d.getTime())) return false;
  const now = new Date();
  return (
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate()
  );
}

// 根据最后活动时间推断状态
export function deriveState(lastActivityMs, now, activeWindowMs, idleWindowMs) {
  if (!lastActivityMs) return 'offline';
  const idle = now - lastActivityMs;
  if (idle < activeWindowMs) return 'active';
  if (idle < idleWindowMs) return 'idle';
  return 'offline';
}

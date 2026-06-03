import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

test('readJsonlCached skips malformed lines and caps cache size', async () => {
  const oldCacheMax = process.env.AM_CACHE_MAX;
  process.env.AM_CACHE_MAX = '3';
  const jsonl = await import(`../src/lib/jsonl.js?cache-max=${Date.now()}`);
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'agent-monitor-jsonl-'));

  try {
    const files = [];
    for (let i = 0; i < 4; i += 1) {
      const file = path.join(dir, `${i}.jsonl`);
      await fs.writeFile(file, `{"index":${i}}\nnot-json\n{"ok":true}\n`, 'utf8');
      files.push(file);
    }

    assert.deepEqual(jsonl.readJsonlCached(files[0]), [{ index: 0 }, { ok: true }]);
    jsonl.readJsonlCached(files[1]);
    jsonl.readJsonlCached(files[2]);
    assert.equal(jsonl.cacheSize(), 3);

    jsonl.readJsonlCached(files[0]);
    jsonl.readJsonlCached(files[3]);
    assert.equal(jsonl.cacheSize(), 3);

    jsonl.clearCache();
    assert.equal(jsonl.cacheSize(), 0);
  } finally {
    if (oldCacheMax === undefined) delete process.env.AM_CACHE_MAX;
    else process.env.AM_CACHE_MAX = oldCacheMax;
    await fs.rm(dir, { recursive: true, force: true });
  }
});

test('readJsonlCached falls back to a safe cache cap when AM_CACHE_MAX is invalid', async () => {
  const oldCacheMax = process.env.AM_CACHE_MAX;
  process.env.AM_CACHE_MAX = 'not-a-number';
  const jsonl = await import(`../src/lib/jsonl.js?invalid-cache-max=${Date.now()}`);
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'agent-monitor-jsonl-'));

  try {
    const file = path.join(dir, 'one.jsonl');
    await fs.writeFile(file, '{"ok":true}\n', 'utf8');

    assert.deepEqual(jsonl.readJsonlCached(file), [{ ok: true }]);
    assert.equal(jsonl.cacheSize(), 1);
  } finally {
    if (oldCacheMax === undefined) delete process.env.AM_CACHE_MAX;
    else process.env.AM_CACHE_MAX = oldCacheMax;
    await fs.rm(dir, { recursive: true, force: true });
  }
});

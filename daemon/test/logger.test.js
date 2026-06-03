import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

test('structured logger writes and reads recent jsonl entries', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-monitor-logs-'));
  const logFile = path.join(dir, 'daemon.jsonl');
  process.env.AM_LOG_FILE = logFile;
  process.env.AM_LOG_ROTATE_BYTES = '100000';
  const logger = await import(`../src/lib/logger.js?case=${Date.now()}`);

  logger.logInfo('test entry', { feature: 'observability' });
  const entries = logger.readRecentLogs(10);

  assert.equal(entries.length, 1);
  assert.equal(entries[0].level, 'info');
  assert.equal(entries[0].message, 'test entry');
  assert.equal(entries[0].feature, 'observability');
  assert.equal(logger.logStatus().path, logFile);

  fs.rmSync(dir, { recursive: true, force: true });
});

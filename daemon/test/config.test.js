import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

test('config fails closed when TLS files are configured but unreadable', async () => {
  const oldCert = process.env.AM_TLS_CERT;
  const oldKey = process.env.AM_TLS_KEY;
  process.env.AM_TLS_CERT = path.join(os.tmpdir(), 'agent-monitor-missing-cert.pem');
  process.env.AM_TLS_KEY = path.join(os.tmpdir(), 'agent-monitor-missing-key.pem');

  try {
    await assert.rejects(
      () => import(`../src/config.js?missing-tls=${Date.now()}`),
      /TLS certificate read failed/
    );
  } finally {
    restoreEnv('AM_TLS_CERT', oldCert);
    restoreEnv('AM_TLS_KEY', oldKey);
  }
});

function restoreEnv(name, value) {
  if (value === undefined) delete process.env[name];
  else process.env[name] = value;
}

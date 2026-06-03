import assert from 'node:assert/strict';
import os from 'node:os';
import test from 'node:test';
import { isAllowedRemoteAddress } from '../src/accessControl.js';

test('remote allowlist supports exact IPv4 and Tailscale CIDR rules', () => {
  assert.equal(isAllowedRemoteAddress('100.64.0.10', ['100.64.0.10']), true);
  assert.equal(isAllowedRemoteAddress('100.64.0.10', ['100.64.0.0/10']), true);
  assert.equal(isAllowedRemoteAddress('192.168.1.20', ['100.64.0.0/10']), false);
});

test('local machine interface addresses are always allowed for self-management', (t) => {
  const address = Object.values(os.networkInterfaces())
    .flatMap((items) => items || [])
    .find((item) => item.family === 'IPv4' && !item.internal)?.address;

  if (!address) {
    t.skip('no non-loopback IPv4 interface found');
    return;
  }

  assert.equal(isAllowedRemoteAddress(address, ['203.0.113.10']), true);
});

import assert from 'node:assert/strict';
import test from 'node:test';
import { config } from '../src/config.js';
import {
  clearAuthFailures,
  extractToken,
  hasRole,
  isRateLimited,
  noteAuthFailure,
  noteAuthSuccess,
  tokenAccess,
  tokenOk,
} from '../src/lib/auth.js';

test('tokenOk accepts any configured token and rejects unknown tokens', () => {
  withAuthConfig({ tokens: ['a'.repeat(32), 'b'.repeat(48)] }, () => {
    assert.equal(tokenOk('a'.repeat(32)), true);
    assert.equal(tokenOk('b'.repeat(48)), true);
    assert.equal(tokenOk('c'.repeat(32)), false);
    assert.equal(tokenOk(''), false);
  });
});

test('tokenAccess exposes token roles without exposing token material', () => {
  withAuthConfig(
    {
      tokens: ['viewer-token'.repeat(4), 'operator-token'.repeat(3)],
      tokenRecords: [
        { token: 'viewer-token'.repeat(4), role: 'read-only', name: 'viewer' },
        { token: 'operator-token'.repeat(3), role: 'operator', name: 'ops' },
      ],
    },
    () => {
      const viewer = tokenAccess('viewer-token'.repeat(4));
      const operator = tokenAccess('operator-token'.repeat(3));

      assert.equal(viewer.role, 'read-only');
      assert.equal(viewer.name, 'viewer');
      assert.equal(hasRole(viewer, 'read-only'), true);
      assert.equal(hasRole(viewer, 'operator'), false);
      assert.equal(hasRole(operator, 'operator'), true);
      assert.equal(hasRole(operator, 'admin'), false);
      assert.equal(tokenAccess('missing'), null);
    }
  );
});

test('extractToken only accepts bearer authorization headers', () => {
  assert.equal(extractToken({ headers: { authorization: 'Bearer secret-token' } }), 'secret-token');
  assert.equal(extractToken({ headers: { authorization: 'Basic secret-token' } }), '');
  assert.equal(extractToken({ headers: {} }), '');
});

test('failed auth attempts are rate limited and successful auth clears the source', () => {
  const ip = '203.0.113.10';
  clearAuthFailures();

  withAuthConfig({ authFailMax: 2, authFailWindowMs: 60_000 }, () => {
    assert.equal(isRateLimited(ip), false);

    noteAuthFailure(ip);
    assert.equal(isRateLimited(ip), false);

    noteAuthFailure(ip);
    assert.equal(isRateLimited(ip), true);

    noteAuthSuccess(ip);
    assert.equal(isRateLimited(ip), false);
  });
});

function withAuthConfig(patch, fn) {
  const original = {
    tokens: config.tokens,
    tokenRecords: config.tokenRecords,
    token: config.token,
    authFailMax: config.authFailMax,
    authFailWindowMs: config.authFailWindowMs,
  };
  Object.assign(config, patch);
  if (patch.tokens) config.token = patch.tokens[0];
  try {
    fn();
  } finally {
    Object.assign(config, original);
    clearAuthFailures();
  }
}

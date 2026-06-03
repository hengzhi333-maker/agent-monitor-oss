import assert from 'node:assert/strict';
import test from 'node:test';
import { config } from '../src/config.js';
import { buildHostSetupProfile, profileToUri } from '../src/hostSetup.js';

test('host setup profile can include a deep link without exposing token by default', () => {
  const original = {
    host: config.host,
    port: config.port,
    token: config.token,
    tls: config.tls,
  };
  config.host = 'workstation';
  config.port = 8765;
  config.token = 'secret-token';
  config.tls = { enabled: false };

  try {
    const withoutToken = buildHostSetupProfile({ address: 'workstation' });
    const withToken = buildHostSetupProfile({ address: 'workstation', includeToken: true });

    assert.equal(withoutToken.profile.token, '');
    assert.equal(withToken.profile.token, 'secret-token');
    assert.match(withToken.profile.id, /^daemon_[a-f0-9]{12}$/);
    assert.equal(withToken.profile.identityKey, withToken.profile.id);
    assert.match(profileToUri(withToken.profile), /^agentmonitor:\/\/host\?/);
    assert.match(withToken.uri, /address=workstation/);
    assert.match(withToken.uri, /identityKey=daemon_/);
    assert.match(withToken.uri, /token=secret-token/);
  } finally {
    Object.assign(config, original);
  }
});

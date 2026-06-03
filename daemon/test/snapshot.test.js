import assert from 'node:assert/strict';
import path from 'node:path';
import test from 'node:test';
import { config } from '../src/config.js';
import { clearAlertState, diffAlerts, redactSnapshot } from '../src/snapshot.js';

test('redactSnapshot removes local working directory paths and trims Hermes status', () => {
  withPrivacy({ redactCwd: true, hermesStatusMaxLen: 5 }, () => {
    const cwd = path.join(process.cwd(), 'private-workspace');
    const snap = redactSnapshot({
      agents: [
        {
          id: 'codex',
          sessions: [{ id: 's1', cwd }],
          detail: { status: '1234567890' },
        },
      ],
    });

    assert.equal(snap.agents[0].sessions[0].cwd, 'private-workspace');
    assert.equal(snap.agents[0].detail.status, '12345');
  });
});

test('redactSnapshot can suppress Hermes status entirely', () => {
  withPrivacy({ redactCwd: false, hermesStatusMaxLen: 0 }, () => {
    const snap = redactSnapshot({
      agents: [{ id: 'hermes', detail: { status: 'sensitive local status' } }],
    });

    assert.equal(snap.agents[0].detail.status, '');
  });
});

test('diffAlerts emits recovery events for agents and services', () => {
  clearAlertState();
  const alerts = diffAlerts(
    {
      agents: [{ id: 'codex', name: 'Codex', state: 'offline' }],
      services: [{ id: 'api', name: 'API', state: 'down' }],
    },
    {
      ts: 123,
      agents: [{ id: 'codex', name: 'Codex', state: 'active' }],
      services: [{ id: 'api', name: 'API', state: 'up' }],
    }
  );

  assert.deepEqual(
    alerts.map((alert) => [alert.level, alert.agent]),
    [
      ['info', 'codex'],
      ['info', 'api'],
    ]
  );
});

test('diffAlerts can wait for repeated service failures', () => {
  withAlertRules({ serviceFailureCount: 2 }, () => {
    clearAlertState();
    const first = diffAlerts(
      { services: [{ id: 'api', name: 'API', state: 'up' }] },
      { ts: 100, services: [{ id: 'api', name: 'API', state: 'down', error: 'timeout' }] }
    );
    const second = diffAlerts(
      { services: [{ id: 'api', name: 'API', state: 'down' }] },
      { ts: 200, services: [{ id: 'api', name: 'API', state: 'down', error: 'timeout' }] }
    );

    assert.equal(first.length, 0);
    assert.equal(second.length, 1);
    assert.equal(second[0].level, 'error');
  });
});

test('diffAlerts can delay agent offline notifications', () => {
  withAlertRules({ agentOfflineGraceMs: 1000 }, () => {
    clearAlertState();
    const first = diffAlerts(
      { agents: [{ id: 'codex', name: 'Codex', state: 'active' }] },
      { ts: 1000, agents: [{ id: 'codex', name: 'Codex', state: 'offline' }] }
    );
    const second = diffAlerts(
      { agents: [{ id: 'codex', name: 'Codex', state: 'offline' }] },
      { ts: 2000, agents: [{ id: 'codex', name: 'Codex', state: 'offline' }] }
    );

    assert.equal(first.length, 0);
    assert.equal(second.length, 1);
    assert.equal(second[0].agent, 'codex');
  });
});

test('diffAlerts applies alert cooldown per source and transition', () => {
  withAlertRules({ serviceFailureCount: 1, cooldownMs: 1000 }, () => {
    clearAlertState();
    const first = diffAlerts(
      { services: [{ id: 'api', name: 'API', state: 'up' }] },
      { ts: 1000, services: [{ id: 'api', name: 'API', state: 'down', error: 'timeout' }] }
    );
    const second = diffAlerts(
      { services: [{ id: 'api', name: 'API', state: 'up' }] },
      { ts: 1500, services: [{ id: 'api', name: 'API', state: 'down', error: 'timeout' }] }
    );

    assert.equal(first.length, 1);
    assert.equal(second.length, 0);
  });
});

test('diffAlerts suppresses low severity alerts during quiet hours', () => {
  withAlertRules({
    cooldownMs: 0,
    quietHours: {
      enabled: true,
      start: '22:00',
      end: '08:00',
      timezoneOffsetMinutes: 0,
      suppressBelow: 'error',
    },
  }, () => {
    clearAlertState();
    const alerts = diffAlerts(
      { agents: [{ id: 'codex', name: 'Codex', state: 'active' }] },
      { ts: Date.UTC(2026, 0, 1, 23, 0), agents: [{ id: 'codex', name: 'Codex', state: 'offline' }] }
    );

    assert.equal(alerts.length, 0);
  });
});

function withPrivacy(patch, fn) {
  const original = { ...config.privacy };
  Object.assign(config.privacy, patch);
  try {
    fn();
  } finally {
    Object.assign(config.privacy, original);
  }
}

function withAlertRules(patch, fn) {
  const original = { ...config.alertRules };
  Object.assign(config.alertRules, patch);
  try {
    fn();
  } finally {
    Object.assign(config.alertRules, original);
    clearAlertState();
  }
}

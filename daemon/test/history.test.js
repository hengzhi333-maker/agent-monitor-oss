import assert from 'node:assert/strict';
import test from 'node:test';
import { clearHistory, getHistory, recordSnapshotHistory } from '../src/history.js';

test('history records bounded snapshot samples and alert events', () => {
  clearHistory();

  recordSnapshotHistory(null, snapshot(1, 'active', 'up'), []);
  recordSnapshotHistory(null, snapshot(2, 'idle', 'up'), []);
  recordSnapshotHistory(null, snapshot(3, 'offline', 'down'), [
    {
      level: 'warn',
      agent: 'codex',
      title: 'Codex offline',
      body: 'Codex moved to offline',
      ts: 3,
    },
  ]);

  const history = getHistory({ samples: '2', events: '10' });

  assert.deepEqual(history.samples.map((sample) => sample.ts), [2, 3]);
  assert.equal(history.samples[1].agentCounts.offline, 1);
  assert.equal(history.samples[1].serviceCounts.down, 1);
  assert.equal(history.trend.length, 2);
  assert.equal(history.trend[1].offlineAgents, 1);
  assert.equal(history.trend[1].downServices, 1);
  assert.equal(history.trend[1].inputTokens, 100);
  assert.equal(history.events.length, 1);
  assert.equal(history.events[0].title, 'Codex offline');
});

function snapshot(ts, agentState, serviceState) {
  return {
    host: 'dev',
    ts,
    agents: [
      {
        id: 'codex',
        name: 'Codex',
        state: agentState,
        lastActivity: ts,
        summary: '',
        metrics: {
          sessionsToday: ts,
          tokensToday: {
            input: 100,
            output: 20,
            cacheRead: 10,
            cacheCreate: 5,
          },
        },
      },
    ],
    services: [
      {
        id: 'api',
        name: 'API',
        state: serviceState,
        httpCode: serviceState === 'up' ? 200 : 500,
        latencyMs: 12,
      },
    ],
  };
}

import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import test from 'node:test';
import { readJsonBody } from '../src/httpBody.js';

function requestFrom(text, headers = {}) {
  const req = Readable.from([Buffer.from(text)]);
  req.headers = headers;
  return req;
}

test('readJsonBody rejects declared bodies over the configured limit', async () => {
  await assert.rejects(
    () => readJsonBody(requestFrom('{}', { 'content-length': '20' }), 4),
    (err) => err.code === 'REQUEST_TOO_LARGE' && err.status === 413
  );
});

test('readJsonBody rejects streamed bodies over the configured limit', async () => {
  await assert.rejects(
    () => readJsonBody(requestFrom('{"text":"too large"}'), 8),
    (err) => err.code === 'REQUEST_TOO_LARGE' && err.status === 413
  );
});

test('readJsonBody parses normal JSON payloads', async () => {
  assert.deepEqual(await readJsonBody(requestFrom('{"ok":true}'), 1024), { ok: true });
});

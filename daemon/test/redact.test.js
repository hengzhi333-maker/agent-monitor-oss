import assert from 'node:assert/strict';
import test from 'node:test';
import { redactText } from '../src/lib/redact.js';

test('redactText masks authorization headers and local user paths', () => {
  const input = [
    'Authorization: Bearer abcdefghijklmnopqrstuvwxyz',
    'Cookie: session=secret-value',
    'C:\\Users\\Administrator\\Documents\\Codex\\private.txt',
    '/home/alice/project/.env',
  ].join('\n');

  const redacted = redactText(input, { maskLocalPaths: true });

  assert.match(redacted, /Authorization: <redacted>/);
  assert.match(redacted, /Cookie: <redacted>/);
  assert.doesNotMatch(redacted, /Administrator|alice|private\.txt|project/);
});

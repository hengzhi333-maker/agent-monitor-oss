import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { promisify } from 'node:util';
import { config } from '../src/config.js';
import {
  buildAgentCommand,
  cleanupWorkbenchData,
  archiveWorkbenchSession,
  createWorkbenchSession,
  deleteWorkbenchAttachment,
  deleteWorkbenchSession,
  dedupeWorkbenchMessages,
  extractWorkbenchText,
  getWorkbenchGitDiff,
  getWorkbenchGitStatus,
  listWorkbenchAttachments,
  listWorkbenchSessionsWithOptions,
  messageFromEvent,
  messageFromOutputLine,
  sendWorkbenchMessage,
  uploadWorkbenchAttachment,
} from '../src/workbench.js';
import {
  classifyWorkbenchAttachment,
  composePromptWithAttachments,
  sanitizeAttachmentName,
} from '../src/workbenchAttachments.js';

const execFileP = promisify(execFile);

test('codex command reads prompt from stdin without directly spawning cmd shims', () => {
  const prompt = 'Reply with exactly: PHONE_WORKBENCH_OK & echo injected';
  const command = buildAgentCommand(
    { agentId: 'codex', cwd: process.cwd(), agentSessionId: '' },
    prompt
  );

  assert.equal(command.stdin, prompt);
  assert.equal(command.args.at(-1), '-');
  assert.equal(command.args.includes(prompt), false);
  if (process.platform === 'win32') {
    assert.notEqual(path.extname(command.cmd).toLowerCase(), '.cmd');
  }
});

test('claude command reads prompt from stdin without directly spawning cmd shims', () => {
  const prompt = 'Reply with exactly: PHONE_WORKBENCH_OK & echo injected';
  const command = buildAgentCommand(
    { agentId: 'claude-code', cwd: process.cwd(), agentSessionId: '' },
    prompt
  );

  assert.equal(command.stdin, prompt);
  assert.equal(command.args.includes('--verbose'), true);
  assert.equal(command.args.includes(prompt), false);
  if (process.platform === 'win32') {
    assert.notEqual(path.extname(command.cmd).toLowerCase(), '.cmd');
  }
});

test('codex standard mode uses workspace sandbox without dangerous bypass', () => {
  const command = buildAgentCommand(
    { agentId: 'codex', cwd: process.cwd(), agentSessionId: '', permissionMode: 'standard' },
    'Run safely'
  );

  assert.equal(command.args.includes('--dangerously-bypass-approvals-and-sandbox'), false);
  const sandboxFlag = command.args.indexOf('--sandbox');
  assert.notEqual(sandboxFlag, -1);
  assert.equal(command.args[sandboxFlag + 1], 'workspace-write');
});

test('codex read-only mode uses read-only sandbox for new sessions', () => {
  const command = buildAgentCommand(
    { agentId: 'codex', cwd: process.cwd(), agentSessionId: '', permissionMode: 'read-only' },
    'Inspect only'
  );

  const sandboxFlag = command.args.indexOf('--sandbox');
  assert.notEqual(sandboxFlag, -1);
  assert.equal(command.args[sandboxFlag + 1], 'read-only');
  assert.equal(command.args.includes('--dangerously-bypass-approvals-and-sandbox'), false);
});

test('dangerous bypass is session scoped and requires dangerous mode', () => {
  withDangerousAllowed(true, () => {
    const standard = buildAgentCommand(
      { agentId: 'codex', cwd: process.cwd(), agentSessionId: '', permissionMode: 'standard' },
      'Standard turn'
    );
    const dangerous = buildAgentCommand(
      { agentId: 'codex', cwd: process.cwd(), agentSessionId: '', permissionMode: 'dangerous' },
      'Dangerous turn'
    );

    assert.equal(standard.args.includes('--dangerously-bypass-approvals-and-sandbox'), false);
    assert.equal(dangerous.args.includes('--dangerously-bypass-approvals-and-sandbox'), true);
    assert.equal(dangerous.args.includes('--sandbox'), false);
  });
});

test('dangerous mode is rejected when daemon disables it', () => {
  withDangerousAllowed(false, () => {
    assert.throws(
      () =>
        buildAgentCommand(
          { agentId: 'codex', cwd: process.cwd(), agentSessionId: '', permissionMode: 'dangerous' },
          'Dangerous turn'
        ),
      /Dangerous workbench permission mode is disabled/
    );
  });
});

test('workbench sessions can be archived, hidden by default, and deleted', async () => {
  await withRemoteControl(
    {
      enabled: true,
      defaultCwd: process.cwd(),
      allowedCwds: [process.cwd()],
    },
    async () => {
      const { session } = createWorkbenchSession(
        { agentId: 'codex', cwd: process.cwd(), title: 'Archive me' },
        { auth: { role: 'operator' } }
      );

      assert.equal(
        listWorkbenchSessionsWithOptions().sessions.some((item) => item.id === session.id),
        true
      );

      const archived = await archiveWorkbenchSession(session.id, true);
      assert.equal(archived.session.archived, true);
      assert.equal(
        listWorkbenchSessionsWithOptions().sessions.some((item) => item.id === session.id),
        false
      );
      assert.equal(
        listWorkbenchSessionsWithOptions({ includeArchived: true }).sessions.some((item) => item.id === session.id),
        true
      );

      const deleted = await deleteWorkbenchSession(session.id);
      assert.equal(deleted.deleted, true);
      assert.equal(
        listWorkbenchSessionsWithOptions({ includeArchived: true }).sessions.some((item) => item.id === session.id),
        false
      );
    }
  );
});

test('dangerous workbench sessions require admin and expire before accepting new turns', async () => {
  await withRemoteControl(
    {
      enabled: true,
      allowDangerousPermissions: true,
      dangerousSessionTtlMs: 1,
      defaultCwd: process.cwd(),
      allowedCwds: [process.cwd()],
    },
    async () => {
      assert.throws(
        () =>
          createWorkbenchSession(
            { agentId: 'codex', cwd: process.cwd(), permissionMode: 'dangerous' },
            { auth: { role: 'operator' } }
          ),
        /requires an admin token/
      );

      const { session } = createWorkbenchSession(
        { agentId: 'codex', cwd: process.cwd(), permissionMode: 'dangerous' },
        { auth: { role: 'admin' } }
      );
      assert.equal(session.permissionMode, 'dangerous');
      assert.ok(session.dangerousExpiresAt >= Date.now());
      await new Promise((resolve) => setTimeout(resolve, 20));
      assert.throws(
        () => sendWorkbenchMessage(session.id, { text: 'This should not start a process.' }),
        /Dangerous permission window expired/
      );

      const deleted = await deleteWorkbenchSession(session.id);
      assert.equal(deleted.deleted, true);
    }
  );
});

test('claude read-only mode uses plan permission mode', () => {
  const command = buildAgentCommand(
    { agentId: 'claude-code', cwd: process.cwd(), agentSessionId: '', permissionMode: 'read-only' },
    'Inspect only'
  );

  const permissionFlag = command.args.indexOf('--permission-mode');
  assert.notEqual(permissionFlag, -1);
  assert.equal(command.args[permissionFlag + 1], 'plan');
});

test('codex resume uses captured thread id and keeps prompt on stdin', () => {
  const prompt = 'Continue this task';
  const command = buildAgentCommand(
    { agentId: 'codex', cwd: process.cwd(), agentSessionId: '019e7772-3612-7310-a975-b7b45065e582' },
    prompt
  );

  assert.deepEqual(command.args.slice(0, 5), ['exec', 'resume', '--json', '--all', '019e7772-3612-7310-a975-b7b45065e582']);
  assert.equal(command.args.at(-1), '-');
  assert.equal(command.stdin, prompt);
});

test('codex command attaches uploaded images as vision inputs', () => {
  const imagePath = path.join(process.cwd(), '.workbench-uploads', 'wb_test', 'photo.png');
  const command = buildAgentCommand(
    { agentId: 'codex', cwd: process.cwd(), agentSessionId: '' },
    'Describe this image',
    [{ kind: 'image', filePath: imagePath }]
  );

  const imageFlag = command.args.indexOf('--image');
  assert.notEqual(imageFlag, -1);
  assert.equal(command.args[imageFlag + 1], imagePath);
  assert.equal(command.args.at(-1), '-');
  assert.equal(command.stdin, 'Describe this image');
});

test('attachment classifier accepts requested multimodal formats', () => {
  assert.equal(classifyWorkbenchAttachment({ name: 'photo.webp', mime: 'image/webp' }).kind, 'image');
  assert.equal(classifyWorkbenchAttachment({ name: 'notes.md', mime: 'text/markdown' }).kind, 'text');
  assert.equal(classifyWorkbenchAttachment({ name: 'app.ts', mime: 'text/plain' }).kind, 'code');
  assert.equal(classifyWorkbenchAttachment({ name: 'spec.pdf', mime: 'application/pdf' }).kind, 'pdf');
  assert.equal(
    classifyWorkbenchAttachment({
      name: 'brief.docx',
      mime: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    }).kind,
    'word'
  );
  assert.equal(
    classifyWorkbenchAttachment({
      name: 'sheet.xlsx',
      mime: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    }).kind,
    'spreadsheet'
  );
  assert.equal(classifyWorkbenchAttachment({ name: 'legacy.xls', mime: 'application/vnd.ms-excel' }).kind, '');
});

test('attachment prompt includes extracted document content and image metadata', () => {
  const prompt = composePromptWithAttachments('', [
    {
      name: 'notes.txt',
      kind: 'text',
      size: 11,
      extractedText: 'hello world',
    },
    {
      name: 'screen.png',
      kind: 'image',
      size: 2048,
      filePath: path.join(process.cwd(), 'screen.png'),
    },
  ]);

  assert.match(prompt, /请分析这些附件/);
  assert.match(prompt, /hello world/);
  assert.match(prompt, /图片已作为视觉输入附加/);
  assert.match(prompt, /screen\.png/);
  assert.equal(prompt.includes(path.join(process.cwd(), 'screen.png')), false);
});

test('attachment names are sanitized before storage', () => {
  assert.equal(sanitizeAttachmentName('..\\secret<bad>.txt'), 'secret_bad_.txt');
});

test('deleting a workbench attachment removes its index entry and stored file', async () => {
  const uploadDir = await fs.mkdtemp(path.join(os.tmpdir(), 'agent-monitor-uploads-'));
  const stateDir = await fs.mkdtemp(path.join(os.tmpdir(), 'agent-monitor-state-'));
  const stateFile = path.join(stateDir, 'workbench-state.json');
  const oldUploadDir = process.env.AM_WORKBENCH_UPLOAD_DIR;
  const oldStateFile = process.env.AM_WORKBENCH_STATE_FILE;
  process.env.AM_WORKBENCH_UPLOAD_DIR = uploadDir;
  process.env.AM_WORKBENCH_STATE_FILE = stateFile;

  try {
    await withRemoteControl(
      { enabled: true, defaultCwd: process.cwd(), allowedCwds: [process.cwd()] },
      async () => {
        const { session } = createWorkbenchSession({
          agentId: 'codex',
          cwd: process.cwd(),
          title: 'Attachment deletion test',
        });
        const { attachment } = await uploadWorkbenchAttachment(session.id, {
          name: 'notes.txt',
          mime: 'text/plain',
          buffer: Buffer.from('hello attachment'),
        });

        assert.equal((await listFiles(uploadDir)).length, 1);
        assert.equal(
          listWorkbenchAttachments().attachments.some((item) => item.id === attachment.id),
          true
        );

        const result = await deleteWorkbenchAttachment(session.id, attachment.id);

        assert.deepEqual(result, { deleted: true, sessionId: session.id, attachmentId: attachment.id });
        assert.equal(
          listWorkbenchAttachments().attachments.some((item) => item.id === attachment.id),
          false
        );
        assert.equal((await listFiles(uploadDir)).length, 0);
      }
    );
  } finally {
    if (oldUploadDir === undefined) delete process.env.AM_WORKBENCH_UPLOAD_DIR;
    else process.env.AM_WORKBENCH_UPLOAD_DIR = oldUploadDir;
    if (oldStateFile === undefined) delete process.env.AM_WORKBENCH_STATE_FILE;
    else process.env.AM_WORKBENCH_STATE_FILE = oldStateFile;
    await fs.rm(uploadDir, { recursive: true, force: true });
    await fs.rm(stateDir, { recursive: true, force: true });
  }
});

test('cleaning all workbench attachments removes index entries and stored files', async () => {
  const uploadDir = await fs.mkdtemp(path.join(os.tmpdir(), 'agent-monitor-uploads-'));
  const stateDir = await fs.mkdtemp(path.join(os.tmpdir(), 'agent-monitor-state-'));
  const stateFile = path.join(stateDir, 'workbench-state.json');
  const oldUploadDir = process.env.AM_WORKBENCH_UPLOAD_DIR;
  const oldStateFile = process.env.AM_WORKBENCH_STATE_FILE;
  process.env.AM_WORKBENCH_UPLOAD_DIR = uploadDir;
  process.env.AM_WORKBENCH_STATE_FILE = stateFile;

  try {
    await withRemoteControl(
      { enabled: true, defaultCwd: process.cwd(), allowedCwds: [process.cwd()] },
      async () => {
        const { session } = createWorkbenchSession({
          agentId: 'codex',
          cwd: process.cwd(),
          title: 'Attachment cleanup test',
        });
        await uploadWorkbenchAttachment(session.id, {
          name: 'cleanup.txt',
          mime: 'text/plain',
          buffer: Buffer.from('cleanup attachment'),
        });

        assert.equal((await listFiles(uploadDir)).length, 1);

        const result = await cleanupWorkbenchData({ all: true });

        assert.equal(result.removedAttachments, 1);
        assert.equal(listWorkbenchAttachments().attachments.some((item) => item.sessionId === session.id), false);
        assert.equal((await listFiles(uploadDir)).length, 0);
      }
    );
  } finally {
    if (oldUploadDir === undefined) delete process.env.AM_WORKBENCH_UPLOAD_DIR;
    else process.env.AM_WORKBENCH_UPLOAD_DIR = oldUploadDir;
    if (oldStateFile === undefined) delete process.env.AM_WORKBENCH_STATE_FILE;
    else process.env.AM_WORKBENCH_STATE_FILE = oldStateFile;
    await fs.rm(uploadDir, { recursive: true, force: true });
    await fs.rm(stateDir, { recursive: true, force: true });
  }
});

test('workbench exposes git status and diff for the session working directory', async (t) => {
  if (!(await commandAvailable('git'))) {
    t.skip('git is not available');
    return;
  }

  const repoDir = await fs.mkdtemp(path.join(os.tmpdir(), 'agent-monitor-git-'));
  const stateDir = await fs.mkdtemp(path.join(os.tmpdir(), 'agent-monitor-state-'));
  const stateFile = path.join(stateDir, 'workbench-state.json');
  const oldStateFile = process.env.AM_WORKBENCH_STATE_FILE;
  process.env.AM_WORKBENCH_STATE_FILE = stateFile;

  try {
    await execFileP('git', ['init'], { cwd: repoDir, windowsHide: true });
    await fs.writeFile(path.join(repoDir, 'notes.txt'), 'one\n', 'utf8');
    await execFileP('git', ['add', 'notes.txt'], { cwd: repoDir, windowsHide: true });
    await execFileP(
      'git',
      ['-c', 'user.email=test@example.invalid', '-c', 'user.name=Agent Monitor Test', 'commit', '-m', 'init'],
      { cwd: repoDir, windowsHide: true }
    );
    await fs.writeFile(path.join(repoDir, 'notes.txt'), 'one\ntwo\n', 'utf8');

    await withRemoteControl(
      { enabled: true, defaultCwd: repoDir, allowedCwds: [repoDir] },
      async () => {
        const { session } = createWorkbenchSession({
          agentId: 'codex',
          cwd: repoDir,
          title: 'Git status test',
        });
        const status = await getWorkbenchGitStatus(session.id);
        const diff = await getWorkbenchGitDiff(session.id);

        assert.equal(status.status.isRepo, true);
        assert.equal(status.status.statusLines.some((line) => line.includes('notes.txt')), true);
        assert.match(diff.diff, /\+two/);
      }
    );
  } finally {
    if (oldStateFile === undefined) delete process.env.AM_WORKBENCH_STATE_FILE;
    else process.env.AM_WORKBENCH_STATE_FILE = oldStateFile;
    await fs.rm(repoDir, { recursive: true, force: true });
    await fs.rm(stateDir, { recursive: true, force: true });
  }
});

test('extracts readable text from Codex JSONL item events', () => {
  assert.equal(
    extractWorkbenchText({
      type: 'item.completed',
      item: { id: 'item_0', type: 'agent_message', text: 'PHONE_WORKBENCH_OK' },
    }),
    'PHONE_WORKBENCH_OK'
  );
});

test('skips Claude protocol init events instead of showing raw JSON in workbench chat', () => {
  assert.equal(
    messageFromEvent(
      { id: 'wb_test', agentId: 'claude-code' },
      'turn_test',
      'stdout',
      { type: 'system', subtype: 'init', session_id: 'session-test', tools: ['Bash'] }
    ),
    null
  );
});

test('extracts Claude result text without exposing the full protocol payload', () => {
  assert.equal(
    extractWorkbenchText({
      type: 'result',
      subtype: 'success',
      result: 'PHONE_WORKBENCH_OK',
      usage: { input_tokens: 123 },
    }),
    'PHONE_WORKBENCH_OK'
  );
});

test('deduplicates Claude assistant stream and matching final result summary', () => {
  const session = { id: 'wb_test', agentId: 'claude-code' };
  const assistant = messageFromEvent(
    session,
    'turn_test',
    'stdout',
    {
      type: 'assistant',
      message: {
        content: [{ type: 'text', text: 'PHONE_WORKBENCH_OK' }],
      },
    }
  );
  const result = messageFromEvent(
    session,
    'turn_test',
    'stdout',
    { type: 'result', subtype: 'success', result: 'PHONE_WORKBENCH_OK' }
  );

  assert.equal(assistant.text, 'PHONE_WORKBENCH_OK');
  assert.equal(result.text, 'PHONE_WORKBENCH_OK');
  assert.deepEqual(dedupeWorkbenchMessages([assistant, result]).map((message) => message.kind), ['assistant']);
});

test('keeps Claude result summaries when they differ from the assistant message', () => {
  const messages = dedupeWorkbenchMessages([
    { id: 'msg_1', turnId: 'turn_test', role: 'assistant', kind: 'assistant', text: 'First answer', ts: 1 },
    { id: 'msg_2', turnId: 'turn_test', role: 'assistant', kind: 'result', text: 'Different final summary', ts: 2 },
  ]);

  assert.equal(messages.length, 2);
});

test('suppresses non-fatal Codex startup stderr from workbench chat', () => {
  const session = { id: 'wb_test', agentId: 'codex' };
  const line =
    '2026-05-30T13:30:53.263707Z ERROR codex_api::endpoint::responses_websocket: failed to connect to websocket: HTTP error: 426 Upgrade Required, url: ws://localhost:8330/responses';

  assert.equal(messageFromOutputLine(session, 'turn_test', 'stderr', line), null);
});

test('suppresses non-fatal Codex plugin cache refresh warnings from workbench chat', () => {
  const session = { id: 'wb_test', agentId: 'codex' };
  const line =
    '2026-05-30T14:46:49.628013Z  WARN codex_core_plugins::manager: failed to refresh curated plugin cache after sync: failed to refresh curated plugin cache for superpowers@openai-curated: failed to back up plugin cache entry: 拒绝访问。 (os error 5)';

  assert.equal(messageFromOutputLine(session, 'turn_test', 'stderr', line), null);
});

test('keeps unexpected stderr visible in workbench chat', () => {
  const session = { id: 'wb_test', agentId: 'codex' };
  const line = 'fatal: missing required credential';

  const message = messageFromOutputLine(session, 'turn_test', 'stderr', line);

  assert.equal(message.role, 'system');
  assert.equal(message.kind, 'stderr');
  assert.equal(message.text, line);
});

test('skips Codex lifecycle protocol events in workbench chat', () => {
  const session = { id: 'wb_test', agentId: 'codex' };

  assert.equal(messageFromEvent(session, 'turn_test', 'stdout', { type: 'thread.started', thread_id: 'thread_test' }), null);
  assert.equal(messageFromEvent(session, 'turn_test', 'stdout', { type: 'turn.started' }), null);
  assert.equal(messageFromEvent(session, 'turn_test', 'stdout', { type: 'turn.completed', usage: { input_tokens: 1 } }), null);
});

test('skips Codex command execution protocol payloads in workbench chat', () => {
  const session = { id: 'wb_test', agentId: 'codex' };

  assert.equal(
    messageFromEvent(
      session,
      'turn_test',
      'stdout',
      { type: 'item.started', item: { id: 'item_0', type: 'command_execution', command: 'Get-Content foo' } }
    ),
    null
  );
  assert.equal(
    messageFromEvent(
      session,
      'turn_test',
      'stdout',
      { type: 'item.completed', item: { id: 'item_0', type: 'command_execution', command: 'Get-Content foo' } }
    ),
    null
  );
});

test('suppresses Windows task cleanup noise from Codex stdout', () => {
  const session = { id: 'wb_test', agentId: 'codex' };
  const line = 'SUCCESS: The process with PID 33364 (child process of PID 14720) has been terminated.';

  assert.equal(messageFromOutputLine(session, 'turn_test', 'stdout', line), null);
});

function withDangerousAllowed(value, fn) {
  const original = config.remoteControl.allowDangerousPermissions;
  config.remoteControl.allowDangerousPermissions = value;
  try {
    fn();
  } finally {
    config.remoteControl.allowDangerousPermissions = original;
  }
}

async function withRemoteControl(patch, fn) {
  const original = {
    ...config.remoteControl,
    allowedRemoteAddresses: [...config.remoteControl.allowedRemoteAddresses],
    allowedCwds: [...config.remoteControl.allowedCwds],
  };
  Object.assign(config.remoteControl, patch);
  try {
    await fn();
  } finally {
    Object.assign(config.remoteControl, original);
  }
}

async function listFiles(dir) {
  const entries = await fs.readdir(dir, { recursive: true, withFileTypes: true }).catch(() => []);
  return entries.filter((entry) => entry.isFile());
}

async function commandAvailable(command) {
  try {
    await execFileP(command, ['--version'], { windowsHide: true, timeout: 3000 });
    return true;
  } catch {
    return false;
  }
}

test('suppresses localized Windows task cleanup noise from Codex stdout', () => {
  const session = { id: 'wb_test', agentId: 'codex' };
  const line = 'garbled cleanup output PID 30832 parent PID 23340 process terminated';

  assert.equal(messageFromOutputLine(session, 'turn_test', 'stdout', line), null);
});

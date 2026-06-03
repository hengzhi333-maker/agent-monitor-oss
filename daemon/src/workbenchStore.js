import fs from 'fs';
import path from 'path';

const fsp = fs.promises;
let writeQueue = Promise.resolve();

export function workbenchStatePath() {
  return path.resolve(process.env.AM_WORKBENCH_STATE_FILE || path.join(process.cwd(), '.workbench-sessions.json'));
}

export function loadWorkbenchState() {
  try {
    const raw = fs.readFileSync(workbenchStatePath(), 'utf8').replace(/^\uFEFF/, '');
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed;
    if (Array.isArray(parsed.sessions)) return parsed.sessions;
    return [];
  } catch {
    return [];
  }
}

export function persistWorkbenchState(sessions) {
  const file = workbenchStatePath();
  const body = JSON.stringify({ version: 1, savedAt: Date.now(), sessions }, null, 2);
  writeQueue = writeQueue.catch(() => {}).then(() => writeState(file, body));
  return writeQueue;
}

function writeState(file, body) {
  const dir = path.dirname(file);
  const tmp = `${file}.${process.pid}.${Date.now()}.${Math.random().toString(16).slice(2)}.tmp`;
  return fsp
    .mkdir(dir, { recursive: true })
    .then(() => fsp.writeFile(tmp, `${body}\n`, 'utf8'))
    .then(() => fsp.rename(tmp, file))
    .catch(async (err) => {
      await fsp.rm(tmp, { force: true }).catch(() => {});
      throw err;
    });
}

import { execFile } from 'child_process';
import { promisify } from 'util';

const execFileP = promisify(execFile);
const MAX_DIFF_CHARS = 120_000;

export async function inspectGitStatus(cwd) {
  const base = {
    isRepo: false,
    branch: '',
    root: '',
    statusLines: [],
    diffStat: '',
    stagedDiffStat: '',
    lastCommit: '',
    error: '',
  };

  try {
    await runGit(cwd, ['rev-parse', '--is-inside-work-tree']);
  } catch {
    return { ...base, error: 'Not a git work tree.' };
  }

  try {
    const [root, branch, status, diffStat, stagedDiffStat, lastCommit] = await Promise.all([
      runGit(cwd, ['rev-parse', '--show-toplevel']),
      runGit(cwd, ['rev-parse', '--abbrev-ref', 'HEAD']),
      runGit(cwd, ['status', '--short', '--branch']),
      runGit(cwd, ['diff', '--stat']),
      runGit(cwd, ['diff', '--cached', '--stat']),
      runGit(cwd, ['log', '-1', '--pretty=format:%h %s']),
    ]);
    return {
      ...base,
      isRepo: true,
      root: root.trim(),
      branch: branch.trim(),
      statusLines: status.split(/\r?\n/).filter(Boolean).slice(0, 200),
      diffStat: diffStat.trim(),
      stagedDiffStat: stagedDiffStat.trim(),
      lastCommit: lastCommit.trim(),
    };
  } catch (err) {
    return {
      ...base,
      isRepo: true,
      error: err?.message || String(err),
    };
  }
}

export async function readGitDiff(cwd, options = {}) {
  const cached = options.cached === true;
  try {
    await runGit(cwd, ['rev-parse', '--is-inside-work-tree']);
    const diff = await runGit(cwd, cached ? ['diff', '--cached'] : ['diff']);
    const truncated = diff.length > MAX_DIFF_CHARS;
    return {
      diff: truncated ? diff.slice(0, MAX_DIFF_CHARS) : diff,
      truncated,
      cached,
      error: '',
    };
  } catch (err) {
    return {
      diff: '',
      truncated: false,
      cached,
      error: err?.message || String(err),
    };
  }
}

function runGit(cwd, args) {
  return execFileP('git', args, {
    cwd,
    windowsHide: true,
    timeout: 8_000,
    maxBuffer: 256 * 1024,
  }).then(({ stdout }) => stdout);
}

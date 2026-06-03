import fs from 'fs';
import { execFile } from 'child_process';
import { promisify } from 'util';
import { config } from '../config.js';
import { mtime, deriveState } from '../lib/jsonl.js';

const execFileP = promisify(execFile);

// hermes:无监听端口,靠进程 + 状态文件判断。
// - hermes-agent.exe 进程数(Windows 用 tasklist)
// - ~/.hermes_tmp_status.txt 文本进度
export async function collectHermes() {
  const now = Date.now();
  const result = {
    id: 'hermes',
    name: 'Hermes Agent',
    kind: 'agent',
    state: 'offline',
    lastActivity: 0,
    summary: '',
    metrics: { processes: 0, model: '' },
    detail: { status: '', statusUpdated: 0 },
    sessions: [],
  };

  // 1) 进程数
  let procCount = 0;
  try {
    if (process.platform === 'win32') {
      const { stdout } = await execFileP(
        'tasklist',
        ['/FI', 'IMAGENAME eq hermes-agent.exe', '/NH', '/FO', 'CSV'],
        { windowsHide: true }
      );
      procCount = (stdout.match(/hermes-agent\.exe/gi) || []).length;
    } else {
      const { stdout } = await execFileP('pgrep', ['-fc', 'hermes-agent']).catch(() => ({
        stdout: '0',
      }));
      procCount = parseInt(String(stdout).trim(), 10) || 0;
    }
  } catch {
    procCount = 0;
  }
  result.metrics.processes = procCount;

  // 2) 状态文件
  const sf = config.paths.hermesStatusFile;
  let statusTs = 0;
  if (fs.existsSync(sf)) {
    statusTs = mtime(sf);
    try {
      const txt = fs.readFileSync(sf, 'utf8').trim();
      result.detail.status = txt.slice(0, 600);
      result.detail.statusUpdated = statusTs;
    } catch {
      /* ignore */
    }
  }

  // 活动时间 = 状态文件最近修改时间;进程在但文件旧 → 仍视为在跑
  result.lastActivity = statusTs;
  if (procCount > 0) {
    // 进程存活则按状态文件时间判 active/idle,但至少是 idle
    const byFile = deriveState(statusTs, now, config.activeWindowMs, config.idleWindowMs);
    result.state = byFile === 'offline' ? 'idle' : byFile;
  } else {
    result.state = 'offline';
  }

  const firstLine = (result.detail.status || '').split(/\r?\n/)[0] || '';
  result.summary =
    procCount > 0
      ? `${procCount} 个进程在跑` + (firstLine ? ` · ${firstLine.slice(0, 40)}` : '')
      : '未运行';

  return result;
}

import fs from 'fs';
import path from 'path';

export function commandExists(command) {
  const value = String(command || '').trim();
  if (!value) return false;
  if (path.isAbsolute(value)) return fs.existsSync(value);
  return Boolean(findOnPath(value));
}

export function inspectCommand(command) {
  const value = String(command || '').trim();
  return {
    command: value,
    found: commandExists(value),
  };
}

function findOnPath(command) {
  const paths = String(process.env.PATH || '')
    .split(path.delimiter)
    .filter(Boolean);
  const exts =
    process.platform === 'win32'
      ? String(process.env.PATHEXT || '.EXE;.CMD;.BAT;.COM')
          .split(';')
          .filter(Boolean)
      : [''];
  const hasExt = Boolean(path.extname(command));
  for (const dir of paths) {
    const candidates = hasExt ? [path.join(dir, command)] : exts.map((ext) => path.join(dir, command + ext));
    for (const candidate of candidates) {
      if (fs.existsSync(candidate)) return candidate;
    }
  }
  return '';
}

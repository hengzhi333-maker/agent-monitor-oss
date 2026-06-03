import fs from 'fs';
import path from 'path';

export function buildVersionInfo() {
  const pkg = readPackageJson();
  return {
    name: pkg.name || 'agent-monitor-daemon',
    version: pkg.version || '0.0.0',
    apiVersion: 2,
    build: process.env.AM_BUILD_ID || '',
    node: process.version,
    startedAt: STARTED_AT,
  };
}

const STARTED_AT = new Date().toISOString();

function readPackageJson() {
  try {
    return JSON.parse(fs.readFileSync(path.join(process.cwd(), 'package.json'), 'utf8'));
  } catch {
    return {};
  }
}

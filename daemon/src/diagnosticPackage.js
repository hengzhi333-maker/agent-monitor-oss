import os from 'os';
import { readAuditLog } from './auditLog.js';
import { config } from './config.js';
import { historyStorageInfo, getHistory } from './history.js';
import { logStatus, readRecentLogs } from './lib/logger.js';
import { listDevices } from './devices.js';
import { buildVersionInfo } from './version.js';

export function buildDiagnosticPackage() {
  return {
    format: 'agent-monitor.diagnostics.v1',
    generatedAt: new Date().toISOString(),
    runtime: {
      version: buildVersionInfo(),
      node: process.version,
      platform: process.platform,
      pid: process.pid,
      uptimeSec: Math.floor(process.uptime()),
      cwd: process.cwd(),
    },
    host: {
      name: config.host,
      port: config.port,
      bindHost: config.bindHost,
      tlsEnabled: config.tls.enabled === true,
      networkInterfaces: safeNetworkInterfaces(),
    },
    security: {
      tokenCount: config.tokens.length,
      tokenRoles: (config.tokenRecords || []).map((record) => ({
        id: record.id || '',
        name: record.name || '',
        role: record.role || 'admin',
        enabled: record.enabled !== false,
      })),
      remoteControl: {
        enabled: config.remoteControl.enabled,
        allowDangerousPermissions: config.remoteControl.allowDangerousPermissions,
        allowedRemoteAddresses: config.remoteControl.allowedRemoteAddresses,
        allowedCwds: config.remoteControl.allowedCwds,
      },
      alertRules: config.alertRules,
    },
    devices: listDevices().devices,
    history: {
      storage: historyStorageInfo(),
      recent: getHistory({ samples: 20, events: 20 }),
    },
    logs: {
      status: logStatus(),
      recent: readRecentLogs(100),
    },
    audit: {
      recent: readAuditLog(100),
    },
  };
}

function safeNetworkInterfaces() {
  const out = {};
  for (const [name, entries] of Object.entries(os.networkInterfaces())) {
    out[name] = (entries || []).map((item) => ({
      address: item.address,
      family: item.family,
      internal: item.internal,
      mac: item.mac ? maskMac(item.mac) : '',
    }));
  }
  return out;
}

function maskMac(value) {
  const parts = String(value || '').split(':');
  if (parts.length < 2) return '';
  return `${parts[0]}:${parts[1]}:xx:xx:xx:xx`;
}

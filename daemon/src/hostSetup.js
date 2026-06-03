import os from 'os';
import crypto from 'crypto';
import { config } from './config.js';

export function buildHostSetupProfile(options = {}) {
  const includeToken = options.includeToken === true;
  const tailscaleIps = localIpv4Addresses().filter(isTailscaleIpv4);
  const lanIps = localIpv4Addresses().filter((item) => !isLoopbackIpv4(item) && !isTailscaleIpv4(item));
  const preferredAddress =
    cleanAddress(options.address) ||
    tailscaleIps[0] ||
    (config.bindHost && config.bindHost !== '0.0.0.0' && config.bindHost !== '::' ? config.bindHost : '') ||
    config.host;

  const profile = {
    format: 'agent-monitor.host.v1',
    id: daemonIdentity(),
    identityKey: daemonIdentity(),
    name: config.host,
    address: preferredAddress,
    port: config.port,
    secure: config.tls.enabled,
    token: includeToken ? config.token : '',
    hints: {
      tailscale: {
        magicDnsName: config.host,
        ips: tailscaleIps,
      },
      lan: {
        ips: lanIps,
      },
      usb: {
        address: '127.0.0.1',
        adbReverse: `adb reverse tcp:${config.port} tcp:${config.port}`,
      },
    },
  };
  return {
    profile,
    uri: profileToUri(profile),
  };
}

export function profileToUri(profile) {
  const params = new URLSearchParams();
  if (profile.id) params.set('id', profile.id);
  if (profile.identityKey) params.set('identityKey', profile.identityKey);
  params.set('name', profile.name || 'Agent Monitor');
  params.set('address', profile.address || '');
  params.set('port', String(profile.port || 8765));
  params.set('secure', profile.secure ? '1' : '0');
  if (profile.token) params.set('token', profile.token);
  return `agentmonitor://host?${params.toString()}`;
}

function daemonIdentity() {
  const basis = `${os.hostname()}|${config.host}|${config.port}`;
  return `daemon_${crypto.createHash('sha256').update(basis).digest('hex').slice(0, 12)}`;
}

export async function profileToQrSvg(profile) {
  const module = await import('qrcode');
  const qrcode = module.default || module;
  return qrcode.toString(profileToUri(profile), {
    type: 'svg',
    margin: 2,
    errorCorrectionLevel: 'M',
  });
}

function cleanAddress(value) {
  return String(value || '')
    .trim()
    .replace(/^https?:\/\//, '')
    .replace(/^wss?:\/\//, '')
    .replace(/\/.*$/, '')
    .replace(/:\d+$/, '');
}

function localIpv4Addresses() {
  const out = [];
  for (const entries of Object.values(os.networkInterfaces())) {
    for (const item of entries || []) {
      if (item.family === 'IPv4' && item.address) out.push(item.address);
    }
  }
  return [...new Set(out)];
}

function isLoopbackIpv4(value) {
  return String(value || '').startsWith('127.');
}

function isTailscaleIpv4(value) {
  const parts = String(value || '').split('.').map((part) => Number(part));
  return parts.length === 4 && parts[0] === 100 && parts[1] >= 64 && parts[1] <= 127;
}

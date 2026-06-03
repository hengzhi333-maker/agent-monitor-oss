import os from 'os';
import { config } from './config.js';

export function normalizeRemoteAddress(value) {
  return String(value || '')
    .replace(/^::ffff:/, '')
    .replace(/^\[/, '')
    .replace(/\]$/, '')
    .trim();
}

export function requestRemoteAddress(req) {
  return normalizeRemoteAddress(req?.socket?.remoteAddress || '');
}

export function isTailscaleAddress(value) {
  const parts = parseIpv4(value);
  return Boolean(parts && parts[0] === 100 && parts[1] >= 64 && parts[1] <= 127);
}

export function isAllowedRemoteAddress(address, allowed = config.remoteControl.allowedRemoteAddresses) {
  const normalized = normalizeRemoteAddress(address);
  if (isLocalMachineAddress(normalized)) return true;
  if (!Array.isArray(allowed) || allowed.length === 0) return true;
  return allowed.some((entry) => addressMatchesEntry(normalized, entry));
}

export function remoteAccessState(req) {
  const remoteAddress = requestRemoteAddress(req);
  const configured = config.remoteControl.allowedRemoteAddresses.length > 0;
  const allowed = isAllowedRemoteAddress(remoteAddress);
  return {
    configured,
    allowed,
    remoteAddress,
    allowedRemoteAddresses: config.remoteControl.allowedRemoteAddresses,
  };
}

function addressMatchesEntry(address, entry) {
  const rule = String(entry || '').trim();
  if (!rule) return false;
  if (rule === address) return true;
  if (rule === 'tailscale' || rule === 'tailnet') return isTailscaleAddress(address);
  if (rule.includes('/')) return ipv4CidrContains(address, rule);
  return false;
}

function isLoopbackAddress(address) {
  return address === '::1' || address === '127.0.0.1' || address === 'localhost';
}

function isLocalMachineAddress(address) {
  if (isLoopbackAddress(address)) return true;
  for (const interfaces of Object.values(os.networkInterfaces())) {
    for (const item of interfaces || []) {
      if (normalizeRemoteAddress(item.address) === address) return true;
    }
  }
  return false;
}

function ipv4CidrContains(address, cidr) {
  const [base, bitsText] = String(cidr).split('/');
  const bits = Number(bitsText);
  if (!Number.isInteger(bits) || bits < 0 || bits > 32) return false;
  const ip = ipv4ToInt(address);
  const network = ipv4ToInt(base);
  if (ip == null || network == null) return false;
  const mask = bits === 0 ? 0 : (0xffffffff << (32 - bits)) >>> 0;
  return (ip & mask) === (network & mask);
}

function ipv4ToInt(value) {
  const parts = parseIpv4(value);
  if (!parts) return null;
  return (((parts[0] << 24) >>> 0) | (parts[1] << 16) | (parts[2] << 8) | parts[3]) >>> 0;
}

function parseIpv4(value) {
  const parts = String(value || '').split('.');
  if (parts.length !== 4) return null;
  const nums = parts.map((part) => Number(part));
  if (nums.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) return null;
  return nums;
}

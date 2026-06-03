import http from 'http';
import https from 'https';
import { WebSocketServer } from 'ws';
import { config } from './config.js';
import { appendAudit } from './auditLog.js';
import { remoteAccessState } from './accessControl.js';
import { readJsonBody, readRawBody } from './httpBody.js';
import {
  clientIp,
  extractToken,
  hasRole,
  isRateLimited,
  noteAuthFailure,
  noteAuthSuccess,
  tokenAccess,
} from './lib/auth.js';
import {
  buildDiagnostics,
  buildSecurityStatus,
  rotateToken,
  setRemoteControlEnabled,
} from './admin.js';
import { exportDaemonBackup, importDaemonBackup } from './backup.js';
import { buildDiagnosticPackage } from './diagnosticPackage.js';
import {
  createDevice,
  deleteDevice,
  listDevices,
  noteDeviceAccess,
  rotateDevice,
  updateDevice,
} from './devices.js';
import { getHistory, recordSnapshotHistory } from './history.js';
import { buildHostSetupProfile, profileToQrSvg } from './hostSetup.js';
import { logError, logInfo, logStatus, logWarn, readRecentLogs } from './lib/logger.js';
import { buildSnapshot, diffAlerts } from './snapshot.js';
import { getSessionMessages, listAgentSessions } from './conversations.js';
import {
  cleanupWorkbenchData,
  archiveWorkbenchSession,
  createWorkbenchSession,
  deleteWorkbenchAttachment,
  deleteWorkbenchSession,
  getWorkbenchGitDiff,
  getWorkbenchGitStatus,
  getWorkbenchMessages,
  listWorkbenchAttachments,
  listWorkbenchSessions,
  listWorkbenchSessionsWithOptions,
  sendWorkbenchMessage,
  setWorkbenchBroadcaster,
  stopWorkbenchSession,
  uploadWorkbenchAttachment,
} from './workbench.js';
import { MAX_RAW_UPLOAD_BYTES } from './workbenchAttachments.js';
import { buildVersionInfo } from './version.js';

function json(res, code, obj) {
  const body = JSON.stringify(obj);
  const headers = {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
  };
  if (config.corsOrigin) headers['Access-Control-Allow-Origin'] = config.corsOrigin;
  res.writeHead(code, headers);
  res.end(body);
}

function text(res, code, body, contentType = 'text/plain; charset=utf-8') {
  res.writeHead(code, {
    'Content-Type': contentType,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
  });
  res.end(body);
}

function errorJson(res, err) {
  const status = err?.status || 500;
  return json(res, status, {
    error: {
      code: err?.code || 'INTERNAL_ERROR',
      message: status >= 500 ? 'Internal server error' : err.message,
    },
  });
}

function apiError(code, message, status = 500) {
  const error = new Error(message);
  error.code = code;
  error.status = status;
  return error;
}

function requireRole(auth, role) {
  if (hasRole(auth, role)) return;
  throw apiError('INSUFFICIENT_TOKEN_ROLE', `This endpoint requires a ${role} token.`, 403);
}

let lastSnapshot = null;

// ---- HTTP 服务 ----
async function handleRequest(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const ip = clientIp(req);

  if (req.method === 'OPTIONS') {
    const headers = {
      'Access-Control-Allow-Headers': 'Authorization, Content-Type',
      'Access-Control-Allow-Methods': 'GET, POST, PATCH, DELETE, OPTIONS',
    };
    if (config.corsOrigin) headers['Access-Control-Allow-Origin'] = config.corsOrigin;
    res.writeHead(204, headers);
    return res.end();
  }

  // 自身健康检查 —— 免鉴权,供 app 探测 daemon 是否在线
  if (url.pathname === '/ping') {
    return json(
      res,
      200,
      config.pingExposeHost ? { ok: true, host: config.host, ts: Date.now() } : { ok: true, ts: Date.now() }
    );
  }

  if (url.pathname === '/version' && req.method === 'GET') {
    return json(res, 200, buildVersionInfo());
  }

  if (isRateLimited(ip)) {
    appendAudit(req, 'auth.rate_limited', { ok: false, method: req.method, path: url.pathname });
    return json(res, 429, {
      error: {
        code: 'TOO_MANY_FAILED_AUTH_ATTEMPTS',
        message: 'Too many failed authentication attempts.',
      },
    });
  }

  // 其余接口需要鉴权
  const auth = tokenAccess(extractToken(req));
  if (!auth) {
    noteAuthFailure(ip);
    appendAudit(req, 'auth.denied', { ok: false, method: req.method, path: url.pathname });
    return json(res, 401, { error: 'unauthorized' });
  }
  noteAuthSuccess(ip);
  noteDeviceAccess(auth, req);

  const access = remoteAccessState(req);
  if (!access.allowed) {
    appendAudit(req, 'access.denied', {
      ok: false,
      method: req.method,
      path: url.pathname,
      remoteAddress: access.remoteAddress,
    });
    return json(res, 403, {
      error: {
        code: 'REMOTE_ADDRESS_NOT_ALLOWED',
        message: 'Remote address is not in the daemon allowlist.',
      },
    });
  }

  if (url.pathname === '/diagnostics/package' && req.method === 'GET') {
    try {
      requireRole(auth, 'admin');
      appendAudit(req, 'diagnostics.package', { role: auth.role });
      return json(res, 200, buildDiagnosticPackage());
    } catch (err) {
      appendAudit(req, 'diagnostics.package', { ok: false, errorCode: err?.code || 'INTERNAL_ERROR' });
      return errorJson(res, err);
    }
  }

  if (url.pathname === '/observability/logs' && req.method === 'GET') {
    try {
      requireRole(auth, 'admin');
      return json(res, 200, {
        status: logStatus(),
        entries: readRecentLogs(url.searchParams.get('limit') || 100),
      });
    } catch (err) {
      return errorJson(res, err);
    }
  }

  if (url.pathname === '/devices' && req.method === 'GET') {
    try {
      requireRole(auth, 'admin');
      return json(res, 200, listDevices());
    } catch (err) {
      return errorJson(res, err);
    }
  }

  if (url.pathname === '/devices' && req.method === 'POST') {
    try {
      requireRole(auth, 'admin');
      const data = createDevice(await readJsonBody(req));
      appendAudit(req, 'devices.create', { deviceId: data.device.id, role: data.device.role });
      return json(res, 201, data);
    } catch (err) {
      appendAudit(req, 'devices.create', { ok: false, errorCode: err?.code || 'INTERNAL_ERROR' });
      return errorJson(res, err);
    }
  }

  const deviceRoute = parseDeviceRoute(url);
  if (deviceRoute) {
    try {
      requireRole(auth, 'admin');
      if (deviceRoute.action === 'device' && req.method === 'PATCH') {
        const data = updateDevice(deviceRoute.id, await readJsonBody(req));
        appendAudit(req, 'devices.update', { deviceId: deviceRoute.id });
        return json(res, 200, data);
      }
      if (deviceRoute.action === 'device' && req.method === 'DELETE') {
        const data = deleteDevice(deviceRoute.id);
        appendAudit(req, 'devices.delete', { deviceId: deviceRoute.id });
        return json(res, 200, data);
      }
      if (deviceRoute.action === 'rotate' && req.method === 'POST') {
        const data = rotateDevice(deviceRoute.id);
        appendAudit(req, 'devices.rotate', { deviceId: deviceRoute.id });
        return json(res, 200, data);
      }
      return json(res, 405, { error: { code: 'METHOD_NOT_ALLOWED', message: 'Method not allowed.' } });
    } catch (err) {
      appendAudit(req, `devices.${deviceRoute.action}`, { ok: false, errorCode: err?.code || 'INTERNAL_ERROR' });
      return errorJson(res, err);
    }
  }

  if (url.pathname === '/setup/profile' && req.method === 'GET') {
    try {
      requireRole(auth, url.searchParams.get('includeToken') === '1' ? 'admin' : 'read-only');
      const includeToken = url.searchParams.get('includeToken') === '1';
      const data = buildHostSetupProfile({
        includeToken,
        address: url.searchParams.get('address') || '',
      });
      appendAudit(req, 'setup.profile', { includeToken, role: auth.role });
      return json(res, 200, data);
    } catch (err) {
      appendAudit(req, 'setup.profile', { ok: false, errorCode: err?.code || 'INTERNAL_ERROR' });
      return errorJson(res, err);
    }
  }

  if (url.pathname === '/setup/qr.svg' && req.method === 'GET') {
    try {
      requireRole(auth, url.searchParams.get('includeToken') === '1' ? 'admin' : 'read-only');
      const includeToken = url.searchParams.get('includeToken') === '1';
      const { profile } = buildHostSetupProfile({
        includeToken,
        address: url.searchParams.get('address') || '',
      });
      appendAudit(req, 'setup.qr', { includeToken, role: auth.role });
      return text(res, 200, await profileToQrSvg(profile), 'image/svg+xml; charset=utf-8');
    } catch (err) {
      appendAudit(req, 'setup.qr', { ok: false, errorCode: err?.code || 'INTERNAL_ERROR' });
      return errorJson(res, err);
    }
  }

  if (url.pathname === '/backup/export' && req.method === 'GET') {
    try {
      requireRole(auth, 'admin');
      const includeSecrets = url.searchParams.get('includeSecrets') === '1';
      const data = exportDaemonBackup({ includeSecrets });
      appendAudit(req, 'backup.export', { includeSecrets });
      return json(res, 200, data);
    } catch (err) {
      appendAudit(req, 'backup.export', { ok: false, errorCode: err?.code || 'INTERNAL_ERROR' });
      return errorJson(res, err);
    }
  }

  if (url.pathname === '/backup/import' && req.method === 'POST') {
    try {
      requireRole(auth, 'admin');
      const data = importDaemonBackup(await readJsonBody(req));
      appendAudit(req, 'backup.import', { restartRequired: data.restartRequired === true });
      return json(res, 200, data);
    } catch (err) {
      appendAudit(req, 'backup.import', { ok: false, errorCode: err?.code || 'INTERNAL_ERROR' });
      return errorJson(res, err);
    }
  }

  if (url.pathname === '/diagnostics' && req.method === 'GET') {
    return json(res, 200, buildDiagnostics(req));
  }

  if (url.pathname === '/security/status' && req.method === 'GET') {
    return json(res, 200, buildSecurityStatus());
  }

  if (url.pathname === '/security/remote-control' && req.method === 'POST') {
    try {
      requireRole(auth, 'admin');
      const body = await readJsonBody(req);
      const data = setRemoteControlEnabled(body.enabled === true);
      appendAudit(req, 'security.remote_control', { enabled: body.enabled === true });
      return json(res, 200, data);
    } catch (err) {
      appendAudit(req, 'security.remote_control', { ok: false, errorCode: err?.code || 'INTERNAL_ERROR' });
      return errorJson(res, err);
    }
  }

  if (url.pathname === '/security/token/rotate' && req.method === 'POST') {
    try {
      requireRole(auth, 'admin');
      const data = rotateToken();
      appendAudit(req, 'security.token_rotate');
      return json(res, 200, data);
    } catch (err) {
      appendAudit(req, 'security.token_rotate', { ok: false, errorCode: err?.code || 'INTERNAL_ERROR' });
      return errorJson(res, err);
    }
  }

  if (url.pathname === '/snapshot') {
    const snap = lastSnapshot || (await buildSnapshot());
    return json(res, 200, snap);
  }

  if (url.pathname === '/history' && req.method === 'GET') {
    return json(res, 200, getHistory({
      samples: url.searchParams.get('samples'),
      events: url.searchParams.get('events'),
    }));
  }

  if (url.pathname === '/workbench/attachments' && req.method === 'GET') {
    return json(res, 200, listWorkbenchAttachments());
  }

  if (url.pathname === '/workbench/attachments/cleanup' && req.method === 'POST') {
    try {
      requireRole(auth, 'admin');
      const body = await readJsonBody(req);
      const data = await cleanupWorkbenchData({ all: body.all === true, ttlHours: body.ttlHours });
      appendAudit(req, 'workbench.attachments.cleanup', {
        removedAttachments: data.removedAttachments,
        all: body.all === true,
      });
      return json(res, 200, data);
    } catch (err) {
      appendAudit(req, 'workbench.attachments.cleanup', { ok: false, errorCode: err?.code || 'INTERNAL_ERROR' });
      return errorJson(res, err);
    }
  }

  const wbRoute = parseWorkbenchRoute(url);
  if (wbRoute) {
    try {
      if (req.method === 'GET' && wbRoute.kind === 'sessions') {
        return json(
          res,
          200,
          listWorkbenchSessionsWithOptions({ includeArchived: url.searchParams.get('includeArchived') === '1' })
        );
      }
      if (req.method === 'POST' && wbRoute.kind === 'sessions') {
        requireRole(auth, 'operator');
        const body = await readJsonBody(req);
        const data = createWorkbenchSession(body, { auth });
        appendAudit(req, 'workbench.session.create', {
          agentId: body.agentId,
          cwd: body.cwd || '',
          permissionMode: body.permissionMode || '',
          sessionId: data.session?.id || '',
        });
        return json(res, 201, data);
      }
      if (req.method === 'POST' && wbRoute.kind === 'archive') {
        requireRole(auth, 'operator');
        const data = await archiveWorkbenchSession(wbRoute.sessionId, true);
        appendAudit(req, 'workbench.session.archive', { sessionId: wbRoute.sessionId });
        return json(res, 200, data);
      }
      if (req.method === 'POST' && wbRoute.kind === 'unarchive') {
        requireRole(auth, 'operator');
        const data = await archiveWorkbenchSession(wbRoute.sessionId, false);
        appendAudit(req, 'workbench.session.unarchive', { sessionId: wbRoute.sessionId });
        return json(res, 200, data);
      }
      if (req.method === 'DELETE' && wbRoute.kind === 'session') {
        requireRole(auth, 'admin');
        const data = await deleteWorkbenchSession(wbRoute.sessionId);
        appendAudit(req, 'workbench.session.delete', { sessionId: wbRoute.sessionId });
        return json(res, 200, data);
      }
      if (req.method === 'GET' && wbRoute.kind === 'messages') {
        const data = getWorkbenchMessages(wbRoute.sessionId);
        if (!data) {
          return json(res, 404, {
            error: { code: 'SESSION_NOT_FOUND', message: 'Workbench session was not found.' },
          });
        }
        return json(res, 200, data);
      }
      if (req.method === 'GET' && wbRoute.kind === 'git-status') {
        return json(res, 200, await getWorkbenchGitStatus(wbRoute.sessionId));
      }
      if (req.method === 'GET' && wbRoute.kind === 'git-diff') {
        return json(
          res,
          200,
          await getWorkbenchGitDiff(wbRoute.sessionId, {
            cached: url.searchParams.get('cached') === 'true',
          })
        );
      }
      if (req.method === 'POST' && wbRoute.kind === 'messages') {
        requireRole(auth, 'operator');
        const body = await readJsonBody(req);
        const data = sendWorkbenchMessage(wbRoute.sessionId, body);
        appendAudit(req, 'workbench.message.send', {
          sessionId: wbRoute.sessionId,
          textLength: String(body.text || '').length,
          attachmentCount: Array.isArray(body.attachmentIds) ? body.attachmentIds.length : 0,
        });
        return json(res, 202, data);
      }
      if (req.method === 'POST' && wbRoute.kind === 'attachments') {
        requireRole(auth, 'operator');
        const name = url.searchParams.get('name') || req.headers['x-file-name'] || '';
        const mime = url.searchParams.get('mime') || req.headers['content-type'] || 'application/octet-stream';
        const data = await uploadWorkbenchAttachment(wbRoute.sessionId, {
          name,
          mime,
          buffer: await readRawBody(req, MAX_RAW_UPLOAD_BYTES),
        });
        appendAudit(req, 'workbench.attachment.upload', {
          sessionId: wbRoute.sessionId,
          name,
          mime,
          size: data.attachment?.size || 0,
          kind: data.attachment?.kind || '',
        });
        return json(res, 201, data);
      }
      if (req.method === 'DELETE' && wbRoute.kind === 'attachment') {
        requireRole(auth, 'operator');
        const data = await deleteWorkbenchAttachment(wbRoute.sessionId, wbRoute.attachmentId);
        appendAudit(req, 'workbench.attachment.delete', {
          sessionId: wbRoute.sessionId,
          attachmentId: wbRoute.attachmentId,
        });
        return json(res, 200, data);
      }
      if (req.method === 'POST' && wbRoute.kind === 'stop') {
        requireRole(auth, 'operator');
        const data = stopWorkbenchSession(wbRoute.sessionId);
        appendAudit(req, 'workbench.session.stop', { sessionId: wbRoute.sessionId });
        return json(res, 200, data);
      }
      return json(res, 405, { error: { code: 'METHOD_NOT_ALLOWED', message: 'Method not allowed.' } });
    } catch (err) {
      logError('workbench route failed', {
        route: wbRoute.kind,
        sessionId: wbRoute.sessionId || '',
        errorCode: err?.code || 'INTERNAL_ERROR',
        error: err?.message || String(err),
      });
      appendAudit(req, `workbench.${wbRoute.kind}`, {
        ok: false,
        sessionId: wbRoute.sessionId || '',
        errorCode: err?.code || 'INTERNAL_ERROR',
      });
      return errorJson(res, err);
    }
  }

  const route = parseAgentRoute(url);
  if (route?.kind === 'sessions') {
    const sessions = listAgentSessions(route.agentId, { limit: url.searchParams.get('limit') });
    if (!sessions) return json(res, 404, { error: 'agent not found' });
    return json(res, 200, { agentId: route.agentId, sessions });
  }

  if (route?.kind === 'messages') {
    const data = getSessionMessages(route.agentId, route.sessionId, {
      limit: url.searchParams.get('limit'),
    });
    if (!data) return json(res, 404, { error: 'session not found' });
    return json(res, 200, data);
  }

  return json(res, 404, { error: 'not found' });
}

const server = config.tls.enabled
  ? https.createServer({ cert: config.tls.cert, key: config.tls.key }, handleRequest)
  : http.createServer(handleRequest);

function parseAgentRoute(url) {
  const parts = url.pathname.split('/').filter(Boolean).map(decodeURIComponent);
  if (parts[0] !== 'agents' || !parts[1] || parts[2] !== 'sessions') return null;
  if (parts.length === 3) return { kind: 'sessions', agentId: parts[1] };
  if (parts.length === 5 && parts[4] === 'messages') {
    return { kind: 'messages', agentId: parts[1], sessionId: parts[3] };
  }
  return null;
}

function parseWorkbenchRoute(url) {
  const parts = url.pathname.split('/').filter(Boolean).map(decodeURIComponent);
  if (parts[0] !== 'workbench' || parts[1] !== 'sessions') return null;
  if (parts.length === 2) return { kind: 'sessions' };
  if (parts.length === 3) return { kind: 'session', sessionId: parts[2] };
  if (parts.length === 4 && parts[3] === 'archive') {
    return { kind: 'archive', sessionId: parts[2] };
  }
  if (parts.length === 4 && parts[3] === 'unarchive') {
    return { kind: 'unarchive', sessionId: parts[2] };
  }
  if (parts.length === 4 && parts[3] === 'messages') {
    return { kind: 'messages', sessionId: parts[2] };
  }
  if (parts.length === 5 && parts[3] === 'git' && parts[4] === 'status') {
    return { kind: 'git-status', sessionId: parts[2] };
  }
  if (parts.length === 5 && parts[3] === 'git' && parts[4] === 'diff') {
    return { kind: 'git-diff', sessionId: parts[2] };
  }
  if (parts.length === 4 && parts[3] === 'attachments') {
    return { kind: 'attachments', sessionId: parts[2] };
  }
  if (parts.length === 5 && parts[3] === 'attachments') {
    return { kind: 'attachment', sessionId: parts[2], attachmentId: parts[4] };
  }
  if (parts.length === 4 && parts[3] === 'stop') {
    return { kind: 'stop', sessionId: parts[2] };
  }
  return null;
}

function parseDeviceRoute(url) {
  const parts = url.pathname.split('/').filter(Boolean).map(decodeURIComponent);
  if (parts[0] !== 'devices' || !parts[1]) return null;
  if (parts.length === 2) return { id: parts[1], action: 'device' };
  if (parts.length === 3 && parts[2] === 'rotate') return { id: parts[1], action: 'rotate' };
  return null;
}

// ---- WebSocket:实时推送快照 + 告警 ----
const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  if (url.pathname !== '/ws') {
    socket.destroy();
    return;
  }
  const ip = clientIp(req);
  if (isRateLimited(ip)) {
    appendAudit(req, 'ws.auth.rate_limited', { ok: false, path: url.pathname });
    socket.write('HTTP/1.1 429 Too Many Requests\r\n\r\n');
    socket.destroy();
    return;
  }
  const wsAuth = tokenAccess(extractToken(req));
  if (!wsAuth) {
    noteAuthFailure(ip);
    appendAudit(req, 'ws.auth.denied', { ok: false, path: url.pathname });
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }
  noteAuthSuccess(ip);
  noteDeviceAccess(wsAuth, req);
  const access = remoteAccessState(req);
  if (!access.allowed) {
    appendAudit(req, 'ws.access.denied', { ok: false, remoteAddress: access.remoteAddress });
    socket.write('HTTP/1.1 403 Forbidden\r\n\r\n');
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', (ws) => {
  // 新连接立即推送当前快照
  if (lastSnapshot) {
    ws.send(JSON.stringify({ type: 'snapshot', data: lastSnapshot }));
  }
  ws.on('message', (m) => {
    // 心跳
    if (m.toString() === 'ping') ws.send('pong');
  });
});

function broadcast(obj) {
  const msg = JSON.stringify(obj);
  for (const client of wss.clients) {
    if (client.readyState === 1) client.send(msg);
  }
}

setWorkbenchBroadcaster(broadcast);

// ---- 采集循环 ----
async function tick() {
  try {
    const snap = await buildSnapshot();
    const alerts = diffAlerts(lastSnapshot, snap);
    recordSnapshotHistory(lastSnapshot, snap, alerts);
    lastSnapshot = snap;
    broadcast({ type: 'snapshot', data: snap });
    for (const a of alerts) {
      broadcast({ type: 'alert', data: a });
      logWarn('alert emitted', {
        level: a.level,
        title: a.title,
        body: a.body,
        sourceId: a.agent || '',
      });
    }
  } catch (e) {
    logError('snapshot collection failed', {
      error: e?.message || String(e),
      stack: e?.stack || '',
    });
  }
}

let timer = null;

if (config.tokens.includes('change-me-please') && !config.allowDefaultToken) {
  logError('refusing to start with default token');
  console.error('agent-monitor daemon 拒绝使用默认 token 启动。请运行 `npm run gen-token` 并写入 config.json, 或设置 AM_ALLOW_DEFAULT_TOKEN=1 临时允许。');
  process.exit(1);
}

server.requestTimeout = 30_000;
server.headersTimeout = 10_000;
server.keepAliveTimeout = 5_000;

server.listen(config.port, config.bindHost, () => {
  const scheme = config.tls.enabled ? 'https' : 'http';
  const masked = config.token.length > 6 ? config.token.slice(0, 3) + '***' : '***';
  logInfo('agent-monitor daemon started', {
    host: config.host,
    bindHost: config.bindHost,
    port: config.port,
    scheme,
    tls: config.tls.enabled,
    tokenCount: config.tokens.length,
  });
  console.log(`agent-monitor daemon 已启动`);
  console.log(`  主机名 : ${config.host}`);
  console.log(`  监听   : ${scheme}://${config.bindHost}:${config.port}${config.tls.enabled ? ' (TLS)' : ''}`);
  console.log(`  REST   : GET /ping (免鉴权) · GET /snapshot · GET /history · GET /agents/:id/sessions · GET /agents/:id/sessions/:sid/messages · WS /ws`);
  console.log(`  Token  : ${masked}  (共 ${config.tokens.length} 个,手机端需带其中之一)`);
  if (config.tokens.includes('change-me-please')) {
    logWarn('daemon is using the default token');
    console.log('  ⚠️  正在使用默认 token,请运行 `npm run gen-token` 生成并写入 config.json');
  }
  if (!config.tls.enabled) {
    logWarn('daemon is serving plaintext HTTP', { bindHost: config.bindHost, port: config.port });
    console.log('  ⚠️  当前为明文 HTTP,生产环境请配置 tls 或置于 Tailscale/可信网络之后');
  }
  tick();
  timer = setInterval(tick, config.pollIntervalMs);
});

let shuttingDown = false;
function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  logInfo('shutdown requested', { signal });
  console.log(`\n收到 ${signal},正在关闭...`);
  if (timer) clearInterval(timer);
  for (const ws of wss.clients) ws.close(1001, 'server shutting down');
  wss.close();
  server.close(() => {
    logInfo('daemon stopped');
    console.log('已关闭。');
    process.exit(0);
  });
  setTimeout(() => process.exit(0), 5000).unref();
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));

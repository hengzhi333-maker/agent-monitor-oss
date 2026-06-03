# Daemon API

All protected routes require:

```http
Authorization: Bearer <daemon-token>
```

Any token configured in `token` or `tokens` is accepted. `tokens` may contain
objects with `name`, `role`, and `token`. Roles are enforced as:

- `read-only`: health, snapshots, history, conversations, diagnostics, and
  read-only workbench state.
- `operator`: read-only plus creating/stopping workbench sessions, sending
  messages, and managing session attachments.
- `admin`: operator plus security settings, token rotation, setup QR including
  token material, device management, diagnostics packages, backup, and
  attachment cleanup.

Repeated failed authentication attempts from one source are rate limited and
return HTTP 429.

`GET /ping` is intentionally unauthenticated and returns only a minimal health
response unless `pingExposeHost` is enabled.

## Health and Status

### `GET /ping`

Returns:

```json
{ "ok": true, "ts": 1760000000000 }
```

### `GET /version`

Unauthenticated version probe used by Android diagnostics.

```json
{
  "name": "agent-monitor-daemon",
  "version": "0.2.0",
  "apiVersion": 2,
  "build": "",
  "node": "v22.16.0",
  "startedAt": "2026-06-02T00:00:00.000Z"
}
```

### `GET /snapshot`

Returns current host, agent, service, and account-health state.

### `GET /history?samples=80&events=80`

Returns recent SQLite-backed monitoring samples and alert/recovery events.
History is written to `.agent-monitor-history.sqlite` in the daemon directory.

Example response:

```json
{
  "storage": {
    "kind": "sqlite",
    "path": "C:\\path\\to\\daemon\\.agent-monitor-history.sqlite",
    "samples": 120,
    "events": 8
  },
  "samples": [
    {
      "ts": 1760000000000,
      "host": "workstation",
      "agentCounts": { "active": 1, "offline": 1 },
      "serviceCounts": { "up": 2 },
      "totals": {
        "sessionsToday": 4,
        "inputTokens": 120000,
        "outputTokens": 3400,
        "cacheReadTokens": 80000,
        "cacheCreateTokens": 2000
      },
      "agents": [{ "id": "codex", "name": "Codex", "state": "active" }],
      "services": [{ "id": "api", "name": "API", "state": "up", "httpCode": 200, "latencyMs": 34 }],
      "alertCount": 0
    }
  ],
  "events": [
    {
      "id": "1760000000000:codex:0",
      "kind": "alert",
      "sourceId": "codex",
      "level": "info",
      "title": "Codex 宸叉仮澶?,
      "body": "workstation 涓婄殑 Codex 浠?offline 鍙樹负 active",
      "ts": 1760000000000
    }
  ],
  "trend": [
    {
      "ts": 1760000000000,
      "onlineAgents": 1,
      "offlineAgents": 1,
      "downServices": 0,
      "sessionsToday": 4,
      "inputTokens": 120000,
      "outputTokens": 3400,
      "cacheTokens": 82000,
      "alertCount": 0
    }
  ]
}
```

### `GET /diagnostics`

Returns daemon bind settings, remote address, Tailscale hints, and workbench
configuration.

### `GET /diagnostics/package`

Admin-only diagnostic export with runtime metadata, masked network interfaces,
token role summaries, device last-seen data, history storage/recent samples,
recent structured logs, and recent audit events.

### `GET /observability/logs?limit=100`

Admin-only structured log tail.

```json
{
  "status": {
    "path": "C:\\path\\to\\daemon\\.agent-monitor.log.jsonl",
    "rotateBytes": 5242880,
    "size": 2048,
    "exists": true
  },
  "entries": [
    { "ts": "2026-06-01T12:00:00.000Z", "level": "info", "message": "agent-monitor daemon started" }
  ]
}
```

### `GET /security/status`

Returns security checks, remote-control settings, upload limits, privacy flags,
transport status, auth rate-limit status, token strength status, alert rules,
and recent audit events.

## Setup, QR, and Backup

### `GET /setup/profile?includeToken=0`

Returns an authenticated Android import profile plus an `agentmonitor://host`
deep link. `includeToken=1` requires an admin token and embeds the current
daemon token in the profile and URI.

```json
{
  "profile": {
    "format": "agent-monitor.host.v1",
    "id": "daemon_abcdef123456",
    "identityKey": "daemon_abcdef123456",
    "name": "workstation",
    "address": "100.64.0.10",
    "port": 8765,
    "secure": false,
    "token": "",
    "hints": {
      "tailscale": { "magicDnsName": "workstation", "ips": ["100.64.0.10"] },
      "lan": { "ips": ["192.168.0.243"] },
      "usb": { "address": "127.0.0.1", "adbReverse": "adb reverse tcp:8765 tcp:8765" }
    }
  },
  "uri": "agentmonitor://host?id=daemon_abcdef123456&identityKey=daemon_abcdef123456&name=workstation&address=100.64.0.10&port=8765&secure=0"
}
```

`id`/`identityKey` are stable per daemon instance and let Android replace the
same workstation when switching between USB, LAN, and Tailscale instead of
creating duplicate workbench entries.

### `GET /setup/qr.svg?includeToken=1`

Returns an SVG QR code for the same `agentmonitor://host` deep link. QR codes
with `includeToken=1` are secrets.

### `GET /backup/export?includeSecrets=0`

Admin-only daemon config backup. Secret fields are blank unless
`includeSecrets=1`.

### `POST /backup/import`

Admin-only daemon config restore. The response includes `restartRequired: true`;
restart the daemon to load the imported runtime config.

## Security Administration

### `POST /security/remote-control`

Body:

```json
{ "enabled": true }
```

### `POST /security/token/rotate`

Rotates the daemon token. Requires a configured remote address allowlist.

## Device Management

Device management endpoints require an admin token. They operate on the daemon
`tokens` list and return a full token only at creation or rotation time.

### `GET /devices`

```json
{
  "devices": [
    {
      "id": "phone-admin",
      "name": "phone-admin",
      "role": "admin",
      "enabled": true,
      "tokenPreview": "abcd...1234",
      "lastSeen": 1760000000000,
      "remoteAddress": "100.64.0.10",
      "userAgent": "okhttp/4.12.0"
    }
  ]
}
```

### `POST /devices`

```json
{ "name": "tablet", "role": "read-only" }
```

Returns `{ "token": "...", "device": {...} }`.

### `PATCH /devices/:id`

```json
{ "name": "phone", "role": "operator", "enabled": true }
```

All fields are optional.

### `POST /devices/:id/rotate`

Returns a new one-time token for that device.

### `DELETE /devices/:id`

Deletes the device token.

## Conversation History

### `GET /agents/:agentId/sessions?limit=80`

`agentId` is `codex` or `claude-code`.

### `GET /agents/:agentId/sessions/:sessionId/messages?limit=180`

Returns redacted messages for one historical agent session.

## Workbench

### `GET /workbench/sessions?includeArchived=0`

Lists remote workbench sessions. Archived sessions are hidden unless
`includeArchived=1` is passed. Session objects include `archived`,
`dangerousExpiresAt`, and `attachmentCount`.

### `POST /workbench/sessions`

Requires an `operator` token. `permissionMode: "dangerous"` requires an `admin`
token, `remoteControl.allowDangerousPermissions: true`, and expires after
`remoteControl.dangerousSessionTtlMs`.

Body:

```json
{
  "agentId": "codex",
  "cwd": "C:\\Users\\you\\Documents\\AgentWorkspace",
  "title": "Codex Workbench",
  "permissionMode": "standard"
}
```

### `POST /workbench/sessions/:sessionId/archive`

Requires an `operator` token. Archives an idle/stopped session and hides it from
the default list.

### `POST /workbench/sessions/:sessionId/unarchive`

Requires an `operator` token. Restores an archived session to the default list.

### `DELETE /workbench/sessions/:sessionId`

Requires an `admin` token. Deletes a non-running session and removes uploaded
attachment files belonging to that session.

### `GET /workbench/sessions/:sessionId/messages`

Returns messages for one workbench session.

### `GET /workbench/sessions/:sessionId/git/status`

Returns Git state for the workbench session's configured `cwd`. The daemon does
not accept an arbitrary path for this route; it always inspects the existing
session working directory.

Example response:

```json
{
  "sessionId": "wb_example",
  "status": {
    "isRepo": true,
    "branch": "main",
    "root": "C:\\Users\\you\\Documents\\AgentWorkspace",
    "statusLines": ["## main", " M README.md"],
    "diffStat": "README.md | 2 +-",
    "stagedDiffStat": "",
    "lastCommit": "abc1234 Improve workbench",
    "error": ""
  }
}
```

### `GET /workbench/sessions/:sessionId/git/diff?cached=false`

Returns the current Git diff for the workbench session `cwd`. Pass
`cached=true` to read the staged diff. Large diffs are truncated and marked with
`truncated: true`.

### `POST /workbench/sessions/:sessionId/messages`

Body:

```json
{
  "text": "Review this change",
  "attachmentIds": ["att_example"]
}
```

### `POST /workbench/sessions/:sessionId/attachments?name=file.pdf&mime=application/pdf`

Uploads a raw attachment body. Supported kinds: image, text, code, PDF, Word,
`.xlsx`, and CSV. Legacy binary `.xls` files are rejected because the previous
parser dependency has unresolved high-severity advisories. The daemon enforces
per-kind and global size limits.

### `DELETE /workbench/sessions/:sessionId/attachments/:attachmentId`

Deletes an uploaded attachment file and removes it from the session attachment
index. Message history keeps only public attachment metadata.

### `GET /workbench/attachments`

Lists uploaded workbench attachments across sessions.

### `POST /workbench/attachments/cleanup`

Body:

```json
{ "all": false, "ttlHours": 168 }
```

`all: true` removes all uploaded attachment files. Otherwise, the daemon removes
files older than `ttlHours`, or the configured `remoteControl.attachmentTtlHours`.

### `POST /workbench/sessions/:sessionId/stop`

Stops the running workbench turn.

## WebSocket

### `GET /ws`

Requires bearer auth and remote address allowlist. Use `wss://` when daemon TLS
or reverse-proxy TLS is enabled. Event envelope:

```json
{ "type": "snapshot", "data": {} }
```

Common event types:

- `snapshot`
- `alert`
- `workbench.session.created`
- `workbench.session.updated`
- `workbench.message`
- `workbench.output.delta`
- `workbench.output.line`
- `workbench.attachment.created`
- `workbench.attachment.deleted`
- `workbench.error`
- `workbench.turn.completed`


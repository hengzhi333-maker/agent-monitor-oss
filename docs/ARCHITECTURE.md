# Architecture

Agent Monitor is split into two deployable modules:

- `daemon/`: Node.js process running on the workstation.
- `android/`: Android app used as the remote workbench.

## Daemon Modules

- `src/index.js`: HTTP and WebSocket entrypoint. It applies bearer auth, remote
  address allowlist checks, request body limits, and route dispatch.
- `src/lib/auth.js`: multi-token bearer validation, role checks, and failed-auth
  rate limiting for REST and WebSocket upgrades.
- `src/httpBody.js`: limited JSON/raw body reader used by all state-changing
  routes.
- `src/accessControl.js`: remote address normalization and CIDR allowlist logic.
- `src/admin.js`: diagnostics, security checks, remote-control toggle, and token
  rotation.
- `src/hostSetup.js`: Android import profile, deep-link, and QR generation.
- `src/backup.js`: admin-only daemon config backup/restore.
- `src/snapshot.js` and `src/collectors/`: local agent and service status
  collection and configurable alert transition rules.
- `src/history.js`: in-memory ring buffer for recent snapshot summaries,
  trend points, and alert/recovery events.
- `src/conversations.js`: read-only Codex/Claude conversation history with
  default redaction.
- `src/workbench.js`: remote workbench sessions, agent process lifecycle,
  message persistence, and attachment lifecycle.
- `src/workbenchAttachments.js`: upload classification, extraction, prompt
  composition, and safe attachment file removal.
- `src/workbenchGit.js`: Git status and diff inspection scoped to an existing
  workbench session working directory.

## Android Modules

- `data/HostStore.kt`: encrypted host/token storage backed by Android Keystore,
  local host-list export/import, and USB/LAN/Tailscale deduplication using
  daemon `identityKey`, token/name matching, and connection-type preference.
- `data/MonitorRepository.kt`: daemon REST/WebSocket client and user-facing
  error normalization.
- `data/Models.kt`: daemon wire models.
- `MonitorEngine.kt`: process-level connection loop, snapshot state, local
  offline/workbench notifications, and workbench event fan-out shared by UI and
  foreground service.
- `MonitorService.kt`: foreground service that keeps daemon monitoring alive
  while Android backgrounds the app.
- `ui/MonitorViewModel.kt`: thin UI adapter over `MonitorEngine`.
- `ui/screens/*`: Jetpack Compose screens for dashboard, agents, workbench,
  files, diagnostics, security, host setup, and backup/import.

## Data Flow

1. Android stores a daemon URL and token.
2. Android calls `/snapshot` for an initial state.
3. Android opens `/ws` for live snapshot, alert, and workbench events.
4. Android can call `/history` to render recent monitoring samples and
   alert/recovery events/trends without reading daemon logs from disk.
5. Workbench messages are sent to daemon REST endpoints.
6. The daemon starts Codex or Claude Code with safe argument arrays and streams
   visible output back over WebSocket.
7. The Android workbench can request Git status/diff for the active session cwd.
8. Uploaded files are stored under `.workbench-uploads/`, extracted when
   supported, and cleaned by TTL or explicit delete/cleanup calls.

## Design Notes

- The daemon is local-first. Tailscale or a trusted LAN is the expected network
  layer.
- The phone workbench is equivalent to remote command execution on the
  workstation. Keep `allowedRemoteAddresses` and `allowedCwds` narrow.
- Images can still be passed to Codex as local file arguments, but local paths
  are not included in the natural-language prompt sent to the agent.
- Conversation history and audit output apply redaction for common secrets,
  authorization headers, emails, and local user paths.
- Monitoring history is SQLite-backed local runtime state; it is meant for
  recent mobile context and diagnostics, not long-term incident storage.
- Alert rules are applied in the daemon before WebSocket delivery, while Android
  still applies local duplicate-notification suppression for the system tray.
- Token roles are checked at route boundaries. Read-only tokens can monitor;
  operator tokens can control workbench sessions; admin tokens can change
  security, rotate tokens, and export/import daemon configuration.
- Host import QR codes are authenticated daemon output. QR codes that include a
  token are equivalent to the token itself.

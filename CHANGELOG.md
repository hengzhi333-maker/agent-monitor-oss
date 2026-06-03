# Changelog

All notable changes to Agent Monitor are documented here.

## 1.1.0 - 2026-06-03

- Added Android foreground monitoring, local host-offline notifications, and
  workbench completion notifications.
- Added encrypted host backup/import, QR/deep-link import, and stable host
  identity deduplication for USB, LAN, and Tailscale.
- Added daemon history, diagnostics package export, structured logs, device
  token management, role-scoped tokens, setup QR/profile endpoints, and version
  probes.
- Added workbench session archive/delete, attachment cleanup, Git status/diff,
  permission templates, and dangerous-session TTL enforcement.
- Added Windows service helpers, private install/upgrade scripts, systemd, PM2,
  Docker examples, release packaging, and open-source hygiene tooling.
- Hardened defaults for public source publication: private runtime files are
  ignored, config examples use placeholders, and the daemon example binds to
  `127.0.0.1`.

## 1.0.0 - 2026-05-31

- Initial Android and Node.js daemon release for private-network agent
  monitoring.

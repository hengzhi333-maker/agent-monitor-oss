# Security Policy

## Supported Use

Agent Monitor is intended for self-hosted use on a trusted private network,
preferably Tailscale or a local LAN. The daemon is not designed to be exposed
directly to the public internet.

## Main Risks

- The phone workbench can trigger real agent execution on the workstation.
- Workbench messages and uploaded files may contain source code, local paths,
  credentials, or other private data.
- A leaked daemon token allows access to protected daemon APIs.
- A setup QR generated with `includeToken=1` is equivalent to sharing that
  daemon token.
- Plain HTTP is acceptable on Tailscale/LAN, but not for public exposure.

## Required Hardening

- Generate a unique daemon token with `npm run gen-token`.
- Prefer role-scoped `tokens`: `read-only` for dashboards, `operator` for
  workbench control, and `admin` only for security/backup operations.
- Keep `daemon/config.json` out of source control.
- Restrict `remoteControl.allowedRemoteAddresses`.
- Restrict `remoteControl.allowedCwds`.
- Keep `remoteControl.attachmentTtlHours` enabled unless you have a separate
  cleanup process.
- Keep `remoteControl.allowDangerousPermissions` disabled unless you understand
  the risk.
- If dangerous workbench mode is enabled, keep
  `remoteControl.dangerousSessionTtlMs` short. Dangerous sessions require an
  admin token and expire back to standard mode after this window.
- Use environment variables for sub2api credentials.
- Rotate credentials immediately if they were committed or shared.
- Use a TLS reverse proxy or VPN if traffic leaves a trusted private network.
- Use the Android Files screen or `POST /workbench/attachments/cleanup` to
  remove uploaded workbench files you no longer need.
- Archive or delete old workbench sessions from Android so stale high-risk
  context and uploaded files do not accumulate.
- Delete exported QR files and Android host backups after use, or store them in
  an encrypted password manager.

## Reporting Vulnerabilities

Please do not open public issues containing secrets, tokens, local hostnames,
uploaded files, or private conversation logs. Open a minimal report with:

- affected version or commit,
- impact,
- reproduction steps using dummy data,
- expected and actual behavior.

If you accidentally disclose a credential, rotate it before reporting.

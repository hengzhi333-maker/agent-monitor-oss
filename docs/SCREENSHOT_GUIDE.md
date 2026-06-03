# Screenshot and Demo Guide

Screenshots, GIFs, and videos can reveal private infrastructure. Capture public
media only from sanitized data or from mockups.

## Never Show

- daemon tokens, device tokens, QR codes with `includeToken=1`, bearer headers,
  cookies, API keys, or passwords
- real Tailscale IPs, MagicDNS names, LAN addresses, hostnames, usernames, or
  device serial numbers
- local absolute paths, repository names under private workspaces, or customer
  code
- workbench prompts, responses, uploaded files, PDFs, spreadsheets, or images
  that are not intended for publication
- account emails, account-health details, audit logs, and diagnostic packages

## Use Placeholders

- `workstation` for hostnames
- `100.64.0.10` for sample Tailscale IPs
- `192.168.1.20` for sample LAN IPs
- `C:\Users\you\Documents\AgentWorkspace` for Windows paths
- `<token>`, `<password>`, and `<redacted>` for secrets

## Recommended Capture Flow

1. Clear app data or use a test device/profile.
2. Import only a mock or disposable daemon host.
3. Use generated demo data rather than private conversations.
4. Crop out the Android status bar if it contains personal indicators.
5. Run `scripts/scan-open-source-leaks.ps1` after adding media metadata or
   captions.

The checked-in README screenshots under `docs/assets/screenshots/` are
sanitized SVG mockups. Replace them only with equally sanitized assets.

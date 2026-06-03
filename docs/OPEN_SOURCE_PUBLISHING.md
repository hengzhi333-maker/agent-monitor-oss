# Open Source Publishing Guide

This guide prepares a public source tree without private runtime configuration.

## Create a Sanitized Source Folder

From the private working tree:

```powershell
.\scripts\prepare-open-source.ps1 -OutputDir ..\agent-monitor-oss-open-source -Force
```

The generated folder is a source tree for GitHub preparation. It excludes:

- `daemon/config.json`
- daemon logs, audit logs, device inventory, history SQLite files, sessions, and uploads
- `daemon/node_modules/`
- Android local SDK config, signing config, keystores, APKs, AABs, and build output
- `dist/` release bundles
- internal notes under `docs/superpowers/`

Do not publish the private working tree directly.

The generated folder includes `CHANGELOG.md`, `.github/release-template.md`,
sanitized README screenshot mockups, Docker examples, CI, and all public docs.

## Five Minute First Run

```powershell
cd daemon
npm install
Copy-Item .\config.example.json .\config.json
npm run gen-token
```

Paste the generated token into `config.json`. Keep the default `bindHost` of
`127.0.0.1` for local testing. For phone access over Tailscale, set `bindHost`
to the workstation Tailscale IP or to `0.0.0.0` with a strict token and
`remoteControl.allowedRemoteAddresses`.

Start the daemon:

```powershell
npm start
```

Then add the host in Android with:

- host: Tailscale IP, MagicDNS name, LAN IP, or `127.0.0.1` when using USB reverse
- port: `8765`
- token: the generated token

## QR Import

Generate a setup QR only on a trusted machine and delete it after import:

```powershell
curl.exe -H "Authorization: Bearer <admin-token>" `
  "http://127.0.0.1:8765/setup/qr.svg?includeToken=1" `
  -o agent-monitor-host.svg
```

The QR embeds a bearer token when `includeToken=1`, so treat the file as a
secret.

## Android Signing

Release APKs require ignored local signing files:

```powershell
cd android
Copy-Item .\keystore.properties.example .\keystore.properties
```

For private builds, generate a local key:

```powershell
.\scripts\create-android-keystore.ps1
.\scripts\build-release.ps1 -AndroidVariant release
```

Never publish `android/keystore.properties`, keystores, `.p12` files, APKs, or
AABs in the source repository.

## Service Deployment

Use one of the examples after editing paths and config:

- Windows service or scheduled-task fallback: `daemon/windows/install-service.ps1`
- Windows local control panel: `daemon/windows/control-panel.ps1`
- Linux systemd: `daemon/systemd/agent-monitor.service.example`
- PM2: `daemon/ecosystem.config.cjs`
- Docker: `daemon/docker-compose.example.yml`

Docker is best for monitor-only or lab use. Workbench sessions run inside the
container, so install the agent CLIs there and mount only the workspaces you
intend to expose.

## Pre-Publish Gates

Run from the sanitized source folder:

```powershell
.\scripts\scan-open-source-leaks.ps1
cd daemon
npm test
npm --registry=https://registry.npmjs.org/ audit --omit=dev --audit-level=high
cd ..\android
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

Release signing must be configured before `assembleRelease`.

GitHub Actions runs the same hygiene checks, daemon tests, npm audit, Android
unit tests, Android instrumentation APK build, and a CI-only release build with
a temporary signing key.

## Screenshots and Demos

The README uses sanitized SVG mockups under `docs/assets/screenshots/`. Before
adding real device screenshots or GIFs, follow `docs/SCREENSHOT_GUIDE.md` and
remove all tokens, hostnames, Tailscale IPs, paths, account data, and private
workbench content.

## FAQ

**Should I expose the daemon to the public internet?**

No. Use Tailscale, a trusted LAN, VPN, or a private reverse proxy with TLS and
strict access control.

**Why can Android connect over USB but not after unplugging?**

USB usually depends on `adb reverse tcp:8765 tcp:8765`. Use Tailscale or LAN for
persistent phone-to-workstation access.

**Why do duplicate USB/Tailscale hosts appear?**

Import the daemon setup QR or deep link so Android receives a stable
`identityKey`, then use Settings -> `Clean old USB hosts` after Tailscale works.

**Why can I not uninstall an old Samsung test build from ADB?**

Samsung Secure Folder is a separate Android profile. Remove the APK inside
Secure Folder separately.

**Why does the phone say the daemon is offline?**

Check `bindHost`, Windows firewall, the daemon port, token role, and whether the
phone can reach the workstation Tailscale or LAN address.

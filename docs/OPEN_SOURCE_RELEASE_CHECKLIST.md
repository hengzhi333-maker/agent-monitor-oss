# Open Source Release Checklist

Run this checklist before pushing to GitHub.

## Prepare a Clean Folder

- [ ] Generate a sanitized source folder outside the private working tree:

```powershell
.\scripts\prepare-open-source.ps1 -OutputDir ..\agent-monitor-oss-open-source -Force
cd ..\agent-monitor-oss-open-source
```

- [ ] Push only the sanitized folder, not the private working tree.
- [ ] `open-source-manifest.json` is present and does not contain local paths,
      tokens, usernames, hostnames, device IDs, or IPs.

## Files

- [ ] `daemon/config.json` is absent.
- [ ] `daemon/node_modules/` is absent.
- [ ] `daemon/*.log` is absent.
- [ ] `daemon/.agent-monitor-audit.jsonl` is absent.
- [ ] `daemon/.workbench-sessions.json` is absent.
- [ ] `daemon/.workbench-uploads/` is absent.
- [ ] `android/local.properties` is absent.
- [ ] `android/keystore.properties` is absent.
- [ ] `android/app/build/` is absent.
- [ ] APK, AAB, keystore, `.p12`, and signing files are absent.
- [ ] Internal planning notes under `docs/superpowers/` are absent.

## Privacy Scan

- [ ] No real daemon token.
- [ ] No sub2api password or admin token.
- [ ] No personal email address.
- [ ] No real Tailscale device IP.
- [ ] No device serial number.
- [ ] No local hostname or username.
- [ ] No private absolute path.
- [ ] No uploaded user file.
- [ ] `.\scripts\scan-open-source-leaks.ps1` passes in the sanitized folder.

## Verification

```powershell
.\scripts\scan-open-source-leaks.ps1
cd .\daemon
npm test
npm --registry=https://registry.npmjs.org/ audit --omit=dev --audit-level=high
cd ..\android
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
cd ..
```

Then run a repository-wide text scan for likely leaks:

```powershell
.\scripts\scan-open-source-leaks.ps1
rg -n --hidden "token|password|secret|Authorization|Bearer|100\\.|Users\\\\|R5C" .
```

Every hit should be either documentation using placeholders, example values, or
source code that handles those concepts without containing real secrets.
Add your real local username, hostname, private source directory name, and
device serial prefixes to the scan before release.

## Release Docs

- [ ] README states this is for trusted private networks, Tailscale, LAN, or VPN.
- [ ] SECURITY.md documents remote workbench risk as remote code execution on
      your own workstation.
- [ ] docs/OPEN_SOURCE_PUBLISHING.md covers first run, QR import, Android
      signing, Windows service, systemd, Docker, Tailscale, USB, Secure Folder,
      ports, and firewall checks.
- [ ] docs/DEPENDENCIES.md has been reviewed for dependency and license notes.
- [ ] docs/KNOWN_LIMITATIONS.md matches the current implementation.
- [ ] docs/SCREENSHOT_GUIDE.md was followed for every screenshot, GIF, or video.
- [ ] CHANGELOG.md includes the release.
- [ ] .github/release-template.md is used for the release notes.
- [ ] LICENSE and dependency audit are acceptable for the intended release.

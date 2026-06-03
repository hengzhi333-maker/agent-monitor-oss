# Contributing

## Development Setup

Daemon:

```powershell
cd daemon
npm install
npm test
```

Android:

```powershell
cd android
Copy-Item .\local.properties.example .\local.properties
# Edit local.properties so sdk.dir points to your Android SDK.
.\gradlew.bat assembleDebug
```

## Pull Request Rules

- Do not commit `daemon/config.json`.
- Do not commit logs, audit logs, workbench sessions, uploads, APKs, keystores,
  or Android build output.
- Use documentation examples only, such as `100.64.0.10`,
  `C:\Users\you\Documents\AgentWorkspace`, and placeholder tokens.
- Keep remote-control changes secure by default.
- Add or update tests when behavior changes.

## Before Opening a PR

Run:

```powershell
cd daemon
npm test
npm --registry=https://registry.npmjs.org/ audit --omit=dev --audit-level=high
cd ..\android
.\gradlew.bat assembleDebug
cd ..
.\scripts\scan-open-source-leaks.ps1
```

Then run the release checklist in `docs/OPEN_SOURCE_RELEASE_CHECKLIST.md` if the
PR prepares a public release.

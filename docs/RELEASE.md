# Release Process

Use this process for public source releases and private APK/daemon handoffs.

## Public Source Release

1. Update `CHANGELOG.md`.
2. Run the full verification set:

```powershell
.\scripts\prepare-open-source.ps1 -OutputDir ..\agent-monitor-oss-open-source -Force
cd ..\agent-monitor-oss-open-source
.\scripts\scan-open-source-leaks.ps1
cd .\daemon
npm test
npm --registry=https://registry.npmjs.org/ audit --omit=dev --audit-level=high
cd ..\android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

3. Review `docs/OPEN_SOURCE_RELEASE_CHECKLIST.md`.
4. Push only the sanitized source folder.
5. Create a GitHub release using `.github/release-template.md`.

## Private Binary Release

Configure Android signing locally:

```powershell
.\scripts\create-android-keystore.ps1
```

Build a handoff folder:

```powershell
.\scripts\build-release.ps1 -AndroidVariant release
```

The release folder contains APK and daemon package hashes in
`release-manifest.json`. Do not commit the generated folder, APK, keystore, or
`android/keystore.properties`.

## Credential Rotation

Rotate the daemon token if any of these were exposed:

- `daemon/config.json`
- setup QR generated with `includeToken=1`
- Android host backup
- logs containing authorization headers
- screenshots showing tokens or QR codes

# Agent Monitor Release

## Highlights

- 

## Verification

- [ ] `scripts/prepare-open-source.ps1` generated a sanitized source folder.
- [ ] `scripts/scan-open-source-leaks.ps1` passed in the sanitized source folder.
- [ ] `daemon` tests passed.
- [ ] npm high-severity audit passed.
- [ ] Android debug build passed.
- [ ] Android release build passed with local signing, if publishing an APK.

## Security Notes

- Do not expose the daemon directly to the public internet.
- Rotate tokens if setup QR files, host backups, or private configs were shared.
- APKs, keystores, `daemon/config.json`, logs, and diagnostics are not included
  in the source release.

## Artifacts

- Source archive:
- Daemon package:
- Android APK:

## Upgrade Notes

- 

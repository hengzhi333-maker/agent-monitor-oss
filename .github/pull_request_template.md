## Summary

- Describe the change here.

## Verification

- [ ] `cd daemon && npm test`
- [ ] `cd daemon && npm --registry=https://registry.npmjs.org/ audit --omit=dev --audit-level=high`
- [ ] `.\scripts\scan-open-source-leaks.ps1`
- [ ] `cd android && .\gradlew.bat assembleDebug`

## Security And Privacy Checklist

- [ ] No real `daemon/config.json`, token, password, or admin credential is committed.
- [ ] No personal email, local username, hostname, device serial, or private path is committed.
- [ ] No APK, AAB, keystore, signing file, upload, log, audit log, or workbench state file is committed.
- [ ] New remote-control behavior is disabled or narrowly scoped by default.

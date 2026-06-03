# Dependencies and Licenses

This project uses standard Android, Kotlin, Node.js, and npm dependencies. Check
the generated lockfiles before each release because dependency versions and
transitive licenses can change.

## Daemon

Runtime dependencies:

- `ws`: WebSocket server for daemon-to-phone streaming.
- `qrcode`: setup QR generation.
- `pdf-parse`, `mammoth`, `word-extractor`, and `read-excel-file`: attachment
  text extraction for supported document types.

Security notes:

- Legacy binary `.xls` files are intentionally not supported. The project
  accepts `.xlsx` and CSV instead.
- Run `npm --registry=https://registry.npmjs.org/ audit --omit=dev
  --audit-level=high` before release.
- Do not vendor `node_modules/` into the public repository or release source
  tree.

## Android

Primary libraries:

- AndroidX Core, Lifecycle, Activity, Navigation, Camera, and Test.
- Jetpack Compose Material 3 and Compose UI.
- ML Kit barcode scanning for QR import.
- OkHttp for daemon HTTP/WebSocket access.
- Kotlinx Serialization for JSON.

Release notes:

- Release APKs must be signed with local credentials that are never committed.
- CI may generate a temporary signing key only to prove release builds compile;
  do not use CI-generated keys for user-facing releases unless you intentionally
  manage them as release secrets.

## License Review

Before tagging a public release:

```powershell
cd daemon
npm install
npm query .dependencies
```

Then review Android dependencies in `android/app/build.gradle.kts` and the npm
lockfile for license compatibility with the project license. The repository is
currently intended for an MIT-style open-source release.

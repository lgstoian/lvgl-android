# First-release checklist

Use this before tagging `v1.0.0`. Every item is verifiable with a command.

## Version

- [ ] `gradle.properties`: `lvgl.versionCode` and `lvgl.versionName` are set to the
      release version (single source of truth; both modules read them).
      ```powershell
      pwsh tools/bump-version.ps1 -Version 1.0.0 -VersionCode 1   # macOS/Linux
      powershell -File tools\bump-version.ps1 -Version 1.0.0 -VersionCode 1   # Windows
      ```
- [ ] `CHANGELOG.md`: the version section is filled in and dated.

## API

- [ ] `:lvgl-android:apiCheck` passes and `lvgl-android/api/lvgl-android.api` is
      committed. On a deliberate API change, run `:lvgl-android:apiDump` first.
- [ ] Public KDoc is complete and `docs/public-api.md` matches the code.

## Build

- [ ] `.\gradlew.bat assembleRelease` succeeds.
- [ ] `pwsh tools/size-report.ps1` reports every APK under budget.
- [ ] Per-ABI APKs, the universal APK and the AAB are produced.

## Tests

- [ ] Unit, guard and plugin tests pass:
      `.\gradlew.bat :lvgl-android:testDebugUnitTest :app:testDebugUnitTest :build-logic:test`
- [ ] Dynamic test passes and the layout lint is clean:
      `powershell -File tools\dynamic-test.ps1`
- [ ] Template end-to-end: `template/` builds and the app launches.

## Release artefacts

- [ ] Tag `v<version>` is pushed and the release workflow runs green.
- [ ] The GitHub release contains the per-ABI APKs, the AAB and `SHA256SUMS.txt`.
- [ ] Release notes describe the highlights.

## Signing secrets

- [ ] `LVGL_KEYSTORE_BASE64`, `LVGL_KEYSTORE_PASSWORD`, `LVGL_KEY_ALIAS` and
      `LVGL_KEY_PASSWORD` are set in the repository secrets.

# Release checklist — v1.1.0

Use this before tagging `v1.1.0`. Every item is verifiable with a command.

## Version

- [ ] `gradle.properties`: `lvgl.versionCode=2` and `lvgl.versionName=1.1.0`
      (single source of truth; both modules read them).
      ```powershell
      pwsh tools/bump-version.ps1 -Version 1.1.0 -VersionCode 2   # macOS/Linux
      powershell -File tools\bump-version.ps1 -Version 1.1.0 -VersionCode 2   # Windows
      ```
- [ ] `CHANGELOG.md`: the `[1.1.0] - 2026-11-24` section is filled in and dated.

## API

- [ ] `:lvgl-android:apiCheck` passes and `lvgl-android/api/lvgl-android.api` is
      committed. On a deliberate API change, run `:lvgl-android:apiDump` first.
- [ ] Public KDoc is complete and `docs/public-api.md` matches the code.

## Build

- [ ] `.\gradlew.bat assembleRelease --no-daemon` succeeds and produces the
      per-ABI and universal release APKs.
- [ ] `.\gradlew.bat bundleRelease -Plvgl.bundleNoSplits=true --no-daemon`
      succeeds. This is a separate step: `bundleRelease` fails when ABI splits
      and R8 resource shrinking are both enabled, so splits are disabled for the
      AAB. A green `assembleRelease` does not prove the bundle works.
- [ ] `.\gradlew.bat lintVitalRelease --no-daemon` passes.
- [ ] `pwsh tools/size-report.ps1` reports every APK under budget and fails if an
      expected ABI APK is missing.
- [ ] The AAB (the artifact Play receives) and all per-ABI APKs are produced.

## Tests

- [ ] Unit, guard and plugin tests pass:
      `.\gradlew.bat :lvgl-android:testDebugUnitTest :app:testDebugUnitTest :build-logic:test`
- [ ] Dynamic test passes and the layout lint is clean:
      `powershell -File tools\dynamic-test.ps1`
- [ ] Release APK smoke passes (CI and the release workflow block publishing on
      it):
      `powershell -File tools\dynamic-test.ps1 -ApkPath app\build\outputs\apk\release\app-universal-release.apk`
- [ ] Template end-to-end: `template/` builds and the app launches.

## Release artefacts

- [ ] The merged manifest inside the shipped release APK is inspected: only
      `MainActivity` is exported; the eight sample activities are
      `exported="false"` and are reached through `MainActivity`'s `SMOKE_SCREEN`
      extra.
      ```powershell
      # Replace <build-tools-version> with an installed build-tools version.
      & "$env:ANDROID_HOME\build-tools\<build-tools-version>\aapt2.exe" dump xmltree --file AndroidManifest.xml app\build\outputs\apk\release\app-universal-release.apk
      ```
- [ ] Tag `v<version>` is pushed and the release workflow runs green.
- [ ] The GitHub release contains the per-ABI APKs, the AAB and `SHA256SUMS.txt`.
- [ ] Release notes describe the highlights.

## Signing secrets

- [ ] `LVGL_KEYSTORE_BASE64`, `LVGL_KEYSTORE_PASSWORD`, `LVGL_KEY_ALIAS` and
      `LVGL_KEY_PASSWORD` are set in the repository secrets.

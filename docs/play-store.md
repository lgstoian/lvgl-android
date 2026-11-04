# Google Play readiness checklist

Maps the project to Google Play's published requirements. The machine-checkable
items are enforced by `PlayComplianceGuardTest`.

## Target API level

- **Requirement**: new apps and app updates must target Android 16 (API 36) or
  higher (from 31 August 2026).
- **Project**: `targetSdk = 36` in `app/build.gradle.kts`. ✓

## 64-bit

- **Requirement**: apps with native libraries must ship 64-bit versions.
- **Project**: `arm64-v8a` and `x86_64` ABIs. ✓

## App bundle and signing

- **Requirement**: new apps must be uploaded as an AAB, signed with an upload
  key enrolled in Play App Signing.
- **Project**: `assembleRelease` + `bundleRelease`; the release workflow signs
  with the `LVGL_KEYSTORE*` secrets. ✓

## Runtime permissions

- **Requirement**: dangerous permissions must be requested at runtime and the
  app must handle denial.
- **Project**: `CAMERA` is requested at runtime by the camera sample; the app is
  fully usable without it. ✓

## Data safety and privacy policy

- **Requirement**: declare data collection in the Play Console and publish a
  privacy policy.
- **Project**: the app collects no personal data; see [PRIVACY.md](../PRIVACY.md).
  Device facts are read locally and never leave the device. ✓

## Backup and device transfer

- **Requirement**: apps targeting Android 12+ must declare what is included in
  backup and D2D transfer.
- **Project**: `backup_rules.xml` and `data_extraction_rules.xml` opt out. ✓

## Predictive back

- **Requirement**: apps must not break the system back gesture; apps targeting
  Android 15+ are expected to support predictive back.
- **Project**: `android:enableOnBackInvokedCallback="true"`; `LvglActivity` uses
  `OnBackPressedDispatcher`. ✓

## Multi-window and large screens

- **Requirement**: apps must not crash or misbehave in multi-window and should
  support large screens.
- **Project**: `android:resizeableActivity="true"`, configuration changes are
  handled in place, and the UI scales by density. ✓

## Package visibility

- **Requirement**: apps that query other apps must declare `<queries>`.
- **Project**: the launcher sample declares the `MAIN` / `LAUNCHER` intent. ✓

## Safer intents (Android 16)

- **Requirement**: explicit intents must match the target component's intent
  filter.
- **Project**: all internal navigation uses explicit component intents. ✓

## Open-source notices

- **Requirement**: apps must disclose the licences of the open-source software
  they use.
- **Project**: the in-app **Open source licenses** screen (LVGL, AndroidX,
  Kotlin). ✓

## Quality

- No crashes or ANRs: `tools/dynamic-test.ps1` walks every screen and fails on
  `FATAL`, `SIGSEGV`, `JNI DETECTED ERROR` or `ANR`.
- No excessive permissions: only the optional `CAMERA` permission.
- No deceptive behaviour: the app does what the listing describes.

## Release checklist

1. `tools/bump-version.ps1 -Version X.Y.Z`
2. Update `CHANGELOG.md`.
3. `./gradlew assembleRelease bundleRelease` (signed with the upload key).
4. `powershell -File tools\dynamic-test.ps1`.
5. Upload the AAB to the Play Console.
6. Complete the data-safety form and link [PRIVACY.md](../PRIVACY.md).
7. Verify the device catalogue (phones, tablets, foldables).

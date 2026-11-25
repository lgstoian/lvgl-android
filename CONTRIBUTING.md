# Contributing to LVGL Android Framework

Thanks for helping improve a small, community-maintained Android framework.
This project is a **general Android UI framework**: changes that make it easier
to mix LVGL with normal Android apps, or to leave LVGL, are as welcome as
changes to the renderer itself. See [README.md](README.md) for the positioning
and honest trade-offs.

## How to contribute

1. Fork the repository and create a feature branch (`git checkout -b feature/my-feature`).
2. Make the smallest coherent change; keep one source of truth for each component.
3. Run the checks below. Unit/guard tests need no device — do **not** plug in or
   reset a physical device for documentation or unit-test work.
4. Commit in concise imperative mood (`Add …`, `Fix …`, `Document …`).
5. Push and open a Pull Request describing the problem, the change, the checks
   you ran and any user-visible behaviour. Link an issue when one exists.

Please update the docs in the same PR when behaviour or public API changes:
`README.md`, `docs/public-api.md` and the relevant guide under `docs/` are part
of the change, not a follow-up. For a deliberate public API change, run
`:lvgl-android:apiDump` and commit the refreshed dump; `apiCheck` will fail
otherwise.

## Code style

- **Kotlin**: standard Android Kotlin style; no comments unless non-obvious.
- **C/C++**: LVGL naming conventions; prefix static globals with `g_`.
- **JNI**: functions use `Java_com_lvgl_android_*` naming.
- **User-visible strings**: `strings.xml`, never hardcoded in sources.
- **Source annotations**: use the tags in
  [docs/agent-comment-schema.md](docs/agent-comment-schema.md) and link a
  breadcrumb entry for non-obvious decisions.

## Hard rules

- **No duplication**: single source of truth for each component.
- **No feature removals**: all existing features preserved and enhanced.
- All app types (activities, IME, launchers, games, camera), Material 3,
  DPI adaptation, multiwindow/foldable/PiP and submodule-based LVGL updates
  remain supported.

## CI checks to run before a PR

```powershell
.\gradlew.bat :lvgl-android:testDebugUnitTest :app:testDebugUnitTest :build-logic:test :lvgl-android:apiCheck
.\gradlew.bat lintVitalRelease --no-daemon
```

For changes that affect the shipped artifact or interop behaviour, also run:

```powershell
.\gradlew.bat assembleRelease --no-daemon
.\gradlew.bat bundleRelease -Plvgl.bundleNoSplits=true --no-daemon
.\tools\size-report.ps1
powershell -File tools\dynamic-test.ps1 -ApkPath app\build\outputs\apk\release\app-universal-release.apk
```

Play-relevant changes must keep `PlayComplianceGuardTest` green (target API,
64-bit ABIs, permission set, privacy policy, licence disclosure and exported
components); see [docs/play-store.md](docs/play-store.md). Note that Play
Console forms and the store listing are a manual step and are not covered by CI.

## Guard rules (and why they exist)

Detection logic lives in `GuardRules` (`lvgl-android/src/test/.../guards/`).
Guards are fail-closed: a missing or malformed report, APK or attribute value
must fail the gate, never read as "clean". Every detector has a canary that
must be detected (no false green), a clean fixture that must not be (no false
positive) and a wiring canary proving the detector is actually invoked. See
[BC-014 and BC-037](docs/agent-breadcrumbs.json).

| Guard | What it protects |
| --- | --- |
| `HardRuleGuardTest` | No duplication, no feature removals, strings in resources |
| `MachineCommentValidationTest` | Known tags, resolvable `BC-`/`ADR-` refs, valid `@machine-json`, no orphan breadcrumbs |
| `LayoutLintRules` + `LayoutLintGuardTest` | No overlaps, out-of-bounds children or clipped text in the layout-lint report |
| `GuardCanaryTest` | Each detector is alive and actually wired into its guard |
| `PlayComplianceGuardTest` | Play requirements, minimum exported surface, manifest attributes |
| `IntegrationGuardTest` (v1.2.0) | Features are **wired**, not merely present in source |

`IntegrationGuardTest` registers seven fail-closed wiring detectors:

- `wired-multiwindow-pip` — `LvglActivity` forwards multiwindow/PiP callbacks to `MultiWindowManager`.
- `wired-gesture-results` — `LvglInputProcessor` returns the recognizer's direct result instead of swallowing taps.
- `wired-performance-caller` — a real caller invokes `LvglPerformanceMonitor.onFrameRendered()`.
- `wired-launcher-receiver` — `LvglLauncherActivity` registers the package receiver and dispatches callbacks.
- `wired-accessibility-announcements` — the helper delegates to `LvglScreenReader`, which posts a real accessibility event.
- `wired-camera-permission` — the camera sample checks the grant before opening the device.
- `wired-ui-event-bridge` — native code emits events that reach the Kotlin listener through JNI.

If you change a detector, update its canary in `GuardCanaryTest` in the same PR.

## Interop and accessibility changes

- Embedding `LvglSurfaceView` in a plain Activity/Fragment/Compose `AndroidView`
  must keep the one-live-display rule and forward the view lifecycle. See
  [docs/interop.md](docs/interop.md).
- Native views over LVGL own their pixels and overlay flags; the camera sample
  is the reference. LVGL-thread callbacks must hop to the UI thread.
- The TalkBack node provider covers `LvglUi` app screens only. Do not claim
  platform semantics that are not implemented; see
  [docs/accessibility.md](docs/accessibility.md).

## Reporting problems

Use issues for reproducible bugs (include version, ABI, logcat tags and whether
it is a release build) and Discussions for questions; see
[SUPPORT.md](SUPPORT.md). Report vulnerabilities privately via
[SECURITY.md](SECURITY.md) — never in a public issue.

## License

By contributing you agree that your contributions are licensed under the MIT
License. See [LICENSE](LICENSE) for details.

# Support policy

> Applies to v1.2.0. This is a small, community-maintained framework — not a
> commercially supported product. Documentation and the included sample are the
> first line of support; please read them before opening an issue. Security
> reports go to [SECURITY.md](SECURITY.md), not here.

## Supported versions

| Version | Status | Bug fixes | Security fixes |
| --- | --- | --- | --- |
| **1.2.x** | Current | Best effort | Yes |
| 1.1.x | Previous | No | Critical only, best effort |
| < 1.1.0 | End of life | No | No — upgrade |

Support follows the version in `gradle.properties`. We do not backport features
to older releases; upgrading to the current version is the default advice.

## Where to ask

1. **Docs first** — [README.md](README.md),
   [docs/quickstart.md](docs/quickstart.md),
   [docs/public-api.md](docs/public-api.md),
   [docs/interop.md](docs/interop.md),
   [docs/accessibility.md](docs/accessibility.md),
   [docs/performance.md](docs/performance.md) and
   [docs/migration.md](docs/migration.md). Most integration questions are
   answered by interop or migration.
2. **GitHub Issues** — reproducible bugs, missing APIs compared with the
   public API reference, and gate failures. Use the checklist below.
3. **GitHub Discussions** — questions, integration ideas and feature
   proposals. Please do not use issues for "how do I…" questions.
4. **Pull requests** — see [CONTRIBUTING.md](CONTRIBUTING.md) for the CI and
   guard-rule expectations. Contributions are welcome and are the main reason
   the project improves.

There is no email support channel, no paid support tier and no response SLA.
Because the maintainer set is small, an issue may sit unanswered for a while;
a well-formed report with a reproduction is much more likely to be picked up.

## What to include in a bug report

- framework version (`lvgl.versionName` / tag), versionCode and source commit;
- device, Android version, ABI (`arm64-v8a` / `x86_64`) and whether it is an
  emulator;
- debug **or release** APK — release-only failures matter most;
- exact steps to reproduce, expected vs actual behaviour, and a screenshot if
  visual;
- relevant logcat tags: `LvglEngine`, `LvglActivity`, `LvglSurfaceView`,
  `LVGLPort`, `LVGLLayout`, `LvglPerfMon`, `LvglAccessibilityManager`;
- the layout-lint dump (`LvglEngine.dumpLayoutReport()`) for UI layout issues;
- the commands you ran and whether a guard/CI gate failed.

For Play publishing problems, state clearly whether the issue is in the project
(a gate, manifest or build artifact) or in your Play Console account. **Play
Console forms, the store listing, screenshots and the privacy-policy URL are a
manual user step** and cannot be diagnosed from this repository alone.

## Known limitations (not bugs to file)

- TalkBack exposes virtual nodes only for `LvglUi` app screens (ids `>= 1000`);
  built-in screens and exact LVGL geometry are not exposed. Use a native view
  when full semantics are required — see [accessibility.md](docs/accessibility.md).
- The render thread is not phase-locked to platform vsync, and frame numbers
  from the software-rendered CI emulator are not representative — see
  [performance.md](docs/performance.md).
- `armeabi-v7a` is not built: NDK r26b plus CMake 3.22.1 cannot produce it in
  this setup.
- The AAB must be built separately from per-ABI APKs
  (`bundleRelease -Plvgl.bundleNoSplits=true`).
- LVGL updates are submodule bumps; upstream behaviour is upstream's support
  responsibility.

## End of life

When a version reaches end of life, it stays available as a tag and in the
CHANGELOG, but issues against it may be closed with a pointer to the current
release. If you depend on an older version in production, plan an upgrade or
take ownership of a fork.

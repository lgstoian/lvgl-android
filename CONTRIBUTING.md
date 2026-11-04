# Contributing to LVGL Android Framework

## How to Contribute

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes (`git commit -m 'Add my feature'`)
4. Push to the branch (`git push origin feature/my-feature`)
5. Open a Pull Request

## Code Style

- **Kotlin**: Follow standard Android Kotlin style
- **C/C++**: Follow LVGL naming conventions; prefix static globals with `g_`
- **JNI**: Functions use `Java_com_lvgl_android_*` naming

## License

By contributing to this project, you agree that your contributions will be
licensed under the MIT License. See [LICENSE](LICENSE) for details.

## Hard Rules

- **No duplication**: Single source of truth for each component
- **No feature removals**: All existing features preserved and enhanced

## Checks

Run the full suite before opening a pull request:

```powershell
.\gradlew.bat :lvgl-android:testDebugUnitTest :app:testDebugUnitTest :build-logic:test :lvgl-android:apiCheck
powershell -File tools\dynamic-test.ps1
```

`PlayComplianceGuardTest` fails the build if a Google Play requirement
regresses (target API, 64-bit, permissions, privacy policy, licence
disclosure). See [docs/play-store.md](docs/play-store.md).

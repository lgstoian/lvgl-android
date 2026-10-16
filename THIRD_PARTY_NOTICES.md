# Third-Party Notices

This project depends on and distributes the following third-party software.

## LVGL — Light and Versatile Graphics Library

- **Source**: https://github.com/lvgl/lvgl
- **Version**: v9.6.0
- **License**: MIT License
- **Copyright**: Copyright (c) 2025 LVGL Kft
- **Location**: `lvgl/` submodule directory
- **License Text**: See `lvgl/LICENCE.txt`

LVGL's MIT license requires that the above copyright notice and permission
notice be included in all copies or substantial portions of the Software.
They are preserved in `lvgl/LICENCE.txt` and reproduced in full below.

### LVGL MIT License Text

```
MIT licence
Copyright (c) 2025 LVGL Kft

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is furnished
to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### LVGL Third-Party Components

LVGL itself bundles additional third-party libraries. See `lvgl/COPYRIGHTS.md` for the complete list. The following are included in this project's compiled output:

| Library | License | Location |
|---------|---------|----------|
| Unity (tests) | MIT | `lvgl/tests/unity/` |

Other LVGL third-party libraries (FreeType, FFmpeg, GStreamer, QR Code, LodePNG, LZ4, ThorVG, TinyTTF, TJpgDe, Barcode, etc.) are **not compiled** by this project's CMake configuration, which includes only core LVGL modules.

## Android SDK / NDK

- **Source**: https://developer.android.com/studio
- **License**: Apache License 2.0 (various components)
- **Not distributed** — downloaded at build time by Gradle.

## Gradle Build Tool

- **Source**: https://github.com/gradle/gradle
- **License**: Apache License 2.0
- **Not distributed** — downloaded at build time.

## JetBrains Kotlin Standard Library

- **Source**: https://github.com/JetBrains/kotlin
- **License**: Apache License 2.0
- **Not distributed** — linked at build time.

---

All trademarks are the property of their respective owners. The LVGL name
and logo are trademarks of LVGL Kft. This project is not affiliated with or
endorsed by LVGL Kft.

# Copyright (c) 2026 LVGL Android Framework Contributors
# SPDX-License-Identifier: MIT
#
# This file is part of the LVGL Android Framework.
# See LICENSE in the project root for full license information.
#
# Consumer ProGuard/R8 rules for the lvgl-android library.
#
# To activate these rules, register the file from the library module:
#   android {
#       defaultConfig {
#           consumerProguardFiles("consumer-rules.pro")
#       }
#   }
# (The framework build file is left untouched by this change.)

# --- JNI entry points -------------------------------------------------------
# The native library (lvgl_android) resolves JNI symbols by exact class and
# method name (Java_com_lvgl_android_*). Neither the classes nor their native
# methods may be renamed or stripped.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the engine bridge explicitly: apps call LvglEngine.instance, and the
# native side caches its method IDs.
-keep class com.lvgl.android.engine.LvglEngine { *; }
-keep class com.lvgl.android.engine.LvglEngine$Companion { *; }

# --- Public API classes apps subclass or reference reflectively -------------
# Activities, services and views are instantiated by Android from the manifest
# or by app code, so keep their names and members.
-keep public class com.lvgl.android.activity.LvglActivity { *; }
-keep public class com.lvgl.android.input.LvglInputMethodService { *; }
-keep public class com.lvgl.android.launcher.LvglLauncherActivity { *; }
-keep public class com.lvgl.android.game.LvglGameActivity { *; }
-keep public class com.lvgl.android.view.LvglSurfaceView { *; }
-keep public class com.lvgl.android.view.LvglSurfaceViewWithGestures { *; }

# --- Interfaces implemented by apps or by the framework's JNI bridges ------
# The native navigator bridge calls these methods by name.
-keep interface com.lvgl.android.engine.LvglEngine$ScreenNavigator { *; }
-keep interface com.lvgl.android.theme.MaterialTheme$OnThemeChangedListener { *; }
-keep interface com.lvgl.android.config.DisplayMetricsManager$OnDisplayMetricsChangedListener { *; }

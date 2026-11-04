# add project specific ProGuard rules here.
#
# The library module ships its own consumer rules (consumer-rules.pro), which
# keep the JNI entry points and the public API. The rules below cover the
# sample app's own classes.

# The native navigator bridge calls these methods by name from JNI.
-keep class * implements com.lvgl.android.engine.LvglEngine$ScreenNavigator { *; }

# Activities are instantiated from the manifest; keep their lifecycle methods.
-keep class com.lvgl.sample.** extends com.lvgl.android.activity.LvglActivity { *; }

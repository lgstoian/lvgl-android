@echo off
"D:\\ASDK\\cmake\\3.22.1-2\\bin\\cmake.exe" ^
  "-HD:\\Code\\android lvgl\\lvgl-android\\src\\main\\cpp" ^
  "-DCMAKE_SYSTEM_NAME=Android" ^
  "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON" ^
  "-DCMAKE_SYSTEM_VERSION=24" ^
  "-DANDROID_ABI=armeabi-v7a" ^
  "-DCMAKE_ANDROID_ARCH_ABI=armeabi-v7a" ^
  "-DANDROID_NDK=D:\\ASDK\\ndk\\android-ndk-r26b" ^
  "-DCMAKE_ANDROID_NDK=D:\\ASDK\\ndk\\android-ndk-r26b" ^
  "-DCMAKE_TOOLCHAIN_FILE=D:\\ASDK\\ndk\\android-ndk-r26b\\build\\cmake\\android.toolchain.cmake" ^
  "-DCMAKE_MAKE_PROGRAM=D:\\ASDK\\cmake\\3.22.1-2\\bin\\ninja.exe" ^
  "-DCMAKE_CXX_FLAGS=-std=c++17" ^
  "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=D:\\Code\\android lvgl\\lvgl-android\\build\\intermediates\\cxx\\Debug\\3561721v\\obj\\armeabi-v7a" ^
  "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=D:\\Code\\android lvgl\\lvgl-android\\build\\intermediates\\cxx\\Debug\\3561721v\\obj\\armeabi-v7a" ^
  "-DCMAKE_BUILD_TYPE=Debug" ^
  "-BD:\\Code\\android lvgl\\lvgl-android\\.cxx\\Debug\\3561721v\\armeabi-v7a" ^
  -GNinja ^
  "-DANDROID_STL=c++_shared" ^
  "-DANDROID_PLATFORM=android-24"

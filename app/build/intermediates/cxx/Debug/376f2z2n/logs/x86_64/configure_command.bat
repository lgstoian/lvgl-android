@echo off
"D:\\ASDK\\cmake\\3.22.1\\bin\\cmake.exe" ^
  "-HD:\\Code\\android lvgl\\app\\src\\main\\cpp" ^
  "-DCMAKE_SYSTEM_NAME=Android" ^
  "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON" ^
  "-DCMAKE_SYSTEM_VERSION=24" ^
  "-DANDROID_ABI=x86_64" ^
  "-DCMAKE_ANDROID_ARCH_ABI=x86_64" ^
  "-DANDROID_NDK=D:\\ASDK\\ndk\\android-ndk-r26b" ^
  "-DCMAKE_ANDROID_NDK=D:\\ASDK\\ndk\\android-ndk-r26b" ^
  "-DCMAKE_TOOLCHAIN_FILE=D:\\ASDK\\ndk\\android-ndk-r26b\\build\\cmake\\android.toolchain.cmake" ^
  "-DCMAKE_MAKE_PROGRAM=D:\\ASDK\\cmake\\3.22.1\\bin\\ninja.exe" ^
  "-DCMAKE_CXX_FLAGS=-std=c++17" ^
  "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=D:\\Code\\android lvgl\\app\\build\\intermediates\\cxx\\Debug\\376f2z2n\\obj\\x86_64" ^
  "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=D:\\Code\\android lvgl\\app\\build\\intermediates\\cxx\\Debug\\376f2z2n\\obj\\x86_64" ^
  "-DCMAKE_BUILD_TYPE=Debug" ^
  "-BD:\\Code\\android lvgl\\app\\.cxx\\Debug\\376f2z2n\\x86_64" ^
  -GNinja ^
  "-DANDROID_STL=c++_shared" ^
  "-DANDROID_PLATFORM=android-24"

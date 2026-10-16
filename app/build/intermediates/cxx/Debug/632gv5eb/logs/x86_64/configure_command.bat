@echo off
"D:\\Code\\key3board\\tools\\android-sdk\\cmake\\3.22.1\\bin\\cmake.exe" ^
  "-HD:\\Code\\android lvgl\\app\\src\\main\\cpp" ^
  "-DCMAKE_SYSTEM_NAME=Android" ^
  "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON" ^
  "-DCMAKE_SYSTEM_VERSION=24" ^
  "-DANDROID_ABI=x86_64" ^
  "-DCMAKE_ANDROID_ARCH_ABI=x86_64" ^
  "-DANDROID_NDK=D:\\Code\\key3board\\tools\\android-sdk\\ndk\\28.2.13676358" ^
  "-DCMAKE_ANDROID_NDK=D:\\Code\\key3board\\tools\\android-sdk\\ndk\\28.2.13676358" ^
  "-DCMAKE_TOOLCHAIN_FILE=D:\\Code\\key3board\\tools\\android-sdk\\ndk\\28.2.13676358\\build\\cmake\\android.toolchain.cmake" ^
  "-DCMAKE_MAKE_PROGRAM=D:\\Code\\key3board\\tools\\android-sdk\\cmake\\3.22.1\\bin\\ninja.exe" ^
  "-DCMAKE_CXX_FLAGS=-std=c++17" ^
  "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=D:\\Code\\android lvgl\\app\\build\\intermediates\\cxx\\Debug\\632gv5eb\\obj\\x86_64" ^
  "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=D:\\Code\\android lvgl\\app\\build\\intermediates\\cxx\\Debug\\632gv5eb\\obj\\x86_64" ^
  "-DCMAKE_BUILD_TYPE=Debug" ^
  "-BD:\\Code\\android lvgl\\app\\.cxx\\Debug\\632gv5eb\\x86_64" ^
  -GNinja ^
  "-DANDROID_STL=c++_shared" ^
  "-DANDROID_PLATFORM=android-24"

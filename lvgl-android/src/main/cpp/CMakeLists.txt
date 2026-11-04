cmake_minimum_required(VERSION 3.22.1)
project("lvgl_android")

set(LVGL_ROOT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../lvgl)

file(GLOB_RECURSE LVGL_SOURCES CONFIGURE_DEPENDS
    ${LVGL_ROOT_DIR}/src/core/*.c
    ${LVGL_ROOT_DIR}/src/draw/*.c
    ${LVGL_ROOT_DIR}/src/font/*.c
    ${LVGL_ROOT_DIR}/src/indev/*.c
    ${LVGL_ROOT_DIR}/src/layouts/*.c
    ${LVGL_ROOT_DIR}/src/misc/*.c
    ${LVGL_ROOT_DIR}/src/osal/*.c
    ${LVGL_ROOT_DIR}/src/stdlib/*.c
    ${LVGL_ROOT_DIR}/src/themes/*.c
    ${LVGL_ROOT_DIR}/src/tick/*.c
    ${LVGL_ROOT_DIR}/src/widgets/*.c
    ${LVGL_ROOT_DIR}/src/image/*.c
    ${LVGL_ROOT_DIR}/src/*.c
)

file(GLOB APP_SOURCES CONFIGURE_DEPENDS
    ${CMAKE_CURRENT_SOURCE_DIR}/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/*.c
)

add_library(lvgl_android SHARED
    ${LVGL_SOURCES}
    ${APP_SOURCES}
)

target_include_directories(lvgl_android PRIVATE
    ${LVGL_ROOT_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_definitions(lvgl_android PRIVATE
    LV_CONF_INCLUDE_SIMPLE
    LV_KCONFIG_IGNORE
    LV_ASSERT_CUSTOM_INCLUDE="lv_assert_handler.h"
)

find_library(log-lib log)
find_library(android-lib android)

# NDK r26b + CMake 3.22.1 cannot resolve libatomic for x86_64 automatically
# (see ai-context.md). Link it explicitly for x86_64 only.
if(ANDROID_ABI STREQUAL "x86_64")
    target_link_options(lvgl_android PRIVATE -latomic)
endif()

target_link_libraries(lvgl_android
    ${log-lib}
    ${android-lib}
)

target_compile_options(lvgl_android PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-missing-field-initializers
)


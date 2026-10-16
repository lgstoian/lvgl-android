cmake_minimum_required(VERSION 3.22.1)
project("lvgl_app")

set(LVGL_ROOT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../lvgl)

file(GLOB_RECURSE LVGL_SOURCES
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

file(GLOB APP_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/*.cpp
)

add_library(lvgl_app SHARED
    ${LVGL_SOURCES}
    ${APP_SOURCES}
)

target_include_directories(lvgl_app PRIVATE
    ${LVGL_ROOT_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_definitions(lvgl_app PRIVATE
    LV_CONF_INCLUDE_SIMPLE
    LV_KCONFIG_IGNORE
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(lvgl_app
    ${log-lib}
    ${android-lib}
)

target_compile_options(lvgl_app PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-missing-field-initializers
)

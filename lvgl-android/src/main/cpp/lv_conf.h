#ifndef LV_CONF_H
#define LV_CONF_H

#ifdef __cplusplus
extern "C" {
#endif

#define LV_COLOR_DEPTH 32

#define LV_USE_STDLIB_MALLOC    LV_STDLIB_CLIB
#define LV_USE_STDLIB_STRING    LV_STDLIB_CLIB
#define LV_USE_STDLIB_SPRINTF   LV_STDLIB_CLIB

#define LV_STDINT_INCLUDE       <stdint.h>
#define LV_STDDEF_INCLUDE       <stddef.h>
#define LV_STDBOOL_INCLUDE      <stdbool.h>
#define LV_INTTYPES_INCLUDE     <inttypes.h>
#define LV_LIMITS_INCLUDE       <limits.h>
#define LV_STDARG_INCLUDE       <stdarg.h>

#if LV_USE_STDLIB_MALLOC == LV_STDLIB_CLIB
    #define LV_MEM_SIZE 0
#endif

#define LV_DEF_REFR_PERIOD  16

#define LV_DPI_DEF 480

#define LV_USE_OS   LV_OS_NONE

#define LV_USE_FS_IF 0

#define LV_USE_DRAW_SW     1
#define LV_USE_DRAW_VG_LITE 0

#define LV_FONT_MONTSERRAT_14 1
#define LV_FONT_MONTSERRAT_20 1
#define LV_FONT_MONTSERRAT_24 1
#define LV_FONT_MONTSERRAT_28 1
#define LV_FONT_DEFAULT &lv_font_montserrat_14

#define LV_USE_THEME_DEFAULT 1
#define LV_THEME_DEFAULT_GROW 1

#define LV_USE_LABEL       1
#define LV_USE_BUTTON      1
#define LV_USE_SLIDER      1
#define LV_USE_SWITCH      1
#define LV_USE_ARC         1
#define LV_USE_BAR         1
#define LV_USE_CHECKBOX    1
#define LV_USE_DROPDOWN    1
#define LV_USE_ROLLER      1
#define LV_USE_TEXTAREA    1
#define LV_USE_TABLE       1
#define LV_USE_WIN         1
#define LV_USE_CANVAS      1
#define LV_USE_IMAGE       1
#define LV_USE_IMAGEBUTTON 1
#define LV_USE_KEYBOARD    1
#define LV_USE_LED         1
#define LV_USE_LIST        1
#define LV_USE_MENU        1
#define LV_USE_MSGBOX      1
#define LV_USE_SPAN        1
#define LV_USE_SPINBOX     1
#define LV_USE_SPINNER     1
#define LV_USE_TABVIEW     1
#define LV_USE_TILEVIEW    1
#define LV_USE_ANIMIMG     1
#define LV_USE_CALENDAR    1
#define LV_USE_CHART       1
#define LV_USE_SCALE       1
#define LV_USE_LINE        1
#define LV_USE_OBSERVABLE  1

#define LV_USE_FLEX        1
#define LV_USE_GRID        1

#define LV_USE_DRAW_ARM2D_SYNC  0
#define LV_USE_DRAW_SDL         0
#define LV_USE_DRAW_VG_LITE     0
#define LV_USE_DRAW_OPENGLES    0

#define LV_USE_LOG      1
#if LV_USE_LOG
    #define LV_LOG_LEVEL    LV_LOG_LEVEL_WARN
    #define LV_LOG_PRINTF   1
#endif

#define LV_USE_PERF_MONITOR     0
#define LV_USE_MEM_MONITOR      0

#define LV_USE_SNAPSHOT         0

#define LV_USE_PROFILER         0

#define LV_BUILD_EXPORT_SHARED  0

#ifdef __cplusplus
}
#endif

#endif /* LV_CONF_H */

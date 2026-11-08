cmake_minimum_required(VERSION 3.22.1)
project(remrss_mod_runtime C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(QJS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vendor/quickjs-2026-06-04")
add_library(quickjs STATIC
    "${QJS_DIR}/quickjs.c"
    "${QJS_DIR}/dtoa.c"
    "${QJS_DIR}/libregexp.c"
    "${QJS_DIR}/libunicode.c"
    "${QJS_DIR}/cutils.c")
target_include_directories(quickjs PUBLIC "${QJS_DIR}")
target_compile_definitions(quickjs PRIVATE _GNU_SOURCE CONFIG_VERSION=\"2026-06-04\")
target_compile_options(quickjs PRIVATE -fvisibility=hidden -fno-strict-aliasing)

add_library(remrss_mod_runtime SHARED runtime_bridge.c)
if(ANDROID)
    target_link_libraries(remrss_mod_runtime PRIVATE quickjs m)
else()
    find_package(JNI REQUIRED)
    target_include_directories(remrss_mod_runtime PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(remrss_mod_runtime PRIVATE quickjs m)
endif()
target_compile_options(remrss_mod_runtime PRIVATE -Wall -Wextra -Werror -Wno-unused-parameter -fvisibility=hidden)
if(ANDROID)
    target_link_options(remrss_mod_runtime PRIVATE -Wl,-z,max-page-size=16384)
endif()

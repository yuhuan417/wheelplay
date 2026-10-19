cmake_minimum_required(VERSION 3.22)
project(wheelplay_video LANGUAGES C CXX)
include(FetchContent)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_SKIP_INSTALL_RULES ON)
add_compile_options(-O2)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(NO_TESTS ON CACHE BOOL "" FORCE)
set(ENABLE_WARNINGS_AS_ERRORS OFF CACHE BOOL "" FORCE)
set(FETCHCONTENT_UPDATES_DISCONNECTED ON)
# AirPlay video frames are ChaCha20-Poly1305 encrypted; MbedTLS is built into the APK.
FetchContent_Declare(mbedtls
    GIT_REPOSITORY https://github.com/Mbed-TLS/mbedtls.git
    GIT_TAG e185d7fd85499c8ce5ca2a54f5cf8fe7dbe3f8df
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(mbedtls)
add_library(wheelplay-video SHARED video_crypto.cpp)
target_link_libraries(wheelplay-video PRIVATE mbedcrypto log)
target_link_options(wheelplay-video PRIVATE "-Wl,-z,max-page-size=16384")

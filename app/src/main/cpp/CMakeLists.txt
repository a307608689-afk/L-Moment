cmake_minimum_required(VERSION 3.22)
project(rawdecoder CXX)

# ---- LibRaw 静态库 ----
file(GLOB_RECURSE LIBRAW_SRC ${CMAKE_SOURCE_DIR}/libraw/src/*.cpp)
add_library(libraw STATIC ${LIBRAW_SRC})
target_include_directories(libraw PUBLIC ${CMAKE_SOURCE_DIR}/libraw)
target_compile_definitions(libraw PRIVATE
    LIBRAW_NODLL
    LIBRAW_NO_OPENMP
    LIBRAW_NOTHREADS
)
target_compile_options(libraw PRIVATE -O2 -fPIC)

# ---- JNI 封装共享库 ----
add_library(rawdecoder SHARED RawDecoder.cpp)
target_link_libraries(rawdecoder libraw log)
target_include_directories(rawdecoder PRIVATE ${CMAKE_SOURCE_DIR}/libraw)

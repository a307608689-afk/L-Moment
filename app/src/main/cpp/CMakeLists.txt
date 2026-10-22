cmake_minimum_required(VERSION 3.22)
project(rawdecoder C CXX)

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

# ---- libjpeg-turbo static library (C1 band-wise streaming encoder) ----
# Upstream refuses add_subdirectory(), so the 8-bit baseline core is compiled
# directly; jconfig.h / jconfigint.h / jversion.h are provided by ljt-config/.
set(LJT ${CMAKE_SOURCE_DIR}/libjpeg-turbo)

# libjpeg-turbo 3.x supports run-time data precision selection: the 8-bit build
# still references j12init_* / j16init_* symbols, which upstream produces by
# recompiling the same files with -DBITS_IN_JSAMPLE=12/16 (the headers rename
# the entry points per precision).
set(LJT_COMMON_SOURCES
    ${LJT}/jcapistd.c ${LJT}/jccolor.c ${LJT}/jcdiffct.c ${LJT}/jclossls.c
    ${LJT}/jcmainct.c ${LJT}/jcprepct.c ${LJT}/jcsample.c ${LJT}/jdapistd.c
    ${LJT}/jdcolor.c ${LJT}/jddiffct.c ${LJT}/jdlossls.c ${LJT}/jdmainct.c
    ${LJT}/jdpostct.c ${LJT}/jdsample.c ${LJT}/jutils.c
)
set(LJT_12_ONLY_SOURCES
    ${LJT}/jccoefct.c ${LJT}/jcdctmgr.c ${LJT}/jdcoefct.c ${LJT}/jddctmgr.c
    ${LJT}/jdmerge.c ${LJT}/jfdctfst.c ${LJT}/jfdctint.c ${LJT}/jidctflt.c
    ${LJT}/jidctfst.c ${LJT}/jidctint.c ${LJT}/jidctred.c ${LJT}/jquant1.c
    ${LJT}/jquant2.c
)

set(LJT_SOURCES
    # shared 8/12/16-bit sources
    ${LJT}/jcapistd.c ${LJT}/jccolor.c ${LJT}/jcdiffct.c ${LJT}/jclossls.c
    ${LJT}/jcmainct.c ${LJT}/jcprepct.c ${LJT}/jcsample.c ${LJT}/jdapistd.c
    ${LJT}/jdcolor.c ${LJT}/jddiffct.c ${LJT}/jdlossls.c ${LJT}/jdmainct.c
    ${LJT}/jdpostct.c ${LJT}/jdsample.c ${LJT}/jutils.c
    # 12-bit-only sources (also used by the 8-bit build)
    ${LJT}/jccoefct.c ${LJT}/jcdctmgr.c ${LJT}/jdcoefct.c ${LJT}/jddctmgr.c
    ${LJT}/jdmerge.c ${LJT}/jfdctfst.c ${LJT}/jfdctint.c ${LJT}/jidctflt.c
    ${LJT}/jidctfst.c ${LJT}/jidctint.c ${LJT}/jidctred.c ${LJT}/jquant1.c
    ${LJT}/jquant2.c
    # 8-bit-only sources
    ${LJT}/jcapimin.c ${LJT}/jchuff.c ${LJT}/jcicc.c ${LJT}/jcinit.c
    ${LJT}/jclhuff.c ${LJT}/jcmarker.c ${LJT}/jcmaster.c ${LJT}/jcomapi.c
    ${LJT}/jcparam.c ${LJT}/jcphuff.c ${LJT}/jctrans.c ${LJT}/jdapimin.c
    ${LJT}/jdatadst.c ${LJT}/jdatasrc.c ${LJT}/jdhuff.c ${LJT}/jdicc.c
    ${LJT}/jdinput.c ${LJT}/jdlhuff.c ${LJT}/jdmarker.c ${LJT}/jdmaster.c
    ${LJT}/jdphuff.c ${LJT}/jdtrans.c ${LJT}/jerror.c ${LJT}/jfdctflt.c
    ${LJT}/jmemmgr.c ${LJT}/jmemnobs.c ${LJT}/jpeg_nbits.c
)
add_library(jpeg12objs OBJECT ${LJT_COMMON_SOURCES} ${LJT_12_ONLY_SOURCES})
target_include_directories(jpeg12objs PRIVATE ${LJT} ${CMAKE_SOURCE_DIR}/ljt-config)
target_compile_options(jpeg12objs PRIVATE -O2 -fPIC -fvisibility=hidden -w
    -DBITS_IN_JSAMPLE=12)

add_library(jpeg16objs OBJECT ${LJT_COMMON_SOURCES})
target_include_directories(jpeg16objs PRIVATE ${LJT} ${CMAKE_SOURCE_DIR}/ljt-config)
target_compile_options(jpeg16objs PRIVATE -O2 -fPIC -fvisibility=hidden -w
    -DBITS_IN_JSAMPLE=16)

add_library(jpegturbo STATIC ${LJT_SOURCES}
    $<TARGET_OBJECTS:jpeg12objs> $<TARGET_OBJECTS:jpeg16objs>)
target_include_directories(jpegturbo PUBLIC ${LJT} ${CMAKE_SOURCE_DIR}/ljt-config)
target_compile_options(jpegturbo PRIVATE -O3 -fPIC -fvisibility=hidden -w)

# ---- JNI 封装共享库 ----
add_library(rawdecoder SHARED RawDecoder.cpp JpegStream.cpp ModelColor.cpp)
target_link_libraries(rawdecoder libraw jpegturbo log jnigraphics)
# ModelColor.cpp uses std::clamp / std::thread; the NDK defaults to C++14.
set_target_properties(rawdecoder PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_include_directories(rawdecoder PRIVATE ${CMAKE_SOURCE_DIR}/libraw)

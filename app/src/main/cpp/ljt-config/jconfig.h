/* Hand-written jconfig.h for the Android build of libjpeg-turbo 3.0.4.
 * The upstream build system refuses add_subdirectory(), so the library is
 * compiled directly from source and this file replaces configure_file().
 * 8-bit samples, baseline Huffman only (no arithmetic coding), no SIMD. */
#ifndef JCONFIG_INCLUDED
#define JCONFIG_INCLUDED

#define JPEG_LIB_VERSION  62

#define LIBJPEG_TURBO_VERSION  3.0.4
#define LIBJPEG_TURBO_VERSION_NUMBER  3004

#define MEM_SRCDST_SUPPORTED  1

#ifndef BITS_IN_JSAMPLE
#define BITS_IN_JSAMPLE  8
#endif

#ifdef _WIN32
#undef RIGHT_SHIFT_IS_UNSIGNED
#ifndef __RPCNDR_H__
typedef unsigned char boolean;
#endif
#define HAVE_BOOLEAN
#if !(defined(_BASETSD_H_) || defined(_BASETSD_H))
typedef short INT16;
typedef signed int INT32;
#endif
#define XMD_H
#else
/* Android clang shifts signed values arithmetically: nothing to define. */
#endif

#endif

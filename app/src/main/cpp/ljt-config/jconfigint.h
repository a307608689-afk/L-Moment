/* Hand-written jconfigint.h for the Android build of libjpeg-turbo 3.0.4. */
#ifndef JCONFIGINT_INCLUDED
#define JCONFIGINT_INCLUDED

#define BUILD  "20261019"
#define HIDDEN  __attribute__((visibility("hidden")))

#undef inline
#define INLINE  inline __attribute__((always_inline))
#define THREAD_LOCAL  __thread

#define PACKAGE_NAME  "libjpeg-turbo"
#define VERSION  "3.0.4"

#define SIZEOF_SIZE_T  8
#define HAVE_BUILTIN_CTZL 1

#if defined(__has_attribute)
#if __has_attribute(fallthrough)
#define FALLTHROUGH  __attribute__((fallthrough));
#else
#define FALLTHROUGH
#endif
#else
#define FALLTHROUGH
#endif

#ifndef BITS_IN_JSAMPLE
#define BITS_IN_JSAMPLE  8
#endif

#undef C_ARITH_CODING_SUPPORTED
#undef D_ARITH_CODING_SUPPORTED
#undef WITH_SIMD

#endif

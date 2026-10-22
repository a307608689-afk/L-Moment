/*
 * JpegStream.cpp - band-wise streaming baseline JPEG writer (C1).
 *
 * Rationale: a 200 MP photo cannot be held as one ARGB Bitmap and encoded in a
 * single pass on a phone.  Each band (a multiple of MCU rows) is encoded by an
 * independent libjpeg instance; the resulting entropy-coded segments are
 * concatenated into one JPEG:
 *
 *   [SOI..SOS of band 0, SOF height patched, DRI inserted] [entropy 0]
 *   [RST0] [entropy 1] [RST1] [entropy 2] ...            [EOI]
 *
 * Every band is exactly one DRI interval, so no band emits internal RST
 * markers, and the restart index continues globally.  A decoder resets its DC
 * predictor at each RST, which matches the independently encoded bands.  With
 * 4:4:4 sampling and the standard Huffman tables this is bit-exact against a
 * one-shot encode of the same pixels (verified on PC: MAE = 0 with both libjpeg
 * and OpenCV decoders, up to 12288x16320 / 51 bands).
 *
 * All bands use identical quantization and Huffman tables (default quality
 * scaling + optimize_coding = FALSE), which is what makes header reuse legal.
 */

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <new>
#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "jpeglib.h"

#define LOG_TAG "JpegStream"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

struct LjtErrorMgr {
  struct jpeg_error_mgr pub;
  jmp_buf setjmp_buffer;
};

static void ljt_error_exit(j_common_ptr cinfo) {
  LjtErrorMgr *err = (LjtErrorMgr *)cinfo->err;
  char msg[JMSG_LENGTH_MAX];
  (*cinfo->err->format_message)(cinfo, msg);
  LOGE("libjpeg fatal: %s", msg);
  longjmp(err->setjmp_buffer, 1);
}

static void ljt_emit_message(j_common_ptr cinfo, int msg_level) {
  if (msg_level < 0) {
    char msg[JMSG_LENGTH_MAX];
    (*cinfo->err->format_message)(cinfo, msg);
    LOGI("libjpeg warning: %s", msg);
  }
}

struct StripEncoder {
  FILE *fp;
  int width;            /* pixels */
  int height;           /* pixels */
  int quality;
  int mcuCols;          /* MCUs per row (4:4:4 -> width/8) */
  int mcuRows;          /* MCUs per column */
  int bandMcus;         /* MCU rows per band == DRI interval rows */
  int bandPx;           /* band height in pixels */
  int bandIndex;
  int rstIndex;
};

/* ------------------------------------------------------------------------- */

static int append(FILE *fp, const unsigned char *p, size_t n) {
  if (n == 0) return 0;
  if (fwrite(p, 1, n, fp) != n) return -1;
  return 0;
}

/*
 * Parses a complete JPEG produced by libjpeg and locates:
 *   sosStart     - offset of the 0xFFDA marker
 *   sosDataStart - offset of the first entropy byte
 *   sofHeightOff - offset of the 16-bit SOF height field
 */
static int locateSections(const unsigned char *d, size_t n, size_t *sosStart,
                          size_t *sosDataStart, size_t *sofHeightOff) {
  size_t i = 2;
  *sofHeightOff = 0;
  while (i + 3 < n) {
    if (d[i] != 0xFF) return -1;
    unsigned m = d[i + 1];
    if (m == 0xFF) { i++; continue; }
    if (m == 0xDA) {
      size_t len = ((size_t)d[i + 2] << 8) | d[i + 3];
      *sosStart = i;
      *sosDataStart = i + 2 + len;
      return (*sofHeightOff && *sosDataStart <= n) ? 0 : -1;
    }
    if (m >= 0xD0 && m <= 0xD9) return -1;
    size_t len = ((size_t)d[i + 2] << 8) | d[i + 3];
    if (len < 2) return -1;
    if (m == 0xC0 || m == 0xC1 || m == 0xC2) {
      if (i + 8 >= n) return -1;
      *sofHeightOff = i + 5;
    }
    i += 2 + len;
  }
  return -1;
}

/* Encodes one band of RGBA pixels into an in-memory baseline JPEG. */
static int encodeBand(StripEncoder *enc, const unsigned char *rgba, int rows,
                      unsigned char **outBuf, unsigned long *outSize) {
  struct jpeg_compress_struct cinfo;
  LjtErrorMgr jerr;
  *outBuf = NULL;
  *outSize = 0;

  cinfo.err = jpeg_std_error(&jerr.pub);
  jerr.pub.error_exit = ljt_error_exit;
  jerr.pub.emit_message = ljt_emit_message;
  if (setjmp(jerr.setjmp_buffer)) {
    jpeg_destroy_compress(&cinfo);
    if (*outBuf) {
      free(*outBuf);
      *outBuf = NULL;
    }
    return -1;
  }

  jpeg_create_compress(&cinfo);
  jpeg_mem_dest(&cinfo, outBuf, outSize);

  cinfo.image_width = (JDIMENSION)enc->width;
  cinfo.image_height = (JDIMENSION)rows;
  cinfo.input_components = 4;
  cinfo.in_color_space = JCS_EXT_RGBA;

  jpeg_set_defaults(&cinfo);
  jpeg_set_quality(&cinfo, enc->quality, TRUE);

  /* Force 4:4:4: no chroma subsampling, so every 8x8 block is intra-MCU and
   * band boundaries cannot cut a subsampled chroma block. */
  cinfo.comp_info[0].h_samp_factor = 1;
  cinfo.comp_info[0].v_samp_factor = 1;
  cinfo.optimize_coding = FALSE;   /* standard Huffman tables -> reusable header */
  cinfo.dct_method = JDCT_ISLOW;

  jpeg_start_compress(&cinfo, TRUE);
  size_t stride = (size_t)enc->width * 4;
  while (cinfo.next_scanline < cinfo.image_height) {
    JSAMPROW row = (JSAMPROW)(rgba + (size_t)cinfo.next_scanline * stride);
    jpeg_write_scanlines(&cinfo, &row, 1);
  }
  jpeg_finish_compress(&cinfo);
  jpeg_destroy_compress(&cinfo);
  return 0;
}

/* Reads at most `n` bytes of file tail to find the final EOI. */
static size_t findEoi(const unsigned char *d, size_t n) {
  for (size_t i = n - 1; i >= 1; i--) {
    if (d[i] == 0xD9 && d[i - 1] == 0xFF) return i - 1;
    if (i == 1) break;
  }
  return (size_t)-1;
}

/* ------------------------------------------------------------------------- */

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_leicamoments_app_JpegStream_nativeOpen(JNIEnv *env, jclass, jstring jpath,
                                                jint width, jint height,
                                                jint quality, jint maxBandRows) {
  const char *path = env->GetStringUTFChars(jpath, NULL);
  if (!path) return 0;

  StripEncoder *enc = new (std::nothrow) StripEncoder();
  if (!enc) {
    env->ReleaseStringUTFChars(jpath, path);
    return 0;
  }
  enc->fp = fopen(path, "wb");
  env->ReleaseStringUTFChars(jpath, path);
  if (!enc->fp) {
    LOGE("cannot open output file");
    delete enc;
    return 0;
  }

  enc->width = width;
  enc->height = height;
  enc->quality = quality;
  enc->mcuCols = (width + 7) / 8;
  enc->mcuRows = (height + 7) / 8;
  enc->bandIndex = 0;
  enc->rstIndex = 0;

  /* DRI carries a 16-bit MCU count, so one band cannot exceed 65535 MCUs. */
  int maxK = 65535 / enc->mcuCols;
  if (maxK < 1) {
    LOGE("image too wide for a 16-bit restart interval: %d px", width);
    fclose(enc->fp);
    delete enc;
    return 0;
  }
  /* Explicit request wins: honours the caller exactly (minus the DRI clamp),
     so a caller that wants a specific band height can line it up with its own
     tile grid.  Only the final band ends up ragged, which is legal. */
  int bandMcus = 0;
  if (maxBandRows > 0) {
    bandMcus = maxBandRows < maxK ? maxBandRows : maxK;
    if (bandMcus > enc->mcuRows) bandMcus = enc->mcuRows;
  } else {
    if (maxK > enc->mcuRows) maxK = enc->mcuRows;
    for (int k = maxK; k >= 1; k--) {
      if (enc->mcuRows % k == 0) {
        bandMcus = k;
        break;
      }
    }
    if (bandMcus == 0) bandMcus = maxK; /* ragged last band allowed */
  }

  enc->bandMcus = bandMcus;
  enc->bandPx = bandMcus * 8;
  LOGI("open %dx%d q=%d bandMcus=%d bandPx=%d bands=%d", width, height, quality,
       bandMcus, enc->bandPx, (height + enc->bandPx - 1) / enc->bandPx);
  return (jlong)(intptr_t)enc;
}

JNIEXPORT jint JNICALL
Java_com_leicamoments_app_JpegStream_nativeBandRows(JNIEnv *, jclass, jlong handle) {
  StripEncoder *enc = (StripEncoder *)(intptr_t)handle;
  return enc ? enc->bandPx : 0;
}

/* Shared band body: encode one band of pixels and append it to the stream. */
static int writeBandPixels(StripEncoder *enc, const unsigned char *pixels, int rows) {
  unsigned char *jpegBuf = NULL;
  unsigned long jpegLen = 0;
  int rc = encodeBand(enc, pixels, rows, &jpegBuf, &jpegLen);
  if (rc != 0 || !jpegBuf) return -2;

  size_t sosStart = 0, sosData = 0, sofOff = 0;
  if (locateSections(jpegBuf, jpegLen, &sosStart, &sosData, &sofOff) != 0) {
    LOGE("band %d: cannot parse encoder output", enc->bandIndex);
    free(jpegBuf);
    return -3;
  }
  size_t eoi = findEoi(jpegBuf, jpegLen);
  if (eoi == (size_t)-1 || eoi < sosData) {
    LOGE("band %d: no EOI", enc->bandIndex);
    free(jpegBuf);
    return -3;
  }

  rc = 0;
  if (enc->bandIndex == 0) {
    /* Patch SOF height to the full image height (band was encoded short). */
    jpegBuf[sofOff] = (unsigned char)((enc->height >> 8) & 0xFF);
    jpegBuf[sofOff + 1] = (unsigned char)(enc->height & 0xFF);
    /* Header up to SOS, then a DRI segment, then the SOS segment itself. */
    if (append(enc->fp, jpegBuf, sosStart) != 0) rc = -4;
    if (rc == 0) {
      int interval = enc->bandMcus * enc->mcuCols;
      unsigned char dri[6] = {0xFF, 0xDD, 0x00, 0x04,
                              (unsigned char)((interval >> 8) & 0xFF),
                              (unsigned char)(interval & 0xFF)};
      if (append(enc->fp, dri, 6) != 0) rc = -4;
    }
    if (rc == 0 && append(enc->fp, jpegBuf + sosStart, sosData - sosStart) != 0) rc = -4;
    if (rc == 0 && append(enc->fp, jpegBuf + sosData, eoi - sosData) != 0) rc = -4;
  } else {
    unsigned char rst[2] = {0xFF, (unsigned char)(0xD0 + (enc->rstIndex & 7))};
    if (append(enc->fp, rst, 2) != 0) rc = -4;
    if (rc == 0 && append(enc->fp, jpegBuf + sosData, eoi - sosData) != 0) rc = -4;
    enc->rstIndex++;
  }

  free(jpegBuf);
  if (rc == 0) enc->bandIndex++;
  return rc;
}

JNIEXPORT jint JNICALL
Java_com_leicamoments_app_JpegStream_nativeWriteBand(JNIEnv *env, jclass,
                                                     jlong handle, jbyteArray jrgba,
                                                     jint rows) {
  StripEncoder *enc = (StripEncoder *)(intptr_t)handle;
  if (!enc || !enc->fp || rows <= 0) return -1;

  jsize need = rows * enc->width * 4;
  if (env->GetArrayLength(jrgba) < need) {
    LOGE("band buffer too small: have %d need %d", env->GetArrayLength(jrgba), need);
    return -1;
  }
  jbyte *pixels = env->GetByteArrayElements(jrgba, NULL);
  if (!pixels) return -1;
  int rc = writeBandPixels(enc, (const unsigned char *)pixels, rows);
  env->ReleaseByteArrayElements(jrgba, pixels, JNI_ABORT);
  return rc;
}

/* Zero-copy variant: Kotlin hands us a direct ByteBuffer (native heap). */
JNIEXPORT jint JNICALL
Java_com_leicamoments_app_JpegStream_nativeWriteBandDirect(JNIEnv *env, jclass,
                                                           jlong handle, jobject jbuf,
                                                           jint rows) {
  StripEncoder *enc = (StripEncoder *)(intptr_t)handle;
  if (!enc || !enc->fp || rows <= 0) return -1;
  void *pixels = env->GetDirectBufferAddress(jbuf);
  jlong cap = env->GetDirectBufferCapacity(jbuf);
  jlong need = (jlong)rows * enc->width * 4;
  if (!pixels || cap < need) {
    LOGE("direct band buffer invalid: cap=%lld need=%lld", (long long)cap, (long long)need);
    return -1;
  }
  return writeBandPixels(enc, (const unsigned char *)pixels, rows);
}

/*
 * P3: one-shot in-memory encode of a whole Bitmap, forced 4:4:4.
 *
 * Android's Bitmap.compress() leaves libjpeg at its defaults, i.e. 4:2:0, which
 * is what puts chroma blocks in dark areas and colour fringes on edges.  This
 * entry point reads the pixels straight from the Bitmap (stride aware, no Java
 * heap copy) and encodes with h/v_samp_factor = 1.
 *
 * Returns NULL on any failure; callers must fall back to Bitmap.compress.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_leicamoments_app_JpegStream_nativeEncodeBitmap(JNIEnv *env, jclass,
                                                       jobject jbitmap,
                                                       jint quality) {
  AndroidBitmapInfo info;
  if (AndroidBitmap_getInfo(env, jbitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
    LOGE("bitmap info unavailable");
    return NULL;
  }
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0) {
    LOGE("unsupported bitmap: format=%d %ux%u", info.format, info.width, info.height);
    return NULL;
  }
  void *pixels = NULL;
  if (AndroidBitmap_lockPixels(env, jbitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
      !pixels) {
    LOGE("lockPixels failed");
    return NULL;
  }

  /* Longjmp-safe: only POD locals below, and every buffer is freed on both paths. */
  unsigned char *outBuf = NULL;
  unsigned long outSize = 0;
  struct jpeg_compress_struct cinfo;
  LjtErrorMgr jerr;
  cinfo.err = jpeg_std_error(&jerr.pub);
  jerr.pub.error_exit = ljt_error_exit;
  jerr.pub.emit_message = ljt_emit_message;
  if (setjmp(jerr.setjmp_buffer)) {
    jpeg_destroy_compress(&cinfo);
    if (outBuf) free(outBuf);
    AndroidBitmap_unlockPixels(env, jbitmap);
    return NULL;
  }

  jpeg_create_compress(&cinfo);
  jpeg_mem_dest(&cinfo, &outBuf, &outSize);

  cinfo.image_width = info.width;
  cinfo.image_height = info.height;
  cinfo.input_components = 4;
  cinfo.in_color_space = JCS_EXT_RGBA;

  jpeg_set_defaults(&cinfo);
  jpeg_set_quality(&cinfo, quality, TRUE);
  cinfo.comp_info[0].h_samp_factor = 1;
  cinfo.comp_info[0].v_samp_factor = 1;
  cinfo.optimize_coding = FALSE;
  cinfo.dct_method = JDCT_ISLOW;

  jpeg_start_compress(&cinfo, TRUE);
  while (cinfo.next_scanline < cinfo.image_height) {
    JSAMPROW row = (JSAMPROW)((unsigned char *)pixels +
                              (size_t)cinfo.next_scanline * (size_t)info.stride);
    jpeg_write_scanlines(&cinfo, &row, 1);
  }
  jpeg_finish_compress(&cinfo);
  jpeg_destroy_compress(&cinfo);
  AndroidBitmap_unlockPixels(env, jbitmap);

  if (!outBuf || outSize == 0 || outSize > 0x7FFFFFFFUL) {
    if (outBuf) free(outBuf);
    return NULL;
  }
  jbyteArray arr = env->NewByteArray((jsize)outSize);
  if (arr) env->SetByteArrayRegion(arr, 0, (jsize)outSize, (const jbyte *)outBuf);
  free(outBuf);
  return arr;
}

JNIEXPORT jint JNICALL
Java_com_leicamoments_app_JpegStream_nativeClose(JNIEnv *, jclass, jlong handle) {
  StripEncoder *enc = (StripEncoder *)(intptr_t)handle;
  if (!enc) return -1;
  int rc = 0;
  if (enc->fp) {
    static const unsigned char eoi[2] = {0xFF, 0xD9};
    if (append(enc->fp, eoi, 2) != 0) rc = -1;
    if (fclose(enc->fp) != 0) rc = -1;
    enc->fp = NULL;
    LOGI("closed after %d bands", enc->bandIndex);
  }
  delete enc;
  return rc;
}

JNIEXPORT void JNICALL
Java_com_leicamoments_app_JpegStream_nativeAbort(JNIEnv *, jclass, jlong handle) {
  StripEncoder *enc = (StripEncoder *)(intptr_t)handle;
  if (!enc) return;
  if (enc->fp) {
    fclose(enc->fp);
    enc->fp = NULL;
  }
  delete enc;
}

JNIEXPORT jint JNICALL
Java_com_leicamoments_app_JpegStream_nativeBandCount(JNIEnv *, jclass, jlong handle) {
  StripEncoder *enc = (StripEncoder *)(intptr_t)handle;
  if (!enc) return 0;
  return (enc->height + enc->bandPx - 1) / enc->bandPx;
}

}  // extern "C"

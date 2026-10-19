#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include "libraw/libraw.h"

#define LOG_TAG "RawDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * 提取 RAW 内嵌的厂商预览图（JPEG 字节）。
 * 厂商 ISP 渲染，色彩最准确；失败或无预览返回 null。
 */
JNIEXPORT jbyteArray JNICALL
Java_com_leicamoments_app_RawDecoder_nativeExtractPreview(JNIEnv *env, jclass, jbyteArray data) {
    if (data == nullptr) return nullptr;
    jsize len = env->GetArrayLength(data);
    if (len <= 0) return nullptr;
    jbyte *buf = env->GetByteArrayElements(data, nullptr);
    if (buf == nullptr) return nullptr;

    jbyteArray result = nullptr;
    libraw_data_t *raw = libraw_init(0);
    if (raw != nullptr) {
        if (libraw_open_buffer(raw, buf, len) == LIBRAW_SUCCESS) {
            if (libraw_unpack_thumb(raw) == LIBRAW_SUCCESS &&
                raw->thumbnail.tformat == LIBRAW_THUMBNAIL_JPEG &&
                raw->thumbnail.tlength > 0 && raw->thumbnail.thumb != nullptr) {
                result = env->NewByteArray(raw->thumbnail.tlength);
                if (result != nullptr) {
                    env->SetByteArrayRegion(result, 0, raw->thumbnail.tlength,
                                            (jbyte *) raw->thumbnail.thumb);
                }
            }
        }
        libraw_close(raw);
    }
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return result;
}


/**
 * 解码实现。autoBrightThr 透传给 LibRaw：取直方图最亮该比例的分位点作为白点，
 * 0.01 为 LibRaw 默认（与旧版本行为完全一致）。
 */
static jintArray decodeInternal(JNIEnv *env, jbyteArray data, jfloat autoBrightThr) {
    if (data == nullptr) return nullptr;
    jsize len = env->GetArrayLength(data);
    if (len <= 0) return nullptr;

    jbyte *buf = env->GetByteArrayElements(data, nullptr);
    if (buf == nullptr) return nullptr;

    jintArray result = nullptr;
    libraw_data_t *raw = libraw_init(0);
    if (raw == nullptr) {
        env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
        return nullptr;
    }

    do {
        if (libraw_open_buffer(raw, buf, len) != LIBRAW_SUCCESS) {
            LOGE("open_buffer failed");
            break;
        }
        // 显影参数：必须用相机白平衡（默认日光 WB 会导致严重色偏），sRGB 8bit 输出
        raw->params.use_camera_wb = 1;
        raw->params.use_auto_wb = 0;
        raw->params.output_color = 1;   // sRGB
        raw->params.output_bps = 8;
        raw->params.no_auto_bright = 0; // LibRaw 标准自动亮度
        raw->params.auto_bright_thr = autoBrightThr;
        if (libraw_unpack(raw) != LIBRAW_SUCCESS) {
            LOGE("unpack failed");
            break;
        }
        if (libraw_dcraw_process(raw) != LIBRAW_SUCCESS) {
            LOGE("dcraw_process failed");
            break;
        }
        libraw_processed_image_t *img = libraw_dcraw_make_mem_image(raw, nullptr);
        if (img == nullptr) {
            LOGE("make_mem_image failed");
            break;
        }

        int w = img->width;
        int h = img->height;
        int colors = img->colors; // 期望 3 (RGB)
        int bps = img->bits;      // 期望 8
        if (w <= 0 || h <= 0) {
            libraw_dcraw_clear_mem(img);
            break;
        }

        long total = (long) w * h;
        result = env->NewIntArray(2 + total);
        if (result == nullptr) {
            libraw_dcraw_clear_mem(img);
            break;
        }

        jint *out = env->GetIntArrayElements(result, nullptr);
        if (out == nullptr) {
            env->DeleteLocalRef(result);
            result = nullptr;
            libraw_dcraw_clear_mem(img);
            break;
        }

        out[0] = w;
        out[1] = h;
        unsigned char *p = img->data;
        if (colors == 3 && bps == 8) {
            for (long i = 0; i < total; i++) {
                int r = p[i * 3 + 0];
                int g = p[i * 3 + 1];
                int b = p[i * 3 + 2];
                out[2 + i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else if (colors == 1 && bps == 8) {
            for (long i = 0; i < total; i++) {
                int g = p[i];
                out[2 + i] = 0xFF000000 | (g << 16) | (g << 8) | g;
            }
        } else {
            LOGI("unexpected colors=%d bps=%d, fallback to black", colors, bps);
            for (long i = 0; i < total; i++) out[2 + i] = 0xFF000000;
        }
        env->ReleaseIntArrayElements(result, out, 0);

        libraw_dcraw_clear_mem(img);
        LOGI("decoded %dx%d (colors=%d bps=%d)", w, h, colors, bps);
    } while (false);

    libraw_close(raw);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return result;
}

/** 解码 RAW 字节（LibRaw 默认 auto_bright_thr = 0.01）。失败返回 null。 */
JNIEXPORT jintArray JNICALL
Java_com_leicamoments_app_RawDecoder_nativeDecode(JNIEnv *env, jclass, jbyteArray data) {
    return decodeInternal(env, data, 0.01f);
}

/** 带 auto_bright_thr 的解码（RAW 解码亮度档位）。非法阈值回落到默认值。 */
JNIEXPORT jintArray JNICALL
Java_com_leicamoments_app_RawDecoder_nativeDecodeWithThr(JNIEnv *env, jclass, jbyteArray data,
                                                         jfloat autoBrightThr) {
    if (!(autoBrightThr > 0.0f) || autoBrightThr >= 0.5f) autoBrightThr = 0.01f;
    return decodeInternal(env, data, autoBrightThr);
}

/**
 * 线性解码（OffLineM9 前段输入）：LibRaw 16bit linear RAW，相机色彩空间。
 * 等价 rawpy.postprocess(gamma=(1,1), output_bps=16, output_color=raw, demosaic=AHD,
 * user_wb=mul, highlight=Clip, no_auto_bright=True)。
 *
 * 返回 direct ByteBuffer：前 8 字节 int w、int h，随后 w*h*3 个 u16（RGB 交错，本机字节序）。
 * 内存由 malloc 分配（堆外，不受 Java 堆限制），必须由 nativeFreeLinear 释放。
 */
JNIEXPORT jobject JNICALL
Java_com_leicamoments_app_RawDecoder_nativeDecodeLinear(JNIEnv *env, jclass, jbyteArray data,
                                                        jdoubleArray wbMul, jboolean half) {
    if (data == nullptr) return nullptr;
    jsize len = env->GetArrayLength(data);
    if (len <= 0) return nullptr;
    jbyte *buf = env->GetByteArrayElements(data, nullptr);
    if (buf == nullptr) return nullptr;

    jobject direct = nullptr;
    libraw_data_t *raw = libraw_init(0);
    do {
        if (raw == nullptr) {
            LOGE("libraw_init failed (linear)");
            break;
        }
        if (libraw_open_buffer(raw, buf, len) != LIBRAW_SUCCESS) {
            LOGE("open_buffer failed (linear)");
            break;
        }

        float mul[4] = {1.0f, 1.0f, 1.0f, 1.0f};
        if (wbMul != nullptr && env->GetArrayLength(wbMul) >= 3) {
            jdouble *m = env->GetDoubleArrayElements(wbMul, nullptr);
            if (m != nullptr) {
                mul[0] = (float) m[0];
                mul[1] = (float) m[1];
                mul[2] = (float) m[2];
                mul[3] = (float) m[1];   // G2
                env->ReleaseDoubleArrayElements(wbMul, m, JNI_ABORT);
            }
        }

        // 与 OffLineM9 的 rawpy 调用逐项对齐（rawpy 的 user_wb 即 LibRaw 的 user_mul）
        for (int i = 0; i < 4; i++) raw->params.user_mul[i] = mul[i];
        raw->params.use_camera_wb = 0;
        raw->params.use_auto_wb = 0;
        raw->params.output_color = 0;        // raw：保持相机色彩空间
        raw->params.output_bps = 16;
        raw->params.gamm[0] = 1.0f;          // gamma=(1,1) → 线性
        raw->params.gamm[1] = 1.0f;
        raw->params.no_auto_bright = 1;
        raw->params.bright = 1.0f;
        raw->params.highlight = 0;           // clip
        raw->params.user_qual = 3;           // AHD
        raw->params.half_size = half ? 1 : 0;

        if (libraw_unpack(raw) != LIBRAW_SUCCESS) {
            LOGE("unpack failed (linear)");
            break;
        }
        if (libraw_dcraw_process(raw) != LIBRAW_SUCCESS) {
            LOGE("dcraw_process failed (linear)");
            break;
        }
        libraw_processed_image_t *img = libraw_dcraw_make_mem_image(raw, nullptr);
        if (img == nullptr) {
            LOGE("make_mem_image failed (linear)");
            break;
        }

        int w = img->width;
        int h = img->height;
        int colors = img->colors;
        int bps = img->bits;
        if (w <= 0 || h <= 0 || colors != 3 || bps != 16) {
            LOGE("unexpected linear output %dx%d colors=%d bps=%d", w, h, colors, bps);
            libraw_dcraw_clear_mem(img);
            break;
        }

        size_t nbytes = (size_t) w * (size_t) h * 3u * 2u;
        unsigned char *mem = (unsigned char *) malloc(8 + nbytes);
        if (mem == nullptr) {
            LOGE("malloc %zu failed", nbytes);
            libraw_dcraw_clear_mem(img);
            break;
        }
        int *head = (int *) mem;
        head[0] = w;
        head[1] = h;
        memcpy(mem + 8, img->data, nbytes);
        libraw_dcraw_clear_mem(img);

        direct = env->NewDirectByteBuffer(mem, (jlong) (8 + nbytes));
        if (direct == nullptr) {
            free(mem);
            LOGE("NewDirectByteBuffer failed");
            break;
        }
        LOGI("linear decoded %dx%d (%.1f MB)", w, h, nbytes / 1048576.0);
    } while (false);

    if (raw != nullptr) libraw_close(raw);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return direct;
}


/**
 * 线性 sRGB 解码（RAW 自适应曲线前段）：16bit 线性输出、sRGB 色彩空间、相机白平衡，
 * 关闭 LibRaw 自动亮度（白点与提亮完全交给上层曲线，避免二次缩放）。
 *
 * 等价 rawpy.postprocess(use_camera_wb=True, output_color=sRGB, output_bps=16,
 * gamma=(1,1), no_auto_bright=True, highlight=Clip, demosaic=AHD)。
 *
 * 返回 direct ByteBuffer：前 8 字节 int w、int h，随后 w*h*3 个 u16（RGB 交错，本机字节序）。
 * 内存由 malloc 分配，必须由 nativeFreeLinear 释放。
 */
JNIEXPORT jobject JNICALL
Java_com_leicamoments_app_RawDecoder_nativeDecodeLinearSrgb(JNIEnv *env, jclass, jbyteArray data) {
    if (data == nullptr) return nullptr;
    jsize len = env->GetArrayLength(data);
    if (len <= 0) return nullptr;
    jbyte *buf = env->GetByteArrayElements(data, nullptr);
    if (buf == nullptr) return nullptr;

    jobject direct = nullptr;
    libraw_data_t *raw = libraw_init(0);
    do {
        if (raw == nullptr) {
            LOGE("libraw_init failed (linear srgb)");
            break;
        }
        if (libraw_open_buffer(raw, buf, len) != LIBRAW_SUCCESS) {
            LOGE("open_buffer failed (linear srgb)");
            break;
        }

        raw->params.use_camera_wb = 1;
        raw->params.use_auto_wb = 0;
        raw->params.output_color = 1;        // sRGB
        raw->params.output_bps = 16;
        raw->params.gamm[0] = 1.0f;          // gamma=(1,1) → 线性
        raw->params.gamm[1] = 1.0f;
        raw->params.no_auto_bright = 1;
        raw->params.bright = 1.0f;
        raw->params.highlight = 0;           // clip
        raw->params.user_qual = 3;           // AHD
        raw->params.half_size = 0;

        if (libraw_unpack(raw) != LIBRAW_SUCCESS) {
            LOGE("unpack failed (linear srgb)");
            break;
        }
        if (libraw_dcraw_process(raw) != LIBRAW_SUCCESS) {
            LOGE("dcraw_process failed (linear srgb)");
            break;
        }
        libraw_processed_image_t *img = libraw_dcraw_make_mem_image(raw, nullptr);
        if (img == nullptr) {
            LOGE("make_mem_image failed (linear srgb)");
            break;
        }

        int w = img->width;
        int h = img->height;
        int colors = img->colors;
        int bps = img->bits;
        if (w <= 0 || h <= 0 || colors != 3 || bps != 16) {
            LOGE("unexpected linear srgb output %dx%d colors=%d bps=%d", w, h, colors, bps);
            libraw_dcraw_clear_mem(img);
            break;
        }

        size_t nbytes = (size_t) w * (size_t) h * 3u * 2u;
        unsigned char *mem = (unsigned char *) malloc(8 + nbytes);
        if (mem == nullptr) {
            LOGE("malloc %zu failed (linear srgb)", nbytes);
            libraw_dcraw_clear_mem(img);
            break;
        }
        int *head = (int *) mem;
        head[0] = w;
        head[1] = h;
        memcpy(mem + 8, img->data, nbytes);
        libraw_dcraw_clear_mem(img);

        direct = env->NewDirectByteBuffer(mem, (jlong) (8 + nbytes));
        if (direct == nullptr) {
            free(mem);
            LOGE("NewDirectByteBuffer failed (linear srgb)");
            break;
        }
        LOGI("linear sRGB decoded %dx%d (%.1f MB)", w, h, nbytes / 1048576.0);
    } while (false);

    if (raw != nullptr) libraw_close(raw);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return direct;
}


/** 释放 nativeDecodeLinear 的堆外缓冲。 */
JNIEXPORT void JNICALL
Java_com_leicamoments_app_RawDecoder_nativeFreeLinear(JNIEnv *env, jclass, jobject buffer) {
    if (buffer == nullptr) return;
    void *addr = env->GetDirectBufferAddress(buffer);
    if (addr != nullptr) free(addr);
}

} // extern "C"

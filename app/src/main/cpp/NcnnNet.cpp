// JNI bridge for ncnn GPU (Vulkan) inference.
//
// Design notes
// ---------------------------------------------------------------------------
// * One native handle == one ncnn::Net plus its optional PipelineCache.
//   Vulkan device is global inside ncnn, so the first create() pays the
//   device init cost and later nets reuse it.
// * Inputs are CHW float32, supplied by the caller as *direct* ByteBuffers so
//   they can be wrapped by ncnn::Mat without a copy. Outputs are returned as a
//   float[] (one memcpy) to keep the JNI ownership rules trivial; the copy is
//   ~1ms for 544x544x3 and ~10ms for 1216x1216x3, well below the CPU inference
//   time it replaces.
// * Every failure path returns null / 0 and records a message that
//   GpuNet.lastError() can read. There is no exception thrown across JNI: the
//   Kotlin caller falls back to the existing ORT implementation.
// * The pipeline cache is loaded from / saved to a caller-provided path.
//   Vulkan shader compilation happens on the first extract() of a given shape,
//   therefore the caller must warm up with the *real* tile shape.

#include <jni.h>
#include <android/log.h>

#include <chrono>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "net.h"

#if NCNN_VULKAN
#include "gpu.h"
#include "pipelinecache.h"
#endif

#define LOG_TAG "NcnnNet"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct NetHandle {
    ncnn::Net net;
#if NCNN_VULKAN
    ncnn::PipelineCache* pipeline_cache = nullptr;
#endif
    std::string cache_path;
    bool vulkan = false;
    bool warm = false;
    int in_count = 0;
    int out_count = 0;
    // Blob names are what ncnn::Extractor addresses by. Blob *indices* are NOT
    // "the i-th input / the graph output": extract(0) hands back blob 0, which is
    // the input tensor itself. Keep the names resolved at load time.
    std::vector<std::string> in_names;
    std::vector<std::string> out_names;
};

std::mutex g_mutex;
std::string g_last_error;

void set_error(const std::string& msg) {
    g_last_error = msg;
    LOGE("%s", msg.c_str());
}

// jfloatArray <- ncnn::Mat (CHW float32, elempack normalised to 1).
jfloatArray mat_to_float_array(JNIEnv* env, const ncnn::Mat& src) {
    ncnn::Mat host = src;
    if (host.elempack != 1) {
        ncnn::Mat unpacked;
        ncnn::convert_packing(host, unpacked, 1, ncnn::Option());
        if (unpacked.empty()) {
            set_error("output convert_packing failed");
            return nullptr;
        }
        host = unpacked;
    }
    if (host.dims != 3 && host.dims != 2) {
        set_error("output dims unsupported: " + std::to_string(host.dims));
        return nullptr;
    }
    const int w = host.w;
    const int h = host.h;
    const int c = host.c;
    const jsize total = static_cast<jsize>(w) * h * c;
    jfloatArray out = env->NewFloatArray(total);
    if (out == nullptr) {
        set_error("NewFloatArray OOM");
        return nullptr;
    }
    // Channel by channel: cstep may be padded, so copying the whole block in one
    // shot would pull padding values into the wrong plane.
    for (int q = 0; q < c; q++) {
        const float* plane = host.channel(q);
        env->SetFloatArrayRegion(out, static_cast<jsize>(q) * w * h, static_cast<jsize>(w) * h,
                                 reinterpret_cast<const jfloat*>(plane));
    }
    return out;
}

// Runs one inference. `input_ptrs[i]` may be null (zero-filled warm-up).
bool run_impl(NetHandle* handle, const std::vector<void*>& input_ptrs,
              const std::vector<int>& shape_c, const std::vector<int>& shape_h,
              const std::vector<int>& shape_w, jfloatArray* out_array, JNIEnv* env) {
    if (handle == nullptr) {
        set_error("null handle");
        return false;
    }
    const size_t n = input_ptrs.size();
    if (n == 0 || n != shape_c.size() || n != shape_h.size() || n != shape_w.size()) {
        set_error("input shape mismatch");
        return false;
    }
    if (static_cast<int>(n) < handle->in_count) {
        set_error("missing inputs: net expects " + std::to_string(handle->in_count));
        return false;
    }

    std::vector<ncnn::Mat> owned;   // storage for zero-filled warm-up inputs
    std::vector<ncnn::Mat> mats;
    owned.reserve(n);
    mats.reserve(n);
    for (size_t i = 0; i < n; i++) {
        const int c = shape_c[i];
        const int h = shape_h[i];
        const int w = shape_w[i];
        if (c <= 0 || h <= 0 || w <= 0) {
            set_error("bad input shape");
            return false;
        }
        if (input_ptrs[i] != nullptr) {
            ncnn::Mat m(w, h, c, input_ptrs[i], 4u);
            // Channel stride of an external buffer is alignSize(w*h*4,16)/4. When the
            // plane is not 16B aligned ncnn pads cstep, so channel 1+ would be read
            // from the wrong offset of our tightly packed Java buffer. Refuse instead
            // of producing garbage (Kotlin treats it as "GPU unavailable" and falls
            // back to ORT). All current tile shapes satisfy this.
            if (m.cstep != static_cast<size_t>(w) * h) {
                set_error("input plane stride mismatch (needs 16B aligned wh)");
                return false;
            }
            mats.push_back(m);
        } else {
            owned.push_back(ncnn::Mat(w, h, c));
            std::memset(owned.back().data, 0, owned.back().cstep * c);
            mats.push_back(owned.back());
        }
    }

    ncnn::Extractor ex = handle->net.create_extractor();
    for (size_t i = 0; i < mats.size(); i++) {
        int ret = ex.input(handle->in_names[i].c_str(), mats[i]);
        if (ret != 0) {
            set_error("input #" + std::to_string(i) + " (" + handle->in_names[i] +
                      ") rejected, ret=" + std::to_string(ret));
            return false;
        }
    }

    ncnn::Mat feat;
    int ret = ex.extract(handle->out_names[0].c_str(), feat);
    if (ret != 0 || feat.empty()) {
        set_error("extract failed, ret=" + std::to_string(ret));
        return false;
    }

    if (out_array != nullptr) {
        jfloatArray arr = mat_to_float_array(env, feat);
        if (arr == nullptr) return false;
        *out_array = arr;
    }
    handle->warm = true;
    return true;
}

void destroy(NetHandle* handle) {
    if (handle == nullptr) return;
#if NCNN_VULKAN
    if (handle->pipeline_cache != nullptr) {
        if (handle->vulkan && !handle->cache_path.empty()) {
            int ret = handle->pipeline_cache->save_cache(handle->cache_path.c_str());
            if (ret != 0) LOGE("pipeline cache save failed, ret=%d", ret);
        }
        delete handle->pipeline_cache;
        handle->pipeline_cache = nullptr;
    }
#endif
    delete handle;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_lmcamera_app_NcnnNet_nativeGpuCount(JNIEnv*, jclass) {
#if NCNN_VULKAN
    return ncnn::get_gpu_count();
#else
    return 0;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_lmcamera_app_NcnnNet_nativeDeviceInfo(JNIEnv* env, jclass) {
#if NCNN_VULKAN
    const int count = ncnn::get_gpu_count();
    if (count <= 0) return env->NewStringUTF("no vulkan device");
    const ncnn::GpuInfo& info = ncnn::get_gpu_info(0);
    char buf[256];
    snprintf(buf, sizeof(buf), "%s api=%u score=%u heap=%uMB", info.device_name(),
             info.api_version(), info.rough_score(),
             info.max_shared_memory_size() / (1024 * 1024));
    return env->NewStringUTF(buf);
#else
    return env->NewStringUTF("ncnn built without vulkan");
#endif
}

/**
 * Creates a net. Returns 0 on any failure (caller falls back to ORT).
 *
 * @param param_path  absolute path of the .param file
 * @param bin_path    absolute path of the .bin file
 * @param cache_path  absolute path of the .vkpipeline cache (empty = no cache)
 * @param threads     CPU threads for layers that still run on CPU
 * @param use_vulkan  1 = GPU, 0 = CPU (useful for parity runs)
 * @param fp16        1 = fp16 packed/storage
 * @param fp16_arith  1 = fp16 arithmetic (faster, slightly less accurate)
 */
JNIEXPORT jlong JNICALL
Java_com_lmcamera_app_NcnnNet_nativeCreate(JNIEnv* env, jclass, jstring param_path,
                                               jstring bin_path, jstring cache_path,
                                               jint threads, jint use_vulkan, jint fp16,
                                               jint fp16_arith) {
    std::lock_guard<std::mutex> guard(g_mutex);
    const char* param_c = env->GetStringUTFChars(param_path, nullptr);
    const char* bin_c = env->GetStringUTFChars(bin_path, nullptr);
    const char* cache_c = cache_path == nullptr ? nullptr : env->GetStringUTFChars(cache_path, nullptr);

    NetHandle* handle = new NetHandle();
    handle->cache_path = cache_c != nullptr ? cache_c : "";
    handle->vulkan = use_vulkan != 0;

#if NCNN_VULKAN
    if (handle->vulkan) {
        if (ncnn::get_gpu_count() <= 0) {
            set_error("no vulkan device available");
            env->ReleaseStringUTFChars(param_path, param_c);
            env->ReleaseStringUTFChars(bin_path, bin_c);
            if (cache_c != nullptr) env->ReleaseStringUTFChars(cache_path, cache_c);
            delete handle;
            return 0;
        }
        ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device(0);
        if (vkdev == nullptr) {
            set_error("vulkan device init failed");
            env->ReleaseStringUTFChars(param_path, param_c);
            env->ReleaseStringUTFChars(bin_path, bin_c);
            if (cache_c != nullptr) env->ReleaseStringUTFChars(cache_path, cache_c);
            delete handle;
            return 0;
        }
        handle->net.set_vulkan_device(vkdev);
        handle->net.opt.use_vulkan_compute = 1;
        if (!handle->cache_path.empty()) {
            handle->pipeline_cache = new ncnn::PipelineCache(vkdev);
            const int ret = handle->pipeline_cache->load_cache(handle->cache_path.c_str());
            LOGI("pipeline cache load(%s) ret=%d", handle->cache_path.c_str(), ret);
            handle->net.opt.pipeline_cache = handle->pipeline_cache;
        }
    } else {
        handle->net.opt.use_vulkan_compute = 0;
    }
#else
    handle->vulkan = false;
    handle->net.opt.use_vulkan_compute = 0;
#endif

    handle->net.opt.num_threads = threads > 0 ? threads : 2;
    handle->net.opt.use_fp16_packed = fp16 != 0;
    handle->net.opt.use_fp16_storage = fp16 != 0;
    handle->net.opt.use_fp16_arithmetic = fp16_arith != 0;

    int ret = handle->net.load_param(param_c);
    if (ret != 0) {
        set_error("load_param failed, ret=" + std::to_string(ret));
        env->ReleaseStringUTFChars(param_path, param_c);
        env->ReleaseStringUTFChars(bin_path, bin_c);
        if (cache_c != nullptr) env->ReleaseStringUTFChars(cache_path, cache_c);
        destroy(handle);
        return 0;
    }
    ret = handle->net.load_model(bin_c);
    if (ret != 0) {
        set_error("load_model failed, ret=" + std::to_string(ret));
        env->ReleaseStringUTFChars(param_path, param_c);
        env->ReleaseStringUTFChars(bin_path, bin_c);
        if (cache_c != nullptr) env->ReleaseStringUTFChars(cache_path, cache_c);
        destroy(handle);
        return 0;
    }

    handle->in_count = static_cast<int>(handle->net.input_names().size());
    handle->out_count = static_cast<int>(handle->net.output_names().size());
    for (const char* nm : handle->net.input_names()) handle->in_names.push_back(nm ? nm : "");
    for (const char* nm : handle->net.output_names()) handle->out_names.push_back(nm ? nm : "");

    if (handle->in_names.empty() || handle->out_names.empty()) {
        set_error("net has no input/output names");
        env->ReleaseStringUTFChars(param_path, param_c);
        env->ReleaseStringUTFChars(bin_path, bin_c);
        if (cache_c != nullptr) env->ReleaseStringUTFChars(cache_path, cache_c);
        destroy(handle);
        return 0;
    }

    std::string inputs;
    for (size_t i = 0; i < handle->in_names.size(); i++) {
        if (i) inputs += ",";
        inputs += handle->in_names[i];
    }
    std::string outputs;
    for (size_t i = 0; i < handle->out_names.size(); i++) {
        if (i) outputs += ",";
        outputs += handle->out_names[i];
    }
    LOGI("net ready param=%s inputs=[%s] outputs=[%s] in=%d out=%d vulkan=%d threads=%d fp16=%d/%d",
         param_c, inputs.c_str(), outputs.c_str(), handle->in_count, handle->out_count,
         (int)handle->vulkan, handle->net.opt.num_threads,
         (int)handle->net.opt.use_fp16_storage, (int)handle->net.opt.use_fp16_arithmetic);

    env->ReleaseStringUTFChars(param_path, param_c);
    env->ReleaseStringUTFChars(bin_path, bin_c);
    if (cache_c != nullptr) env->ReleaseStringUTFChars(cache_path, cache_c);
    g_last_error.clear();
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_lmcamera_app_NcnnNet_nativeFlushCache(JNIEnv*, jclass, jlong ptr) {
    NetHandle* handle = reinterpret_cast<NetHandle*>(ptr);
    if (handle == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(g_mutex);
#if NCNN_VULKAN
    if (handle->vulkan && handle->pipeline_cache != nullptr && !handle->cache_path.empty()) {
        const int ret = handle->pipeline_cache->save_cache(handle->cache_path.c_str());
        LOGI("pipeline cache save(%s) ret=%d", handle->cache_path.c_str(), ret);
        return ret == 0 ? JNI_TRUE : JNI_FALSE;
    }
#endif
    return JNI_FALSE;
}

/**
 * Inputs: direct ByteBuffers holding CHW float32, all with the same shape except
 * channel count (M9 distill uses two inputs with identical H/W).
 * Output: float[C*H*W] (CHW), or null on failure.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_lmcamera_app_NcnnNet_nativeRun(JNIEnv* env, jclass, jlong ptr, jobjectArray inputs,
                                            jintArray shape_c, jintArray shape_h,
                                            jintArray shape_w) {
    NetHandle* handle = reinterpret_cast<NetHandle*>(ptr);
    if (handle == nullptr) return nullptr;
    if (inputs == nullptr || shape_c == nullptr || shape_h == nullptr || shape_w == nullptr) {
        set_error("null arguments");
        return nullptr;
    }
    const jsize n = env->GetArrayLength(inputs);
    if (env->GetArrayLength(shape_c) != n || env->GetArrayLength(shape_h) != n ||
        env->GetArrayLength(shape_w) != n) {
        set_error("shape array length mismatch");
        return nullptr;
    }
    std::vector<int> cs(n), hs(n), ws(n);
    env->GetIntArrayRegion(shape_c, 0, n, cs.data());
    env->GetIntArrayRegion(shape_h, 0, n, hs.data());
    env->GetIntArrayRegion(shape_w, 0, n, ws.data());

    std::vector<void*> ptrs(n, nullptr);
    for (jsize i = 0; i < n; i++) {
        jobject buf = env->GetObjectArrayElement(inputs, i);
        if (buf == nullptr) continue;
        void* addr = env->GetDirectBufferAddress(buf);
        const jlong cap = env->GetDirectBufferCapacity(buf);
        const jlong need = static_cast<jlong>(cs[i]) * hs[i] * ws[i] * 4;
        // GetDirectBufferCapacity() is expressed in the buffer's own elements: bytes for
        // ByteBuffer, floats for FloatBuffer. Our Kotlin side legitimately passes either
        // (DirectFloatBuffer saves a 7MB copy per tile), so normalise before comparing.
        jclass float_buffer_cls = env->FindClass("java/nio/FloatBuffer");
        const bool is_float_buffer =
                float_buffer_cls != nullptr && env->IsInstanceOf(buf, float_buffer_cls);
        if (float_buffer_cls != nullptr) env->DeleteLocalRef(float_buffer_cls);
        const jlong cap_bytes = is_float_buffer ? cap * 4 : cap;
        if (addr == nullptr || cap_bytes < need) {
            set_error("input #" + std::to_string(i) + " must be a direct buffer of >= " +
                      std::to_string(need) + " bytes (got " + std::to_string(cap_bytes) + ")");
            env->DeleteLocalRef(buf);
            return nullptr;
        }
        ptrs[i] = addr;
        env->DeleteLocalRef(buf);
    }

    std::lock_guard<std::mutex> guard(g_mutex);
    jfloatArray out = nullptr;
    if (!run_impl(handle, ptrs, cs, hs, ws, &out, env)) {
        return nullptr;
    }
    return out;
}

/** Warms up (compiles shaders for) the given shape; returns elapsed ms or -1. */
JNIEXPORT jlong JNICALL
Java_com_lmcamera_app_NcnnNet_nativeWarmup(JNIEnv* env, jclass, jlong ptr, jintArray shape_c,
                                               jintArray shape_h, jintArray shape_w) {
    NetHandle* handle = reinterpret_cast<NetHandle*>(ptr);
    if (handle == nullptr) return -1;
    const jsize n = env->GetArrayLength(shape_c);
    if (n <= 0 || env->GetArrayLength(shape_h) != n || env->GetArrayLength(shape_w) != n) {
        set_error("warmup shape mismatch");
        return -1;
    }
    std::vector<int> cs(n), hs(n), ws(n);
    env->GetIntArrayRegion(shape_c, 0, n, cs.data());
    env->GetIntArrayRegion(shape_h, 0, n, hs.data());
    env->GetIntArrayRegion(shape_w, 0, n, ws.data());
    std::vector<void*> ptrs(n, nullptr);   // null => zero-filled

    std::lock_guard<std::mutex> guard(g_mutex);
    const auto t0 = std::chrono::steady_clock::now();
    if (!run_impl(handle, ptrs, cs, hs, ws, nullptr, env)) return -1;
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t0).count();
    return static_cast<jlong>(ms);
}

JNIEXPORT void JNICALL
Java_com_lmcamera_app_NcnnNet_nativeDestroy(JNIEnv*, jclass, jlong ptr) {
    NetHandle* handle = reinterpret_cast<NetHandle*>(ptr);
    if (handle == nullptr) return;
    std::lock_guard<std::mutex> guard(g_mutex);
    destroy(handle);
}

JNIEXPORT jstring JNICALL
Java_com_lmcamera_app_NcnnNet_nativeLastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_last_error.c_str());
}

}  // extern "C"

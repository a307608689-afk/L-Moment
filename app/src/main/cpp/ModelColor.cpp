// M6.2 native apply: aff + knots + sky/skin protect + RGB guard.
//
// Mirrors AdaptiveColorNet.apply (Kotlin) but runs multi-threaded in C++.
// Protection uses a continuous 0..1 sky/skin weight scored on a 1/8 grid (see
// protectScore): the old hard box left a cliff at the cloud/building boundary,
// where a pixel either took the whole style or none of it, and a 3x3 blur was
// far too narrow to hide it -- the sky broke into blocks.
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

namespace {

constexpr float kPi = 3.14159265f;

float gSrgb2Lin[256];
float gLin2Srgb[1025];
float gCbrt[1025];
std::once_flag gLutOnce;

inline float srgb2linScalar(float v) {
    return v <= 0.04045f ? v / 12.92f : powf((v + 0.055f) / 1.055f, 2.4f);
}

inline float lin2srgb(float v) {
    return v <= 0.0031308f ? v * 12.92f : 1.055f * powf(v, 1.0f / 2.4f) - 0.055f;
}

// The main loop is pow-bound; quantise the transfer curves. Input is 8-bit sRGB
// to begin with, and the 1024-entry LUTs interpolate to well under 1/255.
inline float srgb2linLut(float v) {
    int i = (int) (v * 255.0f + 0.5f);
    return gSrgb2Lin[std::clamp(i, 0, 255)];
}

inline float lin2srgbLut(float v) {
    float x = std::clamp(v, 0.0f, 1.0f) * 1024.0f;
    int i = std::clamp((int) x, 0, 1023);
    float f = x - (float) i;
    return gLin2Srgb[i] + (gLin2Srgb[i + 1] - gLin2Srgb[i]) * f;
}

inline float cbrtLut(float v) {
    float x = std::clamp(v, 0.0f, 1.0f) * 1024.0f;
    int i = std::clamp((int) x, 0, 1023);
    float f = x - (float) i;
    return gCbrt[i] + (gCbrt[i + 1] - gCbrt[i]) * f;
}

inline float finv(float t) {
    return t > 0.206893f ? t * t * t : (t - 16.0f / 116.0f) / 7.787f;
}

inline void rgb2lab(float r, float g, float b, float* lab) {
    float rl = srgb2linLut(r), gl = srgb2linLut(g), bl = srgb2linLut(b);
    float x = (0.412453f * rl + 0.357580f * gl + 0.180423f * bl) / 0.950456f;
    float y = (0.212671f * rl + 0.715160f * gl + 0.072169f * bl);
    float z = (0.019334f * rl + 0.119193f * gl + 0.950227f * bl) / 1.088754f;
    float fx = x > 0.008856f ? cbrtLut(x) : 7.787f * x + 16.0f / 116.0f;
    float fy = y > 0.008856f ? cbrtLut(y) : 7.787f * y + 16.0f / 116.0f;
    float fz = z > 0.008856f ? cbrtLut(z) : 7.787f * z + 16.0f / 116.0f;
    lab[0] = 116.0f * fy - 16.0f;
    lab[1] = 500.0f * (fx - fy);
    lab[2] = 200.0f * (fy - fz);
}

inline void lab2rgb(float L, float A, float B, float* rgb) {
    float fy = (L + 16.0f) / 116.0f;
    float fx = fy + A / 500.0f;
    float fz = fy - B / 200.0f;
    float x = finv(fx) * 0.950456f;
    float y = finv(fy);
    float z = finv(fz) * 1.088754f;
    float rl = 3.240479f * x - 1.537150f * y - 0.498535f * z;
    float gl = -0.969256f * x + 1.875992f * y + 0.041556f * z;
    float bl = 0.055648f * x - 0.204043f * y + 1.057311f * z;
    rgb[0] = lin2srgbLut(std::clamp(rl, 0.0f, 1.0f));
    rgb[1] = lin2srgbLut(std::clamp(gl, 0.0f, 1.0f));
    rgb[2] = lin2srgbLut(std::clamp(bl, 0.0f, 1.0f));
}

inline float curve(float v, const float* k, int off, float lo, float hi) {
    float pos = std::clamp((v - lo) / (hi - lo), 0.0f, 1.0f) * 8.0f;
    int i0 = std::clamp((int) pos, 0, 7);
    float f = pos - (float) i0;
    return k[off + i0] + (k[off + i0 + 1] - k[off + i0]) * f;
}

inline float smoothstep(float e0, float e1, float x) {
    float t = std::clamp((x - e0) / (e1 - e0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// Continuous 0..1 sky/skin weight (1 = protect). Two sky models: thin cloud is
// bright but nearly neutral, a clear sky is darker and clearly blue; the chroma
// and |a| caps keep blue objects (signs, cars, sculptures) out. Skin is a soft
// box on a/b. Mirrors common.protect_score -- keep the constants in step.
inline float protectScore(float L, float a, float b) {
    const float chroma = sqrtf(a * a + b * b);
    const float white = smoothstep(66.0f, 79.0f, L) *
                        smoothstep(25.0f, 12.0f, chroma) *
                        smoothstep(-1.0f, -4.0f, b) *
                        (1.0f - smoothstep(4.0f, 9.0f, fabsf(a)));
    const float blue = smoothstep(42.0f, 60.0f, L) *
                       smoothstep(42.0f, 25.0f, chroma) *
                       smoothstep(-2.0f, -7.0f, b) *
                       (1.0f - smoothstep(12.0f, 20.0f, fabsf(a)));
    const float skin = smoothstep(26.0f, 36.0f, L) * smoothstep(92.0f, 80.0f, L) *
                       smoothstep(5.0f, 11.0f, a) * smoothstep(38.0f, 30.0f, a) *
                       smoothstep(4.0f, 10.0f, b) * smoothstep(40.0f, 32.0f, b);
    return std::max(std::max(white, blue), skin);
}

// 1/8 grid: same picture as a full-res score at 1/64 the cost, and the blur
// radius there (~16px at full res) is what actually hides the mask boundary.
constexpr int kMaskDiv = 8;
constexpr float kMaskSigma = 2.0f;
// Protected pixels may drift at most this many Lab units from the original.
// 0 = fully pinned to the raw source pixel (the style may not move it at all).
// Same value as common.apply_shift_bgr(soft=) and AdaptiveColorNet.
constexpr float kProtectSoft = 0.0f;

struct Ctx {
    int w, h;
    float aff[12];
    float knots[27];
    float strength;   // 0 = identity, 1 = full style, >1 = extrapolate
    bool protect, guard;
    const float* smallMask;  // sw*sh protection weight; null = no protection
    int sw, sh;
    // neutralize
    float gr, gg, gb;      // wb gains
    float scale, offset;   // exposure
    // Full-resolution band mode: local y=0 is global row yOffset. The mask is a
    // window of the global shFull-row grid starting at grid row maskTop.
    // All zero in the normal whole-image path.
    int yOffset, globalH, shFull, maskTop;
};

inline void neutralizeLab(const Ctx& c, float r, float g, float b, float* lab) {
    float rl = srgb2linScalar(r) * c.gr * c.scale + c.offset;
    float gl = srgb2linScalar(g) * c.gg * c.scale + c.offset;
    float bl = srgb2linScalar(b) * c.gb * c.scale + c.offset;
    rl = std::clamp(rl, 0.0f, 1.0f);
    gl = std::clamp(gl, 0.0f, 1.0f);
    bl = std::clamp(bl, 0.0f, 1.0f);
    rgb2lab(lin2srgbLut(rl), lin2srgbLut(gl), lin2srgbLut(bl), lab);
}

void applyRange(const Ctx& c, int32_t* px, int y0, int y1) {
    const int w = c.w;
    for (int y = y0; y < y1; ++y) {
        int row = y * w;
        for (int x = 0; x < w; ++x) {
            int32_t p = px[row + x];
            float r = (float) ((p >> 16) & 0xFF) / 255.0f;
            float g = (float) ((p >> 8) & 0xFF) / 255.0f;
            float b = (float) (p & 0xFF) / 255.0f;
            // Style input = N(x), exactly what 04_dataset/05_model trained on.
            // The raw Lab is kept only as the protection anchor: protected pixels
            // must converge to the ORIGINAL source pixel, never to N(x).
            float lab[3];
            neutralizeLab(c, r, g, b, lab);
            float labRaw[3];
            rgb2lab(r, g, b, labRaw);

            float l = lab[0] / 100.0f;
            float a = lab[1] / 128.0f;
            float bb = lab[2] / 128.0f;
            float vl = c.aff[0] * l + c.aff[1] * a + c.aff[2] * bb + c.aff[3];
            float va = c.aff[4] * l + c.aff[5] * a + c.aff[6] * bb + c.aff[7];
            float vb = c.aff[8] * l + c.aff[9] * a + c.aff[10] * bb + c.aff[11];
            vl = curve(vl, c.knots, 0, 0.0f, 1.0f);
            va = curve(va, c.knots, 9, -1.0f, 1.0f);
            vb = curve(vb, c.knots, 18, -1.0f, 1.0f);

            float L = vl * 100.0f, A = va * 128.0f, B = vb * 128.0f;

            // strength: linear blend from the ORIGINAL pixel (0) through the fitted style (1).
            // Using N(x) as identity here would make strength=0 change white balance.
            if (c.strength != 1.0f) {
                L = labRaw[0] + c.strength * (L - labRaw[0]);
                A = labRaw[1] + c.strength * (A - labRaw[1]);
                B = labRaw[2] + c.strength * (B - labRaw[2]);
            }

            if (c.smallMask != nullptr) {
                // bilinear sample of the 1/8 weight grid, cv2 INTER_LINEAR
                // mapping: src = (dst + 0.5) * scale - 0.5
                const float sxp = (x + 0.5f) * (float) c.sw / (float) w - 0.5f;
                // In band mode c.h/c.sh describe the decoded window, while the
                // mask must be sampled in the coordinates of the whole image.
                const int maskH = c.globalH > 0 ? c.globalH : c.h;
                const int maskSh = c.globalH > 0 ? c.shFull : c.sh;
                const float syp = ((float) (y + c.yOffset) + 0.5f) * (float) maskSh /
                                  (float) maskH - 0.5f - (float) c.maskTop;
                int gx0 = (int) floorf(sxp);
                int gy0 = (int) floorf(syp);
                const float fx = sxp - (float) gx0;
                const float fy = syp - (float) gy0;
                const int gx1 = std::clamp(gx0 + 1, 0, c.sw - 1);
                const int gy1 = std::clamp(gy0 + 1, 0, c.sh - 1);
                gx0 = std::clamp(gx0, 0, c.sw - 1);
                gy0 = std::clamp(gy0, 0, c.sh - 1);
                const float* r0 = c.smallMask + (size_t) gy0 * c.sw;
                const float* r1 = c.smallMask + (size_t) gy1 * c.sw;
                const float m = (r0[gx0] * (1.0f - fx) + r0[gx1] * fx) * (1.0f - fy) +
                                (r1[gx0] * (1.0f - fx) + r1[gx1] * fx) * fy;
                if (m > 0.0f) {
                    // Cross-fade towards the anchored pixel, not a where(m>0)
                    // cut: the old form stepped at m=0 because a barely-sky
                    // pixel still got the full style.
                    L = (1.0f - m) * L +
                        m * (labRaw[0] + std::clamp(L - labRaw[0], -kProtectSoft, kProtectSoft));
                    A = (1.0f - m) * A +
                        m * (labRaw[1] + std::clamp(A - labRaw[1], -kProtectSoft, kProtectSoft));
                    B = (1.0f - m) * B +
                        m * (labRaw[2] + std::clamp(B - labRaw[2], -kProtectSoft, kProtectSoft));
                }
            }

            float rgb[3];
            lab2rgb(L, A, B, rgb);
            int r8 = (int) lroundf(rgb[0] * 255.0f);
            int g8 = (int) lroundf(rgb[1] * 255.0f);
            int b8 = (int) lroundf(rgb[2] * 255.0f);
            r8 = std::clamp(r8, 0, 255);
            g8 = std::clamp(g8, 0, 255);
            b8 = std::clamp(b8, 0, 255);
            if (c.guard) {
                r8 = std::min(r8, 250);
                g8 = std::min(g8, 250);
                b8 = std::min(b8, 250);
            }
            px[row + x] = (int32_t) (0xFF000000u | ((uint32_t) r8 << 16) |
                                     ((uint32_t) g8 << 8) | (uint32_t) b8);
        }
    }
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_leicamoments_app_AdaptiveColorNet_applyNative(
        JNIEnv* env, jobject, jintArray pixels, jint w, jint h,
        jfloatArray affArr, jfloatArray knotsArr, jboolean protect, jboolean guard,
        jfloat strength, jboolean smooth) {
    if (pixels == nullptr || w <= 0 || h <= 0 || affArr == nullptr || knotsArr == nullptr) return;

    std::call_once(gLutOnce, []() {
        for (int i = 0; i < 256; ++i) gSrgb2Lin[i] = srgb2linScalar((float) i / 255.0f);
        for (int i = 0; i <= 1024; ++i) {
            float v = (float) i / 1024.0f;
            gLin2Srgb[i] = lin2srgb(v);
            gCbrt[i] = cbrtf(v);
        }
    });

    Ctx c{};
    c.w = w;
    c.h = h;
    c.protect = protect == JNI_TRUE;
    c.guard = guard == JNI_TRUE;
    c.strength = std::clamp(strength, 0.0f, 2.0f);
    env->GetFloatArrayRegion(affArr, 0, 12, c.aff);
    env->GetFloatArrayRegion(knotsArr, 0, 27, c.knots);

    jint* px = env->GetIntArrayElements(pixels, nullptr);
    if (px == nullptr) return;
    const int n = w * h;

    // neutralize stats: gray-world means + 0.5/99.5 percentile of Y
    double mr = 0, mg = 0, mb = 0;
    for (int i = 0; i < n; ++i) {
        int32_t p = px[i];
        mr += gSrgb2Lin[(p >> 16) & 0xFF];
        mg += gSrgb2Lin[(p >> 8) & 0xFF];
        mb += gSrgb2Lin[p & 0xFF];
    }
    mr = mr / n + 1e-6;
    mg = mg / n + 1e-6;
    mb = mb / n + 1e-6;
    double mean = (mr + mg + mb) / 3.0;
    c.gr = (float) std::clamp(mean / mr, 0.5, 2.0);
    c.gg = (float) std::clamp(mean / mg, 0.5, 2.0);
    c.gb = (float) std::clamp(mean / mb, 0.5, 2.0);

    const int step = 16;
    std::vector<float> ys;
    ys.reserve((n + step - 1) / step);
    for (int i = 0; i < n; i += step) {
        int32_t p = px[i];
        float y = 0.2126f * gSrgb2Lin[(p >> 16) & 0xFF] * c.gr +
                  0.7152f * gSrgb2Lin[(p >> 8) & 0xFF] * c.gg +
                  0.0722f * gSrgb2Lin[p & 0xFF] * c.gb;
        ys.push_back(y);
    }
    float lo = 0, hi = 0;
    if (!ys.empty()) {
        size_t iLo = (size_t) ((ys.size() - 1) * 0.005f);
        size_t iHi = (size_t) ((ys.size() - 1) * 0.995f);
        std::nth_element(ys.begin(), ys.begin() + iLo, ys.end());
        lo = ys[iLo];
        std::nth_element(ys.begin(), ys.begin() + iHi, ys.end());
        hi = ys[iHi];
    }
    float span = std::max(hi - lo, 1e-4f);
    c.scale = std::clamp((0.98f - 0.005f) / span, 0.25f, 4.0f);
    c.offset = 0.005f - lo * c.scale;

    // continuous sky/skin weight on a 1/8 grid, then blur it there. Sampling it
    // bilinearly per pixel in applyRange keeps the full-res mask (n floats,
    // 48MB at 12MP) out of memory entirely.
    std::vector<float> smallMask;
    if (c.protect) {
        const int sw = std::max(1, (w + kMaskDiv - 1) / kMaskDiv);
        const int sh = std::max(1, (h + kMaskDiv - 1) / kMaskDiv);
        smallMask.assign((size_t) sw * sh, 0.0f);
        for (int sy = 0; sy < sh; ++sy) {
            const int by0 = sy * kMaskDiv;
            const int by1 = std::min(h, by0 + kMaskDiv);
            for (int sx = 0; sx < sw; ++sx) {
                const int bx0 = sx * kMaskDiv;
                const int bx1 = std::min(w, bx0 + kMaskDiv);
                int sr = 0, sg = 0, sb = 0, cnt = 0;
                for (int yy = by0; yy < by1; ++yy) {
                    const int32_t* rowp = px + (size_t) yy * w;
                    for (int xx = bx0; xx < bx1; ++xx) {
                        const int32_t p = rowp[xx];
                        sr += (p >> 16) & 0xFF;
                        sg += (p >> 8) & 0xFF;
                        sb += p & 0xFF;
                        ++cnt;
                    }
                }
                if (cnt <= 0) continue;
                // INTER_AREA averages in 8-bit space first, then converts
                float lab[3];
                rgb2lab((float) ((sr + cnt / 2) / cnt) / 255.0f,
                        (float) ((sg + cnt / 2) / cnt) / 255.0f,
                        (float) ((sb + cnt / 2) / cnt) / 255.0f, lab);
                smallMask[(size_t) sy * sw + sx] = protectScore(lab[0], lab[1], lab[2]);
            }
        }
        // separable Gaussian, sigma=kMaskSigma, BORDER_REFLECT_101 (cv2 default)
        const int rad = (int) ceilf(3.0f * kMaskSigma);
        std::vector<float> kern((size_t) (2 * rad + 1));
        float ksum = 0.0f;
        for (int i = -rad; i <= rad; ++i) {
            const float wgt = expf(-(float) (i * i) / (2.0f * kMaskSigma * kMaskSigma));
            kern[i + rad] = wgt;
            ksum += wgt;
        }
        for (float& wgt : kern) wgt /= ksum;
        auto reflect101 = [](int i, int size) {
            if (size <= 1) return 0;
            while (i < 0 || i >= size) {
                if (i < 0) i = -i;
                if (i >= size) i = 2 * (size - 1) - i;
            }
            return i;
        };
        std::vector<float> tmp(smallMask.size(), 0.0f);
        for (int yy = 0; yy < sh; ++yy) {
            for (int xx = 0; xx < sw; ++xx) {
                float acc = 0.0f;
                for (int i = -rad; i <= rad; ++i)
                    acc += kern[i + rad] * smallMask[(size_t) yy * sw + reflect101(xx + i, sw)];
                tmp[(size_t) yy * sw + xx] = acc;
            }
        }
        for (int yy = 0; yy < sh; ++yy) {
            for (int xx = 0; xx < sw; ++xx) {
                float acc = 0.0f;
                for (int i = -rad; i <= rad; ++i) {
                    const int ry = reflect101(yy + i, sh);
                    acc += kern[i + rad] * tmp[(size_t) ry * sw + xx];
                }
                smallMask[(size_t) yy * sw + xx] = acc;
            }
        }
        c.smallMask = smallMask.data();
        c.sw = sw;
        c.sh = sh;
    }

    unsigned threads = std::thread::hardware_concurrency();
    threads = std::clamp(threads == 0 ? 4u : threads, 1u, 8u);
    int rowsPer = (h + (int) threads - 1) / (int) threads;
    std::vector<std::thread> pool;
    pool.reserve(threads - 1);
    for (unsigned t = 0; t < threads; ++t) {
        int y0 = (int) t * rowsPer;
        int y1 = std::min(h, y0 + rowsPer);
        if (y0 >= y1) break;
        if (t + 1 == threads) {
            applyRange(c, px, y0, y1);
        } else {
            pool.emplace_back(applyRange, std::cref(c), px, y0, y1);
        }
    }
    for (auto& th : pool) th.join();

    // 3x3 edge-preserving Y smoothing (approximates the PC bilateral 5/4/2):
    // only neighbours within a luma threshold contribute, so banding flattens
    // while real edges stay put.
    if (smooth == JNI_TRUE) {
        std::vector<float> lum(n, 0.0f), sm(n, 0.0f);
        for (int i = 0; i < n; ++i) {
            int32_t p = px[i];
            lum[i] = (0.2126f * ((p >> 16) & 0xFF) + 0.7152f * ((p >> 8) & 0xFF) +
                      0.0722f * (p & 0xFF)) / 255.0f;
        }
        auto smoothRows = [&](int y0, int y1) {
            for (int yy = y0; yy < y1; ++yy) {
                int row = yy * w;
                for (int xx = 0; xx < w; ++xx) {
                    float c = lum[row + xx];
                    float acc = 0.0f;
                    int cnt = 0;
                    for (int dy = -1; dy <= 1; ++dy) {
                        int sy = std::clamp(yy + dy, 0, h - 1);
                        for (int dx = -1; dx <= 1; ++dx) {
                            int sx = std::clamp(xx + dx, 0, w - 1);
                            float v = lum[sy * w + sx];
                            if (fabsf(v - c) < 0.04f) {
                                acc += v;
                                ++cnt;
                            }
                        }
                    }
                    sm[row + xx] = cnt > 0 ? acc / (float) cnt : c;
                }
            }
        };
        std::vector<std::thread> spool;
        int rows2 = (h + 3) / 4;
        for (int t = 0; t < 4; ++t) {
            int y0 = t * rows2;
            int y1 = std::min(h, y0 + rows2);
            if (y0 >= y1) break;
            if (t == 3) smoothRows(y0, y1);
            else spool.emplace_back(smoothRows, y0, y1);
        }
        for (auto& th : spool) th.join();

        for (int i = 0; i < n; ++i) {
            float ratio = sm[i] / std::max(lum[i], 1e-4f);
            int32_t p = px[i];
            int r = std::clamp((int) lroundf(((p >> 16) & 0xFF) * ratio), 0, 255);
            int g = std::clamp((int) lroundf(((p >> 8) & 0xFF) * ratio), 0, 255);
            int b = std::clamp((int) lroundf((p & 0xFF) * ratio), 0, 255);
            if (c.guard) {
                r = std::min(r, 250);
                g = std::min(g, 250);
                b = std::min(b, 250);
            }
            px[i] = (int32_t) (0xFF000000u | ((uint32_t) r << 16) |
                               ((uint32_t) g << 8) | (uint32_t) b);
        }
    }

    env->ReleaseIntArrayElements(pixels, px, 0);
}

// ---------------------------------------------------------------------------
// Full-resolution band path for M6 model styles.
//
// The style itself (neutralize -> aff -> knots -> protect -> guard) is purely
// per-pixel, so it can run on a 512-row band. Two things are global and must be
// made once for the whole image instead of per band:
//   * neutralize: gray-world gains + 0.5/99.5 percentile of Y;
//   * protectWeight: the 1/8-grid sky/skin mask.
// statsNative() computes the former on a low-res sample. The mask is rebuilt
// per band from the *full-res* pixels of the decoded window (a 1/8 grid keeps
// it cheap), which is why it stays identical to the whole-image mask instead of
// being blurred at a different physical scale. The band decoded by the caller
// carries >= 56 px of halo on both sides (7 grid rows + 1 smoothing row), so the
// blurred mask and the 3x3 smoothing are exact for every core row.
// ---------------------------------------------------------------------------
namespace {

void initLutsOnce() {
    std::call_once(gLutOnce, []() {
        for (int i = 0; i < 256; ++i) gSrgb2Lin[i] = srgb2linScalar((float) i / 255.0f);
        for (int i = 0; i <= 1024; ++i) {
            float v = (float) i / 1024.0f;
            gLin2Srgb[i] = lin2srgb(v);
            gCbrt[i] = cbrtf(v);
        }
    });
}

// Same as the inline block in applyNative, but on the decoded window. `h` must
// start at a multiple of kMaskDiv in image coordinates so the cell grid aligns.
void buildMaskWindow(const int32_t* px, int w, int h,
                     std::vector<float>& mask, int& swOut, int& shOut) {
    const int sw = std::max(1, (w + kMaskDiv - 1) / kMaskDiv);
    const int sh = std::max(1, (h + kMaskDiv - 1) / kMaskDiv);
    mask.assign((size_t) sw * sh, 0.0f);
    for (int sy = 0; sy < sh; ++sy) {
        const int by0 = sy * kMaskDiv;
        const int by1 = std::min(h, by0 + kMaskDiv);
        for (int sx = 0; sx < sw; ++sx) {
            const int bx0 = sx * kMaskDiv;
            const int bx1 = std::min(w, bx0 + kMaskDiv);
            int sr = 0, sg = 0, sb = 0, cnt = 0;
            for (int yy = by0; yy < by1; ++yy) {
                const int32_t* rowp = px + (size_t) yy * w;
                for (int xx = bx0; xx < bx1; ++xx) {
                    const int32_t p = rowp[xx];
                    sr += (p >> 16) & 0xFF;
                    sg += (p >> 8) & 0xFF;
                    sb += p & 0xFF;
                    ++cnt;
                }
            }
            if (cnt <= 0) continue;
            float lab[3];
            rgb2lab((float) ((sr + cnt / 2) / cnt) / 255.0f,
                    (float) ((sg + cnt / 2) / cnt) / 255.0f,
                    (float) ((sb + cnt / 2) / cnt) / 255.0f, lab);
            mask[(size_t) sy * sw + sx] = protectScore(lab[0], lab[1], lab[2]);
        }
    }
    const int rad = (int) ceilf(3.0f * kMaskSigma);
    std::vector<float> kern((size_t) (2 * rad + 1));
    float ksum = 0.0f;
    for (int i = -rad; i <= rad; ++i) {
        const float wgt = expf(-(float) (i * i) / (2.0f * kMaskSigma * kMaskSigma));
        kern[i + rad] = wgt;
        ksum += wgt;
    }
    for (float& wgt : kern) wgt /= ksum;
    auto reflect101 = [](int i, int size) {
        if (size <= 1) return 0;
        while (i < 0 || i >= size) {
            if (i < 0) i = -i;
            if (i >= size) i = 2 * (size - 1) - i;
        }
        return i;
    };
    std::vector<float> tmp(mask.size(), 0.0f);
    for (int yy = 0; yy < sh; ++yy) {
        for (int xx = 0; xx < sw; ++xx) {
            float acc = 0.0f;
            for (int i = -rad; i <= rad; ++i)
                acc += kern[i + rad] * mask[(size_t) yy * sw + reflect101(xx + i, sw)];
            tmp[(size_t) yy * sw + xx] = acc;
        }
    }
    for (int yy = 0; yy < sh; ++yy) {
        for (int xx = 0; xx < sw; ++xx) {
            float acc = 0.0f;
            for (int i = -rad; i <= rad; ++i) {
                const int ry = reflect101(yy + i, sh);
                acc += kern[i + rad] * tmp[(size_t) ry * sw + xx];
            }
            mask[(size_t) yy * sw + xx] = acc;
        }
    }
    swOut = sw;
    shOut = sh;
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_leicamoments_app_AdaptiveColorNet_statsNative(
        JNIEnv* env, jobject, jintArray pixels, jint w, jint h) {
    if (pixels == nullptr || w <= 0 || h <= 0) return nullptr;
    initLutsOnce();
    jint* px = env->GetIntArrayElements(pixels, nullptr);
    if (px == nullptr) return nullptr;
    const int n = w * h;

    double mr = 0, mg = 0, mb = 0;
    for (int i = 0; i < n; ++i) {
        int32_t p = px[i];
        mr += gSrgb2Lin[(p >> 16) & 0xFF];
        mg += gSrgb2Lin[(p >> 8) & 0xFF];
        mb += gSrgb2Lin[p & 0xFF];
    }
    mr = mr / n + 1e-6;
    mg = mg / n + 1e-6;
    mb = mb / n + 1e-6;
    const double mean = (mr + mg + mb) / 3.0;
    const float gr = (float) std::clamp(mean / mr, 0.5, 2.0);
    const float gg = (float) std::clamp(mean / mg, 0.5, 2.0);
    const float gb = (float) std::clamp(mean / mb, 0.5, 2.0);

    const int step = std::max(1, n / 800000);
    std::vector<float> ys;
    ys.reserve((size_t) ((n + step - 1) / step));
    for (int i = 0; i < n; i += step) {
        int32_t p = px[i];
        ys.push_back(0.2126f * gSrgb2Lin[(p >> 16) & 0xFF] * gr +
                     0.7152f * gSrgb2Lin[(p >> 8) & 0xFF] * gg +
                     0.0722f * gSrgb2Lin[p & 0xFF] * gb);
    }
    float lo = 0, hi = 0;
    if (!ys.empty()) {
        size_t iLo = (size_t) ((ys.size() - 1) * 0.005f);
        size_t iHi = (size_t) ((ys.size() - 1) * 0.995f);
        std::nth_element(ys.begin(), ys.begin() + iLo, ys.end());
        lo = ys[iLo];
        std::nth_element(ys.begin(), ys.begin() + iHi, ys.end());
        hi = ys[iHi];
    }
    const float span = std::max(hi - lo, 1e-4f);
    const float scale = std::clamp((0.98f - 0.005f) / span, 0.25f, 4.0f);
    const float offset = 0.005f - lo * scale;

    const float out[5] = {gr, gg, gb, scale, offset};
    env->ReleaseIntArrayElements(pixels, px, 0);
    jfloatArray arr = env->NewFloatArray(5);
    if (arr != nullptr) env->SetFloatArrayRegion(arr, 0, 5, out);
    return arr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_leicamoments_app_AdaptiveColorNet_applyPreparedNative(
        JNIEnv* env, jobject, jintArray pixels, jint w, jint extH,
        jfloatArray affArr, jfloatArray knotsArr, jboolean protect, jboolean guard,
        jfloat strength, jfloat gr, jfloat gg, jfloat gb, jfloat scale, jfloat offset,
        jint extTop, jint globalH, jint coreStart, jint coreRows, jboolean smooth) {
    if (pixels == nullptr || w <= 0 || extH <= 0 || affArr == nullptr || knotsArr == nullptr ||
        globalH <= 0 || extTop < 0 || extTop >= globalH || coreRows <= 0 ||
        coreStart < 0 || coreStart + coreRows > extH || (extTop % kMaskDiv) != 0) {
        return JNI_FALSE;
    }
    initLutsOnce();

    Ctx c{};
    c.w = w;
    c.h = extH;
    c.protect = protect == JNI_TRUE;
    c.guard = guard == JNI_TRUE;
    c.strength = std::clamp(strength, 0.0f, 2.0f);
    env->GetFloatArrayRegion(affArr, 0, 12, c.aff);
    env->GetFloatArrayRegion(knotsArr, 0, 27, c.knots);
    c.gr = gr;
    c.gg = gg;
    c.gb = gb;
    c.scale = scale;
    c.offset = offset;
    c.yOffset = extTop;
    c.globalH = globalH;
    c.shFull = std::max(1, (globalH + kMaskDiv - 1) / kMaskDiv);
    c.maskTop = extTop / kMaskDiv;

    jint* px = env->GetIntArrayElements(pixels, nullptr);
    if (px == nullptr) return JNI_FALSE;

    std::vector<float> smallMask;
    if (c.protect) {
        int sw = 0, sh = 0;
        buildMaskWindow(px, w, extH, smallMask, sw, sh);
        c.smallMask = smallMask.data();
        c.sw = sw;
        c.sh = sh;
    }

    unsigned threads = std::thread::hardware_concurrency();
    threads = std::clamp(threads == 0 ? 4u : threads, 1u, 8u);
    const int rowsPer = (extH + (int) threads - 1) / (int) threads;
    std::vector<std::thread> pool;
    pool.reserve(threads - 1);
    for (unsigned t = 0; t < threads; ++t) {
        const int y0 = (int) t * rowsPer;
        const int y1 = std::min(extH, y0 + rowsPer);
        if (y0 >= y1) break;
        if (t + 1 == threads) applyRange(c, px, y0, y1);
        else pool.emplace_back(applyRange, std::cref(c), px, y0, y1);
    }
    for (auto& th : pool) th.join();

    if (smooth == JNI_TRUE) {
        const int cs = std::clamp((int) coreStart, 0, extH);
        const int ce = std::clamp((int) coreStart + (int) coreRows, cs, extH);
        std::vector<float> lum((size_t) w * extH, 0.0f);
        std::vector<float> sm((size_t) w * extH, 0.0f);
        for (int i = 0; i < w * extH; ++i) {
            int32_t p = px[i];
            lum[i] = (0.2126f * ((p >> 16) & 0xFF) + 0.7152f * ((p >> 8) & 0xFF) +
                      0.0722f * (p & 0xFF)) / 255.0f;
        }
        auto smoothRows = [&](int y0, int y1) {
            for (int yy = y0; yy < y1; ++yy) {
                const int row = yy * w;
                for (int xx = 0; xx < w; ++xx) {
                    const float lumC = lum[row + xx];
                    float acc = 0.0f;
                    int cnt = 0;
                    for (int dy = -1; dy <= 1; ++dy) {
                        const int gy = std::clamp(extTop + yy + dy, 0, globalH - 1) - extTop;
                        for (int dx = -1; dx <= 1; ++dx) {
                            const int sx = std::clamp(xx + dx, 0, w - 1);
                            const float v = lum[(size_t) gy * w + sx];
                            if (fabsf(v - lumC) < 0.04f) {
                                acc += v;
                                ++cnt;
                            }
                        }
                    }
                    sm[row + xx] = cnt > 0 ? acc / (float) cnt : lumC;
                }
            }
        };
        const int coreRowsN = ce - cs;
        const int chunk = (coreRowsN + 3) / 4;
        std::vector<std::thread> spool;
        for (int t = 0; t < 4; ++t) {
            const int y0 = cs + t * chunk;
            const int y1 = std::min(ce, y0 + chunk);
            if (y0 >= y1) break;
            if (t == 3) smoothRows(y0, y1);
            else spool.emplace_back(smoothRows, y0, y1);
        }
        for (auto& th : spool) th.join();

        for (int yy = cs; yy < ce; ++yy) {
            const int row = yy * w;
            for (int xx = 0; xx < w; ++xx) {
                const float lumC = std::max(lum[row + xx], 1e-4f);
                const float ratio = sm[row + xx] / lumC;
                int32_t p = px[row + xx];
                int r = std::clamp((int) lroundf(((p >> 16) & 0xFF) * ratio), 0, 255);
                int g = std::clamp((int) lroundf(((p >> 8) & 0xFF) * ratio), 0, 255);
                int b = std::clamp((int) lroundf((p & 0xFF) * ratio), 0, 255);
                if (c.guard) {
                    r = std::min(r, 250);
                    g = std::min(g, 250);
                    b = std::min(b, 250);
                }
                px[row + xx] = (int32_t) (0xFF000000u | ((uint32_t) r << 16) |
                                          ((uint32_t) g << 8) | (uint32_t) b);
            }
        }
    }

    env->ReleaseIntArrayElements(pixels, px, 0);
    return JNI_TRUE;
}

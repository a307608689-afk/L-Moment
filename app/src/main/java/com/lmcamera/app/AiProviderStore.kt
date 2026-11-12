package com.lmcamera.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AI 服务（OpenAI 兼容）配置。
 *
 * 与 [SettingsStore] 完全隔离：AI 的一切配置都存在独立的 prefs 文件 `lm_ai` 里，
 * key 一律 `ai_` 前缀，绝不触碰用户既有的图片/色彩设置。
 *
 * [apiKey] 在内存里是明文，落盘前走 [AiSecret]（AndroidKeyStore AES/GCM）加密，
 * 因而 **这个文件不会把 key 明文写进 SharedPreferences**。
 */
data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val vision: Boolean,
    /**
     * 深度思考强度。新模型普遍无法真正关闭，因此默认取「最低档」；
     * 老配置缺该字段时也按最低档解析（升级即生效，不需要重新添加）。
     */
    val thinking: Int = THINK_LOW
) {
    companion object {
        const val THINK_DEFAULT = 0
        const val THINK_LOW = 1
        const val THINK_OFF = 2

        fun thinkingName(v: Int): String = when (v) {
            THINK_DEFAULT -> "服务默认"
            THINK_OFF -> "关闭"
            else -> "最低档"
        }
    }
    /** 规范化后的 chat completions 地址（用户可只填到 /v1）。 */
    val chatUrl: String
        get() {
            val base = baseUrl.trim().trimEnd('/')
            if (base.isEmpty()) return ""
            return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }

    val modelsUrl: String
        get() {
            val base = baseUrl.trim().trimEnd('/')
            if (base.isEmpty()) return ""
            return if (base.endsWith("/chat/completions")) base.removeSuffix("/chat/completions") + "/models" else "$base/models"
        }

    val display: String get() = if (model.isBlank()) name else "$name · $model"
}

object AiProviderStore {

    private const val FILE = "lm_ai"
    private const val KEY_PROVIDERS = "ai_providers"
    private const val KEY_CURRENT = "ai_current_provider"
    private const val KEY_ONBOARDED = "ai_onboarded"

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- Provider ----

    fun providers(c: Context): List<AiProvider> {
        val raw = prefs(c).getString(KEY_PROVIDERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<AiProvider>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    AiProvider(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        baseUrl = o.optString("baseUrl"),
                        model = o.optString("model"),
                        apiKey = AiSecret.decrypt(o.optString("keyEnc")),
                        vision = o.optBoolean("vision", false),
                        // 老配置没有该字段：直接按最低档生效（用户升级后即省 token）
                        thinking = o.optInt("thinking", AiProvider.THINK_LOW).coerceIn(0, 2)
                    )
                )
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun currentProvider(c: Context): AiProvider? {
        val list = providers(c)
        if (list.isEmpty()) return null
        val id = prefs(c).getString(KEY_CURRENT, null)
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    fun setCurrentProvider(c: Context, id: String) {
        prefs(c).edit().putString(KEY_CURRENT, id).apply()
    }

    /** 新增或按 id 覆盖一个 provider（只影响 AI 自己的配置，不涉用户图片设置）。 */
    fun saveProvider(c: Context, p: AiProvider) {
        val list = providers(c).toMutableList()
        val idx = list.indexOfFirst { it.id == p.id }
        if (idx >= 0) list[idx] = p else list.add(p)
        writeProviders(c, list)
        if (prefs(c).getString(KEY_CURRENT, null).isNullOrEmpty()) {
            prefs(c).edit().putString(KEY_CURRENT, p.id).apply()
        }
    }

    fun deleteProvider(c: Context, id: String) {
        val list = providers(c).filterNot { it.id == id }
        writeProviders(c, list)
        if (prefs(c).getString(KEY_CURRENT, null) == id) {
            prefs(c).edit().putString(KEY_CURRENT, list.firstOrNull()?.id ?: "").apply()
        }
    }

    private fun writeProviders(c: Context, list: List<AiProvider>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("baseUrl", p.baseUrl)
                put("model", p.model)
                put("keyEnc", AiSecret.encrypt(p.apiKey))
                put("vision", p.vision)
                put("thinking", p.thinking)
            })
        }
        prefs(c).edit().putString(KEY_PROVIDERS, arr.toString()).apply()
    }

    fun newProviderId(): String = "p_" + System.currentTimeMillis().toString(36)

    // ---- 引导标记 ----

    fun onboardingShown(c: Context): Boolean = prefs(c).getBoolean(KEY_ONBOARDED, false)

    fun setOnboardingShown(c: Context) {
        prefs(c).edit().putBoolean(KEY_ONBOARDED, true).apply()
    }
}

/**
 * 极小的 Keystore AES/GCM 加解密器。
 *
 * 为什么不用 `androidx.security:security-crypto`：本工程依赖表只有 7 条，
 * 为了一个 key 的保存引入一整个库不划算（也避免构建期拉包失败），
 * 这里用系统自带的 AndroidKeyStore 达成同等目标——密钥不出 TEE/StrongBox，
 * 落盘只有密文；解密失败（换机/清密钥）返回空串，用户重填即可。
 */
internal object AiSecret {

    private const val KEY_ALIAS = "lm_ai_secret"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                gen.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                gen.generateKey()
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val key = secretKey() ?: return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(ct, 0, out, iv.size, ct.size)
            "v1:" + Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (t: Throwable) {
            ""
        }
    }

    fun decrypt(enc: String): String {
        if (enc.isEmpty()) return ""
        if (!enc.startsWith("v1:")) return ""
        val key = secretKey() ?: return ""
        return try {
            val bytes = Base64.decode(enc.substring(3), Base64.NO_WRAP)
            if (bytes.size <= IV_LEN) return ""
            val iv = ByteArray(IV_LEN)
            System.arraycopy(bytes, 0, iv, 0, IV_LEN)
            val ct = ByteArray(bytes.size - IV_LEN)
            System.arraycopy(bytes, IV_LEN, ct, 0, ct.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        }
    }
}

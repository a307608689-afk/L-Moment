package com.lmcamera.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AI 面板的参考图附件缓存：**选图即落地私有缓存**，与 SAF 临时权限和进程生命周期解耦。
 *
 * 为什么不走「存 URI、发送时再读」：
 * - `GetMultipleContents` 的读授权只在当前会话有效，进程被杀重进后 URI 读不了；
 * - [MainActivity] 已 `configChanges` 免疫旋转，唯一丢附件的场景就是进程回收——
 *   本缓存把拟合输入（240px JPEG，与 [ModelRefStore.thumbJpeg] 同规格，即 M6 编码链
 *   的既有输入）落地到 `cacheDir/ai_ref/`，重进后凭 `pending.json` 清单原样恢复。
 *
 * 生命周期：
 * - [save]：选图后逐张提取缩略落盘 + 重写清单（后台线程调用）；
 * - [restore]：AI 面板打开时读清单，文件仍在的条目原样恢复；
 * - [remove]：用户点附件条上的 × 删单张；
 * - [clear]：发送成功（拟合完成）或 App 启动清残留时调用，删整个目录内容。
 */
object RefAttachmentCache {

    /** 一张待发送的参考图附件：[thumbFile] 就是拟合输入（最长边 240px JPEG90）。 */
    data class Pending(val id: String, val thumbFile: File, val sourceName: String)

    /** 一次性上传上限（用户拍板：一次性上限五张） */
    const val MAX_COUNT = 5

    private const val DIR = "ai_ref"
    private const val LIST = "pending.json"

    private fun dir(ctx: Context): File = File(ctx.applicationContext.cacheDir, DIR)

    private fun listFile(ctx: Context): File = File(dir(ctx), LIST)

    /**
     * 选图后落盘（**后台线程调用**，解码/写盘是 IO）。超出 [MAX_COUNT] 的部分截断。
     * 返回落盘后的全部附件（含此前已挂的）。
     */
    fun save(ctx: Context, uris: List<Uri>): List<Pending> {
        val items = ArrayList(restore(ctx))
        var dropped = 0
        for (u in uris) {
            if (items.size >= MAX_COUNT) { dropped++; continue }
            val jpeg = ModelRefStore.thumbJpeg(ctx, u) ?: continue
            val d = dir(ctx)
            if (!d.isDirectory) d.mkdirs()
            val id = "r_" + System.currentTimeMillis().toString(36) + "_" + (items.size + 1)
            val f = File(d, "$id.jpg")
            try {
                f.writeBytes(jpeg)
            } catch (t: Throwable) {
                DiagLog.w("AiRef", "附件落盘失败: ${t.message}")
                continue
            }
            items.add(Pending(id, f, ""))
        }
        if (dropped > 0) DiagLog.w("AiRef", "参考图超上限，丢弃 $dropped 张")
        writeList(ctx, items)
        return items
    }

    /** 读清单恢复（文件已被系统清掉的条目自动剔除并回写）。 */
    fun restore(ctx: Context): List<Pending> {
        val out = ArrayList<Pending>()
        try {
            val f = listFile(ctx)
            if (!f.isFile) return out
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val file = File(o.optString("file"))
                if (!file.isFile) continue
                out.add(Pending(o.optString("id"), file, o.optString("name")))
            }
        } catch (t: Throwable) {
            DiagLog.w("AiRef", "附件清单恢复失败: ${t.message}")
        }
        return out
    }

    /** 移除单张（点附件条 ×），文件与清单同步删。 */
    fun remove(ctx: Context, id: String) {
        val all = restore(ctx)
        all.firstOrNull { it.id == id }?.thumbFile?.delete()
        writeList(ctx, all.filterNot { it.id == id })
    }

    /** 清空（发送成功 / App 启动清残留）。 */
    fun clear(ctx: Context) {
        try {
            val d = dir(ctx)
            d.listFiles()?.forEach { it.delete() }
        } catch (t: Throwable) {
            DiagLog.w("AiRef", "附件缓存清理失败: ${t.message}")
        }
    }

    private fun writeList(ctx: Context, items: List<Pending>) {
        try {
            val d = dir(ctx)
            if (!d.isDirectory) d.mkdirs()
            val arr = JSONArray()
            items.forEach { p ->
                arr.put(JSONObject().apply {
                    put("id", p.id)
                    put("file", p.thumbFile.absolutePath)
                    put("name", p.sourceName)
                })
            }
            listFile(ctx).writeText(arr.toString())
        } catch (t: Throwable) {
            DiagLog.w("AiRef", "附件清单写入失败: ${t.message}")
        }
    }
}

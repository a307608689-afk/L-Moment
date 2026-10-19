package com.leicamoments.app

import android.content.Context
import com.codex.leicaapp.AlexaLut
import java.io.File

/**
 * 模型色彩的 LUT 缓存与 θ 快照（M5「游标懒烘」）。
 *
 * 版本元数据与 LUT 文件解耦：一条色彩可以有任意多个版本，
 * 但磁盘上最多留 [MAX_KEEP] 份 LUT（按最后使用时间），其余删掉。
 * 被删掉的版本不是「丢失」——[ensure] 会按该版本的 θ 快照重新生成，
 * 没有快照（旧存档 / 导入包）时退回「按池前缀重聚合」。
 * 这同时就是「回退重烘」：回退到旧版本后，第一次在主界面应用该色彩时
 * 才付一次烘焙成本。
 *
 * 所有方法都可能读池/烘 LUT，**只能在后台线程调用**。
 */
object ModelLutStore {

    /** 每条色彩最多保留几份已烘 LUT（当前版本 + 最近用过的上一版）。 */
    const val MAX_KEEP = 2

    private const val THETA_DIR = "theta"

    fun binFile(ctx: Context, version: StyleVersion): File =
        File(AlexaLut.customDir(ctx), version.lutFile)

    fun has(ctx: Context, version: StyleVersion): Boolean {
        if (version.lutFile.isEmpty()) return false
        val f = binFile(ctx, version)
        return f.isFile && f.length() > 0
    }

    /**
     * 确保该版本的 LUT 可用。返回 false 表示快照/池缺失或烘焙失败，
     * 调用方必须按「色彩不可用」处理（不要静默出原图后当成功）。
     *
     * 串行化：缩略图、预览、导出各自在 executor 上跑，可能同时命中同一个被回收的
     * 版本；不加锁会两条线程烘同一个文件（白烧 CPU，且写文件可能互相踩）。
     */
    @Synchronized
    fun ensure(ctx: Context, def: CustomStyleDef, version: StyleVersion): Boolean {
        if (has(ctx, version)) {
            // LRU 按 mtime 排序：用过一次就把时间戳顶上去
            runCatching { binFile(ctx, version).setLastModified(System.currentTimeMillis()) }
            return true
        }
        if (version.lutFile.isEmpty()) return false

        val snap = readTheta(ctx, version)
        if (snap != null) {
            val t0 = System.currentTimeMillis()
            if (CustomLutBaker.bakeFromSnapshotTo(ctx, snap, version.lutFile)) {
                onRebuilt(ctx, def, version, "θ 快照", t0)
                return true
            }
            DiagLog.w("ModelLut", "θ 快照重烘失败，退回池前缀 ${def.id}/${version.id}")
        }

        if (!CustomStyleStore.ensureSeeds(def)) {
            DiagLog.w("ModelLut", "池读取失败，无法重建 ${def.id}/${version.id}")
            return false
        }
        if (def.seeds.isEmpty()) {
            DiagLog.w("ModelLut", "池为空，无法重建 ${def.id}/${version.id}")
            return false
        }
        val n = version.seedCount.coerceIn(1, def.seeds.size)
        val seeds = def.seeds.subList(0, n).toList()
        val t0 = System.currentTimeMillis()
        if (!CustomLutBaker.bake(ctx, seeds, version.lutFile)) return false
        onRebuilt(ctx, def, version, "池前缀 $n 张", t0)
        return true
    }

    private fun onRebuilt(ctx: Context, def: CustomStyleDef, version: StyleVersion, how: String, t0: Long) {
        // 同名文件重烘后，AlexaLut 里可能还留着「加载失败」的 ATTEMPTED 标记
        AlexaLut.invalidate(AlexaLut.fileRef(version.lutFile))
        DiagLog.i(
            "ModelLut",
            "重建 ${def.id}/${version.id}（$how）${System.currentTimeMillis() - t0}ms"
        )
        // 保护集必须含「没有 θ 的版本」：它们被删就再也重建不出来（旧存档/导入包）
        val keep = HashSet<String>()
        keep.add(version.lutFile)
        val versions = runCatching { ArrayList(def.versions) }.getOrDefault(ArrayList())
        versions.forEach { if (it.thetaFile.isEmpty() && it.lutFile.isNotEmpty()) keep.add(it.lutFile) }
        versions.firstOrNull { it.id == def.currentVersionId }?.let { keep.add(it.lutFile) }
        evict(ctx, def.id, keep)
    }

    /**
     * 保留 [keep] + 最近用过的若干份，其余删掉。
     *
     * 只动本条色彩自己的文件（前缀 `lut_<styleId>_`），不会误删别的色彩/内置 assets；
     * θ 快照在独立目录，不参与回收（约 19 KB/版本，是版本元数据的一部分）。
     */
    fun evict(ctx: Context, styleId: String, keep: Set<String>) {
        val dir = AlexaLut.customDir(ctx)
        val prefix = "lut_${styleId}_"
        val all = dir.listFiles { f ->
            f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".bin")
        } ?: return
        val kept = all.filter { it.name in keep }
        val rest = all.filter { it.name !in keep }.sortedByDescending { it.lastModified() }
        val room = (MAX_KEEP - kept.size).coerceAtLeast(0)
        rest.drop(room).forEach { f ->
            AlexaLut.invalidate(AlexaLut.fileRef(f.name))
            if (!f.delete()) DiagLog.w("ModelLut", "回收失败 ${f.name}")
        }
    }

    /** 当前缓存里的 LUT 份数（诊断用）。 */
    fun cachedCount(ctx: Context, styleId: String): Int {
        val prefix = "lut_${styleId}_"
        return AlexaLut.customDir(ctx).listFiles { f ->
            f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".bin")
        }?.size ?: 0
    }

    // ---- θ 快照 ----

    /** θ 快照目录（备份/导入要整目录搬运，故公开）。 */
    fun thetaDir(ctx: Context): File = File(ctx.filesDir, THETA_DIR)

    fun thetaFileOf(ctx: Context, name: String): File = File(thetaDir(ctx), name)

    /**
     * 写入 θ 快照，返回文件名（存进 [StyleVersion.thetaFile]）。失败返回空串。
     * 快照约 19 KB，先写临时文件再改名，避免半个文件被当成有效快照。
     */
    fun writeTheta(ctx: Context, styleId: String, versionId: String, snapshot: ByteArray): String {
        val name = "${styleId}_${versionId}.bin"
        return try {
            val dir = thetaDir(ctx)
            dir.mkdirs()
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(snapshot)
            if (target.exists() && !target.delete()) return ""
            if (!tmp.renameTo(target)) {
                target.writeBytes(snapshot)
                tmp.delete()
            }
            name
        } catch (t: Throwable) {
            DiagLog.w("ModelLut", "θ 快照落盘失败 $name: ${t.message}")
            ""
        }
    }

    private fun readTheta(ctx: Context, version: StyleVersion): ByteArray? {
        if (version.thetaFile.isEmpty()) return null
        return try {
            val f = thetaFileOf(ctx, version.thetaFile)
            if (f.isFile) f.readBytes() else null
        } catch (t: Throwable) {
            DiagLog.w("ModelLut", "θ 快照读取失败 ${version.thetaFile}: ${t.message}")
            null
        }
    }

    /** 删除一条色彩的全部 LUT + θ 快照（删色彩时调用）。 */
    fun purge(ctx: Context, styleId: String) {
        val lutPrefix = "lut_${styleId}_"
        AlexaLut.customDir(ctx).listFiles { f ->
            f.isFile && f.name.startsWith(lutPrefix)
        }?.forEach { f ->
            AlexaLut.invalidate(AlexaLut.fileRef(f.name))
            f.delete()
        }
        thetaDir(ctx).listFiles { f -> f.isFile && f.name.startsWith("${styleId}_") }
            ?.forEach { it.delete() }
    }
}

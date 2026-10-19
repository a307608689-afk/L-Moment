package com.leicamoments.app

import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 轻量诊断日志：纯内存环形缓冲（500 条封顶，自动覆盖最旧），不落盘、不联网。
 * 隐私边界：只记录 App 内部事件/异常堆栈/无身份识别性的环境信息，
 * 不写图片内容、EXIF 用户数据、完整文件路径。
 * 查看入口：顶栏标题长按 → 日志对话框（复制/清空）。
 */
object DiagLog {

    private const val CAP = 500
    private val lines = ArrayDeque<String>(CAP)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appVersion: String = "?"

    /** 由 MainActivity.onCreate 注入版本号（避免在工具类里碰隐藏 API） */
    fun init(versionName: String) {
        appVersion = versionName
        markSessionStart()
    }

    /** 环境信息头（对话框展示与复制时附在最前，便于定位问题环境） */
    private fun envHeader(): String {
        val ver = appVersion
        val mem = Runtime.getRuntime()
        return buildString {
            append("L-Moment v").append(ver)
            append(" | ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" | Android ").append(Build.VERSION.RELEASE)
            append(" (API ").append(Build.VERSION.SDK_INT).append(')')
            append(" | ").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
            append(" | heap ").append(mem.totalMemory() / 1048576).append('/')
                .append(mem.maxMemory() / 1048576).append("MB")
            append('\n')
        }
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String) = write("W", tag, msg, null)
    fun e(tag: String, msg: String, tr: Throwable? = null) = write("E", tag, msg, tr)

    private fun write(level: String, tag: String, msg: String, tr: Throwable?) {
        val line = buildString {
            append(fmt.format(Date()))
            append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            if (tr != null) {
                append('\n').append(tr.javaClass.name)
                append(": ").append(tr.message ?: "")
                val st = tr.stackTrace.take(8)
                for (f in st) append("\n    at ").append(f)
            }
        }
        synchronized(lines) {
            // 多行条目（带堆栈）按行拆分计数，保证缓冲不超总行数
            val n = line.count { it == '\n' } + 1
            repeat(n) { if (lines.size >= CAP) lines.removeFirst() }
            lines.addLast(line)
        }
        // logcat 镜像：仍不落盘、不联网，只为 adb 抓取（真机排查用）
        try {
            Log.println(
                when (level) {
                    "E" -> Log.ERROR
                    "W" -> Log.WARN
                    else -> Log.INFO
                },
                "LMDiag/$tag", if (tr == null) msg else "$msg\n$tr"
            )
        } catch (_: Throwable) {
        }
    }

    /** 注入上次崩溃记录（启动时从磁盘读取后调用，内容已在 installCrashBox 落盘） */
    fun recordCrash(text: String) {
        val head = text.lineSequence().take(20).joinToString("\n")
        write("E", "CRASH", "上次会话异常退出\n$head", null)
    }

    /** 当前会话启动标记 */
    fun markSessionStart() {
        write("I", "App", "会话开始", null)
    }

    /** 全量快照：环境头 + 全部日志（对话框展示/复制用） */
    fun snapshot(): String {
        val body = synchronized(lines) { lines.joinToString("\n") }
        return envHeader() + body
    }

    fun clear() = synchronized(lines) { lines.clear() }
}

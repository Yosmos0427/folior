package com.yussy.folior

import android.app.Application
import java.io.File

/**
 * 全局崩溃日志：未捕获异常写入 files/crash/（保留最近 5 份），
 * 之后交回系统默认处理（进程照常终止）。
 * 文件可用文件管理器取出回报，便于定位用户遇到的闪退。
 */
class FoliorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val dir = File(filesDir, "crash").apply { mkdirs() }
                val now = System.currentTimeMillis()
                File(dir, "crash-$now.txt").writeText(buildString {
                    append("app: ").append(packageName)
                        .append("  time(ms): ").append(now).append('\n')
                    append("thread: ").append(t.name).append('\n')
                    append(android.util.Log.getStackTraceString(e))
                })
                // 只保留最近 5 份
                dir.listFiles { f -> f.name.startsWith("crash-") }
                    ?.sortedByDescending { it.name }
                    ?.drop(5)
                    ?.forEach { it.delete() }
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }
}

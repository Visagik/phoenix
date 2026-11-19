package com.phoenix.app

import android.content.Context

/** Строка о памяти процесса для журнала: по журналу 15–19.11 процесс убивали «из-за нехватки памяти» (до 1446 МБ), а что растёт — неизвестно. */
object MemInfo {
    fun line(tag: String): String = try {
        val rt = Runtime.getRuntime()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) / MB
        val maxMb = rt.maxMemory() / MB
        val nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / MB
        val info = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
        "MEM: $tag — куча Java $heapMb из $maxMb МБ, нативная $nativeMb МБ, всего PSS ${info.totalPss / 1024} МБ"
    } catch (e: Throwable) {
        "MEM: $tag — не удалось измерить (${e.javaClass.simpleName})"
    }

    fun log(context: Context, tag: String) {
        try { Storage(context).log(line(tag)) } catch (_: Throwable) {}
    }

    private const val MB = 1024L * 1024L
}

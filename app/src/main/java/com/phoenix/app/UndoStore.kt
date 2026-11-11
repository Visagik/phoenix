package com.phoenix.app

import java.io.File

/**
 * «Вернуть»: перед автоматическим изменением (аудит источников 1-го и 15-го, авто-приоритеты) делается копия затронутых файлов.
 * В течение суток изменение можно откатить одной кнопкой. Хранится не больше [KEEP] последних копий.
 */
class UndoStore(private val dir: File) {
    data class Entry(val id: String, val label: String, val at: Long)

    companion object {
        const val TTL_MS = 24L * 3_600_000L
        const val KEEP = 4
        private const val META = "meta.txt"
        private const val FILES = "files"
    }

    private fun slot(id: String) = File(dir, id)

    /** Копирует [files] в новую запись. Файлов, которых нет, не будет и при возврате (их удалят). */
    fun snapshot(label: String, files: List<File>, now: Long): String {
        val id = now.toString()
        val s = slot(id)
        val store = File(s, FILES).apply { mkdirs() }
        val lines = ArrayList<String>()
        for ((i, f) in files.withIndex()) {
            val copy = File(store, "$i")
            if (f.exists()) copy.writeBytesAtomic(f.readBytes())
            lines += "${f.absolutePath}\t$i\t${if (f.exists()) 1 else 0}"
        }
        File(s, META).writeTextAtomic(label.replace('\n', ' ') + "\n" + lines.joinToString("\n") + "\n")
        prune(now)
        return id
    }

    private fun prune(now: Long) {
        val all = (dir.listFiles()?.filter { it.isDirectory }?.mapNotNull { d -> d.name.toLongOrNull()?.let { it to d } } ?: emptyList()).sortedByDescending { it.first }
        all.forEachIndexed { i, (at, d) -> if (i >= KEEP || now - at > TTL_MS) d.deleteRecursively() }
    }

    fun available(now: Long): List<Entry> {
        val out = ArrayList<Entry>()
        for (d in dir.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            val at = d.name.toLongOrNull() ?: continue
            if (now - at !in 0..TTL_MS) continue
            val label = try { File(d, META).readLines().firstOrNull().orEmpty() } catch (_: Exception) { continue }
            out += Entry(d.name, label, at)
        }
        return out.sortedByDescending { it.at }
    }

    /** Возвращает файлы записи на место; запись после этого удаляется. false — записи нет или она устарела. */
    fun restore(id: String, now: Long): Boolean {
        val d = slot(id)
        val at = id.toLongOrNull() ?: return false
        if (!d.isDirectory || now - at !in 0..TTL_MS) return false
        val meta = try { File(d, META).readLines() } catch (_: Exception) { return false }
        for (line in meta.drop(1)) {
            val p = line.split('\t')
            if (p.size < 3) continue
            val target = File(p[0])
            if (p[2] == "1") {
                val src = File(File(d, FILES), p[1])
                if (src.exists()) target.writeBytesAtomic(src.readBytes())
            } else {
                target.delete()
            }
        }
        d.deleteRecursively()
        return true
    }
}

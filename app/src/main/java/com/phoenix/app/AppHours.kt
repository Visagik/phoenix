package com.phoenix.app

import java.io.File
import java.time.ZoneId

/**
 * Когда пользователь обычно открывает конкретные приложения (из ваших целей): для каждого свой файл часов активности.
 * Нужно, чтобы перед обычным временем открытия проверить сервер этого приложения заранее.
 */
class AppHours(private val dir: File, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val map = java.util.concurrent.ConcurrentHashMap<String, ActivityHours>()

    private fun of(pkg: String): ActivityHours =
        map.getOrPut(pkg) { ActivityHours(File(dir, pkg.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".tsv"), zone) }

    fun record(pkg: String, now: Long = System.currentTimeMillis()) = of(pkg).recordActive(now)

    /** Приложения, которые обычно открывают в ближайший час (сейчас слот спокойный, следующий оживлённый). */
    fun soon(pkgs: Collection<String>, now: Long = System.currentTimeMillis(), leadMin: Int = 15): List<String> =
        pkgs.filter { of(it).peakSoon(now, leadMin) }
}

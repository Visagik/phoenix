package com.phoenix.app

import java.util.concurrent.ConcurrentHashMap

/**
 * «Одна работа за раз»: если проверка с таким ключом уже идёт, повторный запуск (экран включили, сеть сменилась, сработал таймер —
 * всё в одну секунду) пропускается, а не запускает вторую такую же проверку параллельно.
 */
class SingleFlight {
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** true — можно начинать (ключ занят этим вызовом); false — такая работа уже идёт. После работы обязательно [leave]. */
    fun tryEnter(key: String): Boolean = running.add(key)

    fun leave(key: String) {
        running.remove(key)
    }

    fun isRunning(key: String): Boolean = key in running

    inline fun <T> once(key: String, skipped: () -> T, block: () -> T): T {
        if (!tryEnter(key)) return skipped()
        try {
            return block()
        } finally {
            leave(key)
        }
    }
}

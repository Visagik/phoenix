package com.phoenix.app.xray

import libv2ray.ProcessFinder

/**
 * Определение приложения по соединению для правил process — настоящий класс вместо Proxy (причина — в PhoenixCoreCallback).
 * [find] получает (протокол, адрес источника, порт источника, адрес назначения, порт назначения) и возвращает UID или -1.
 */
class PhoenixProcessFinder(private val find: (String, String, Long, String, Long) -> Long) : ProcessFinder {
    override fun findProcessByConnection(p0: String?, p1: String?, p2: Long, p3: String?, p4: Long): Long =
        try {
            find(p0 ?: "", p1 ?: "", p2, p3 ?: "", p4)
        } catch (_: Throwable) {
            -1L
        }
    override fun toString(): String = "PhoenixProcessFinder"
}

package com.phoenix.app.xray

import libv2ray.CoreCallbackHandler

/**
 * Обработчик событий ядра Xray — настоящий класс, а не java.lang.reflect.Proxy.
 * На Android 6 проверка аргументов CheckJNI в ART падала на методе Proxy-класса (дамп 28.11: libart CheckMethodArguments).
 * Подключается в сборку только если есть app/libs/libv2ray.aar (см. build.gradle.kts).
 */
class PhoenixCoreCallback : CoreCallbackHandler {
    override fun onEmitStatus(p0: Long, p1: String?): Long = 0L
    override fun shutdown(): Long = 0L
    override fun startup(): Long = 0L
    override fun toString(): String = "PhoenixCoreCallback"
}

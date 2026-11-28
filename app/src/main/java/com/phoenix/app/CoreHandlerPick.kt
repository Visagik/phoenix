package com.phoenix.app

/**
 * Выбор обработчика ядра: настоящий класс из app/src/xray (есть, только если в сборке был libv2ray.aar) или null — тогда XrayCore берёт Proxy.
 * Чистая функция: без Android и без самого ядра, проверяется тестом.
 */
object CoreHandlerPick {
    const val CALLBACK_CLASS = "com.phoenix.app.xray.PhoenixCoreCallback"
    const val FINDER_CLASS = "com.phoenix.app.xray.PhoenixProcessFinder"

    /** Экземпляр класса [className] с конструктором из [argTypes]; класса или конструктора нет, либо он упал — null (без исключения). */
    fun create(className: String, argTypes: List<Class<*>> = emptyList(), args: List<Any?> = emptyList(), loader: ClassLoader? = CoreHandlerPick::class.java.classLoader): Any? =
        try {
            val c = Class.forName(className, true, loader)
            c.getConstructor(*argTypes.toTypedArray()).newInstance(*args.toTypedArray())
        } catch (_: Throwable) {
            null
        }
}

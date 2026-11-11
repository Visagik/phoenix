package com.phoenix.app

import android.content.Context
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Обёртка над ядром Xray (libv2ray.aar из AndroidLibXrayLite).
 *
 * Ядро вызывается через рефлексию: приложение собирается и запускается и без файла libv2ray.aar
 * (сбор конфигов работает), а VPN просто сообщает, что ядра нет. Положите libv2ray.aar в app/libs —
 * и VPN заработает без правок кода.
 */
object XrayCore {
    private const val LIB = "libv2ray.Libv2ray"
    private const val CALLBACK = "libv2ray.CoreCallbackHandler"
    private const val SEQ = "go.Seq"
    private const val PROCESS_FINDER = "libv2ray.ProcessFinder"

    const val MISSING_TEXT = "Ядро Xray не найдено: положите libv2ray.aar в папку app/libs и пересоберите приложение (см. README)."
    const val OLD_TEXT = "Ядро слишком старое (Lib v30 и ниже работает только через tun2socks). Нужен свежий libv2ray.aar из AndroidLibXrayLite (релизы вида 26.x, github.com/2dust/AndroidLibXrayLite/releases/latest)."

    private val libClass: Class<*>? by lazy {
        try {
            Class.forName(LIB)
        } catch (_: Throwable) {
            null
        }
    }

    /** Есть ли вообще библиотека libv2ray. */
    val present: Boolean get() = libClass != null

    /** Подходит ли она: новое ядро умеет принимать TUN-дескриптор само (newCoreController + initCoreEnv). */
    val available: Boolean by lazy {
        val c = libClass
        c != null && c.methods.any { it.name == "newCoreController" } && c.methods.any { it.name == "initCoreEnv" }
    }

    /** Почему ядро не подходит (пустая строка — всё в порядке). */
    val problem: String
        get() = when {
            !present -> MISSING_TEXT
            !available -> OLD_TEXT
            else -> ""
        }

    @Volatile
    private var initialized = false

    /** Последняя ошибка запуска ядра (показывается пользователю, если все проверки провалились). */
    @Volatile
    var lastError: String = ""
        private set

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        if (!available) throw IllegalStateException(problem)
        val app = context.applicationContext
        call(Class.forName(SEQ), null, "setContext", app)
        val assets = File(app.filesDir, "xray-assets").apply { mkdirs() }
        call(Class.forName(LIB), null, "initCoreEnv", assets.absolutePath, deviceKey(app))
        initialized = true
    }

    /**
     * Ключ XUDP: ядро требует base64url без «=» от ровно 32 байт (иначе «xray.xudp.basekey: invalid value»).
     * Ключ случайный, но постоянный для установки.
     */
    private fun deviceKey(app: Context): String {
        val prefs = app.getSharedPreferences("phoenix", Context.MODE_PRIVATE)
        val saved = prefs.getString("xray_key_b64", null)
        if (saved != null && XudpKey.isValid(saved)) return saved
        val key = XudpKey.make()
        prefs.edit().putString("xray_key_b64", key).apply()
        return key
    }

    fun version(): String = try {
        if (!present) "нет" else call(Class.forName(LIB), null, "checkVersionX").toString()
    } catch (e: Throwable) {
        "?"
    }

    /**
     * Доступность адреса через конфиг (ядро поднимает временный экземпляр без входов).
     * @return задержка в мс, либо -1 если не получилось
     */
    fun measureDelay(context: Context, config: String, url: String): Long =
        if (url == Baseline.PRIMARY) Baseline.measure({ rawDelay(context, config, it) }, { threadError.get().orEmpty() })
        else rawDelay(context, config, url)

    private val threadError = ThreadLocal<String>()

    private fun rawDelay(context: Context, config: String, url: String): Long = try {
        init(context)
        threadError.set("")
        (call(Class.forName(LIB), null, "measureOutboundDelay", config, url) as? Number)?.toLong() ?: -1L
    } catch (e: Throwable) {
        val msg = (e.cause?.message ?: e.message ?: e.javaClass.simpleName)
        threadError.set(msg)
        lastError = msg
        -1L
    }

    /** Управление запущенным ядром (VPN). */
    class Controller {
        private val controller: Any

        init {
            val callbackClass = Class.forName(CALLBACK)
            val handler = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
                when (method.name) {
                    "startup", "shutdown", "onEmitStatus" -> 0L
                    "toString" -> "PhoenixCoreCallback"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
            controller = call(Class.forName(LIB), null, "newCoreController", handler)!!
        }

        /** @param tunFd дескриптор TUN от VpnService, 0 — без TUN */
        fun start(config: String, tunFd: Int) {
            call(controller.javaClass, controller, "startLoop", config, tunFd)
        }

        /**
         * Позволяет правилам маршрутизации узнавать приложение по соединению (правила process).
         * [find] получает (протокол, адрес источника, порт источника, адрес назначения, порт назначения), возвращает UID или -1.
         * Нужно вызвать до start.
         */
        fun registerProcessFinder(find: (String, String, Long, String, Long) -> Long): Boolean {
            val finderClass = try {
                Class.forName(PROCESS_FINDER)
            } catch (_: Throwable) {
                return false // в этой версии ядра маршрутов по приложениям нет
            }
            val proxy = Proxy.newProxyInstance(finderClass.classLoader, arrayOf(finderClass)) { p, method, args ->
                when (method.name) {
                    "findProcessByConnection" -> try {
                        find(args[0] as String, args[1] as String, (args[2] as Number).toLong(), args[3] as String, (args[4] as Number).toLong())
                    } catch (_: Throwable) {
                        -1L
                    }
                    "toString" -> "PhoenixProcessFinder"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.firstOrNull()
                    else -> null
                }
            }
            return try {
                call(controller.javaClass, controller, "registerProcessFinder", proxy)
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun stop() {
            try {
                call(controller.javaClass, controller, "stopLoop")
            } catch (_: Throwable) {
            }
        }

        val isRunning: Boolean
            get() = try {
                call(controller.javaClass, controller, "getIsRunning") as? Boolean ?: false
            } catch (_: Throwable) {
                false
            }
    }

    // ---- рефлексия ----

    /** Ищет метод по имени и числу параметров (gomobile создаёт методы со стабильными именами). */
    private fun call(cls: Class<*>, target: Any?, name: String, vararg args: Any?): Any? {
        val method: Method = cls.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: throw NoSuchMethodException("$cls.$name/${args.size}")
        try {
            return method.invoke(target, *coerce(method, args))
        } catch (e: InvocationTargetException) {
            throw RuntimeException(e.targetException.message ?: e.targetException.javaClass.simpleName, e.targetException)
        }
    }

    /** Int -> Long там, где ядро ждёт long (Go int становится Java long). */
    private fun coerce(method: Method, args: Array<out Any?>): Array<Any?> =
        Array(args.size) { i ->
            val a = args[i]
            val t = method.parameterTypes[i]
            when {
                a is Int && (t == java.lang.Long.TYPE || t == java.lang.Long::class.java) -> a.toLong()
                a is Long && (t == java.lang.Integer.TYPE || t == java.lang.Integer::class.java) -> a.toInt()
                else -> a
            }
        }
}

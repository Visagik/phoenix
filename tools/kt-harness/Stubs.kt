package android.content
open class Context {
    val applicationContext: Context get() = this
    val filesDir: java.io.File get() = java.io.File(System.getProperty("phoenix.dir") ?: ".")
}

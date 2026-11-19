package android.content

/** Замена android.content.Context для общей логики: ей нужна только папка данных. */
open class Context(private val dir: java.io.File) {
    val applicationContext: Context get() = this
    val filesDir: java.io.File get() = dir
}

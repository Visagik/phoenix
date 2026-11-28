package androidx.annotation

/** Заглушка для JVM-harness: настоящая аннотация приходит с androidx.annotation в приложении. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class RequiresApi(val value: Int = 1, val api: Int = 1)

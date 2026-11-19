package com.phoenix.app

/**
 * Какие приложения идут через VPN. Android не позволяет в одном туннеле совмещать списки «разрешённых» и «запрещённых» приложений,
 * поэтому режима два: «все, кроме списка» (как чёрный список: обход) и «только выбранные» (как белый список).
 * В режиме «только выбранные» приложения из «Свой сервер для приложений» добавляются сами, иначе их цели перестали бы работать.
 */
object AppsMode {
    const val ALL_EXCEPT = 0
    const val ONLY_SELECTED = 1

    fun clamp(v: Int): Int = if (v == ONLY_SELECTED) ONLY_SELECTED else ALL_EXCEPT

    fun encode(items: Set<String>): String = items.filter { it.isNotBlank() && !it.contains('\n') }.sorted().joinToString("\n")

    fun decode(text: String?): Set<String> = (text ?: "").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * Пакеты, которые пойдут через туннель в режиме «только выбранные»: выбранные и приложения целей, но только установленные
     * и не само Phoenix (оно всегда мимо туннеля). Пусто — режим не применяется (иначе Android пустил бы через VPN всё).
     */
    fun allowed(selected: Set<String>, targetPackages: Set<String>, installed: Collection<String>, self: String): Set<String> {
        val have = installed.toHashSet()
        return (selected + targetPackages).filter { it != self && it in have }.toSet()
    }
}

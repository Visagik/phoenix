package com.phoenix.app

/** Определение страны по названию профиля: [XX] -> текстовое название -> флаг-эмодзи. */
object NameGeo {
    private val bracketCodes = setOf(
        "de", "us", "nl", "fr", "gb", "jp", "kr", "sg", "hk", "ca", "au", "it", "es", "pl", "cz", "ro",
        "bg", "rs", "tr", "ae", "in", "se", "dk", "fi", "no", "ie", "pt", "be", "at", "ch", "lv", "lt",
        "ee", "kz", "uz", "ru", "ua", "by", "md", "ge", "am", "az",
    )

    private val bracketRegex = Regex("\\[([a-z]{2})]")

    // Порядок важен: первое совпадение побеждает.
    private val names: List<Pair<String, List<String>>> = listOf(
        "DE" to listOf("germany", "германия"),
        "NL" to listOf("netherlands", "нидерланды"),
        "FR" to listOf("france", "франция"),
        "GB" to listOf("great britain", "великобритания", "united kingdom"),
        "IT" to listOf("italy", "италия"),
        "ES" to listOf("spain", "испания"),
        "PL" to listOf("poland", "польша"),
        "CZ" to listOf("czechia", "чехия", "czech republic"),
        "RO" to listOf("romania", "румыния"),
        "BG" to listOf("bulgaria", "болгария"),
        "RS" to listOf("serbia", "сербия"),
        "SE" to listOf("sweden", "швеция"),
        "DK" to listOf("denmark", "дания"),
        "FI" to listOf("finland", "финляндия"),
        "NO" to listOf("norway", "норвегия"),
        "IE" to listOf("ireland", "ирландия"),
        "PT" to listOf("portugal", "португалия"),
        "BE" to listOf("belgium", "бельгия"),
        "AT" to listOf("austria", "австрия"),
        "CH" to listOf("switzerland", "швейцария"),
        "LV" to listOf("latvia", "латвия"),
        "LT" to listOf("lithuania", "литва"),
        "EE" to listOf("estonia", "эстония"),
        "HU" to listOf("hungary", "венгрия"),
        "GR" to listOf("greece", "греция"),
        "TR" to listOf("turkey", "турция"),
        "RU" to listOf("russia", "россия", "russian"),
        "UA" to listOf("ukraine", "украина"),
        "BY" to listOf("belarus", "беларусь", "белоруссия"),
        "MD" to listOf("moldova", "молдова"),
        "GE" to listOf("georgia", "грузия"),
        "AM" to listOf("armenia", "армения"),
        "AZ" to listOf("azerbaijan", "азербайджан"),
        "KZ" to listOf("kazakhstan", "казахстан"),
        "UZ" to listOf("uzbekistan", "узбекистан"),
        "US" to listOf("usa", "сша", "united states", "america"),
        "JP" to listOf("japan", "япония"),
        "KR" to listOf("south korea", "южная корея", "korea"),
        "SG" to listOf("singapore", "сингапур"),
        "HK" to listOf("hong kong", "гонконг"),
        "CA" to listOf("canada", "канада"),
        "AU" to listOf("australia", "австралия"),
        "IN" to listOf("india", "индия"),
        "AE" to listOf("uae", "оаэ", "emirates"),
        "CN" to listOf("china", "китай"),
    )

    /** @param rawName фрагмент после # (может быть URL-кодирован) */
    fun detect(rawName: String): String? {
        if (rawName.isBlank()) return null
        val decoded = urlDecode(rawName)
        val lower = decoded.lowercase()

        bracketRegex.find(lower)?.let {
            val code = it.groupValues[1]
            if (code in bracketCodes) return code.uppercase()
        }

        for ((cc, words) in names) {
            if (words.any { lower.contains(it) }) return cc
        }

        return flagCode(decoded)
    }

    /** Флаг-эмодзи = пара региональных индикаторов U+1F1E6..U+1F1FF. */
    fun flagCode(s: String): String? {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            if (cp in 0x1F1E6..0x1F1FF && i + n < s.length) {
                val cp2 = s.codePointAt(i + n)
                if (cp2 in 0x1F1E6..0x1F1FF) {
                    val a = 'A' + (cp - 0x1F1E6)
                    val b = 'A' + (cp2 - 0x1F1E6)
                    return "$a$b"
                }
            }
            i += n
        }
        return null
    }
}

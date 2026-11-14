package com.phoenix.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Палитра Phoenix («спокойный огонь»): тёплые нейтральные тона и янтарный акцент.
 * Акцент — только главное действие и выбранное; зелёный — «в порядке»; красный — ошибка.
 * Все роли Material 3 заданы явно, иначе остаются стандартные серо-фиолетовые.
 */
object PhoenixTheme {
    val light: ColorScheme = lightColorScheme(
        primary = Color(0xFF9A4600), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDBC8), onPrimaryContainer = Color(0xFF331200),
        secondary = Color(0xFF77574A), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF3DFD2), onSecondaryContainer = Color(0xFF2B1D14),
        tertiary = Color(0xFF6B5E00), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFE9A8), onTertiaryContainer = Color(0xFF3D2F00),
        background = Color(0xFFFFF8F5), onBackground = Color(0xFF221A15),
        surface = Color(0xFFFFF8F5), onSurface = Color(0xFF221A15),
        surfaceVariant = Color(0xFFF6E5DB), onSurfaceVariant = Color(0xFF52443B),
        surfaceTint = Color(0xFF9A4600),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFEF1EA),
        surfaceContainer = Color(0xFFFCEEE6), surfaceContainerHigh = Color(0xFFF6E5DB), surfaceContainerHighest = Color(0xFFFCEEE6),
        outline = Color(0xFF85736A), outlineVariant = Color(0xFFD7C2B6),
        error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
        inverseSurface = Color(0xFF382E29), inverseOnSurface = Color(0xFFFFEDE4), inversePrimary = Color(0xFFFFB68A),
    )

    val dark: ColorScheme = darkColorScheme(
        primary = Color(0xFFFFB68A), onPrimary = Color(0xFF532200),
        primaryContainer = Color(0xFF753400), onPrimaryContainer = Color(0xFFFFDBC8),
        secondary = Color(0xFFE7BDAB), onSecondary = Color(0xFF442A1D),
        secondaryContainer = Color(0xFF3A2E26), onSecondaryContainer = Color(0xFFF3DFD2),
        tertiary = Color(0xFFE2C46A), onTertiary = Color(0xFF3A3000),
        tertiaryContainer = Color(0xFF4A3900), onTertiaryContainer = Color(0xFFFFE08A),
        background = Color(0xFF141210), onBackground = Color(0xFFEDE0D8),
        surface = Color(0xFF141210), onSurface = Color(0xFFEDE0D8),
        surfaceVariant = Color(0xFF2C2824), onSurfaceVariant = Color(0xFFD7C2B6),
        surfaceTint = Color(0xFFFFB68A),
        surfaceContainerLowest = Color(0xFF0F0D0C), surfaceContainerLow = Color(0xFF1A1816),
        surfaceContainer = Color(0xFF211E1B), surfaceContainerHigh = Color(0xFF2C2824), surfaceContainerHighest = Color(0xFF211E1B),
        outline = Color(0xFF9F8D82), outlineVariant = Color(0xFF4F443C),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        inverseSurface = Color(0xFFEDE0D8), inverseOnSurface = Color(0xFF382E29), inversePrimary = Color(0xFF9A4600),
    )

    /** Смысловые цвета: (фон, текст). */
    fun safe(dark: Boolean) = if (dark) Color(0xFF14432A) to Color(0xFF8FD6A8) else Color(0xFFC8F0D4) to Color(0xFF0B5D2E)
    fun warn(dark: Boolean) = if (dark) Color(0xFF4A3900) to Color(0xFFFFE08A) else Color(0xFFFFE9A8) to Color(0xFF3D2F00)

    /** Один цвет галочки «в порядке» на всех экранах. */
    /** Жёлтая точка статуса («частично», «может не сработать»): светлая и тёмная темы читаются одинаково. */
    fun amber(dark: Boolean): Color = if (dark) Color(0xFFFFC857) else Color(0xFFC98A00)
    fun ok(dark: Boolean): Color = if (dark) Color(0xFF8FD6A8) else Color(0xFF0B5D2E)
}

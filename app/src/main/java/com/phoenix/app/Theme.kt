package com.phoenix.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Палитра Phoenix («спокойный огонь»): тёплые нейтральные тона и янтарный акцент.
 * Акцент — только главное действие и выбранное; зелёный — «в порядке»; красный — ошибка.
 * Все роли Material 3 заданы явно, иначе остаются стандартные серо-фиолетовые.
 */
object PhoenixTheme {
    /** Три радиуса на всё приложение: 12 — кнопки и поля, 20 — карточки, 28 — капсула нижней панели, листы и диалоги. */
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(12.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

    /** Типографика: пять-шесть ролей вместо стандартной шкалы. Цифры одной ширины — в [tabular]. */
    val typography = Typography(
        headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium),
        headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
        titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
        titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp, fontFeatureSettings = "tnum"),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp, fontFeatureSettings = "tnum"),
    )

    val light: ColorScheme = lightColorScheme(
        primary = Color(0xFF9A4600), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDBC8), onPrimaryContainer = Color(0xFF331200),
        secondary = Color(0xFF77574A), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF3DFD2), onSecondaryContainer = Color(0xFF2B1D14),
        tertiary = Color(0xFF6B5E00), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFE9A8), onTertiaryContainer = Color(0xFF3D2F00),
        background = Color(0xFFFFF8F5), onBackground = Color(0xFF221A15),
        surface = Color(0xFFFFF8F5), onSurface = Color(0xFF221A15),
        surfaceVariant = Color(0xFFF6E5DB), onSurfaceVariant = Color(0xFF6B5C52),
        surfaceTint = Color(0xFF9A4600),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFEF1EA),
        surfaceContainer = Color(0xFFF7EBE3), surfaceContainerHigh = Color(0xFFF1E2D8), surfaceContainerHighest = Color(0xFFEFDACE),
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
        surfaceVariant = Color(0xFF2C2824), onSurfaceVariant = Color(0xFFA8998F),
        surfaceTint = Color(0xFFFFB68A),
        surfaceContainerLowest = Color(0xFF0F0D0C), surfaceContainerLow = Color(0xFF1A1816),
        surfaceContainer = Color(0xFF24201D), surfaceContainerHigh = Color(0xFF2E2925), surfaceContainerHighest = Color(0xFF37322D),
        outline = Color(0xFF9F8D82), outlineVariant = Color(0xFF4F443C),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        inverseSurface = Color(0xFFEDE0D8), inverseOnSurface = Color(0xFF382E29), inversePrimary = Color(0xFF9A4600),
    )

    /** Смысловые цвета: (фон, текст). */
    fun safe(dark: Boolean) = if (dark) Color(0xFF14432A) to Color(0xFF8FD6A8) else Color(0xFFC8F0D4) to Color(0xFF0B5D2E)
    fun warn(dark: Boolean) = if (dark) Color(0xFF4A3900) to Color(0xFFFFD27A) else Color(0xFFFFE9A8) to Color(0xFF6B4A00)

    /** Один цвет галочки «в порядке» на всех экранах. */
    /** Жёлтая точка статуса («частично», «может не сработать»): контраст не ниже 4,5:1 к фону в обеих темах (раньше #C98A00 давал 2,8:1 в светлой). */
    fun amber(dark: Boolean): Color = if (dark) Color(0xFFFFC857) else Color(0xFF8A5F00)
    fun ok(dark: Boolean): Color = if (dark) Color(0xFF8FD6A8) else Color(0xFF0B5D2E)
}

/** Выбранная в приложении тема (см. [ThemeChoice]); читается один раз при запуске и меняется переключателем «Тема». */
object ThemeMode {
    val mode = MutableStateFlow(ThemeChoice.SYSTEM)
}

/** Тёмная ли тема с учётом выбора в приложении; все цвета состояний берут её, а не системную. */
@Composable
fun appDark(): Boolean {
    val m by ThemeMode.mode.collectAsState()
    return ThemeChoice.dark(m, isSystemInDarkTheme())
}

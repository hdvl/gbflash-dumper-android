package com.gbflash.dumper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The app's Material3 theme. [accent] is the only thing that changes at runtime — it picks which
 * console's colors the primary/container roles borrow (DMG green, GBC grape purple, GBA indigo,
 * or a neutral slate before any cartridge has been identified). Background/surface stay the same
 * neutral "shell" tones no matter what's plugged in.
 */
@Composable
fun GbFlashTheme(accent: AppAccent, darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val a = (if (darkTheme) darkAccents else lightAccents).getValue(accent)

    val scheme: ColorScheme = if (darkTheme) {
        darkColorScheme(
            primary = a.primary,
            onPrimary = a.onPrimary,
            primaryContainer = a.primaryContainer,
            onPrimaryContainer = a.onPrimaryContainer,
            secondaryContainer = a.primaryContainer,
            onSecondaryContainer = a.onPrimaryContainer,
            background = Neutral.backgroundDark,
            onBackground = Neutral.onSurfaceDark,
            surface = Neutral.surfaceDark,
            onSurface = Neutral.onSurfaceDark,
            surfaceVariant = Neutral.surfaceVariantDark,
            onSurfaceVariant = Neutral.onSurfaceVariantDark,
            outline = Neutral.outlineDark,
        )
    } else {
        lightColorScheme(
            primary = a.primary,
            onPrimary = a.onPrimary,
            primaryContainer = a.primaryContainer,
            onPrimaryContainer = a.onPrimaryContainer,
            secondaryContainer = a.primaryContainer,
            onSecondaryContainer = a.onPrimaryContainer,
            background = Neutral.backgroundLight,
            onBackground = Neutral.onSurfaceLight,
            surface = Neutral.surfaceLight,
            onSurface = Neutral.onSurfaceLight,
            surfaceVariant = Neutral.surfaceVariantLight,
            onSurfaceVariant = Neutral.onSurfaceVariantLight,
            outline = Neutral.outlineLight,
        )
    }

    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

package com.gbflash.dumper.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Which console the current accent color should evoke. Chosen from the detected cartridge (or
 * NEUTRAL before anything's been identified) — see [com.gbflash.dumper.ui.accentFor].
 */
enum class AppAccent { NEUTRAL, DMG, CGB, AGB }

/** Neutral "shell" colors shared by every accent — only the accent slots (primary/containers) change. */
internal object Neutral {
    // Light: warm off-white, close to the original DMG unit's plastic.
    val backgroundLight = Color(0xFFFBF9F4)
    val surfaceLight = Color(0xFFF2EFE8)
    val surfaceVariantLight = Color(0xFFE6E1D6)
    val onSurfaceLight = Color(0xFF1B1B18)
    val onSurfaceVariantLight = Color(0xFF48453C)
    val outlineLight = Color(0xFF7A756A)

    // Dark: charcoal, not pure black — easier on the eyes, still lets accent colors read as "lit".
    val backgroundDark = Color(0xFF161614)
    val surfaceDark = Color(0xFF201F1C)
    val surfaceVariantDark = Color(0xFF2E2C27)
    val onSurfaceDark = Color(0xFFEDECE6)
    val onSurfaceVariantDark = Color(0xFFC8C3B6)
    val outlineDark = Color(0xFF8C8778)
}

/** The four primary-role colors an accent contributes to the color scheme. */
internal data class AccentColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
)

// DMG: the four canonical shades of the original Game Boy's pea-green LCD.
private val dmgDarkest = Color(0xFF0F380F)
private val dmgDark = Color(0xFF306230)
private val dmgLight = Color(0xFF8BAC0F)
private val dmgLightest = Color(0xFF9BBC0F)

// GBC: "Grape", one of the Game Boy Color's signature translucent shell colors.
private val gbcPurple = Color(0xFF7B2FBF)
private val gbcPurpleContainerLight = Color(0xFFE9D2FF)
private val gbcPurpleOnContainerLight = Color(0xFF3A0A66)
private val gbcPurpleLightOnDark = Color(0xFFC89CFF)
private val gbcPurpleContainerDark = Color(0xFF4A1B7A)
private val gbcPurpleOnContainerDark = Color(0xFFEBD6FF)

// AGB: "Indigo", the Game Boy Advance's flagship launch color.
private val agbIndigo = Color(0xFF4B4AC9)
private val agbIndigoContainerLight = Color(0xFFDEDDFF)
private val agbIndigoOnContainerLight = Color(0xFF1D1B5C)
private val agbIndigoLightOnDark = Color(0xFF9F9EFF)
private val agbIndigoContainerDark = Color(0xFF302E8A)
private val agbIndigoOnContainerDark = Color(0xFFE4E3FF)

// Neutral/idle: slate, used before a cartridge has been identified.
private val slate = Color(0xFF4A4A55)
private val slateContainerLight = Color(0xFFDCDCE6)
private val slateOnContainerLight = Color(0xFF1C1C24)
private val slateLightOnDark = Color(0xFFB7B6C6)
private val slateContainerDark = Color(0xFF3A3945)
private val slateOnContainerDark = Color(0xFFE4E3EE)

internal val lightAccents: Map<AppAccent, AccentColors> = mapOf(
    AppAccent.NEUTRAL to AccentColors(slate, Color.White, slateContainerLight, slateOnContainerLight),
    AppAccent.DMG to AccentColors(dmgDark, dmgLightest, dmgLightest, dmgDarkest),
    AppAccent.CGB to AccentColors(gbcPurple, Color.White, gbcPurpleContainerLight, gbcPurpleOnContainerLight),
    AppAccent.AGB to AccentColors(agbIndigo, Color.White, agbIndigoContainerLight, agbIndigoOnContainerLight),
)

internal val darkAccents: Map<AppAccent, AccentColors> = mapOf(
    AppAccent.NEUTRAL to AccentColors(slateLightOnDark, Color(0xFF26252E), slateContainerDark, slateOnContainerDark),
    AppAccent.DMG to AccentColors(dmgLightest, dmgDarkest, dmgDark, dmgLight),
    AppAccent.CGB to AccentColors(gbcPurpleLightOnDark, gbcPurpleOnContainerLight, gbcPurpleContainerDark, gbcPurpleOnContainerDark),
    AppAccent.AGB to AccentColors(agbIndigoLightOnDark, agbIndigoOnContainerLight, agbIndigoContainerDark, agbIndigoOnContainerDark),
)

/** The pea-green "LCD screen" look used for the technical log panel — always these colors, regardless of theme/accent. */
internal object LcdScreen {
    val background = Color(0xFF9BBC0F)
    val backgroundDark = Color(0xFF8BAC0F)
    val ink = Color(0xFF0F380F)
}

package com.gbflash.dumper.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gbflash.dumper.R

/**
 * "Press Start 2P" (SIL OFL 1.1, see assets/licenses/press_start_2p_OFL.txt) — an 8-bit arcade
 * pixel font. Used sparingly, for headings only: it's unreadable at body-text sizes since every
 * glyph is a fixed-width square block.
 */
val PixelFontFamily = FontFamily(Font(R.font.press_start_2p))

/**
 * Default Material3 typography, except the two biggest heading styles borrow the pixel font (with
 * extra line height — pixel fonts run tall) so the app title and big cartridge-name headers read
 * as "retro game" without making body text, buttons, or the log illegible.
 */
val AppTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(
            fontFamily = PixelFontFamily,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = PixelFontFamily,
            fontSize = 18.sp,
            lineHeight = 28.sp,
        ),
    )
}

/** Standalone style for the top app bar title, a notch smaller so "GBFlash Dumper" fits on one line. */
val AppBarTitleStyle = TextStyle(fontFamily = PixelFontFamily, fontSize = 14.sp, lineHeight = 1.4.em)

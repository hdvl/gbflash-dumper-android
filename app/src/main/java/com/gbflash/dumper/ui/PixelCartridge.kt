package com.gbflash.dumper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * A blocky, pixel-art game cartridge silhouette drawn cell-by-cell on a grid — chamfered top
 * corners, a recessed label window (tinted with the current theme accent, like a game's sticker),
 * and a narrower connector tab at the bottom. Deliberately drawn with a [Canvas] rather than a
 * static vector asset so the label can react live to [labelColor]/[bodyColor].
 */
@Composable
fun PixelCartridge(
    modifier: Modifier = Modifier,
    bodyColor: Color = MaterialTheme.colorScheme.outline,
    labelColor: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier = modifier) {
        val cols = 8
        val rows = 10
        val cell = minOf(size.width / cols, size.height / rows)
        val gridWidth = cell * cols
        val gridHeight = cell * rows
        val originX = (size.width - gridWidth) / 2f
        val originY = (size.height - gridHeight) / 2f

        fun cellRect(col: Int, row: Int, colSpan: Int = 1, rowSpan: Int = 1, color: Color) {
            drawRect(
                color = color,
                topLeft = Offset(originX + col * cell, originY + row * cell),
                size = Size(cell * colSpan, cell * rowSpan),
            )
        }

        // Body, row by row (see the ASCII grid this mirrors in the class doc above).
        cellRect(1, 0, 6, 1, bodyColor) // chamfered top edge
        cellRect(0, 1, 8, 1, bodyColor) // body widens to full width
        cellRect(0, 2, 2, 3, bodyColor) // left edge beside the label window
        cellRect(6, 2, 2, 3, bodyColor) // right edge beside the label window
        cellRect(2, 2, 4, 3, labelColor) // the label window itself
        cellRect(0, 5, 8, 3, bodyColor) // solid lower body
        cellRect(1, 8, 6, 1, bodyColor) // taper before the connector
        cellRect(2, 9, 4, 1, bodyColor) // connector tab
    }
}

package com.mutsho.localuno.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate as canvasRotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mutsho.localuno.R
import kotlin.math.hypot

/**
 * The three promotional card faces on the Main Menu (assets/card-red/yellow/blue.svg).
 *
 * Built as one parameterized composable rather than three imported VectorDrawables. The source
 * SVGs are each ~30 shapes - 28 individually-positioned clipped stripe rects for the diagonal rib,
 * a radial gloss, a linear shade, a cream keyline, a cream oval, and the glyph three times over -
 * repeated per colour. Importing them as-is would mean three near-identical multi-KB drawables where
 * only the fill colour and glyph actually differ, in an app whose card rendering (CardView.kt) is
 * already a parameterized composable rather than static art. One function matches that pattern and
 * avoids the duplication; HANDOFF_MENUS.md calls the SVGs "safe" to import as drawables, not
 * mandatory to.
 *
 * The diagonal rib in the source is 28 explicit 10px bars, 26px apart, rotated 28°. Reproduced here
 * as a Canvas loop rather than counting bars by hand - same angle and spacing, sized to the actual
 * card regardless of its dp size.
 */
@Composable
fun MenuCardFace(
    color: Color,
    glyph: Int,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(width, height)
            .clip(RoundedCornerShape(14.dp * (width / 140.dp)))
            .background(color)
    ) {
        // Diagonal rib - clipped to the card bounds by Canvas itself.
        Canvas(modifier = Modifier.matchParentSize()) {
            val diag = hypot(size.width, size.height)
            canvasRotate(28f, pivot = Offset(size.width / 2f, size.height / 2f)) {
                var x = -diag
                while (x < diag * 1.5f) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.08f),
                        topLeft = Offset(x, -diag / 2f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.07f, diag * 2f)
                    )
                    x += size.width * 0.186f
                }
            }
        }
        // Gloss (top-left) + shade (bottom-right), same layered-background pattern CardView.kt
        // already uses for the in-game card faces.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.26f), Color.Transparent),
                        center = Offset.Zero,
                        radius = with(androidx.compose.ui.platform.LocalDensity.current) { width.toPx() * 0.9f }
                    )
                )
                .background(
                    Brush.linearGradient(colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.26f)))
                )
        )
        // Cream inset keyline
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(
                    width = (8.dp * (width / 140.dp)),
                    color = Color(0xFFF2EFE6),
                    shape = RoundedCornerShape(9.dp * (width / 140.dp))
                )
        )
        // Cream oval carrying the glyph in the card's own colour
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(width * 0.71f, height * 0.31f)
                .clip(RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = Modifier.matchParentSize().background(Color(0xFFF7F5EF)))
            androidx.compose.foundation.Image(
                painter = painterResource(glyph),
                contentDescription = null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color),
                modifier = Modifier.size(width * 0.27f)
            )
        }
        // Corner glyphs, white, small - top-left and bottom-right (rotated 180°)
        androidx.compose.foundation.Image(
            painter = painterResource(glyph),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(width * 0.07f)
                .size(width * 0.13f)
        )
        androidx.compose.foundation.Image(
            painter = painterResource(glyph),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(width * 0.07f)
                .size(width * 0.13f)
                .rotate(180f)
        )
    }
}

/**
 * The app's face-down card - the menu's resume banner and the board's draw pile both use it.
 *
 * Re-authored rather than imported: the source card-back.svg uses <text> ("UNO"), a <pattern> weave
 * and a gradient, none of which survive a VectorDrawable import. Flat field, the same rib technique
 * as UnoCardFace, the cream keyline and a red oval - the wordmark is omitted exactly as
 * ic_launcher_foreground.xml omits its own, and for the same reason.
 */
@Composable
fun UnoCardBack(width: Dp, height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width, height)
            .clip(RoundedCornerShape(9.dp * (width / 70.dp)))
            .background(Color(0xFF1A1C2B))
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val diag = hypot(size.width, size.height)
            canvasRotate(28f, pivot = Offset(size.width / 2f, size.height / 2f)) {
                var x = -diag
                while (x < diag * 1.5f) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.05f),
                        topLeft = Offset(x, -diag / 2f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.07f, diag * 2f)
                    )
                    x += size.width * 0.186f
                }
            }
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(width = 3.dp * (width / 70.dp), color = Color(0xFFF2EFE6), shape = RoundedCornerShape(6.dp * (width / 70.dp)))
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(width * 0.63f, height * 0.28f)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFED1C24))
        )
    }
}

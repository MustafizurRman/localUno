package com.mutsho.localuno.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.ui.theme.*

/**
 * What colour is actually in force, said loudly enough to be read at a glance.
 *
 * The three skins each had their own quiet version of this - Round Table and No Mercy a 38-40dp orb
 * under an 8sp "COLOUR" caption, Side Rails a row of 8dp pips over `ACTIVE COLOUR · YELLOW` set in
 * 9sp Neutral600. Dark grey, nine pixels tall, on a dark felt: legible if you go looking for it,
 * invisible if you are looking at your hand, which is where a player actually looks.
 *
 * It matters most at exactly the moment it is hardest to infer. Any wild breaks the link between
 * the card lying face up and the colour you may answer with, and No Mercy - the deck people
 * actually pick - is the deck with the most wilds in it. So the board would be showing a red +4 on
 * a red felt while the colour in force was green, and the only thing saying so was grey 9sp text.
 *
 * Three things carry it now, in descending order of how hard they are to miss:
 *
 *  1. [ActiveColourAura] washes the felt behind the discard pile in the colour itself. It is the
 *     largest, softest signal, and it sits exactly where the eye already is.
 *  2. [ActiveColourBanner] names the colour in filled, saturated, high-contrast type.
 *  3. Both flare briefly when the colour CHANGES, so a switch is noticed rather than discovered
 *     two turns later.
 *
 * Every animated value here is read in a draw lambda, never in composition - see
 * `BoardScaffold.heatAlpha` for the crash that rule exists to avoid.
 */

/** Text that stays readable on [background] - yellow needs ink, the rest need paper. */
private fun readableOn(background: Color): Color {
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.6f) Color(0xFF151018) else Color.White
}

/**
 * A soft wash of the active colour, sized to sit behind the discard pile.
 *
 * Drawn as a radial gradient rather than a ring so it reads as the table being lit that colour,
 * which is unmissable in peripheral vision, instead of as one more piece of chrome to parse.
 */
@Composable
fun ActiveColourAura(
    color: CardColor,
    modifier: Modifier = Modifier,
    intensity: Float = 1f
) {
    val target = colorFor(color)
    val tint = remember { Animatable(target, Color.VectorConverter(target.colorSpace)) }
    // 0 at rest, 1 the instant the colour changes, easing back down - the flare that makes a switch
    // announce itself.
    val flare = remember { Animatable(0f) }

    LaunchedEffect(color) {
        flare.snapTo(1f)
        tint.animateTo(target, tween(420))
        flare.animateTo(0f, tween(900, easing = EaseOutCubic))
    }

    Box(
        modifier = modifier.drawBehind {
            val f = flare.value
            val c = tint.value
            val radius = size.minDimension * (0.62f + 0.10f * f)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        c.copy(alpha = (0.42f + 0.30f * f) * intensity),
                        c.copy(alpha = (0.16f + 0.14f * f) * intensity),
                        Color.Transparent
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = radius
                )
            )
        }
    )
}

/**
 * The colour, named, in the colour.
 *
 * Filled rather than outlined and set in 15sp bold: the previous captions were 8-9sp in a grey that
 * sat a few percent above the felt, which is a label you can only read deliberately.
 */
@Composable
fun ActiveColourBanner(
    color: CardColor,
    showSymbols: Boolean,
    modifier: Modifier = Modifier
) {
    val target = colorFor(color)
    val fill = remember { Animatable(target, Color.VectorConverter(target.colorSpace)) }
    val flare = remember { Animatable(0f) }

    LaunchedEffect(color) {
        flare.snapTo(1f)
        fill.animateTo(target, tween(420))
        flare.animateTo(0f, tween(900, easing = EaseOutCubic))
    }

    val ink = readableOn(target)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier
            .height(34.dp)
            // The flare is a scale-up on change, read in the draw phase.
            .graphicsLayer {
                val s = 1f + 0.10f * flare.value
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(17.dp))
            .drawBehind {
                val c = fill.value
                val f = flare.value
                // A glow that widens with the flare, so the change reads even at the edge of vision.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(c.copy(alpha = 0.55f * f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.width * 0.9f
                    )
                )
                drawRect(color = c)
            }
            .padding(horizontal = 13.dp)
    ) {
        if (showSymbols) {
            ColorSymbol(color = color, size = 15.dp, tint = ink)
        } else {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ink.copy(alpha = 0.85f))
            )
        }
        Text(
            text = color.name,
            color = ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp
        )
    }
}

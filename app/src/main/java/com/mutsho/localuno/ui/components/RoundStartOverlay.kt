package com.mutsho.localuno.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The deck being shuffled and dealt, played once at the start of a round.
 *
 * This exists because the board previously just... appeared, fully dealt, with no moment marking
 * the transition. A player coming from the lobby could not tell whether the game had started, was
 * still waiting for someone, or had frozen - the screen looked identical in all three cases. A deal
 * is the universal signal that a card game has begun, and every physical game opens with one.
 *
 * It also replaces something that was lost: the old flat-fan hand dealt its cards in with a
 * staggered flight, and swapping the fan for the dial dropped that with nothing in its place. This
 * puts the beat back where it belongs - on the whole table rather than on one player's hand.
 *
 * Deliberately does NOT consume touches. The turn clock is live the instant the round starts, and a
 * modal scrim would be spending someone's first turn on a flourish.
 */
@Composable
fun RoundStartOverlay(
    visible: Boolean,
    playerCount: Int,
    onFinished: () -> Unit
) {
    if (!visible) return

    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(TOTAL_MS, easing = LinearEasing))
        onFinished()
    }

    val t = progress.value
    if (t >= 1f) return

    // Fade the scene in and out so it arrives and leaves softly rather than popping.
    val sceneAlpha = when {
        t < 0.08f -> t / 0.08f
        t > 0.88f -> (1f - t) / 0.12f
        else -> 1f
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05050F).copy(alpha = 0.82f * sceneAlpha))
            .graphicsLayer { alpha = sceneAlpha }
    ) {
        val centreX = maxWidth / 2
        val centreY = maxHeight / 2
        val cardW = 46.dp
        val cardH = 68.dp

        // ── Phase 1: the riffle ─────────────────────────────────────────────
        // Two halves of the deck split apart, spring back, and interleave. Drawn as a stack of
        // backs whose horizontal offset alternates by parity, so odd and even cards move in
        // opposite directions - that alternation is what reads as a shuffle rather than as a stack
        // sliding around.
        val shuffle = ((t - 0.08f) / 0.34f).coerceIn(0f, 1f)
        val dealing = ((t - 0.44f) / 0.44f).coerceIn(0f, 1f)

        if (dealing <= 0f) {
            repeat(DECK_CARDS) { i ->
                // Three riffles across the shuffle phase.
                // Each card is a little further through the riffle than the one below it, so the
                // deck cascades. Without the per-card phase offset every odd card moves in exact
                // lockstep with every other odd card and nine cards read as two.
                val swing = sin(shuffle * PI.toFloat() * 6f + i * 0.38f)
                val side = if (i % 2 == 0) 1f else -1f
                // Amplitude decays so the deck visibly settles into a neat stack before dealing.
                val amp = (1f - shuffle) * 30f
                val lift = (i - DECK_CARDS / 2f) * 2.6f

                CardBackTile(
                    width = cardW,
                    height = cardH,
                    modifier = Modifier
                        .offset(
                            x = centreX - cardW / 2 + (swing * side * amp).dp,
                            y = centreY - cardH / 2 + lift.dp
                        )
                        .graphicsLayer {
                            rotationZ = swing * side * amp * 0.35f
                        }
                )
            }
        }

        // ── Phase 2: the deal ───────────────────────────────────────────────
        // One card per seat, flying out from the stack. Seat 0 is straight down - that is where
        // this player's own hand lives on every skin - and the rest spread evenly around, so the
        // fan of flights matches the table it is dealing to.
        if (dealing > 0f) {
            val seats = playerCount.coerceIn(2, 8)
            repeat(seats) { i ->
                // Staggered, so cards leave one at a time the way a dealer actually deals.
                val delay = i / (seats * 1.6f)
                val local = ((dealing - delay) / (1f - delay).coerceAtLeast(0.2f)).coerceIn(0f, 1f)
                if (local <= 0f) return@repeat
                val eased = local * local * (3f - 2f * local)

                val angle = PI.toFloat() / 2f + (2f * PI.toFloat() * i / seats)
                val reach = minOf(maxWidth, maxHeight) * 0.34f
                val x = centreX - cardW / 2 + (reach.value * cos(angle) * eased).dp
                val y = centreY - cardH / 2 + (reach.value * sin(angle) * eased).dp

                CardBackTile(
                    width = cardW,
                    height = cardH,
                    modifier = Modifier
                        .offset(x = x, y = y)
                        .graphicsLayer {
                            rotationZ = 360f * eased * (if (i % 2 == 0) 1f else -1f) * 0.6f
                            val s = 1f - 0.25f * eased
                            scaleX = s
                            scaleY = s
                            alpha = 1f - (eased * eased) * 0.95f
                        }
                )
            }
        }

        // ── Caption ─────────────────────────────────────────────────────────
        // Anchored to the top rather than under the deck. The deal throws a card straight down
        // towards this player's own seat, and at the caption's old position that card landed on
        // top of the word DEALING.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 118.dp)
        ) {
            Text(
                text = if (dealing > 0f) "DEALING" else "SHUFFLING",
                color = NocturneText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            )
            Text(
                text = "$playerCount players",
                color = Accent300,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

// Short on purpose. The engine's turn clock starts the instant the round does, so every frame of
// this is spent out of the first player's turn - at 2400ms that was a sixth of a 15s clock burned
// on a flourish. Long enough to read as a deal, short enough not to cost a move.
private const val TOTAL_MS = 1700
private const val DECK_CARDS = 9

/** A face-down card, matching the frame the real faces use so the deck reads as the same deck. */
@Composable
private fun CardBackTile(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(width, height)
            .clip(RoundedCornerShape(width * 0.11f))
            .background(CardInk)
            .padding(width * 0.055f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(width * 0.075f))
                .background(Brush.linearGradient(listOf(Accent700, Neutral900)))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(width * 0.075f)
                )
        )
    }
}

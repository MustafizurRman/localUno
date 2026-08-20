package com.mutsho.localuno.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.ui.theme.*

/**
 * "YOUR TURN", announced the moment the turn lands on this player.
 *
 * The board already stated whose turn it was, in the turn row, in 20sp text that changes colour -
 * and it was routinely missed. That row is static: it reads the same whether the turn arrived a
 * quarter-second ago or has been sitting there for ten seconds, so nothing about it catches an eye
 * that was looking somewhere else. What people actually notice is a change, which is why this is a
 * momentary animation rather than a brighter label.
 *
 * Pairs with the haptic cue that already fires on the same edge (see rememberTurnAlert): together
 * they cover both the player looking at the screen and the one who isn't.
 *
 * Two things it deliberately does not do:
 *
 *  - **Consume touches.** The turn clock starts running the instant the turn arrives, so an overlay
 *    that ate the first second of taps would be actively costing the player the time it is telling
 *    them about.
 *  - **Sit in the middle of the screen.** It rides above centre, clear of both the discard pile and
 *    the hand, so the thing it is announcing stays visible underneath it - being told it is your
 *    turn is not much use if the announcement covers your cards.
 */
@Composable
fun YourTurnBanner(trigger: Int, modifier: Modifier = Modifier) {
    // Nothing on first composition: `trigger` starts at 0 and only a genuine turn edge increments
    // it. Without this the banner would fire for a turn the player already had, every time the
    // board is entered or returned to after a reconnect.
    var lastPlayed by remember { mutableStateOf(0) }
    val progress = remember { Animatable(1f) }

    // Ordinary state written once at the end, rather than gating the emission below on
    // `progress.value >= 1f`. See `finished` in HandTransferOverlay: deciding STRUCTURE from a
    // value the animation rewrites every frame is what tears the subtree out mid-flight.
    var finished by remember { mutableStateOf(true) }
    LaunchedEffect(trigger) {
        if (trigger == 0 || trigger == lastPlayed) return@LaunchedEffect
        lastPlayed = trigger
        finished = false
        progress.snapTo(0f)
        progress.animateTo(1f, tween(TOTAL_MS, easing = LinearEasing))
        finished = true
    }

    val t = progress.value
    // Emission is wrapped in `if`, never skipped with an early `return`.
    //
    // Returning early AFTER remember/LaunchedEffect makes this composable's group structure differ
    // between frames: on some frames it opens groups and emits, on others it bails partway. Driven
    // by an ANIMATING value that flips many times a second, that is a structure changing under the
    // composer on almost every frame, and the failure lands nowhere near here - it surfaces as
    // IndexOutOfBoundsException at Stack.pop / ComposerImpl.endRoot, with no application code on
    // the stack at all. Keeping every remember unconditional and only the CONTENT conditional is
    // the shape Compose is built for.
    if (!finished) {

        // In fast, hold, out gently - the hold is what makes it readable rather than a flicker.
        val alpha = when {
            t < 0.12f -> t / 0.12f
            t > 0.72f -> (1f - t) / 0.28f
            else -> 1f
        }
        // A single overshoot on entry, settling to rest. Reads as a stamp landing.
        val scale = when {
            t < 0.12f -> 0.72f + 0.42f * (t / 0.12f)
            t < 0.26f -> 1.14f - 0.14f * ((t - 0.12f) / 0.14f)
            else -> 1f
        }

        // Positioned by the CALLER, which is the only place that knows the board's layout. Sizing
        // itself here was the bug: a fillMaxWidth Box wraps its own height, so it sat at the parent's
        // top edge and the "above centre" offset put the banner on top of an opponent's avatar.
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(RoundedCornerShape(16.dp))
                    .background(Neutral900.copy(alpha = 0.94f))
                    .border(2.dp, UnoYellow, RoundedCornerShape(16.dp))
                    .padding(horizontal = 26.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "YOUR TURN",
                    color = UnoYellow,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
                Text(
                    text = "spin the dial · tap the top card",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

// Roughly a second at full opacity after the entry overshoot - long enough to read, short enough
// that it is gone before it becomes something to wait out on a 10s turn clock.
private const val TOTAL_MS = 1800

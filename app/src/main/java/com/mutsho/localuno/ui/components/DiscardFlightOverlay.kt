package com.mutsho.localuno.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.model.GameState
import com.mutsho.localuno.model.detectPlayedCard

/** How long a card takes to reach the pile. Long enough to follow, short enough not to hold a turn. */
private const val FLIGHT_MS = 380

/**
 * Which card is currently in the air, so the discard pile can decline to draw it yet.
 *
 * Needed because the state is already correct before the animation starts: by the time a play
 * reaches the board the engine has put the card on the pile, so a flight would show the SAME card
 * in two places at once - settled on the pile and arriving above it. Verified on a device, and it
 * reads exactly as wrong as it sounds; the pile has to keep showing the previous card until the
 * flight lands.
 *
 * A single Int, written twice per play. Reading it during composition is fine at that rate and
 * must stay that way - this is not a per-frame value and must never become one.
 */
@Stable
class CardInFlight {
    var cardId by mutableStateOf<Int?>(null)
}

val LocalCardInFlight = staticCompositionLocalOf { CardInFlight() }

/**
 * Flies a played card from the seat that played it to the discard pile.
 *
 * A card used to leave a hand and appear on the pile with nothing in between, which is the one beat
 * on this board that happened without being shown - the deal, the colour splash, hand transfers and
 * the winner are all animated. But the version worth building is not "animate it": it is animate it
 * FROM SOMEBODY, so that a four-player table teaches you who acted without your reading the log.
 *
 * That is why this needed [SeatAnchors] first. There was no seat coordinate system at all, and the
 * cheap alternative - a synthetic ring, as HandTransferOverlay uses - would have flown the card
 * from a place the seat is not. On the side-rails skin, where seats sit in two edge columns, that
 * would read as plainly broken. The whole purpose is to teach who played; one flying from the wrong
 * place teaches the wrong thing.
 *
 * Your own plays fly from your hand rather than a seat, which needs no special case: the hand
 * registers itself under the local player's id like any other anchor.
 *
 * Reading discipline, which this file is otherwise a prime candidate to get wrong: the anchors are
 * read ONCE when a flight starts and captured in a local, and the animating offset is read inside
 * `graphicsLayer` - never in composition. A board that recomposes every frame is what took this app
 * down from inside the Compose runtime.
 */
@Composable
fun DiscardFlightOverlay(
    gameState: GameState,
    showSymbols: Boolean,
    rules: GameRules = GameRules()
) {
    val anchors = LocalSeatAnchors.current
    val inFlight = LocalCardInFlight.current
    val density = LocalDensity.current

    // The previous board, kept here rather than lifted, because nothing else needs it.
    var previous by remember { mutableStateOf(gameState) }
    var flight by remember { mutableStateOf<Flight?>(null) }

    val progress = remember { Animatable(0f) }

    LaunchedEffect(gameState.sequenceNumber) {
        val played = detectPlayedCard(previous, gameState)
        previous = gameState
        if (played == null) return@LaunchedEffect

        // Captured now, once. Holding the values means a layout pass mid-flight cannot move the
        // card's origin out from under it, and nothing subscribes to the anchor map.
        val from = anchors.centreOf(played.byPlayerId) ?: return@LaunchedEffect
        val to = anchors.centreOf(SeatAnchors.DISCARD) ?: return@LaunchedEffect

        flight = Flight(played.card, from, to)
        inFlight.cardId = played.card.id
        progress.snapTo(0f)
        try {
            progress.animateTo(1f, tween(FLIGHT_MS, easing = EaseOutCubic))
        } finally {
            // Cleared in `finally` because this effect is cancelled whenever the next state
            // arrives - a fast table can play again mid-flight. Without it the pile would keep
            // hiding a card that is no longer coming, which looks like the play never happened.
            inFlight.cardId = null
            flight = null
        }
    }

    val current = flight ?: return

    // Sized to the discard slot so the card arrives at the size it will settle at.
    val w = 70.dp
    val h = 100.dp
    val halfW = with(density) { w.toPx() / 2f }
    val halfH = with(density) { h.toPx() / 2f }

    Box(modifier = Modifier.fillMaxSize()) {
        BoardCardFace(
            card = current.card,
            showSymbols = showSymbols,
            dimmed = false,
            width = w,
            height = h,
            rules = rules,
            modifier = Modifier
                .graphicsLayer {
                    // Read here, in the draw/layer phase. `by` on this value would recompose the
                    // whole board every frame of every play.
                    val t = progress.value
                    val x = current.from.x + (current.to.x - current.from.x) * t
                    // A shallow arc rather than a straight line - a card thrown onto a table rises
                    // slightly before it lands, and a linear slide reads as a UI element moving
                    // rather than a card being played.
                    val lift = -60f * t * (1f - t) * 4f
                    val y = current.from.y + (current.to.y - current.from.y) * t + lift
                    translationX = x - halfW
                    translationY = y - halfH
                    // Settles onto the pile's own tilt.
                    rotationZ = -3f * t
                    // Opponents' cards are unknown until they land, so they arrive rather than
                    // being readable in transit; the scale does the "it came from there" work.
                    scaleX = 0.7f + 0.3f * t
                    scaleY = 0.7f + 0.3f * t
                }
        )
    }
}

private data class Flight(val card: Card, val from: Offset, val to: Offset)

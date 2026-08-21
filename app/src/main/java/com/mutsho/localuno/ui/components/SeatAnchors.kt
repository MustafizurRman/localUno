package com.mutsho.localuno.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Where each seat, the discard pile and your own hand actually are on screen.
 *
 * There was no such thing before. `onGloballyPositioned` appeared nowhere in the UI, and
 * [HandTransferOverlay] lays its animation out on a synthetic ring of its own precisely because
 * seat positions differ per skin - the round table arranges them on an arc, the side-rails skin
 * puts them in two edge columns. An overlay that wants to animate FROM a player therefore had
 * nowhere to ask where that player is.
 *
 * Registered from the layout phase, which is the only phase that knows. Each skin tags its own seat
 * composable, so a skin that arranges seats differently needs no special case here - it simply
 * reports different rectangles.
 *
 * Read this when an animation STARTS and hold the value for its duration, rather than reading it
 * every frame. The map is snapshot state, so a composition that subscribes to it recomposes on
 * every layout pass; nothing in the board needs that, and this codebase has already paid once for
 * reading a moving value in the wrong phase.
 */
@Stable
class SeatAnchors {
    val bounds = mutableStateMapOf<String, Rect>()

    /** Centre of a registered anchor, or null if that thing is not on screen. */
    fun centreOf(key: String): Offset? = bounds[key]?.center

    companion object {
        /** The discard pile - shared by every skin through DiscardSlot, so tagged once. */
        const val DISCARD = "__discard__"
    }
}

val LocalSeatAnchors = staticCompositionLocalOf { SeatAnchors() }

/**
 * Reports this element's on-screen rectangle under [key].
 *
 * Uses `boundsInRoot` rather than a position in some parent, because the overlay that consumes
 * these is a sibling of the whole board and has to place things in the same space regardless of
 * which skin drew them or how deeply they are nested.
 *
 * Writing snapshot state from `onGloballyPositioned` is a layout-phase write, which is allowed -
 * but it is only safe because nothing reads this map during composition. See [SeatAnchors].
 */
fun Modifier.seatAnchor(key: String, anchors: SeatAnchors): Modifier =
    this.onGloballyPositioned { coords ->
        val rect = coords.boundsInRoot()
        // Skip degenerate measurements - a node can be positioned before it has a size, and a
        // zero-area anchor would fly a card from the top-left corner of the screen.
        if (rect.width > 0f && rect.height > 0f) {
            val existing = anchors.bounds[key]
            if (existing != rect) anchors.bounds[key] = rect
        }
    }

package com.mutsho.localuno.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.GameRules

/**
 * The board's card face. A thin wrapper over [UnoCardFace], which is the real scanned deck.
 *
 * Everything this used to draw itself - the 118 degree rib, the cream keyline, the per-skin centre
 * treatment - was a stand-in for card art that did not exist yet, and it showed: a Reverse card
 * read as the word "REV". Kept as a wrapper rather than deleted because it also owns the two things
 * the face itself has no business knowing about: the dimming of unplayable cards, and the house
 * rules that decide whether a rule pip is truthful.
 */
@Composable
fun BoardCardFace(
    card: Card,
    showSymbols: Boolean,
    dimmed: Boolean,
    width: Dp,
    height: Dp,
    rules: GameRules = GameRules(),
    // Default false: the discard pile's single face-up card is the one place the full-size art -
    // the giant centre numeral, the oval, the wear - is meant to be seen whole.
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(width, height)) {
        UnoCardFace(
            card = card,
            width = width,
            height = height,
            rules = rules,
            compact = compact,
            showSymbols = showSymbols
        )
        // Unplayable cards are darkened with an OPAQUE scrim rather than faded with alpha.
        //
        // alpha 0.55 was the single biggest reason a fanned hand was hard to read: a translucent
        // card lets the card behind it show straight through, and in a hand where most cards are
        // unplayable that is a dozen half-visible faces printed on top of each other. Every number
        // competed with whatever was underneath it. A scrim de-emphasises exactly as well while
        // keeping each card solid, so a card only ever hides its neighbour instead of blending
        // with it.
        if (dimmed) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(width * 0.11f))
                    .background(Color.Black.copy(alpha = 0.42f))
            )
        }
    }
}

/** Two-to-four character mark for a card - move log entries, compact chips. */
fun cardLabel(type: CardType): String = when (type) {
    CardType.ZERO -> "0"; CardType.ONE -> "1"; CardType.TWO -> "2"; CardType.THREE -> "3"
    CardType.FOUR -> "4"; CardType.FIVE -> "5"; CardType.SIX -> "6"; CardType.SEVEN -> "7"
    CardType.EIGHT -> "8"; CardType.NINE -> "9"
    CardType.SKIP -> "SKIP"
    CardType.REVERSE -> "REV"
    CardType.DRAW_TWO -> "+2"
    CardType.DRAW_FOUR -> "+4"
    CardType.WILD -> "W"
    CardType.WILD_DRAW_SIX -> "+6"
    CardType.WILD_DRAW_TEN -> "+10"
    CardType.WILD_REVERSE_DRAW_FOUR -> "R+4"
    CardType.WILD_COLOR_ROULETTE -> "ROUL"
    CardType.DISCARD_ALL -> "DISC"
    CardType.SKIP_EVERYONE -> "SKIP+"
    CardType.FLIP -> "FLIP"
    else -> type.name.filter { it.isDigit() || it.isUpperCase() }.take(4)
}

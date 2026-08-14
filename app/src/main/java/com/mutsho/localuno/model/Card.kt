package com.mutsho.localuno.model

enum class CardColor {
    RED, YELLOW, GREEN, BLUE, WILD
}

enum class CardType {
    // Number cards
    ZERO, ONE, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE,

    // Colored Action cards
    SKIP, REVERSE, DRAW_TWO, DRAW_FOUR, DISCARD_ALL, SKIP_EVERYONE,

    // Wild cards
    WILD, WILD_DRAW_SIX, WILD_DRAW_TEN, WILD_REVERSE_DRAW_FOUR, WILD_COLOR_ROULETTE,

    // Flip special cards (Light side)
    FLIP,

    // Flip special cards (Dark side)
    DARK_DRAW_FIVE, DARK_SKIP_EVERYONE, DARK_WILD_DRAW_COLOR, DARK_WILD, DARK_REVERSE,
    DARK_ONE, DARK_TWO, DARK_THREE, DARK_FOUR, DARK_FIVE, DARK_SIX, DARK_SEVEN, DARK_EIGHT, DARK_NINE,
    DARK_SKIP, DARK_DRAW_FIVE_NUMBER, DARK_FLIP
}

data class Card(
    val id: Int,
    val color: CardColor,
    val type: CardType,
    val darkColor: CardColor? = null,
    val darkType: CardType? = null,
    val points: Int = calculatePoints(type)
) {
    companion object {
        fun calculatePoints(type: CardType): Int = when (type) {
            CardType.ZERO -> 0
            CardType.ONE, CardType.DARK_ONE -> 1
            CardType.TWO, CardType.DARK_TWO -> 2
            CardType.THREE, CardType.DARK_THREE -> 3
            CardType.FOUR, CardType.DARK_FOUR -> 4
            CardType.FIVE, CardType.DARK_FIVE -> 5
            CardType.SIX, CardType.DARK_SIX -> 6
            CardType.SEVEN, CardType.DARK_SEVEN -> 7
            CardType.EIGHT, CardType.DARK_EIGHT -> 8
            CardType.NINE, CardType.DARK_NINE -> 9
            // Colored action cards = 20 pts
            CardType.SKIP, CardType.REVERSE, CardType.DRAW_TWO, CardType.DRAW_FOUR,
            CardType.DISCARD_ALL, CardType.SKIP_EVERYONE,
            CardType.DARK_SKIP, CardType.DARK_REVERSE -> 20
            // Wild action cards = 50 pts
            CardType.WILD, CardType.WILD_DRAW_SIX, CardType.WILD_DRAW_TEN,
            CardType.WILD_REVERSE_DRAW_FOUR, CardType.WILD_COLOR_ROULETTE,
            CardType.DARK_WILD -> 50
            CardType.FLIP, CardType.DARK_FLIP -> 20
            CardType.DARK_DRAW_FIVE, CardType.DARK_DRAW_FIVE_NUMBER,
            CardType.DARK_SKIP_EVERYONE, CardType.DARK_WILD_DRAW_COLOR -> 30
        }

        /**
         * Draw cards that can never take part in a stack at all - neither as an answer to a
         * pending penalty nor as something a later card may answer. Playing one ends the exchange:
         * whoever it lands on draws.
         *
         * This is the "+6 and +10 can never be stacked" half of the house rule both setup screens
         * state verbatim.
         */
        private val NEVER_STACKABLE = setOf(CardType.WILD_DRAW_SIX, CardType.WILD_DRAW_TEN)

        /**
         * Whether [answer] may be played onto a live draw penalty created by [onto].
         *
         * Stacking matches by CARD TYPE, not by draw value: +2 answers +2, +4 answers +4, reverse
         * +4 answers reverse +4. It previously compared values with `answer >= onto`, which is a
         * materially different and much looser rule - it let a +10 answer a +4 (the two are both
         * "at least 4"), so penalties compounded across card types and reached totals like +14 that
         * no combination of the stated rules can produce.
         *
         * Single source of truth on purpose: GameEngine enforces this, and the client mirrors it to
         * decide which cards to light up. Those two answering differently is what makes a card look
         * playable and then get rejected by the host.
         */
        fun canStack(answer: CardType, onto: CardType): Boolean {
            if (drawValue(answer) == 0 || drawValue(onto) == 0) return false
            if (answer in NEVER_STACKABLE || onto in NEVER_STACKABLE) return false
            return answer == onto
        }

        /** Returns the draw value of a card, or 0 if it isn't a draw card. */
        fun drawValue(type: CardType): Int = when (type) {
            CardType.DRAW_TWO -> 2
            CardType.DRAW_FOUR -> 4
            CardType.WILD_REVERSE_DRAW_FOUR -> 4
            CardType.WILD_DRAW_SIX -> 6
            CardType.WILD_DRAW_TEN -> 10
            CardType.DARK_DRAW_FIVE, CardType.DARK_DRAW_FIVE_NUMBER -> 5
            else -> 0
        }
    }

    fun isNumberCard(): Boolean = type in listOf(
        CardType.ZERO, CardType.ONE, CardType.TWO, CardType.THREE, CardType.FOUR,
        CardType.FIVE, CardType.SIX, CardType.SEVEN, CardType.EIGHT, CardType.NINE
    )

    fun isActionCard(): Boolean = type in listOf(
        CardType.SKIP, CardType.REVERSE, CardType.DRAW_TWO, CardType.DRAW_FOUR,
        CardType.DISCARD_ALL, CardType.SKIP_EVERYONE
    )

    fun isWildCard(): Boolean = color == CardColor.WILD || type in listOf(
        CardType.WILD, CardType.WILD_DRAW_SIX, CardType.WILD_DRAW_TEN,
        CardType.WILD_REVERSE_DRAW_FOUR, CardType.WILD_COLOR_ROULETTE
    )

    fun isDrawCard(): Boolean = drawValue(type) > 0

    fun displayName(): String {
        val colorStr = if (color == CardColor.WILD) "" else color.name
        val typeStr = when (type) {
            CardType.ZERO -> "0"
            CardType.ONE -> "1"
            CardType.TWO -> "2"
            CardType.THREE -> "3"
            CardType.FOUR -> "4"
            CardType.FIVE -> "5"
            CardType.SIX -> "6"
            CardType.SEVEN -> "7"
            CardType.EIGHT -> "8"
            CardType.NINE -> "9"
            CardType.SKIP -> "Skip"
            CardType.REVERSE -> "Reverse"
            CardType.DRAW_TWO -> "+2"
            CardType.DRAW_FOUR -> "+4"
            CardType.DISCARD_ALL -> "Discard All"
            CardType.SKIP_EVERYONE -> "Skip All"
            CardType.WILD -> "Wild"
            CardType.WILD_DRAW_SIX -> "Wild +6"
            CardType.WILD_DRAW_TEN -> "Wild +10"
            CardType.WILD_REVERSE_DRAW_FOUR -> "Wild Rev+4"
            CardType.WILD_COLOR_ROULETTE -> "Wild Roulette"
            CardType.FLIP -> "Flip"
            else -> type.name
        }
        return if (colorStr.isEmpty()) typeStr else "$colorStr $typeStr"
    }
}

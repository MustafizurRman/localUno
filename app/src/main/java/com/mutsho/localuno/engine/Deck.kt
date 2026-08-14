package com.mutsho.localuno.engine

import kotlin.random.Random

import com.mutsho.localuno.model.*

object DeckFactory {

    fun createClassicDeck(): MutableList<Card> {
        var id = 0
        val cards = mutableListOf<Card>()
        val colors = listOf(CardColor.RED, CardColor.YELLOW, CardColor.GREEN, CardColor.BLUE)
        val numberTypes = listOf(
            CardType.ZERO, CardType.ONE, CardType.TWO, CardType.THREE, CardType.FOUR,
            CardType.FIVE, CardType.SIX, CardType.SEVEN, CardType.EIGHT, CardType.NINE
        )

        for (color in colors) {
            // One zero per color
            cards.add(Card(id = id++, color = color, type = CardType.ZERO))
            // Two of each 1-9 per color
            for (type in numberTypes.drop(1)) {
                cards.add(Card(id = id++, color = color, type = type))
                cards.add(Card(id = id++, color = color, type = type))
            }
            // Two Skip, Reverse, Draw Two per color
            repeat(2) {
                cards.add(Card(id = id++, color = color, type = CardType.SKIP))
                cards.add(Card(id = id++, color = color, type = CardType.REVERSE))
                cards.add(Card(id = id++, color = color, type = CardType.DRAW_TWO))
            }
        }

        // Four Wild and four Wild Draw Four
        repeat(4) {
            cards.add(Card(id = id++, color = CardColor.WILD, type = CardType.WILD))
        }
        repeat(4) {
            cards.add(Card(id = id++, color = CardColor.WILD, type = CardType.DRAW_FOUR))
        }

        return cards // 108 cards total
    }

    /**
     * Creates the official UNO No Mercy 168-card deck.
     *
     * Colored cards (per color �~ 4 colors):
     *   - 0: 1 per color = 4
     *   - 1-9: 2 per color = 72
     *   - Skip: 2 per color = 8
     *   - Reverse: 2 per color = 8
     *   - Draw Two (+2): 2 per color = 8
     *   - Draw Four (+4): 2 per color = 8
     *   - Discard All: 2 per color = 8
     *   - Skip Everyone: 2 per color = 8
     *
     * Wild cards - note there is NO plain Wild in No Mercy; that card belongs to the Classic deck
     * only, and every wild here carries an effect beyond choosing a colour:
     *   - Wild Reverse Draw 4: 11
     *   - Wild Draw 6: 11
     *   - Wild Draw 10: 11
     *   - Wild Color Roulette: 11
     *
     * Total: 4 + 72 + 48 + 44 = 168
     *
     * The 11-of-each split is an assumption: removing the 12 plain Wilds left 12 cards to
     * redistribute, and spreading them evenly across the four remaining wilds is the only choice
     * that preserves the 168 total without inventing a lopsided ratio. If the real deck weights
     * these differently, this is the one place to change it.
     */
    fun createNoMercyDeck(): MutableList<Card> {
        var id = 0
        val cards = mutableListOf<Card>()
        val colors = listOf(CardColor.RED, CardColor.YELLOW, CardColor.GREEN, CardColor.BLUE)
        val numberTypes = listOf(
            CardType.ZERO, CardType.ONE, CardType.TWO, CardType.THREE, CardType.FOUR,
            CardType.FIVE, CardType.SIX, CardType.SEVEN, CardType.EIGHT, CardType.NINE
        )

        for (color in colors) {
            // One 0 per color
            cards.add(Card(id = id++, color = color, type = CardType.ZERO))
            // Two of each 1-9 per color
            for (type in numberTypes.drop(1)) {
                cards.add(Card(id = id++, color = color, type = type))
                cards.add(Card(id = id++, color = color, type = type))
            }
            // Two Skip per color
            repeat(2) {
                cards.add(Card(id = id++, color = color, type = CardType.SKIP))
            }
            // Two Reverse per color
            repeat(2) {
                cards.add(Card(id = id++, color = color, type = CardType.REVERSE))
            }
            // Two Draw Two (+2) per color
            repeat(2) {
                cards.add(Card(id = id++, color = color, type = CardType.DRAW_TWO))
            }
            // Two Draw Four (+4) per color
            repeat(2) {
                cards.add(Card(id = id++, color = color, type = CardType.DRAW_FOUR))
            }
            // Two Discard All per color
            repeat(2) {
                cards.add(Card(id = id++, color = color, type = CardType.DISCARD_ALL))
            }
            // Two Skip Everyone per color
            repeat(2) {
                cards.add(Card(id = id++, color = color, type = CardType.SKIP_EVERYONE))
            }
        }

        // No plain Wild in this deck - see the composition note above. Every wild here does
        // something beyond choosing a colour.
        repeat(11) {
            cards.add(Card(id = id++, color = CardColor.WILD, type = CardType.WILD_REVERSE_DRAW_FOUR))
        }
        repeat(11) {
            cards.add(Card(id = id++, color = CardColor.WILD, type = CardType.WILD_DRAW_SIX))
        }
        repeat(11) {
            cards.add(Card(id = id++, color = CardColor.WILD, type = CardType.WILD_DRAW_TEN))
        }
        repeat(11) {
            cards.add(Card(id = id++, color = CardColor.WILD, type = CardType.WILD_COLOR_ROULETTE))
        }

        return cards // 168 cards
    }

    fun createFlipDeck(): MutableList<Card> {
        var id = 0
        val cards = mutableListOf<Card>()
        val colors = listOf(CardColor.RED, CardColor.YELLOW, CardColor.GREEN, CardColor.BLUE)
        val numberTypes = listOf(
            CardType.ZERO, CardType.ONE, CardType.TWO, CardType.THREE, CardType.FOUR,
            CardType.FIVE, CardType.SIX, CardType.SEVEN, CardType.EIGHT, CardType.NINE
        )

        for (color in colors) {
            cards.add(Card(id = id++, color = color, type = CardType.ZERO))
            for (type in numberTypes.drop(1)) {
                cards.add(Card(id = id++, color = color, type = type))
                cards.add(Card(id = id++, color = color, type = type))
            }
            repeat(2) {
                cards.add(Card(id = id++, color = color, type = CardType.SKIP))
                cards.add(Card(id = id++, color = color, type = CardType.REVERSE))
                cards.add(Card(id = id++, color = color, type = CardType.DRAW_TWO))
            }
            cards.add(Card(id = id++, color = color, type = CardType.FLIP))
        }

        repeat(4) {
            cards.add(Card(id = id++, color = CardColor.WILD, type = CardType.WILD))
            cards.add(Card(id = id++, color = CardColor.WILD, type = CardType.WILD_DRAW_SIX))
        }

        return cards
    }

    fun createDeckForMode(mode: GameMode): MutableList<Card> = when (mode) {
        GameMode.CLASSIC -> createClassicDeck()
        GameMode.NO_MERCY -> createNoMercyDeck()
        GameMode.FLIP -> createFlipDeck()
    }

    /**
     * [random] is injectable purely so tests can reproduce a deal.
     *
     * The fuzz suites pick their MOVES from a seeded generator but the DEAL came from the global
     * one, so a failing run could not be replayed - and those suites are what found the +14 stack
     * and the duplicated-card crash. A fuzz failure you cannot reproduce is most of the way to no
     * fuzz failure at all. Defaulted, so production behaviour is unchanged.
     */
    fun shuffle(cards: MutableList<Card>, random: Random = Random): MutableList<Card> {
        cards.shuffle(random)
        return cards
    }
}

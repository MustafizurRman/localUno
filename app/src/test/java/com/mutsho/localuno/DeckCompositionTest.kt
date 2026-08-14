package com.mutsho.localuno

import com.mutsho.localuno.engine.DeckFactory
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.GameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what is actually in each deck.
 *
 * Deck composition is the kind of fact that reads as correct in code review and is only ever caught
 * by someone who knows the physical game - the plain Wild sat in the No Mercy deck for a long time
 * looking perfectly reasonable. These assertions make the composition something the build checks
 * rather than something a reader has to know.
 */
class DeckCompositionTest {

    private fun counts(mode: GameMode): Map<CardType, Int> =
        DeckFactory.createDeckForMode(mode).groupingBy { it.type }.eachCount()

    @Test
    fun `no mercy has no plain wild`() {
        val deck = DeckFactory.createNoMercyDeck()
        assertEquals(
            "No Mercy must not contain a plain Wild - every wild in that deck carries an effect",
            0, deck.count { it.type == CardType.WILD }
        )
    }

    @Test
    fun `no mercy is 168 cards with the expected wilds`() {
        val deck = DeckFactory.createNoMercyDeck()
        assertEquals(168, deck.size)

        val c = counts(GameMode.NO_MERCY)
        assertEquals(11, c[CardType.WILD_REVERSE_DRAW_FOUR])
        assertEquals(11, c[CardType.WILD_DRAW_SIX])
        assertEquals(11, c[CardType.WILD_DRAW_TEN])
        assertEquals(11, c[CardType.WILD_COLOR_ROULETTE])

        // Colour side: the two faces unique to this deck must be present.
        assertEquals(8, c[CardType.SKIP_EVERYONE])
        assertEquals(8, c[CardType.DISCARD_ALL])
        // Coloured +4s, as well as the wild ones - this deck really does have both.
        assertEquals(8, c[CardType.DRAW_FOUR])
        assertTrue(
            "the coloured +4s must not be wild-coloured",
            deck.filter { it.type == CardType.DRAW_FOUR }.none { it.color == CardColor.WILD }
        )
    }

    @Test
    fun `classic is 108 cards and is the deck that has plain wilds`() {
        val deck = DeckFactory.createClassicDeck()
        assertEquals(108, deck.size)

        val c = counts(GameMode.CLASSIC)
        assertEquals("Classic is where the plain Wild lives", 4, c[CardType.WILD])
        // Classic's +4 is the Wild Draw Four, so it is wild-coloured here.
        assertEquals(4, c[CardType.DRAW_FOUR])
        assertTrue(
            deck.filter { it.type == CardType.DRAW_FOUR }.all { it.color == CardColor.WILD }
        )

        // None of the No Mercy-only cards leak into Classic.
        listOf(
            CardType.SKIP_EVERYONE, CardType.DISCARD_ALL, CardType.WILD_DRAW_SIX,
            CardType.WILD_DRAW_TEN, CardType.WILD_REVERSE_DRAW_FOUR, CardType.WILD_COLOR_ROULETTE
        ).forEach { type ->
            assertEquals("$type does not belong in Classic", 0, c[type] ?: 0)
        }
    }

    /** Every deck must be able to open: initializeGame looks for a number card to start the pile. */
    @Test
    fun `every deck contains number cards to open on`() {
        GameMode.entries.forEach { mode ->
            val deck = DeckFactory.createDeckForMode(mode)
            assertTrue("$mode has no number card to start the discard pile",
                deck.any { it.isNumberCard() })
        }
    }
}

package com.mutsho.localuno

import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.Direction
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.ProbeSupportAliases.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias T = CardType
private typealias Col = CardColor

internal object ProbeSupportAliases { val C = Probe }

/**
 * What the rules of UNO say should happen, asked of the engine one claim at a time.
 *
 * Matching, turn order, direction, skips and the wild colour - the parts a player would notice
 * immediately if they were wrong, and which nothing in the suite states directly.
 */
class RulesProbeTest {

    // ── Matching ────────────────────────────────────────────────────────────

    @Test fun `a card matching the colour is playable`() {
        val e = C.table(listOf(listOf(C.card(1, Col.RED, T.NINE))), colour = Col.RED)
        assertTrue(e.canPlayCard(C.card(1, Col.RED, T.NINE), "p0"))
    }

    @Test fun `a card matching the type is playable on another colour`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.BLUE, T.THREE))),
            top = C.card(500, Col.RED, T.THREE), colour = Col.RED
        )
        assertTrue(e.canPlayCard(C.card(1, Col.BLUE, T.THREE), "p0"))
    }

    @Test fun `a card matching neither colour nor type is not playable`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.BLUE, T.NINE))),
            top = C.card(500, Col.RED, T.THREE), colour = Col.RED
        )
        assertFalse(e.canPlayCard(C.card(1, Col.BLUE, T.NINE), "p0"))
    }

    @Test fun `a wild is playable on anything`() {
        val e = C.table(listOf(listOf(C.card(1, Col.WILD, T.WILD))), colour = Col.GREEN)
        assertTrue(e.canPlayCard(C.card(1, Col.WILD, T.WILD), "p0"))
    }

    @Test fun `the chosen colour is what a later card must match`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.WILD, T.WILD)), listOf(C.card(2, Col.GREEN, T.FIVE))),
            colour = Col.RED
        )
        e.playCard("p0", C.card(1, Col.WILD, T.WILD), Col.GREEN)
        assertEquals(Col.GREEN, e.getState().currentColor)
    }

    @Test fun `matching is judged against the chosen colour and not the wild card itself`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.WILD, T.WILD), C.card(90, Col.BLUE, T.EIGHT)), listOf(C.card(2, Col.GREEN, T.FIVE))),
            colour = Col.RED
        )
        e.playCard("p0", C.card(1, Col.WILD, T.WILD), Col.GREEN)
        assertTrue(e.canPlayCard(C.card(2, Col.GREEN, T.FIVE), "p1"))
    }

    @Test fun `a card not in your hand cannot be played`() {
        val e = C.table(listOf(listOf(C.card(1, Col.RED, T.NINE))))
        val before = e.getState().sequenceNumber
        e.playCard("p0", C.card(77, Col.RED, T.FIVE))
        assertEquals("a card you do not hold must not reach the pile", before, e.getState().sequenceNumber)
    }

    @Test fun `a player cannot play out of turn`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.RED, T.NINE)), listOf(C.card(2, Col.RED, T.FIVE))),
            turn = 0
        )
        val before = e.getState().sequenceNumber
        e.playCard("p1", C.card(2, Col.RED, T.FIVE))
        assertEquals(before, e.getState().sequenceNumber)
    }

    @Test fun `getPlayableCards lists exactly what canPlayCard accepts`() {
        val hand = listOf(
            C.card(1, Col.RED, T.NINE),
            C.card(2, Col.BLUE, T.NINE),
            C.card(3, Col.BLUE, T.ONE),
            C.card(4, Col.WILD, T.WILD)
        )
        val e = C.table(listOf(hand), top = C.card(500, Col.RED, T.THREE), colour = Col.RED)
        val listed = e.getPlayableCards("p0").map { it.id }.toSet()
        val accepted = hand.filter { e.canPlayCard(it, "p0") }.map { it.id }.toSet()
        assertEquals(accepted, listed)
    }

    // ── Turn order and direction ────────────────────────────────────────────

    @Test fun `play passes the turn to the next seat clockwise`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.RED, T.NINE), C.card(90, Col.BLUE, T.EIGHT)), listOf(), listOf()),
            turn = 0, seatCount = 3
        )
        e.playCard("p0", C.card(1, Col.RED, T.NINE))
        assertEquals("p1", C.turnId(e))
    }

    @Test fun `a reverse turns the direction around`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.RED, T.REVERSE), C.card(90, Col.BLUE, T.EIGHT)), listOf(), listOf()),
            turn = 0, seatCount = 3, direction = Direction.CLOCKWISE
        )
        e.playCard("p0", C.card(1, Col.RED, T.REVERSE))
        assertEquals(Direction.COUNTER_CLOCKWISE, e.getState().direction)
    }

    @Test fun `a reverse sends play to the previous seat`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.RED, T.REVERSE), C.card(90, Col.BLUE, T.EIGHT)), listOf(), listOf()),
            turn = 0, seatCount = 3, direction = Direction.CLOCKWISE
        )
        e.playCard("p0", C.card(1, Col.RED, T.REVERSE))
        assertEquals("p2", C.turnId(e))
    }

    @Test fun `a reverse with two players acts as a skip`() {
        // With two at the table, reversing hands the turn straight back.
        val e = C.table(
            listOf(listOf(C.card(1, Col.RED, T.REVERSE)), listOf()),
            turn = 0, seatCount = 2
        )
        e.playCard("p0", C.card(1, Col.RED, T.REVERSE))
        assertEquals("p0", C.turnId(e))
    }

    @Test fun `a skip jumps the next seat`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.RED, T.SKIP), C.card(90, Col.BLUE, T.EIGHT)), listOf(), listOf()),
            turn = 0, seatCount = 3
        )
        e.playCard("p0", C.card(1, Col.RED, T.SKIP))
        assertEquals("p2", C.turnId(e))
    }

    @Test fun `a skip with two players returns the turn to the player`() {
        val e = C.table(listOf(listOf(C.card(1, Col.RED, T.SKIP)), listOf()), turn = 0, seatCount = 2)
        e.playCard("p0", C.card(1, Col.RED, T.SKIP))
        assertEquals("p0", C.turnId(e))
    }

    @Test fun `turn order wraps past the last seat`() {
        val e = C.table(
            listOf(listOf(), listOf(), listOf(C.card(1, Col.RED, T.NINE), C.card(90, Col.BLUE, T.EIGHT))),
            turn = 2, seatCount = 3
        )
        e.playCard("p2", C.card(1, Col.RED, T.NINE))
        assertEquals("p0", C.turnId(e))
    }

    @Test fun `turn order wraps below the first seat going anticlockwise`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.RED, T.NINE), C.card(90, Col.BLUE, T.EIGHT)), listOf(), listOf()),
            turn = 0, seatCount = 3, direction = Direction.COUNTER_CLOCKWISE
        )
        e.playCard("p0", C.card(1, Col.RED, T.NINE))
        assertEquals("p2", C.turnId(e))
    }

    // ── Drawing ─────────────────────────────────────────────────────────────

    @Test fun `drawing takes a card off the pile`() {
        val e = C.table(listOf(listOf(C.card(1, Col.BLUE, T.NINE))), colour = Col.RED)
        val deckBefore = e.getState().drawPile.size
        e.drawCardForPlayer("p0")
        assertEquals(deckBefore - 1, e.getState().drawPile.size)
    }

    @Test fun `drawing with nothing playable passes the turn on`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.BLUE, T.NINE)), listOf(), listOf()),
            top = C.card(500, Col.RED, T.THREE), colour = Col.RED, seatCount = 3
        )
        e.drawCardForPlayer("p0")
        // Unless the drawn card happens to be playable, in which case the turn is kept on purpose.
        val kept = C.turnId(e) == "p0"
        val drawn = C.hand(e, "p0").last()
        assertTrue(
            "turn stayed with p0 but the drawn card was not playable",
            !kept || e.canPlayCard(drawn, "p0")
        )
    }

    @Test fun `a player cannot draw out of turn`() {
        val e = C.table(listOf(listOf(), listOf(C.card(1, Col.BLUE, T.NINE))), turn = 0, seatCount = 2)
        val before = C.count(e, "p1")
        e.drawCardForPlayer("p1")
        assertEquals(before, C.count(e, "p1"))
    }

    @Test fun `drawing is refused once the round is over`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.BLUE, T.NINE))),
            phase = GamePhase.ROUND_OVER
        )
        val before = C.count(e, "p0")
        e.drawCardForPlayer("p0")
        assertEquals(before, C.count(e, "p0"))
    }

    // ── Winning ─────────────────────────────────────────────────────────────

    @Test fun `emptying your hand ends the round`() {
        val e = C.table(listOf(listOf(C.card(1, Col.RED, T.NINE)), listOf()), seatCount = 2)
        e.playCard("p0", C.card(1, Col.RED, T.NINE))
        assertEquals(GamePhase.ROUND_OVER, e.getState().phase)
    }

    @Test fun `emptying your hand names you the winner`() {
        val e = C.table(listOf(listOf(C.card(1, Col.RED, T.NINE)), listOf()), seatCount = 2)
        e.playCard("p0", C.card(1, Col.RED, T.NINE))
        assertEquals("p0", e.getState().winnerId)
    }

    @Test fun `a round already over cannot be won again`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.RED, T.NINE)), listOf()),
            seatCount = 2, phase = GamePhase.ROUND_OVER
        )
        e.playCard("p0", C.card(1, Col.RED, T.NINE))
        assertEquals(1, C.count(e, "p0"))
    }

    @Test fun `last card must be a number locks a skip that would win`() {
        // Asked of canPlayCard, not playCard. Every real play goes through the former -
        // GameViewModel.executePlayCard gates on it before touching the engine - while playCard
        // deliberately checks only turn, phase and hand membership so that drawUntilPlayable can
        // auto-play the card it just drew without re-running the rule checks it already satisfied.
        val e = C.table(
            listOf(listOf(C.card(1, Col.RED, T.SKIP)), listOf()),
            settings = C.settings(lastCardMustBeNumber = true), seatCount = 2
        )
        assertFalse(
            "a skip that would empty the hand is locked",
            e.canPlayCard(C.card(1, Col.RED, T.SKIP), "p0")
        )
    }

    @Test fun `last card must be a number leaves the same skip playable with cards to spare`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.RED, T.SKIP), C.card(90, Col.BLUE, T.EIGHT)), listOf()),
            settings = C.settings(lastCardMustBeNumber = true), seatCount = 2
        )
        assertTrue(e.canPlayCard(C.card(1, Col.RED, T.SKIP), "p0"))
    }

    @Test fun `last card must be a number still allows winning on a number`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.RED, T.NINE)), listOf()),
            settings = C.settings(lastCardMustBeNumber = true), seatCount = 2
        )
        e.playCard("p0", C.card(1, Col.RED, T.NINE))
        assertEquals("p0", e.getState().winnerId)
    }

    // ── Housekeeping the board depends on ───────────────────────────────────

    @Test fun `the played card becomes the top of the discard pile`() {
        val e = C.table(listOf(listOf(C.card(1, Col.RED, T.NINE)), listOf()), seatCount = 2)
        e.playCard("p0", C.card(1, Col.RED, T.NINE))
        assertEquals(9001, e.getState().topCard?.id)
    }

    @Test fun `the played card leaves the hand`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.RED, T.NINE), C.card(2, Col.BLUE, T.ONE)), listOf()),
            seatCount = 2
        )
        e.playCard("p0", C.card(1, Col.RED, T.NINE))
        assertFalse(C.hand(e, "p0").any { it.id == 9001 })
    }

    @Test fun `every play advances the sequence number`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.RED, T.NINE), C.card(2, Col.RED, T.ONE)), listOf()),
            seatCount = 2
        )
        val before = e.getState().sequenceNumber
        e.playCard("p0", C.card(1, Col.RED, T.NINE))
        assertTrue("guests order updates by this", e.getState().sequenceNumber > before)
    }

    @Test fun `playing conserves the deck`() {
        val e = C.table(
            listOf(listOf(C.card(1, Col.RED, T.NINE), C.card(2, Col.RED, T.ONE)), listOf()),
            seatCount = 2
        )
        val before = C.allCards(e.getState()).size
        e.playCard("p0", C.card(1, Col.RED, T.NINE))
        assertEquals(before, C.allCards(e.getState()).size)
        assertTrue(C.duplicateIds(e.getState()).isEmpty())
    }

    @Test fun `drawing conserves the deck`() {
        val e = C.table(listOf(listOf(C.card(1, Col.BLUE, T.NINE))), colour = Col.RED)
        val before = C.allCards(e.getState()).size
        e.drawCardForPlayer("p0")
        assertEquals(before, C.allCards(e.getState()).size)
        assertTrue(C.duplicateIds(e.getState()).isEmpty())
    }

    @Test fun `a wild played without a colour does not leave the colour as WILD`() {
        // The board matches against currentColor; leaving it WILD would make everything playable.
        val e = C.table(
            listOf(listOf(C.card(1, Col.WILD, T.WILD)), listOf()),
            colour = Col.RED, seatCount = 2
        )
        e.playCard("p0", C.card(1, Col.WILD, T.WILD), null)
        assertNotEquals(Col.WILD, e.getState().currentColor)
    }

    @Test fun `the deal gives every player the same number of cards`() {
        val e = C.table(listOf(listOf(), listOf(), listOf(), listOf()), seatCount = 4)
        val fresh = com.mutsho.localuno.engine.GameEngine(C.settings())
        fresh.initializeGame(C.seats(4))
        val counts = fresh.getState().players.map { it.hand.size }.toSet()
        assertEquals("everyone starts level", 1, counts.size)
    }

    @Test fun `a fresh deal starts with one card face up`() {
        val fresh = com.mutsho.localuno.engine.GameEngine(C.settings())
        fresh.initializeGame(C.seats(4))
        assertEquals(1, fresh.getState().discardPile.size)
    }

    @Test fun `a fresh deal conserves the deck`() {
        val fresh = com.mutsho.localuno.engine.GameEngine(C.settings())
        fresh.initializeGame(C.seats(4))
        assertTrue(C.duplicateIds(fresh.getState()).isEmpty())
    }

    @Test fun `a fresh No Mercy deal uses the bigger deck`() {
        val classic = com.mutsho.localuno.engine.GameEngine(C.settings(mode = GameMode.CLASSIC))
        classic.initializeGame(C.seats(4))
        val mercy = com.mutsho.localuno.engine.GameEngine(C.settings(mode = GameMode.NO_MERCY))
        mercy.initializeGame(C.seats(4))
        assertTrue(
            "No Mercy adds +6, +10 and reverse +4",
            C.allCards(mercy.getState()).size > C.allCards(classic.getState()).size
        )
    }
}

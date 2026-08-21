package com.mutsho.localuno

import com.mutsho.localuno.model.CardColor as Col
import com.mutsho.localuno.model.CardType as T
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.GameState
import com.mutsho.localuno.model.TableEvent
import com.mutsho.localuno.model.detectTableEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Haptic feedback is only worth having if it never cries wolf.
 *
 * Most of this file tests a NEGATIVE - that nothing fires for something the player did themselves.
 * That is deliberate and it is where the value is. A false positive here is not a cosmetic bug: a
 * player who feels a buzz every time they tap a card stops noticing buzzes, and then the one that
 * says "a +10 just landed on you" arrives and means nothing. The feature degrades to worse than
 * not having it, silently, with every test about what it DOES report still passing.
 */
class TableEventProbeTest {

    private val P = Probe

    /** A table where [me] is seat 0 and it is [turn]'s go. */
    private fun table(
        hands: List<List<com.mutsho.localuno.model.Card>>,
        turn: Int,
        pending: Int = 0,
        phase: GamePhase = GamePhase.PLAYING,
        connected: List<Boolean> = List(hands.size) { true },
        round: Long = 1000L
    ): GameState {
        val players = hands.mapIndexed { i, hand ->
            com.mutsho.localuno.model.Player(
                id = "p$i",
                name = "P$i",
                hand = hand,
                isConnected = connected[i]
            )
        }
        return GameState(
            players = players,
            currentPlayerIndex = turn,
            pendingDrawCount = pending,
            phase = phase,
            roundStartTime = round
        )
    }

    private fun cards(n: Int, from: Int = 1) =
        (0 until n).map { P.card(from + it, Col.RED, T.THREE) }

    private fun events(prev: GameState, next: GameState, me: String = "p0") =
        detectTableEvents(prev, next, me)

    // ── Things done TO you are reported ─────────────────────────────────────

    @Test fun `a draw stack landing on you is reported`() {
        val prev = table(listOf(cards(3), cards(3)), turn = 1, pending = 0)
        val next = table(listOf(cards(3), cards(3)), turn = 0, pending = 4)
        assertTrue(events(prev, next).any { it is TableEvent.StackLanded })
    }

    @Test fun `the stack event carries what you owe`() {
        val prev = table(listOf(cards(3), cards(3)), turn = 1, pending = 2)
        val next = table(listOf(cards(3), cards(3)), turn = 0, pending = 10)
        val e = events(prev, next).filterIsInstance<TableEvent.StackLanded>().single()
        assertEquals(10, e.count)
    }

    @Test fun `a hand arriving while you were never the active seat is reported`() {
        // A seven's swap or a zero's rotation played by somebody else.
        val prev = table(listOf(cards(2), cards(7)), turn = 1)
        val next = table(listOf(cards(7, from = 20), cards(2)), turn = 1)
        assertTrue(events(prev, next).contains(TableEvent.HandHit))
    }

    @Test fun `being knocked out is reported`() {
        val prev = table(listOf(cards(3), cards(3)), turn = 1)
        val next = table(listOf(cards(3), cards(3)), turn = 1, connected = listOf(false, true))
        assertTrue(events(prev, next).contains(TableEvent.KnockedOut))
    }

    @Test fun `a knockout reports only the knockout`() {
        // Going out is the thing worth feeling; a hand change in the same transition is noise
        // beside it, and two patterns firing at once is just a longer buzz nobody can decode.
        val prev = table(listOf(cards(3), cards(3)), turn = 1)
        val next = table(listOf(cards(9, from = 40), cards(3)), turn = 1, connected = listOf(false, true))
        assertEquals(listOf(TableEvent.KnockedOut), events(prev, next))
    }

    // ── Things YOU did are never reported ───────────────────────────────────

    @Test fun `drawing a card yourself is not reported`() {
        // Your hand grew, but on your own turn, by your own tap - you already know.
        val prev = table(listOf(cards(3), cards(3)), turn = 0)
        val next = table(listOf(cards(4), cards(3)), turn = 0)
        assertTrue(events(prev, next).isEmpty())
    }

    @Test fun `taking a stack you were owed is not reported twice`() {
        // The landing was already reported. Actually collecting it is your own action.
        val prev = table(listOf(cards(3), cards(3)), turn = 0, pending = 4)
        val next = table(listOf(cards(7), cards(3)), turn = 1, pending = 0)
        assertTrue(events(prev, next).isEmpty())
    }

    @Test fun `playing a seven that swaps hands to you is not reported`() {
        // The trap case. Your hand changes exactly as much as if it had been played AGAINST you,
        // and the only thing telling them apart is that the turn was yours when it happened.
        val prev = table(listOf(cards(2), cards(9)), turn = 0)
        val next = table(listOf(cards(9, from = 30), cards(2)), turn = 1)
        assertTrue(
            "a swap you initiated is your own doing, however much your hand changed",
            events(prev, next).isEmpty()
        )
    }

    @Test fun `stacking onto a stack yourself is not reported`() {
        // You answered a +2 with a +2: the count rose, but it lands on somebody else.
        val prev = table(listOf(cards(3), cards(3)), turn = 0, pending = 2)
        val next = table(listOf(cards(2), cards(3)), turn = 1, pending = 4)
        assertTrue(events(prev, next).isEmpty())
    }

    // ── Nothing fires where nothing happened ────────────────────────────────

    @Test fun `an unchanged table reports nothing`() {
        val s = table(listOf(cards(3), cards(3)), turn = 1)
        assertTrue(events(s, s).isEmpty())
    }

    @Test fun `a new round deals everyone in without buzzing anyone`() {
        // Every hand grows at once at the start of a round. Reporting that would buzz every player
        // on every deal, which is the loudest possible false positive.
        val prev = table(listOf(cards(1), cards(1)), turn = 1, round = 1000L)
        val next = table(listOf(cards(7, from = 50), cards(7, from = 70)), turn = 1, round = 2000L)
        assertTrue(events(prev, next).isEmpty())
    }

    @Test fun `a player already out stays quiet`() {
        // Spectators watch the rest of the round; the table is no longer doing anything to them.
        val out = listOf(false, true)
        val prev = table(listOf(cards(3), cards(3)), turn = 1, connected = out)
        val next = table(listOf(cards(9, from = 60), cards(3)), turn = 1, pending = 8, connected = out)
        assertTrue(events(prev, next).isEmpty())
    }

    @Test fun `your hand shrinking is never a hit`() {
        // Somebody took cards FROM you. Unpleasant, but it is not the shape this reports.
        val prev = table(listOf(cards(9), cards(2)), turn = 1)
        val next = table(listOf(cards(2, from = 80), cards(9)), turn = 1)
        assertTrue(events(prev, next).none { it == TableEvent.HandHit })
    }

    @Test fun `an unknown player id reports nothing rather than throwing`() {
        val s = table(listOf(cards(3), cards(3)), turn = 1)
        assertTrue(detectTableEvents(s, s, "nobody").isEmpty())
    }

    @Test fun `a blank player id reports nothing`() {
        // The local id is empty until a match is initialised; this runs on every state change.
        val s = table(listOf(cards(3), cards(3)), turn = 1)
        assertTrue(detectTableEvents(s, s, "").isEmpty())
    }

    @Test fun `a stack already sitting on you is not re-reported`() {
        // It has to GROW. Otherwise every state change while you owe cards buzzes again.
        val prev = table(listOf(cards(3), cards(3)), turn = 0, pending = 4)
        val next = table(listOf(cards(3), cards(3)), turn = 0, pending = 4)
        assertTrue(events(prev, next).isEmpty())
    }

    @Test fun `events are reported for a guest exactly as for the host`() {
        // Both funnels call the same pure function over the same two states, so a guest and the
        // host must derive the same events - this asserts the function has no host-only inputs.
        val prev = table(listOf(cards(3), cards(3), cards(3)), turn = 2)
        val next = table(listOf(cards(3), cards(3), cards(3)), turn = 0, pending = 6)
        assertEquals(events(prev, next, "p0").size, 1)
        assertTrue(events(prev, next, "p1").isEmpty())
    }
}

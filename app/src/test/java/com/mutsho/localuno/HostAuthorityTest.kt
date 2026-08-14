package com.mutsho.localuno

import com.mutsho.localuno.engine.GameEngine
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trust boundary: every one of these actions arrives from ANOTHER PHONE.
 *
 * A client sends "I played this card" and the host has to decide whether that is true. Nothing on
 * the wire is trustworthy - not the player id in the payload, not the card, not the colour, not the
 * timing. The failures here are not crashes; they are a table where one player can act out of turn,
 * play cards they do not hold, or set the colour on every move, and where nobody else can tell.
 *
 * These test the ENGINE's own guarantees. GameViewModel.executePlayCard gates on canPlayCard before
 * it calls playCard, so the two together are the real defence - but a guarantee that lives only in
 * the caller is one refactor away from being gone, so the engine's half is pinned down here.
 */
class HostAuthorityTest {

    private val settings = GameSettings(
        gameMode = GameMode.NO_MERCY,
        maxPlayers = 4,
        rules = com.mutsho.localuno.model.GameRules(stacking = true, lastCardMustBeNumber = true)
    )

    private fun table(): Pair<GameEngine, com.mutsho.localuno.model.GameState> {
        val engine = GameEngine(settings)
        val state = engine.initializeGame(
            (0 until 4).map { Player(id = "p$it", name = "P$it", avatarColor = it) }
        )
        return engine to state
    }

    // ── Turn order ──────────────────────────────────────────────────────────

    @Test
    fun `a player cannot act out of turn`() {
        val (engine, state) = table()
        val current = state.players[state.currentPlayerIndex].id
        val other = state.players.first { it.id != current }

        for (card in other.hand) {
            assertFalse(
                "seat ${other.id} may not play while it is $current's turn",
                engine.canPlayCard(card, other.id)
            )
        }
        val before = engine.getState().sequenceNumber
        engine.drawCardForPlayer(other.id)
        assertEquals("an out-of-turn draw must change nothing", before, engine.getState().sequenceNumber)

        // And playCard must refuse on its own, not merely because the ViewModel asked canPlayCard
        // first. A probe confirmed it previously accepted this: the card left the hand and the
        // game advanced.
        val stateBefore = engine.getState()
        engine.playCard(other.id, other.hand.first(), CardColor.RED)
        assertEquals(
            "playCard must enforce turn order itself",
            stateBefore.sequenceNumber, engine.getState().sequenceNumber
        )
        assertEquals(
            "and the out-of-turn player must keep their card",
            other.hand.size, engine.getState().getPlayerById(other.id)!!.hand.size
        )
    }

    @Test
    fun `playCard refuses outside the playing phase on its own`() {
        val (engine, state) = table()
        val current = state.players[state.currentPlayerIndex]
        for (phase in listOf(GamePhase.PAUSED_DISCONNECT, GamePhase.ROUND_OVER, GamePhase.CHOOSING_SWAP)) {
            engine.setStateForTest(state.copy(phase = phase))
            val before = engine.getState().sequenceNumber
            engine.playCard(current.id, current.hand.first(), CardColor.RED)
            assertEquals(
                "playCard must refuse during $phase without relying on its caller",
                before, engine.getState().sequenceNumber
            )
        }
    }

    @Test
    fun `a player who is not at the table cannot act`() {
        val (engine, _) = table()
        val before = engine.getState()
        engine.drawCardForPlayer("ghost")
        engine.callUno("ghost")
        engine.playCard("ghost", Card(1, CardColor.RED, CardType.FIVE))
        assertEquals("an unknown id must change nothing", before.sequenceNumber, engine.getState().sequenceNumber)
    }

    @Test
    fun `a knocked-out player cannot act`() {
        val (engine, state) = table()
        val victim = state.players[1]
        engine.setStateForTest(
            state.copy(
                currentPlayerIndex = 1,
                players = state.players.toMutableList().also {
                    it[1] = victim.copy(isConnected = false)
                }
            )
        )
        val before = engine.getState().sequenceNumber
        engine.callUno(victim.id)
        engine.penalizeUnoNotCalled(victim.id, state.players[0].id)
        assertEquals(
            "a player out of the round must not be able to act in it",
            before, engine.getState().sequenceNumber
        )
    }

    // ── Card ownership and legality ─────────────────────────────────────────

    @Test
    fun `a card the player does not hold is refused`() {
        val (engine, state) = table()
        val current = state.players[state.currentPlayerIndex]
        val heldIds = state.players.flatMap { it.hand }.map { it.id }.toSet()
        // An id belonging to nobody - the simplest forgery.
        val forged = Card(id = heldIds.max() + 500, color = state.currentColor, type = CardType.FIVE)

        val before = engine.getState()
        engine.playCard(current.id, forged)
        assertEquals("a forged card must not be playable", before.sequenceNumber, engine.getState().sequenceNumber)
        assertEquals(
            "and must not appear on the pile",
            before.topCard, engine.getState().topCard
        )
    }

    @Test
    fun `another player's card cannot be played from your seat`() {
        val (engine, state) = table()
        val current = state.players[state.currentPlayerIndex]
        val victim = state.players.first { it.id != current.id }
        val stolen = victim.hand.first()

        val before = engine.getState()
        engine.playCard(current.id, stolen)
        assertEquals(
            "playing a card out of someone else's hand must be refused",
            before.sequenceNumber, engine.getState().sequenceNumber
        )
        assertEquals(
            "and the owner must keep it",
            victim.hand.size, engine.getState().getPlayerById(victim.id)!!.hand.size
        )
    }

    @Test
    fun `an illegal card is refused even though it is genuinely held`() {
        val (engine, state) = table()
        // A hand and a pile that share neither colour nor value.
        val top = Card(900, CardColor.RED, CardType.THREE)
        val mismatch = Card(901, CardColor.BLUE, CardType.EIGHT)
        engine.setStateForTest(
            state.copy(
                phase = GamePhase.PLAYING,
                currentPlayerIndex = 0,
                currentColor = CardColor.RED,
                discardPile = listOf(top),
                players = state.players.toMutableList().also {
                    it[0] = it[0].copy(hand = mutableListOf(mismatch, Card(902, CardColor.RED, CardType.NINE)))
                }
            )
        )
        assertFalse("blue 8 on a red 3 is not a legal play", engine.canPlayCard(mismatch, "p0"))
        assertTrue("the red 9 is", engine.canPlayCard(Card(902, CardColor.RED, CardType.NINE), "p0"))
    }

    // ── Colour ──────────────────────────────────────────────────────────────

    @Test
    fun `a non-wild card cannot set the table colour`() {
        // The exploit this closes: attaching chosenColor to an ordinary card would let a client
        // pick the active colour on every single turn.
        val (engine, state) = table()
        val red5 = Card(900, CardColor.RED, CardType.FIVE)
        engine.setStateForTest(
            state.copy(
                phase = GamePhase.PLAYING,
                currentPlayerIndex = 0,
                currentColor = CardColor.RED,
                discardPile = listOf(Card(901, CardColor.RED, CardType.THREE)),
                players = state.players.toMutableList().also {
                    it[0] = it[0].copy(hand = mutableListOf(red5, Card(902, CardColor.RED, CardType.TWO)))
                }
            )
        )
        engine.playCard("p0", red5, chosenColor = CardColor.BLUE)
        assertEquals(
            "a red 5 must leave the colour red however the client labels it",
            CardColor.RED, engine.getState().currentColor
        )
    }

    @Test
    fun `a wild cannot set the colour to wild`() {
        // currentColor = WILD soft-locks the round: almost nothing matches it.
        val (engine, state) = table()
        val wild = Card(900, CardColor.WILD, CardType.WILD)
        engine.setStateForTest(
            state.copy(
                phase = GamePhase.PLAYING,
                currentPlayerIndex = 0,
                currentColor = CardColor.GREEN,
                discardPile = listOf(Card(901, CardColor.GREEN, CardType.THREE)),
                players = state.players.toMutableList().also {
                    it[0] = it[0].copy(hand = mutableListOf(wild, Card(902, CardColor.GREEN, CardType.TWO)))
                }
            )
        )
        engine.playCard("p0", wild, chosenColor = CardColor.WILD)
        assertNotEquals(
            "the active colour must never become WILD",
            CardColor.WILD, engine.getState().currentColor
        )
    }

    // ── Phase ───────────────────────────────────────────────────────────────

    @Test
    fun `nobody can play while the table is paused`() {
        val (engine, state) = table()
        val current = state.players[state.currentPlayerIndex]
        engine.setStateForTest(state.copy(phase = GamePhase.PAUSED_DISCONNECT))

        for (card in current.hand) {
            assertFalse(
                "a paused table must accept no plays",
                engine.canPlayCard(card, current.id)
            )
        }
        val before = engine.getState().sequenceNumber
        engine.drawCardForPlayer(current.id)
        engine.callUno(current.id)
        assertEquals("nor draws or calls", before, engine.getState().sequenceNumber)
    }

    @Test
    fun `a finished round accepts no further actions`() {
        // A CallUno arriving after the round ends used to re-broadcast GameOver over a completed
        // game.
        val (engine, state) = table()
        engine.setStateForTest(state.copy(phase = GamePhase.ROUND_OVER, winnerId = "p0"))
        val before = engine.getState().sequenceNumber

        engine.callUno("p1")
        engine.penalizeUnoNotCalled("p1", "p0")
        engine.drawCardForPlayer(state.players[state.currentPlayerIndex].id)

        assertEquals("a finished round must be immutable", before, engine.getState().sequenceNumber)
        assertEquals("and keep its winner", "p0", engine.getState().winnerId)
    }

    // ── Catching ────────────────────────────────────────────────────────────

    @Test
    fun `you cannot catch yourself`() {
        // Self-reporting would be a free way to take the penalty on your own terms.
        val (engine, state) = table()
        val target = state.players[1]
        engine.setStateForTest(
            state.copy(
                phase = GamePhase.PLAYING,
                players = state.players.toMutableList().also {
                    it[1] = target.copy(hand = mutableListOf(Card(900, CardColor.RED, CardType.FIVE)))
                }
            )
        )
        val before = engine.getState().getPlayerById(target.id)!!.hand.size
        engine.penalizeUnoNotCalled(target.id, target.id)
        assertEquals(
            "catching yourself must do nothing",
            before, engine.getState().getPlayerById(target.id)!!.hand.size
        )
    }

    @Test
    fun `a spectator cannot catch a live player`() {
        // Someone knocked out still sees every seat; they must not be able to change the round.
        val (engine, state) = table()
        engine.setStateForTest(
            state.copy(
                phase = GamePhase.PLAYING,
                players = state.players.toMutableList().also {
                    it[0] = it[0].copy(isConnected = false)                       // out of the round
                    it[1] = it[1].copy(hand = mutableListOf(Card(900, CardColor.RED, CardType.FIVE)))
                }
            )
        )
        val before = engine.getState().getPlayerById("p1")!!.hand.size
        engine.penalizeUnoNotCalled("p0", "p1")
        assertEquals(
            "a knocked-out reporter must not be able to penalise anyone",
            before, engine.getState().getPlayerById("p1")!!.hand.size
        )
    }

    @Test
    fun `a player who declared cannot be caught`() {
        val (engine, state) = table()
        engine.setStateForTest(
            state.copy(
                phase = GamePhase.PLAYING,
                players = state.players.toMutableList().also {
                    it[1] = it[1].copy(
                        hand = mutableListOf(Card(900, CardColor.RED, CardType.FIVE)),
                        hasCalledUno = true
                    )
                }
            )
        )
        val before = engine.getState().getPlayerById("p1")!!.hand.size
        engine.penalizeUnoNotCalled("p0", "p1")
        assertEquals(before, engine.getState().getPlayerById("p1")!!.hand.size)
    }

    @Test
    fun `repeated catches on the same lapse only penalise once`() {
        // Every client sees the same catchable seat; several may tap at once, and the messages
        // arrive one after another at the host.
        val (engine, state) = table()
        engine.setStateForTest(
            state.copy(
                phase = GamePhase.PLAYING,
                players = state.players.toMutableList().also {
                    it[1] = it[1].copy(hand = mutableListOf(Card(900, CardColor.RED, CardType.FIVE)))
                }
            )
        )
        engine.penalizeUnoNotCalled("p0", "p1")
        val afterFirst = engine.getState().getPlayerById("p1")!!.hand.size
        assertEquals("the first catch draws two", 3, afterFirst)

        engine.penalizeUnoNotCalled("p2", "p1")
        engine.penalizeUnoNotCalled("p3", "p1")
        assertEquals(
            "a second and third catch for the same lapse must not stack",
            afterFirst, engine.getState().getPlayerById("p1")!!.hand.size
        )
    }
}

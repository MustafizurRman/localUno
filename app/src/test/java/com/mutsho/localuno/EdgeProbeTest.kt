package com.mutsho.localuno

import com.mutsho.localuno.engine.GameEngine
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor as Col
import com.mutsho.localuno.model.CardType as T
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.NetworkMessage
import com.mutsho.localuno.model.Player
import com.mutsho.localuno.network.MessageSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The corners: an empty deck, a table down to one player, ids that do not exist, and long random
 * games checked against the things that must be true after every single move.
 *
 * Deliberately harsher than the rule probes. The rules are the part somebody notices immediately;
 * these are the parts that only show up on the fourth round of a long evening, which is exactly
 * when nobody wants to restart.
 */
class EdgeProbeTest {

    private val P = Probe

    // ── Running the deck dry ────────────────────────────────────────────────

    /** A table whose draw pile is empty and whose discard pile holds [buried] spent cards. */
    private fun exhausted(buried: Int, mode: GameMode = GameMode.CLASSIC): GameEngine {
        val e = P.table(
            listOf(listOf(P.card(1, Col.BLUE, T.NINE)), listOf(P.card(2, Col.GREEN, T.TWO))),
            settings = P.settings(mode = mode), colour = Col.RED, seatCount = 2
        )
        val pile = (0 until buried).map { P.card(600 + it, Col.RED, T.THREE) }
        e.setStateForTest(
            e.getState().copy(
                drawPile = emptyList(),
                discardPile = pile + P.card(500, Col.RED, T.THREE)
            )
        )
        return e
    }

    @Test fun `drawing from an empty pile still gives a card`() {
        val e = exhausted(buried = 10)
        val before = P.count(e, "p0")
        e.drawCardForPlayer("p0")
        assertTrue("the discard has to be recycled", P.count(e, "p0") > before)
    }

    @Test fun `recycling leaves the face-up card on the table`() {
        val e = exhausted(buried = 10)
        e.drawCardForPlayer("p0")
        assertTrue("something must still be face up", e.getState().discardPile.isNotEmpty())
    }

    @Test fun `recycling conserves the deck`() {
        val e = exhausted(buried = 10)
        val before = P.allCards(e.getState()).size
        e.drawCardForPlayer("p0")
        assertEquals(before, P.allCards(e.getState()).size)
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    @Test fun `a truly empty table does not hang or duplicate`() {
        // Nothing in the pile and nothing to recycle. It may refuse to deal a card; it must not
        // invent one or spin.
        val e = exhausted(buried = 0)
        val before = P.allCards(e.getState()).size
        e.drawCardForPlayer("p0")
        assertEquals(before, P.allCards(e.getState()).size)
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    @Test fun `an owed stack bigger than the deck does not duplicate cards`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.BLUE, T.NINE)), listOf(P.card(2, Col.GREEN, T.TWO))),
            settings = P.settings(stacking = true), colour = Col.RED,
            turn = 0, pendingDraw = 40, seatCount = 2
        )
        val pile = (0 until 6).map { P.card(700 + it, Col.RED, T.THREE) }
        e.setStateForTest(e.getState().copy(drawPile = pile))
        val before = P.allCards(e.getState()).size
        e.drawCardForPlayer("p0")
        assertEquals(before, P.allCards(e.getState()).size)
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    @Test fun `No Mercy draw-until-playable terminates on a hopeless hand`() {
        // Nothing in the pile matches, so the loop has to stop on its own rather than run forever.
        val e = P.table(
            listOf(listOf(P.card(1, Col.BLUE, T.NINE)), listOf(P.card(2, Col.GREEN, T.TWO))),
            settings = P.settings(mode = GameMode.NO_MERCY), colour = Col.RED, seatCount = 2
        )
        val pile = (0 until 12).map { P.card(800 + it, Col.BLUE, T.ONE) }
        e.setStateForTest(
            e.getState().copy(drawPile = pile, discardPile = listOf(P.card(500, Col.RED, T.THREE)))
        )
        e.drawCardForPlayer("p0")
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    // ── Ids that are not there ──────────────────────────────────────────────

    @Test fun `playing as a player who does not exist changes nothing`() {
        val e = P.table(listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf()), seatCount = 2)
        val before = e.getState().sequenceNumber
        e.playCard("nobody", P.card(1, Col.RED, T.NINE))
        assertEquals(before, e.getState().sequenceNumber)
    }

    @Test fun `drawing as a player who does not exist changes nothing`() {
        val e = P.table(listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf()), seatCount = 2)
        val before = P.allCards(e.getState()).size
        e.drawCardForPlayer("nobody")
        assertEquals(before, P.allCards(e.getState()).size)
    }

    @Test fun `calling UNO as a stranger changes nothing`() {
        val e = P.table(listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf()), seatCount = 2)
        val before = e.getState().sequenceNumber
        e.callUno("nobody")
        assertEquals(before, e.getState().sequenceNumber)
    }

    @Test fun `catching a stranger changes nothing`() {
        val e = P.table(listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf()), seatCount = 2)
        val before = P.allCards(e.getState()).size
        e.penalizeUnoNotCalled("p1", "nobody")
        assertEquals(before, P.allCards(e.getState()).size)
    }

    @Test fun `swapping with a stranger leaves the deck intact`() {
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.SEVEN), P.card(2, Col.BLUE, T.ONE)),
                listOf(P.card(3, Col.BLUE, T.TWO))
            ),
            settings = P.settings(sevenSwaps = true), seatCount = 2
        )
        e.playCard("p0", P.card(1, Col.RED, T.SEVEN))
        val before = P.allCards(e.getState()).size
        e.chooseSwapTarget("p0", "ghost")
        assertEquals(before, P.allCards(e.getState()).size)
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    @Test fun `disconnecting a stranger does not pause the table`() {
        val e = P.table(listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf()), seatCount = 2)
        e.beginDisconnectCountdown("nobody")
        assertEquals(GamePhase.PLAYING, e.getState().phase)
    }

    @Test fun `a stranger leaving changes nothing`() {
        val e = P.table(listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf()), seatCount = 2)
        val before = e.getState().players.count { it.isConnected }
        e.playerLeft("nobody")
        assertEquals(before, e.getState().players.count { it.isConnected })
    }

    @Test fun `the same card cannot be played twice`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.NINE), P.card(90, Col.RED, T.ONE)), listOf(), listOf()),
            seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.NINE))
        val before = P.allCards(e.getState()).size
        e.playCard("p0", P.card(1, Col.RED, T.NINE))
        assertEquals(before, P.allCards(e.getState()).size)
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    // ── Down to the last player ─────────────────────────────────────────────

    @Test fun `the turn skips a player who has left`() {
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.NINE), P.card(90, Col.RED, T.ONE)),
                listOf(P.card(2, Col.BLUE, T.ONE)),
                listOf(P.card(3, Col.BLUE, T.TWO))
            ),
            turn = 0, seatCount = 3
        )
        e.playerLeft("p1")
        e.playCard("p0", P.card(1, Col.RED, T.NINE))
        assertEquals("p1 is gone, so play reaches p2", "p2", P.turnId(e))
    }

    @Test fun `the current player is always someone still in the game`() {
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.NINE), P.card(90, Col.RED, T.ONE)),
                listOf(P.card(2, Col.BLUE, T.ONE)),
                listOf(P.card(3, Col.BLUE, T.TWO))
            ),
            turn = 0, seatCount = 3
        )
        e.playerLeft("p1")
        e.playCard("p0", P.card(1, Col.RED, T.NINE))
        assertTrue(e.getState().currentPlayer?.isConnected == true)
    }

    @Test fun `a knocked out player is not handed the turn`() {
        val big = (0 until 30).map { P.card(100 + it, Col.BLUE, T.NINE) }
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.DRAW_TWO), P.card(90, Col.RED, T.ONE)),
                big,
                listOf(P.card(3, Col.BLUE, T.TWO))
            ),
            settings = P.settings(mode = GameMode.NO_MERCY, mercyThreshold = 25), seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.DRAW_TWO))
        assertFalse("p1 exploded and must not be on the clock", P.turnId(e) == "p1")
    }

    @Test fun `a knockout conserves the deck`() {
        val big = (0 until 30).map { P.card(100 + it, Col.BLUE, T.NINE) }
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.DRAW_TWO), P.card(90, Col.RED, T.ONE)),
                big,
                listOf(P.card(3, Col.BLUE, T.TWO))
            ),
            settings = P.settings(mode = GameMode.NO_MERCY, mercyThreshold = 25), seatCount = 3
        )
        val before = P.allCards(e.getState()).size
        e.playCard("p0", P.card(1, Col.RED, T.DRAW_TWO))
        assertEquals(before, P.allCards(e.getState()).size)
        assertTrue(P.duplicateIds(e.getState()).isEmpty())
    }

    @Test fun `a knocked out player still scores what they were holding`() {
        val big = (0 until 30).map { P.card(100 + it, Col.BLUE, T.NINE) }
        val e = P.table(
            listOf(
                listOf(P.card(1, Col.RED, T.DRAW_TWO), P.card(90, Col.RED, T.ONE)),
                big,
                listOf(P.card(3, Col.BLUE, T.TWO))
            ),
            settings = P.settings(mode = GameMode.NO_MERCY, mercyThreshold = 25), seatCount = 3
        )
        e.playCard("p0", P.card(1, Col.RED, T.DRAW_TWO))
        assertTrue(
            "exploding must rank worse than surviving, so it cannot score zero",
            (e.calculateScores()["p1"] ?: 0) > 0
        )
    }

    // ── Every message on the wire ───────────────────────────────────────────

    private fun roundTrip(m: NetworkMessage): NetworkMessage? =
        MessageSerializer.deserialize(MessageSerializer.serialize(m))

    @Test fun `join response survives the wire`() {
        assertNotNull(roundTrip(NetworkMessage.JoinResponse(true, null, "id")) as? NetworkMessage.JoinResponse)
    }

    @Test fun `game start survives the wire`() {
        assertNotNull(roundTrip(NetworkMessage.GameStart()) as? NetworkMessage.GameStart)
    }

    @Test fun `draw card survives the wire`() {
        assertEquals("p2", (roundTrip(NetworkMessage.DrawCard("p2")) as? NetworkMessage.DrawCard)?.playerId)
    }

    @Test fun `call uno survives the wire`() {
        assertEquals("p3", (roundTrip(NetworkMessage.CallUno("p3")) as? NetworkMessage.CallUno)?.playerId)
    }

    @Test fun `uno not called survives the wire`() {
        assertNotNull(roundTrip(NetworkMessage.UnoNotCalled("p1", "p2")) as? NetworkMessage.UnoNotCalled)
    }

    @Test fun `player disconnected survives the wire`() {
        assertNotNull(roundTrip(NetworkMessage.PlayerDisconnected("p1", "P1")) as? NetworkMessage.PlayerDisconnected)
    }

    @Test fun `player reconnected survives the wire`() {
        assertNotNull(roundTrip(NetworkMessage.PlayerReconnected("p1")) as? NetworkMessage.PlayerReconnected)
    }

    @Test fun `player left survives the wire`() {
        assertNotNull(roundTrip(NetworkMessage.PlayerLeft("p1")) as? NetworkMessage.PlayerLeft)
    }

    @Test fun `emoji reaction survives the wire`() {
        assertNotNull(roundTrip(NetworkMessage.EmojiReaction("p1", "gg")) as? NetworkMessage.EmojiReaction)
    }

    @Test fun `resync request survives the wire`() {
        assertNotNull(roundTrip(NetworkMessage.ResyncRequest("p1", 7L)) as? NetworkMessage.ResyncRequest)
    }

    @Test fun `return to lobby survives the wire`() {
        assertNotNull(roundTrip(NetworkMessage.ReturnToLobby()) as? NetworkMessage.ReturnToLobby)
    }

    @Test fun `host ended game survives the wire`() {
        assertNotNull(roundTrip(NetworkMessage.HostEndedGame()) as? NetworkMessage.HostEndedGame)
    }

    @Test fun `choose swap target survives the wire`() {
        assertNotNull(roundTrip(NetworkMessage.ChooseSwapTarget("p2")) as? NetworkMessage.ChooseSwapTarget)
    }

    @Test fun `play rejected survives the wire`() {
        assertNotNull(roundTrip(NetworkMessage.PlayRejected("too slow")) as? NetworkMessage.PlayRejected)
    }

    @Test fun `ping survives the wire`() {
        assertNotNull(roundTrip(NetworkMessage.Ping()) as? NetworkMessage.Ping)
    }

    @Test fun `a message never deserializes as the wrong type`() {
        val back = roundTrip(NetworkMessage.DrawCard("p1"))
        assertNull("a draw must not arrive as a play", back as? NetworkMessage.PlayCard)
    }

    @Test fun `a name with a newline cannot split the wire framing`() {
        // Framing is newline-delimited; a name carrying one would otherwise cut the message in two.
        val msg = NetworkMessage.JoinRequest("bad\nname", "id-1", null, 0)
        val line = MessageSerializer.serialize(msg)
        assertEquals("exactly one frame", 1, line.trimEnd('\n').count { it == '\n' } + 1)
    }

    @Test fun `a name with a quote survives the wire`() {
        val msg = NetworkMessage.JoinRequest("Mut\"sho", "id-1", null, 0)
        assertEquals("Mut\"sho", (roundTrip(msg) as? NetworkMessage.JoinRequest)?.playerName)
    }

    @Test fun `an emoji name survives the wire`() {
        val msg = NetworkMessage.JoinRequest("🎴 Mutsho", "id-1", null, 0)
        assertEquals("🎴 Mutsho", (roundTrip(msg) as? NetworkMessage.JoinRequest)?.playerName)
    }

    // ── Long games, checked after every move ────────────────────────────────

    /** Everything that must be true of a table between any two moves. */
    private fun invariants(e: GameEngine, where: String, expectedCards: Int) {
        val s = e.getState()
        assertEquals("$where: deck changed size", expectedCards, P.allCards(s).size)
        assertTrue("$where: duplicate cards ${P.duplicateIds(s)}", P.duplicateIds(s).isEmpty())
        assertTrue("$where: pending draw went negative", s.pendingDrawCount >= 0)
        assertTrue(
            "$where: current player index out of range (${s.currentPlayerIndex}/${s.players.size})",
            s.currentPlayerIndex in s.players.indices
        )
        assertTrue("$where: nothing face up", s.discardPile.isNotEmpty())
        if (s.phase == GamePhase.PLAYING) {
            assertTrue("$where: the turn rests on somebody out of the game",
                s.currentPlayer?.isConnected == true)
        }
        if (s.winnerId != null) {
            assertTrue(
                "$where: a winner exists while the round is still running",
                s.phase == GamePhase.ROUND_OVER || s.phase == GamePhase.GAME_OVER
            )
        }
    }

    /** Plays a whole round at random and checks the invariants after every single move. */
    private fun fuzz(seed: Int, mode: GameMode, players: Int, houseRules: Boolean) {
        val rng = Random(seed)
        val engine = GameEngine(
            P.settings(
                mode = mode,
                stacking = houseRules,
                lastCardMustBeNumber = houseRules,
                zeroRotates = houseRules,
                sevenSwaps = houseRules,
                seats = players,
                mercyThreshold = 25
            ),
            Random(seed)
        )
        engine.initializeGame(P.seats(players))
        val total = P.allCards(engine.getState()).size
        invariants(engine, "seed $seed after deal", total)

        repeat(300) { move ->
            val s = engine.getState()
            if (s.phase == GamePhase.ROUND_OVER || s.phase == GamePhase.GAME_OVER) return
            val current = s.currentPlayer ?: return

            when {
                s.phase == GamePhase.CHOOSING_SWAP -> {
                    val target = s.players.firstOrNull { it.id != current.id && it.isConnected }
                    if (target != null) engine.chooseSwapTarget(current.id, target.id)
                    else engine.autoResolveSwap()
                }
                else -> {
                    val playable = engine.getPlayableCards(current.id)
                    if (playable.isNotEmpty() && rng.nextInt(10) > 2) {
                        val card = playable[rng.nextInt(playable.size)]
                        val colour = if (card.color == Col.WILD) {
                            listOf(Col.RED, Col.YELLOW, Col.GREEN, Col.BLUE)[rng.nextInt(4)]
                        } else null
                        engine.playCard(current.id, card, colour)
                    } else {
                        engine.drawCardForPlayer(current.id)
                    }
                }
            }
            // Occasionally somebody says UNO, or catches somebody who didn't.
            if (rng.nextInt(20) == 0) engine.callUno(current.id)
            if (rng.nextInt(30) == 0) {
                val victim = engine.getState().players.random(rng)
                engine.penalizeUnoNotCalled(current.id, victim.id)
            }
            invariants(engine, "seed $seed move $move", total)
        }
    }

    @Test fun `classic tables hold their invariants`() {
        for (seed in 1..12) for (n in 2..6) fuzz(seed * 31 + n, GameMode.CLASSIC, n, houseRules = false)
    }

    @Test fun `classic with every house rule holds its invariants`() {
        for (seed in 1..12) for (n in 2..6) fuzz(seed * 37 + n, GameMode.CLASSIC, n, houseRules = true)
    }

    @Test fun `no mercy holds its invariants`() {
        for (seed in 1..12) for (n in 2..6) fuzz(seed * 41 + n, GameMode.NO_MERCY, n, houseRules = false)
    }

    @Test fun `no mercy with every house rule holds its invariants`() {
        for (seed in 1..12) for (n in 2..6) fuzz(seed * 43 + n, GameMode.NO_MERCY, n, houseRules = true)
    }

    @Test fun `a full eight-seat table holds its invariants`() {
        for (seed in 1..8) fuzz(seed * 53, GameMode.NO_MERCY, 8, houseRules = true)
    }

    @Test fun `a two-seat table holds its invariants`() {
        for (seed in 1..8) fuzz(seed * 59, GameMode.NO_MERCY, 2, houseRules = true)
    }

    @Test fun `the same seed plays the same game twice`() {
        // Reproducibility is what makes a failing fuzz seed worth anything at all.
        fun play(): List<Int> {
            val e = GameEngine(P.settings(seats = 4), Random(4242))
            e.initializeGame(P.seats(4))
            return e.getState().players.flatMap { p -> p.hand.map { it.id } }
        }
        assertEquals(play(), play())
    }
}

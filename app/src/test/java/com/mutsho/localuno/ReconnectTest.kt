package com.mutsho.localuno

import com.mutsho.localuno.engine.GameEngine
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GameEngine.reconnectPlayer - the host-side half of rejoining a match. The client-side retry loop
 * (GameViewModel.handleClientDisconnected) and the transport-level socket re-keying (GameServer)
 * aren't reachable from a plain unit test, so this covers the one piece that's pure state
 * transition: does resuming actually put the round back exactly where it paused, and does it
 * correctly refuse everything that ISN'T "the seat currently being waited on."
 */
class ReconnectTest {

    private fun players(n: Int) = (0 until n).map { i -> Player(id = "p$i", name = "P$i") }

    @Test
    fun `reconnecting the waited-on player resumes the round unchanged`() {
        val engine = GameEngine(GameSettings(gameMode = GameMode.CLASSIC, turnTimeoutSeconds = null))
        val started = engine.initializeGame(players(3))
        val turnBefore = started.currentPlayerIndex
        val handBefore = started.getPlayerById("p1")!!.hand

        val paused = engine.beginDisconnectCountdown("p1")
        assertEquals(GamePhase.PAUSED_DISCONNECT, paused.phase)
        assertEquals("p1", paused.disconnectedPlayerId)

        val resumed = engine.reconnectPlayer("p1")
        assertEquals(GamePhase.PLAYING, resumed.phase)
        assertNull(resumed.disconnectedPlayerId)
        assertTrue(resumed.getPlayerById("p1")!!.isConnected)
        // Nothing about the round itself moved while paused - same seat's turn, same hand back.
        assertEquals(turnBefore, resumed.currentPlayerIndex)
        assertEquals(handBefore, resumed.getPlayerById("p1")!!.hand)
    }

    @Test
    fun `reconnecting a player nobody is waiting on is a no-op`() {
        val engine = GameEngine(GameSettings(gameMode = GameMode.CLASSIC, turnTimeoutSeconds = null))
        val started = engine.initializeGame(players(3))
        val before = started.sequenceNumber

        // Nobody has disconnected - phase is plain PLAYING.
        val result = engine.reconnectPlayer("p1")
        assertEquals(before, result.sequenceNumber)
        assertEquals(GamePhase.PLAYING, result.phase)
    }

    @Test
    fun `reconnecting the wrong seat while paused on someone else is a no-op`() {
        val engine = GameEngine(GameSettings(gameMode = GameMode.CLASSIC, turnTimeoutSeconds = null))
        engine.initializeGame(players(3))
        val paused = engine.beginDisconnectCountdown("p1")
        val before = paused.sequenceNumber

        // p2 didn't drop - p1 did. p2 trying to "reconnect" must not resume the round out from
        // under the seat that's actually being waited on.
        val result = engine.reconnectPlayer("p2")
        assertEquals(before, result.sequenceNumber)
        assertEquals(GamePhase.PAUSED_DISCONNECT, result.phase)
        assertEquals("p1", result.disconnectedPlayerId)
    }

    @Test
    fun `once the grace window expires the seat cannot be reconnected any more`() {
        val engine = GameEngine(GameSettings(gameMode = GameMode.CLASSIC, turnTimeoutSeconds = null))
        engine.initializeGame(players(3))
        engine.beginDisconnectCountdown("p1")

        // Run the countdown all the way out - this is what knocks p1 out for the round.
        var state = engine.getState()
        while (state.phase == GamePhase.PAUSED_DISCONNECT) {
            state = engine.tickDisconnectCountdown()
        }
        assertTrue("p1 should be knocked out, not still connected", !state.getPlayerById("p1")!!.isConnected)
        assertTrue("p1's hand should have been cleared on knockout", state.getPlayerById("p1")!!.hand.isEmpty())

        val before = state.sequenceNumber
        val result = engine.reconnectPlayer("p1")
        // Phase is no longer PAUSED_DISCONNECT (it resumed to PLAYING once knocked out), so
        // reconnectPlayer's own guard refuses regardless of who asks.
        assertEquals(before, result.sequenceNumber)
        assertTrue(result.getPlayerById("p1")!!.hand.isEmpty())
    }
}

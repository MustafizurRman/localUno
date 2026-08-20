package com.mutsho.localuno

import com.mutsho.localuno.model.CardColor as Col
import com.mutsho.localuno.model.CardType as T
import com.mutsho.localuno.model.Direction
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.NetworkMessage
import com.mutsho.localuno.network.JoinLink
import com.mutsho.localuno.network.MessageSerializer
import com.mutsho.localuno.viewmodel.applyStateUpdate
import com.mutsho.localuno.viewmodel.buildStateUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What survives the trip between two phones.
 *
 * Everything here is invisible on one device: the host is always right about its own game, so a
 * field that never leaves, or arrives wrong, shows up only as one player seeing a different table
 * from everyone else - and looks exactly like lag until somebody compares two screens.
 */
class WireProbeTest {

    private val P = Probe

    private fun hostTable() = P.table(
        listOf(
            listOf(P.card(1, Col.RED, T.NINE), P.card(2, Col.BLUE, T.ONE)),
            listOf(P.card(3, Col.GREEN, T.TWO)),
            listOf(P.card(4, Col.YELLOW, T.THREE))
        ),
        settings = P.settings(mode = GameMode.NO_MERCY, stacking = true),
        top = P.card(500, Col.RED, T.THREE), colour = Col.RED, seatCount = 3
    )

    private fun update(engine: com.mutsho.localuno.engine.GameEngine, forId: String) =
        buildStateUpdate(engine.getState(), engine.getState().getPlayerById(forId)!!, 1_000L)!!

    // ── What the host sends ─────────────────────────────────────────────────

    @Test fun `a guest is told the top card`() {
        assertEquals(9500, update(hostTable(), "p1").topCard.id)
    }

    @Test fun `a guest is told the colour in force`() {
        assertEquals(Col.RED, update(hostTable(), "p1").currentColor)
    }

    @Test fun `a guest is told the direction`() {
        assertEquals(Direction.CLOCKWISE, update(hostTable(), "p1").direction)
    }

    @Test fun `a guest is told the phase`() {
        assertEquals(GamePhase.PLAYING, update(hostTable(), "p1").phase)
    }

    @Test fun `a guest receives their own hand in full`() {
        assertEquals(listOf(9003), update(hostTable(), "p1").playerHand.map { it.id })
    }

    @Test fun `a guest is never sent another player's cards`() {
        val u = update(hostTable(), "p1")
        assertFalse(
            "opponent hands must not travel",
            u.playerHand.any { it.id == 9001 || it.id == 9004 }
        )
    }

    @Test fun `a guest is told how many cards each opponent holds`() {
        assertEquals(2, update(hostTable(), "p1").opponentCardCounts["p0"])
    }

    @Test fun `a guest is not listed among its own opponents`() {
        assertNull(update(hostTable(), "p1").opponentCardCounts["p1"])
    }

    @Test fun `a guest is told the draw pile size`() {
        val e = hostTable()
        assertEquals(e.getState().drawPile.size, update(e, "p1").drawPileCount)
    }

    @Test fun `a guest is told whose turn it is by id`() {
        assertEquals("p0", update(hostTable(), "p1").currentPlayerId)
    }

    @Test fun `a guest is told the pending draw stack`() {
        val e = P.table(
            listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf(P.card(3, Col.GREEN, T.TWO))),
            pendingDraw = 4, seatCount = 2
        )
        assertEquals(4, update(e, "p1").pendingDrawCount)
    }

    @Test fun `a guest is told their own connected state`() {
        assertTrue(update(hostTable(), "p1").myIsConnected)
    }

    @Test fun `a guest is told each opponent's connected state`() {
        val e = hostTable()
        e.playerLeft("p2")
        assertEquals(false, update(e, "p1").opponentConnected["p2"])
    }

    @Test fun `a table with no card face up sends nothing rather than a broken update`() {
        val e = hostTable()
        e.setStateForTest(e.getState().copy(discardPile = emptyList()))
        assertNull(buildStateUpdate(e.getState(), e.getState().players[1], 1_000L))
    }

    // ── What the guest rebuilds ─────────────────────────────────────────────

    @Test fun `applying an update puts the right card on top`() {
        val e = hostTable()
        val out = applyStateUpdate(e.getState().copy(players = emptyList()), update(e, "p1"), "p1", 5_000L)
        assertEquals(9500, out.topCard?.id)
    }

    @Test fun `applying an update gives the guest their own hand`() {
        val e = hostTable()
        val out = applyStateUpdate(e.getState().copy(players = emptyList()), update(e, "p1"), "p1", 5_000L)
        assertEquals(listOf(9003), out.getPlayerById("p1")?.hand?.map { it.id })
    }

    @Test fun `an opponent's hand is rebuilt at the right size`() {
        val e = hostTable()
        val out = applyStateUpdate(e.getState().copy(players = emptyList()), update(e, "p1"), "p1", 5_000L)
        assertEquals(2, out.getPlayerById("p0")?.hand?.size)
    }

    @Test fun `the guest's copy of the turn points at the same player`() {
        val e = hostTable()
        val out = applyStateUpdate(e.getState().copy(players = emptyList()), update(e, "p1"), "p1", 5_000L)
        assertEquals("p0", out.currentPlayer?.id)
    }

    @Test fun `a stale update is ignored`() {
        val e = hostTable()
        val fresh = update(e, "p1")
        val newer = e.getState().copy(sequenceNumber = fresh.sequenceNumber + 5, roundStartTime = fresh.roundStartedAtMillis)
        val out = applyStateUpdate(newer, fresh, "p1", 5_000L)
        assertEquals("the older update must not roll the board back", newer.sequenceNumber, out.sequenceNumber)
    }

    @Test fun `a rematch is not mistaken for a stale update`() {
        // A fresh round restarts sequence numbers from low values while the guest still holds the
        // finished round's high one. Rejecting those would freeze every rematch.
        val e = hostTable()
        val fresh = update(e, "p1")
        val previous = e.getState().copy(
            sequenceNumber = fresh.sequenceNumber + 100,
            roundStartTime = fresh.roundStartedAtMillis + 12_345L
        )
        val out = applyStateUpdate(previous, fresh, "p1", 5_000L)
        assertEquals(fresh.sequenceNumber, out.sequenceNumber)
    }

    @Test fun `the turn clock is rebased onto the guest's own clock`() {
        // Two phones' wall clocks differ by seconds; copying the host's timestamp across leaves the
        // countdown wrong or frozen.
        val e = P.table(listOf(listOf(P.card(1, Col.RED, T.NINE)), listOf()), seatCount = 2)
        e.setStateForTest(e.getState().copy(turnStartedAtMillis = 1_000_000L))
        val u = buildStateUpdate(e.getState(), e.getState().players[1], 1_004_000L)!!
        val out = applyStateUpdate(e.getState().copy(players = emptyList()), u, "p1", 50_000L)
        assertEquals("four seconds already spent, on this device's clock", 46_000L, out.turnStartedAtMillis)
    }

    // ── The join code ───────────────────────────────────────────────────────

    @Test fun `a join code round trips its host`() {
        val link = JoinLink("192.168.1.9", 41000, "Table", hasPin = false)
        assertEquals("192.168.1.9", JoinLink.decode(link.encode())?.host)
    }

    @Test fun `a join code round trips its port`() {
        val link = JoinLink("192.168.1.9", 41000, "Table", hasPin = false)
        assertEquals(41000, JoinLink.decode(link.encode())?.port)
    }

    @Test fun `a join code round trips a name with spaces`() {
        val link = JoinLink("10.0.0.1", 5000, "Mutsho's table", hasPin = false)
        assertEquals("Mutsho's table", JoinLink.decode(link.encode())?.lobbyName)
    }

    @Test fun `a join code carrying a pin needs no typing`() {
        val link = JoinLink("10.0.0.1", 5000, "T", hasPin = true, pin = "4821")
        assertEquals("4821", JoinLink.decode(link.encode())?.pin)
    }

    @Test fun `a foreign QR is rejected`() {
        assertNull(JoinLink.decode("https://example.com/hello"))
    }

    @Test fun `an empty scan is rejected`() {
        assertNull(JoinLink.decode(""))
    }

    @Test fun `a port of zero is rejected`() {
        assertNull(JoinLink.decode("localuno://join?h=1.2.3.4&p=0&n=x&pin=0"))
    }

    @Test fun `a port above the range is rejected`() {
        assertNull(JoinLink.decode("localuno://join?h=1.2.3.4&p=70000&n=x&pin=0"))
    }

    // ── The message envelope ────────────────────────────────────────────────

    @Test fun `a play survives serialization`() {
        val msg = NetworkMessage.PlayCard("p1", P.card(1, Col.RED, T.NINE), Col.GREEN)
        val back = MessageSerializer.deserialize(MessageSerializer.serialize(msg)) as? NetworkMessage.PlayCard
        assertEquals(9001, back?.card?.id)
    }

    @Test fun `a chosen colour survives serialization`() {
        val msg = NetworkMessage.PlayCard("p1", P.card(1, Col.WILD, T.WILD), Col.GREEN)
        val back = MessageSerializer.deserialize(MessageSerializer.serialize(msg)) as? NetworkMessage.PlayCard
        assertEquals(Col.GREEN, back?.chosenColor)
    }

    @Test fun `a timeout message carries whether a card was drawn`() {
        val msg = NetworkMessage.TurnTimedOut("Mutsho", drewCard = true)
        val back = MessageSerializer.deserialize(MessageSerializer.serialize(msg)) as? NetworkMessage.TurnTimedOut
        assertEquals(true, back?.drewCard)
    }

    @Test fun `a state update survives serialization`() {
        val e = hostTable()
        val back = MessageSerializer.deserialize(MessageSerializer.serialize(update(e, "p1")))
        assertNotNull(back as? NetworkMessage.StateUpdate)
    }

    @Test fun `a state update keeps its hand through serialization`() {
        val e = hostTable()
        val back = MessageSerializer.deserialize(MessageSerializer.serialize(update(e, "p1")))
            as? NetworkMessage.StateUpdate
        assertEquals(listOf(9003), back?.playerHand?.map { it.id })
    }

    @Test fun `garbage on the wire is dropped rather than thrown`() {
        assertNull(MessageSerializer.deserialize("this is not json at all"))
    }

    @Test fun `an unknown message type is dropped`() {
        assertNull(MessageSerializer.deserialize("""{"type":"TOTAL_NONSENSE","payload":"{}"}"""))
    }

    @Test fun `an empty line is dropped`() {
        assertNull(MessageSerializer.deserialize(""))
    }

    @Test fun `every serialized message ends in a newline`() {
        // The wire is newline-delimited; a message without one merges into the next.
        assertTrue(MessageSerializer.serialize(NetworkMessage.Ping()).endsWith("\n"))
    }

    @Test fun `a join request survives serialization`() {
        val msg = NetworkMessage.JoinRequest("Mutsho", "id-1", pin = "1234", avatarColor = 3)
        val back = MessageSerializer.deserialize(MessageSerializer.serialize(msg)) as? NetworkMessage.JoinRequest
        assertEquals("1234", back?.pin)
    }

    @Test fun `a join request keeps the avatar colour`() {
        val msg = NetworkMessage.JoinRequest("Mutsho", "id-1", pin = null, avatarColor = 5)
        val back = MessageSerializer.deserialize(MessageSerializer.serialize(msg)) as? NetworkMessage.JoinRequest
        assertEquals(5, back?.avatarColor)
    }
}

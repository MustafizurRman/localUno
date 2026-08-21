package com.mutsho.localuno

import com.mutsho.localuno.model.NetworkMessage
import com.mutsho.localuno.model.PlayerInfo
import com.mutsho.localuno.model.TableRemoval
import com.mutsho.localuno.model.TableRemoval.Verdict
import com.mutsho.localuno.network.MessageSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A host may free any seat but their own, and a removal has to actually hold.
 *
 * The second half is the part worth guarding. A playerId is a stable per-device UUID and the join
 * path treats a familiar id as a rejoin, so "remove" that only edits the roster is a control that
 * appears to work and does nothing - the guest walks straight back in. That failure is invisible
 * to a test that only checks the roster shrank.
 */
class RemovePlayerProbeTest {

    private fun host(id: String = "host") =
        PlayerInfo(id, "Mutsho", 0, isHost = true, isConnected = true)

    private fun guest(id: String = "g1") =
        PlayerInfo(id, "Sam", 1, isHost = false, isConnected = true)

    private fun bot(id: String = "bot-1") =
        PlayerInfo(id, "Circuit", 2, isHost = false, isConnected = true, isBot = true)

    private fun decide(
        target: PlayerInfo?,
        isHost: Boolean = true,
        started: Boolean = false,
        me: String? = "host"
    ) = TableRemoval.decide(isHost, started, target, me)

    // ── Who may remove whom ─────────────────────────────────────────────────

    @Test fun `a host may remove a guest`() {
        assertEquals(Verdict.RemovePerson, decide(guest()))
    }

    @Test fun `a host may remove a bot`() {
        assertEquals(Verdict.RemoveBot, decide(bot()))
    }

    @Test fun `a guest may not remove anyone`() {
        assertTrue(decide(guest("g2"), isHost = false) is Verdict.Refuse)
    }

    @Test fun `a host may not remove themself`() {
        // Leaving is a different control with a different meaning - it ends the table for everyone.
        assertTrue(decide(host()) is Verdict.Refuse)
    }

    @Test fun `a host may not remove themself even if the host flag is missing`() {
        // Belt and braces: the id check stands on its own, so a roster that lost its host flag
        // cannot be used to remove the one seat running the engine.
        val meWithoutFlag = PlayerInfo("host", "Mutsho", 0, isHost = false, isConnected = true)
        assertTrue(decide(meWithoutFlag) is Verdict.Refuse)
    }

    @Test fun `nobody may be removed once the round is underway`() {
        // The engine holds their hand and the turn order depends on their seat.
        assertTrue(decide(guest(), started = true) is Verdict.Refuse)
    }

    @Test fun `a bot may not be removed once the round is underway either`() {
        assertTrue(decide(bot(), started = true) is Verdict.Refuse)
    }

    @Test fun `removing a seat nobody is sitting in is refused rather than crashing`() {
        assertTrue(decide(null) is Verdict.Refuse)
    }

    @Test fun `a disconnected guest can still be removed`() {
        // The ordinary case: someone dropped, their ghost is holding a seat, the host wants it back.
        val gone = guest().copy(isConnected = false)
        assertEquals(Verdict.RemovePerson, decide(gone))
    }

    // ── Bots and people are removed differently ─────────────────────────────

    @Test fun `a bot removal tells nobody`() {
        // The host runs bots itself, so there is no peer to notify and nothing to keep out.
        assertEquals(Verdict.RemoveBot, decide(bot()))
    }

    @Test fun `a person removal is distinguishable from a bot removal`() {
        // The caller branches on this to decide whether to send RemovedFromTable and add the id to
        // the keep-out set. Collapsing the two verdicts would silently drop both.
        assertTrue(decide(bot()) != decide(guest()))
    }

    // ── The message that makes it stick ─────────────────────────────────────

    @Test fun `a removal notice survives serialization`() {
        val msg = NetworkMessage.RemovedFromTable()
        val back = MessageSerializer.deserialize(MessageSerializer.serialize(msg))
        assertNotNull(back as? NetworkMessage.RemovedFromTable)
    }

    @Test fun `a removal notice carries its reason`() {
        // The guest is shown this verbatim. A notice that arrives without it explains nothing.
        val msg = NetworkMessage.RemovedFromTable("The host removed you from the table")
        val back = MessageSerializer.deserialize(MessageSerializer.serialize(msg))
            as? NetworkMessage.RemovedFromTable
        assertEquals("The host removed you from the table", back?.reason)
    }

    @Test fun `a removal notice from an older host still deserializes`() {
        // The reason is defaulted, so a payload without the field must not come back null - a
        // dropped notice is a guest whose client quietly tries to reconnect to a table that
        // just removed them.
        val json = """{"type":"REMOVED_FROM_TABLE","payload":"{\"sequenceNumber\":0}"}"""
        val back = MessageSerializer.deserialize(json) as? NetworkMessage.RemovedFromTable
        assertNotNull(back)
        assertTrue("a defaulted reason must still be showable", !back?.reason.isNullOrBlank())
    }

    @Test fun `a removal notice is its own message type`() {
        // It must not be confused with the host ending the game: that one goes to everybody and
        // means the match is over, this one goes to one person and means the table goes on without
        // them.
        assertEquals("REMOVED_FROM_TABLE", NetworkMessage.RemovedFromTable().type)
        assertTrue(NetworkMessage.RemovedFromTable().type != NetworkMessage.HostEndedGame().type)
    }

    @Test fun `a removal notice ends in a newline like every other message`() {
        assertTrue(MessageSerializer.serialize(NetworkMessage.RemovedFromTable()).endsWith("\n"))
    }
}

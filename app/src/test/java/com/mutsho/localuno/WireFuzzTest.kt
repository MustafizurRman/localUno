package com.mutsho.localuno

import com.mutsho.localuno.model.NetworkMessage
import com.mutsho.localuno.network.MessageSerializer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Malformed payloads from a hostile peer.
 *
 * The existing wire tests prove that garbage does not crash the PARSER. That is the easy half and
 * it was already true. This asks the harder question: what does the parser hand back when the JSON
 * is well-formed but the contents are not, and is that thing safe for the rest of the app to use?
 *
 * Gson does not call Kotlin constructors. It allocates the object directly and writes fields, so
 * Kotlin's non-null types are not enforced and default values are not applied. A payload of `{}`
 * therefore produces an object whose non-null String and enum fields are all null - and nothing
 * fails until some entirely different part of the app dereferences one, far from the parse site
 * and with nothing on the trace to say a peer caused it.
 *
 * Every message here is one any device on the network can send at any time, with no PIN and no
 * successful join.
 */
class WireFuzzTest {

    private fun wire(type: String, payload: String): String {
        // The envelope is itself JSON-encoded, exactly as MessageSerializer writes it.
        val escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"type":"$type","payload":"$escaped"}"""
    }

    private fun parse(type: String, payload: String) =
        MessageSerializer.deserialize(wire(type, payload))

    // ── Missing required fields ─────────────────────────────────────────────

    @Test fun `an empty play payload is rejected rather than half-built`() {
        // Would otherwise be PlayCard(playerId = null, card = null) with non-null declared types.
        assertNull(parse("PLAY_CARD", "{}"))
    }

    @Test fun `a play with a null card is rejected`() {
        assertNull(parse("PLAY_CARD", """{"playerId":"p1","card":null}"""))
    }

    @Test fun `a play with a null player id is rejected`() {
        assertNull(parse("PLAY_CARD", """{"playerId":null,"card":{"id":1,"color":"RED","type":"THREE"}}"""))
    }

    @Test fun `an empty join payload is rejected`() {
        assertNull(parse("JOIN_REQUEST", "{}"))
    }

    @Test fun `an empty state update is rejected`() {
        // The worst one: topCard, currentColor, playerHand and phase are all non-null declared and
        // all arrive null, then the client rebuilds its whole board from it.
        assertNull(parse("STATE_UPDATE", "{}"))
    }

    @Test fun `a state update missing only its top card is rejected`() {
        assertNull(
            parse(
                "STATE_UPDATE",
                """{"currentPlayerIndex":0,"direction":"CLOCKWISE","currentColor":"RED",
                   "playerHand":[],"opponentCardCounts":{},"pendingDrawCount":0,"phase":"PLAYING"}"""
                    .replace("\n", "").replace("  ", "")
            )
        )
    }

    @Test fun `an empty card is rejected`() {
        assertNull(parse("PLAY_CARD", """{"playerId":"p1","card":{}}"""))
    }

    // ── Wrong types and unknown enum values ─────────────────────────────────

    @Test fun `an unknown colour is rejected rather than left null`() {
        // Gson maps an unrecognised enum name to null, in a field declared non-null.
        assertNull(parse("PLAY_CARD", """{"playerId":"p1","card":{"id":1,"color":"MAUVE","type":"THREE"}}"""))
    }

    @Test fun `an unknown card type is rejected`() {
        assertNull(parse("PLAY_CARD", """{"playerId":"p1","card":{"id":1,"color":"RED","type":"NUKE"}}"""))
    }

    @Test fun `an unknown phase is rejected`() {
        assertNull(parse("STATE_UPDATE", """{"phase":"MELTDOWN"}"""))
    }

    @Test fun `a string where a number belongs does not crash the parser`() {
        // Gson throws here; the contract is that deserialize converts that into null.
        assertNull(parse("PLAY_CARD", """{"playerId":"p1","card":{"id":"not-a-number","color":"RED","type":"THREE"}}"""))
    }

    @Test fun `an array where an object belongs does not crash the parser`() {
        assertNull(parse("PLAY_CARD", """{"playerId":"p1","card":[1,2,3]}"""))
    }

    // ── Hostile but well-typed values ───────────────────────────────────────

    @Test fun `a valid play still parses`() {
        // The control. A fuzz guard that rejected everything would pass every test above and break
        // the game completely.
        val ok = parse("PLAY_CARD", """{"playerId":"p1","card":{"id":9001,"color":"RED","type":"THREE"},"sequenceNumber":4}""")
        assertNotNull(ok as? NetworkMessage.PlayCard)
    }

    @Test fun `a valid state update still parses`() {
        val e = Probe.table(
            listOf(listOf(Probe.card(1, com.mutsho.localuno.model.CardColor.RED, com.mutsho.localuno.model.CardType.THREE)),
                   listOf(Probe.card(2, com.mutsho.localuno.model.CardColor.BLUE, com.mutsho.localuno.model.CardType.FIVE))),
            seatCount = 2
        )
        val update = com.mutsho.localuno.viewmodel.buildStateUpdate(
            e.getState(), e.getState().players[1], System.currentTimeMillis()
        )
        assertNotNull(update)
        val round = MessageSerializer.deserialize(MessageSerializer.serialize(update!!))
        assertNotNull("a genuine StateUpdate must survive the guard", round as? NetworkMessage.StateUpdate)
    }

    @Test fun `absurd numbers do not crash the parser`() {
        // Whether the engine likes them is a separate question - it validates every move anyway.
        // The requirement here is only that parsing one cannot take the process down.
        val payloads = listOf(
            """{"playerId":"p1","card":{"id":-999999,"color":"RED","type":"THREE"}}""",
            """{"playerId":"p1","card":{"id":9223372036854775807,"color":"RED","type":"THREE"}}""",
            """{"playerId":"p1","card":{"id":1e309,"color":"RED","type":"THREE"}}"""
        )
        payloads.forEach { parse("PLAY_CARD", it) }   // must not throw
    }

    @Test fun `deeply nested json does not crash the parser`() {
        val deep = "[".repeat(2_000) + "]".repeat(2_000)
        parse("PLAY_CARD", """{"playerId":"p1","card":$deep}""")
    }

    // ── Older builds must still be understood ───────────────────────────────
    //
    // The guard above rejects messages whose REQUIRED fields are missing. It must not reject ones
    // that merely omit OPTIONAL fields - several exist precisely so a peer on an older build stays
    // compatible, and each carries a comment in NetworkMessage saying so. Rejecting those would
    // turn a hardening fix into a silent break of every cross-version table, which is a worse bug
    // than the one being fixed and would not show up in a same-version test anywhere.

    @Test fun `a state update from a build that predates the newer fields still parses`() {
        // Omits currentPlayerId, opponentHasCalledUno, opponentConnected - all defaulted.
        val old = """{"currentPlayerIndex":0,"direction":"CLOCKWISE",
            "topCard":{"id":1,"color":"RED","type":"THREE"},"currentColor":"RED",
            "playerHand":[],"opponentCardCounts":{},"pendingDrawCount":0,"phase":"PLAYING"}"""
            .replace(Regex("\\s+"), "")
        val m = parse("STATE_UPDATE", old) as? NetworkMessage.StateUpdate
        assertNotNull("an older host must still be understood", m)
        // And the defaults have to be real values, or the receiver NPEs on them later anyway.
        assertNotNull(m!!.opponentHasCalledUno)
        assertNotNull(m.opponentConnected)
        assertNotNull(m.currentPlayerId)
    }

    @Test fun `a lobby update with a bare settings object still parses`() {
        // Every GameSettings field is defaulted, so an older or terser peer can legitimately send
        // very little here.
        val m = parse(
            "LOBBY_UPDATE",
            """{"players":[{"id":"p1","name":"Ada","avatarColor":0,"isHost":true,"isConnected":true}],"settings":{}}"""
        ) as? NetworkMessage.LobbyUpdate
        assertNotNull(m)
        assertNotNull("settings must be usable, not half-null", m!!.settings.gameMode)
        assertNotNull(m.settings.rules)
        assertNotNull(m.settings.turnTimeoutAction)
    }

    @Test fun `a removal notice with no reason still parses`() {
        // reason is defaulted; a host on an older build simply will not send it.
        val m = parse("REMOVED_FROM_TABLE", "{}") as? NetworkMessage.RemovedFromTable
        assertNotNull(m)
        assertTrue("a defaulted reason must still be showable", m!!.reason.isNotBlank())
    }

    @Test fun `a host-ended notice with no reason still parses`() {
        val m = parse("HOST_ENDED_GAME", "{}") as? NetworkMessage.HostEndedGame
        assertNotNull(m)
        assertTrue(m!!.reason.isNotBlank())
    }

    // ── Random mutation ─────────────────────────────────────────────────────

    @Test fun `randomly corrupted real messages never throw`() {
        // Takes messages the app genuinely sends and damages them a byte at a time. Seeded, so a
        // failure is reproducible.
        val rng = Random(20260821)
        val originals = listOf(
            MessageSerializer.serialize(NetworkMessage.Ping()),
            MessageSerializer.serialize(NetworkMessage.JoinRequest("Ada", "p1", "4821", 3)),
            MessageSerializer.serialize(NetworkMessage.CallUno("p1")),
            MessageSerializer.serialize(NetworkMessage.RemovedFromTable()),
            MessageSerializer.serialize(NetworkMessage.EmojiReaction("p1", "🎯"))
        )
        repeat(3_000) {
            val src = originals.random(rng)
            val chars = src.toCharArray()
            repeat(rng.nextInt(1, 4)) {
                chars[rng.nextInt(chars.size)] = rng.nextInt(32, 127).toChar()
            }
            // The only contract: no exception escapes. Null is a perfectly good answer.
            MessageSerializer.deserialize(String(chars))
        }
        assertTrue(true)
    }

    @Test fun `truncated messages never throw`() {
        val full = MessageSerializer.serialize(
            NetworkMessage.JoinRequest("Ada", "p1", "4821", 3)
        )
        for (cut in full.indices) MessageSerializer.deserialize(full.substring(0, cut))
    }
}

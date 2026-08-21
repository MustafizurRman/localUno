package com.mutsho.localuno

import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.Direction
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.HandTransfer
import com.mutsho.localuno.model.NetworkMessage
import com.mutsho.localuno.model.PlayerInfo
import com.mutsho.localuno.network.MessageSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire protocol. Every message that crosses between two phones goes through here.
 *
 * The hazard this exists for: a message type is declared in NetworkMessage.kt and then has to be
 * registered AGAIN, by hand, in MessageSerializer's `when`. Miss the second step and nothing warns
 * you - the sealed class still compiles, the sender still serialises it happily, and the receiver
 * silently returns null and drops it. The failure surfaces as a feature that "just doesn't work on
 * the other phone", with no error on either device.
 *
 * So the coverage check is reflective rather than a list someone has to remember to extend: it
 * enumerates the sealed subclasses and fails on any that this file does not exercise.
 */
class MessageSerializerTest {

    private val sample = Card(7, CardColor.RED, CardType.SEVEN)

    /** One instance of every NetworkMessage subclass, with non-default values in every field. */
    private val everyMessage: List<NetworkMessage> = listOf(
        NetworkMessage.JoinRequest("Ada", "p1", pin = "4821", avatarColor = 5, sequenceNumber = 11),
        NetworkMessage.JoinResponse(accepted = false, reason = "Table full", assignedId = "p2", sequenceNumber = 12),
        NetworkMessage.LobbyUpdate(
            players = listOf(
                PlayerInfo("p1", "Ada", 3, isHost = true, isConnected = true, isBot = false),
                PlayerInfo("p2", "Robo", 6, isHost = false, isConnected = false, isBot = true)
            ),
            settings = GameSettings(
                gameMode = GameMode.NO_MERCY,
                maxPlayers = 8,
                lobbyName = "Ada's table",
                pin = "4821",
                rules = GameRules(stacking = true, lastCardMustBeNumber = true, zeroRotates = true, sevenSwaps = true),
                turnTimeoutSeconds = 30,
                mercyThreshold = 22
            ),
            sequenceNumber = 13
        ),
        NetworkMessage.GameStart(sequenceNumber = 14),
        NetworkMessage.StateUpdate(
            currentPlayerIndex = 2,
            direction = Direction.COUNTER_CLOCKWISE,
            topCard = sample,
            currentColor = CardColor.GREEN,
            playerHand = listOf(sample, Card(8, CardColor.WILD, CardType.WILD_DRAW_TEN)),
            opponentCardCounts = mapOf("p2" to 4, "p3" to 9),
            pendingDrawCount = 10,
            lastDrawCardValue = 4,
            phase = GamePhase.CHOOSING_SWAP,
            drawPileCount = 42,
            isFlipDarkSide = true,
            myHasCalledUno = true,
            myIsConnected = false,
            opponentHasCalledUno = mapOf("p2" to true),
            opponentConnected = mapOf("p2" to false),
            turnStartedAtMillis = 1_700_000_000_000L,
            turnElapsedMillis = 4321L,
            currentPlayerId = "p3",
            winnerId = "p1",
            disconnectedPlayerId = "p2",
            disconnectCountdown = 17,
            pendingSwapPlayerId = "p3",
            handTransfer = HandTransfer(id = 99L, isRotation = true, order = listOf("p1", "p2", "p3")),
            roundStartedAtMillis = 1_699_999_000_000L,
            sequenceNumber = 15
        ),
        NetworkMessage.PlayCard("p1", sample, chosenColor = CardColor.BLUE, sequenceNumber = 16),
        NetworkMessage.DrawCard("p1", sequenceNumber = 17),
        NetworkMessage.CallUno("p1", sequenceNumber = 18),
        NetworkMessage.UnoNotCalled("p1", "p2", sequenceNumber = 19),
        NetworkMessage.PlayerDisconnected("p2", "Robo", sequenceNumber = 20),
        NetworkMessage.PlayerReconnected("p2", sequenceNumber = 21),
        NetworkMessage.GameOver("p1", "Ada", mapOf("p1" to 0, "p2" to 57), durationSeconds = 428, sequenceNumber = 22),
        NetworkMessage.EmojiReaction("p1", "🔥", sequenceNumber = 23),
        NetworkMessage.ResyncRequest("p1", lastSequenceNumber = 14, sequenceNumber = 24),
        NetworkMessage.PlayerLeft("p2", sequenceNumber = 25),
        NetworkMessage.ReturnToLobby(sequenceNumber = 26),
        NetworkMessage.TurnTimedOut("Robo", sequenceNumber = 27),
        NetworkMessage.PlayRejected("Too slow", sequenceNumber = 28),
        NetworkMessage.HostEndedGame("The host ended the game", sequenceNumber = 29),
        NetworkMessage.ChooseSwapTarget("p3", sequenceNumber = 30),
        NetworkMessage.ChoosingColor("p2", choosing = true, sequenceNumber = 32),
        NetworkMessage.Ping(sequenceNumber = 31)
    )

    @Test
    fun `every message type survives the wire intact`() {
        for (original in everyMessage) {
            val wire = MessageSerializer.serialize(original)
            val back = MessageSerializer.deserialize(wire)
            assertNotNull(
                "${original::class.simpleName} came back null - is its type registered in " +
                    "MessageSerializer.deserialize?",
                back
            )
            // Data classes, so equality compares every field: this catches a value quietly lost in
            // transit as well as a type that fails to parse at all.
            assertEquals("${original::class.simpleName} changed crossing the wire", original, back)
        }
    }

    @Test
    fun `no message type is missing from this test`() {
        val declared = NetworkMessage::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        val covered = everyMessage.map { it::class.simpleName }.toSet()
        val untested = declared - covered
        assertTrue(
            "NetworkMessage subclasses with no round-trip coverage: $untested. Add one to " +
                "everyMessage - and check it is registered in MessageSerializer too.",
            untested.isEmpty()
        )
    }

    @Test
    fun `every message type is registered in the serializer`() {
        // The actual dual-registration trap: declared in the sealed class, forgotten in the `when`.
        for (message in everyMessage) {
            val back = MessageSerializer.deserialize(MessageSerializer.serialize(message))
            assertNotNull("${message.type} is not handled by MessageSerializer.deserialize", back)
        }
    }

    @Test
    fun `each message is one newline-terminated line`() {
        // GameClient/GameServer frame the stream by newline. A payload containing a raw newline
        // would split one message into two unparseable halves.
        for (message in everyMessage) {
            val wire = MessageSerializer.serialize(message)
            assertTrue("${message.type} is not newline terminated", wire.endsWith("\n"))
            assertEquals(
                "${message.type} contains an embedded newline and would be split in transit",
                1, wire.count { it == '\n' }
            )
        }
    }

    @Test
    fun `a lobby name with newlines and quotes cannot break framing`() {
        // Lobby names and player names are free text typed by a person.
        for (nasty in listOf("a\nb", "quote\"quote", "back\\slash", "tab\there", "{\"type\":\"PING\"}")) {
            val message = NetworkMessage.JoinRequest(nasty, "p1", pin = nasty)
            val wire = MessageSerializer.serialize(message)
            assertEquals("framing broken by <$nasty>", 1, wire.count { it == '\n' })
            assertEquals(message, MessageSerializer.deserialize(wire))
        }
    }

    @Test
    fun `garbage on the wire is rejected rather than thrown`() {
        // A half-written line, a foreign protocol, a truncated payload - none may take the reader
        // loop down, because that loop is the connection.
        for (junk in listOf(
            "", "   ", "not json at all", "{}", "[]", "null",
            """{"type":"NOPE","payload":"{}"}""",
            """{"type":"PLAY_CARD"}""",
            """{"type":"PLAY_CARD","payload":"not json"}""",
            """{"type":"STATE_UPDATE","payload":"{\"currentPlayerIndex\":\"NaN\"}"}""",
            "{\"type\":\"PING\",\"payload\":"
        )) {
            // Must not throw; null is the contract for anything unusable.
            val result = MessageSerializer.deserialize(junk)
            if (junk.contains("STATE_UPDATE") || junk.contains("\"PLAY_CARD\"")) {
                // Partial payloads may parse into a defaulted object; what matters is no crash.
                continue
            }
            assertNull("garbage <$junk> should not deserialise", result)
        }
    }
}

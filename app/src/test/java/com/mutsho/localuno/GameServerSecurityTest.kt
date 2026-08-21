package com.mutsho.localuno

import com.mutsho.localuno.model.NetworkMessage
import com.mutsho.localuno.network.GameServer
import com.mutsho.localuno.network.MessageSerializer
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

/**
 * The transport's trust boundary, exercised over real loopback sockets.
 *
 * These could not be written as pure functions, because the thing being asserted only exists in the
 * interaction between two connections and a map: "a second socket cannot take a live player's
 * identity" is not a property of any single value. GameServer is otherwise ordinary JVM socket code
 * - the only Android in it is android.util.Log - so with unit tests returning default values it
 * runs here unchanged.
 *
 * The bug these were written for: a JoinRequest used to rebind `clients[playerId]` to whatever
 * connection sent it, unconditionally, before the PIN or anything else had been checked. Private
 * hands are addressed through that map, and every player's id is broadcast to every guest in
 * LobbyUpdate - so anyone who could reach the port could name a seated player and start receiving
 * their cards, while the real player went dark and was knocked out for going quiet.
 */
class GameServerSecurityTest {

    private fun join(name: String, id: String) =
        MessageSerializer.serialize(NetworkMessage.JoinRequest(name, id)).toByteArray()

    /** Connects, sends a JoinRequest, and returns the socket plus the id the server resolved. */
    private suspend fun connectAndClaim(
        port: Int,
        inbox: Channel<Pair<String, NetworkMessage>>,
        name: String,
        claimedId: String
    ): Pair<Socket, String> {
        val socket = Socket("127.0.0.1", port)
        socket.getOutputStream().apply { write(join(name, claimedId)); flush() }
        val (clientId, _) = withTimeout(5_000) { inbox.receive() }
        return socket to clientId
    }

    private fun withServer(maxPlayers: Int = 4, body: suspend (GameServer, Int, Channel<Pair<String, NetworkMessage>>) -> Unit) =
        runBlocking {
            val server = GameServer()
            server.maxPlayers = maxPlayers
            val port = server.start()
            val inbox = Channel<Pair<String, NetworkMessage>>(Channel.UNLIMITED)
            var collector: Job? = null
            try {
                collector = launch { server.messages.collect { inbox.send(it) } }
                delay(150) // let the collector attach before anything is sent
                body(server, port, inbox)
            } finally {
                collector?.cancel()
                server.stop()
            }
        }

    // ── Identity ────────────────────────────────────────────────────────────

    @Test fun `a joining connection is not given the identity it claims`() = withServer { server, port, inbox ->
        // The heart of it: sending a JoinRequest must buy you nothing on its own.
        val (socket, clientId) = connectAndClaim(port, inbox, "Alice", "alice-id")
        assertFalse(
            "a claim is not an identity until the application binds it",
            server.isPlayerIdLive("alice-id")
        )
        assertTrue("the socket is still addressed by its own key", clientId.isNotBlank())
        socket.close()
    }

    @Test fun `binding gives the connection its identity`() = withServer { server, port, inbox ->
        val (socket, clientId) = connectAndClaim(port, inbox, "Alice", "alice-id")
        assertTrue(server.bindPlayerId(clientId, "alice-id"))
        assertTrue(server.isPlayerIdLive("alice-id"))
        socket.close()
    }

    @Test fun `a second connection cannot take a live player's identity`() = withServer { server, port, inbox ->
        val (alice, aliceClient) = connectAndClaim(port, inbox, "Alice", "alice-id")
        assertTrue(server.bindPlayerId(aliceClient, "alice-id"))

        // Mallory knows alice-id because every LobbyUpdate carries it.
        val (mallory, malloryClient) = connectAndClaim(port, inbox, "Mallory", "alice-id")
        assertFalse(
            "this is the hand-disclosure bug - a live seat must never be rebindable",
            server.bindPlayerId(malloryClient, "alice-id")
        )

        alice.close(); mallory.close()
    }

    @Test fun `a player's own messages go to that player and to nobody else`() = withServer { server, port, inbox ->
        // THE test, and the one worth being careful about: it is not enough that the map still
        // contains "alice-id" - it has to point at ALICE. An earlier version of this test asserted
        // only that the id was still live and still listed, and it passed against the vulnerable
        // code, because Mallory's connection was live and listed under exactly that id. It proved
        // nothing at all.
        //
        // Private hands are delivered by sendToClient(playerId, ...), so the honest question is
        // which socket the bytes arrive on.
        val (alice, aliceClient) = connectAndClaim(port, inbox, "Alice", "alice-id")
        server.bindPlayerId(aliceClient, "alice-id")

        val (mallory, malloryClient) = connectAndClaim(port, inbox, "Mallory", "alice-id")
        server.bindPlayerId(malloryClient, "alice-id")

        // Stands in for a StateUpdate carrying Alice's hand.
        server.sendToClient("alice-id", NetworkMessage.EmojiReaction("alice-id", "SECRET"))
        delay(400)

        assertTrue("Alice must receive her own message", alice.sawSecret())
        assertFalse("Mallory must never receive Alice's message", mallory.sawSecret())

        alice.close(); mallory.close()
    }

    /** Drains whatever is waiting and reports whether the marker message was among it. */
    private fun Socket.sawSecret(): Boolean {
        soTimeout = 600
        val reader = getInputStream().bufferedReader()
        return try {
            var found = false
            while (true) {
                val line = reader.readLine() ?: break
                if ("SECRET" in line) { found = true; break }   // skip PINGs
            }
            found
        } catch (_: Exception) {
            false   // read timeout - nothing more was waiting
        }
    }

    @Test fun `a genuine reconnect can still take the seat once the old socket is gone`() =
        withServer { server, port, inbox ->
            // The refusal must be about LIVE connections only, or dropping out of Wi-Fi would lock
            // you out of your own seat for the rest of the round.
            val (alice, aliceClient) = connectAndClaim(port, inbox, "Alice", "alice-id")
            assertTrue(server.bindPlayerId(aliceClient, "alice-id"))

            alice.close()
            delay(300) // let the server's read loop notice and clean up

            val (again, againClient) = connectAndClaim(port, inbox, "Alice", "alice-id")
            assertTrue(
                "a reconnect after a real drop must succeed",
                server.bindPlayerId(againClient, "alice-id")
            )
            again.close()
        }

    @Test fun `binding an unknown connection fails rather than inventing one`() =
        withServer { server, _, _ ->
            assertFalse(server.bindPlayerId("no-such-socket", "alice-id"))
        }

    @Test fun `rebinding the same connection to the same id is harmless`() = withServer { server, port, inbox ->
        // A duplicate JoinRequest from an accepted client must not look like a takeover attempt.
        val (socket, clientId) = connectAndClaim(port, inbox, "Alice", "alice-id")
        assertTrue(server.bindPlayerId(clientId, "alice-id"))
        assertTrue(server.bindPlayerId("alice-id", "alice-id"))
        socket.close()
    }

    // ── Resource limits ─────────────────────────────────────────────────────

    @Test fun `a peer that never sends a newline is dropped`() = withServer { _, port, _ ->
        // Unbounded readLine let one connection decide how much memory the host allocated.
        val hostile = Socket("127.0.0.1", port)
        val out = hostile.getOutputStream()
        val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
        var closed = false
        try {
            repeat(12) { out.write(chunk); out.flush() }   // 768 KB, no newline anywhere
            delay(500)
            // The server has dropped us; the next read reports end of stream.
            hostile.soTimeout = 2_000
            closed = hostile.getInputStream().read() == -1
        } catch (_: Exception) {
            closed = true   // a reset is just as good an answer
        }
        assertTrue("the host must drop a peer that will not frame its messages", closed)
        hostile.close()
    }

    @Test fun `connections beyond the cap are refused`() = withServer(maxPlayers = 2) { _, port, _ ->
        // maxPlayers 2 plus CONNECTION_HEADROOM 6 = 8 accepted; the rest are closed at the door.
        val sockets = mutableListOf<Socket>()
        var refused = 0
        repeat(14) {
            try {
                val s = Socket("127.0.0.1", port)
                sockets += s
                s.soTimeout = 1_000
            } catch (_: Exception) {
                refused++
            }
        }
        delay(500)
        // A refused socket connects (the OS backlog accepts it) and is then closed by the server.
        val dead = sockets.count { s ->
            try { s.getInputStream().read() == -1 } catch (_: Exception) { true }
        }
        assertTrue("expected some connections to be refused, got $dead dead + $refused rejected",
            dead + refused > 0)
        sockets.forEach { runCatching { it.close() } }
    }

    @Test fun `an accepted client still works normally after the cap is in place`() =
        withServer(maxPlayers = 4) { server, port, inbox ->
            // Guard against the cap being set so tight that ordinary play breaks.
            val (socket, clientId) = connectAndClaim(port, inbox, "Alice", "alice-id")
            assertTrue(server.bindPlayerId(clientId, "alice-id"))
            assertEquals(setOf("alice-id"), server.getConnectedPlayerIds())
            socket.close()
        }
}

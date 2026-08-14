package com.mutsho.localuno.network

/**
 * Shared timing for the connection-liveness mechanism GameClient and GameServer both implement.
 *
 * Neither end ever called `Socket.setSoTimeout()`, so a blocking `readLine()` would wait
 * indefinitely for data that will never arrive if a peer's connection dies without a clean TCP
 * close - which is the ORDINARY way a phone disconnects (walking out of Wi-Fi range, the OS killing
 * a backgrounded process, airplane mode), not the exception. The 30-second disconnect-pause and
 * knockout feature in GameEngine only ever starts once `GameServer.ConnectionEvent.Disconnected`
 * fires, and without a socket timeout that could take many minutes or simply never happen within a
 * round's lifetime - the feature built specifically to handle a lost player never actually saw one.
 *
 * A read timeout alone would false-positive on a perfectly healthy connection that just hasn't had
 * anything to send in a while (a slow human's turn, someone idling in the lobby), so both ends also
 * send a content-free [com.mutsho.localuno.model.NetworkMessage.Ping] on [PING_INTERVAL_MS] - that
 * gives the read timeout something to see even during silence, and [READ_TIMEOUT_MS] tolerates one
 * lost or delayed ping (a touch over 3x the interval) before treating the peer as gone.
 */
object Heartbeat {
    const val PING_INTERVAL_MS = 4_000L
    const val READ_TIMEOUT_MS = 13_000

    // A dropped client's automatic reconnect attempts (see LobbyViewModel.attemptReconnect and
    // GameViewModel's retry loop). Bounded well under GameEngine.DISCONNECT_TIMEOUT_SECONDS (30s):
    // both ends detect the SAME drop independently and roughly simultaneously via READ_TIMEOUT_MS
    // above, so the client's retry budget and the host's 30s grace window both start around the
    // same real moment - these numbers leave a comfortable margin (4 x 5s = 20s) before the host's
    // deadline, rather than racing it.
    const val RECONNECT_CONNECT_TIMEOUT_MS = 2_500
    const val RECONNECT_MAX_ATTEMPTS = 4
    const val RECONNECT_RETRY_DELAY_MS = 2_500L
}

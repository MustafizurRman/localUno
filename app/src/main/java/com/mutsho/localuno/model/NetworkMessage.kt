package com.mutsho.localuno.model

sealed class NetworkMessage {
    abstract val type: String
    abstract val sequenceNumber: Long

    // Lobby messages
    data class JoinRequest(
        val playerName: String,
        val playerId: String,
        val pin: String? = null,
        // The colour this player actually picked on the main menu. Without it the host had no
        // choice but to invent one from the player's UUID hash, so the avatar picker silently
        // did nothing for anyone except the host.
        val avatarColor: Int = 0,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "JOIN_REQUEST"
    }

    data class JoinResponse(
        val accepted: Boolean,
        val reason: String? = null,
        val assignedId: String? = null,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "JOIN_RESPONSE"
    }

    data class LobbyUpdate(
        val players: List<PlayerInfo>,
        val settings: GameSettings,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "LOBBY_UPDATE"
    }

    data class GameStart(
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "GAME_START"
    }

    // Game messages
    data class StateUpdate(
        val currentPlayerIndex: Int,
        val direction: Direction,
        val topCard: Card,
        val currentColor: CardColor,
        val playerHand: List<Card>,
        val opponentCardCounts: Map<String, Int>,
        val pendingDrawCount: Int,
        val lastDrawCardValue: Int = 0,
        val phase: GamePhase,
        val drawPileCount: Int = 0,
        val isFlipDarkSide: Boolean = false,
        val myHasCalledUno: Boolean = false,
        // The recipient's OWN connected state. Only opponents' was ever sent, so a knocked-out
        // player's device still believed it was in the game - harmless until isConnected became
        // load-bearing for the end-of-round ranking, at which point you'd rank yourself among the
        // survivors while everyone else correctly listed you as out.
        val myIsConnected: Boolean = true,
        val opponentHasCalledUno: Map<String, Boolean> = emptyMap(),
        val opponentConnected: Map<String, Boolean> = emptyMap(),
        val turnStartedAtMillis: Long = 0L,
        // How long the current turn has already been running, per the HOST's clock. Receivers
        // rebase this onto their own clock instead of interpreting turnStartedAtMillis directly -
        // that's an absolute host timestamp, and two devices' System.currentTimeMillis() can
        // differ by seconds, which would desync (or completely freeze) every client's countdown.
        val turnElapsedMillis: Long = 0L,
        // Whose turn it is, by identity rather than by index. currentPlayerIndex only means
        // anything if the client's player list is in the exact same order as the host's; resolving
        // by id keeps the turn indicator correct even if the two rosters ever diverge.
        val currentPlayerId: String = "",
        // Host-authoritative fields the client can't derive on its own. Without these a client's
        // GameState keeps the defaults forever: no winner (so the End screen can't rank or
        // highlight, and a win looks like a Mercy knockout), and a disconnect pause showing
        // "A player" frozen at 30s instead of the real name and a live countdown.
        val winnerId: String? = null,
        val disconnectedPlayerId: String? = null,
        val disconnectCountdown: Int = 30,
        // Whose 7 is waiting on a swap target - clients need this to know whether to show the
        // picker to THIS player or just the "waiting on them" state.
        val pendingSwapPlayerId: String? = null,
        // Who just handed their hand to whom (a 7's swap or a 0's rotation). Without this a client
        // sees hand counts change but cannot tell who traded with whom, so it could only ever play
        // a generic flourish rather than the actual transfer. See HandTransfer.
        val handTransfer: HandTransfer? = null,
        /**
         * Which ROUND this update belongs to - GameState.roundStartTime, stamped once when the
         * round is dealt. Never interpreted as a time by the receiver; only compared for equality.
         *
         * Exists so a client can tell a stale update apart from a new round. Sequence numbers
         * restart from 1 every round, because a rematch builds a fresh GameEngine, so "this number
         * is lower than the last one I saw" means EITHER an out-of-order message OR the next round
         * beginning - and those want opposite handling. Without the epoch, a receiver that dropped
         * lower sequence numbers would drop every single update of a rematch and sit frozen on the
         * finished board forever.
         *
         * Defaulted to 0, which a host too old to send it will leave alone; the receiver then skips
         * the staleness check entirely. Failing open costs a guard, failing shut would cost the game.
         */
        val roundStartedAtMillis: Long = 0L,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "STATE_UPDATE"
    }

    data class PlayCard(
        val playerId: String,
        val card: Card,
        val chosenColor: CardColor? = null,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "PLAY_CARD"
    }

    data class DrawCard(
        val playerId: String,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "DRAW_CARD"
    }

    data class CallUno(
        val playerId: String,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "CALL_UNO"
    }

    data class UnoNotCalled(
        val reporterId: String,
        val targetPlayerId: String,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "UNO_NOT_CALLED"
    }

    data class PlayerDisconnected(
        val playerId: String,
        val playerName: String,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "PLAYER_DISCONNECTED"
    }

    data class PlayerReconnected(
        val playerId: String,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "PLAYER_RECONNECTED"
    }

    data class GameOver(
        val winnerId: String,
        val winnerName: String,
        val scores: Map<String, Int>,
        val durationSeconds: Long,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "GAME_OVER"
    }

    data class EmojiReaction(
        val playerId: String,
        val emoji: String,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "EMOJI_REACTION"
    }

    data class ResyncRequest(
        val playerId: String,
        val lastSequenceNumber: Long,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "RESYNC_REQUEST"
    }

    data class PlayerLeft(
        val playerId: String,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "PLAYER_LEFT"
    }

    /** Host -> All: the match ended and the host is taking everyone back to the lobby for a rematch. */
    data class ReturnToLobby(
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "RETURN_TO_LOBBY"
    }

    /** Host -> All: the turn timer ran out and the host auto-drew for this player. */
    data class TurnTimedOut(
        val playerName: String,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "TURN_TIMED_OUT"
    }

    /** Host -> one client: their PlayCard arrived too late (turn already moved on) and was ignored. */
    data class PlayRejected(
        val reason: String,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "PLAY_REJECTED"
    }

    /** Client -> Host: the seat this player picked to trade hands with, completing their 7's swap. */
    data class ChooseSwapTarget(
        val targetPlayerId: String,
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "CHOOSE_SWAP_TARGET"
    }

    /** Host -> All: the host left an active match, ending it for everyone (no one else can take
     *  over the authoritative engine). */
    data class HostEndedGame(
        val reason: String = "The host ended the game",
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "HOST_ENDED_GAME"
    }

    /**
     * Either direction, on a fixed interval: carries no data, exists purely so each side's read
     * timeout (see GameClient/GameServer's Heartbeat constants) has something to see during a
     * stretch with no real traffic - a slow human's turn, an idle lobby. Without it, the socket
     * timeout that's supposed to catch a genuinely dead connection would also fire on a perfectly
     * healthy one that just hasn't needed to send anything in a while.
     */
    data class Ping(
        override val sequenceNumber: Long = 0
    ) : NetworkMessage() {
        override val type = "PING"
    }
}

data class PlayerInfo(
    val id: String,
    val name: String,
    val avatarColor: Int,
    val isHost: Boolean,
    val isConnected: Boolean,
    // Defaulted, so a LobbyUpdate from a build that predates bots still deserializes.
    // Clients need this only to label the seat - the host runs every bot itself.
    val isBot: Boolean = false
)

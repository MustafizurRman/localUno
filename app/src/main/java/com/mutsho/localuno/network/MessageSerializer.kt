package com.mutsho.localuno.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.NetworkMessage
import com.mutsho.localuno.model.WireLimits

object MessageSerializer {
    private val gson: Gson = GsonBuilder().create()

    fun serialize(message: NetworkMessage): String {
        val wrapper = MessageWrapper(
            type = message.type,
            payload = gson.toJson(message)
        )
        return gson.toJson(wrapper) + "\n"
    }

    /**
     * Parses one line from the wire, or returns null if it cannot be trusted.
     *
     * Null now means two things, and the second one is new: the JSON did not parse, OR it parsed
     * into an object that is not safe to use. See [isWellFormed] - Gson does not run Kotlin
     * constructors, so "it parsed" says nothing at all about whether the declared non-null fields
     * actually arrived.
     */
    fun deserialize(json: String): NetworkMessage? {
        return try {
            val wrapper = gson.fromJson(json.trim(), MessageWrapper::class.java)
            val message = parse(wrapper)?.repaired() ?: return null
            if (message.isWellFormed()) message else null
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(wrapper: MessageWrapper): NetworkMessage? {
        return try {
            when (wrapper.type) {
                "JOIN_REQUEST" -> gson.fromJson(wrapper.payload, NetworkMessage.JoinRequest::class.java)
                "JOIN_RESPONSE" -> gson.fromJson(wrapper.payload, NetworkMessage.JoinResponse::class.java)
                "LOBBY_UPDATE" -> gson.fromJson(wrapper.payload, NetworkMessage.LobbyUpdate::class.java)
                "GAME_START" -> gson.fromJson(wrapper.payload, NetworkMessage.GameStart::class.java)
                "STATE_UPDATE" -> gson.fromJson(wrapper.payload, NetworkMessage.StateUpdate::class.java)
                "PLAY_CARD" -> gson.fromJson(wrapper.payload, NetworkMessage.PlayCard::class.java)
                "DRAW_CARD" -> gson.fromJson(wrapper.payload, NetworkMessage.DrawCard::class.java)
                "CALL_UNO" -> gson.fromJson(wrapper.payload, NetworkMessage.CallUno::class.java)
                "UNO_NOT_CALLED" -> gson.fromJson(wrapper.payload, NetworkMessage.UnoNotCalled::class.java)
                "PLAYER_DISCONNECTED" -> gson.fromJson(wrapper.payload, NetworkMessage.PlayerDisconnected::class.java)
                "PLAYER_RECONNECTED" -> gson.fromJson(wrapper.payload, NetworkMessage.PlayerReconnected::class.java)
                "GAME_OVER" -> gson.fromJson(wrapper.payload, NetworkMessage.GameOver::class.java)
                "EMOJI_REACTION" -> gson.fromJson(wrapper.payload, NetworkMessage.EmojiReaction::class.java)
                "RESYNC_REQUEST" -> gson.fromJson(wrapper.payload, NetworkMessage.ResyncRequest::class.java)
                "PLAYER_LEFT" -> gson.fromJson(wrapper.payload, NetworkMessage.PlayerLeft::class.java)
                "RETURN_TO_LOBBY" -> gson.fromJson(wrapper.payload, NetworkMessage.ReturnToLobby::class.java)
                "TURN_TIMED_OUT" -> gson.fromJson(wrapper.payload, NetworkMessage.TurnTimedOut::class.java)
                "PLAY_REJECTED" -> gson.fromJson(wrapper.payload, NetworkMessage.PlayRejected::class.java)
                "HOST_ENDED_GAME" -> gson.fromJson(wrapper.payload, NetworkMessage.HostEndedGame::class.java)
                "REMOVED_FROM_TABLE" -> gson.fromJson(wrapper.payload, NetworkMessage.RemovedFromTable::class.java)
                "CHOOSE_SWAP_TARGET" -> gson.fromJson(wrapper.payload, NetworkMessage.ChooseSwapTarget::class.java)
                "CHOOSING_COLOR" -> gson.fromJson(wrapper.payload, NetworkMessage.ChoosingColor::class.java)
                "PING" -> gson.fromJson(wrapper.payload, NetworkMessage.Ping::class.java)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Restores Kotlin defaults that Gson did not apply, so an OPTIONAL missing field is repaired
     * rather than rejected.
     *
     * Whether defaults survive depends on something easy to miss: Kotlin synthesises a no-argument
     * constructor only when EVERY parameter has a default, and Gson uses that constructor when it
     * exists. So GameSettings and RemovedFromTable come back fully defaulted from a payload of
     * `{}`, while StateUpdate - which mixes required and defaulted parameters, and therefore gets
     * no such constructor - is built through Unsafe with nulls in the defaulted slots.
     *
     * That distinction is the whole reason this function exists. Several StateUpdate fields are
     * defaulted precisely so a peer on an older build stays compatible, each with a comment in
     * NetworkMessage saying so, and rejecting a message for omitting one would turn a hardening fix
     * into a silent break of every cross-version table - a worse bug than the one being fixed, and
     * invisible to any same-version test.
     *
     * USELESS_ELVIS is suppressed for the same reason SENSELESS_COMPARISON is below: the compiler
     * is right about the declared types and wrong about what Gson produces.
     */
    @Suppress("USELESS_ELVIS")
    private fun NetworkMessage.repaired(): NetworkMessage = when (this) {
        is NetworkMessage.StateUpdate -> copy(
            currentPlayerId = currentPlayerId ?: "",
            opponentHasCalledUno = opponentHasCalledUno ?: emptyMap(),
            opponentConnected = opponentConnected ?: emptyMap()
        )
        else -> this
    }

    /**
     * Whether a parsed message's required fields actually arrived.
     *
     * Gson does not call Kotlin constructors - it allocates the object and writes fields directly -
     * so a declared non-null `String` or enum is null whenever the JSON omitted it, and Kotlin's
     * default values are never applied either. A payload of `{}` for a StateUpdate therefore
     * produces an object with a null top card, a null colour, a null hand and a null phase, and the
     * receiver rebuilds its whole board from it.
     *
     * Nothing fails at the parse site. It fails later, somewhere else entirely, with nothing on the
     * trace to say a peer caused it - and every message here is one any device on the network can
     * send with no PIN and no successful join, because messages reach the ViewModels before any of
     * that is checked. A single malformed PLAY_CARD was enough to take the host down.
     *
     * An unrecognised enum name behaves the same way: Gson maps it to null rather than throwing, so
     * `"color":"MAUVE"` is a null CardColor in a field that cannot be one.
     *
     * SENSELESS_COMPARISON is suppressed deliberately and is the entire point: the compiler is
     * right that these cannot be null according to their types, and wrong about what Gson does at
     * runtime. Removing the "redundant" checks is exactly the tidy-up that would reopen this.
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun NetworkMessage.isWellFormed(): Boolean {
        fun Card?.ok() = this != null && color != null && type != null
        return when (this) {
            is NetworkMessage.JoinRequest -> playerName != null && playerId != null
            is NetworkMessage.JoinResponse -> true      // every field is optional by design
            is NetworkMessage.LobbyUpdate ->
                players != null && settings != null &&
                    settings.gameMode != null && settings.rules != null &&
                    settings.turnTimeoutAction != null &&
                    players.all { it != null && it.id != null && it.name != null }
            is NetworkMessage.GameStart -> true
            is NetworkMessage.StateUpdate ->
                direction != null && topCard.ok() && currentColor != null &&
                    playerHand != null && playerHand.all { it.ok() } &&
                    opponentCardCounts != null && phase != null &&
                    currentPlayerId != null && opponentHasCalledUno != null &&
                    opponentConnected != null &&
                    // Counts are ALLOCATION SIZES on the receiving side, so an absurd one is not a
                    // strange value - it is an instruction to allocate that much. A string costs
                    // what it weighs; a count costs whatever it says, which is why the line cap is
                    // no defence: `{"p1":2000000000}` is twenty bytes. See WireLimits.
                    playerHand.size <= WireLimits.MAX_HAND_CARDS &&
                    opponentCardCounts.size <= WireLimits.MAX_PLAYERS &&
                    opponentCardCounts.values.all {
                        WireLimits.isPlausibleCount(it, WireLimits.MAX_HAND_CARDS)
                    } &&
                    WireLimits.isPlausibleCount(pendingDrawCount, WireLimits.MAX_PENDING_DRAW) &&
                    WireLimits.isPlausibleCount(drawPileCount, WireLimits.MAX_DECK_CARDS)
            is NetworkMessage.PlayCard -> playerId != null && card.ok()
            is NetworkMessage.DrawCard -> playerId != null
            is NetworkMessage.CallUno -> playerId != null
            is NetworkMessage.UnoNotCalled -> reporterId != null && targetPlayerId != null
            is NetworkMessage.PlayerDisconnected -> playerId != null && playerName != null
            is NetworkMessage.PlayerReconnected -> playerId != null
            is NetworkMessage.GameOver -> winnerId != null && winnerName != null && scores != null
            is NetworkMessage.EmojiReaction -> playerId != null && emoji != null
            is NetworkMessage.ResyncRequest -> playerId != null
            is NetworkMessage.PlayerLeft -> playerId != null
            is NetworkMessage.ReturnToLobby -> true
            is NetworkMessage.TurnTimedOut -> playerName != null
            is NetworkMessage.PlayRejected -> reason != null
            is NetworkMessage.HostEndedGame -> reason != null
            is NetworkMessage.RemovedFromTable -> reason != null
            is NetworkMessage.ChooseSwapTarget -> targetPlayerId != null
            is NetworkMessage.ChoosingColor -> playerId != null
            is NetworkMessage.Ping -> true
        }
    }

    private data class MessageWrapper(
        val type: String,
        val payload: String
    )
}

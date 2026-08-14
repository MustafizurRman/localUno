package com.mutsho.localuno.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.mutsho.localuno.model.NetworkMessage

object MessageSerializer {
    private val gson: Gson = GsonBuilder().create()

    fun serialize(message: NetworkMessage): String {
        val wrapper = MessageWrapper(
            type = message.type,
            payload = gson.toJson(message)
        )
        return gson.toJson(wrapper) + "\n"
    }

    fun deserialize(json: String): NetworkMessage? {
        return try {
            val wrapper = gson.fromJson(json.trim(), MessageWrapper::class.java)
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
                "CHOOSE_SWAP_TARGET" -> gson.fromJson(wrapper.payload, NetworkMessage.ChooseSwapTarget::class.java)
                "PING" -> gson.fromJson(wrapper.payload, NetworkMessage.Ping::class.java)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class MessageWrapper(
        val type: String,
        val payload: String
    )
}

package com.mutsho.localuno.model

data class Player(
    val id: String,
    val name: String,
    val avatarColor: Int = 0,
    val hand: List<Card> = emptyList(),
    val isHost: Boolean = false,
    val isConnected: Boolean = true,
    val hasCalledUno: Boolean = false,
    val score: Int = 0,
    /**
     * A seat the host plays on everyone's behalf. Bots exist only on the host - they have no socket
     * and send no messages - but they are ordinary players in every other respect: dealt a hand,
     * given turns, knocked out at the Mercy threshold, scored at the end.
     *
     * `isConnected` deliberately stays true for a live bot. It means "still in the round" throughout
     * the engine (getNextPlayerIndex skips on it, knockOutPlayer clears it, calculateScores reads
     * it), not "has an open socket" - so a bot must satisfy it to be dealt in at all.
     */
    val isBot: Boolean = false
) {
    val cardCount: Int get() = hand.size
}

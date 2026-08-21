package com.mutsho.localuno.model

/**
 * Whether a host may take a given seat off the table, and what removing it entails.
 *
 * Pure on purpose. The rest of this decision lives in LobbyViewModel, which is an AndroidViewModel
 * and so reachable only from an instrumented test - and instrumented tests are exactly what this
 * project cannot currently run (the app never reaches Compose UI idle). Four silent guards behind
 * an untestable boundary is how a control quietly starts letting a host remove themself, so the
 * guards live out here where the JVM suite can reach them.
 *
 * Mid-round removal is deliberately absent rather than unimplemented. A seat in a running match
 * holds a hand the engine has dealt and a position the turn order depends on; taking it out means
 * deciding where those cards go without breaking deck conservation. A match already has an answer
 * for a player who should not be there - the host ends it.
 */
object TableRemoval {

    sealed class Verdict {
        /** Remove the seat and say nothing to anyone - the host runs bots itself. */
        data object RemoveBot : Verdict()

        /** Remove the seat, tell them why, and refuse them a way back in. */
        data object RemovePerson : Verdict()

        /** Leave the roster alone. [why] is for a log, not for a player - the UI never offers
         *  a control that would land here. */
        data class Refuse(val why: String) : Verdict()
    }

    /**
     * @param removerIsHost whether the device asking is the host. Only a host may remove anyone.
     * @param gameStarted whether the round is underway.
     * @param target the seat to remove, or null if no such seat is on the roster.
     * @param localPlayerId the asking device's own id, checked separately from [PlayerInfo.isHost]
     *        so a roster that has somehow lost its host flag still cannot be used to self-remove.
     */
    fun decide(
        removerIsHost: Boolean,
        gameStarted: Boolean,
        target: PlayerInfo?,
        localPlayerId: String?
    ): Verdict {
        if (!removerIsHost) return Verdict.Refuse("only the host can remove a player")
        if (gameStarted) return Verdict.Refuse("the round is already underway")
        if (target == null) return Verdict.Refuse("no such player on the roster")
        if (target.isHost) return Verdict.Refuse("the host cannot be removed")
        if (localPlayerId != null && target.id == localPlayerId) {
            return Verdict.Refuse("you cannot remove yourself")
        }
        return if (target.isBot) Verdict.RemoveBot else Verdict.RemovePerson
    }
}

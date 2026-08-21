package com.mutsho.localuno.engine

import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.Player
import com.mutsho.localuno.model.TimeoutAction
import kotlin.random.Random

/**
 * A replayable record of one round: the seed the deck was shuffled with, the table it was dealt to,
 * and every move that was made on it, in order.
 *
 * The point is bug reports. "It got stuck on Circuit's turn" is not something anyone can act on,
 * and the crashes worth chasing are the ones that need a particular hand to show up. Because the
 * only randomness in the engine is [DeckFactory.shuffle] - BotBrain is a pure function of the state
 * and the bots' thinking pause is cosmetic - a seed plus the seat order fixes the deal exactly, and
 * the move list on top of it fixes everything after. Feed both back in and the same round happens
 * again, on a desktop JVM, in milliseconds, as many times as you like.
 *
 * Not free, so it is opt-in: [GameEngine] takes a journal only when one is handed to it, and the
 * app only hands one over on a debug build (see GameViewModel.startGame). With no journal the
 * recording calls are a null check per move and nothing else, which is why this can stay in the
 * shipping source rather than living behind a source set that never gets compiled.
 *
 * The format is deliberately plain text, one move per line, so it can be pasted into a chat message
 * or a test without any tooling. [parse] and [replay] read it back.
 */
class SeedJournal(val seed: Long) {

    /**
     * Guarded by itself. Every move is written from the engine's single dispatcher, but the one
     * reader that matters - the crash handler - runs on whichever thread just died, so the list has
     * to survive being read while it is being appended to.
     */
    private val lines = mutableListOf<String>()

    /** Newest last. Bounded so a long evening can't grow this without limit. */
    val moves: List<String> get() = synchronized(lines) { lines.toList() }

    /**
     * The table, recorded once at the deal. Seat ORDER matters as much as the seed: the engine deals
     * seven to each player in list order, so the same seed with the seats in a different order is a
     * different game.
     */
    private var header: List<String> = emptyList()

    fun begin(settings: GameSettings, players: List<Player>) = synchronized<Unit>(lines) {
        header = buildList {
            add("seed $seed")
            add("mode ${settings.gameMode.name}")
            add("seats ${settings.maxPlayers}")
            add("timeout ${settings.turnTimeoutSeconds ?: "off"} ${settings.turnTimeoutAction.name}")
            add("mercy ${settings.mercyThreshold}")
            add(
                "rules ${settings.rules.stacking} ${settings.rules.lastCardMustBeNumber} " +
                    "${settings.rules.zeroRotates} ${settings.rules.sevenSwaps}"
            )
            players.forEach { add("player ${it.id} ${it.isBot} ${it.name.replace(' ', '_')}") }
        }
        lines.clear()
    }

    fun record(verb: String, vararg args: String) {
        synchronized(lines) {
            if (header.isEmpty()) return      // moves before the deal can't be replayed anyway
            if (lines.size >= MOVE_LIMIT) return
            lines.add((listOf(verb) + args).joinToString(" "))
        }
    }

    fun recordPlay(playerId: String, card: Card, chosenColor: CardColor?) {
        record("play", playerId, card.id.toString(), card.color.name, card.type.name,
            chosenColor?.name ?: "-")
    }

    /** The whole thing, ready to paste. */
    fun dump(): String = synchronized(lines) {
        (listOf(BANNER) + header + listOf("--") + lines).joinToString("\n")
    }

    companion object {
        const val BANNER = "# local-uno seed log v1"

        /**
         * Cap on recorded moves. A round is a few hundred at most; anything past this is a soak or a
         * stuck loop, and neither is worth the memory. Truncating rather than dropping the oldest is
         * deliberate - a replay is only meaningful from the deal forwards, so a log with a hole at
         * the front is worse than a short one.
         */
        const val MOVE_LIMIT = 4000

        /** What [parse] recovers: enough to stand the round back up. */
        data class Recording(
            val seed: Long,
            val settings: GameSettings,
            val players: List<Player>,
            val moves: List<String>
        )

        fun parse(text: String): Recording {
            var seed = 0L
            var mode = GameMode.CLASSIC
            var seats = 6
            var timeout: Int? = 15
            var timeoutAction = TimeoutAction.SKIP
            var mercy = 25
            var rules = GameRules()
            val players = mutableListOf<Player>()
            val moves = mutableListOf<String>()
            var inMoves = false

            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line == "--") { inMoves = true; continue }
                if (inMoves) { moves.add(line); continue }
                val t = line.split(" ")
                when (t[0]) {
                    "seed" -> seed = t[1].toLong()
                    "mode" -> mode = GameMode.valueOf(t[1])
                    "seats" -> seats = t[1].toInt()
                    "timeout" -> {
                        timeout = t[1].toIntOrNull()
                        timeoutAction = TimeoutAction.valueOf(t[2])
                    }
                    "mercy" -> mercy = t[1].toInt()
                    "rules" -> rules = GameRules(
                        stacking = t[1].toBoolean(),
                        lastCardMustBeNumber = t[2].toBoolean(),
                        zeroRotates = t[3].toBoolean(),
                        sevenSwaps = t[4].toBoolean()
                    )
                    "player" -> players.add(
                        Player(
                            id = t[1],
                            isBot = t[2].toBoolean(),
                            name = t.getOrElse(3) { t[1] }.replace('_', ' '),
                            isHost = players.isEmpty()
                        )
                    )
                }
            }
            return Recording(
                seed = seed,
                settings = GameSettings(
                    gameMode = mode,
                    maxPlayers = seats,
                    rules = rules,
                    turnTimeoutSeconds = timeout,
                    mercyThreshold = mercy,
                    turnTimeoutAction = timeoutAction
                ),
                players = players,
                moves = moves
            )
        }

        /**
         * Deal the recorded table from the recorded seed and re-apply every move, returning the
         * engine so a caller can assert on the state it ended in.
         *
         * [upTo] stops early, which is how you bisect a log: halve it until the last move before the
         * symptom is the one you're looking at.
         */
        fun replay(text: String, upTo: Int = Int.MAX_VALUE): GameEngine {
            val rec = parse(text)
            val engine = GameEngine(rec.settings, Random(rec.seed))
            engine.initializeGame(rec.players)
            rec.moves.take(upTo).forEach { apply(engine, it) }
            return engine
        }

        private fun apply(engine: GameEngine, move: String) {
            val t = move.split(" ")
            when (t[0]) {
                "play" -> {
                    val id = t[2].toInt()
                    // Prefer the card the player is actually holding: it carries the flip-side
                    // faces, which the log doesn't, and a mismatched copy would be rejected.
                    val held = engine.getState().getPlayerById(t[1])?.hand?.firstOrNull { it.id == id }
                    val card = held ?: Card(id, CardColor.valueOf(t[3]), CardType.valueOf(t[4]))
                    engine.playCard(t[1], card, t.getOrNull(5)?.takeIf { it != "-" }?.let { CardColor.valueOf(it) })
                }
                "draw" -> engine.drawCardForPlayer(t[1])
                "uno" -> engine.callUno(t[1])
                "catch" -> engine.penalizeUnoNotCalled(t[1], t[2])
                "swap" -> engine.chooseSwapTarget(t[1], t[2])
                "autoswap" -> engine.autoResolveSwap()
                "timeout" -> engine.applyTurnTimeout(t[1])
                "left" -> engine.playerLeft(t[1])
                "disconnect" -> engine.beginDisconnectCountdown(t[1])
                "reconnect" -> engine.reconnectPlayer(t[1])
                "tick" -> engine.tickDisconnectCountdown()
            }
        }
    }
}

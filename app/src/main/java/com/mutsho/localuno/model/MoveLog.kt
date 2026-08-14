package com.mutsho.localuno.model

/**
 * Tint for a move-log row's leading dot. Kept as a semantic enum rather than a raw colour so the
 * model layer stays free of Compose types - the board maps these to the Nocturne palette.
 */
enum class MoveLogTint {
    NEUTRAL,
    WARN,
    DANGER,
    RED,
    YELLOW,
    GREEN,
    BLUE;

    companion object {
        fun forCardColor(color: CardColor): MoveLogTint = when (color) {
            CardColor.RED -> RED
            CardColor.YELLOW -> YELLOW
            CardColor.GREEN -> GREEN
            CardColor.BLUE -> BLUE
            CardColor.WILD -> NEUTRAL
        }
    }
}

/** One line in the board's move log. */
data class MoveLogEntry(
    val text: String,
    val tint: MoveLogTint = MoveLogTint.NEUTRAL
)

/** How many lines the log retains; the strip shows the newest, the sheet shows the rest. */
const val MOVE_LOG_LIMIT = 24

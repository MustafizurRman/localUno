package com.mutsho.localuno.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.R
import com.mutsho.localuno.model.*
import com.mutsho.localuno.model.Direction
import com.mutsho.localuno.ui.theme.*

/**
 * The board's fixed vertical rhythm, identical in all three skins per UnoBoard.dc.html:
 *
 *   top bar -> move-log strip -> felt (per skin) -> turn row -> hand -> action bar
 *
 * Only [felt] and [timer] vary, so each skin supplies just those two and inherits everything else.
 */
@Composable
fun BoardScaffold(
    gameState: GameState,
    localPlayerId: String,
    playableCardIds: Set<Int>,
    hand: List<Card>,
    moveLog: List<MoveLogEntry>,
    logOpen: Boolean,
    onToggleLog: () -> Unit,
    showSymbols: Boolean,
    onPlayCard: (Card) -> Unit,
    onCallUno: () -> Unit,
    onSendEmoji: (String) -> Unit,
    onRequestLeave: () -> Unit,
    sortHandEnabled: Boolean,
    onToggleSortHand: () -> Unit,
    background: Brush,
    timer: @Composable () -> Unit,
    felt: @Composable BoxScope.() -> Unit
) {
    val localPlayer = gameState.getPlayerById(localPlayerId)
    val isMyTurn = gameState.currentPlayer?.id == localPlayerId
    val isSpectating = gameState.isSpectating(localPlayerId)
    val current = gameState.currentPlayer
    val hasPlays = hand.any { it.id in playableCardIds }

    Box(modifier = Modifier.fillMaxSize().background(background)) {
    // heatOn: No Mercy runs with a red inset glow that intensifies with the pending draw stack
    // (heatShadow = inset 0 0 (60 + pending*4)px), pulsing on ubGlow's 1.2s cycle. Drawn behind
    // the content and non-interactive.
    if (gameState.settings.gameMode == GameMode.NO_MERCY) {
        // Gated as well as read-in-draw now. The reasoning below is about WHERE the value is read;
        // this is about whether it should be produced at all. A No Mercy round spends most of its
        // time waiting on other people, and the heat is atmosphere rather than information.
        // A State, deliberately NOT delegated with `by`.
        //
        // `val heatAlpha by ...` reads the animation during COMPOSITION, and the value then fed
        // `.background(...)`, which is a composition-time modifier. That put the whole of
        // BoardScaffold - top bar, felt, seats, the hand, every overlay - into a recomposition on
        // every frame, for as long as a No Mercy round lasted. Not a slow path: a composition
        // subscribed to the frame clock is what this app crashes on, in the Compose runtime with an
        // empty group stack at `endRoot` and no application code on the trace.
        //
        // It is also why the crash looked mode-specific and unreproducible for so long. This whole
        // block is gated on NO_MERCY, so the solo games used for soak testing - Classic deck,
        // default rules - never ran it once, and eight clean rounds said nothing about the table
        // people actually play on, which is No Mercy with every house rule on.
        //
        // Read in drawBehind instead: the pulse still animates, and nothing recomposes to do it.
        val heatAlpha = rememberBreathing(
            active = LocalAmbientMotion.current,
            from = 0.25f, to = 0.8f, periodMs = 1200
        )
        val spread = 60f + gameState.pendingDrawCount * 4f
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                UnoRed.copy(alpha = 0.30f * heatAlpha.value)
                            ),
                            radius = spread * 12f
                        )
                    )
                }
        )
    }

    // Deliberately hoisted out of BoardTopBar so the dialog is a sibling of the board rather than a
    // child of a Row inside it - a dialog composed inside that strip inherits its layout constraints
    // for measurement purposes and is a surprising place for one to live.
    var showRules by remember { mutableStateOf(false) }
    if (showRules) {
        RulesInForceDialog(
            settings = gameState.settings,
            onDismiss = { showRules = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        BoardTopBar(
            chipLabel = gameState.settings.gameMode.displayName.uppercase(),
            chipBg = Accent800,
            chipFg = Accent200,
            chipEdge = Accent700,
            subLabel = "${gameState.players.count { it.isConnected }} playing · ${gameState.drawPile.size} in deck",
            logOpen = logOpen,
            onToggleLog = onToggleLog,
            onRequestLeave = onRequestLeave,
            onShowRules = { showRules = true }
        )

        // Closed by default. The strip used to sit here permanently, spending a band of the screen
        // on one line of text that is only ever interesting for a second after it changes - and
        // taking that band from the felt, which is the part of the board people actually watch.
        // The list button in the top bar already toggles the history; it now toggles the whole
        // thing, so the last move is one tap away and costs nothing while nobody wants it.
        if (logOpen) {
            MoveLogStrip(log = moveLog)
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { felt() }

        BoardTurnRow(
            turnTitle = if (isMyTurn) "YOUR TURN" else "${current?.name ?: "…"}'s turn",
            turnTitleColor = if (isMyTurn) UnoYellow else NocturneText,
            turnTitleSize = if (isMyTurn) 20.sp else 16.sp,
            turnSub = when {
                // Checked before the pause case: a knocked-out player watching a paused table is
                // still, first and foremost, knocked out - "waiting for a player" would read as
                // though they were about to get another turn.
                isSpectating -> "You're out — watching the rest"
                gameState.phase == GamePhase.PAUSED_DISCONNECT -> "Paused — waiting for a player"
                gameState.pendingDrawCount > 0 && isMyTurn -> "Stack it or take +${gameState.pendingDrawCount}"
                // Says what to DO, not just what is true. "Nothing playable" describes the
                // situation and leaves the player to work out that the deck is the way out of it -
                // which is obvious only if you already know this board.
                isMyTurn && !hasPlays -> "Nothing playable — tap the deck to draw"
                // The dial replaced drag-to-play: only the card under the needle is playable, and a
                // tap anywhere else spins rather than plays. Copy that still said "drag a card up"
                // would be describing an interaction the board no longer has.
                isMyTurn -> "Spin the dial · tap the top card"
                else -> "${current?.cardCount ?: 0} cards in hand"
            },
            sortLabel = if (sortHandEnabled) "SORTED" else "SORT",
            onSortHand = onToggleSortHand,
            timer = timer
        )

        // Your own hand registers under YOUR player id, which is what lets the flight overlay treat
        // "you played it" as an ordinary case rather than a special one - your cards fly from your
        // hand, everyone else's from their seat, through exactly the same lookup.
        Box(modifier = Modifier.seatAnchor(localPlayerId, LocalSeatAnchors.current)) {
        HandArc(
            hand = hand,
            playableCardIds = playableCardIds,
            isMyTurn = isMyTurn,
            // The 0 and 7 faces carry a pip stating those rules, so the face has to know whether
            // this table actually plays them.
            rules = gameState.settings.rules,
            showSymbols = showSymbols,
            // "WAITING" implies a turn is coming. For a knocked-out player it never is.
            idleHint = when {
                isSpectating -> "knocked out — watching"
                gameState.phase == GamePhase.PAUSED_DISCONNECT -> "paused"
                else -> "waiting for your turn"
            },
            onPlayCard = onPlayCard,
            // The dial is a fixed share of the screen rather than a function of hand size: its
            // whole point is that forty cards occupy exactly the room four do.
            //
            // Half the design's own 392-of-844. The source devotes that stage to a screen holding
            // nothing but a discard pile; this board also carries a top bar, the move log, the felt
            // with every opponent on it, the turn row and the action bar, and at 46% the hand was
            // squeezing all of them. The dial absorbs the cut better than the old fan could,
            // because it only ever shows about five cards - it is the empty band of dial below the
            // hand that goes, not the cards themselves. See DESIGN_CARD_BASE.
            modifier = Modifier.height(
                (LocalConfiguration.current.screenHeightDp * 196f / 844f).dp
            )
        )
        }

        BoardActionBar(
            // TWO cards, not one: the call is a declaration made before the play that leaves you
            // on your last card. Armed at one card it was a formality tapped after the fact - see
            // GameEngine.callUno.
            canCallUno = localPlayer?.cardCount == 2 && localPlayer.hasCalledUno != true,
            hasCalledUno = localPlayer?.hasCalledUno == true,
            onCallUno = onCallUno,
            onSendEmoji = onSendEmoji
        )
    }

    // Last inside the root Box, so a card in flight passes over the felt and the seats rather than
    // under them. It is positioned in root coordinates from SeatAnchors, so it does not care which
    // skin drew what or how deeply any of it is nested.
    DiscardFlightOverlay(
        gameState = gameState,
        showSymbols = showSymbols,
        rules = gameState.settings.rules
    )
    }
}

/** Face-down deck with its count caption. */
@Composable
fun DrawPile(
    enabled: Boolean,
    onDraw: () -> Unit,
    width: Dp,
    height: Dp,
    caption: String,
    /**
     * True when this player is genuinely stuck - their turn, and not one card in hand can be
     * played. Distinct from [enabled], which is also true while a draw stack is pending, because
     * that case is a CHOICE (stack it or take the penalty) and does not want to be shouted at.
     *
     * Everything below exists because the board had no answer to "what do I do now". A player who
     * cannot play sees a hand where nothing lights up and a deck that looks exactly as inert as it
     * did a moment ago; the only text saying otherwise was two words in the turn row. Someone who
     * has never played this app has no reason to know the deck is even tappable.
     */
    mustDraw: Boolean = false
) {
    // Slow enough to read as breathing rather than blinking - it has to be noticeable without
    // becoming the thing you are trying to ignore while you think.
    // State, not `by` - see `heatAlpha` above. This one is composed for the whole round too.
    //
    // Driven by mustDraw rather than by the ambient gate, because that IS the condition it exists
    // for: this pulse is the board answering "what do I do now" for a player who cannot play a
    // card. It used to breathe for the entire round regardless - through everyone else's turns,
    // and through your own turns when you had perfectly good cards - which is both a waste and a
    // weaker signal, since a cue that is always on says nothing when it matters.
    val pulse = rememberBreathing(
        active = mustDraw,
        from = 0f, to = 1f, periodMs = 1100
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.then(if (enabled) Modifier.clickable { onDraw() } else Modifier)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (mustDraw) {
                // A halo that grows out past the card, so the pull is visible even to someone
                // whose eyes are on their own hand rather than the middle of the table.
                Box(
                    modifier = Modifier
                        .size(width = width + 64.dp, height = height + 64.dp)
                        .drawBehind {
                            // Reach and brightness both raised: at the old 34dp/0.34 the halo
                            // stopped just past the card edge and read as part of the card art
                            // rather than as something asking to be touched.
                            drawRoundRect(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        UnoYellow.copy(alpha = 0.55f * (0.45f + pulse.value)),
                                        Color.Transparent
                                    ),
                                    center = Offset(size.width / 2f, size.height / 2f),
                                    radius = size.minDimension * 0.95f
                                ),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                    30.dp.toPx(), 30.dp.toPx()
                                )
                            )
                            // A hard ring on the card itself. The halo alone is soft enough to be
                            // missed on a bright screen; an edge is not.
                            val inset = 32.dp.toPx()
                            drawRoundRect(
                                color = UnoYellow.copy(alpha = 0.5f + 0.5f * pulse.value),
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - inset * 2f, size.height - inset * 2f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                    10.dp.toPx(), 10.dp.toPx()
                                ),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 2.5f.dp.toPx()
                                )
                            )
                        }
                )
            }
            Box(
                modifier = if (mustDraw) {
                    Modifier.graphicsLayer {
                        val s = 1f + 0.05f * pulse.value
                        scaleX = s
                        scaleY = s
                    }
                } else Modifier
            ) {
                UnoCardBack(width = width, height = height)
            }
        }

        if (mustDraw) {
            // A filled pill rather than loose yellow text on felt. The caption sits over a busy,
            // variably-lit background, and at 9.5sp with no ground behind it the one instruction
            // on the screen was the least legible thing on it.
            Text(
                "TAP TO DRAW",
                color = Color(0xFF1A1400),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp,
                modifier = Modifier
                    .graphicsLayer { alpha = 0.8f + 0.2f * pulse.value }
                    .clip(RoundedCornerShape(8.dp))
                    .background(UnoYellow)
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }
        Text(caption, color = Neutral500, fontSize = 9.sp)
    }
}

/** The discard slot: a dashed target underneath, with the top card sitting on it. */
@Composable
fun DiscardSlot(
    gameState: GameState,
    isMyTurn: Boolean,
    showSymbols: Boolean,
    width: Dp,
    height: Dp,
    rotationDeg: Float = 0f
) {
    // Tagged once here rather than in each skin: all three draw their discard through this, so the
    // flight overlay knows where the pile is on every board without any of them knowing about it.
    Box(modifier = Modifier.size(width, height).seatAnchor(SeatAnchors.DISCARD, LocalSeatAnchors.current)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(
                    width = 1.5.dp,
                    color = if (isMyTurn) NocturneAccent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp)
                )
        )
        // While a card is on its way here, keep showing the one it is about to cover.
        //
        // The engine applies a play before the animation starts, so drawing gameState.topCard
        // unconditionally showed the arriving card settled on the pile AND arriving above it at the
        // same time - the same card in two places, which reads exactly as wrong as it sounds. Found
        // on a device rather than in review; nothing about the code looks incorrect.
        val arriving = LocalCardInFlight.current.cardId
        val shown = gameState.topCard?.let { top ->
            if (top.id == arriving) gameState.discardPile.getOrNull(gameState.discardPile.size - 2)
            else top
        }
        shown?.let { top ->
            // ubBurst: the landing card pops in (scale .4 -> 1.12 -> 1, rotate -9 -> 0) each time
            // the discard changes. This is the design's `flying` moment reduced to its visible
            // payoff - a full hand-to-pile flight would need the card's on-screen origin, which
            // the fanned layout doesn't expose to the felt.
            val burst = remember(top.id) { Animatable(0f) }
            LaunchedEffect(top.id) {
                burst.snapTo(0f)
                burst.animateTo(1f, tween(350))
            }
            val b = burst.value
            val burstScale = when {
                b < 0.55f -> 0.4f + (1.12f - 0.4f) * (b / 0.55f)
                else -> 1.12f - 0.12f * ((b - 0.55f) / 0.45f)
            }
            val burstRot = -9f + 9f * b
            Box(
                modifier = Modifier.graphicsLayer {
                    rotationZ = rotationDeg + burstRot
                    scaleX = burstScale
                    scaleY = burstScale
                    alpha = (b / 0.3f).coerceAtMost(1f)
                }
            ) {
                BoardCardFace(
                    card = top,
                    rules = gameState.settings.rules,
                    showSymbols = showSymbols,
                    dimmed = false,
                    width = width,
                    height = height
                )
            }
        }
    }
}

/** Play-direction glyph. Handed artwork, so the drawable is swapped rather than mirrored. */
@Composable
fun DirectionGlyph(direction: Direction, size: Dp, alpha: Float = 1f) {
    Icon(
        painter = painterResource(
            if (direction == Direction.CLOCKWISE) R.drawable.ic_clockwise
            else R.drawable.ic_counter_clockwise
        ),
        contentDescription = if (direction == Direction.CLOCKWISE) "Play order: clockwise"
        else "Play order: counter-clockwise",
        tint = Color.White.copy(alpha = alpha),
        modifier = Modifier.size(size)
    )
}

fun colorFor(color: CardColor): Color = when (color) {
    CardColor.RED -> UnoRed
    CardColor.YELLOW -> UnoYellow
    CardColor.GREEN -> UnoGreen
    CardColor.BLUE -> UnoBlue
    CardColor.WILD -> Neutral700
}

/**
 * "+2 with +2" style hint under a live draw stack.
 *
 * Derived from the card actually on the pile rather than from the running total, and only claims a
 * match is possible when one really is. It used to read "MATCH +${total}" off the accumulated
 * penalty, so a +4 answered by a +2 advertised "MATCH +6" - a card that cannot answer anything -
 * and a +10, which nothing may ever answer, still told the player to match it.
 */
fun stackHint(gameState: GameState): String {
    val penalty = gameState.topCard ?: return "DRAW OR PASS"
    val total = gameState.pendingDrawCount
    if (!gameState.settings.rules.stacking) return "TAKE +$total"
    // Anything that can answer a penalty can answer itself, so this is the honest test for
    // "is there a card in the deck that gets you out of this".
    val answerable = Card.canStack(penalty.type, penalty.type)
    return if (answerable) "MATCH +${Card.drawValue(penalty.type)} OR DRAW" else "NO ANSWER — TAKE +$total"
}

/** 3dp coloured stripe on one edge of a rail seat. */
fun Modifier.drawEdgeStripe(color: Color, leftEdge: Boolean): Modifier = this.drawBehind {
    if (color == Color.Transparent) return@drawBehind
    val w = 3.dp.toPx()
    drawRect(
        color = color,
        topLeft = if (leftEdge) Offset.Zero else Offset(size.width - w, 0f),
        size = Size(w, size.height)
    )
}

fun Modifier.alphaIf(condition: Boolean, value: Float): Modifier =
    if (condition) this.alpha(value) else this

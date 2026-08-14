package com.mutsho.localuno.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.mutsho.localuno.R
import com.mutsho.localuno.model.Player
import com.mutsho.localuno.ui.theme.*

@Composable
fun EndScreen(
    winnerName: String,
    winnerId: String = "",
    players: List<Player>,
    scores: Map<String, Int>,
    durationSeconds: Long,
    isHost: Boolean,
    onPlayAgain: () -> Unit,
    onBackToMenu: () -> Unit,
    /**
     * Set once the host has gone (they quit, or the connection to them died) while this player was
     * reading the results. Purely informational - the results themselves are local data and stay
     * perfectly valid, so this does NOT navigate anyone away mid-read. What it does is answer the
     * question the screen would otherwise leave hanging: no rematch is coming, stop waiting.
     */
    hostLeftMessage: String? = null
) {
    // Winner first, then everyone still standing, then the knocked-out; within each group, fewer
    // points is better.
    //
    // Two separate traps here. The winner scores the SUM of everyone else's remaining cards, so
    // they always hold the largest number - sorting everyone one way would put the WORST loser in
    // 2nd. And a knocked-out player's hand was cleared at elimination, so ranking purely on points
    // floated them to the top of the losers; being knocked out is the worst outcome in the game and
    // must never outrank someone who survived to the end.
    val sortedPlayers = players.sortedWith(
        compareByDescending<Player> { it.id == winnerId }
            .thenByDescending { it.isConnected }
            .thenBy { scores[it.id] ?: 0 }
    )

    // The connection to the host/clients stays alive here so Play Again can skip reconnecting -
    // without this, a system back gesture would skip onBackToMenu and strand that connection
    // exactly like leaving mid-game would.
    BackHandler { onBackToMenu() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ChromeGradientTop, Background)))
    ) {
        ConfettiOverlay(modifier = Modifier.fillMaxSize())

        // Three bands: a scrolling middle between nothing at the top and pinned actions at the
        // bottom. This used to be one centred Column with no scroll, which silently clipped once
        // the rankings grew - at 8 seats (No Mercy's maximum) the content runs well past a phone's
        // height and PLAY AGAIN / BACK TO MENU were pushed off the bottom edge entirely, leaving no
        // visible way off the screen except the system back gesture.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Winner trophy banner ? animated entrance
                val bannerScale = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    bannerScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                }
                Surface(
                    color = UnoYellow.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.graphicsLayer {
                        scaleX = bannerScale.value; scaleY = bannerScale.value
                        alpha = bannerScale.value
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp)
                    ) {
                        // The banner has been called a "trophy banner" since it was written; this is
                        // the design's actual trophy (assets/icons/trophy.svg) finally in place.
                        Icon(
                            painter = painterResource(R.drawable.ic_trophy),
                            contentDescription = null,
                            tint = UnoYellow,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "WINNER!",
                            color = UnoYellow,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "$winnerName Wins!",
                    color = UnoYellow,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Rankings
                Text(
                    text = "RANKINGS",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                sortedPlayers.forEachIndexed { index, player ->
                    RankingRow(
                        rank = index + 1,
                        name = player.name,
                        cardCount = player.cardCount,
                        isOut = !player.isConnected,
                        score = scores[player.id] ?: 0,
                        isWinner = if (winnerId.isNotEmpty()) player.id == winnerId else player.name == winnerName
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Duration
                val minutes = durationSeconds / 60
                val seconds = durationSeconds % 60
                Text(
                    text = "Game lasted ${minutes}m ${seconds}s",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(24.dp))
            }

            if (hostLeftMessage != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .background(UnoRed.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                        .border(1.dp, UnoRed.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_wifi_small),
                        contentDescription = null,
                        tint = UnoRed,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "$hostLeftMessage — no rematch from here.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp
                    )
                }
            }

            // Pinned actions - always on screen no matter how many players ranked above.
            if (isHost) {
                Button(
                    onClick = onPlayAgain,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = UnoGreen),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("PLAY AGAIN", fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            OutlinedButton(
                onClick = onBackToMenu,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("BACK TO MENU", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private data class ConfettiSpec(
    val xFraction: Float,
    val color: Color,
    val durationMillis: Int,
    val delayMillis: Int
)

private val CONFETTI_COLORS = listOf(UnoRed, UnoYellow, UnoGreen, UnoBlue)
private val CONFETTI_SPECS = List(14) { i ->
    ConfettiSpec(
        xFraction = ((i * 7 + 4) % 100) / 100f,
        color = CONFETTI_COLORS[i % CONFETTI_COLORS.size],
        durationMillis = 2400 + (i % 5) * 400,
        delayMillis = i * 180
    )
}

/** Falling confetti strips behind the win screen content - purely decorative, loops forever. */
@Composable
private fun ConfettiOverlay(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val fallDistance = maxHeight + 60.dp
        CONFETTI_SPECS.forEach { spec ->
            key(spec) {
                val transition = rememberInfiniteTransition(label = "confetti")
                val progress by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(spec.durationMillis, delayMillis = spec.delayMillis, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "confettiFall"
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = maxWidth * spec.xFraction, y = fallDistance * progress - 60.dp)
                        .size(width = 8.dp, height = 14.dp)
                        .graphicsLayer {
                            rotationZ = progress * 420f
                            alpha = 1f - progress
                        }
                        .clip(RoundedCornerShape(2.dp))
                        .background(spec.color)
                )
            }
        }
    }
}

@Composable
private fun RankingRow(rank: Int, name: String, cardCount: Int, isOut: Boolean, score: Int, isWinner: Boolean) {
    val bgColor = if (isWinner) UnoYellow.copy(alpha = 0.2f) else SurfaceVariant

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rank medal
            Text(
                text = when (rank) {
                    1 -> "1st"
                    2 -> "2nd"
                    3 -> "3rd"
                    else -> "#$rank"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = when (rank) {
                    1 -> UnoYellow
                    2 -> Color.LightGray
                    3 -> Color(0xFFCD7F32)
                    else -> Color.White.copy(alpha = 0.6f)
                },
                modifier = Modifier.width(40.dp)
            )

            // Name
            Text(
                text = name,
                color = Color.White,
                fontWeight = if (isWinner) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )

            // Cards remaining
            if (!isWinner) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        // A knocked-out hand was cleared, so "0 cards" would read like a
                        // near-win for the player who actually blew up at the mercy threshold.
                        text = if (isOut) "knocked out" else "$cardCount cards",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                    if (score > 0) {
                        Text(
                            text = "$score pts",
                            color = UnoRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Text(
                    text = "WINNER",
                    color = UnoYellow,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

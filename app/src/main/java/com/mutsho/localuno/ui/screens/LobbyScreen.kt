package com.mutsho.localuno.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.mutsho.localuno.R
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.PlayerInfo
import com.mutsho.localuno.network.JoinLink
import com.mutsho.localuno.ui.components.QrCode
import com.mutsho.localuno.ui.theme.*

@Composable
fun LobbyScreen(
    settings: GameSettings,
    players: List<PlayerInfo>,
    isHost: Boolean,
    onStartGame: () -> Unit,
    onLeave: () -> Unit,
    error: String? = null,
    onClearError: () -> Unit = {},
    hostAddress: String? = null,
    onAddBot: () -> Unit = {},
    onRemoveBot: (String) -> Unit = {}
) {
    // Leaving as host with others already present destroys the lobby for everyone - confirm first.
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    val requestLeave = {
        if (isHost && players.size > 1) showLeaveConfirm = true else onLeave()
    }

    // Without this, a system back gesture skips onLeave (and its host confirmation) entirely,
    // stranding the live GameServer/GameClient connection running in the background while the
    // UI moves on - the host would keep accepting connections and advertising the lobby, or a
    // client would keep occupying a seat on the host's roster, with nothing left to clean it up.
    BackHandler { requestLeave() }

    // The QR carries the same host:port the text line above shows, so a guest can either scan it
    // or read it out - the code is a shortcut, never the only way in.
    if (showQr && hostAddress != null) {
        val hostPort = hostAddress.split(":")
        val ip = hostPort.getOrNull(0).orEmpty()
        val port = hostPort.getOrNull(1)?.toIntOrNull()
        if (ip.isNotBlank() && port != null) {
            AlertDialog(
                onDismissRequest = { showQr = false },
                title = { Text("Scan to join", color = Color.White) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        QrCode(
                            content = JoinLink(
                                host = ip,
                                port = port,
                                lobbyName = settings.lobbyName,
                                hasPin = settings.pin != null
                            ).encode(),
                            size = 240.dp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = hostAddress,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.sp
                        )
                        if (settings.pin != null) {
                            // The PIN is deliberately NOT in the code. Anyone who can see the
                            // host's screen can scan it, which would make the PIN no barrier at
                            // all; the QR saves typing an address, not the PIN protecting the table.
                            Text(
                                text = "They'll still need the PIN: ${settings.pin}",
                                color = UnoYellow,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showQr = false }) { Text("Done", color = Color.White) }
                },
                containerColor = NocturneSurface
            )
        }
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("Leave lobby?", color = Color.White) },
            text = {
                Text(
                    "You're the host - leaving will end the lobby for the ${players.size - 1} other player(s) still here.",
                    color = Color.White.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                TextButton(onClick = { showLeaveConfirm = false; onLeave() }) {
                    Text("Leave", color = UnoRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text("Cancel", color = Color.White) }
            },
            containerColor = SurfaceVariant
        )
    }

    if (error != null) {
        AlertDialog(
            onDismissRequest = { onClearError(); onLeave() },
            title = { Text("Cannot Join", color = Color.White) },
            text = { Text(error, color = Color.White.copy(alpha = 0.8f)) },
            confirmButton = {
                TextButton(onClick = { onClearError(); onLeave() }) { Text("OK", color = UnoYellow) }
            },
            containerColor = SurfaceVariant
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ChromeGradientTop, Background)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = requestLeave) {
                    Icon(painterResource(R.drawable.ic_arrow_left), "Leave", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = settings.lobbyName,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = settings.gameMode.displayName,
                        color = UnoYellow,
                        fontSize = 14.sp
                    )
                    // Show PIN to host so they can share it
                    if (isHost && settings.pin != null) {
                        Text(
                            text = "PIN: ${settings.pin}",
                            color = UnoYellow,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    // Fallback for players whose mDNS discovery can't see this lobby
                    if (isHost && hostAddress != null) {
                        Text(
                            text = "Can't be found? Show the QR, or share IP: $hostAddress",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { showQr = !showQr }
                                .padding(vertical = 2.dp)
                        )
                    }
                }
                // Player count badge
                Surface(
                    color = UnoRed,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "${players.size} / ${settings.maxPlayers}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_users),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "PLAYERS",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Player list
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(players) { player ->
                    PlayerRow(
                        player = player,
                        // Only the host runs bots, and only before the round starts.
                        onRemoveBot = if (isHost && player.isBot) {
                            { onRemoveBot(player.id) }
                        } else null
                    )
                }

                // Empty slots. For the host these double as the add-a-bot control: the row is
                // already a seat-shaped target sitting exactly where the new player would appear,
                // so filling one needs no extra chrome and reads as what it does.
                val emptySlots = settings.maxPlayers - players.size
                items(emptySlots) {
                    EmptySlotRow(onAddBot = if (isHost) onAddBot else null)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Waiting indicator or Start button
            if (isHost) {
                Button(
                    onClick = onStartGame,
                    enabled = players.size >= 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UnoGreen,
                        disabledContainerColor = Color.Gray
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_play), null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "START GAME",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Waiting for host to start...",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Leave button
            OutlinedButton(
                onClick = requestLeave,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("Leave Lobby")
            }
        }
    }
}

@Composable
private fun PlayerRow(player: PlayerInfo, onRemoveBot: (() -> Unit)? = null) {
    val avatarColor = AvatarColors.getOrElse(player.avatarColor % AvatarColors.size) { AvatarColors[0] }

    Surface(
        color = SurfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(avatarColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = player.name.take(1).uppercase().ifEmpty { "?" },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Name
            Text(
                text = player.name,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )

            // Bot badge. On the board the seats aren't marked - the three skins' seat layouts have
            // no free slot that doesn't already carry OUT/UNO!/CATCH, which all matter more - so
            // bots are told apart there by their names instead, and this is where it's stated
            // outright.
            if (player.isBot) {
                Surface(
                    color = NocturneAccent.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "BOT",
                        color = NocturneAccent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                if (onRemoveBot != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = onRemoveBot, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove ${player.name}",
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Host badge
            if (player.isHost) {
                Surface(
                    color = UnoYellow,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "HOST",
                        color = Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            // Connection status
            if (!player.isConnected) {
                Surface(
                    color = UnoRed.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "Disconnected",
                        color = UnoRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptySlotRow(onAddBot: (() -> Unit)? = null) {
    Surface(
        color = SurfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onAddBot != null) Modifier.clickable { onAddBot() } else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (onAddBot != null) NocturneAccent.copy(alpha = 0.18f)
                        else Color.Gray.copy(alpha = 0.3f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (onAddBot != null) Icons.Default.Add else Icons.Default.Person,
                    contentDescription = null,
                    tint = if (onAddBot != null) NocturneAccent else Color.White.copy(alpha = 0.3f)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                // The seat is genuinely still open to a real player either way - tapping it just
                // fills it now instead of waiting, so the host's copy says so rather than
                // implying the slot is bot-only.
                text = if (onAddBot != null) "Add a bot" else "Waiting...",
                color = if (onAddBot != null) Color.White.copy(alpha = 0.75f)
                else Color.White.copy(alpha = 0.4f),
                fontSize = 14.sp
            )
        }
    }
}

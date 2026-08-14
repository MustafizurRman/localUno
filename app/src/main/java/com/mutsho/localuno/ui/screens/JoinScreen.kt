package com.mutsho.localuno.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import com.mutsho.localuno.R
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.network.JoinLink
import com.mutsho.localuno.network.NsdHelper
import com.mutsho.localuno.ui.components.rememberQrJoinScanner
import com.mutsho.localuno.ui.theme.*

@Composable
fun JoinScreen(
    lobbies: List<NsdHelper.LobbyInfo>,
    isScanning: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onJoinLobby: (NsdHelper.LobbyInfo, String?) -> Unit,
    onManualJoin: (String, Int, String?) -> Unit = { _, _, _ -> }
) {
    // A scanned code carries host and port, which is exactly what a manual entry provides - so a
    // scan lands in the same join path rather than a parallel one of its own.
    var scanError by remember { mutableStateOf<String?>(null) }
    var pendingPinFor by remember { mutableStateOf<JoinLink?>(null) }
    val openScanner = rememberQrJoinScanner(
        onResult = { link ->
            if (link.hasPin) pendingPinFor = link
            else onManualJoin(link.host, link.port, null)
        },
        onNotOurCode = { scanError = "That QR isn't a Local UNO table." }
    )
    var showPinDialog by remember { mutableStateOf(false) }
    var selectedLobby by remember { mutableStateOf<NsdHelper.LobbyInfo?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var showManualDialog by remember { mutableStateOf(false) }

    // Without this, a system back gesture skips onBack entirely (that's only wired to the
    // in-UI arrow button) and leaves NSD discovery running in the background indefinitely -
    // there's nothing else that would stop it once this screen is gone.
    BackHandler { onBack() }

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
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_left), "Back", tint = Color.White)
                }
                Text(
                    text = "Join Game",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = openScanner) {
                    Icon(
                        painter = painterResource(R.drawable.ic_qr_scan),
                        contentDescription = "Scan QR code",
                        tint = UnoYellow
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, "Refresh", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Scanning indicator
            if (isScanning) {
                // Broadcast rings from the design's assets/wifi.svg, gently pulsing while we scan.
                val scanPulse = rememberInfiniteTransition(label = "scanPulse")
                val scanAlpha by scanPulse.animateFloat(
                    initialValue = 0.45f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1100, easing = EaseInOutSine),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "scanAlpha"
                )
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_wifi_scan),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(84.dp).alpha(scanAlpha)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = UnoYellow
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Scanning for nearby games...",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (lobbies.isEmpty() && !isScanning) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(R.drawable.ic_empty_table),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(width = 150.dp, height = 105.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No games found nearby",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Make sure you're on the same Wi-Fi network\nas the host device",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = openScanner,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NocturneAccent,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Scan host's QR code")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onRefresh,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text("Scan Again")
                        }
                    }
                }
            } else {
                // Lobby list
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(lobbies) { lobby ->
                        LobbyCard(
                            lobby = lobby,
                            onClick = {
                                if (lobby.hasPin) {
                                    selectedLobby = lobby
                                    showPinDialog = true
                                } else {
                                    onJoinLobby(lobby, null)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Fallback for when mDNS discovery can't see the lobby (guest/corporate Wi-Fi)
            TextButton(
                onClick = { showManualDialog = true },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Can't find it? Enter IP manually", color = UnoYellow, fontSize = 13.sp)
            }
        }

        // A scanned table that wants a PIN. Reuses nothing from the lobby PIN dialog below because
        // that one is keyed on a discovered LobbyInfo, and the whole point of the scan path is that
        // discovery never saw this table.
        pendingPinFor?.let { link ->
            var scannedPin by remember(link) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { pendingPinFor = null },
                title = { Text("Enter PIN") },
                text = {
                    Column {
                        Text(
                            text = if (link.lobbyName.isNotBlank()) link.lobbyName else link.host,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = scannedPin,
                            onValueChange = {
                                if (it.length <= 4 && it.all { c -> c.isDigit() }) scannedPin = it
                            },
                            label = { Text("4-digit PIN") },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onManualJoin(link.host, link.port, scannedPin)
                            pendingPinFor = null
                        },
                        enabled = scannedPin.length == 4
                    ) { Text("Join") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingPinFor = null }) { Text("Cancel") }
                },
                containerColor = SurfaceVariant
            )
        }

        // A scan that succeeded but read something else entirely. Saying so beats doing nothing,
        // which is indistinguishable from the camera having failed.
        scanError?.let { message ->
            AlertDialog(
                onDismissRequest = { scanError = null },
                title = { Text("Not a table code") },
                text = { Text(message, color = Color.White.copy(alpha = 0.8f)) },
                confirmButton = {
                    TextButton(onClick = { scanError = null }) { Text("OK", color = UnoYellow) }
                },
                containerColor = SurfaceVariant
            )
        }

        // PIN dialog
        if (showPinDialog && selectedLobby != null) {
            AlertDialog(
                onDismissRequest = {
                    showPinDialog = false
                    pinInput = ""
                },
                title = { Text("Enter PIN") },
                text = {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pinInput = it },
                        label = { Text("4-digit PIN") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            selectedLobby?.let { onJoinLobby(it, pinInput) }
                            showPinDialog = false
                            pinInput = ""
                        },
                        enabled = pinInput.length == 4
                    ) {
                        Text("Join")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showPinDialog = false
                        pinInput = ""
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Manual IP:port entry dialog - NSD discovery fallback
        if (showManualDialog) {
            var address by remember { mutableStateOf("") }
            var manualPin by remember { mutableStateOf("") }
            val parsedPort = address.substringAfter(':', "").toIntOrNull()
            val hostPart = address.substringBefore(':').trim()
            val isValid = hostPart.isNotEmpty() && parsedPort != null

            AlertDialog(
                onDismissRequest = { showManualDialog = false },
                title = { Text("Enter Host Address") },
                text = {
                    Column {
                        Text(
                            "Ask the host to read their IP from the lobby screen.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("IP:port (e.g. 192.168.1.5:54321)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = manualPin,
                            onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) manualPin = it },
                            label = { Text("PIN (if the lobby has one)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onManualJoin(hostPart, parsedPort!!, manualPin.ifBlank { null })
                            showManualDialog = false
                        },
                        enabled = isValid
                    ) {
                        Text("Connect")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showManualDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun LobbyCard(lobby: NsdHelper.LobbyInfo, onClick: () -> Unit) {
    Surface(
        color = SurfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = lobby.name,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (lobby.hasPin) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_lock),
                            contentDescription = "PIN protected",
                            tint = UnoYellow,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    // lobby.gameMode is the raw enum name broadcast over mDNS (e.g. "NO_MERCY") -
                    // show the friendly display name instead, falling back to the raw value for
                    // forward-compat if a future mode string doesn't match anything we know here.
                    text = GameMode.entries.find { it.name == lobby.gameMode }?.displayName ?: lobby.gameMode,
                    color = UnoYellow,
                    fontSize = 12.sp
                )
            }

            // Player count
            Surface(
                color = Background,
                shape = RoundedCornerShape(8.dp)
            ) {
                // Capacity, not occupancy. The advertised mDNS "count" is fixed at registration and
                // can't be refreshed without re-registering the service (see
                // NsdHelper.updateServicePlayerCount), so it always read "1" no matter how many had
                // actually joined. Showing the seat limit is information we can stand behind; the
                // real roster lands a moment later over TCP.
                Text(
                    text = "up to ${lobby.maxPlayers}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

package com.mutsho.localuno.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.ui.components.LocalCardSkin
import com.mutsho.localuno.ui.screens.*
import kotlinx.coroutines.delay
import com.mutsho.localuno.viewmodel.GameViewModel
import com.mutsho.localuno.viewmodel.LobbyViewModel
import com.mutsho.localuno.viewmodel.MainViewModel

object Routes {
    const val MAIN_MENU = "main_menu"
    const val CUSTOM_TABLE = "custom_table"
    const val LOBBY = "lobby"
    const val JOIN = "join"
    const val GAME = "game"
    const val END = "end"
    const val CARD_GALLERY = "card_gallery"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val mainViewModel: MainViewModel = viewModel()
    val lobbyViewModel: LobbyViewModel = viewModel()
    val gameViewModel: GameViewModel = viewModel()

    // Provided once for the whole app rather than passed down. Card faces are drawn from the hand
    // dial, the discard pile, the seat rails, the card gallery and the settings preview, and none
    // of those wants a skin parameter it does nothing with but forward.
    val cardSkin by mainViewModel.cardSkin.collectAsState()

    CompositionLocalProvider(LocalCardSkin provides cardSkin) {
    NavHost(navController = navController, startDestination = Routes.MAIN_MENU) {
        composable(Routes.MAIN_MENU) {
            val playerName by mainViewModel.playerName.collectAsState()
            val avatarColor by mainViewModel.avatarColor.collectAsState()
            val gamesPlayed by mainViewModel.gamesPlayed.collectAsState()
            val gamesWon by mainViewModel.gamesWon.collectAsState()
            val lobbies by lobbyViewModel.lobbies.collectAsState()

            // The design's JOIN NEARBY sub-line reports a live table count, which means the menu
            // itself has to be discovering. Scanning starts when the menu is shown and stops when
            // it isn't - NsdHelper's own 12s auto-stop then caps how long the radio stays up.
            DisposableEffect(Unit) {
                lobbyViewModel.startScanning()
                onDispose { lobbyViewModel.stopScanning() }
            }

            MainMenuScreen(
                playerName = playerName,
                onNameChange = { mainViewModel.updatePlayerName(it) },
                avatarColor = avatarColor,
                onAvatarColorChange = { mainViewModel.updateAvatarColor(it) },
                gamesPlayed = gamesPlayed,
                gamesWon = gamesWon,
                nearbyCount = lobbies.size,
                // One entry point. HOST A TABLE and the old "Custom table" link went to two
                // different screens that did the same job - both picked a deck, seats and rules,
                // both emitted a GameSettings, both landed in the same lobby. The deck choice is a
                // chip on the host screen now, so there is one way to open a table.
                onCreateGame = { navController.navigate(Routes.CUSTOM_TABLE) },
                // Same screen as hosting: solo is the same table with nobody invited.
                onPlaySolo = { navController.navigate(Routes.CUSTOM_TABLE) },
                onJoinGame = { navController.navigate(Routes.JOIN) },
                onInspectCards = { navController.navigate(Routes.CARD_GALLERY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.CARD_GALLERY) {
            CardGalleryScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            // Reads and writes the same MainViewModel preferences the in-game skin sheet does, so
            // the two can never drift apart.
            val playerName by mainViewModel.playerName.collectAsState()
            val avatarColor by mainViewModel.avatarColor.collectAsState()
            val boardSkin by mainViewModel.boardSkin.collectAsState()
            val showCardSymbols by mainViewModel.showCardSymbols.collectAsState()
            val hapticsEnabled by mainViewModel.hapticsEnabled.collectAsState()
            val turnAlertEnabled by mainViewModel.turnAlertEnabled.collectAsState()
            val keepHandSorted by mainViewModel.keepHandSorted.collectAsState()

            SettingsScreen(
                playerName = playerName,
                onNameChange = { mainViewModel.updatePlayerName(it) },
                avatarColor = avatarColor,
                onAvatarColorChange = { mainViewModel.updateAvatarColor(it) },
                boardSkin = boardSkin,
                onBoardSkinChange = { mainViewModel.updateBoardSkin(it) },
                cardSkin = cardSkin,
                onCardSkinChange = { mainViewModel.updateCardSkin(it) },
                showCardSymbols = showCardSymbols,
                onToggleCardSymbols = { mainViewModel.toggleCardSymbols() },
                hapticsEnabled = hapticsEnabled,
                onToggleHaptics = { mainViewModel.toggleHaptics() },
                turnAlertEnabled = turnAlertEnabled,
                onToggleTurnAlert = { mainViewModel.toggleTurnAlert() },
                keepHandSorted = keepHandSorted,
                onToggleKeepHandSorted = { mainViewModel.toggleKeepHandSorted() },
                onBack = { navController.popBackStack() }
            )
        }


        // Solo: the same settings screen in solo dress, then straight onto the board. No lobby step
        // - there is nobody to wait for, and no server is started, so a lobby would only be a
        // screen showing a table that is already complete.

        composable(Routes.CUSTOM_TABLE) {
            val savedPreset by mainViewModel.rulePreset.collectAsState()
            CustomTableScreen(
                savedPresetKey = savedPreset,
                onPresetSaved = { mainViewModel.setRulePreset(it) },
                onBack = { navController.popBackStack() },
                onPlaySolo = { settings ->
                    lobbyViewModel.startSolo(
                        settings = settings.copy(lobbyName = "Solo table"),
                        playerId = mainViewModel.playerId.value,
                        playerName = mainViewModel.playerName.value,
                        avatarColor = mainViewModel.avatarColor.value
                    )
                    gameViewModel.initializeAsHost(
                        settings = settings,
                        // Read back rather than rebuilt here: startSolo owns what a solo roster is.
                        players = lobbyViewModel.players.value,
                        localPlayerId = mainViewModel.playerId.value,
                        server = null
                    )
                    navController.navigate(Routes.GAME) { popUpTo(Routes.MAIN_MENU) }
                },
                onOpenTable = { settings ->
                    val playerId = mainViewModel.playerId.value
                    val playerName = mainViewModel.playerName.value
                    val avatarColor = mainViewModel.avatarColor.value
                    lobbyViewModel.leaveLobby()
                    // Named after the host: a deck-derived name ("No Mercy table") is identical
                    // for every host running that deck, which collides on the wire - NsdHelper
                    // registers as "LocalUno-<lobbyName>" and Android silently renames duplicates.
                    val named = settings.copy(
                        lobbyName = settings.lobbyName.ifBlank { "$playerName's table" }
                    )
                    lobbyViewModel.createLobby(named, playerId, playerName, avatarColor)
                    navController.navigate(Routes.LOBBY)
                }
            )
        }

        composable(Routes.LOBBY) {
            val settings by lobbyViewModel.settings.collectAsState()
            val players by lobbyViewModel.players.collectAsState()
            val isHost by lobbyViewModel.isHost.collectAsState()
            val gameStarted by lobbyViewModel.gameStarted.collectAsState()
            val error by lobbyViewModel.error.collectAsState()
            val hostAddress by lobbyViewModel.hostAddress.collectAsState()

            LaunchedEffect(gameStarted) {
                if (gameStarted) {
                    if (isHost) {
                        val server = lobbyViewModel.getServer() ?: return@LaunchedEffect
                        gameViewModel.initializeAsHost(
                            settings = settings,
                            players = players,
                            localPlayerId = mainViewModel.playerId.value,
                            server = server
                        )
                    } else {
                        val client = lobbyViewModel.getClient() ?: return@LaunchedEffect
                        gameViewModel.initializeAsClient(
                            localPlayerId = mainViewModel.playerId.value,
                            client = client,
                            players = players,
                            settings = settings,
                            reconnect = { lobbyViewModel.attemptReconnect() }
                        )
                    }
                    navController.navigate(Routes.GAME) {
                        popUpTo(Routes.MAIN_MENU)
                    }
                }
            }

            LobbyScreen(
                settings = settings,
                players = players,
                isHost = isHost,
                onStartGame = { lobbyViewModel.startGame() },
                onLeave = {
                    lobbyViewModel.leaveLobby()
                    navController.popBackStack(Routes.MAIN_MENU, inclusive = false)
                },
                error = error,
                onClearError = { lobbyViewModel.clearError() },
                hostAddress = hostAddress,
                onAddBot = { lobbyViewModel.addBot() },
                onRemoveBot = { lobbyViewModel.removeBot(it) }
            )
        }

        composable(Routes.JOIN) {
            val lobbies by lobbyViewModel.lobbies.collectAsState()
            val isScanning by lobbyViewModel.isScanning.collectAsState()
            val gameStarted by lobbyViewModel.gameStarted.collectAsState()

            LaunchedEffect(Unit) {
                lobbyViewModel.startScanning()
            }

            LaunchedEffect(gameStarted) {
                if (gameStarted) {
                    val client = lobbyViewModel.getClient() ?: return@LaunchedEffect
                    gameViewModel.initializeAsClient(
                        localPlayerId = mainViewModel.playerId.value,
                        client = client,
                        players = lobbyViewModel.players.value,
                        settings = lobbyViewModel.settings.value,
                        reconnect = { lobbyViewModel.attemptReconnect() }
                    )
                    navController.navigate(Routes.GAME) {
                        popUpTo(Routes.MAIN_MENU)
                    }
                }
            }

            JoinScreen(
                lobbies = lobbies,
                isScanning = isScanning,
                onBack = {
                    lobbyViewModel.stopScanning()
                    navController.popBackStack()
                },
                onRefresh = { lobbyViewModel.startScanning() },
                onJoinLobby = { lobby, pin ->
                    lobbyViewModel.stopScanning()
                    lobbyViewModel.joinLobby(
                        lobby = lobby,
                        pin = pin,
                        playerId = mainViewModel.playerId.value,
                        playerName = mainViewModel.playerName.value,
                        avatarColor = mainViewModel.avatarColor.value
                    )
                    navController.navigate(Routes.LOBBY)
                },
                onManualJoin = { host, port, pin ->
                    lobbyViewModel.stopScanning()
                    lobbyViewModel.joinManual(
                        host = host,
                        port = port,
                        pin = pin,
                        playerId = mainViewModel.playerId.value,
                        playerName = mainViewModel.playerName.value,
                        avatarColor = mainViewModel.avatarColor.value
                    )
                    navController.navigate(Routes.LOBBY)
                }
            )
        }

        composable(Routes.GAME) {
            val gameState by gameViewModel.gameState.collectAsState()
            val localPlayerId by gameViewModel.localPlayerId.collectAsState()
            val showColorPicker by gameViewModel.showColorPicker.collectAsState()
            val playableCardIds by gameViewModel.playableCardIds.collectAsState()
            val isGameOver by gameViewModel.gameOver.collectAsState()
            val boardSkin by mainViewModel.boardSkin.collectAsState()
            val isHostInGame by gameViewModel.isHost.collectAsState()
            val moveLog by gameViewModel.moveLog.collectAsState()
            val winnerNameInGame by gameViewModel.winnerName.collectAsState()
            val showCardSymbols by mainViewModel.showCardSymbols.collectAsState()
            val hapticsEnabled by mainViewModel.hapticsEnabled.collectAsState()
            val turnAlertEnabled by mainViewModel.turnAlertEnabled.collectAsState()
            val keepHandSorted by mainViewModel.keepHandSorted.collectAsState()
            val reconnecting by gameViewModel.reconnecting.collectAsState()
            val reconnectAttempt by gameViewModel.reconnectAttemptNumber.collectAsState()

            // UNO announcement ? auto-dismiss after 2.5 seconds
            var unoAnnouncement by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                gameViewModel.unoAnnouncement.collect { name ->
                    unoAnnouncement = name
                    delay(2500)
                    unoAnnouncement = null
                }
            }

            // Knockout callout - auto-dismiss after 2.5 seconds, same pattern as the UNO one
            var knockoutAnnouncement by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                gameViewModel.knockoutEvent.collect { name ->
                    knockoutAnnouncement = name
                    delay(2500)
                    knockoutAnnouncement = null
                }
            }

            // Turn-timeout toast - auto-dismiss after 2.5 seconds. Also reused for the
            // play-rejected notice (e.g. a wild-card pick that arrived after the timer already
            // moved the turn on) - same "routine status banner" treatment, no need for a
            // second overlay/param threaded through all 3 board skins.
            var timeoutAnnouncement by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                gameViewModel.timeoutEvent.collect { (name, drew) ->
                    // Says what happened. It used to claim a draw unconditionally, which was wrong
                    // on every table running the default Skip rule.
                    timeoutAnnouncement =
                        if (drew) "Time's up — $name drew a card"
                        else "Time's up — $name's turn was skipped"
                    delay(2500)
                    timeoutAnnouncement = null
                }
            }
            LaunchedEffect(Unit) {
                gameViewModel.playRejectedEvent.collect { reason ->
                    timeoutAnnouncement = reason
                    delay(2500)
                    timeoutAnnouncement = null
                }
            }

            // Emoji reactions - this collector didn't exist, so every reaction sent by anyone
            // (including your own local echo) was silently dropped and the buttons did nothing.
            var emojiAnnouncement by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                gameViewModel.emojiEvent.collect { reaction ->
                    val name = gameViewModel.gameState.value
                        .getPlayerById(reaction.playerId)?.name ?: "Someone"
                    emojiAnnouncement = "$name: ${reaction.emoji}"
                    delay(1800)
                    emojiAnnouncement = null
                }
            }

            // Host left mid-match - show why, then follow the same teardown+navigate path a
            // client's own "leave" action would take (there's no winner here, so gameOver
            // doesn't fire for this).
            val gameEndedReason by gameViewModel.gameEndedReason.collectAsState()
            LaunchedEffect(gameEndedReason) {
                gameEndedReason?.let { reason ->
                    timeoutAnnouncement = reason
                    delay(1500)
                    gameViewModel.leaveGame()
                    // Also tear down the lobby side. Without this its gameStarted stays true, and
                    // the Join/Lobby screens' LaunchedEffect(gameStarted) would immediately bounce
                    // the player straight back into the dead game screen.
                    lobbyViewModel.leaveLobby()
                    navController.navigate(Routes.MAIN_MENU) {
                        popUpTo(Routes.MAIN_MENU) { inclusive = true }
                    }
                }
            }

            // The host started a rematch while this client was still sitting on the finished board.
            //
            // A non-host's winner overlay offers only FULL RESULTS - there's no Play Again for them
            // - so the board, not the End screen, is where a client most naturally waits after a
            // round. This listener used to exist ONLY on the End route, and rematchRequested is a
            // SharedFlow with no replay: with the End screen not composed, the emission went
            // nowhere. The host would return to the lobby and start the next round while this
            // player sat on a dead board that could never update again, with no way out but Leave.
            LaunchedEffect(Unit) {
                lobbyViewModel.rematchRequested.collect {
                    gameViewModel.resetGame()
                    navController.navigate(Routes.LOBBY) {
                        popUpTo(Routes.MAIN_MENU)
                    }
                }
            }

            val haptic = LocalHapticFeedback.current
            val isSolo by lobbyViewModel.isSolo.collectAsState()

            // The round now ends ON the board, per UnoBoard.dc.html's `winner` state, instead of
            // immediately navigating away. Stats are still banked here exactly once - this effect
            // only restarts on the false->true edge, not on every recomposition.
            LaunchedEffect(isGameOver) {
                // Solo rounds are excluded. The menu presents these as a record against other
                // people ("0 games · 0 wins"), and a table of bots you can add and remove at will
                // is a practice mode - counting it lets anyone run the number up against opponents
                // they configured, which makes the figure mean nothing.
                if (isGameOver && !isSolo) {
                    mainViewModel.recordRoundFinished(won = gameState.winnerId == localPlayerId)
                }
            }

            UnoBoardScreen(
                gameState = gameState,
                localPlayerId = localPlayerId,
                playableCardIds = playableCardIds,
                boardSkin = boardSkin,
                moveLog = moveLog,
                showSymbols = showCardSymbols,
                sortHandEnabled = keepHandSorted,
                onToggleSortHand = { mainViewModel.toggleKeepHandSorted() },
                isHost = isHostInGame,
                onLeaveGame = {
                    gameViewModel.leaveGame()
                    // Same reason as the host-ended path above: leaves gameStarted true otherwise,
                    // which re-navigates the player into the game they just left.
                    lobbyViewModel.leaveLobby()
                    navController.navigate(Routes.MAIN_MENU) {
                        popUpTo(Routes.MAIN_MENU) { inclusive = true }
                    }
                },
                onPlayCard = {
                    if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    gameViewModel.playCard(it)
                },
                onDrawCard = {
                    if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    gameViewModel.drawCard()
                },
                onCallUno = {
                    if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    gameViewModel.callUno()
                },
                onCallUnoOn = { gameViewModel.callUnoOn(it) },
                onSendEmoji = { gameViewModel.sendEmoji(it) },
                onChooseSwapTarget = { gameViewModel.chooseSwapTarget(it) },
                isRoundOver = isGameOver,
                winnerName = winnerNameInGame,
                onPlayAgain = {
                    // Solo restarts in place. There is nobody to re-gather, so routing through the
                    // lobby would just show a full table and ask you to press start again.
                    if (isSolo) {
                        lobbyViewModel.prepareRematch(gameState.players)
                        gameViewModel.resetGame()
                        gameViewModel.initializeAsHost(
                            settings = gameState.settings,
                            players = lobbyViewModel.players.value,
                            localPlayerId = mainViewModel.playerId.value,
                            server = null
                        )
                    } else {
                        lobbyViewModel.prepareRematch(gameState.players)
                        gameViewModel.resetGame()
                        navController.navigate(Routes.LOBBY) { popUpTo(Routes.MAIN_MENU) }
                    }
                },
                onFullResults = {
                    navController.navigate(Routes.END) {
                        popUpTo(Routes.GAME) { inclusive = true }
                    }
                },
                showColorPicker = showColorPicker,
                onColorChosen = { gameViewModel.chooseColor(it) },
                unoAnnouncement = unoAnnouncement,
                knockoutAnnouncement = knockoutAnnouncement,
                timeoutAnnouncement = timeoutAnnouncement,
                emojiAnnouncement = emojiAnnouncement,
                reconnecting = reconnecting,
                reconnectAttempt = reconnectAttempt,
                getConnectionAgeMs = { gameViewModel.connectionAgeMs() },
                turnAlertOn = turnAlertEnabled,
            )
        }

        composable(Routes.END) {
            val gameState by gameViewModel.gameState.collectAsState()
            val isHost by gameViewModel.isHost.collectAsState()
            val winnerName by gameViewModel.winnerName.collectAsState()
            val scores by gameViewModel.scores.collectAsState()
            val durationSeconds by gameViewModel.durationSeconds.collectAsState()
            val isSolo by lobbyViewModel.isSolo.collectAsState()

            // Non-host clients can't watch gameStarted from here (that's only observed on the
            // Lobby screen) - this is the host's signal to pull them back for a rematch.
            LaunchedEffect(Unit) {
                lobbyViewModel.rematchRequested.collect {
                    navController.navigate(Routes.LOBBY) {
                        popUpTo(Routes.MAIN_MENU)
                    }
                }
            }

            // The host quit (or the link to them died) while this player was reading the results.
            // This was only observed on the Game route, so from here it went unheard: the player
            // kept waiting for a rematch that could never arrive, on a socket that was already
            // dead. It is sticky state now (see GameViewModel.gameEndedReason), so it survives the
            // hand-off between routes rather than needing a subscriber at the exact instant.
            //
            // Deliberately NOT the Game route's treatment. There, the board is live state that has
            // become meaningless, so it toasts and evicts to the menu. Here the results are local
            // data that stays completely valid, and yanking someone off a scoreboard they're still
            // reading would be the worse bug. So this only says so, and releases the connection -
            // Back to Menu was always available and still is.
            val hostLeftMessage by gameViewModel.gameEndedReason.collectAsState()
            LaunchedEffect(hostLeftMessage) {
                if (hostLeftMessage != null) {
                    gameViewModel.leaveGame()
                    lobbyViewModel.leaveLobby()
                }
            }

            EndScreen(
                winnerName = winnerName,
                winnerId = gameState.winnerId ?: "",
                players = gameState.players,
                scores = scores,
                durationSeconds = durationSeconds,
                isHost = isHost,
                hostLeftMessage = hostLeftMessage,
                onPlayAgain = {
                    lobbyViewModel.prepareRematch(gameState.players)
                    gameViewModel.resetGame()
                    // Solo has no lobby to return to - restart the round and go back to the board.
                    if (isSolo) {
                        gameViewModel.initializeAsHost(
                            settings = gameState.settings,
                            players = lobbyViewModel.players.value,
                            localPlayerId = mainViewModel.playerId.value,
                            server = null
                        )
                        navController.navigate(Routes.GAME) { popUpTo(Routes.MAIN_MENU) }
                    } else {
                        navController.navigate(Routes.LOBBY) {
                            popUpTo(Routes.MAIN_MENU)
                        }
                    }
                },
                onBackToMenu = {
                    gameViewModel.resetGame()
                    lobbyViewModel.leaveLobby()
                    navController.navigate(Routes.MAIN_MENU) {
                        popUpTo(Routes.MAIN_MENU) { inclusive = true }
                    }
                }
            )
        }
    }
}
}

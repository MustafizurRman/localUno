# Local UNO Multiplayer ? Complete Technical Deep Dive

> A full explanation of every layer of the app: architecture, networking, game engine, UI, and data flow.

---

## Table of Contents

1. [Tech Stack](#1-tech-stack)
2. [Project Structure](#2-project-structure)
3. [Architecture Pattern: MVVM](#3-architecture-pattern-mvvm)
4. [Data Layer ? Models](#4-data-layer--models)
5. [Game Engine](#5-game-engine)
6. [Card Generation ? The Deck Factory](#6-card-generation--the-deck-factory)
7. [Local Network ? How Devices Talk](#7-local-network--how-devices-talk)
8. [Message System ? Serialization](#8-message-system--serialization)
9. [ViewModels ? The Brain](#9-viewmodels--the-brain)
10. [UI Layer ? Jetpack Compose](#10-ui-layer--jetpack-compose)
11. [Navigation](#11-navigation)
12. [Complete Data Flow ? One Card Play](#12-complete-data-flow--one-card-play)
13. [Key Kotlin / Coroutine Patterns Used](#13-key-kotlin--coroutine-patterns-used)
14. [Things to Study Further](#14-things-to-study-further)

---

## 1. Tech Stack

| Category | Technology | Version | Why |
|---|---|---|---|
| Language | Kotlin | 1.9.0 | Official Android language, concise, null-safe |
| UI Framework | Jetpack Compose | BOM 2023.10.01 | Declarative UI, no XML, reactive |
| UI Components | Material 3 | via BOM | Modern Android design system |
| Architecture | MVVM + StateFlow | ? | Unidirectional data flow, lifecycle-aware |
| Navigation | Navigation Compose | 2.7.7 | Type-safe composable navigation |
| Async | Kotlin Coroutines | 1.7.3 | Structured concurrency for networking |
| Serialization | Gson | 2.10.1 | JSON encode/decode for network messages |
| Service Discovery | Android NSD (mDNS) | OS built-in | Find other players on same Wi-Fi |
| Transport | Raw TCP Sockets | Java stdlib | Low-latency local network communication |
| Persistence | SharedPreferences | Android built-in | Save player name and UUID |
| Build System | Gradle KTS + Version Catalog | AGP 8.5.1 | Kotlin DSL build files |
| Min SDK | Android 10 (API 29) | ? | NSD + Wi-Fi requirements |
| Target SDK | Android 14 (API 34) | ? | Latest stable |

---

## 2. Project Structure

```
app/src/main/java/com/mutsho/localuno/
„ 
„¥„Ÿ„Ÿ MainActivity.kt              © Single Activity entry point
„ 
„¥„Ÿ„Ÿ model/                       © Pure data, no Android dependencies
„    „¥„Ÿ„Ÿ Card.kt                  © Card data class + CardType + CardColor enums
„    „¥„Ÿ„Ÿ Player.kt                © Player data class
„    „¥„Ÿ„Ÿ GameState.kt             © Entire game state snapshot + GamePhase + Direction
„    „¤„Ÿ„Ÿ NetworkMessage.kt        © All message types + PlayerInfo
„ 
„¥„Ÿ„Ÿ engine/                      © Game logic, pure Kotlin, no Android
„    „¥„Ÿ„Ÿ GameEngine.kt            © Authoritative rule engine
„    „¤„Ÿ„Ÿ Deck.kt                  © Card deck factory for all game modes
„ 
„¥„Ÿ„Ÿ network/                     © TCP networking
„    „¥„Ÿ„Ÿ GameServer.kt            © TCP server (host device runs this)
„    „¥„Ÿ„Ÿ GameClient.kt            © TCP client (joining devices run this)
„    „¥„Ÿ„Ÿ NsdHelper.kt             © mDNS: advertise and discover lobbies
„    „¤„Ÿ„Ÿ MessageSerializer.kt    © JSON encode/decode with Gson
„ 
„¥„Ÿ„Ÿ viewmodel/                   © Business logic, connects engine + network + UI
„    „¥„Ÿ„Ÿ MainViewModel.kt         © Player name and ID (SharedPreferences)
„    „¥„Ÿ„Ÿ LobbyViewModel.kt        © Lobby creation, player list, game start
„    „¤„Ÿ„Ÿ GameViewModel.kt         © In-game orchestration, state distribution
„ 
„¤„Ÿ„Ÿ ui/
    „¥„Ÿ„Ÿ navigation/
    „    „¤„Ÿ„Ÿ NavGraph.kt          © All screen routes and transitions
    „¥„Ÿ„Ÿ screens/
    „    „¥„Ÿ„Ÿ MainMenuScreen.kt    © Start screen
    „    „¥„Ÿ„Ÿ ModeSelectScreen.kt  © Choose Classic / No Mercy / Flip
    „    „¥„Ÿ„Ÿ LobbyScreen.kt       © Waiting room
    „    „¥„Ÿ„Ÿ JoinScreen.kt        © Scan and join available lobbies
    „    „¥„Ÿ„Ÿ GameBoardScreen.kt   © The actual game UI
    „    „¥„Ÿ„Ÿ EndScreen.kt         © Results / rankings
    „    „¤„Ÿ„Ÿ ColorPickerDialog.kt © Wild card color selection
    „¥„Ÿ„Ÿ components/
    „    „¥„Ÿ„Ÿ CardView.kt          © Single card composable
    „    „¤„Ÿ„Ÿ PlayerIndicator.kt  © Opponent widget
    „¤„Ÿ„Ÿ theme/
        „¥„Ÿ„Ÿ Color.kt             © All colors
        „¥„Ÿ„Ÿ Theme.kt             © MaterialTheme wrapper
        „¤„Ÿ„Ÿ Type.kt              © Typography
```

---

## 3. Architecture Pattern: MVVM

MVVM = **Model ? View ? ViewModel**

```
„¡„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„¢
„                         UI Layer                              „ 
„    Composables observe StateFlow and call ViewModel methods   „ 
„    GameBoardScreen, LobbyScreen, EndScreen, ...               „ 
„¤„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„¦„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„£
              „   observes (collectAsState)        „  calls
              ¥                                   ¥
„¡„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„¢
„                      ViewModel Layer                          „ 
„    GameViewModel, LobbyViewModel, MainViewModel               „ 
„    Holds StateFlow, orchestrates Engine + Network             „ 
„¤„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„¦„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„£
              „                                    „ 
   reads/writes state                    sends/receives messages
              ¥                                   ¥
„¡„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„¢        „¡„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„¢
„     Engine Layer      „         „       Network Layer          „ 
„    GameEngine.kt      „         „   GameServer / GameClient    „ 
„    (pure game logic)  „         „   NsdHelper                  „ 
„¤„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„£        „¤„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„£
```

**Key principle:** The UI never talks to the network or engine directly. Everything passes through a ViewModel.

### Why MVVM?

- **Testable:** GameEngine has zero Android imports ? pure Kotlin, pure logic.
- **Lifecycle-safe:** ViewModels survive screen rotation.
- **Reactive:** StateFlow automatically pushes updates to the UI when state changes.

---

## 4. Data Layer ? Models

### Card.kt

Every card in the game is represented by this immutable data class:

```kotlin
data class Card(
    val id: Int,           // Unique ID assigned by DeckFactory (0, 1, 2, ...)
    val color: CardColor,  // RED, YELLOW, GREEN, BLUE, WILD
    val type: CardType,    // ZERO, SKIP, WILD_DRAW_SIX, etc.
    val darkColor: CardColor? = null,  // Flip mode dark side
    val darkType: CardType? = null,    // Flip mode dark side type
    val points: Int = calculatePoints(type)  // Scoring value
)
```

**Why `id`?** When you play a card, the server needs to know *exactly which card* you played from your hand (there can be two RED SKIP cards). The `id` uniquely identifies each physical card.

**Points system:**
- Numbers 0?9: face value
- Action cards (Skip, Reverse, +2, +4, etc.): 20 points
- Wild cards: 50 points

### CardType enum

All 44 card types in one enum:

```kotlin
enum class CardType {
    // Classic numbers
    ZERO, ONE, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE,
    // Classic actions
    SKIP, REVERSE, DRAW_TWO, DRAW_FOUR, DISCARD_ALL, SKIP_EVERYONE,
    // Wild
    WILD, WILD_DRAW_SIX, WILD_DRAW_TEN, WILD_REVERSE_DRAW_FOUR, WILD_COLOR_ROULETTE,
    // Flip light side
    FLIP,
    // Flip dark side
    DARK_DRAW_FIVE, DARK_SKIP_EVERYONE, DARK_WILD_DRAW_COLOR, DARK_WILD,
    DARK_REVERSE, DARK_ONE..DARK_NINE, DARK_SKIP, DARK_DRAW_FIVE_NUMBER, DARK_FLIP
}
```

### GameState.kt

The entire state of a game in one immutable snapshot:

```kotlin
data class GameState(
    val phase: GamePhase,           // PLAYING, CHOOSING_COLOR, ROUND_OVER, etc.
    val players: List<Player>,      // All players in seat order
    val currentPlayerIndex: Int,    // Whose turn it is (index into players)
    val direction: Direction,       // CLOCKWISE or COUNTER_CLOCKWISE
    val discardPile: List<Card>,    // Cards played (top = last card)
    val drawPile: List<Card>,       // Cards left to draw
    val currentColor: CardColor,    // Active color (changes on Wild)
    val pendingDrawCount: Int,       // Accumulated draw penalty (stacking)
    val lastDrawCardValue: Int,     // Value of last draw card (for stacking rules)
    val sequenceNumber: Long,       // Monotonically increasing, for ordering updates
    val winnerId: String?,          // Set when someone wins
    val isFlipDarkSide: Boolean,    // Flip mode: which side is active
    val roundStartTime: Long        // Unix ms, for game duration calculation
)
```

**Why immutable?** Every game action returns a *new* `GameState` copy. This means:
- You can always compare old vs. new state
- No race conditions from partial mutations
- Easy to replay or debug

**Computed properties:**
```kotlin
val topCard: Card? get() = discardPile.lastOrNull()
val currentPlayer: Player? get() = players.getOrNull(currentPlayerIndex)
```

---

## 5. Game Engine

`GameEngine.kt` is the heart of the game. It runs **only on the host device** and is the single source of truth.

### How it works

```kotlin
class GameEngine(private val settings: GameSettings) {
    private var state: GameState = GameState(settings = settings)
    private var sequenceCounter: Long = 0
}
```

Every public method takes inputs and returns a new `GameState`:

```kotlin
fun playCard(playerId: String, card: Card, chosenColor: CardColor?): GameState
fun drawCardForPlayer(playerId: String): GameState
fun callUno(playerId: String): GameState
fun challengeDrawFour(challengerId: String): GameState
```

### canPlayCard ? Rule Enforcement

```kotlin
fun canPlayCard(card: Card, playerId: String): Boolean {
    if (state.currentPlayer?.id != playerId) return false
    if (state.phase != GamePhase.PLAYING) return false

    // Stacking: if someone played +4, you must play a card of equal/higher draw value
    if (state.pendingDrawCount > 0) {
        return canStackOnPending(card)
    }
    return isCardPlayable(card)
}

private fun isCardPlayable(card: Card): Boolean {
    val topCard = state.topCard ?: return true
    return when {
        card.isWildCard() -> true                    // Wilds always playable
        card.color == state.currentColor -> true     // Matching color
        card.type == topCard.type -> true            // Matching type
        else -> false
    }
}
```

### applyCardEffect ? Every Card Rule

When a card is played, `applyCardEffect` is called to produce the next state:

```kotlin
private fun applyCardEffect(card: Card, chosenColor: CardColor?, playerIndex: Int): GameState {
    return when (card.type) {
        CardType.SKIP -> {
            // Skip the next ACTIVE player (skips KO'd players automatically)
            val skippedIndex = getNextPlayerFrom(state.currentPlayerIndex)
            val afterSkip = getNextPlayerFrom(skippedIndex)
            state.copy(currentPlayerIndex = afterSkip, sequenceNumber = nextSequence())
        }
        CardType.REVERSE -> {
            val newDirection = state.direction.reversed()
            val activePlayers = state.players.count { it.isConnected }
            if (activePlayers == 2) {
                // With 2 players, Reverse = Skip (official rule)
                state.copy(direction = newDirection, sequenceNumber = nextSequence())
            } else {
                state.copy(direction = newDirection, currentPlayerIndex = getNextPlayerIndexWith(newDirection), ...)
            }
        }
        CardType.DRAW_TWO -> applyDrawCard(2)
        CardType.DRAW_FOUR -> applyDrawCard(4)
        CardType.SKIP_EVERYONE -> state.copy(sequenceNumber = nextSequence()) // Same player again
        CardType.ZERO -> applyZeroPass()             // Everyone passes hand clockwise
        CardType.SEVEN -> {                          // Current player swaps with opponent
            // Auto-swap with player who has most cards
        }
        CardType.WILD_COLOR_ROULETTE -> {
            // Next player draws until a card of the chosen color appears
        }
        // ... etc.
    }
}
```

### getNextPlayerFrom ? Skip KO'd Players

This is critical. During the Mercy Rule, a player with 25+ cards is knocked out (marked `isConnected = false`). The turn-advancement function must skip them:

```kotlin
private fun getNextPlayerFrom(index: Int): Int {
    val step = if (state.direction == Direction.CLOCKWISE) 1 else -1
    var next = index + step
    // Wrap around
    if (next >= state.players.size) next = 0
    if (next < 0) next = state.players.size - 1

    // Skip knocked-out players
    var attempts = state.players.size
    while (!state.players[next].isConnected && attempts > 0) {
        next += step
        if (next >= state.players.size) next = 0
        if (next < 0) next = state.players.size - 1
        attempts--
    }
    // Safety: if everyone is KO'd (shouldn't happen), stay at current
    if (attempts == 0 && !state.players[next].isConnected) return index
    return next
}
```

### Stacking (No Mercy)

```kotlin
private fun applyDrawCard(amount: Int): GameState {
    val nextIndex = getNextPlayerFrom(state.currentPlayerIndex)
    val newPending = state.pendingDrawCount + amount

    val stackingActive = settings.gameMode == GameMode.NO_MERCY || settings.stackingEnabled

    if (!stackingActive) {
        // Immediate: next player draws and loses turn
        val drawn = drawCards(amount)
        // ... add cards to next player's hand, advance turn
    }

    // Stacking: just accumulate and pass turn
    return state.copy(
        currentPlayerIndex = nextIndex,
        pendingDrawCount = newPending,
        sequenceNumber = nextSequence()
    )
}
```

When a player can't stack (no matching or higher draw card), they draw `pendingDrawCount` cards at once via `drawCardForPlayer`.

### Mercy Rule (No Mercy mode only)

```kotlin
private fun checkMercyRule(): GameState {
    for (player in state.players) {
        if (player.hand.size >= 25 && player.isConnected) {
            // Add their cards back to draw pile (shuffled)
            // Mark player as isConnected = false (knocked out)
        }
    }
    // If only 1 active player remains ¨ ROUND_OVER
}
```

---

## 6. Card Generation ? The Deck Factory

`DeckFactory` (a Kotlin `object` = singleton) builds the deck for each game mode.

### How Classic Deck (108 cards) is built

```kotlin
fun createClassicDeck(): MutableList<Card> {
    nextId = 0   // Reset the ID counter
    val cards = mutableListOf<Card>()
    val colors = listOf(RED, YELLOW, GREEN, BLUE)

    for (color in colors) {
        // 1 zero per color = 4 total
        cards.add(Card(id = nextId++, color = color, type = ZERO))

        // 2 of each 1?9 per color = 72 total
        for (type in listOf(ONE..NINE)) {
            cards.add(Card(id = nextId++, color = color, type = type))
            cards.add(Card(id = nextId++, color = color, type = type))
        }

        // 2 each of Skip, Reverse, Draw Two = 24 total
        repeat(2) {
            cards.add(Card(id = nextId++, color = color, type = SKIP))
            cards.add(Card(id = nextId++, color = color, type = REVERSE))
            cards.add(Card(id = nextId++, color = color, type = DRAW_TWO))
        }
    }

    // 4 Wild + 4 Wild Draw Four = 8
    repeat(4) { cards.add(Card(id = nextId++, color = WILD, type = WILD)) }
    repeat(4) { cards.add(Card(id = nextId++, color = WILD, type = DRAW_FOUR)) }

    return cards  // 108 total
}
```

### No Mercy Deck (168 cards)

Adds per color: Draw Four, Discard All, Skip Everyone (more aggressive action cards)
Adds wild: Wild Draw 6 (~8), Wild Draw 10 (~8), Wild Reverse Draw 4 (~8), Wild Color Roulette (~8)

### ID Assignment

The `nextId` counter starts at 0 for every new deck. Each `Card(id = nextId++, ...)` gets a unique sequential integer. This guarantees:
- No two cards in the same deck share an ID
- The network can use `card.id` to precisely identify a played card
- The engine validates `player.hand.none { it.id == card.id }` before playing

### Dealing

```kotlin
// In GameEngine.initializeGame()
val deck = DeckFactory.createDeckForMode(settings.gameMode)
DeckFactory.shuffle(deck)  // calls deck.shuffle() ? random in-place

val updatedPlayers = players.map { player ->
    val hand = mutableListOf<Card>()
    repeat(7) {
        if (deck.isNotEmpty()) hand.add(deck.removeFirst())
    }
    player.copy(hand = hand)
}

// Find first number card for discard pile (action cards can't start)
var firstCard: Card? = null
for (i in drawPile.indices) {
    if (drawPile[i].isNumberCard()) {
        firstCard = drawPile.removeAt(i)
        break
    }
}
```

---

## 7. Local Network ? How Devices Talk

This is the most interesting part. No internet, no server, no Firebase. Devices talk directly on the local Wi-Fi network.

### Two Technologies Used Together

```
Device A (Host)                         Device B (Joiner)
„¡„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„¢         „¡„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„¢
„                              „          „                               „ 
„   NsdHelper.registerService()„          „   NsdHelper.discoverServices()„ 
„   ¨ Advertise "LocalUno-     „          „   ¨ Scan for "_localuno._tcp."„ 
„     MyLobby" on Wi-Fi        „ „Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„   ¨ Find lobby, get host IP   „ 
„                              „    mDNS  „     and port number           „ 
„   GameServer.start()         „          „                               „ 
„   ¨ Open TCP ServerSocket    „          „   GameClient.connect(ip,port) „ 
„     on a random port         „          „   ¨ Open TCP Socket to host   „ 
„                              „ ?„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„                               „ 
„                              „    TCP   „                               „ 
„¤„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„£         „¤„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„Ÿ„£
```

### Step 1: mDNS Service Discovery (NsdHelper)

**mDNS** = Multicast DNS. It lets devices on the same network advertise and find services, like how your phone finds a printer or Chromecast.

```kotlin
// HOST: advertise the lobby
val serviceInfo = NsdServiceInfo().apply {
    serviceName = "LocalUno-MyLobby"   // Unique name on the network
    serviceType = "_localuno._tcp."    // Custom service type
    setPort(port)                       // TCP port the server is listening on
    // Metadata embedded in the advertisement:
    setAttribute("lobby", "MyLobby")
    setAttribute("mode", "NO_MERCY")
    setAttribute("max", "6")
    setAttribute("pin", "0")           // 0 = no PIN required
}
nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
```

```kotlin
// JOINER: scan for lobbies
nsdManager.discoverServices("_localuno._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)

// When found, resolve to get IP + port
nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
    override fun onServiceResolved(info: NsdServiceInfo) {
        val host = info.host.hostAddress   // "192.168.1.5"
        val port = info.port               // e.g. 54321
        // Now connect via TCP
    }
})
```

### Step 2: TCP Socket Communication (GameServer / GameClient)

TCP = Transmission Control Protocol. Reliable, ordered delivery. Each message is a line of JSON.

**Host (GameServer.kt):**
```kotlin
// Open a server socket on any available port
serverSocket = ServerSocket(0)  // OS picks the port
val actualPort = serverSocket.localPort  // e.g. 54321

// Accept incoming connections in a loop
scope.launch {
    while (isActive) {
        val clientSocket = serverSocket.accept()  // Blocks until client connects
        handleNewClient(clientSocket)             // Spawn coroutine per client
    }
}
```

**Per-client handling:**
```kotlin
private fun handleNewClient(socket: Socket) {
    scope.launch {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val writer = PrintWriter(socket.getOutputStream(), true)

        while (isActive && !socket.isClosed) {
            val line = reader.readLine()  // Blocks until full JSON line arrives
            val message = MessageSerializer.deserialize(line)
            _messages.emit(Pair(clientId, message))  // Push to ViewModel via Flow
        }
    }
}
```

**Client (GameClient.kt):**
```kotlin
fun connect(host: String, port: Int): Boolean {
    socket = Socket(host, port)       // Connect to host's IP and port
    writer = PrintWriter(socket.getOutputStream(), true)
    reader = BufferedReader(InputStreamReader(socket.getInputStream()))

    // Read loop in background
    scope.launch {
        while (isActive) {
            val line = reader.readLine() ?: break
            val message = MessageSerializer.deserialize(line)
            _messages.emit(message)   // Push to ViewModel via Flow
        }
    }
}

fun send(message: NetworkMessage) {
    scope.launch {
        val serialized = MessageSerializer.serialize(message)
        writer.print(serialized)   // Send JSON line to server
        writer.flush()
    }
}
```

### Star Topology

The host is the center. Clients never talk to each other ? all messages go through the host:

```
         Client B
            „ 
Client A „Ÿ„Ÿ HOST „Ÿ„Ÿ Client C
            „ 
         Client D
```

When a client plays a card:
1. Client sends `PlayCard` message to host
2. Host's `GameEngine` processes it ¨ new `GameState`
3. Host sends `StateUpdate` to ALL clients (each gets their own version with only their cards visible)

### Why TCP and not UDP or Bluetooth?

- **TCP over Wi-Fi**: Reliable, ordered, no packet loss. Ideal for turn-based game state.
- **Not Bluetooth**: NSD+TCP works on Wi-Fi Direct and home networks simultaneously, supports 2?8 players.
- **Not UDP**: Game state must arrive and in order. UDP is better for real-time games (shooters), not turn-based.

### Client ID Lifecycle

There's a subtle problem: when a socket connects, we only have its IP address as ID. But we need the player's UUID (set in MainViewModel from SharedPreferences):

```kotlin
private fun handleNewClient(socket: Socket) {
    val clientId = socket.remoteSocketAddress.toString()  // e.g. "/192.168.1.8:58432"
    var finalPlayerId = clientId  // Starts as socket address

    // When client sends JoinRequest, swap to their UUID
    if (message is NetworkMessage.JoinRequest) {
        finalPlayerId = message.playerId   // e.g. "550e8400-e29b-41d4-a716-446655440000"
        clients.remove(clientId)            // Remove old socket-address key
        clients[message.playerId] = connection  // Store under UUID
    }

    // On disconnect, emit UUID (not socket address)
    _connectionEvents.emit(Disconnected(finalPlayerId))
}
```

---

## 8. Message System ? Serialization

All messages follow the **wrapper pattern**:

```
{ "type": "PLAY_CARD", "payload": "{\"playerId\":\"abc\",\"card\":{...}}" }
```

The outer JSON has `type` (string) and `payload` (the actual message, also JSON-encoded as a string).

### Why nested JSON?

Gson can't deserialize a sealed class polymorphically without help. The wrapper tells us which subclass to deserialize the payload into:

```kotlin
fun deserialize(json: String): NetworkMessage? {
    val wrapper = gson.fromJson(json, MessageWrapper::class.java)
    return when (wrapper.type) {
        "PLAY_CARD"    -> gson.fromJson(wrapper.payload, NetworkMessage.PlayCard::class.java)
        "STATE_UPDATE" -> gson.fromJson(wrapper.payload, NetworkMessage.StateUpdate::class.java)
        "DRAW_CARD"    -> gson.fromJson(wrapper.payload, NetworkMessage.DrawCard::class.java)
        // ... 15+ message types
        else -> null  // Unknown message = ignore gracefully
    }
}
```

### All 16 Message Types

| Message | Direction | Purpose |
|---|---|---|
| `JoinRequest` | Client ¨ Host | "I want to join, here's my name/ID/PIN" |
| `JoinResponse` | Host ¨ Client | "Accepted" or "Rejected (wrong PIN)" |
| `LobbyUpdate` | Host ¨ All | Updated player list in the lobby |
| `GameStart` | Host ¨ All | Game is starting |
| `StateUpdate` | Host ¨ Client | Full game state (personalized per player) |
| `PlayCard` | Client ¨ Host | "I played this card, with this color choice" |
| `DrawCard` | Client ¨ Host | "I'm drawing a card" |
| `CallUno` | Client ¨ Host | "UNO!" |
| `ChallengeDrawFour` | Client ¨ Host | "I challenge your Wild +4" |
| `UnoNotCalled` | Client ¨ Host | "That player didn't call UNO" |
| `EmojiReaction` | Client ¨ Host ¨ All | Send a reaction ("lol", "wow") |
| `PlayerDisconnected` | Host ¨ All | A player dropped |
| `PlayerReconnected` | Host ¨ All | A player came back |
| `GameOver` | Host ¨ All | Game ended with scores |
| `ResyncRequest` | Client ¨ Host | "I missed a state update, send again" |
| `PlayerLeft` | Client ¨ Host | "I'm leaving voluntarily" |

### sequenceNumber

Every message has a `sequenceNumber: Long`. The host increments a counter (`++sequenceCounter`) every time the game state changes. Clients can detect missed updates by comparing received sequence numbers.

---

## 9. ViewModels ? The Brain

### MainViewModel

The simplest ViewModel. Persists player identity using `SharedPreferences`:

```kotlin
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("localuno_prefs", MODE_PRIVATE)

    // Player name: loaded from prefs or random "Player1234"
    val playerName: StateFlow<String>

    // Player UUID: loaded from prefs or generated once with UUID.randomUUID()
    val playerId: StateFlow<String>

    // Avatar color index (0-7): persisted across sessions
    val avatarColor: StateFlow<Int>
}
```

The UUID is generated once and stored forever ? this is how the app recognizes the same player even after reconnecting.

### LobbyViewModel

Manages pre-game: creating/joining a lobby, showing the player list:

```kotlin
// HOST flow:
createLobby(settings, playerId, playerName, avatarColor)
  ¨ starts GameServer (TCP)
  ¨ starts NSD advertisement
  ¨ adds self as first player
  ¨ listens for JoinRequest messages
  ¨ on each join: validates PIN, adds to _players, sends JoinResponse, broadcasts LobbyUpdate

// JOIN flow:
startScanning()
  ¨ starts NSD discovery (auto-stops after 12 seconds)

joinLobby(lobby, pin, playerId, playerName)
  ¨ connects GameClient to host IP:port
  ¨ sends JoinRequest
  ¨ listens for JoinResponse, LobbyUpdate, GameStart
```

### GameViewModel

The most complex ViewModel. Orchestrates the entire game:

```kotlin
// HOST initialization:
initializeAsHost(settings, players, localPlayerId, server)
  ¨ creates GameEngine
  ¨ calls engine.initializeGame(players)
  ¨ calls broadcastStateToAll()
  ¨ starts listening to server.messages flow

// CLIENT initialization:
initializeAsClient(localPlayerId, client, players)
  ¨ pre-populates _gameState with player list
  ¨ starts listening to client.messages flow

// Playing a card (HOST):
fun playCard(card: Card) {
    if (card.isWildCard()) {
        pendingCard = card
        _showColorPicker.value = true   // Show UI, wait for color choice
    } else {
        executePlayCard(localPlayerId, card, null)
    }
}

fun executePlayCard(playerId, card, chosenColor) {
    val newState = gameEngine.playCard(playerId, card, chosenColor)
    _gameState.value = newState
    updatePlayableCards()
    broadcastStateToAll()
}
```

### StateUpdate ? Personalized Per Player

Each player receives a different `StateUpdate`. The host sees the full draw pile; clients see a count. Each client only sees their own hand:

```kotlin
private fun broadcastStateToAll() {
    state.players.forEach { player ->
        val update = NetworkMessage.StateUpdate(
            playerHand = player.hand,   // Their own cards only!
            opponentCardCounts = state.players
                .filter { it.id != player.id }
                .associate { it.id to it.cardCount },  // Others: just count
            // ... everything else is shared
        )
        if (player.id != localPlayerId) {
            server.sendToClient(player.id, update)
        }
        // Host updates its own UI from _gameState directly
    }
}
```

### playableCardIds ? Computed Highlights

```kotlin
// HOST: engine does the work
private fun updatePlayableCards() {
    val playable = gameEngine.getPlayableCards(localPlayerId)
    _playableCardIds.value = playable.map { it.id }.toSet()
}

// CLIENT: recompute locally from state snapshot
private fun computePlayableCardsForClient(state: GameState) {
    val ids = localPlayer.hand.filter { card ->
        when {
            state.pendingDrawCount > 0 ->
                card.isDrawCard() && Card.drawValue(card.type) >= state.lastDrawCardValue
            card.isWildCard() -> true
            card.color == state.currentColor -> true
            card.type == topCard.type -> true
            else -> false
        }
    }.map { it.id }.toSet()
    _playableCardIds.value = ids
}
```

The set of IDs is passed to `GameBoardScreen`, which uses it to visually highlight playable cards.

---

## 10. UI Layer ? Jetpack Compose

Compose is a **declarative** UI framework. You describe *what* the UI should look like for a given state, and Compose re-renders only the changed parts.

### How StateFlow + Compose Works

```kotlin
// In NavGraph:
val gameState by gameViewModel.gameState.collectAsState()

// gameState is now a regular Kotlin value in composition.
// When gameViewModel._gameState.value changes, Compose re-runs
// only the parts of GameBoardScreen that read it.
```

### CardView.kt ? Rendering a Card

```kotlin
@Composable
fun UnoCardView(card: Card, isPlayable: Boolean, onClick: () -> Unit = {}) {
    val cardColor = when (card.color) {
        CardColor.RED    -> UnoRed      // Color(0xFFED1C24)
        CardColor.YELLOW -> UnoYellow
        CardColor.GREEN  -> UnoGreen
        CardColor.BLUE   -> UnoBlue
        CardColor.WILD   -> UnoBlack
    }

    Box(
        modifier = Modifier
            .width(80.dp).height(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cardColor)
            .border(if (isSelected) 3.dp else 1.dp, ...)
            .then(if (isPlayable) Modifier.clickable { onClick() } else Modifier)
            // Dim non-playable cards
            .then(if (!isPlayable) Modifier.background(Color.Black.copy(alpha = 0.3f)) else Modifier)
    ) {
        Text(text = getCardDisplayText(card.type), ...)   // Center label
        Text(text = ..., modifier = Modifier.align(TopStart))   // Corner label
        Text(text = ..., modifier = Modifier.align(BottomEnd))  // Corner label
    }
}
```

### ColorPickerDialog ? No Accidental Dismiss

```kotlin
@Composable
fun ColorPickerDialog(onColorChosen: (CardColor) -> Unit) {
    AlertDialog(
        onDismissRequest = { /* intentionally empty ? cannot close without picking */ },
        ...
    ) {
        // 4 colored circles, one per color
        listOf(RED, YELLOW, GREEN, BLUE).forEach { color ->
            Box(
                modifier = Modifier
                    .size(80.dp).clip(CircleShape)
                    .background(colorOf(color))
                    .clickable { onColorChosen(color) }
            )
        }
    }
}
```

---

## 11. Navigation

All routes are defined in `NavGraph.kt` using Navigation Compose:

```
MAIN_MENU
    „¥„Ÿ„Ÿ MODE_SELECT ¨ LOBBY (host flow)
    „¤„Ÿ„Ÿ JOIN        ¨ LOBBY (join flow)
                         „¤„Ÿ„Ÿ GAME
                                „¤„Ÿ„Ÿ END
                                       „¤„Ÿ„Ÿ MAIN_MENU (back to start)
```

```kotlin
NavHost(navController, startDestination = Routes.MAIN_MENU) {
    composable(Routes.MAIN_MENU) { MainMenuScreen(...) }
    composable(Routes.MODE_SELECT) { ModeSelectScreen(...) }
    composable(Routes.LOBBY) {
        // When game starts:
        LaunchedEffect(gameStarted) {
            if (gameStarted) {
                if (isHost) gameViewModel.initializeAsHost(...)
                else gameViewModel.initializeAsClient(...)
                navController.navigate(Routes.GAME) {
                    popUpTo(Routes.MAIN_MENU)   // Clear back stack up to menu
                }
            }
        }
        LobbyScreen(...)
    }
    composable(Routes.GAME) {
        // When game over:
        LaunchedEffect(isGameOver) {
            if (isGameOver) navController.navigate(Routes.END) {
                popUpTo(Routes.GAME) { inclusive = true }
            }
        }
        GameBoardScreen(...)
    }
    composable(Routes.END) { EndScreen(...) }
}
```

**`popUpTo`** removes intermediate screens from the back stack so pressing Back doesn't return to a finished game.

---

## 12. Complete Data Flow ? One Card Play

Here's every step that happens when a **client** plays a card:

```
1. Player taps a card in GameBoardScreen
   ¨ onClick { if (isPlayable) onPlayCard(card) }

2. NavGraph callback: onPlayCard = { gameViewModel.playCard(it) }

3. GameViewModel.playCard(card):
   if (card.isWildCard()) {
       pendingCard = card
       _showColorPicker.value = true   © UI shows ColorPickerDialog
   } else {
       gameClient.send(PlayCard(playerId, card))   © TCP ¨ host
   }

4. On host's GameServer: reads line from socket
   _messages.emit(Pair(playerId, PlayCard(...)))

5. GameViewModel.handlePlayerMessage():
   is PlayCard ¨ executePlayCard(message.playerId, message.card, message.chosenColor)

6. GameEngine.playCard():
   - Validates card is in player's hand
   - Removes card from hand
   - Adds to discard pile
   - Determines new currentColor
   - Calls applyCardEffect() ¨ new GameState

7. _gameState.value = newState   © Host UI updates immediately

8. broadcastStateToAll():
   For each player, send StateUpdate with:
   - Their hand only
   - Opponent card counts
   - currentColor, pendingDraw, phase, sequenceNumber, etc.

9. Client's GameClient receives line ¨ deserializes StateUpdate

10. GameViewModel.handleServerMessage():
    Updates _gameState.value
    Calls computePlayableCardsForClient()

11. Compose re-renders GameBoardScreen:
    - Opponent chip shows updated card count
    - Hand shows new cards highlighted by playableCardIds
    - Discard pile shows the played card
    - Turn indicator shows next player's name
```

---

## 13. Key Kotlin / Coroutine Patterns Used

### StateFlow vs SharedFlow

| | StateFlow | SharedFlow |
|---|---|---|
| Has current value? | Yes (`.value`) | No |
| New collectors get? | Latest value immediately | Nothing (unless replay > 0) |
| Used for | Game state, player name | Events (UNO announcement, disconnect) |

```kotlin
// StateFlow: UI always gets the current game state on collect
private val _gameState = MutableStateFlow(GameState())

// SharedFlow: one-shot events (won't replay old announcements)
private val _unoAnnouncement = MutableSharedFlow<String>(extraBufferCapacity = 1)
```

### SupervisorJob

```kotlin
private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
```

`SupervisorJob` means if one child coroutine (one client connection) crashes, it doesn't cancel the parent scope or other client coroutines. Essential for a server handling multiple clients.

### ConcurrentHashMap

```kotlin
private val clients = ConcurrentHashMap<String, ClientConnection>()
```

Multiple coroutines read/write the client map simultaneously (one per connected client). `ConcurrentHashMap` is thread-safe for this pattern.

### Flow.collect in ViewModels

```kotlin
viewModelScope.launch(Dispatchers.IO) {
    server.messages.collect { (playerId, message) ->
        handlePlayerMessage(playerId, message)
    }
}
```

`viewModelScope` is automatically cancelled when the ViewModel is destroyed (app closed or Activity recreated). This prevents coroutine leaks.

### data class copy()

```kotlin
// Instead of mutating state:
state.currentPlayerIndex = nextIndex   // ? Can't ? it's val

// Create a new copy with only the changed fields:
state.copy(currentPlayerIndex = nextIndex, sequenceNumber = nextSequence())  // ?
```

Every field not mentioned in `copy()` keeps its original value.

### sealed class for NetworkMessage

```kotlin
sealed class NetworkMessage {
    data class PlayCard(...) : NetworkMessage()
    data class DrawCard(...) : NetworkMessage()
    // ...
}
```

`sealed class` means all subclasses are defined in the same file. When you `when (message)`, the compiler forces you to handle every case ? no missing message types at compile time.

---

## 14. Things to Study Further

Based on what this project uses, here are the key concepts to deepen your skills:

### Android / Kotlin
- **Kotlin Coroutines** ? `launch`, `async`, `Flow`, `StateFlow`, `SharedFlow`, `SupervisorJob`, `Dispatchers`
- **Jetpack Compose** ? Recomposition, `remember`, `LaunchedEffect`, `SideEffect`, `derivedStateOf`
- **ViewModel + Lifecycle** ? `viewModelScope`, `AndroidViewModel`, lifecycle awareness
- **StateFlow** ? Hot observable, always has a value, Compose integration

### Networking
- **Java TCP Sockets** ? `ServerSocket`, `Socket`, `BufferedReader`, `PrintWriter`
- **Android NSD** ? `NsdManager`, `NsdServiceInfo`, mDNS/DNS-SD protocol
- **Coroutine-based I/O** ? Switching between `Dispatchers.IO` and `Dispatchers.Main`

### Architecture
- **MVVM pattern** ? Separation of concerns, testability
- **Unidirectional Data Flow** ? State flows down, events flow up
- **Immutable state** ? Why `copy()` is preferred over mutation
- **Observer pattern** ? How `StateFlow` implements it

### Design Patterns in This Project
- **Factory** ? `DeckFactory.createDeckForMode()`
- **Observer** ? `StateFlow` + `collectAsState()`
- **Command** ? Each `NetworkMessage` subclass is a command
- **Singleton** ? `DeckFactory` (Kotlin `object`)
- **Wrapper / Envelope** ? `MessageWrapper` for polymorphic serialization

---

*This document covers the full technical architecture of Local UNO Multiplayer as of June 2026.*

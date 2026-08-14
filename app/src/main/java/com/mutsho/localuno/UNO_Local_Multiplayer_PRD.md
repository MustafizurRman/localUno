# UNO Local Multiplayer — Product Requirements Document

**Version:** 1.0 Draft  
**Platform:** iOS & Android  
**Players:** 4–8 (local)  
**Connection:** Wi-Fi LAN / Bluetooth  
**Status:** Pre-development

---

## Table of Contents

1. [Overview & Goals](#1-overview--goals)
2. [Game Modes](#2-game-modes)
3. [Core User Flows](#3-core-user-flows)
4. [Screen Inventory](#4-screen-inventory)
5. [Networking Architecture](#5-networking-architecture)
6. [Functional Requirements](#6-functional-requirements)
7. [Non-Functional Requirements](#7-non-functional-requirements)
8. [Recommended Tech Stack](#8-recommended-tech-stack)
9. [Development Roadmap](#9-development-roadmap)
10. [Risks & Mitigations](#10-risks--mitigations)
11. [Open Questions](#11-open-questions)

---

## 1. Overview & Goals

### Problem Statement

There is no great mobile UNO experience for local multiplayer that works without internet access or external accounts. Friends in the same room — on a flight, camping, or anywhere with poor connectivity — have no good digital option.

### Core Vision

One person opens the app, creates a lobby, and friends nearby join in seconds — exactly like Mini Militia. No logins, no internet, no friction. Pick a game mode and deal the cards.

### Target Users

Friend groups aged 10–35 who want to play card games locally without relying on internet connectivity or carrying a physical deck.

### Key Constraint

Fully offline — no server, no cloud. All communication is peer-to-peer over local Wi-Fi or Bluetooth. All devices must be in physical proximity.

### Success Metric

A full 8-player game completes with zero desync or disconnect errors in a standard Wi-Fi environment.

---

## 2. Game Modes

### 2.1 Classic UNO

The standard Mattel ruleset. First player to empty their hand wins.

**Deck:** Standard 108-card deck (19 cards per colour × 4, plus action and wild cards)

**Cards included:**
- Draw Two
- Skip
- Reverse
- Wild
- Wild Draw Four

**Rules:**
- Players must call "UNO" when they have one card remaining, or draw 2 as penalty
- Stacking rule: optional host toggle (house rule)
- Jump-in rule: optional host toggle
- ~~Challenge Draw Four: player can challenge legality of a Wild Draw Four play~~ **REMOVED** - cut deliberately; the design system (HANDOFF_MENUS.md, CustomTable.dc.html) defines exactly four house rules and no challenge, and the feature shipped always-on with no toggle on any screen.

---

### 2.2 UNO No Mercy

The official 2023 variant. Draw cards stack indefinitely. Chaos ensues.

**Special cards:**
- Wild Draw Colour — opponent chooses the colour the affected player must draw until declared
- Skip Everyone — entire table is skipped, current player goes again

**Rule changes from Classic:**
- Draw Two and Wild Draw Four cards can be stacked by the next player in sequence
- Stacking continues until a player cannot stack — they draw the full accumulated total
- No blocking plays — you take the accumulated draw or add to it
- No hand limit — players can accumulate 20+ cards

---

### 2.3 UNO Flip

Two-sided deck. A Flip card toggles the game between the light side (standard play) and the dark side (brutal penalties).

**Light side** — standard Classic rules apply.

**Dark side cards:**
- Draw Five — next player draws five cards
- Skip Everyone — entire table is skipped
- Wild Draw Colour — next player draws cards until the declared colour appears in the draw pile
- Dark Wild — standard wild with dark-side stacking

**Rule changes:**
- The Flip card is a light-side card that triggers the switch to the dark-side deck
- A dark-side Flip card switches back to the light side
- UI displays a full-screen flip animation when the deck switches
- Score values differ between sides (dark-side cards carry higher point values)

---

### 2.4 Custom Rules (v1.1 — Future)

Host selects from a list of toggleable house rules to create a custom variant.

**Toggleable rules:**
- Card stacking (Draw Two / Draw Four)
- Jump-in (any player can play an identical card out of turn)
- 7-0 swap (playing a 7 forces a hand swap with another player; 0 rotates all hands)
- Progressive draw accumulation
- ~~Challenge Draw Four~~ (removed - see above)
- Score-based multi-round sessions (first to 500 points wins the match)

---

## 3. Core User Flows

### 3.1 Host Flow

```
Open app → Tap "Create Game" → Select game mode → Set player limit (4–8)
→ Set optional PIN → Lobby activates & advertises → Wait for players to join
→ Tap "Start Game" (enabled at ≥ 4 players)
```

### 3.2 Player Join Flow

```
Open app → Tap "Join Game" → Auto-scan for nearby lobbies (≤ 5 seconds)
→ Lobby list appears → Tap lobby name → Enter PIN if prompted
→ Set/confirm name and avatar → Enter lobby waiting room → Wait for host to start
```

### 3.3 Gameplay Loop

```
View hand → Select a valid card to play (or tap draw pile)
→ Host validates move → Card effect applied (skip, draw, colour change, etc.)
→ If 1 card remains: tap "UNO!" before next player acts or draw 2 penalty
→ Turn passes clockwise (or counter-clockwise if reversed)
→ First player to empty hand triggers end-of-round scoring → Victory screen
```

### 3.4 Disconnect / Reconnect Flow

```
Player disconnects → All players see "[Name] disconnected" notification
→ Game pauses with 30-second countdown → Disconnected player sees rejoin prompt
→ Rejoins within 30s: game resumes from current state
→ 30s expires: host can boot player or extend pause → game continues without them
```

---

## 4. Screen Inventory

### 4.1 Main Menu

First screen after app open. Two primary CTAs and access to player profile and settings.

| Element | Notes |
|---|---|
| App logo + name | Centred, prominent |
| "Create Game" button | Primary CTA |
| "Join Game" button | Secondary CTA |
| Player name display | Tap to edit inline |
| Settings icon | Top-right corner |

---

### 4.2 Mode Select

Shown to host after tapping Create Game. Displays all available modes with descriptions.

| Element | Notes |
|---|---|
| Classic UNO mode card | Tap to select |
| No Mercy mode card | Tap to select |
| Flip mode card | Tap to select |
| Player limit slider (4–8) | Below mode cards |
| Optional lobby name field | Defaults to host name's lobby |
| PIN toggle + input | Off by default |
| "Create Lobby" button | Confirms and starts advertising |

---

### 4.3 Lobby (Waiting Room)

Live-updating waiting room. Host sees Start Game; joining players see a waiting indicator.

| Element | Notes |
|---|---|
| Lobby name + mode badge | Top header |
| Player slots (up to 8 rows) | Fills as players join |
| Player avatar + name per row | Shows "Waiting…" for empty slots |
| "Start Game" button (host only) | Enabled when ≥ 4 players present |
| "Leave" button | Available to all players |
| Connection type indicator | Wi-Fi or Bluetooth icon |
| Player count display | e.g. "4 / 6 players" |

---

### 4.4 Join Screen

Auto-scans for nearby lobbies. No IP address required from the player.

| Element | Notes |
|---|---|
| Animated scanning indicator | Starts automatically on screen open |
| Lobby list | Name, mode, player count (e.g. "3/6") |
| Lock icon for PIN-protected lobbies | Shows PIN prompt on tap |
| "Refresh" button | Manual rescan trigger |
| Connection type toggle | Switch between Wi-Fi and Bluetooth scan |
| Empty state | "No lobbies found nearby" with tips |

---

### 4.5 Game Board

Main gameplay screen. Player's hand at bottom; opponent indicators around the top arc.

| Element | Notes |
|---|---|
| Discard pile (centre) | Shows top card with colour fill |
| Draw pile | Tappable to draw a card |
| Player hand (bottom strip) | Horizontally scrollable row of cards |
| Opponent indicators (top) | Name + card count per player |
| Current player highlight | Glowing ring or arrow indicator |
| "UNO!" button | Appears when player has exactly 1 card |
| Emoji reaction strip | Quick-tap row: 👀 😱 🔥 💀 |
| Direction indicator | Clockwise / counter-clockwise arrow |
| Current colour indicator | Pill showing active colour (for Wild plays) |

---

### 4.6 Wild Colour Picker

Modal overlay shown when a Wild or Wild Draw card is played.

| Element | Notes |
|---|---|
| Semi-transparent overlay | Blocks board interaction |
| Red / Yellow / Green / Blue buttons | Large tap targets, full colour fill |
| Instruction text | "Choose a colour" |
| No cancel option | Must pick a colour to continue |

---

### 4.7 End Screen

Shown when the round ends. Displays rankings, stats, and options to continue.

| Element | Notes |
|---|---|
| Winner announcement | Large, animated celebration |
| Player rankings (1st to last) | With remaining card counts |
| "Play Again" button (host only) | Same mode and players, new game |
| "Back to Menu" button | Returns all players to main menu |
| Round duration stat | e.g. "Game lasted 12 minutes" |

---

## 5. Networking Architecture

### 5.1 Topology: Host-as-Server (Star)

The lobby creator acts as the authoritative game server. All player devices connect directly to the host device. The host holds the canonical game state and broadcasts personalised state snapshots to each player (players only see their own cards, not others'). This mirrors Mini Militia's architecture and avoids the complexity of full peer-to-peer consensus.

### 5.2 Local Wi-Fi (Primary Transport)

- Lobby discovery via mDNS (Bonjour on iOS / Avahi on Android) — players see lobbies by display name, not IP address
- UDP multicast for initial lobby advertisement
- TCP sockets for all game state messages (reliable, ordered delivery)
- All devices must be on the same subnet
- Host listens on a fixed local port; players connect by resolving the mDNS service

### 5.3 Bluetooth (Fallback Transport)

- BLE for lobby discovery — host advertises as a BLE peripheral
- RFCOMM / Bluetooth Classic for data transport once connected
- Fallback activates automatically when no shared Wi-Fi network is detected
- Maximum 7 active connections per Bluetooth piconet — lobby capped at 7 players in BT mode
- Players prompted to enable Bluetooth if required and not already on

### 5.4 State Sync & Integrity

- Game state is authoritative on the host only
- Each player receives a personalised snapshot: their own cards, public pile state, opponent card counts, turn order
- All moves are sent to the host for validation before any state change is applied
- Messages carry monotonically increasing sequence numbers
- If a client detects a sequence gap, it sends a full-state resync request
- On player disconnect: game pauses for 30 seconds, host decides to boot or wait
- If host disconnects: game ends immediately for all players with a clear error message (host migration deferred to v1.1)

---

## 6. Functional Requirements

| ID | Requirement | Priority |
|---|---|---|
| FR-01 | Host can create a lobby, select game mode, set player limit (4–8), add an optional name, and optionally lock with a 4-digit PIN | P1 |
| FR-02 | Players joining see all nearby lobbies auto-listed within 5 seconds of tapping Join, with no manual IP entry required | P1 |
| FR-03 | Player name and avatar colour are stored locally per device; no account or login required | P1 |
| FR-04 | Classic UNO ruleset is fully implemented, including stacking toggle (jump-in and Challenge Draw Four both removed - see Out of scope) | P1 |
| FR-05 | UNO No Mercy variant is fully implemented, including infinite stacking, Skip Everyone, and Wild Draw Colour | P1 |
| FR-06 | UNO Flip variant is fully implemented, including two-sided deck logic, flip animation, and dark-side card effects | P1 |
| FR-07 | UNO call button appears when a player has exactly one card remaining; missed calls result in a 2-card draw penalty enforced by the host | P1 |
| FR-08 | A disconnected player has 30 seconds to rejoin; all other players see a pause countdown; host can boot the player early | P2 |
| FR-09 | Players can send quick emoji reactions (👀 😱 🔥 💀) visible to all players in the current game; no text chat required in v1 | P2 |
| FR-10 | A local game history log (winner, mode, duration) is stored on the host device only, viewable from the settings screen | P3 |

---

## 7. Non-Functional Requirements

### Performance

- Card play round-trip latency: under 200ms on a standard Wi-Fi LAN
- UI frame rate: 60fps minimum on supported devices
- App launch to lobby scan visible: under 3 seconds
- Maximum concurrent connections: 8 devices

### Reliability

- Zero state desync under normal LAN conditions (no packet loss)
- Game ends gracefully with a visible error message if the host device disconnects
- Draw pile reshuffles from discard pile automatically when exhausted

### Compatibility

- iOS 15 and above
- Android 10 (API level 29) and above
- Cross-platform play between iOS and Android devices in the same lobby is a hard requirement
- Minimum device: iPhone SE 2nd gen equivalent; mid-range Android with ≥ 2GB RAM

### Security & Privacy

- Lobby PIN is hashed locally; never transmitted in plaintext
- No data ever leaves the local network
- No analytics, telemetry, crash reporting, or PII collected in v1
- Private by design: the app functions fully without any network connectivity to the internet

### Accessibility

- All interactive elements meet a minimum 44×44pt tap target size
- Colour-blind support: card colours supplemented with distinct shape/pattern indicators
- Dynamic Type support on iOS; system font scaling on Android

---

## 8. Recommended Tech Stack

| Layer | Choice | Rationale |
|---|---|---|
| Framework | React Native (Expo managed → bare workflow) | Single codebase for iOS and Android; rich ecosystem for networking and BT modules |
| Wi-Fi networking | `react-native-tcp-socket` + `react-native-zeroconf` | TCP sockets for reliable transport; Zeroconf for mDNS lobby discovery |
| Bluetooth | `react-native-ble-plx` (discovery) + `react-native-bluetooth-classic` (data) | BLE for scanning, RFCOMM for data transport |
| State management | Zustand | Lightweight, minimal boilerplate; game state serialised as signed JSON per message |
| Animation | React Native Reanimated 3 + Lottie | Card deal/play/draw animations; Lottie for celebration effects |
| Local storage | AsyncStorage | Player profile persistence and game history log |
| Navigation | React Navigation v6 | Stack + modal navigators for game screens |
| Audio | `expo-av` | Sound effects for card plays, UNO call, win fanfare |

---

## 9. Development Roadmap

### Phase 1 — Foundation (Weeks 1–4)

- Project scaffolding and architecture decisions
- Card deck engine: full card type definitions for all three variants
- Classic UNO game logic: shuffle, deal, turn order, card validation, draw mechanics
- Local Wi-Fi lobby creation and mDNS advertisement
- Single-device playthrough (host-only testing mode)
- Minimal functional UI: hand display, discard pile, draw pile, turn indicator

### Phase 2 — Multiplayer (Weeks 5–8)

- Multi-device state sync over TCP sockets
- Player join, leave, and disconnect handling
- Host-side move validation and authoritative state broadcast
- UNO call mechanics and penalty enforcement
- Bluetooth fallback transport (discovery + data)
- No Mercy variant rules and card effects
- Cross-platform iOS ↔ Android integration testing

### Phase 3 — Polish (Weeks 9–11)

- UNO Flip variant: deck logic, flip animation, dark-side cards
- Card animations: deal, play, draw, hand sort
- Emoji reaction system
- Victory screen and end-of-game stats
- Sound effects and optional background music
- PIN-locked lobbies
- Reconnection flow (30-second pause and rejoin)

### Phase 4 — Release (Weeks 12–14)

- Stress testing with 8 simultaneous devices (Wi-Fi and Bluetooth)
- Edge case coverage: host crash, network drop mid-game, full lobby, reshuffled deck, instant UNO
- App Store and Google Play submission preparation (metadata, screenshots, privacy policy)
- Closed beta with 10–20 target users
- Bug fix cycle based on beta feedback

---

## 10. Risks & Mitigations

| Risk | Severity | Likelihood | Mitigation |
|---|---|---|---|
| Bluetooth range and reliability — BT Classic background drops on Android 11+ | High | Medium | Position Bluetooth as explicit fallback; recommend Wi-Fi for 5+ player games; keep BT lobby cap at 7 |
| Cross-platform socket differences — iOS restricts background and peer networking | Medium | High | Test on physical iOS devices from Week 5; use foreground-only gameplay assumption; do not rely on background socket keepalive |
| State desync on packet loss — UDP drops cause diverged game states | High | Medium | Use TCP (not UDP) for all game state messages; implement sequence-number-based full-state resync on any gap detected |
| IP / trademark exposure — UNO is a registered Mattel trademark | High | Low | Do not use "UNO" as the app name; use a descriptive name (e.g. "Card Party: Local Multiplayer"); implement original card artwork; obtain legal review before store submission |
| 8-player Bluetooth piconet limit — Bluetooth Classic limits to 7 active connections | Medium | Medium | Cap Bluetooth lobbies at 7 players; surface this limit clearly in the UI when the host selects Bluetooth mode |
| iOS App Store rejection — networking or Bluetooth permissions may require justification | Medium | Low | Draft clear permission usage descriptions; reference explicit user benefit in review notes |

---

## 11. Open Questions

**Host migration in v1.1?**  
If the host leaves mid-game, should a new host be elected automatically (complex), or should the game end with a message to all players (simple)? Recommendation: end the game in v1 with a clear message; implement migration in v1.1.

**Monetisation model?**  
Options: free with no ads (trust-building), paid upfront (e.g. $1.99), or free with cosmetic IAPs (card backs, avatar frames). Recommendation: paid-once or free with cosmetic IAPs to avoid friction in group-play discovery.

**Spectator mode?**  
Should a 9th device be able to observe an active game without playing? Useful for onlookers but adds networking and UI scope. Defer to v1.1 unless design time allows.

**Score tracking across rounds?**  
Classic UNO uses a points system across multiple rounds (first player to 500 points wins the match). Should the app track this, or just show per-round winners? Recommendation: per-round winners in v1; multi-round sessions as an optional setting in v1.1.

**Offline name persistence across devices?**  
Names are stored locally per device. Should the app offer a QR-code or short code for players to share a profile across devices? Low priority for v1 but relevant for returning player groups.

---

*Document maintained by product team. Last updated: June 2026.*

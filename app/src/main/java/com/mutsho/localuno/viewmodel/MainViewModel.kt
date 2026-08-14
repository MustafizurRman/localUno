package com.mutsho.localuno.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import com.mutsho.localuno.ui.components.BoardSkin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences = application.getSharedPreferences("localuno_prefs", Context.MODE_PRIVATE)

    private val _playerName = MutableStateFlow(loadPlayerName())
    val playerName: StateFlow<String> = _playerName

    private val _playerId = MutableStateFlow(loadPlayerId())
    val playerId: StateFlow<String> = _playerId

    private val _avatarColor = MutableStateFlow(loadAvatarColor())
    val avatarColor: StateFlow<Int> = _avatarColor

    private val _boardSkin = MutableStateFlow(loadBoardSkin())
    val boardSkin: StateFlow<BoardSkin> = _boardSkin

    // Real, persisted counts for the Main Menu profile row (HANDOFF_MENUS.md §1.8) - the design
    // mockup hardcodes "18 games · 11 wins" as placeholder copy; showing that literally in a real
    // build would be a lie about the player's actual history, so this tracks it for real instead.
    private val _gamesPlayed = MutableStateFlow(prefs.getInt("games_played", 0))
    val gamesPlayed: StateFlow<Int> = _gamesPlayed

    private val _gamesWon = MutableStateFlow(prefs.getInt("games_won", 0))
    val gamesWon: StateFlow<Int> = _gamesWon

    // Board preferences from the skin sheet's PREFERENCES block. The design persists these
    // (localStorage 'localuno.<key>'); they were previously plain `remember` state inside
    // UnoBoardScreen, so every one of them reset on rotation, on leaving the board, and between
    // rounds. Board skin already persisted here, so this just follows the same pattern.
    private val _showCardSymbols = MutableStateFlow(prefs.getBoolean("pref_card_symbols", false))
    val showCardSymbols: StateFlow<Boolean> = _showCardSymbols

    private val _hapticsEnabled = MutableStateFlow(prefs.getBoolean("pref_haptics", true))
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled

    private val _keepHandSorted = MutableStateFlow(prefs.getBoolean("pref_sort_hand", false))
    val keepHandSorted: StateFlow<Boolean> = _keepHandSorted

    // Separate from hapticsEnabled on purpose, even though both are "vibration". That one is
    // CONFIRMATION - it fires on your own tap, telling you the app registered it, and someone who
    // finds that noisy will switch it off. This one is an ALERT - it fires when the table starts
    // waiting on you, which is exactly the moment you may not be looking at the screen. Folding
    // them into one switch would mean turning off tap-buzz also silently costs you the alert.
    private val _turnAlertEnabled = MutableStateFlow(prefs.getBoolean("pref_turn_alert", true))
    val turnAlertEnabled: StateFlow<Boolean> = _turnAlertEnabled

    fun toggleTurnAlert() {
        val v = !_turnAlertEnabled.value
        _turnAlertEnabled.value = v
        prefs.edit().putBoolean("pref_turn_alert", v).apply()
    }

    fun toggleCardSymbols() {
        val v = !_showCardSymbols.value
        _showCardSymbols.value = v
        prefs.edit().putBoolean("pref_card_symbols", v).apply()
    }

    fun toggleHaptics() {
        val v = !_hapticsEnabled.value
        _hapticsEnabled.value = v
        prefs.edit().putBoolean("pref_haptics", v).apply()
    }

    fun toggleKeepHandSorted() {
        val v = !_keepHandSorted.value
        _keepHandSorted.value = v
        prefs.edit().putBoolean("pref_sort_hand", v).apply()
    }

    // Custom Table remembers the last preset the same way the design does
    // (localStorage 'localuno.rulePreset'). Null once the config has been hand-edited away from it.
    private val _rulePreset = MutableStateFlow(prefs.getString("rule_preset", "house"))
    val rulePreset: StateFlow<String?> = _rulePreset

    fun setRulePreset(key: String?) {
        _rulePreset.value = key
        prefs.edit().putString("rule_preset", key).apply()
    }

    /** Call once per finished round, from wherever gameOver actually lands (NavGraph). */
    fun recordRoundFinished(won: Boolean) {
        val played = _gamesPlayed.value + 1
        _gamesPlayed.value = played
        prefs.edit().putInt("games_played", played).apply()
        if (won) {
            val wins = _gamesWon.value + 1
            _gamesWon.value = wins
            prefs.edit().putInt("games_won", wins).apply()
        }
    }

    fun updatePlayerName(name: String) {
        _playerName.value = name
        prefs.edit().putString("player_name", name).apply()
    }

    fun updateAvatarColor(color: Int) {
        _avatarColor.value = color
        prefs.edit().putInt("avatar_color", color).apply()
    }

    fun updateBoardSkin(skin: BoardSkin) {
        _boardSkin.value = skin
        prefs.edit().putString("board_skin", skin.storageKey).apply()
    }

    private fun loadPlayerName(): String {
        return prefs.getString("player_name", null) ?: "Player${(1000..9999).random()}"
    }

    private fun loadPlayerId(): String {
        val existing = prefs.getString("player_id", null)
        if (existing != null) return existing

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("player_id", newId).apply()
        return newId
    }

    private fun loadAvatarColor(): Int {
        return prefs.getInt("avatar_color", (0..7).random())
    }

    private fun loadBoardSkin(): BoardSkin {
        return BoardSkin.fromStorageKey(prefs.getString("board_skin", null))
    }
}

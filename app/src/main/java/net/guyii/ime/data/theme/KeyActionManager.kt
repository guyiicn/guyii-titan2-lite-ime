// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package net.guyii.ime.data.theme

import net.guyii.ime.data.theme.model.KeyActionToken
import net.guyii.ime.data.theme.model.PresetKey
import net.guyii.ime.ime.keyboard.KeyAction
import net.guyii.ime.ime.keyboard.KeyCode

object KeyActionManager {
    private val actionCache = mutableMapOf<KeyActionToken, KeyAction>()

    fun getAction(token: String) = getAction(KeyActionToken.Plain(token))

    fun getAction(token: KeyActionToken): KeyAction = actionCache.getOrPut(token) {
        KeyAction(token, ThemeManager.activeTheme.presetKeys)
    }

    fun resetCache() = actionCache.clear()

    /**
     * Lists presets whose send value can never resolve to a key, so that a
     * theme is checked once at activation time instead of on first use.
     */
    fun presetDiagnostics(presetKeys: Map<String, PresetKey>): List<String> = presetKeys.mapNotNull { (name, preset) ->
        val (keycode, modifiers) = KeyCode.parse(preset.send)
        if (preset.send.isNotEmpty() && keycode == 0 && modifiers == 0) {
            "preset '$name' has an unrecognized send '${preset.send}'"
        } else {
            null
        }
    }
}

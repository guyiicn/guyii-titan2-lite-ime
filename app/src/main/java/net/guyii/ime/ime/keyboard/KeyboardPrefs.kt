/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ime.keyboard

import android.content.Context
import net.guyii.ime.data.prefs.AppPrefs
import net.guyii.ime.util.isLandscape

object KeyboardPrefs {
    private val prefs = AppPrefs.defaultInstance()

    private const val WIDE_SCREEN_WIDTH_DP = 600

    fun Context.isLandscapeMode(): Boolean = when (prefs.keyboard.landscapeMode.getValue()) {
        AppPrefs.Keyboard.LandscapeMode.WIDE -> resources.configuration.isLandscape() || isWideScreen()
        AppPrefs.Keyboard.LandscapeMode.LANDSCAPE -> resources.configuration.isLandscape()
        AppPrefs.Keyboard.LandscapeMode.ALWAYS -> true
        else -> false
    }

    private fun Context.isWideScreen(): Boolean {
        val metrics = resources.displayMetrics
        return metrics.widthPixels / metrics.density > WIDE_SCREEN_WIDTH_DP
    }
}

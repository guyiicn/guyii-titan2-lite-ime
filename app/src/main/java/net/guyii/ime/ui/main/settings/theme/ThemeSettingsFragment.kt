/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ui.main.settings.theme

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import net.guyii.ime.R
import net.guyii.ime.data.prefs.PreferenceDelegateFragment
import net.guyii.ime.data.theme.ThemeManager
import net.guyii.ime.ui.main.settings.ColorPickerDialog
import net.guyii.ime.util.addPreference
import net.guyii.ime.util.startActivity
import kotlinx.coroutines.launch

class ThemeSettingsFragment : PreferenceDelegateFragment(ThemeManager.prefs) {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        // Theme switching is hidden, not merely discouraged. Only this project's own theme
        // defines the function row, the four-row symbol page and the full-keyboard escape
        // hatch; switching to any other theme silently replaces all of them with a plain
        // QWERTY. That is recoverable -- switching back restores everything -- but it
        // leaves the keyboard looking broken with no hint as to why, and it applies to any
        // theme added later too, not just the one shipped alongside.
        //
        // Colours stay switchable: a colour scheme only restyles the existing keyboards
        // (refreshColors) rather than rebuilding them, so all 37 remain safe to use.
        findPreference<Preference>("selected_theme")?.isVisible = false
        findPreference<Preference>("normal_mode_color")?.setOnPreferenceClickListener {
            lifecycleScope.launch { ColorPickerDialog.build(lifecycleScope, requireContext()).show() }
            true
        }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(
            R.string.theme_diagnostics,
            R.string.theme_diagnostics_summary,
        ) {
            startActivity<ThemeDiagnosticsActivity>()
        }
    }
}

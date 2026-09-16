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
import net.guyii.ime.ui.main.settings.ThemePickerDialog
import net.guyii.ime.util.addPreference
import net.guyii.ime.util.startActivity
import kotlinx.coroutines.launch

class ThemeSettingsFragment : PreferenceDelegateFragment(ThemeManager.prefs) {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        findPreference<Preference>("selected_theme")?.setOnPreferenceClickListener {
            lifecycleScope.launch { ThemePickerDialog.build(lifecycleScope, requireContext()).show() }
            true
        }
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

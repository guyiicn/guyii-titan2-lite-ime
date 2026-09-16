/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ui.main.settings

import net.guyii.ime.data.prefs.AppPrefs
import net.guyii.ime.data.prefs.PreferenceDelegateFragment

class ClipboardSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().clipboard)

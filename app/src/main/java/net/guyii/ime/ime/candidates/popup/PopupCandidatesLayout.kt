/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ime.candidates.popup

import net.guyii.ime.R
import net.guyii.ime.data.prefs.PreferenceDelegateEnum

enum class PopupCandidatesLayout(override val stringRes: Int) : PreferenceDelegateEnum {
    AUTOMATIC(R.string.automatic),
    HORIZONTAL(R.string.horizontal),
    VERTICAL(R.string.vertical),
    VERTICAL_REVERSE(R.string.vertical_reverse),
}

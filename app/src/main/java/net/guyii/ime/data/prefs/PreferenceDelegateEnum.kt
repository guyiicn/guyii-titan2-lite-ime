/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.data.prefs

import androidx.annotation.StringRes

interface PreferenceDelegateEnum {
    @get:StringRes
    val stringRes: Int
}

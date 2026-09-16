/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.util

import android.content.Context
import net.guyii.ime.data.sync.RimeDataSync

@Suppress("NOTHING_TO_INLINE")
inline fun Context.isStorageAvailable(): Boolean = RimeDataSync.isStorageAvailable(this)

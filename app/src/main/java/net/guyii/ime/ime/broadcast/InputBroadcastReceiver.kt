/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ime.broadcast

import android.view.inputmethod.EditorInfo
import net.guyii.ime.core.Candidates
import net.guyii.ime.core.CompositionProto
import net.guyii.ime.core.RimeMessage
import net.guyii.ime.core.SchemaItem
import net.guyii.ime.core.StatusProto
import net.guyii.ime.ime.window.BoardWindow

interface InputBroadcastReceiver {
    fun onStartInput(info: EditorInfo) {}

    fun onSelectionUpdate(start: Int, end: Int) {}

    fun onRimeSchemaUpdated(schema: SchemaItem) {}

    fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {}

    fun onCandidateListUpdate(data: Candidates.Bulk) {}

    fun onCompositionUpdate(data: CompositionProto) {}

    fun onKeyAppearanceUpdate(composing: Boolean, menu: Boolean, paging: Boolean) {}

    fun onInputStatusUpdate(value: StatusProto) {}

    fun onWindowAttached(window: BoardWindow) {}

    fun onWindowDetached(window: BoardWindow) {}

    fun onEnterKeyLabelUpdate(label: String) {}
}

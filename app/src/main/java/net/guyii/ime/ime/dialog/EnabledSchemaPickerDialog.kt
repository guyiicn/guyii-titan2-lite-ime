// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package net.guyii.ime.ime.dialog

import android.app.AlertDialog
import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import net.guyii.ime.R
import net.guyii.ime.core.RimeApi
import kotlinx.coroutines.launch
import splitties.systemservices.inputMethodManager

object EnabledSchemaPickerDialog {
    suspend fun build(
        rime: RimeApi,
        scope: LifecycleCoroutineScope,
        context: Context,
        extensions: (AlertDialog.Builder.() -> AlertDialog.Builder)? = null,
    ): AlertDialog {
        val selecteds = rime.selectedSchemata()
        val selectedNames = selecteds.map { it.name }
        val selectedIds = selecteds.map { it.id }
        val selectedSchemaId = rime.selectedSchemaId()
        val selectedIndex = selecteds.indexOfFirst { it.id == selectedSchemaId }
        return AlertDialog
            .Builder(context)
            .apply {
                setTitle(R.string.select_current_schema)
                if (rime.isEmpty()) {
                    setMessage(R.string.no_schema_to_select)
                } else {
                    setSingleChoiceItems(
                        selectedNames.toTypedArray(),
                        selectedIndex,
                    ) { dialog, which ->
                        scope.launch {
                            rime.selectSchema(selectedIds[which])
                            dialog.dismiss()
                        }
                    }
                }
                setNeutralButton(R.string.other_ime) { _, _ ->
                    inputMethodManager.showInputMethodPicker()
                }
                extensions?.invoke(this)
            }.create()
    }
}

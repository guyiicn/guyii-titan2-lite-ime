/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ui.main.settings.schema

import android.view.View
import net.guyii.ime.TrimeApplication
import net.guyii.ime.core.SchemaItem
import net.guyii.ime.daemon.RimeDaemon
import net.guyii.ime.data.sync.RimeDataSync
import net.guyii.ime.ui.common.OnItemChangedListener
import net.guyii.ime.ui.main.settings.ProgressFragment
import net.guyii.ime.util.NaiveDustman
import net.guyii.ime.util.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class SchemaListFragment :
    ProgressFragment(),
    OnItemChangedListener<SchemaItem> {
    private lateinit var ui: SchemaListUi

    private val dustman = NaiveDustman<SchemaItem>()

    override suspend fun initialize(): View {
        val available = rime.runOnReady { availableSchemata().toSet() }
        val enabled = rime.runOnReady { enabledSchemata().map { it.id } }
        val entries = available.filter { enabled.contains(it.id) }
        ui =
            SchemaListUi(
                requireContext(),
                initialEntries = entries,
                contentSource = { (available - adapter.items.toSet()).toTypedArray() },
            )
        resetDustman()
        ui.adapter.setViewModel(viewModel)
        ui.adapter.addOnItemChangedListener(this)
        viewModel.enableToolbarEditButton(enabled.isNotEmpty()) {
            ui.adapter.enterMultiSelect(requireActivity().onBackPressedDispatcher)
        }
        return ui.root
    }

    override fun onStart() {
        super.onStart()
        if (::ui.isInitialized) {
            viewModel.enableToolbarEditButton(ui.adapter.items.isNotEmpty()) {
                ui.adapter.enterMultiSelect(requireActivity().onBackPressedDispatcher)
            }
        }
    }

    override fun onStop() {
        persistSchemaList()
        if (::ui.isInitialized) {
            ui.adapter.exitMultiSelect()
        }
        viewModel.disableToolbarEditButton()
        super.onStop()
    }

    override fun onDestroy() {
        if (::ui.isInitialized) {
            ui.adapter.removeItemChangedListener()
        }
        super.onDestroy()
    }

    override fun onItemAdded(
        idx: Int,
        item: SchemaItem,
    ) {
        dustman.addOrUpdate(item.toString(), item)
    }

    override fun onItemRemoved(
        idx: Int,
        item: SchemaItem,
    ) {
        dustman.remove(item.toString())
    }

    override fun onItemAddedBatch(items: List<SchemaItem>) {
        items.forEach { dustman.addOrUpdate(it.toString(), it) }
    }

    override fun onItemRemovedBatch(items: List<SchemaItem>) {
        items.forEach { dustman.remove(it.toString()) }
    }

    private fun persistSchemaList() {
        if (!dustman.dirty) return
        val schemaIds = ui.adapter.items.map { it.id }.toTypedArray()
        resetDustman()
        Timber.i("Persisting schema list: ${schemaIds.joinToString()}")
        TrimeApplication.getInstance().coroutineScope.launch {
            withContext(Dispatchers.IO) {
                val sessionName = "schema-list-persist"
                runCatching {
                    val session = RimeDaemon.createSession(sessionName)
                    try {
                        session.runOnReady {
                            setEnabledSchemata(schemaIds)
                            deploy(skipImport = true)
                        }
                        if (RimeDataSync.usesExternalSync(appContext)) {
                            RimeDataSync.exportConfigFilesToExternal(appContext).getOrThrow()
                        }
                    } finally {
                        RimeDaemon.destroySession(sessionName)
                    }
                }.onFailure { Timber.e(it, "Failed to persist schema list") }
            }
        }
    }

    private fun resetDustman() {
        dustman.reset(ui.adapter.items.associateBy { it.toString() })
    }
}

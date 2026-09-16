/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import net.guyii.ime.R
import net.guyii.ime.data.prefs.AppPrefs
import net.guyii.ime.data.sync.DataStorageMode
import net.guyii.ime.data.sync.RimeDataSync
import net.guyii.ime.databinding.FragmentSetupBinding
import net.guyii.ime.util.serializable

class SetupFragment : Fragment() {
    private lateinit var binding: FragmentSetupBinding

    private val page: SetupPage by lazy { requireArguments().serializable("page")!! }

    private val prefs = AppPrefs.defaultInstance().profile

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentSetupBinding.inflate(inflater).apply {
            storageModeOptions.setOnCheckedChangeListener { _, checkedId ->
                val oldMode = prefs.dataStorageMode.getValue()
                val newMode = when (checkedId) {
                    R.id.sync_from_external_option -> DataStorageMode.EXTERNAL_SYNC
                    R.id.app_specific_storage_option -> DataStorageMode.APP_STORAGE
                    else -> return@setOnCheckedChangeListener
                }
                if (oldMode == DataStorageMode.EXTERNAL_SYNC &&
                    newMode == DataStorageMode.APP_STORAGE
                ) {
                    prefs.userDbMigrated.setValue(false)
                    RimeDataSync.clearExternalTree(requireContext())
                }
                prefs.dataStorageMode.setValue(newMode)
                sync()
                (requireActivity() as SetupActivity).updateButtons()
            }
            syncFromExternalDesc.setOnClickListener { syncFromExternalOption.isChecked = true }
            appSpecificStorageDesc.setOnClickListener { appSpecificStorageOption.isChecked = true }
        }
        sync()
        return binding.root
    }

    // Called on window focus changed
    fun sync() {
        val done = page.isDone()
        val isStorageModePage = false
        val checkedId = when (prefs.dataStorageMode.getValue()) {
            DataStorageMode.EXTERNAL_SYNC -> R.id.sync_from_external_option
            DataStorageMode.APP_STORAGE -> R.id.app_specific_storage_option
        }
        with(binding) {
            storageModeOptions.visibility = if (isStorageModePage) View.VISIBLE else View.GONE
            storageModeOptions.check(checkedId)

            stepText.text = page.getStepText(requireContext())
            hintText.text = page.getHintText(requireContext())
            val showActionButton = !done && page.showActionButton()
            actionButton.visibility = if (showActionButton) View.VISIBLE else View.GONE
            actionButton.text = page.getButtonText(requireContext())
            actionButton.setOnClickListener { page.getButtonAction(requireActivity()) }
            doneText.visibility = if (done) View.VISIBLE else View.GONE
            doneIcon.visibility = if (done) View.VISIBLE else View.GONE
        }
    }
}

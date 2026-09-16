/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ime.clipboard

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import net.guyii.ime.R
import net.guyii.ime.data.db.ClipboardHelper
import net.guyii.ime.data.db.CollectionHelper
import net.guyii.ime.data.db.DatabaseBean
import net.guyii.ime.data.prefs.AppPrefs
import net.guyii.ime.data.theme.FontManager
import net.guyii.ime.data.theme.Theme
import net.guyii.ime.data.theme.ThemeScope
import net.guyii.ime.ime.core.InputTabLayout
import net.guyii.ime.ime.core.TrimeInputMethodService
import net.guyii.ime.ime.keyboard.KeyboardWindow
import net.guyii.ime.ime.segments.SegmentsWindow
import net.guyii.ime.ime.window.BoardWindow
import net.guyii.ime.ime.window.BoardWindowManager
import net.guyii.ime.ui.main.ClipEditActivity
import net.guyii.ime.util.AppUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.views.recyclerview.verticalLayoutManager

class ClipboardWindow(di: DI, private val initialTab: Int = 0) : BoardWindow.BarBoardWindow(di) {

    private val service: TrimeInputMethodService by instance()
    private val windowManager: BoardWindowManager by instance()
    private val scope: ThemeScope by instance()
    private val theme: Theme get() = scope.theme

    private lateinit var clipboardLayout: ClipboardLayout
    private lateinit var clipboardPagesAdapter: ClipboardPagesAdapter
    private var wasAttached = false

    private val prefs = AppPrefs.defaultInstance().clipboard
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste

    private val clipboardBeansPager by lazy {
        Pager(PagingConfig(pageSize = 16)) { ClipboardHelper.allBeans() }
    }
    private val collectionBeansPager by lazy {
        Pager(PagingConfig(pageSize = 16)) { CollectionHelper.allBeans() }
    }
    private var clipboardBeansSubmitJob: Job? = null
    private var collectionBeansSubmitJob: Job? = null

    private val clipboardBeansAdapter by lazy {
        object : ClipboardAdapter(scope) {
            override fun onPaste(bean: DatabaseBean) {
                val text = bean.text ?: return
                service.commitText(text)
                if (clipboardReturnAfterPaste) {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            override fun onPin(id: Int) {
                service.lifecycleScope.launch { ClipboardHelper.pin(id) }
            }

            override fun onUnpin(id: Int) {
                service.lifecycleScope.launch { ClipboardHelper.unpin(id) }
            }

            override fun onEdit(id: Int) {
                AppUtils.launchClipEdit(context, id, ClipEditActivity.FROM_CLIPBOARD)
            }

            override fun onShare(bean: DatabaseBean) {
                val text = bean.text ?: return
                launchTextSharing(text)
            }

            override fun onSegment(bean: DatabaseBean) {
                val text = bean.text ?: return
                windowManager.attachWindow(SegmentsWindow(di, text))
            }

            override fun onCollect(bean: DatabaseBean) {
                service.lifecycleScope.launch {
                    CollectionHelper.addNewBean(bean.text ?: "")
                }
            }

            override fun onDelete(id: Int) {
                service.lifecycleScope.launch { ClipboardHelper.delete(id) }
            }

            override val enableCollection: Boolean = true
        }
    }

    private val collectionBeansAdapter by lazy {
        object : ClipboardAdapter(scope) {
            override fun onPaste(bean: DatabaseBean) {
                val text = bean.text ?: return
                service.commitText(text)
                if (clipboardReturnAfterPaste) {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            override fun onEdit(id: Int) {
                AppUtils.launchClipEdit(context, id, ClipEditActivity.FROM_COLLECTION)
            }

            override fun onShare(bean: DatabaseBean) {
                val text = bean.text ?: return
                launchTextSharing(text)
            }

            override fun onSegment(bean: DatabaseBean) {
                val text = bean.text ?: return
                windowManager.attachWindow(SegmentsWindow(di, text))
            }

            override fun onDelete(id: Int) {
                service.lifecycleScope.launch { CollectionHelper.delete(id) }
            }

            override val enableCollection: Boolean = false
        }
    }

    private val clipboardPage by lazy {
        ClipboardPageUi(context).apply {
            recyclerView.apply {
                layoutManager = verticalLayoutManager()
                adapter = clipboardBeansAdapter
            }
        }
    }

    private val collectionPage by lazy {
        ClipboardPageUi(context).apply {
            recyclerView.apply {
                layoutManager = verticalLayoutManager()
                adapter = collectionBeansAdapter
            }
        }
    }

    override fun onCreateView() = ClipboardLayout(context, scope).apply {
        clipboardLayout = this
        clipboardPagesAdapter = object : ClipboardPagesAdapter() {
            override fun getItemCount(): Int = 2
            override fun onCreatePage(position: Int): ClipboardPageUi = when (position) {
                0 -> clipboardPage
                else -> collectionPage
            }
        }
        viewPager.apply {
            adapter = clipboardPagesAdapter
        }
        titleUi.apply {
            tabLayout.onConfigureTab(viewPager) { tabUi, position ->
                configureTab(tabUi, position)
            }
            deleteAllButton.setOnClickListener {
                val currentItem = viewPager.currentItem
                when (currentItem) {
                    0 -> promptDeleteAll {
                        ClipboardHelper.deleteAll(ClipboardHelper.haveUnpinned())
                    }
                    else -> promptDeleteAll {
                        CollectionHelper.deleteAll(CollectionHelper.haveUnpinned())
                    }
                }
            }
        }
    }

    private fun launchTextSharing(text: String) {
        val target = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(target, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        service.startActivity(chooser)
    }

    private fun promptDeleteAll(action: suspend () -> Unit) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.delete_all)
            .setMessage(R.string.ask_to_delete_all)
            .setPositiveButton(R.string.ok) { _, _ ->
                service.lifecycleScope.launch {
                    action()
                }
            }.setNegativeButton(R.string.cancel, null)
            .create()
        service.showDialog(dialog)
    }

    private fun configureTab(
        tabUi: InputTabLayout.TabUi,
        position: Int,
    ) {
        val label =
            when (position) {
                0 -> R.string.clipboard
                else -> R.string.collection
            }
        tabUi.label.apply {
            setText(label)
            textSize = theme.generalStyle.candidateTextSize
            setTypeface(FontManager.getTypeface("candidate_font"), Typeface.BOLD)
            setTextColor(scope.colors.keyTextColor)
        }
    }

    override fun onAttached() {
        wasAttached = true
        clipboardLayout.viewPager.setCurrentItem(initialTab, false)
        clipboardBeansSubmitJob = service.lifecycleScope.launch {
            clipboardBeansPager.flow.collect {
                clipboardBeansAdapter.submitData(it)
            }
        }
        collectionBeansSubmitJob = service.lifecycleScope.launch {
            collectionBeansPager.flow.collect {
                collectionBeansAdapter.submitData(it)
            }
        }
    }

    override fun onDetached() {
        clipboardBeansAdapter.dismissPopupMenu()
        collectionBeansAdapter.dismissPopupMenu()
        clipboardBeansSubmitJob?.cancel()
        collectionBeansSubmitJob?.cancel()
    }

    override fun refreshColors() {
        if (!wasAttached) return
        clipboardLayout.titleUi.deleteAllButton.refreshColors()
        clipboardLayout.titleUi.tabLayout.reconfigureTabs { tabUi, position ->
            configureTab(tabUi, position)
        }
        clipboardBeansAdapter.refreshColors()
        collectionBeansAdapter.refreshColors()
    }

    override fun onCreateBarView(): View = clipboardLayout.titleUi.root
}

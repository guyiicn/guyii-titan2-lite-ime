/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ime.symbol

import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import net.guyii.ime.daemon.RimeSession
import net.guyii.ime.daemon.launchOnReady
import net.guyii.ime.data.SymbolHistory
import net.guyii.ime.data.theme.LiquidData
import net.guyii.ime.data.theme.ThemeScope
import net.guyii.ime.data.theme.model.LiquidKeyboard
import net.guyii.ime.ime.core.TrimeInputMethodService
import net.guyii.ime.ime.keyboard.CommonKeyboardActionListener
import net.guyii.ime.ime.keyboard.KeyboardWindow
import net.guyii.ime.ime.window.BoardWindow
import net.guyii.ime.ime.window.BoardWindowManager
import net.guyii.ime.ime.window.ResidentWindow
import org.kodein.di.DI
import org.kodein.di.instance

class LiquidWindow(di: DI) :
    BoardWindow.BarBoardWindow(di),
    ResidentWindow {
    override val showTitle = false

    private val service: TrimeInputMethodService by instance()
    private val rime: RimeSession by instance()
    private val scope: ThemeScope by instance()
    private val windowManager: BoardWindowManager by instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by instance()

    private lateinit var liquidLayout: LiquidLayout
    private val symbolHistory = SymbolHistory(180)
    var currentDataType: LiquidData.Type = LiquidData.Type.SINGLE
        private set

    private val adapter by lazy {
        LiquidAdapter(scope) {
            when (currentDataType) {
                LiquidData.Type.SYMBOL -> triggerSymbolInput(this.altText)
                LiquidData.Type.TABS -> {
                    val realPosition = LiquidData.getTagList()
                        .indexOfFirst { it.label == this.text }
                    setDataByIndex(realPosition)
                }
                else -> {
                    service.commitText(this.text)
                    if (currentDataType != LiquidData.Type.HISTORY) {
                        symbolHistory.insert(this.text)
                        symbolHistory.save()
                    }
                }
            }
        }
    }

    private val mainLayoutManager by lazy {
        FlexboxLayoutManager(context).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
        }
    }

    companion object : ResidentWindow.Key

    override val key: ResidentWindow.Key
        get() = LiquidWindow

    override fun onCreateView(): View = LiquidLayout(context, scope, commonKeyboardActionListener).apply {
        liquidLayout = this
        tabsUi.apply {
            setTags(LiquidData.getTagList())
            setOnTabClickListener { i ->
                setDataByIndex(i)
            }
        }
        recyclerView.apply {
            layoutManager = mainLayoutManager
            this.adapter = this@LiquidWindow.adapter
        }
    }

    override fun onCreateBarView() = liquidLayout.tabsUi.root

    override fun onAttached() {}

    override fun onDetached() {}

    override fun refreshColors() {
        if (::liquidLayout.isInitialized) liquidLayout.refreshColors()
    }

    fun setDataByIndex(i: Int) {
        val tag = LiquidData.getTagList()[i]
        currentDataType = tag.type
        liquidLayout.tabsUi.activateTab(i)
        when (tag.type) {
            LiquidData.Type.HISTORY -> {
                symbolHistory.load()
                submitData(symbolHistory.toOrderedList().map { LiquidKeyboard.KeyItem(it) })
            }
            else -> {
                val data = LiquidData.getDataByIndex(i)
                submitData(data)
            }
        }
    }

    private fun submitData(data: List<LiquidKeyboard.KeyItem>) {
        adapter.submitList(data)
    }

    private fun triggerSymbolInput(symbol: String) {
        rime.launchOnReady {
            val (isAsciiMode, isAsciiPunch) = it.statusCached.run { isAsciiMode to isAsciiPunct }
            if (isAsciiMode) it.setRuntimeOption("ascii_mode", false)
            if (isAsciiPunch) it.setRuntimeOption("ascii_punch", false)
            it.clearComposition()
            it.simulateKeySequence(symbol)
            if (isAsciiPunch) it.setRuntimeOption("ascii_punch", true)
            ContextCompat.getMainExecutor(service).execute {
                windowManager.attachWindow(KeyboardWindow)
            }
        }
    }
}

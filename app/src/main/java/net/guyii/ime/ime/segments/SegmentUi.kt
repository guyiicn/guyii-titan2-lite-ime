/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ime.segments

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.StateListDrawable
import android.view.ViewOutlineProvider
import com.google.android.flexbox.FlexboxLayoutManager
import net.guyii.ime.data.theme.FontManager
import net.guyii.ime.data.theme.Theme
import net.guyii.ime.data.theme.ThemeScope
import net.guyii.ime.ime.keyboard.GestureFrame
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.setPaddingDp

class SegmentUi(override val ctx: Context, private val scope: ThemeScope) : Ui {
    private val theme: Theme get() = scope.theme

    private val spacing = ctx.dp(4)

    val textView =
        textView {
            textSize = 16f
            isSingleLine = true
            typeface = FontManager.getTypeface("key_font")
            setPaddingDp(8, 4, 8, 4)
            setTextColor(textColorStates())
        }

    private fun textColorStates(): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_selected),
            intArrayOf(android.R.attr.state_selected),
        ),
        intArrayOf(
            scope.colors.keyTextColor,
            scope.colors.hilitedKeyTextColor,
        ),
    )

    private fun rootBackground(): StateListDrawable = StateListDrawable().apply {
        addState(
            intArrayOf(-android.R.attr.state_selected),
            scope.decorDrawable(
                "key_back_color",
                cornerRadius = ctx.dp(theme.generalStyle.roundCorner),
            ),
        )
        addState(
            intArrayOf(android.R.attr.state_selected),
            scope.decorDrawable(
                "hilited_key_back_color",
                cornerRadius = ctx.dp(theme.generalStyle.roundCorner),
            ),
        )
    }

    override val root = GestureFrame(ctx).apply {
        isClickable = true
        background = rootBackground()
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        layoutParams = FlexboxLayoutManager.LayoutParams(wrapContent, wrapContent).apply {
            setMargins(spacing, spacing, spacing, spacing)
        }
        add(
            textView,
            lParams(wrapContent, wrapContent) {
                gravity = gravityCenter
            },
        )
    }

    /** Restyles the row after a scheme switch. */
    fun refreshColors() {
        textView.setTextColor(textColorStates())
        root.background = rootBackground()
    }

    fun update(isSelected: Boolean) {
        root.isSelected = isSelected
        textView.isSelected = isSelected
    }
}

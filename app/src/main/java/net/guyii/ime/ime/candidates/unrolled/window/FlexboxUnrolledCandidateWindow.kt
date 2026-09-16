/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ime.candidates.unrolled.window

import android.view.Gravity
import android.view.ViewGroup
import androidx.transition.Slide
import androidx.transition.Transition
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import net.guyii.ime.ime.candidates.CandidateViewHolder
import net.guyii.ime.ime.candidates.unrolled.PagingCandidateViewAdapter
import net.guyii.ime.ime.candidates.unrolled.UnrolledCandidateLayout
import net.guyii.ime.ime.candidates.unrolled.decoration.FlexboxHorizontalDecoration
import net.guyii.ime.ime.window.BoardWindow
import org.kodein.di.DI
import splitties.dimensions.dp
import splitties.views.dsl.core.wrapContent

class FlexboxUnrolledCandidateWindow(di: DI) : BaseUnrolledCandidateWindow(di) {
    override fun exitAnimation(nextWindow: BoardWindow): Transition = Slide().apply {
        slideEdge = Gravity.TOP
    }

    override val adapter by lazy {
        object : PagingCandidateViewAdapter(scope) {
            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): CandidateViewHolder = super.onCreateViewHolder(parent, viewType).apply {
                itemView.apply {
                    minimumWidth = dp(40)
                    val itemHeight = dp(theme.generalStyle.run { candidateViewHeight + commentHeight })
                    layoutParams =
                        FlexboxLayoutManager
                            .LayoutParams(wrapContent, itemHeight)
                            .apply { flexGrow = 1f }
                }
            }

            override fun onBindViewHolder(
                holder: CandidateViewHolder,
                position: Int,
            ) {
                super.onBindViewHolder(holder, position)
                bindCandidateUiViewHolder(holder)
            }
        }
    }

    override val layoutManager by lazy {
        FlexboxLayoutManager(context).apply {
            justifyContent = JustifyContent.SPACE_AROUND
            alignItems = AlignItems.FLEX_START
        }
    }

    override fun onCreateCandidateLayout(): UnrolledCandidateLayout = UnrolledCandidateLayout(context, scope).apply {
        recyclerView.apply {
            adapter = this@FlexboxUnrolledCandidateWindow.adapter
            layoutManager = this@FlexboxUnrolledCandidateWindow.layoutManager
            addItemDecoration(FlexboxHorizontalDecoration(separatorDrawable))
        }
    }
}

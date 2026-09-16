/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ime.candidates.compact

import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.ContextThemeWrapper
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import net.guyii.ime.R
import net.guyii.ime.core.Candidates
import net.guyii.ime.daemon.RimeSession
import net.guyii.ime.daemon.launchOnReady
import net.guyii.ime.data.prefs.AppPrefs
import net.guyii.ime.data.theme.Theme
import net.guyii.ime.data.theme.ThemeScope
import net.guyii.ime.ime.bar.InputBarDelegate
import net.guyii.ime.ime.bar.UnrollButtonStateMachine
import net.guyii.ime.ime.broadcast.InputBroadcastReceiver
import net.guyii.ime.ime.candidates.unrolled.decoration.FlexboxVerticalDecoration
import net.guyii.ime.ime.core.InputView
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.recyclerview.recyclerView
import kotlin.math.max

class CompactCandidateDelegate(override val di: DI) :
    DIAware,
    InputBroadcastReceiver {
    private val context: ContextThemeWrapper by instance()
    private val rime: RimeSession by instance()
    private val scope: ThemeScope by instance()
    private val inputView: InputView by instance()
    private val bar: InputBarDelegate by instance()

    private val theme: Theme
        get() = scope.theme

    private val fillStyle by AppPrefs.defaultInstance().keyboard.horizontalCandidateMode

    private val maxSpanCountPref by lazy {
        AppPrefs.defaultInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                maxSpanCount
            } else {
                maxSpanCountLandscape
            }
        }
    }

    private var layoutMinWidth = 0
    private var layoutFlexGrow = 0f

    /**
     * (for [CompactCandidateMode.AUTO_FILL] only)
     * Second layout pass is needed when:
     * [^1] total candidates count < maxSpanCount && [^2] RecyclerView cannot display all of them
     * In that case, displayed candidates should be stretched evenly (by setting flexGrow to 1.0f).
     */
    private var secondLayoutPassNeeded = false
    private var secondLayoutPassDone = false

    private val _unrolledCandidateOffset =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val unrolledCandidateOffset = _unrolledCandidateOffset.asSharedFlow()

    fun refreshUnrolled(childCount: Int) {
        _unrolledCandidateOffset.tryEmit(childCount)
        bar.unrollButtonStateMachine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesUpdated,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty to
                (adapter.total == childCount),
        )
    }

    /** Restyles the compact list after a scheme switch; visible rows re-bind. */
    fun refreshColors() {
        separatorDrawable.paint.color = scope.colors.candidateSeparatorColor
        adapter.notifyDataSetChanged()
    }

    val adapter by lazy {
        CompactCandidateViewAdapter(scope).apply {
            setOnItemClickListener { _, _, position ->
                rime.launchOnReady { it.selectCandidate(position, global = true) }
            }
            setOnItemLongClickListener { _, view, position ->
                inputView.showCandidateActionMenu(position, items[position].text, view, global = true)
                true
            }
        }
    }

    fun updateLayoutParams(minWidth: Int, flexGrow: Float) {
        layoutMinWidth = minWidth
        layoutFlexGrow = flexGrow
    }

    val layoutManager by lazy {
        object : FlexboxLayoutManager(context) {
            init {
                // One long line rather than hidden wrapped rows, so the bar can be scrolled
                // sideways to reach candidates that do not fit. Flick typing rides on this:
                // a swipe across the key surface drags the row, and a swipe up picks
                // whatever ended up above that key.
                flexWrap = FlexWrap.NOWRAP
            }

            override fun canScrollHorizontally(): Boolean = true

            override fun canScrollVertically(): Boolean = false

            override fun onLayoutCompleted(state: RecyclerView.State?) {
                super.onLayoutCompleted(state)
                val cnt = this.childCount
                if (secondLayoutPassNeeded) {
                    if (cnt < adapter.itemCount) {
                        // [^2] RecyclerView can't display all candidates
                        // update LayoutParams in onLayoutCompleted would trigger another
                        // onLayoutCompleted, skip the second one to avoid infinite loop
                        if (secondLayoutPassDone) return
                        secondLayoutPassDone = true
                        for (i in 0 until cnt) {
                            getChildAt(i)!!.updateLayoutParams<LayoutParams> {
                                flexGrow = 1f
                            }
                        }
                    } else {
                        secondLayoutPassNeeded = false
                    }
                }
                refreshUnrolled(cnt)
            }
        }
    }

    private val separatorDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val spacing = theme.generalStyle.candidateSpacing
            val intrinsicSize = max(spacing, context.dp(spacing)).toInt()
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = scope.colors.candidateSeparatorColor
        }
    }

    val view by lazy {
        object : RecyclerView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (fillStyle == CompactCandidateMode.AUTO_FILL) {
                    val maxSpanCount = maxSpanCountPref.getValue()
                    layoutMinWidth = w / maxSpanCount - separatorDrawable.intrinsicWidth
                }
            }
        }
        context.recyclerView(R.id.candidate_view) {
            itemAnimator = null
            isFocusable = false
            isFocusableInTouchMode = false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                defaultFocusHighlightEnabled = false
            }
            adapter = this@CompactCandidateDelegate.adapter
            layoutManager = this@CompactCandidateDelegate.layoutManager
            addItemDecoration(FlexboxVerticalDecoration(separatorDrawable))
        }
    }

    /**
     * Which candidate sits at [x] across the bar, or null if nothing does.
     *
     * Flick typing aims by position, not by arithmetic: the key surface and the candidate
     * bar span the same width, so a swipe up from a point on the keyboard should take
     * whatever is drawn above that point. Candidates are not a fixed width, so the only
     * honest answer comes from hit-testing the row.
     */
    fun candidateIndexAt(x: Float): Int? {
        val child = view.findChildViewUnder(x, view.height / 2f) ?: return null
        return view.getChildAdapterPosition(child).takeIf { it != RecyclerView.NO_POSITION }
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        val (total, highlighted, candidates) = data

        val maxSpanCount = maxSpanCountPref.getValue()

        // Stretching candidates to fill the row would leave nothing to scroll to, so the
        // fill styles only apply while everything already fits.
        when (if (candidates.size > maxSpanCount) CompactCandidateMode.NEVER_FILL else fillStyle) {
            CompactCandidateMode.NEVER_FILL -> {
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = false
            }
            CompactCandidateMode.AUTO_FILL -> {
                layoutMinWidth = view.width / maxSpanCount - separatorDrawable.intrinsicWidth
                layoutFlexGrow = if (candidates.size < maxSpanCount) 0f else 1f
                // [^1] total candidates count < maxSpanCount
                secondLayoutPassNeeded = candidates.size < maxSpanCount
                secondLayoutPassDone = false
            }
            CompactCandidateMode.ALWAYS_FILL -> {
                layoutMinWidth = 0
                layoutFlexGrow = 1f
                secondLayoutPassNeeded = false
            }
        }

        adapter.updateLayoutParams(layoutMinWidth, layoutFlexGrow)
        adapter.updateCandidates(candidates, total, highlighted)

        // not sure why empty candidates won't trigger `FlexboxLayoutManager#onLayoutCompleted()`
        if (candidates.isEmpty()) {
            refreshUnrolled(0)
        }
    }
}

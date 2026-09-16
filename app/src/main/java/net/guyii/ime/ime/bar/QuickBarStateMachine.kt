/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ime.bar

import net.guyii.ime.ime.bar.QuickBarStateMachine.BooleanKey.CandidateEmpty
import net.guyii.ime.ime.bar.QuickBarStateMachine.State.Always
import net.guyii.ime.ime.bar.QuickBarStateMachine.State.Candidate
import net.guyii.ime.ime.bar.QuickBarStateMachine.State.Tab
import net.guyii.ime.util.BuildTransitionEvent
import net.guyii.ime.util.EventStateMachine
import net.guyii.ime.util.TransitionBuildBlock

object QuickBarStateMachine {
    enum class State {
        Always,
        Candidate,
        Tab,
    }

    enum class BooleanKey : EventStateMachine.BooleanStateKey {
        CandidateEmpty,
    }

    enum class TransitionEvent(
        val builder: TransitionBuildBlock<State, BooleanKey>,
    ) : EventStateMachine.TransitionEvent<State, BooleanKey> by BuildTransitionEvent(builder) {
        CandidatesUpdated({
            from(Always) transitTo Candidate on (CandidateEmpty to false)
            from(Candidate) transitTo Always on (CandidateEmpty to true)
        }),
        BarBoardWindowAttached({
            from(Always) transitTo Tab
            from(Candidate) transitTo Tab
        }),
        WindowDetached({
            // candidate state has higher priority so here it goes first
            from(Tab) transitTo Candidate on (CandidateEmpty to false)
            from(Tab) transitTo Always
        }),
    }

    fun new(block: (State) -> Unit) = EventStateMachine<State, TransitionEvent, BooleanKey>(
        initialState = Always,
        externalBooleanStates =
        mutableMapOf(
            CandidateEmpty to true,
        ),
    ).apply {
        onNewStateListener = block
    }
}

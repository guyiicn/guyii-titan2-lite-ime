/*
 * SPDX-FileCopyrightText: 2026 guyii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ime.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import net.guyii.ime.ime.core.TouchpadGesture.Direction

/**
 * Traces captured on a Titan 2 Lite, from the diagnostic log the input method wrote while
 * the key surface was swiped (`touchpad-debug.log`, 19 complete DOWN→UP gestures). Every
 * coordinate pair below is a real swipe, not an invented one.
 */
class TouchpadGestureTest :
    StringSpec({

        fun swipe(
            fromX: Float,
            fromY: Float,
            toX: Float,
            toY: Float,
        ) = TouchpadGesture.classify(fromX, toX - fromX, toY - fromY)

        // --- the swipes that used to work: long sideways runs across the keyboard ---

        "a sweep across the keyboard pages forward" {
            val s = swipe(445f, 646f, 857f, 550f)
            s?.direction shouldBe Direction.Right
        }

        "the longest captured sweep is still just a page" {
            swipe(138f, 646f, 1078f, 595f)?.direction shouldBe Direction.Right
        }

        "a sweep back the other way is Left" {
            swipe(977f, 539f, 270f, 609f)?.direction shouldBe Direction.Left
        }

        // --- the swipe that flick typing was losing ---

        "an upward flick of 111px is accepted" {
            // Captured at 06:19:25 and thrown away by the old single 120px threshold, which
            // is why paging worked while candidate selection never fired.
            val s = swipe(682f, 874f, 762f, 763f)
            s?.direction shouldBe Direction.Up
            s?.keyIndex shouldBe 6
        }

        "a downward flick of the same size is accepted" {
            swipe(682f, 763f, 762f, 874f)?.direction shouldBe Direction.Down
        }

        // --- everything the device produced that must stay rejected ---

        "a tap that does not move is not a swipe" {
            swipe(540f, 958f, 540f, 958f).shouldBeNull()
            swipe(634f, 240f, 634f, 240f).shouldBeNull()
        }

        "drift while tapping is not a swipe" {
            swipe(10f, 253f, 10f, 245f).shouldBeNull() // 8px
            swipe(10f, 271f, 10f, 259f).shouldBeNull() // 12px
            swipe(966f, 755f, 957f, 755f).shouldBeNull() // 9px
            swipe(113f, 575f, 129f, 573f).shouldBeNull() // 16px
            swipe(10f, 288f, 39f, 289f).shouldBeNull() // 29px sideways
        }

        "sideways travel between the two thresholds is still rejected" {
            // 100px sideways is under MIN_TRAVEL_H even though it clears MIN_TRAVEL_V;
            // the vertical threshold must not leak into horizontal classification.
            swipe(400f, 500f, 500f, 500f).shouldBeNull()
        }

        // --- how far a sweep travels, in candidates ---

        fun steps(dx: Float) = (dx / TouchpadGesture.STEP_PX).toInt()

        "one key width of travel is one candidate" {
            steps(108f) shouldBe 1
            steps(-108f) shouldBe -1
        }

        "travel under a key width moves nothing" {
            // This is what the discrete implementation got wrong: any swipe at all jumped
            // a whole page of five, however short.
            steps(107f) shouldBe 0
            steps(29f) shouldBe 0
        }

        "the captured sweeps map to sensible counts" {
            steps(287f) shouldBe 2 // 06:28:19
            steps(143f) shouldBe 1 // 06:28:20
            steps(-185f) shouldBe -1 // 06:28:19 back the other way
            steps(940f) shouldBe 8 // the full width of the keyboard
        }

        // --- column mapping ---

        "key index is clamped to the row at both edges" {
            TouchpadGesture.keyIndexOf(0f) shouldBe 0
            TouchpadGesture.keyIndexOf(1078f) shouldBe 9
            TouchpadGesture.keyIndexOf(5000f) shouldBe 9
        }

        "every column of the letter row is reachable" {
            val perKey = TouchpadGesture.SURFACE_WIDTH / TouchpadGesture.KEYS_PER_ROW
            for (i in 0 until TouchpadGesture.KEYS_PER_ROW) {
                TouchpadGesture.keyIndexOf(perKey * i + perKey / 2) shouldBe i
            }
        }
    })

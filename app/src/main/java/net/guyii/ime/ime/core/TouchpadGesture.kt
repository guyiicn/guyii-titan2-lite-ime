/*
 * SPDX-FileCopyrightText: 2026 guyii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ime.core

import android.view.InputDevice
import android.view.MotionEvent

/**
 * Recognises swipes on the Titan's capacitive key surface, which Android exposes as a
 * separate [InputDevice.SOURCE_TOUCHPAD] device alongside the key matrix.
 *
 * Measured on a Titan 2 Lite: the surface reports absolute coordinates over
 * `X 0..1079` and `Y 0..748`, roughly 60 Hz, so a swipe can be attributed to the key it
 * started on. The first letter row spans the full width in ten keys, which is what
 * [Swipe.keyIndex] resolves for flick-to-select.
 *
 * Whether these arrive at all is decided per app by the system's Scroll assistant
 * (Settings -> Keyboard gesture): this input method has to be set to Sliding Mode 2
 * there. The setting is per app, which is easy to miss -- an early measurement compared
 * the assistant on against off and concluded it had to be off, when what actually
 * mattered was the mode assigned to the app doing the measuring.
 */
class TouchpadGesture(
    /** Names the delivery route in the diagnostic log, e.g. `IME` or `A11Y`. */
    private val tag: String = "IME",
    private val surfaceWidth: Float = SURFACE_WIDTH,
    /**
     * Reports sideways travel as it happens, in whole steps of [STEP_PX], signed by
     * direction. Browsing candidates has to follow the finger rather than wait for it to
     * lift: a single action per swipe can only ever move a fixed amount, which is what
     * made paging jump five candidates at a time no matter how far the finger went.
     */
    private val onScroll: (steps: Int) -> Unit = {},
    private val onSwipe: (Swipe) -> Unit,
) {
    enum class Direction { Up, Down, Left, Right }

    data class Swipe(
        val direction: Direction,
        /** Column the swipe started on, `0..9`, matching the first letter row Q..P. */
        val keyIndex: Int,
        /** Where the finger went down, in surface coordinates, for positional targeting. */
        val startX: Float = 0f,
    )

    private var tracking = false
    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var moves = 0
    private var emittedSteps = 0

    /** @return true when the event belongs to the key surface and was consumed. */
    fun onMotionEvent(event: MotionEvent): Boolean {
        val fromTouchpad = event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
        TouchpadLog.countMotion(fromTouchpad)
        if (!fromTouchpad) {
            // Recorded, not consumed: if the surface ever reports a source we do not expect,
            // this is the line that says so.
            TouchpadLog.otherSource {
                "$tag MOTION other src=0x%x act=%s dev=%d(%s) x=%.0f y=%.0f".format(
                    event.source,
                    actionName(event.actionMasked),
                    event.deviceId,
                    event.device?.name ?: "?",
                    event.x,
                    event.y,
                )
            }
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                startX = event.x
                startY = event.y
                startTime = event.eventTime
                moves = 0
                emittedSteps = 0
                if (TouchpadLog.enabled) TouchpadLog.line(
                    "$tag MOTION down src=0x%x dev=%d(%s) x=%.0f y=%.0f".format(
                        event.source,
                        event.deviceId,
                        event.device?.name ?: "?",
                        event.x,
                        event.y,
                    ),
                )
            }
            MotionEvent.ACTION_MOVE -> {
                moves++
                if (tracking) reportScroll(event)
            }
            MotionEvent.ACTION_UP -> {
                if (tracking) {
                    tracking = false
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (emittedSteps != 0) {
                        // Already acted on continuously; a second, discrete action on lift
                        // would move the selection twice for one swipe.
                        TouchpadLog.line("$tag GESTURE scrolled $emittedSteps step(s), no lift action")
                        return true
                    }
                    val swipe = classify(dx, dy)
                    if (TouchpadLog.enabled) TouchpadLog.line(
                        "$tag GESTURE dx=%.0f dy=%.0f moves=%d dur=%dms startX=%.0f -> %s".format(
                            dx,
                            dy,
                            moves,
                            event.eventTime - startTime,
                            startX,
                            swipe?.let { "${it.direction} key=${it.keyIndex}" }
                                ?: "rejected (too short)",
                        ),
                    )
                    swipe?.let(onSwipe)
                } else {
                    TouchpadLog.line("$tag MOTION up without a tracked down")
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                TouchpadLog.line("$tag MOTION cancel after $moves moves")
                tracking = false
            }
            else ->
                TouchpadLog.line(
                    "$tag MOTION ${actionName(event.actionMasked)} x=%.0f y=%.0f".format(event.x, event.y),
                )
        }
        return true
    }

    /** Emits whole steps of sideways travel, and only while the swipe is mainly sideways. */
    private fun reportScroll(event: MotionEvent) {
        val dx = event.x - startX
        if (kotlin.math.abs(dx) <= kotlin.math.abs(event.y - startY)) return
        val steps = (dx / STEP_PX).toInt()
        if (steps == emittedSteps) return
        onScroll(steps - emittedSteps)
        emittedSteps = steps
    }

    private fun actionName(action: Int) = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
        MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
        MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
        MotionEvent.ACTION_SCROLL -> "SCROLL"
        else -> "action#$action"
    }

    private fun classify(
        dx: Float,
        dy: Float,
    ): Swipe? = classify(startX, dx, dy, surfaceWidth)

    companion object {
        /** Pure form of the recogniser, so the thresholds can be tested without a device. */
        fun classify(
            startX: Float,
            dx: Float,
            dy: Float,
            surfaceWidth: Float = SURFACE_WIDTH,
        ): Swipe? {
            val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
            val travel = if (horizontal) kotlin.math.abs(dx) else kotlin.math.abs(dy)
            if (travel < if (horizontal) MIN_TRAVEL_H else MIN_TRAVEL_V) return null
            val direction =
                when {
                    horizontal && dx > 0 -> Direction.Right
                    horizontal -> Direction.Left
                    dy > 0 -> Direction.Down
                    else -> Direction.Up
                }
            return Swipe(direction, keyIndexOf(startX, surfaceWidth), startX)
        }

        fun keyIndexOf(
            x: Float,
            surfaceWidth: Float = SURFACE_WIDTH,
        ): Int = (x / (surfaceWidth / KEYS_PER_ROW)).toInt().coerceIn(0, KEYS_PER_ROW - 1)

        /** Reported range of the key surface; see [TouchpadGesture]. */
        const val SURFACE_WIDTH = 1080f
        const val KEYS_PER_ROW = 10

        /**
         * Sideways swipes run the length of the keyboard, so they are long: the ones
         * captured on the device covered 312 to 940 px, against 2 to 29 px of drift on
         * a tap.
         */
        const val MIN_TRAVEL_H = 120f

        /**
         * Vertically the surface is only four key rows tall, so the same bar cannot
         * apply. A deliberate upward swipe in the captured traces covered 111 px and was
         * being thrown away by the horizontal threshold -- which is why flick typing
         * appeared to do nothing while paging worked. Drift on a tap stays under 12 px,
         * so this separates them with room to spare.
         */
        const val MIN_TRAVEL_V = 70f

        /**
         * Sideways travel worth one candidate. One key width (1080 / 10), so the gesture
         * reads as "drag past three keys, move three candidates" -- the same proportional
         * feel as dragging the space bar to move a cursor, which is the idiom people
         * already know, and it covers both stepping and sweeping without a second gesture.
         */
        const val STEP_PX = 108f
    }
}

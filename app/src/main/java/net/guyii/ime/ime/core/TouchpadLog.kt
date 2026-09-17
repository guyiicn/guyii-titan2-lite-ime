/*
 * SPDX-FileCopyrightText: 2026 guyii
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ime.core

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import net.guyii.ime.BuildConfig
import net.guyii.ime.data.prefs.AppPrefs
import net.guyii.ime.util.appContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only diagnostic log for the flick-typing (飞字) path, written to the shared
 * **Downloads** folder so it can be opened with any file manager and collected from a phone
 * that is not reachable over adb at the time of testing.
 *
 * The question this exists to answer is whether the Titan's capacitive key surface reaches
 * the input method at all. Everything on the path is recorded: service lifecycle, every
 * generic motion event the service is handed (including ones from other sources), the
 * gesture the recogniser makes of them, and whether rime was composing when it fired. A
 * run with no `MOTION` lines at all is itself the answer.
 *
 * Writes go through MediaStore, which needs no storage permission, and are batched: a
 * MediaStore stream is far too expensive to open per line, and the surface samples at
 * roughly 60 Hz. Anything buffered is flushed a second later, and at the end of every
 * input session.
 */
object TouchpadLog {
    /**
     * `.txt`, not `.log`: MediaStore appends an extension matching the declared MIME type,
     * so a `.log` name lands on disk as `guyii-ime-touchpad.log.txt`.
     */
    const val FILE_NAME = "guyii-ime-touchpad.txt"

    /** Keep the file small enough to send; it starts over once exceeded. */
    private const val MAX_BYTES = 2L * 1024 * 1024

    private const val FLUSH_DELAY_MS = 1000L
    private const val FLUSH_THRESHOLD = 4096

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val day = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val lock = Any()
    private val buffer = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private val flushRunnable = Runnable { flush() }

    private var uri: Uri? = null
    private var written = 0L
    private var broken = false

    private val enabledPref by AppPrefs.defaultInstance().advanced.touchpadDebugLog

    /**
     * Whether anything is written at all, read from the setting each time so flipping the
     * switch takes effect without restarting the keyboard.
     *
     * Callers check this before building their message: the surface samples at roughly
     * 60 Hz, and formatting a line that is about to be discarded is work nobody asked for.
     */
    val enabled: Boolean
        get() = !broken && enabledPref

    /** Counts motion events between input sessions, so "none arrived" is stated explicitly. */
    @Volatile
    var motionCount = 0
        private set

    @Volatile
    var touchpadCount = 0
        private set

    private var otherLogged = 0

    fun countMotion(fromTouchpad: Boolean) {
        if (!enabled) return
        motionCount++
        if (fromTouchpad) touchpadCount++
    }

    fun resetCounts() {
        motionCount = 0
        touchpadCount = 0
        otherLogged = 0
    }

    /**
     * Records an unexpected motion source, but only the first few per input session.
     *
     * If the key surface turns out to report something other than `SOURCE_TOUCHPAD`, these
     * arrive at the sampling rate of the hardware; a handful identify it just as well as
     * thousands would, and the file stays small enough to send.
     */
    fun otherSource(describe: () -> String) {
        if (!enabled || otherLogged >= OTHER_LIMIT) return
        otherLogged++
        line(describe() + if (otherLogged == OTHER_LIMIT) "  (further such lines suppressed)" else "")
    }

    private const val OTHER_LIMIT = 12

    fun line(message: String) {
        if (!enabled) return
        synchronized(lock) {
            buffer.append('[').append(stamp.format(Date())).append("] ").append(message).append('\n')
            if (buffer.length >= FLUSH_THRESHOLD) {
                flushLocked()
                return
            }
        }
        handler.removeCallbacks(flushRunnable)
        handler.postDelayed(flushRunnable, FLUSH_DELAY_MS)
    }

    /** Marks a new run so separate test sessions can be told apart in one file. */
    fun session(note: String) {
        line("")
        line("=== session ${day.format(Date())} | ${BuildConfig.BUILD_VERSION_NAME} | $note")
        flush()
    }

    fun flush() = synchronized(lock) { flushLocked() }

    private fun flushLocked() {
        if (broken || buffer.isEmpty()) return
        val text = buffer.toString()
        buffer.setLength(0)
        runCatching {
            if (written > MAX_BYTES) {
                // Start over rather than grow without bound; MediaStore has no cheap truncate.
                delete()
                written = 0
            }
            append(text)
            written += text.length
        }.onFailure {
            broken = true
            Timber.w(it, "touchpad log write failed; disabling")
        }
    }

    private fun append(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val target = uri ?: locate() ?: create() ?: error("cannot create $FILE_NAME")
            uri = target
            appContext.contentResolver.openOutputStream(target, "wa")?.use {
                it.write(text.toByteArray())
            } ?: error("cannot open $FILE_NAME")
        } else {
            legacyFile().appendText(text)
        }
    }

    /** Reuses the entry across process restarts so a session does not silently start a new file. */
    private fun locate(): Uri? = runCatching {
        appContext.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(FILE_NAME),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                written = c.getLong(1)
                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
            } else {
                null
            }
        }
    }.getOrNull()

    private fun create(): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        return appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private fun delete() {
        uri?.let { runCatching { appContext.contentResolver.delete(it, null, null) } }
        uri = null
    }

    @Suppress("DEPRECATION")
    private fun legacyFile(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FILE_NAME,
        )
}

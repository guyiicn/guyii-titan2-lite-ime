/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.ime.core

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import net.guyii.ime.core.KeyModifiers
import net.guyii.ime.core.KeyValue
import net.guyii.ime.core.RimeApi
import net.guyii.ime.core.RimeKeyMapping
import net.guyii.ime.core.RimeMessage
import net.guyii.ime.daemon.RimeDaemon
import net.guyii.ime.daemon.RimeSession
import net.guyii.ime.data.prefs.AppPrefs
import net.guyii.ime.data.prefs.PreferenceDelegate
import net.guyii.ime.data.prefs.PreferenceDelegateProvider
import net.guyii.ime.data.theme.ColorManager
import net.guyii.ime.data.theme.ThemeManager
import net.guyii.ime.data.theme.ThemeScope
import net.guyii.ime.ime.composition.CandidatesView
import net.guyii.ime.ime.keyboard.InputFeedbackManager
import net.guyii.ime.ime.keyboard.KeyAction
import net.guyii.ime.ime.keyboard.KeyboardWindow
import net.guyii.ime.receiver.RimeIntentReceiver
import net.guyii.ime.util.any
import net.guyii.ime.util.findSectionFrom
import net.guyii.ime.util.forceShowSelf
import net.guyii.ime.util.monitorCursorAnchor
import net.guyii.ime.util.styledFloat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import splitties.bitflags.hasFlag
import splitties.systemservices.clipboardManager
import splitties.systemservices.inputMethodManager
import timber.log.Timber

/** Shift+Alt punctuation layer; each keycode carries the symbol printed on its Alt layer. */
private val ASCII_PUNCTUATION_LAYER =
    mapOf(
        KeyEvent.KEYCODE_N to ",",
        KeyEvent.KEYCODE_M to ".",
        KeyEvent.KEYCODE_H to ":",
        KeyEvent.KEYCODE_V to "?",
        KeyEvent.KEYCODE_B to "!",
        KeyEvent.KEYCODE_K to "'",
        KeyEvent.KEYCODE_L to "\"",
        KeyEvent.KEYCODE_T to "(",
        KeyEvent.KEYCODE_Y to ")",
        KeyEvent.KEYCODE_G to "/",
        KeyEvent.KEYCODE_J to "#",
        KeyEvent.KEYCODE_P to "@",
        KeyEvent.KEYCODE_A to "*",
    )

/** [輸入法][InputMethodService]主程序  */

open class TrimeInputMethodService : LifecycleInputMethodService() {
    private lateinit var rime: RimeSession
    private val jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    private val prefs = AppPrefs.defaultInstance()
    private lateinit var decorView: View
    private lateinit var contentView: FrameLayout
    private lateinit var lastKnownConfig: Configuration
    private var inputView: InputView? = null
    private var candidatesView: CandidatesView? = null
    private val navBarManager = NavigationBarManager()
    private val inputDeviceManager = InputDeviceManager { useVirtualKeyboard, useCandidatesView ->
        postRimeJob {
            setCandidatePagingMode(useCandidatesView)
        }
        currentInputConnection?.monitorCursorAnchor(useCandidatesView)
        window.window?.let {
            navBarManager.evaluate(it, useVirtualKeyboard, themeScope.colors)
        }
    }
    private val rimeIntentReceiver = RimeIntentReceiver()

    private var lastCommittedText: String = ""

    private val touchpadGesture =
        TouchpadGesture(onScroll = ::onTouchpadScroll, onSwipe = ::onTouchpadSwipe)

    private var composingText: String = ""

    private var cursorUpdateIndex = 0

    private val recreateInputViewPrefs: Array<PreferenceDelegate<*>> = arrayOf(
        prefs.keyboard.expandKeypressArea,
        prefs.keyboard.hideKeySymbol,
        prefs.keyboard.hideKeyHint,
        prefs.advanced.ignoreSystemGestureInsets,
    )

    private val themeScope: ThemeScope
        get() = requireNotNull(ColorManager.currentScope())

    @Keep
    private val recreateInputViewListener =
        PreferenceDelegate.OnChangeListener<Any> { _, _ ->
            replaceInputView(themeScope)
        }

    @Keep
    private val recreateCandidatesViewListener =
        PreferenceDelegateProvider.OnChangeListener {
            replaceCandidateView(themeScope)
        }

    @Keep
    private val onThemeChangeListener =
        ThemeManager.OnThemeChangeListener {
            replaceInputViews(themeScope)
        }

    @Keep
    private val onColorChangeListener =
        ColorManager.OnColorChangeListener {
            ContextCompat.getMainExecutor(this).execute {
                // A scheme-only change restyles the tree in place; theme
                // switches rebuild it through onThemeChangeListener.
                inputView?.refreshColors()
                candidatesView?.refreshColors()
                window.window?.let {
                    navBarManager.evaluate(it, inputDeviceManager.useVirtualKeyboard, themeScope.colors)
                }
            }
        }

    private fun postJob(
        scope: CoroutineScope,
        block: suspend () -> Unit,
    ): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        jobs.trySend(job)
        return job
    }

    /**
     * Post a rime operation to [jobs] to be executed
     *
     * Unlike `rime.runOnReady` or `rime.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postRimeJob] ensures that operations are executed sequentially.
     */
    fun postRimeJob(block: suspend RimeApi.() -> Unit) = postJob(rime.lifecycleScope) { rime.runOnReady(block) }

    private suspend fun updateRimeOption(api: RimeApi) {
        try {
            api.setRuntimeOption("soft_cursor", prefs.keyboard.useSoftCursor.getValue()) // 軟光標
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun registerReceiver() {
        val intentFilter =
            IntentFilter().apply {
                addAction(RimeIntentReceiver.ACTION_DEPLOY)
                addAction(RimeIntentReceiver.ACTION_SYNC_USER_DATA)
            }
        ContextCompat.registerReceiver(
            this,
            rimeIntentReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onCreate() {
        TouchpadLog.session("飞字诊断")
        rime = RimeDaemon.createSession(javaClass.name)
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        lifecycleScope.launch {
            rime.run { messageFlow }.collect {
                handleRimeMessage(it)
            }
        }
        recreateInputViewPrefs.forEach {
            it.registerOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.registerOnChangeListener(recreateCandidatesViewListener)
        // ensure theme and color managers are initialized after rime is ready
        lifecycleScope.launch {
            rime.runOnReady {
                ThemeManager.init(resources.configuration)
                ThemeManager.addOnChangedListener(onThemeChangeListener)
                ColorManager.addOnChangedListener(onColorChangeListener)
            }
        }
        InputFeedbackManager.init(this)
        registerReceiver()
        super.onCreate()
        Timber.d("onCreate")
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        lastKnownConfig = Configuration(resources.configuration)
    }

    private fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.CommitTextMessage -> {
                if (!it.data.text.isNullOrEmpty()) {
                    commitText(it.data.text)
                }
            }
            is RimeMessage.InlinePreeditMessage -> {
                updateComposingText(it.data)
            }
            is RimeMessage.KeyMessage ->
                it.data.let msg@{
                    if (it.isVirtual) {
                        when (it.value.value) {
                            RimeKeyMapping.RimeKey_Return -> handleReturnKey()
                            else -> {
                                val keyCode = it.value.keyCode
                                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                                    // recognized keyCode
                                    sendDownUpKeyEvent(
                                        keyCode,
                                        it.modifiers.metaState or meta(
                                            alt = it.modifiers.alt,
                                            shift = it.modifiers.shift,
                                            ctrl = it.modifiers.ctrl,
                                            meta = it.modifiers.meta,
                                        ),
                                    )
                                    if (it.modifiers.ctrl && keyCode == KeyEvent.KEYCODE_C) clearTextSelection()
                                } else {
                                    if (it.value.value > 0) {
                                        runCatching {
                                            commitText(Character.toString(it.value.value))
                                        }.getOrElse { t -> Timber.w(t, "Unhandled Virtual KeyEvent: $it") }
                                    } else {
                                        Timber.w("Unhandled Virtual KeyEvent: $it")
                                    }
                                }
                            }
                        }
                    } else {
                        val keyCode = it.value.keyCode
                        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            // recognized keyCode
                            val eventTime = SystemClock.uptimeMillis()
                            if (it.modifiers.release) {
                                sendUpKeyEvent(eventTime, keyCode, it.modifiers.metaState)
                            } else {
                                sendDownKeyEvent(eventTime, keyCode, it.modifiers.metaState)
                            }
                        } else {
                            if (!it.modifiers.release && it.value.value > 0) {
                                runCatching {
                                    commitText(Character.toString(it.value.value))
                                }.getOrElse { t -> Timber.w(t, "Unhandled Rime KeyEvent: $it") }
                            } else {
                                Timber.w("Unhandled Rime KeyEvent: $it")
                            }
                        }
                    }
                }
            is RimeMessage.DeployMessage -> {
                if (it.data == RimeMessage.DeployMessage.State.Success) {
                    // The deployment may have refreshed the current theme's artifact.
                    val themeId = ThemeManager.prefs.selectedTheme.getValue()
                    lifecycleScope.launch { ThemeManager.selectTheme(themeId) }
                }
            }
            else -> {}
        }
    }

    private fun replaceInputView(scope: ThemeScope): InputView {
        val newInputView = InputView(this, rime, scope)
        listenForFlicks(newInputView)
        setInputView(newInputView)
        inputDeviceManager.setInputView(newInputView)
        inputView = newInputView
        return newInputView
    }

    private fun replaceCandidateView(scope: ThemeScope): CandidatesView {
        val newCandidatesView = CandidatesView(this, rime, scope)
        contentView.removeView(candidatesView)
        contentView.addView(newCandidatesView)
        inputDeviceManager.setCandidatesView(newCandidatesView)
        candidatesView = newCandidatesView
        if (decorLocationUpdated) {
            candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
        } else {
            candidatesView?.updateCursorAnchor(contentSize)
        }
        return newCandidatesView
    }

    private fun replaceInputViews(scope: ThemeScope) {
        navBarManager.evaluate(window.window!!, inputDeviceManager.useVirtualKeyboard, scope.colors)
        replaceInputView(scope)
        replaceCandidateView(scope)
        inputView?.updateEnterKeyLabel(currentInputEditorInfo)
    }

    override fun onDestroy() {
        TouchpadLog.flush()
        InputFeedbackManager.destroy()
        inputView = null
        recreateInputViewPrefs.forEach {
            it.unregisterOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.unregisterOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        ColorManager.removeOnChangedListener(onColorChangeListener)
        super.onDestroy()
        unregisterReceiver(rimeIntentReceiver)
        RimeDaemon.destroySession(javaClass.name)
    }

    private fun handleReturnKey() {
        currentInputEditorInfo.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL ||
                imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)
            ) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                return
            }
            if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                currentInputConnection.performEditorAction(actionId)
                return
            }
            when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED,
                EditorInfo.IME_ACTION_NONE,
                -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)

                else -> currentInputConnection.performEditorAction(action)
            }
        }
    }

    /**
     * https://github.com/fcitx5-android/fcitx5-android/blob/fe3a618c8fd18842305d2f8ec2880fcc67ec1679/app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt#L523-#L547
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        postRimeJob { clearComposition() }
        val keyboardUiModeMask = ActivityInfo.CONFIG_KEYBOARD or
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
            ActivityInfo.CONFIG_UI_MODE
        val diff = lastKnownConfig.diff(newConfig)
        Timber.d("onConfigurationChanged diff=$diff")
        if (diff and keyboardUiModeMask != diff) {
            super.onConfigurationChanged(newConfig)
        }
        lastKnownConfig.setTo(newConfig)
    }

    private val contentSize = floatArrayOf(0f, 0f)
    private val decorLocation = floatArrayOf(0f, 0f)
    private val decorLocationInt = intArrayOf(0, 0)
    private var decorLocationUpdated = false

    private fun updateDecorLocation() {
        contentSize[0] = contentView.width.toFloat()
        contentSize[1] =
            if (inputDeviceManager.useVirtualKeyboard) {
                inputViewLocation[1].toFloat()
            } else {
                contentView.height.toFloat()
            }
        decorView.getLocationOnScreen(decorLocationInt)
        decorLocation[0] = decorLocationInt[0].toFloat()
        decorLocation[1] = decorLocationInt[1].toFloat()
        // contentSize and decorLocation can be completely wrong,
        // when measuring right after the very first onStartInputView() of an IMS' lifecycle
        if (contentSize[0] > 0 && contentSize[1] > 0) {
            decorLocationUpdated = true
        }
    }

    private val anchorPosition = RectF()

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val bounds = info.getCharacterBounds(0)
        // update anchorPosition
        if (bounds == null) {
            // composing is disabled in target app or trime settings
            // use the position of the insertion marker instead
            anchorPosition.top = info.insertionMarkerTop
            anchorPosition.left = info.insertionMarkerHorizontal
            anchorPosition.bottom = info.insertionMarkerBottom
            anchorPosition.right = info.insertionMarkerHorizontal
        } else {
            // for different writing system (e.g. right to left languages),
            // we have to calculate the correct RectF
            val horizontal = if (candidatesView?.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right else bounds.left
            anchorPosition.top = bounds.top
            anchorPosition.left = horizontal
            anchorPosition.bottom = bounds.bottom
            anchorPosition.right = horizontal
        }
        if (!decorLocationUpdated) {
            updateDecorLocation()
        }
        if (anchorPosition.any(Float::isNaN)) {
            candidatesView?.updateCursorAnchor(contentSize)
            return
        }
        info.matrix.mapRect(anchorPosition)
        val (dX, dY) = decorLocation
        anchorPosition.offset(-dX, -dY)
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        cursorUpdateIndex += 1
        handleCursorUpdate(newSelStart, newSelEnd, candidatesStart, candidatesEnd, cursorUpdateIndex)
        inputView?.updateSelection(newSelStart, newSelEnd)
    }

    private fun handleCursorUpdate(
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
        updateIndex: Int,
    ) {
        if (newSelStart != newSelEnd) return
        if (candidatesStart == candidatesEnd) return
        if (newSelStart in candidatesStart..candidatesEnd) {
            val position = newSelStart - candidatesStart
            if (position != composingText.length) {
                postRimeJob {
                    if (updateIndex != cursorUpdateIndex) return@postRimeJob
                    Timber.d("handleCursorUpdate: move rime cursor to $position")
                    moveCursorPos(position)
                }
            }
        } else {
            Timber.d("handleCursorUpdate: clear composition")
            postRimeJob {
                clearComposition()
            }
        }
    }

    private val inputViewLocation = intArrayOf(0, 0)

    override fun onComputeInsets(outInsets: Insets) {
        if (inputDeviceManager.useVirtualKeyboard) {
            inputView?.keyboardView?.getLocationInWindow(inputViewLocation)
            outInsets.apply {
                contentTopInsets = inputViewLocation[1]
                visibleTopInsets = inputViewLocation[1]
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        } else {
            val n = decorView.findViewById<View>(android.R.id.navigationBarBackground)?.height ?: 0
            val h = decorView.height - n
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        }
    }

    // always show InputView since we delegate CandidatesView's visibility to it
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    fun superEvaluateInputViewShown() = super.onEvaluateInputViewShown()

    override fun onCreateInputView(): View? {
        Timber.d("onCreateInputView")
        replaceInputViews(themeScope)
        // We will call `setInputView` by ourselves. This is fine.
        return null
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        val inputArea = contentView.findViewById<FrameLayout>(android.R.id.inputArea)
        inputArea.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(
        win: Window,
        isFullscreen: Boolean,
        isCandidatesOnly: Boolean,
    ) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onStartInput(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        composingText = ""
        Timber.d("onStartInput: restarting=$restarting")
        val isNullType = attribute.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        hasNoEditableField = isNullType
        postRimeJob {
            if (restarting) {
                // when input restarts in the same editor, clear previous composition
                clearComposition()
            }
            setNullInputType(isNullType)
        }
    }

    private val inlineSuggestions by prefs.general.inlineSuggestions

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!inlineSuggestions || !inputDeviceManager.useVirtualKeyboard) return null
        return InlineSuggestions.createRequest(this, themeScope.colors)
    }

    @SuppressLint("NewApi")
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!inputDeviceManager.useVirtualKeyboard) return false
        return inputView?.handleInlineSuggestions(response) == true
    }

    override fun onStartInputView(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        Timber.d("onStartInputView: restarting=$restarting")
        TouchpadLog.resetCounts()
        TouchpadLog.line(
            "INPUT start pkg=${attribute.packageName} inputType=0x${attribute.inputType.toString(16)}",
        )
        InputFeedbackManager.startInput()
        postRimeJob {
            updateRimeOption(this)
        }
        val (useVirtualKeyboard, useCandidatesView) =
            inputDeviceManager.evaluateOnStartInputView(attribute, this)
        if (useVirtualKeyboard) {
            inputView?.startInput(attribute, restarting)
        }
        if (useCandidatesView) {
            if (currentInputConnection?.monitorCursorAnchor() != true) {
                if (!decorLocationUpdated) {
                    updateDecorLocation()
                }
                // anchor CandidatesView to bottom-left corner in case InputConnection does not
                // support monitoring CursorAnchorInfo
                candidatesView?.updateCursorAnchor(contentSize)
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        // The decisive number: if this stays 0 across a session where the key surface was
        // swiped, the events never reach the input method and no amount of tuning the
        // recogniser will help.
        TouchpadLog.line(
            "INPUT end   motionEvents=${TouchpadLog.motionCount} " +
                "fromTouchpad=${TouchpadLog.touchpadCount}",
        )
        // Get it on disk now: the input method process can be killed at any point after this.
        TouchpadLog.flush()
        decorLocationUpdated = false
        inputView?.dismissCandidateActionMenu()
        candidatesView?.dismissCandidateActionMenu()
        inputDeviceManager.onFinishInputView()
        currentInputConnection?.apply {
            finishComposingText()
            monitorCursorAnchor(false)
        }
        composingText = ""
        postRimeJob {
            clearComposition()
        }
        InputFeedbackManager.finishInput()
    }

    fun commitText(text: String) {
        val ic = currentInputConnection ?: return

        // when composing text equals commit content, finish composing text as-is
        if (composingText.isNotEmpty() && composingText == text) {
            ic.finishComposingText()
        } else {
            ic.commitText(text, 1)
        }
        lastCommittedText = text
        composingText = ""
        InputFeedbackManager.textCommitSpeak(text)
    }

    /**
     * Constructs a meta state integer flag which can be used for setting the `metaState` field when sending a KeyEvent
     * to the input connection. If this method is called without a meta modifier set to true, the default value `0` is
     * returned.
     *
     * @param ctrl Set to true to enable the CTRL meta modifier. Defaults to false.
     * @param alt Set to true to enable the ALT meta modifier. Defaults to false.
     * @param shift Set to true to enable the SHIFT meta modifier. Defaults to false.
     *
     * @return An integer containing all meta flags passed and formatted for use in a [KeyEvent].
     */
    fun meta(
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
        sym: Boolean = false,
    ): Int {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (meta) metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        if (sym) metaState = metaState or KeyEvent.META_SYM_ON
        return metaState
    }

    private fun sendDownKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    private fun sendUpKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    /**
     * Same as [InputMethodService.sendDownUpKeyEvents] but also allows to set meta state.
     *
     * @param keyEventCode The key code to send, use a key code defined in Android's [KeyEvent].
     * @param metaState Flags indicating which meta keys are currently pressed.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun sendDownUpKeyEvent(
        keyEventCode: Int,
        metaState: Int = meta(),
    ): Boolean {
        val eventTime = SystemClock.uptimeMillis()
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        }
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        if (metaState and KeyEvent.META_META_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT)
        }
        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SYM)
        }
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SYM)
        }
        if (metaState and KeyEvent.META_META_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        }
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        }
        return true
    }

    /**
     * Where flick typing actually gets its events.
     *
     * [onGenericMotionEvent] is the documented hook and never fires for the Titan's key
     * surface -- measured across 20 input sessions: 0 events there, 245 here, all
     * `source=0x100008` with proper DOWN/MOVE/UP. Reading the framework's dispatch path
     * did not explain the difference, so the route that demonstrably works is used.
     *
     * The listener never consumes, so whatever else the surface drives keeps working.
     */
    private fun listenForFlicks(view: View) {
        view.setOnGenericMotionListener { _, event ->
            touchpadGesture.onMotionEvent(event)
            false
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        touchpadGesture.onMotionEvent(event) || super.onGenericMotionEvent(event)

    /**
     * Flick typing: a swipe up on the key surface picks the candidate sitting above that
     * key, mirroring how the physical row maps onto the candidate row. Horizontal swipes
     * page through candidates or drop the last syllable. Without a composition in flight
     * there is nothing to act on, so the gesture is ignored rather than guessed at.
     */
    private fun onTouchpadSwipe(swipe: TouchpadGesture.Swipe) {
        when (swipe.direction) {
            // Down is the one gesture that is useful with nothing composed, so it is
            // handled before the composing check.
            TouchpadGesture.Direction.Down -> {
                TouchpadLog.line("  SWIPE Down -> backspace")
                postRimeJob { processKey(KeyValue(RimeKeyMapping.RimeKey_BackSpace), KeyModifiers.Empty) }
            }
            TouchpadGesture.Direction.Up -> {
                if (!isComposing) {
                    TouchpadLog.line("  SWIPE Up dropped: rime is not composing")
                    return
                }
                // Confirms whatever is highlighted -- the sideways swipe moves the
                // highlight, this commits it. Aiming at a particular candidate instead
                // was tried and did not work: the key surface gives no feedback about
                // where a finger is, so in practice every swipe landed in the same
                // comfortable spot near the middle (seven of eight measured swipes fell
                // between 53% and 62% across) and picked whatever happened to be there.
                //
                // Space is how rime commits its highlighted candidate, so it needs no
                // index: rime already knows which one that is, and no reconstruction here
                // can be more correct than that.
                TouchpadLog.line("  SWIPE Up -> commit highlighted candidate")
                postRimeJob { processKey(KeyValue(' '.code), KeyModifiers.Empty) }
            }
            // Sideways swipes are handled continuously in onTouchpadScroll; reaching here
            // means the travel was too small to make a step.
            TouchpadGesture.Direction.Left, TouchpadGesture.Direction.Right ->
                TouchpadLog.line("  SWIPE ${swipe.direction} ignored: under one step")
        }
    }

    /**
     * Moves the highlighted candidate as the finger travels, one per step.
     *
     * Rime moves its selection with Up/Down (Left/Right walk the cursor through the
     * composition instead -- verified on the device). Steps are applied in one batch so a
     * fast sweep does not queue a job per candidate.
     */
    private fun onTouchpadScroll(steps: Int) {
        if (!isComposing) return
        val key =
            if (steps > 0) RimeKeyMapping.RimeKey_Down else RimeKeyMapping.RimeKey_Up
        val count = kotlin.math.abs(steps)
        TouchpadLog.line("  SCROLL ${if (steps > 0) "+" else "-"}$count")
        postRimeJob {
            repeat(count) { processKey(KeyValue(key), KeyModifiers.Empty) }
        }
    }

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        if (passThroughToApp()) return false
        if (commitAsciiPunctuation(event)) return true
        val keyVal = KeyValue.fromKeyEvent(event)
        if (keyVal.value != RimeKeyMapping.RimeKey_VoidSymbol) {
            val modifiers = mergeLatchedModifiers(event, KeyModifiers.fromKeyEvent(event))
            postRimeJob {
                processKey(keyVal, modifiers, isVirtual = false)
            }
            return true
        }
        Timber.d("Skipped KeyEvent: $event")
        return false
    }

    /**
     * True while the focused editor reports `TYPE_NULL`, i.e. there is no editable
     * field to compose into -- a list view, a launcher, a game, a terminal.
     */
    private var hasNoEditableField = true

    /**
     * Lets a hardware key reach the app untouched.
     *
     * [forwardKeyEvent] otherwise claims every key that maps to a rime key value, so a
     * letter pressed with no editable field focused is swallowed by the composer and the
     * app never sees it. That breaks single-letter shortcuts (j/k to move through a list,
     * WASD in a game) and any app-defined combination built on plain letters.
     *
     * `TYPE_NULL` is also how terminals and other editors say "send me raw key events
     * rather than committed text", so passing through is what they expect anyway.
     *
     * A composition still in flight keeps priority, so a focus change mid-word does not
     * strand the preedit.
     */
    private fun passThroughToApp(): Boolean = hasNoEditableField && !isComposing

    /**
     * Whether rime currently holds an unconfirmed composition.
     *
     * Not [composingText]: that only tracks the *inline* preedit written into the editor,
     * and `inline_preedit_mode` defaults to DISABLE, so it stays empty the whole time a
     * candidate list is up. Rime's own status is the only signal that survives that
     * setting.
     */
    private val isComposing: Boolean
        get() = rime.run { statusCached }.isComposing

    /**
     * Claims the Shift+Alt layer, which the Titan keyboard leaves empty: its key
     * character map yields no character there, so nothing would reach the editor.
     * We use it for ASCII punctuation, letting plain Alt+<key> keep going through
     * rime's punctuator (which produces the full-width form in Chinese mode).
     */
    private fun commitAsciiPunctuation(event: KeyEvent): Boolean {
        if (!isShiftAltOnly(event.metaState)) return false
        val text = ASCII_PUNCTUATION_LAYER[event.keyCode] ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            postRimeJob { clearComposition() }
            currentInputConnection?.commitText(text, 1)
        }
        return true
    }

    private fun isShiftAltOnly(metaState: Int): Boolean {
        val wanted = KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON
        val unwanted = KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON or KeyEvent.META_SYM_ON
        return metaState and wanted == wanted && metaState and unwanted == 0
    }

    /**
     * Applies modifiers latched on the on-screen keyboard (e.g. its Ctrl key) to a
     * hardware key event, then releases the latch. Without this the two input paths
     * are independent and the on-screen Ctrl only affects on-screen keys.
     */
    private fun mergeLatchedModifiers(
        event: KeyEvent,
        modifiers: KeyModifiers,
    ): KeyModifiers {
        val keyboard = KeyboardWindow.currentKeyboardOrNull ?: return modifiers
        val latched = keyboard.modifier
        if (latched == 0) return modifiers
        if (KeyAction.getModifierKeyOnMask(event.keyCode) != 0) return modifiers
        val merged = KeyModifiers.of(modifiers.toInt() or KeyModifiers.fromMetaState(latched).toInt())
        if (event.action == KeyEvent.ACTION_UP && keyboard.refreshModifier()) {
            KeyboardWindow.invalidateKeys?.invoke()
        }
        return merged
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        // With the system's Scroll/Cursor Assistant enabled, the key surface is swallowed by
        // the system and reaches us as bare directionless pulses instead of motion events.
        // Seeing these in the log means that setting is still on, not that our code is wrong.
        if (keyCode !in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z && keyCode > KeyEvent.KEYCODE_PROFILE_SWITCH) {
            TouchpadLog.line("KEY unusual keyCode=$keyCode action=${event.action} src=0x${event.source.toString(16)}")
        }
        if (inputDeviceManager.evaluateOnKeyDown(event, this)) {
            decorLocationUpdated = false
            forceShowSelf()
        }
        return forwardKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = forwardKeyEvent(event) || super.onKeyUp(keyCode, event)

    // Added in API level 14, deprecated in 29
    // it's needed because editors still use it even on API 36
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        inputDeviceManager.evaluateOnViewClicked(this)
    }

    @RequiresApi(34)
    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        inputDeviceManager.evaluateOnUpdateEditorToolType(toolType, this)
    }

    fun switchToPrevIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                @Suppress("DEPRECATION")
                inputMethodManager.switchToLastInputMethod(window.window!!.attributes.token)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the previous IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    fun switchToNextIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToNextInputMethod(false)
            } else {
                @Suppress("DEPRECATION")
                inputMethodManager.switchToNextInputMethod(window.window!!.attributes.token, false)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the next IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    fun shareText(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val ic = currentInputConnection ?: return false
            val cs = ic.getSelectedText(0)
            if (cs == null) ic.performContextMenuAction(android.R.id.selectAll)
            return ic.performContextMenuAction(android.R.id.shareText)
        }
        return false
    }

    /** 編輯操作 */
    fun hookKeyboard(
        code: Int,
        mask: Int,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        // 没按下 Ctrl 键
        if (mask != KeyEvent.META_CTRL_ON) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (prefs.keyboard.hookCtrlZY.getValue()) {
                when (code) {
                    KeyEvent.KEYCODE_Y -> return ic.performContextMenuAction(android.R.id.redo)
                    KeyEvent.KEYCODE_Z -> return ic.performContextMenuAction(android.R.id.undo)
                }
            }
        }

        when (code) {
            KeyEvent.KEYCODE_A -> {
                // 全选
                return if (prefs.keyboard.hookCtrlA.getValue()) {
                    ic.performContextMenuAction(android.R.id.selectAll)
                } else {
                    false
                }
            }

            KeyEvent.KEYCODE_X -> {
                // 剪切
                if (prefs.keyboard.hookCtrlCV.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionStart != et.selectionEnd) return ic.performContextMenuAction(android.R.id.cut)
                    }
                }
                Timber.w("hookKeyboard cut fail")
                return false
            }

            KeyEvent.KEYCODE_C -> {
                // 复制
                if (prefs.keyboard.hookCtrlCV.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionStart != et.selectionEnd) {
                            ic.performContextMenuAction(android.R.id.copy).also { result ->
                                if (result) {
                                    clearTextSelection()
                                }
                                return result
                            }
                        }
                    }
                }
                Timber.w("hookKeyboard copy fail")
                return false
            }

            KeyEvent.KEYCODE_V -> {
                // 粘贴
                if (prefs.keyboard.hookCtrlCV.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et == null) {
                        Timber.d("hookKeyboard paste, et == null, try commitText")
                        val clipboardText = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)
                        if (ic.commitText(clipboardText, 1)) {
                            return true
                        }
                    } else if (ic.performContextMenuAction(android.R.id.paste)) {
                        return true
                    }
                    Timber.w("hookKeyboard paste fail")
                }
                return false
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (prefs.keyboard.hookCtrlLR.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = et.text.findSectionFrom(et.startOffset + et.selectionEnd)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (prefs.keyboard.hookCtrlLR.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = et.text.findSectionFrom(et.startOffset + et.selectionStart, true)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
        }
        return false
    }

    fun clearTextSelection() {
        val ic = currentInputConnection ?: return
        val etr = ExtractedTextRequest().apply { token = 0 }
        val et = currentInputConnection.getExtractedText(etr, 0)
        et?.let {
            if (it.selectionStart != it.selectionEnd) {
                ic.setSelection(it.selectionEnd, it.selectionEnd)
            }
        }
    }

    internal fun updateComposingText(text: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (composingText.isNotEmpty() || text.isNotEmpty()) {
            if (!ic.getSelectedText(0).isNullOrEmpty()) {
                ic.deleteSurroundingText(1, 0)
            }
            ic.setComposingText(text, 1)
            if (text.isEmpty()) {
                ic.finishComposingText()
            }
        }
        composingText = text
        ic.endBatchEdit()
    }

    fun getActiveText(type: Int): String {
        val rimeComposition = rime.run { compositionCached }
        val selected = currentInputConnection?.getSelectedText(0)?.toString()
        val commitPreview = rimeComposition.commitTextPreview
        val preedit = rimeComposition.preedit ?: ""
        val beforeCursor = getTextAroundCursor(1024, before = true) ?: ""
        val afterCursor = getTextAroundCursor(before = false) ?: ""
        val lastCommitted = lastCommittedText

        return sequenceOf(
            when (type) {
                2 -> preedit
                3 -> selected
                4 -> beforeCursor
                1 -> lastCommitted
                else -> null
            },
            commitPreview,
            selected,
            lastCommitted,
            beforeCursor,
            afterCursor,
        )
            .firstOrNull { it?.isNotEmpty() == true } ?: ""
    }

    private fun getTextAroundCursor(
        initialStep: Int = 1024,
        before: Boolean,
    ): String? {
        val ic = currentInputConnection ?: return null
        var step = initialStep
        while (true) {
            val text = (if (before) ic.getTextBeforeCursor(step, 0) else ic.getTextAfterCursor(step, 0)) ?: return ""
            if (text.length < step) return text.toString()
            step *= 2
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private var showingDialog: Dialog? = null

    fun showDialog(dialog: Dialog) {
        showingDialog?.dismiss()
        dialog.window?.also {
            it.attributes.apply {
                token = decorView.windowToken
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            it.addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            )
            it.setDimAmount(styledFloat(android.R.attr.backgroundDimAmount))
        }
        dialog.setOnDismissListener {
            showingDialog = null
        }
        dialog.show()
        showingDialog = dialog
    }
}

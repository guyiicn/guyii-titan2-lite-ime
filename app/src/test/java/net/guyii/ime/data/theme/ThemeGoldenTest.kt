/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.guyii.ime.data.theme

import net.guyii.ime.data.theme.model.KeyActionToken
import net.guyii.ime.data.theme.model.TextKeyboard
import net.guyii.ime.ime.keyboard.KeyBehavior
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Golden tests for the shipped themes: parse + decode, key fields compared against the
 * source files value by value.
 *
 * - tongwenfeng.trime.yaml: no librime DSL; decodes as-is, covering anchors/aliases
 *   (style values via `*hgap`/`*jpgd4`/...) and flow mappings (`preset_keys`/`keys`).
 * - trime.yaml: two `__include` entries (librime DSL), expanded by [ThemeDslExpander] before
 *   decoding: `letter` inherits /preset_keyboards/default, `scj6` is a copy of cangjie5.
 */
private val PLAIN_VI_KEYS = setOf("Escape", "BackSpace", "guyii_back_row", "guyii_page_pc")

class ThemeGoldenTest :
    BehaviorSpec({
        Given("the built-in tongwenfeng.trime.yaml") {
            val theme = ThemeTestSupport.decodeBuiltinTheme("tongwenfeng.trime.yaml")

            When("the whole file is decoded") {
                Then("theme header and style scalars are preserved") {
                    theme.name shouldBe "标准"
                    val style = theme.generalStyle
                    style.autoCaps shouldBe false
                    style.candidateTextSize shouldBe 18f
                    style.keyTextSize shouldBe 24f
                    style.keyWidth shouldBe 10f
                    style.keyboardHeight shouldBe 250
                    style.keyboardHeightLand shouldBe 200
                }

                Then("style values referenced through anchors/aliases resolve to the anchored values") {
                    // File defines height: {4: &jpgd4 48}, 6: &hgap 4, 7: &sgap 12, 1: &round1 6;
                    // style references them via *jpgd4 / *hgap / *sgap / *round1.
                    val style = theme.generalStyle
                    style.keyHeight shouldBe 48
                    style.horizontalGap shouldBe 4
                    style.verticalGap shouldBe 12
                    style.roundCorner shouldBe 6f
                }

                Then("enter labels are decoded") {
                    val enterLabel = theme.generalStyle.enterLabel
                    enterLabel.go shouldBe "前往"
                    enterLabel.done shouldBe "完成"
                    enterLabel.default shouldBe "Enter"
                }

                Then("all 50 preset keyboards are decoded") {
                    theme.presetKeyboards.size shouldBe 50
                    theme.presetKeyboards shouldContainKey "default"
                    theme.presetKeyboards shouldContainKey "letter"
                    theme.presetKeyboards shouldContainKey "number"
                    theme.presetKeyboards shouldContainKey "bqrw1"
                }

                Then("the default keyboard decodes keys incl. inline flow mappings and per-key colors") {
                    val keyboard = theme.presetKeyboards.getValue("default")
                    keyboard.name shouldBe "26键默认布局"
                    keyboard.author shouldBe "暖暖"
                    keyboard.width shouldBe 10f
                    keyboard.asciiMode shouldBe false
                    keyboard.keys.size shouldBe 37
                    val firstKey = keyboard.keys.first()
                    firstKey.behaviors[KeyBehavior.CLICK] shouldBe KeyActionToken.Plain("q")
                    firstKey.behaviors[KeyBehavior.LONG_CLICK] shouldBe KeyActionToken.Plain("1")
                    firstKey.keyBackColor shouldBe "bh1"
                    firstKey.keyTextColor shouldBe "th1"
                }

                Then("all 46 color schemes are decoded, with the default scheme intact") {
                    theme.colorSchemes.size shouldBe 46
                    val defaultScheme = theme.colorSchemes.first { it.id == "default" }
                    defaultScheme.colors["name"] shouldBe "标准配色！"
                    defaultScheme.colors["dark_scheme"] shouldBe "steam"
                }

                Then("fallback colors override table is decoded") {
                    theme.fallbackColors shouldBe mapOf("candidate_text_color" to "text_color")
                }

                Then("preset keys with inline maps are decoded") {
                    theme.presetKeys shouldContainKey "BRIGHTNESS_DOWN"
                    val brightnessDown = theme.presetKeys.getValue("BRIGHTNESS_DOWN")
                    brightnessDown.label shouldBe "亮度-"
                    brightnessDown.send shouldBe "BRIGHTNESS_DOWN"
                }
            }
        }

        Given("the built-in trime.yaml (with its two __include entries expanded)") {
            val theme = ThemeTestSupport.decodeBuiltinTheme("trime.yaml")

            When("the whole file is decoded") {
                Then("theme header and style scalars are preserved") {
                    theme.name shouldBe "默认"
                    val style = theme.generalStyle
                    style.candidateTextSize shouldBe 22f
                    style.keyHeight shouldBe 44
                    style.horizontalGap shouldBe 1
                }

                Then("color schemes and preset keys are decoded") {
                    theme.colorSchemes.size shouldBe 37
                    theme.presetKeys.size shouldBe 120
                    val brightnessDown = theme.presetKeys.getValue("BRIGHTNESS_DOWN")
                    brightnessDown.label shouldBe "亮度-"
                    brightnessDown.send shouldBe "BRIGHTNESS_DOWN"
                }

                Then("all 23 plain keyboards are decoded with their keys") {
                    theme.presetKeyboards.size shouldBe 23
                    theme.presetKeyboards shouldContainKey "default"
                    theme.presetKeyboards shouldContainKey "qwerty0"
                    theme.presetKeyboards shouldContainKey "cangjie5"
                    theme.presetKeyboards shouldContainKey "array30"

                    // guyii additions. No `rime_ice` here: this branch has no standalone
                    // function row -- with no physical keyboard the full keyboard is the
                    // default, and Esc/Tab/Ctrl/arrows live on the symbol page instead.
                    theme.presetKeyboards shouldNotContainKey "rime_ice"
                    theme.presetKeyboards shouldContainKey "guyii_sym1"
                    theme.presetKeyboards["guyii_sym1"]!!.keys.size shouldBe 45
                    theme.presetKeyboards shouldContainKey "guyii_num"
                    theme.presetKeyboards shouldContainKey "guyii_ascii"

                    // The vi page: every key commits or sends, none is a plain character
                    // key, because a plain one would feed rime and `i` would start a
                    // pinyin instead of entering insert mode.
                    val vi = theme.presetKeyboards.getValue("guyii_vi")
                    vi.keys.size shouldBe 35
                    vi.lock shouldBe true
                    vi.keys.forEach { key ->
                        val click = key.behaviors[KeyBehavior.CLICK]
                        withClue("a vi key feeds rime: $click") {
                            when (click) {
                                is KeyActionToken.Inline ->
                                    (click.token.commit != null || click.token.text != null) shouldBe true
                                // The only plain tokens allowed are the ones that are not
                                // characters at all: Escape, BackSpace and the page keys.
                                is KeyActionToken.Plain ->
                                    (click.token in PLAIN_VI_KEYS) shouldBe true
                                null -> error("vi key with no click")
                            }
                        }
                    }

                    // The escape hatch for a broken hardware keyboard: a full soft
                    // keyboard, locked so it survives a focus change.
                    val full = theme.presetKeyboards.getValue("guyii_full")
                    full.keys.size shouldBe 46
                    full.lock shouldBe true

                    val default = theme.presetKeyboards.getValue("default")
                    default.name shouldBe "默认40键"
                    default.width shouldBe 10f
                    default.height shouldBe 44f
                    default.lock shouldBe true
                    default.asciiMode shouldBe false
                    default.keys.size shouldBe 47
                    default.keys.first().behaviors[KeyBehavior.CLICK] shouldBe
                        KeyActionToken.Plain("1")
                    default.keys.first().behaviors[KeyBehavior.LONG_CLICK] shouldBe
                        KeyActionToken.Plain("!")

                    val qwerty0 = theme.presetKeyboards.getValue("qwerty0")
                    qwerty0.labelTransform shouldBe TextKeyboard.LabelTransform.UPPERCASE
                }

                Then("the __include 'letter' keyboard inherits the default keyboard and overrides its own keys") {
                    val letter = theme.presetKeyboards.getValue("letter")
                    val default = theme.presetKeyboards.getValue("default")
                    letter.asciiMode shouldBe true
                    letter.resetAsciiMode shouldBe true
                    letter.lock shouldBe false
                    letter.name shouldBe default.name
                    letter.width shouldBe default.width
                    letter.height shouldBe default.height
                    letter.keys.size shouldBe default.keys.size
                    letter.keys.first().behaviors[KeyBehavior.CLICK] shouldBe
                        default.keys.first().behaviors[KeyBehavior.CLICK]
                }

                Then("the pure __include 'scj6' keyboard equals cangjie5") {
                    theme.presetKeyboards.getValue("scj6") shouldBe
                        theme.presetKeyboards.getValue("cangjie5")
                }

                Then("every guyii keyboard has a way back to the letter keyboard") {
                    // A keyboard reachable from the function row but with no path back strands
                    // the user: upstream's `default` is `lock: true`, so in an app that never
                    // changes focus (a terminal) onStartInput never runs to re-match it.
                    // That is exactly what `Keyboard_letter` used to do -- it selected
                    // `default`, which carries no key returning to the function row.
                    val selectOf = { keyboardId: String ->
                        theme.presetKeyboards.getValue(keyboardId).keys
                            .flatMap { it.behaviors.values }
                            .filterIsInstance<KeyActionToken.Plain>()
                            .mapNotNull { theme.presetKeys[it.token]?.select }
                            .filter { it.isNotEmpty() }
                    }
                    val guyiiKeyboards =
                        listOf(
                            "guyii_full", "guyii_sym1", "guyii_vi",
                            "guyii_ascii", "guyii_num",
                        )

                    // Nothing in the guyii set may lead to a keyboard outside it; ".default"
                    // is allowed because it re-matches against the current schema.
                    guyiiKeyboards.forEach { id ->
                        selectOf(id).forEach { target ->
                            withClue("$id selects $target") {
                                (target in guyiiKeyboards || target.startsWith(".")) shouldBe true
                            }
                        }
                    }

                    // And every one of them reaches the function row without a focus change.
                    val reachesLetters = { id: String ->
                        generateSequence(setOf(id)) { seen ->
                            (seen + seen.flatMap(selectOf).filter { it in guyiiKeyboards })
                                .takeIf { it != seen }
                        }.last()
                    }
                    guyiiKeyboards.forEach { id ->
                        withClue("$id cannot reach guyii_full") {
                            reachesLetters(id) shouldContain "guyii_full"
                        }
                    }
                }

                Then("every keyboard decodes a non-empty key set") {
                    theme.presetKeyboards.forEach { (id, keyboard) ->
                        keyboard.keys shouldNotBe emptyList<TextKeyboard.TextKey>()
                    }
                }
            }
        }
    })

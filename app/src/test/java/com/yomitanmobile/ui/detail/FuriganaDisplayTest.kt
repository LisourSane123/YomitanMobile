package com.yomitanmobile.ui.detail

import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yomitanmobile.domain.model.ExamplePair
import com.yomitanmobile.domain.model.FuriganaSegment
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the actual Compose furigana renderer on the JVM (Robolectric) so the
 * tap-to-reveal interaction is verified without a device — the reported
 * "tapping shows nothing" is an interaction/render question, not a data one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FuriganaDisplayTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Composable
    private fun themed(content: @Composable () -> Unit) {
        MaterialTheme { content() }
    }

    private fun visibleCount(text: String): Int =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun tappingKanjiRevealsReading() {
        composeRule.setContent {
            themed {
                FuriganaWord(
                    segment = FuriganaSegment("私", "わたし"),
                    fontSizeSp = 16,
                    color = Color.Black
                )
            }
        }

        assertEquals("reading hidden initially", 0, visibleCount("わたし"))

        composeRule.onNodeWithText("私").performClick()

        composeRule.onNodeWithText("わたし").assertIsDisplayed()
    }

    @Test
    fun sentenceRevealsTappedWordOnly() {
        val example = ExamplePair(
            jp = "私は本を読む。",
            en = "I read a book.",
            segments = listOf(
                FuriganaSegment("私", "わたし"),
                FuriganaSegment("は", ""),
                FuriganaSegment("本", "ほん"),
                FuriganaSegment("を", ""),
                FuriganaSegment("読", "よ"),
                FuriganaSegment("む。", "")
            )
        )
        composeRule.setContent {
            themed {
                FuriganaSentence(example = example, fontSizeSp = 16, color = Color.Black)
            }
        }

        assertEquals("readings hidden initially", 0, visibleCount("ほん"))

        composeRule.onNodeWithText("本").performClick()
        composeRule.onNodeWithText("ほん").assertIsDisplayed()

        // Reveal is per-word: 私's reading stays hidden.
        assertEquals("other words stay hidden", 0, visibleCount("わたし"))
    }

    @Test
    fun revealWorksInsideVerticalScroll() {
        // The real detail screen nests the sentence in a verticalScroll; verify
        // the scroll container doesn't swallow the tap.
        val example = ExamplePair(
            jp = "水を飲む。",
            en = "Drink water.",
            segments = listOf(
                FuriganaSegment("水", "みず"),
                FuriganaSegment("を", ""),
                FuriganaSegment("飲", "の"),
                FuriganaSegment("む。", "")
            )
        )
        composeRule.setContent {
            themed {
                androidx.compose.foundation.layout.Column(
                    modifier = androidx.compose.ui.Modifier.verticalScroll(
                        androidx.compose.foundation.rememberScrollState()
                    )
                ) {
                    FuriganaSentence(example = example, fontSizeSp = 14, color = Color.Black)
                }
            }
        }

        assertEquals("hidden initially", 0, visibleCount("みず"))
        composeRule.onNodeWithText("水").performClick()
        composeRule.onNodeWithText("みず").assertIsDisplayed()
    }

    @Test
    fun fallbackMapPathRevealsFurigana() {
        // The no-ruby path the app relies on for old/plain imports: the
        // ExamplePair has NO segments, and readings come from the
        // generatedFurigana map keyed by the plain jp text.
        val jp = "水を飲む。"
        val example = ExamplePair(jp = jp, en = "Drink water.", segments = emptyList())
        val generated = mapOf(
            jp to listOf(
                FuriganaSegment("水", "みず"),
                FuriganaSegment("を", ""),
                FuriganaSegment("飲", "の"),
                FuriganaSegment("む。", "")
            )
        )
        composeRule.setContent {
            themed {
                FuriganaSentence(
                    example = example,
                    fontSizeSp = 16,
                    color = Color.Black,
                    generatedFurigana = generated
                )
            }
        }

        assertEquals("reading hidden initially", 0, visibleCount("みず"))
        composeRule.onNodeWithText("水").performClick()
        composeRule.onNodeWithText("みず").assertIsDisplayed()
    }
}

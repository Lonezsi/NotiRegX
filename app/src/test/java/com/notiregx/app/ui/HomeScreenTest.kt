package com.notiregx.app.ui

import android.graphics.drawable.ColorDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.notiregx.app.model.MatchField
import com.notiregx.app.model.RuleAdvice
import com.notiregx.app.model.Suggestion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.notiregx.app.model.Rule as NRule

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun lateSuggestionsShowOnScreenNotAboveIt() {
        // Robolectric has no launcher icons; give the card's app one so AppIcon has something to draw.
        shadowOf(RuntimeEnvironment.getApplication().packageManager)
            .setApplicationIcon("com.example.chat", ColorDrawable(0xFF336699.toInt()))
        val rules = (1..30).map { NRule(id = "r$it", name = "Rule $it") }
        var advice by mutableStateOf(emptyList<RuleAdvice>())
        compose.setContent {
            MaterialTheme {
                HomeScreen(
                    rules = rules, hasAccess = true, canPost = true, filteringEnabled = true,
                    onGrantAccess = {}, onGrantPost = {}, listenerStopped = false, onRestartListener = {},
                    onEdit = {}, onMoveTo = { _, _ -> }, onToggle = { _, _ -> }, onOpenArchive = {},
                    advice = advice, onUseAdvice = {}, onIgnoreAdvice = {},
                )
            }
        }
        compose.onNodeWithText("Rule 1").assertIsDisplayed()

        // Suggestions arrive a moment after the screen opened.
        advice = listOf(
            RuleAdvice(
                Suggestion(label = "Title like x", field = MatchField.TITLE, regex = "x", matches = 5, total = 6),
                "com.example.chat", "Example", 5, 0,
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText("Suggested for you").assertIsDisplayed()
    }
}

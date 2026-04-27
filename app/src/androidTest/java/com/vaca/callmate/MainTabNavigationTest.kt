package com.vaca.callmate

import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 自动点击底部导航三个 Tab（与 [com.vaca.callmate.ui.screens.MainTabView] 图标 contentDescription 对齐）。
 * 中英文界面均可：接电话/Receive、AI分身/AI Avatar、打电话/Call Out。
 */
@RunWith(AndroidJUnit4::class)
class MainTabNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun SemanticsNodeInteractionCollection.hasAnyNode(): Boolean =
        fetchSemanticsNodes().isNotEmpty()

    @Test
    fun bottomNav_clicksEachTab_inOrder() {
        val receive = hasContentDescription("接电话") or hasContentDescription("Receive")
        val avatar = hasContentDescription("AI分身") or hasContentDescription("AI Avatar")
        val outbound = hasContentDescription("打电话") or hasContentDescription("Call Out")
        val agreeLegal = hasText("同意") or hasText("Agree")

        composeRule.waitUntil(timeoutMillis = 45_000) {
            if (composeRule.onAllNodes(receive).hasAnyNode()) return@waitUntil true
            if (composeRule.onAllNodes(agreeLegal).hasAnyNode()) {
                composeRule.onAllNodes(agreeLegal).onFirst().performClick()
            }
            false
        }

        composeRule.onAllNodes(receive).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(avatar).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(outbound).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(receive).onFirst().performClick()
        composeRule.waitForIdle()
    }
}

package com.vaca.callmate

import android.util.Log
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PERF_LOG = "CallMateScrollPerf"

/**
 * 自动测试：进入「接电话」首页后对 LazyColumn 连续上滑，用于配合 gfxinfo / 卡顿分析。
 * 运行（需连接真机或模拟器）：
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vaca.callmate.DashboardScrollAutomationTest`
 * 更快：先 `./gradlew :app:installDebug` 装一次，再跑上面命令可跳过部分编译；或用手动打开 App + `./script/quick_scroll_perf.sh` 纯 adb 滑动。
 */
@RunWith(AndroidJUnit4::class)
class DashboardScrollAutomationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun SemanticsNodeInteractionCollection.hasAnyNode(): Boolean =
        fetchSemanticsNodes().isNotEmpty()

    @Test
    fun dashboard_receiveTab_repeatedSwipeUp() {
        val receive = hasContentDescription("接电话") or hasContentDescription("Receive")
        val agreeLegal = hasText("同意") or hasText("Agree")

        composeRule.waitUntil(timeoutMillis = 45_000) {
            if (composeRule.onAllNodes(receive).hasAnyNode()) return@waitUntil true
            if (composeRule.onAllNodes(agreeLegal).hasAnyNode()) {
                composeRule.onAllNodes(agreeLegal).onFirst().performClick()
            }
            false
        }

        composeRule.onAllNodes(receive).onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodes(hasTestTag("calls_dashboard_lazy")).hasAnyNode()
        }

        val t0 = System.currentTimeMillis()
        repeat(SCROLL_SWIPES) {
            composeRule.onNodeWithTag("calls_dashboard_lazy").performTouchInput {
                swipeUp()
            }
            // 勿用 waitForIdle()：BLE/Flow 持续更新时主线程几乎永不 idle，会拖到超时或极慢。
            Thread.sleep(48)
        }
        val elapsed = System.currentTimeMillis() - t0
        Log.i(PERF_LOG, "swipes=$SCROLL_SWIPES durationMs=$elapsed avgMsPerSwipe=${elapsed / SCROLL_SWIPES}")
    }

    companion object {
        private const val SCROLL_SWIPES = 12
    }
}

package com.example.subtitleplayer

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 冒烟 UI 测试（GitHub Actions 模拟器跑 connectedDebugAndroidTest）。
 *
 * 注意：headless 模拟器窗口无焦点，Espresso onView 交互（点击）会 RootViewWithoutFocusException。
 * 因此交互统一走两种不依赖窗口焦点的方式：
 *  - onActivity 内直接 performClick()（绕开 focus/idle）
 *  - UiAutomator 查跨窗口的对话框内容（accessibility 树）
 *
 * 覆盖：启动、底部导航切页、设置对话框、音乐库布局切换、1.25 新控件存在性。
 * 媒体播放/SAF/通知/闹钟/桌面歌词等系统级场景模拟器覆盖不到，靠真机回归。
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun launch(block: (android.app.Activity) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { block(it) }
        }
    }

    /** 遍历 view tree 找指定文本的 TextView/Button 并点击（导航 tab 是动态构建无 id）。 */
    private fun clickByText(root: View, text: String): Boolean {
        if (root is TextView && root.text?.toString() == text) {
            root.performClick()
            return true
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (clickByText(root.getChildAt(i), text)) return true
            }
        }
        return false
    }

    @Test
    fun app启动不崩溃且主视图存在() {
        launch { activity ->
            assertNotNull("主视图不存在", activity.findViewById<View>(android.R.id.content))
        }
    }

    @Test
    fun 底部导航切换到音乐库页() {
        launch { activity ->
            assertTrue(
                "找不到音乐库导航项",
                clickByText(activity.window.decorView, "音乐库")
            )
            val list = activity.findViewById<View>(R.id.recyclerPlaylists)
            assertTrue("音乐库列表未显示", list.visibility == View.VISIBLE)
        }
    }

    @Test
    fun 设置对话框打开并可切换树形布局() {
        launch { activity ->
            activity.findViewById<View>(R.id.btnSettings).performClick()
        }
        // UiAutomator 跨窗口断言对话框内容
        assertTrue("设置对话框未打开", device.wait(Until.hasObject(By.text("快进退时长")), 5000))
        device.findObject(By.text("树形目录")).click()
        device.findObject(By.text("确定")).click()
    }

    @Test
    fun 播放页快进退倍速按钮存在() {
        launch { activity ->
            assertNotNull("快退按钮缺失", activity.findViewById<View>(R.id.btnSeekBack))
            assertNotNull("快进按钮缺失", activity.findViewById<View>(R.id.btnSeekForward))
            assertNotNull("倍速按钮缺失", activity.findViewById<View>(R.id.btnSpeed))
        }
    }

    @Test
    fun 设置对话框快进退时长选项存在() {
        launch { activity ->
            activity.findViewById<View>(R.id.btnSettings).performClick()
        }
        assertTrue("设置对话框未打开", device.wait(Until.hasObject(By.text("快进退时长")), 5000))
        // 点 30s 选项并确定，不崩溃即通过
        device.findObject(By.text("30s")).click()
        device.findObject(By.text("确定")).click()
    }
}

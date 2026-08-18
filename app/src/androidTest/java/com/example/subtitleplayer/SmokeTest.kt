package com.example.subtitleplayer

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 冒烟 UI 测试（GitHub Actions 模拟器跑 connectedDebugAndroidTest）。
 *
 * headless 模拟器窗口无焦点：Espresso onView 交互（点击）报 RootViewWithoutFocusException，
 * UiAutomator 跨窗口查 dialog 也不可靠。因此交互统一走 onActivity 内 performClick() +
 * Activity 成员引用（settingsDialog）同步断言，完全不依赖窗口焦点。
 *
 * 覆盖：启动、底部导航切页、设置对话框（布局切换/快进退时长）、1.25 新控件存在性。
 * 媒体播放/SAF/通知/闹钟/桌面歌词等系统级场景模拟器覆盖不到，靠真机回归。
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private fun launch(block: (Activity) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { block(it) }
        }
    }

    private fun main(activity: Activity): MainActivity = activity as MainActivity

    /** 遍历 view tree 找指定文本的 TextView/Button 并点击（导航 tab 动态构建无 id）。 */
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
            val dlg = main(activity).settingsDialog
            assertNotNull("设置对话框未打开", dlg)
            assertTrue("对话框未显示", dlg!!.isShowing)
            dlg.findViewById<RadioButton>(R.id.rbLibTree)?.performClick()
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
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
            val dlg = main(activity).settingsDialog
            assertNotNull("设置对话框未打开", dlg)
            val rg = dlg!!.findViewById<RadioGroup>(R.id.rgSeekStep)
            assertNotNull("快进退时长选项缺失", rg)
            // 点 30s 选项（tag=30），不崩溃即通过
            for (i in 0 until rg.childCount) {
                if (rg.getChildAt(i).tag?.toString() == "30") {
                    (rg.getChildAt(i) as RadioButton).performClick()
                }
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
    }
}

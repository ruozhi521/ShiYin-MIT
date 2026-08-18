package com.example.subtitleplayer

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 冒烟 UI 测试（GitHub Actions 模拟器跑 connectedDebugAndroidTest）。
 * 覆盖：启动、底部导航切页、设置对话框、音乐库布局切换、播放页 1.25 新控件存在性。
 * 媒体播放/SAF/通知/闹钟等系统级场景模拟器覆盖不到，靠真机回归。
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @Test
    fun app启动不崩溃且主视图存在() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(
                    "主视图不存在",
                    activity.findViewById<View>(android.R.id.content)
                )
            }
        }
    }

    @Test
    fun 底部导航切换到音乐库页() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("音乐库")).perform(click())
            onView(withId(R.id.recyclerPlaylists)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun 设置对话框打开并可切换树形布局() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("音乐库")).perform(click())
            onView(withId(R.id.btnSettings)).perform(click())
            onView(withId(R.id.rgLibLayout)).check(matches(isDisplayed()))
            // 切到树形并确定（1.24 布局切换）
            onView(withText("树形目录")).perform(click())
            onView(withText("确定")).perform(click())
        }
    }

    @Test
    fun 播放页快进退倍速按钮存在() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("快退按钮缺失", activity.findViewById<View>(R.id.btnSeekBack))
                assertNotNull("快进按钮缺失", activity.findViewById<View>(R.id.btnSeekForward))
                assertNotNull("倍速按钮缺失", activity.findViewById<View>(R.id.btnSpeed))
            }
        }
    }

    @Test
    fun 设置对话框快进退时长选项存在() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("音乐库")).perform(click())
            onView(withId(R.id.btnSettings)).perform(click())
            onView(withId(R.id.rgSeekStep)).check(matches(isDisplayed()))
        }
    }
}

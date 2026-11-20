package com.streamify.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamifyMacrobenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun benchmark120FpsScrollAndLyricSweep() = benchmarkRule.measureRepeated(
        packageName = "com.streamify.app",
        metrics = listOf(FrameTimingMetric()), // Measures 50th, 90th, 95th, and 99th percentile frame times
        compilationMode = CompilationMode.Full(),
        iterations = 5,
        startupMode = StartupMode.WARM
    ) {
        pressHome()
        startActivityAndWait()

        // 1. Scroll through the Universal Virtual Shelf feed
        val shelfList = device.findObject(By.res("universal_home_lazy_column"))
        shelfList?.let {
            it.setGestureMargin(device.displayWidth / 5)
            it.fling(Direction.DOWN)
            device.waitForIdle()
        }

        // 2. Open FullPlayerSheet and benchmark Syllable Karaoke rendering
        val miniPlayer = device.findObject(By.res("mini_player_dock"))
        miniPlayer?.click()
        device.waitForIdle()

        val lyricsView = device.findObject(By.res("lyrics_karaoke_canvas"))
        lyricsView?.fling(Direction.DOWN)
        device.waitForIdle()
    }
}

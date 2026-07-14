package com.smilelight.midebao.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * [RealtimeBpmAnalyzer] 单元测试。
 *
 * 运行方式：`./gradlew :app:testDebugUnitTest --tests "*.RealtimeBpmAnalyzerTest"`
 */
class RealtimeBpmAnalyzerTest {

    @Test
    fun `初始状态BPM为0`() {
        val analyzer = RealtimeBpmAnalyzer(sampleRate = 16000)
        assertThat(analyzer.currentBpm()).isEqualTo(0.0)
    }

    @Test
    fun `reset清空所有状态`() {
        val analyzer = RealtimeBpmAnalyzer(sampleRate = 16000)
        // 喂一些数据
        analyzer.process(1000L, 10.0)
        analyzer.process(1100L, 20.0)
        analyzer.reset()
        assertThat(analyzer.currentBpm()).isEqualTo(0.0)
        assertThat(analyzer.recentOnsetTimes()).isEmpty()
    }

    @Test
    fun `低通量不触发onset`() {
        val analyzer = RealtimeBpmAnalyzer(sampleRate = 16000, sensitivity = 1.5)
        // 持续低通量
        var result = BeatResult(false, 0.0, 0.0)
        for (i in 0 until 30) {
            result = analyzer.process(i.toLong() * 128, 0.1)
        }
        assertThat(result.isBeat).isFalse()
    }

    @Test
    fun `高通量峰值触发onset`() {
        val analyzer = RealtimeBpmAnalyzer(sampleRate = 16000, sensitivity = 1.5)
        // 先填充低通量基线
        for (i in 0 until 20) {
            analyzer.process(i.toLong() * 128, 0.1)
        }
        // 突然高通量
        val result = analyzer.process(20L * 128, 10.0)
        assertThat(result.isBeat).isTrue()
    }

    @Test
    fun `onset最小间隔约束`() {
        val analyzer = RealtimeBpmAnalyzer(
            sampleRate = 16000,
            sensitivity = 1.5,
            minOnsetIntervalMs = 250L
        )
        // 填充基线
        for (i in 0 until 20) {
            analyzer.process(i.toLong() * 128, 0.1)
        }
        // 第一个峰值
        val r1 = analyzer.process(20L * 128, 10.0)
        assertThat(r1.isBeat).isTrue()
        // 紧接着第二个峰值（间隔 < 250ms）不应触发
        val r2 = analyzer.process(21L * 128, 10.0)
        assertThat(r2.isBeat).isFalse()
    }

    @Test
    fun `规律onset序列产生合理BPM`() {
        val analyzer = RealtimeBpmAnalyzer(
            sampleRate = 16000,
            sensitivity = 1.5,
            minOnsetIntervalMs = 200L
        )
        // 模拟 120 BPM 的 onset：每 500ms 一个
        // 先填充基线通量
        var t = 0L
        for (i in 0 until 10) {
            analyzer.process(t, 0.1)
            t += 128
        }
        // 模拟 8 个规律 onset（每 500ms）
        for (beat in 0 until 8) {
            // onset 时刻：高通量
            analyzer.process(t, 10.0)
            t += 128
            // onset 后低通量
            while (t < (beat + 1) * 500L + 500L) {
                analyzer.process(t, 0.1)
                t += 128
            }
        }
        // BPM 应在 100-140 之间（120 附近）
        val bpm = analyzer.currentBpm()
        assertThat(bpm).isGreaterThan(0.0)
        assertThat(bpm).isAtLeast(100.0)
        assertThat(bpm).isAtMost(140.0)
    }

    @Test
    fun `onset历史记录可获取`() {
        val analyzer = RealtimeBpmAnalyzer(
            sampleRate = 16000,
            sensitivity = 1.5,
            minOnsetIntervalMs = 100L
        )
        // 填充基线
        for (i in 0 until 20) {
            analyzer.process(i.toLong() * 128, 0.1)
        }
        // 触发 onset
        analyzer.process(20L * 128, 10.0)
        val onsets = analyzer.recentOnsetTimes()
        assertThat(onsets).isNotEmpty()
    }
}

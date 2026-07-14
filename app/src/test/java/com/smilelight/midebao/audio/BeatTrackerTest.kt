package com.smilelight.midebao.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [BeatTracker] 的单元测试。
 *
 * 验证：
 * 1. 初始 smoothedBpm 为 0。
 * 2. process() 正确调用 aubio 接口。
 * 3. 半速修正：BPM 在 30~80 区间时 ×2。
 * 4. BPM 平滑：smoothedBpm 逐步收敛到真实值。
 * 5. 节拍间隔统计：连续节拍后 beatIntervalsMs 正确记录。
 * 6. reset() 清除所有状态。
 * 7. predictNextBeatTimeMs 在样本不足时返回 -1。
 */
class BeatTrackerTest {

    private lateinit var mockAubio: MockAubioProcessor
    private lateinit var tracker: BeatTracker
    private val config = PipelineConfig()

    @Before
    fun setUp() {
        mockAubio = MockAubioProcessor()
        tracker = BeatTracker(mockAubio, config)
    }

    @Test
    fun `initial smoothedBpm is zero`() {
        assertEquals(0.0, tracker.smoothedBpm, 0.0)
    }

    @Test
    fun `process calls aubio getTempo`() {
        val samples = FloatArray(1024) { 0.1f }
        tracker.process(samples, nowMs = 1000L)
        assertTrue("getTempo should be called at least once", mockAubio.tempoCallCount > 0)
    }

    @Test
    fun `half speed correction doubles BPM in 30-80 range`() {
        // 设置 aubio 返回 60 BPM（在半速区间内）
        mockAubio.setTempo(60f)
        val samples = FloatArray(1024) { 0.1f }

        // 多次调用让 smoothedBpm 收敛
        var time = 0L
        for (i in 0 until 20) {
            tracker.process(samples, nowMs = time)
            time += 100
        }

        // 60 * 2 = 120 BPM（半速修正后）
        assertTrue(
            "smoothedBpm (${tracker.smoothedBpm}) should be near 120 after half-speed correction",
            tracker.smoothedBpm > 100.0
        )
    }

    @Test
    fun `no half speed correction for BPM above 80`() {
        mockAubio.setTempo(120f)
        val samples = FloatArray(1024) { 0.1f }

        var time = 0L
        for (i in 0 until 20) {
            tracker.process(samples, nowMs = time)
            time += 100
        }

        // 120 BPM 不需要半速修正
        assertTrue(
            "smoothedBpm (${tracker.smoothedBpm}) should be near 120 without correction",
            tracker.smoothedBpm > 100.0 && tracker.smoothedBpm < 140.0
        )
    }

    @Test
    fun `reset clears all state`() {
        mockAubio.setTempo(120f)
        val samples = FloatArray(1024) { 0.1f }
        tracker.process(samples, nowMs = 1000L)

        tracker.reset()
        assertEquals(0.0, tracker.smoothedBpm, 0.0)
    }

    @Test
    fun `predictNextBeatTimeMs returns minus 1 when not enough beats`() {
        val result = tracker.predictNextBeatTimeMs(nowMs = 5000L)
        assertEquals(-1L, result)
    }

    @Test
    fun `beat detection records interval`() {
        mockAubio.setTempo(120f)
        val samples = FloatArray(1024) { 0.1f }

        // 模拟节拍：每次 beatSample 变化时触发新节拍
        mockAubio.setBeatSample(100L)
        tracker.process(samples, nowMs = 0L)

        mockAubio.setBeatSample(200L)
        tracker.process(samples, nowMs = 500L)

        mockAubio.setBeatSample(300L)
        tracker.process(samples, nowMs = 1000L)

        // 预测下一拍应该返回一个正数
        val predicted = tracker.predictNextBeatTimeMs(nowMs = 1000L)
        assertTrue(
            "predictNextBeatTimeMs should return positive value after enough beats, got $predicted",
            predicted > 0L
        )
    }
}

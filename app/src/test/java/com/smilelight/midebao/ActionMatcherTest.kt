package com.smilelight.midebao.action

import com.google.common.truth.Truth.assertThat
import com.smilelight.midebao.audio.MusicFeatures
import org.junit.Test

/**
 * [ActionMatcher] 单元测试。
 *
 * 运行方式：`./gradlew :app:testDebugUnitTest --tests "*.ActionMatcherTest"`
 */
class ActionMatcherTest {

    private val matcher = ActionMatcher()

    /** 构造测试用 MusicFeatures（spectrum 用空数组，匹配逻辑不依赖它）。 */
    private fun features(centroidHz: Double = 1000.0): MusicFeatures =
        MusicFeatures(
            rms = 0.5,
            spectralCentroidHz = centroidHz,
            spectralFlux = 1.0,
            bassRatio = 0.3,
            trebleRatio = 0.2,
            spectrum = DoubleArray(1024)
        )

    @Test
    fun `BPM无效时selectBest返回null`() {
        val result = matcher.selectBest(bpm = 0.0, pe = 0.0, features = features())
        assertThat(result).isNull()
    }

    @Test
    fun `BPM有效时selectBest返回非null`() {
        val result = matcher.selectBest(bpm = 120.0, pe = 0.0, features = features())
        assertThat(result).isNotNull()
        assertThat(result!!.score).isGreaterThan(0.0)
    }

    @Test
    fun `节奏完全匹配时tempoScore接近1`() {
        // F3 档3 的 nodInterval = 0.5s，对应 BPM = 120
        val f3lvl3 = ActionCatalog.find("F3", 3)!!
        val score = matcher.computeTempoScore(f3lvl3, bpm = 120.0)
        assertThat(score).isWithin(0.01).of(1.0)
    }

    @Test
    fun `节奏半拍匹配tempoScore也较高`() {
        // F3 档3 nodInterval=0.5s，BPM=60 → ratio=0.5，是简单整数比
        val f3lvl3 = ActionCatalog.find("F3", 3)!!
        val score = matcher.computeTempoScore(f3lvl3, bpm = 60.0)
        assertThat(score).isGreaterThan(0.9)
    }

    @Test
    fun `节奏完全不匹配tempoScore较低`() {
        // F3 档3 nodInterval=0.5s，BPM=123 → ratio=0.5/0.488≈1.024，偏离简单整数比
        val f3lvl3 = ActionCatalog.find("F3", 3)!!
        val score = matcher.computeTempoScore(f3lvl3, bpm = 123.0)
        // 123 BPM 的拍间隔 = 0.488s，ratio = 0.5/0.488 = 1.024，log2 误差小
        // 实际上 1.024 接近 1:1，所以分数不会太低。改用更极端的值
        val scoreExtreme = matcher.computeTempoScore(f3lvl3, bpm = 173.0)
        // 173 BPM 拍间隔=0.347s，ratio=0.5/0.347=1.44，偏离 1 和 2 都较远
        assertThat(scoreExtreme).isLessThan(0.8)
    }

    @Test
    fun `高强度动作在高PE时能量分更高`() {
        val f3lvl7 = ActionCatalog.find("F3", 7)!!  // 最激烈
        val f7 = ActionCatalog.find("F7", 0)!!      // 最安静

        val highPeScore = matcher.computeEnergyScore(f3lvl7, pe = 1.0)
        val lowPeScore = matcher.computeEnergyScore(f3lvl7, pe = -1.0)

        assertThat(highPeScore).isGreaterThan(lowPeScore)

        // F7 在低 PE 时分数更高
        val f7HighPe = matcher.computeEnergyScore(f7, pe = 1.0)
        val f7LowPe = matcher.computeEnergyScore(f7, pe = -1.0)
        assertThat(f7LowPe).isGreaterThan(f7HighPe)
    }

    @Test
    fun `低质心音乐偏好高强度动作`() {
        val f3lvl7 = ActionCatalog.find("F3", 7)!!  // 高强度
        val f7 = ActionCatalog.find("F7", 0)!!      // 低强度

        val bassFeatures = features(centroidHz = 300.0)  // 低质心
        val brightFeatures = features(centroidHz = 3000.0)  // 高质心

        val f3BassScore = matcher.computeStyleScore(f3lvl7, bassFeatures)
        val f3BrightScore = matcher.computeStyleScore(f3lvl7, brightFeatures)
        assertThat(f3BassScore).isGreaterThan(f3BrightScore)

        val f7BassScore = matcher.computeStyleScore(f7, bassFeatures)
        val f7BrightScore = matcher.computeStyleScore(f7, brightFeatures)
        assertThat(f7BrightScore).isGreaterThan(f7BassScore)
    }

    @Test
    fun `总分在0到1之间`() {
        for (candidate in ActionCatalog.ALL) {
            val score = matcher.scoreCandidate(
                candidate = candidate,
                bpm = 120.0,
                pe = 0.0,
                features = features()
            )
            assertThat(score).isAtLeast(0.0)
            assertThat(score).isAtMost(1.0)
        }
    }

    @Test
    fun `17个候选动作全部存在`() {
        assertThat(ActionCatalog.ALL).hasSize(17)
    }

    @Test
    fun `F3和F5各有7个档位`() {
        assertThat(ActionCatalog.levelsOf("F3")).hasSize(7)
        assertThat(ActionCatalog.levelsOf("F5")).hasSize(7)
    }

    @Test
    fun `F4F6F7各1个档位`() {
        assertThat(ActionCatalog.levelsOf("F4")).hasSize(1)
        assertThat(ActionCatalog.levelsOf("F6")).hasSize(1)
        assertThat(ActionCatalog.levelsOf("F7")).hasSize(1)
    }

    @Test
    fun `强度极值正确`() {
        // F7 最慢：intensity = 1/6.0
        assertThat(ActionCatalog.MIN_INTENSITY).isWithin(0.001).of(1.0 / 6.0)
        // F3 档7 最快：intensity = 1/0.20 = 5.0
        assertThat(ActionCatalog.MAX_INTENSITY).isWithin(0.001).of(5.0)
    }

    @Test
    fun `idealEnergy线性映射正确`() {
        val f7 = ActionCatalog.find("F7", 0)!!  // 最慢 → idealEnergy = -1
        val f3lvl7 = ActionCatalog.find("F3", 7)!!  // 最快 → idealEnergy = +1

        val f7Energy = f7.idealEnergy(ActionCatalog.MIN_INTENSITY, ActionCatalog.MAX_INTENSITY)
        val f3Energy = f3lvl7.idealEnergy(ActionCatalog.MIN_INTENSITY, ActionCatalog.MAX_INTENSITY)

        assertThat(f7Energy).isWithin(0.001).of(-1.0)
        assertThat(f3Energy).isWithin(0.001).of(1.0)
    }

    @Test
    fun `find查找存在的动作`() {
        val f3lvl1 = ActionCatalog.find("F3", 1)
        assertThat(f3lvl1).isNotNull()
        assertThat(f3lvl1!!.code).isEqualTo("F3")
        assertThat(f3lvl1.speedLevel).isEqualTo(1)
    }

    @Test
    fun `find查找不存在的动作返回null`() {
        assertThat(ActionCatalog.find("F3", 8)).isNull()  // F3 只有 1-7 档
        assertThat(ActionCatalog.find("F9", 1)).isNull()  // F9 不存在
    }

    @Test
    fun `CODE_TO_COMMAND包含所有动作码`() {
        for (code in listOf("F3", "F4", "F5", "F6", "F7", "F8")) {
            assertThat(ActionCatalog.CODE_TO_COMMAND).containsKey(code)
        }
    }

    @Test
    fun `SPEEDABLE_CODES只含F3和F5`() {
        assertThat(ActionCatalog.SPEEDABLE_CODES).containsExactly("F3", "F5")
    }
}

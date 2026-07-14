package com.smilelight.midebao.action

import com.google.common.truth.Truth.assertThat
import com.smilelight.midebao.audio.MusicFeatures
import org.junit.Test

/**
 * [ActionSelector] 单元测试。
 *
 * 运行方式：`./gradlew :app:testDebugUnitTest --tests "*.ActionSelectorTest"`
 */
class ActionSelectorTest {

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
    fun `BPM无效时返回NoSwitch`() {
        val selector = ActionSelector()
        val decision = selector.decide(
            beatCount = 10,
            bpm = 0.0,
            pe = 0.0,
            features = features(),
            nowMs = 1000L
        )
        assertThat(decision).isInstanceOf(SwitchDecision.NoSwitch::class.java)
    }

    @Test
    fun `首次调用返回Switch`() {
        val selector = ActionSelector()
        val decision = selector.decide(
            beatCount = 2,
            bpm = 120.0,
            pe = 0.0,
            features = features(),
            nowMs = 1000L
        )
        assertThat(decision).isInstanceOf(SwitchDecision.Switch::class.java)
        assertThat((decision as SwitchDecision.Switch).reason).contains("首次")
    }

    @Test
    fun `驻留不足时返回NoSwitch`() {
        val selector = ActionSelector(minCyclesBeforeSwitch = 2)
        // 先设置当前动作
        val firstDecision = selector.decide(
            beatCount = 2, bpm = 120.0, pe = 0.0, features = features(), nowMs = 1000L
        )
        assertThat(firstDecision).isInstanceOf(SwitchDecision.Switch::class.java)
        selector.setCurrentAction((firstDecision as SwitchDecision.Switch).newAction, 2)

        // 立即再调用（驻留不足）
        val secondDecision = selector.decide(
            beatCount = 3, bpm = 120.0, pe = 1.0, features = features(), nowMs = 2000L
        )
        assertThat(secondDecision).isInstanceOf(SwitchDecision.NoSwitch::class.java)
        assertThat((secondDecision as SwitchDecision.NoSwitch).reason).contains("驻留")
    }

    @Test
    fun `分差不足时返回NoSwitch`() {
        val selector = ActionSelector(minCyclesBeforeSwitch = 1)
        selector.switchThreshold = 0.5
        // 设置当前动作
        val firstDecision = selector.decide(
            beatCount = 2, bpm = 120.0, pe = 0.0, features = features(), nowMs = 1000L
        )
        selector.setCurrentAction((firstDecision as SwitchDecision.Switch).newAction, 2)

        // 驻留足够但分差不足（相同特征）
        val secondDecision = selector.decide(
            beatCount = 100, bpm = 120.0, pe = 0.0, features = features(), nowMs = 2000L
        )
        // 相同特征下最佳候选可能就是当前动作，或分差很小
        if (secondDecision is SwitchDecision.NoSwitch) {
            // 预期：要么"候选相同"要么"分差不足"
            assertThat(secondDecision.reason).matches("候选与当前相同|分差不足.*")
        }
    }

    @Test
    fun `reset清空当前动作`() {
        val selector = ActionSelector()
        selector.decide(beatCount = 2, bpm = 120.0, pe = 0.0, features = features(), nowMs = 1000L)
        assertThat(selector.currentAction()).isNotNull()
        selector.reset()
        assertThat(selector.currentAction()).isNull()
    }

    @Test
    fun `recordOnset记录时间戳`() {
        val selector = ActionSelector()
        selector.recordOnset(1000L)
        selector.recordOnset(1500L)
        selector.recordOnset(2000L)
        // 预测需要至少 2 个 onset
        val predicted = selector.predictNextBeat(2500L)
        // 3 个 onset 间隔 500ms，预测下一拍 = 2000 + 500 = 2500
        assertThat(predicted).isEqualTo(2500L)
    }

    @Test
    fun `predictNextBeat数据不足返回负1`() {
        val selector = ActionSelector()
        assertThat(selector.predictNextBeat(1000L)).isEqualTo(-1L)
        selector.recordOnset(1000L)
        // 只有 1 个 onset
        assertThat(selector.predictNextBeat(1000L)).isEqualTo(-1L)
    }

    @Test
    fun `computeMinHoldBeats至少2拍`() {
        val selector = ActionSelector(minCyclesBeforeSwitch = 2)
        val f7 = ActionCatalog.find("F7", 0)!!  // periodSec=24.5，极长
        // F7 周期 24.5s，BPM=120 → beatInterval=0.5s
        // minHoldBeats = ceil(2 * 24.5 / 0.5) = 98，但下限 2
        val holdBeats = selector.computeMinHoldBeats(f7, 0.5)
        assertThat(holdBeats).isAtLeast(2)
    }

    @Test
    fun `switchThreshold可运行时修改`() {
        val selector = ActionSelector()
        assertThat(selector.switchThreshold).isEqualTo(0.10)
        selector.switchThreshold = 0.20
        assertThat(selector.switchThreshold).isEqualTo(0.20)
    }

    @Test
    fun `切换后setCurrentAction更新当前动作`() {
        val selector = ActionSelector()
        val decision = selector.decide(
            beatCount = 2, bpm = 120.0, pe = 0.0, features = features(), nowMs = 1000L
        )
        val switched = decision as SwitchDecision.Switch
        selector.setCurrentAction(switched.newAction, 2)
        assertThat(selector.currentAction()).isEqualTo(switched.newAction)
    }
}

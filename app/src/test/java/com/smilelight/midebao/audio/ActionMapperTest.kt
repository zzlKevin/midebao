package com.smilelight.midebao.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ActionMapper] 的单元测试。
 *
 * 验证：
 * 1. computeDistanceScore 在目标与实际相等时返回 1.0。
 * 2. computeDistanceScore 在目标与实际相反时返回 0.0。
 * 3. computeSwitchingPenalty 在同码时返回 0.0。
 * 4. computeSwitchingPenalty 在跨码时返回正值。
 * 5. selectBest 返回得分最高的动作。
 * 6. selectBest 在 BPM 无效时返回 null。
 * 7. shouldSwitch 在驻留节拍不足时为 false。
 */
class ActionMapperTest {

    private lateinit var mapper: ActionMapper
    private val config = PipelineConfig()

    @Before
    fun setUp() {
        mapper = ActionMapper(ActionCatalog, config)
    }

    @Test
    fun `distance score is 1 when target equals actual`() {
        val score = mapper.computeDistanceScore(0.5, 0.5)
        assertEquals(1.0, score, 0.001)
    }

    @Test
    fun `distance score is 0 when target is opposite of actual`() {
        val score = mapper.computeDistanceScore(-1.0, 1.0)
        assertEquals(0.0, score, 0.001)
    }

    @Test
    fun `distance score is in range 0 to 1`() {
        val score = mapper.computeDistanceScore(0.3, 0.7)
        assertTrue("score $score should be in [0,1]", score in 0.0..1.0)
    }

    @Test
    fun `switching penalty is 0 for same code`() {
        val penalty = mapper.computeSwitchingPenalty("F3", "F3")
        assertEquals(0.0, penalty, 0.0)
    }

    @Test
    fun `switching penalty is positive for different code`() {
        val penalty = mapper.computeSwitchingPenalty("F4", "F3")
        assertTrue("penalty $penalty should be positive for different code", penalty > 0.0)
    }

    @Test
    fun `selectBest returns null when BPM is zero`() {
        val state = MusicState.EMPTY
        val result = mapper.selectBest(state, currentActionCode = "F8", currentSpeedLevel = 2, beatsSinceSwitch = 100)
        assertTrue("selectBest should return null when BPM is 0", result == null)
    }

    @Test
    fun `selectBest returns non-null for valid state`() {
        val state = MusicState(
            bpm = 120.0,
            energy = 0.5,
            brightness = 0.3,
            complexity = 0.2,
            stability = 0.8f,
            rawPe = 0.5,
            bassEnergy = 0.4,
            midEnergy = 0.4,
            highEnergy = 0.2
        )
        val result = mapper.selectBest(state, currentActionCode = "F8", currentSpeedLevel = 2, beatsSinceSwitch = 100)
        assertNotNull("selectBest should return non-null for valid state", result)
    }

    @Test
    fun `selectBest bestAction is in catalog`() {
        val state = MusicState(
            bpm = 120.0,
            energy = 0.9,
            brightness = 0.9,
            complexity = 0.9,
            stability = 0.8f,
            rawPe = 0.9,
            bassEnergy = 0.4,
            midEnergy = 0.4,
            highEnergy = 0.2
        )
        val result = mapper.selectBest(state, currentActionCode = "F8", currentSpeedLevel = 2, beatsSinceSwitch = 100)
        assertNotNull(result)
        assertTrue(
            "bestAction should be in catalog",
            result!!.bestAction.actionCode in ActionCatalog.all.map { it.actionCode }
        )
    }

    @Test
    fun `shouldSwitch is false when beatsSinceSwitch is zero`() {
        val state = MusicState(
            bpm = 120.0,
            energy = 0.9,
            brightness = 0.9,
            complexity = 0.9,
            stability = 0.8f,
            rawPe = 0.9,
            bassEnergy = 0.4,
            midEnergy = 0.4,
            highEnergy = 0.2
        )
        val result = mapper.selectBest(state, currentActionCode = "F8", currentSpeedLevel = 2, beatsSinceSwitch = 0)
        assertNotNull(result)
        assertFalse(
            "shouldSwitch should be false when beatsSinceSwitch is 0",
            result!!.shouldSwitch
        )
    }

    @Test
    fun `bestScore is greater than or equal to currentScore`() {
        val state = MusicState(
            bpm = 120.0,
            energy = 0.5,
            brightness = 0.3,
            complexity = 0.2,
            stability = 0.8f,
            rawPe = 0.5,
            bassEnergy = 0.4,
            midEnergy = 0.4,
            highEnergy = 0.2
        )
        val result = mapper.selectBest(state, currentActionCode = "F8", currentSpeedLevel = 2, beatsSinceSwitch = 100)
        assertNotNull(result)
        assertTrue(
            "bestScore (${result!!.bestScore}) should be >= currentScore (${result.currentScore})",
            result.bestScore >= result.currentScore - 0.001
        )
    }
}

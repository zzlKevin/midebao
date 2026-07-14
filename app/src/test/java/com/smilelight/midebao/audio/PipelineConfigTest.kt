package com.smilelight.midebao.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PipelineConfig] 的单元测试。
 *
 * 验证：
 * 1. 默认配置的权重之和为 1.0。
 * 2. switchThreshold 在 [0,1] 范围内。
 * 3. bpmSmoothingFactor 在 [0,1] 范围内。
 * 4. peWindowSizeFrames 为正数。
 * 5. predictionWindowBeats 为正数。
 * 6. preSendOffsetMs 非负。
 * 7. 非法参数抛出 IllegalArgumentException。
 */
class PipelineConfigTest {

    @Test
    fun `default weights sum to 1`() {
        val config = PipelineConfig()
        val sum = config.beatScoreWeight +
            config.energyScoreWeight +
            config.brightnessScoreWeight +
            config.complexityScoreWeight +
            config.switchingPenaltyWeight
        assertEquals(1.0, sum, 0.001)
    }

    @Test
    fun `switchThreshold is in range 0 to 1`() {
        val config = PipelineConfig()
        assertTrue(
            "switchThreshold ${config.switchThreshold} should be in [0,1]",
            config.switchThreshold in 0.0..1.0
        )
    }

    @Test
    fun `bpmSmoothingFactor is in range 0 to 1`() {
        val config = PipelineConfig()
        assertTrue(
            "bpmSmoothingFactor ${config.bpmSmoothingFactor} should be in [0,1]",
            config.bpmSmoothingFactor in 0.0..1.0
        )
    }

    @Test
    fun `peWindowSizeFrames is positive`() {
        val config = PipelineConfig()
        assertTrue(
            "peWindowSizeFrames ${config.peWindowSizeFrames} should be positive",
            config.peWindowSizeFrames > 0
        )
    }

    @Test
    fun `predictionWindowBeats is positive`() {
        val config = PipelineConfig()
        assertTrue(
            "predictionWindowBeats ${config.predictionWindowBeats} should be positive",
            config.predictionWindowBeats > 0
        )
    }

    @Test
    fun `preSendOffsetMs is non-negative`() {
        val config = PipelineConfig()
        assertTrue(
            "preSendOffsetMs ${config.preSendOffsetMs} should be non-negative",
            config.preSendOffsetMs >= 0
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative beatScoreWeight throws exception`() {
        PipelineConfig(beatScoreWeight = -0.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `weights not summing to 1 throws exception`() {
        PipelineConfig(
            beatScoreWeight = 0.5,
            energyScoreWeight = 0.5,
            brightnessScoreWeight = 0.5,
            complexityScoreWeight = 0.0,
            switchingPenaltyWeight = 0.0
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `switchThreshold above 1 throws exception`() {
        PipelineConfig(switchThreshold = 1.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative peWindowSizeFrames throws exception`() {
        PipelineConfig(peWindowSizeFrames = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative predictionWindowBeats throws exception`() {
        PipelineConfig(predictionWindowBeats = 0)
    }
}

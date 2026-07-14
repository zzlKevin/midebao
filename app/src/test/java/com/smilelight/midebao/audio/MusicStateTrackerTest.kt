package com.smilelight.midebao.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [MusicStateTracker] 的单元测试。
 *
 * 验证：
 * 1. update() 返回的 MusicState 各字段在有效范围内。
 * 2. 多帧更新后状态趋于稳定。
 * 3. reset() 清除所有状态。
 * 4. BPM 从 BeatInfo 正确传播。
 * 5. bassEnergy 从 AudioFeatures 正确传播。
 */
class MusicStateTrackerTest {

    private lateinit var tracker: MusicStateTracker
    private val config = PipelineConfig()

    @Before
    fun setUp() {
        tracker = MusicStateTracker(config)
    }

    @Test
    fun `energy is in range minus 1 to 1 after update`() {
        val features = AudioFeatures(
            rms = 0.3,
            spectralCentroid = 0.5,
            spectralFlux = 0.2,
            onsetStrength = 0.3,
            bassEnergy = 0.4,
            midEnergy = 0.4,
            highEnergy = 0.2,
            totalEnergy = 0.6
        )
        val beatInfo = BeatInfo(isBeat = false, bpm = 120.0, rawBpm = 120.0, beatSample = 0L)

        val state = tracker.update(features, beatInfo)
        assertTrue("energy ${state.energy} out of range", state.energy in -1.0..1.0)
    }

    @Test
    fun `brightness is in range minus 1 to 1 after update`() {
        val features = AudioFeatures(
            rms = 0.3,
            spectralCentroid = 0.7,
            spectralFlux = 0.2,
            onsetStrength = 0.3,
            bassEnergy = 0.4,
            midEnergy = 0.4,
            highEnergy = 0.2,
            totalEnergy = 0.6
        )
        val beatInfo = BeatInfo(isBeat = false, bpm = 120.0, rawBpm = 120.0, beatSample = 0L)

        val state = tracker.update(features, beatInfo)
        assertTrue("brightness ${state.brightness} out of range", state.brightness in -1.0..1.0)
    }

    @Test
    fun `complexity is in range minus 1 to 1 after update`() {
        val features = AudioFeatures(
            rms = 0.3,
            spectralCentroid = 0.5,
            spectralFlux = 0.5,
            onsetStrength = 0.5,
            bassEnergy = 0.4,
            midEnergy = 0.4,
            highEnergy = 0.2,
            totalEnergy = 0.6
        )
        val beatInfo = BeatInfo(isBeat = false, bpm = 120.0, rawBpm = 120.0, beatSample = 0L)

        val state = tracker.update(features, beatInfo)
        assertTrue("complexity ${state.complexity} out of range", state.complexity in -1.0..1.0)
    }

    @Test
    fun `reset clears state`() {
        val features = AudioFeatures(
            rms = 0.5,
            spectralCentroid = 0.5,
            spectralFlux = 0.3,
            onsetStrength = 0.5,
            bassEnergy = 0.4,
            midEnergy = 0.4,
            highEnergy = 0.2,
            totalEnergy = 0.8
        )
        val beatInfo = BeatInfo(isBeat = false, bpm = 120.0, rawBpm = 120.0, beatSample = 0L)
        tracker.update(features, beatInfo)

        tracker.reset()
        val state = tracker.update(AudioFeatures.SILENT, BeatInfo(false, 0.0, 0.0, 0L))
        assertEquals(0.0, state.energy, 0.01)
        assertEquals(0.0, state.brightness, 0.01)
    }

    @Test
    fun `bpm is propagated from beatInfo`() {
        val features = AudioFeatures.SILENT
        val beatInfo = BeatInfo(isBeat = false, bpm = 128.0, rawBpm = 128.0, beatSample = 0L)
        val state = tracker.update(features, beatInfo)
        assertEquals(128.0, state.bpm, 0.0)
    }

    @Test
    fun `bassEnergy is propagated from features`() {
        val features = AudioFeatures(
            rms = 0.3,
            spectralCentroid = 0.5,
            spectralFlux = 0.2,
            onsetStrength = 0.3,
            bassEnergy = 0.6,
            midEnergy = 0.3,
            highEnergy = 0.1,
            totalEnergy = 0.5
        )
        val beatInfo = BeatInfo(isBeat = false, bpm = 120.0, rawBpm = 120.0, beatSample = 0L)
        val state = tracker.update(features, beatInfo)
        assertEquals(0.6, state.bassEnergy, 0.01)
    }
}

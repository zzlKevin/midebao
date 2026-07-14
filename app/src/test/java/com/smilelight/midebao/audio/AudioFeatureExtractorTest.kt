package com.smilelight.midebao.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [AudioFeatureExtractor] 的单元测试。
 *
 * 验证：
 * 1. 静音帧返回全零特征。
 * 2. 正弦波输入返回合理的 RMS 和频谱质心。
 * 3. 所有归一化字段在 [0,1] 范围内。
 * 4. 频段能量占比之和约为 1.0。
 */
class AudioFeatureExtractorTest {

    private lateinit var extractor: AudioFeatureExtractor

    @Before
    fun setUp() {
        extractor = AudioFeatureExtractor(sampleRate = 16000, fftSize = 2048)
    }

    @Test
    fun `silent input returns zero features`() {
        val samples = FloatArray(2048) { 0.0f }
        val features = extractor.extract(samples)
        assertEquals(0.0, features.rms, 1e-9)
        assertEquals(0.0, features.totalEnergy, 1e-9)
    }

    @Test
    fun `non-zero input returns positive rms`() {
        val samples = FloatArray(2048) { 0.5f }
        val features = extractor.extract(samples)
        assertTrue("RMS should be positive for non-zero input, got ${features.rms}", features.rms > 0.0)
    }

    @Test
    fun `all normalized fields are in range 0 to 1`() {
        val samples = FloatArray(2048) { (Math.sin(it * 0.01) * 0.5).toFloat() }
        val features = extractor.extract(samples)
        assertTrue("spectralCentroid ${features.spectralCentroid} out of range", features.spectralCentroid in 0.0..1.0)
        assertTrue("spectralFlux ${features.spectralFlux} out of range", features.spectralFlux in 0.0..1.0)
        assertTrue("onsetStrength ${features.onsetStrength} out of range", features.onsetStrength in 0.0..1.0)
        assertTrue("bassEnergy ${features.bassEnergy} out of range", features.bassEnergy in 0.0..1.0)
        assertTrue("midEnergy ${features.midEnergy} out of range", features.midEnergy in 0.0..1.0)
        assertTrue("highEnergy ${features.highEnergy} out of range", features.highEnergy in 0.0..1.0)
        assertTrue("totalEnergy ${features.totalEnergy} out of range", features.totalEnergy in 0.0..1.0)
    }

    @Test
    fun `band energy ratios sum to approximately 1`() {
        val samples = FloatArray(2048) { (Math.sin(it * 0.01) * 0.5).toFloat() }
        val features = extractor.extract(samples)
        val sum = features.bassEnergy + features.midEnergy + features.highEnergy
        assertEquals(1.0, sum, 0.15)
    }

    @Test
    fun `SILENT constant has all zeros`() {
        assertEquals(0.0, AudioFeatures.SILENT.rms, 0.0)
        assertEquals(0.0, AudioFeatures.SILENT.totalEnergy, 0.0)
    }

    @Test
    fun `low frequency sine wave produces high bass energy ratio`() {
        // 100 Hz 正弦波，采样率 16000
        val sampleRate = 16000
        val freq = 100.0
        val samples = FloatArray(2048) { i ->
            (Math.sin(2.0 * Math.PI * freq * i / sampleRate) * 0.5).toFloat()
        }
        val extractor = AudioFeatureExtractor(sampleRate = sampleRate, fftSize = 2048)
        val features = extractor.extract(samples)
        assertTrue(
            "Bass energy ratio (${features.bassEnergy}) should be high for 100Hz sine wave",
            features.bassEnergy > 0.3
        )
    }

    @Test
    fun `high frequency sine wave produces high high energy ratio`() {
        // 5000 Hz 正弦波，采样率 16000
        val sampleRate = 16000
        val freq = 5000.0
        val samples = FloatArray(2048) { i ->
            (Math.sin(2.0 * Math.PI * freq * i / sampleRate) * 0.5).toFloat()
        }
        val extractor = AudioFeatureExtractor(sampleRate = sampleRate, fftSize = 2048)
        val features = extractor.extract(samples)
        assertTrue(
            "High energy ratio (${features.highEnergy}) should be high for 5000Hz sine wave",
            features.highEnergy > 0.2
        )
    }
}

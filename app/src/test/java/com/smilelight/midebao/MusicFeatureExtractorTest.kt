package com.smilelight.midebao.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * [MusicFeatureExtractor] 单元测试。
 *
 * 运行方式：`./gradlew :app:testDebugUnitTest --tests "*.MusicFeatureExtractorTest"`
 */
class MusicFeatureExtractorTest {

    private val sampleRate = 16000
    private val fftSize = 2048
    private val extractor = MusicFeatureExtractor(sampleRate, fftSize)

    @Test
    fun `静音输入RMS接近0`() {
        val samples = DoubleArray(fftSize) { 0.0 }
        val features = extractor.extract(samples, null)
        assertThat(features.rms).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `满幅正弦波RMS约为0_707`() {
        // 满幅正弦波 RMS = 1/sqrt(2) ≈ 0.707
        val freq = 440.0
        val samples = DoubleArray(fftSize) { i ->
            sin(2 * PI * freq * i / sampleRate)
        }
        val features = extractor.extract(samples, null)
        assertThat(features.rms).isWithin(0.01).of(1.0 / kotlin.math.sqrt(2.0))
    }

    @Test
    fun `低频信号质心低于高频信号`() {
        // 100 Hz 正弦波
        val lowFreqSamples = DoubleArray(fftSize) { i ->
            sin(2 * PI * 100.0 * i / sampleRate)
        }
        val lowFeatures = extractor.extract(lowFreqSamples, null)

        // 3000 Hz 正弦波
        val highFreqSamples = DoubleArray(fftSize) { i ->
            sin(2 * PI * 3000.0 * i / sampleRate)
        }
        val highFeatures = extractor.extract(highFreqSamples, null)

        assertThat(lowFeatures.spectralCentroidHz).isLessThan(highFeatures.spectralCentroidHz)
        // 100Hz 信号的质心应接近 100Hz
        assertThat(lowFeatures.spectralCentroidHz).isWithin(50.0).of(100.0)
        // 3000Hz 信号的质心应接近 3000Hz
        assertThat(highFeatures.spectralCentroidHz).isWithin(200.0).of(3000.0)
    }

    @Test
    fun `首帧频谱通量为0`() {
        val samples = DoubleArray(fftSize) { i -> sin(2 * PI * 440.0 * i / sampleRate) }
        val features = extractor.extract(samples, null)
        assertThat(features.spectralFlux).isEqualTo(0.0)
    }

    @Test
    fun `相同信号连续两帧通量接近0`() {
        val samples = DoubleArray(fftSize) { i -> sin(2 * PI * 440.0 * i / sampleRate) }
        val features1 = extractor.extract(samples, null)
        val features2 = extractor.extract(samples, features1.spectrum)
        // 相同信号的通量应非常小
        assertThat(features2.spectralFlux).isLessThan(1.0)
    }

    @Test
    fun `不同信号通量大于相同信号`() {
        val samples1 = DoubleArray(fftSize) { i -> sin(2 * PI * 440.0 * i / sampleRate) }
        val features1 = extractor.extract(samples1, null)

        // 完全不同的频率
        val samples2 = DoubleArray(fftSize) { i -> sin(2 * PI * 2000.0 * i / sampleRate) }
        val features2 = extractor.extract(samples2, features1.spectrum)

        // 相同信号的通量
        val features3 = extractor.extract(samples1, features1.spectrum)

        assertThat(features2.spectralFlux).isGreaterThan(features3.spectralFlux)
    }

    @Test
    fun `低频信号bassRatio大于高频信号`() {
        val lowSamples = DoubleArray(fftSize) { i -> sin(2 * PI * 80.0 * i / sampleRate) }
        val lowFeatures = extractor.extract(lowSamples, null)

        val highSamples = DoubleArray(fftSize) { i -> sin(2 * PI * 4000.0 * i / sampleRate) }
        val highFeatures = extractor.extract(highSamples, null)

        assertThat(lowFeatures.bassRatio).isGreaterThan(highFeatures.bassRatio)
        assertThat(highFeatures.trebleRatio).isGreaterThan(lowFeatures.trebleRatio)
    }

    @Test
    fun `样本长度不匹配抛出异常`() {
        val badSamples = DoubleArray(100) { 0.0 }
        try {
            extractor.extract(badSamples, null)
            assert(false) { "应抛出 IllegalArgumentException" }
        } catch (e: IllegalArgumentException) {
            // 预期行为
        }
    }

    @Test
    fun `halfBinCount返回正确值`() {
        assertThat(extractor.halfBinCount()).isEqualTo(fftSize / 2)
    }
}

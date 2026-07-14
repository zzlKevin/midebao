package com.smilelight.midebao.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * [PerceivedEnergyCalculator] 单元测试。
 *
 * 运行方式：`./gradlew :app:testDebugUnitTest --tests "*.PerceivedEnergyCalculatorTest"`
 */
class PerceivedEnergyCalculatorTest {

    private fun features(rms: Double, flux: Double, centroid: Double): MusicFeatures =
        MusicFeatures(
            rms = rms,
            spectralCentroidHz = centroid,
            spectralFlux = flux,
            bassRatio = 0.3,
            trebleRatio = 0.2,
            spectrum = DoubleArray(1024)
        )

    @Test
    fun `PE值在负1到正1之间`() {
        val calc = PerceivedEnergyCalculator()
        // 喂入各种数据
        for (i in 0 until 100) {
            val f = features(
                rms = (i % 10) / 10.0,
                flux = (i % 5).toDouble(),
                centroid = 500.0 + i * 50
            )
            val pe = calc.calculate(i.toLong() * 100, f)
            assertThat(pe).isAtLeast(-1.0)
            assertThat(pe).isAtMost(1.0)
        }
    }

    @Test
    fun `样本不足时PE为0`() {
        val calc = PerceivedEnergyCalculator()
        val f = features(rms = 0.5, flux = 1.0, centroid = 1000.0)
        // AdaptiveNormalizer 默认 minSamples=8，前 7 个样本应返回 0
        for (i in 0 until 7) {
            assertThat(calc.calculate(i.toLong() * 100, f)).isEqualTo(0.0)
        }
    }

    @Test
    fun `高能量输入PE高于低能量输入`() {
        val calc = PerceivedEnergyCalculator()
        // 先填充基线（中等能量）
        for (i in 0 until 20) {
            calc.calculate(i.toLong() * 100, features(rms = 0.3, flux = 0.5, centroid = 1000.0))
        }
        // 高能量帧
        val highPe = calc.calculate(2000L, features(rms = 0.9, flux = 5.0, centroid = 2000.0))

        // 重置后填充相同基线
        calc.reset()
        for (i in 0 until 20) {
            calc.calculate(i.toLong() * 100, features(rms = 0.3, flux = 0.5, centroid = 1000.0))
        }
        // 低能量帧
        val lowPe = calc.calculate(2000L, features(rms = 0.05, flux = 0.01, centroid = 500.0))

        assertThat(highPe).isGreaterThan(lowPe)
    }

    @Test
    fun `reset后重新开始`() {
        val calc = PerceivedEnergyCalculator()
        for (i in 0 until 20) {
            calc.calculate(i.toLong() * 100, features(rms = 0.5, flux = 1.0, centroid = 1000.0))
        }
        calc.reset()
        // reset 后第一个样本应返回 0（样本不足）
        assertThat(calc.calculate(0L, features(rms = 0.5, flux = 1.0, centroid = 1000.0))).isEqualTo(0.0)
    }
}

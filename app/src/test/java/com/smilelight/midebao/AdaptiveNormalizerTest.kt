package com.smilelight.midebao.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [AdaptiveNormalizer] 单元测试。
 *
 * 运行方式：在项目根目录执行 `./gradlew :app:testDebugUnitTest --tests "*.AdaptiveNormalizerTest"`
 */
class AdaptiveNormalizerTest {

    @Test
    fun `样本不足时返回0`() {
        val norm = AdaptiveNormalizer(windowMs = 10_000, minSamples = 8)
        repeat(7) { i ->
            assertThat(norm.add(i.toLong() * 100, 1.0)).isEqualTo(0.0)
        }
    }

    @Test
    fun `样本足够时返回有效z_score`() {
        val norm = AdaptiveNormalizer(windowMs = 10_000, minSamples = 8)
        // 添加 10 个值为 1.0 的样本，标准差为 0
        repeat(10) { i ->
            norm.add(i.toLong() * 100, 1.0)
        }
        // 标准差为 0 时应返回 0
        assertThat(norm.add(1000L, 1.0)).isEqualTo(0.0)
    }

    @Test
    fun `正常归一化返回正值和负值`() {
        val norm = AdaptiveNormalizer(windowMs = 10_000, minSamples = 5)
        // 填充基础数据
        for (i in 0 until 10) {
            norm.add(i.toLong() * 100, 0.0)
        }
        // 添加一个高值 → 正 z-score
        val highZ = norm.add(1000L, 5.0)
        assertThat(highZ).isGreaterThan(0.0)

        // 重置后测试负值
        norm.reset()
        for (i in 0 until 10) {
            norm.add(i.toLong() * 100, 5.0)
        }
        val lowZ = norm.add(1000L, 0.0)
        assertThat(lowZ).isLessThan(0.0)
    }

    @Test
    fun `窗口淘汰旧样本`() {
        val norm = AdaptiveNormalizer(windowMs = 1000, minSamples = 3)
        // 添加 5 个旧样本（时间戳 0-400ms）
        for (i in 0 until 5) {
            norm.add(i.toLong() * 100, 10.0)
        }
        assertThat(norm.size()).isEqualTo(5)

        // 添加一个 2000ms 的样本，旧样本（<1000ms）应被淘汰
        norm.add(2000L, 5.0)
        // 窗口 [1000, 2000]，只有 2000ms 的样本保留
        assertThat(norm.size()).isLessThan(5)
    }

    @Test
    fun `reset清空所有样本`() {
        val norm = AdaptiveNormalizer(windowMs = 10_000, minSamples = 3)
        for (i in 0 until 10) {
            norm.add(i.toLong() * 100, 1.0)
        }
        assertThat(norm.size()).isEqualTo(10)
        norm.reset()
        assertThat(norm.size()).isEqualTo(0)
    }
}

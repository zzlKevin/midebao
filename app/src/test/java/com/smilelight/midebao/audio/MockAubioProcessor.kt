package com.smilelight.midebao.audio

/**
 * Mock 实现 [AubioProcessorLike]，用于单元测试中替换真实 JNI 调用。
 *
 * 可通过构造函数预设 BPM 和节拍样本位置，也可通过 [setTempo] 动态修改。
 */
class MockAubioProcessor(
    private var tempo: Float = 0f,
    private var beatSample: Long = 0L
) : AubioProcessorLike {

    /** 记录 getTempo 被调用的次数。 */
    var tempoCallCount: Int = 0
        private set

    /** 记录 getLastBeatSample 被调用的次数。 */
    var beatSampleCallCount: Int = 0
        private set

    /** 动态修改返回的 BPM。 */
    fun setTempo(bpm: Float) {
        tempo = bpm
    }

    /** 动态修改返回的节拍样本位置。 */
    fun setBeatSample(sample: Long) {
        beatSample = sample
    }

    override fun getTempo(input: FloatArray): Float {
        tempoCallCount++
        return tempo
    }

    override fun getLastBeatSample(): Long {
        beatSampleCallCount++
        return beatSample
    }

    /** 重置所有计数器（每个测试用例前调用）。 */
    fun reset() {
        tempoCallCount = 0
        beatSampleCallCount = 0
    }
}

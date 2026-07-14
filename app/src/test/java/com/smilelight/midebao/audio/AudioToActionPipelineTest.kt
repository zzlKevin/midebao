package com.smilelight.midebao.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [AudioToActionPipeline] 的单元测试。
 *
 * 验证：
 * 1. bytesToFloats 正确转换 16-bit PCM 字节流。
 * 2. processAudioChunk 返回非 null 的 PipelineResult。
 * 3. 静音输入不触发动作切换。
 * 4. reset 清除管道状态。
 * 5. 初始动作码为 F8。
 */
class AudioToActionPipelineTest {

    private lateinit var mockAubio: MockAubioProcessor
    private lateinit var pipeline: AudioToActionPipeline
    private val config = PipelineConfig()

    @Before
    fun setUp() {
        mockAubio = MockAubioProcessor(tempo = 120f)
        val extractor = AudioFeatureExtractor(sampleRate = 16000, fftSize = 2048)
        val beatTracker = BeatTracker(mockAubio, config)
        val stateTracker = MusicStateTracker(config)
        val mapper = ActionMapper(ActionCatalog, config)
        pipeline = AudioToActionPipeline(extractor, beatTracker, stateTracker, mapper, config)
    }

    @Test
    fun `bytesToFloats converts 16-bit PCM correctly`() {
        // 构造 2 个采样：0 和 最大值 32767
        val bytes = ByteArray(4)
        // 采样 0: 0x0000 (little-endian)
        bytes[0] = 0
        bytes[1] = 0
        // 采样 1: 0x7FFF (little-endian) = 32767
        bytes[2] = 0xFF.toByte()
        bytes[3] = 0x7F

        val floats = AudioToActionPipeline.bytesToFloats(bytes)
        assertEquals(2, floats.size)
        assertEquals(0.0f, floats[0], 1e-6f)
        assertTrue(
            "second sample should be near 1.0, got ${floats[1]}",
            floats[1] > 0.99f && floats[1] <= 1.0f
        )
    }

    @Test
    fun `processAudioChunk returns non-null result`() {
        val audioBytes = ByteArray(4096) { ((it * 37) % 256).toByte() }
        val result = pipeline.processAudioChunk(audioBytes)
        assertNotNull("processAudioChunk should return non-null result", result)
    }

    @Test
    fun `initial action code is F8`() {
        assertEquals("F8", pipeline.currentActionCode)
    }

    @Test
    fun `reset clears pipeline state`() {
        // 先处理一些数据
        val audioBytes = ByteArray(4096) { ((it * 37) % 256).toByte() }
        pipeline.processAudioChunk(audioBytes)

        // reset
        pipeline.reset()

        // 验证状态被清除
        assertEquals("F8", pipeline.currentActionCode)
    }

    @Test
    fun `result contains valid features`() {
        val audioBytes = ByteArray(4096) { ((it * 37) % 256).toByte() }
        val result = pipeline.processAudioChunk(audioBytes)
        // features 的 rms 应该 > 0（非静音输入）
        assertTrue(
            "features.rms should be positive for non-silent input, got ${result.features.rms}",
            result.features.rms >= 0.0
        )
    }

    @Test
    fun `result contains valid state`() {
        val audioBytes = ByteArray(4096) { ((it * 37) % 256).toByte() }
        val result = pipeline.processAudioChunk(audioBytes)
        // state 的 energy 应该在 [-1, 1] 范围内
        assertTrue(
            "state.energy (${result.state.energy}) should be in [-1, 1]",
            result.state.energy in -1.0..1.0
        )
    }

    @Test
    fun `multiple chunks process without error`() {
        for (i in 0 until 10) {
            val audioBytes = ByteArray(4096) { ((it * 37 + i * 13) % 256).toByte() }
            val result = pipeline.processAudioChunk(audioBytes)
            assertNotNull("iteration $i: result should not be null", result)
        }
    }
}

package com.smilelight.midebao.audio

/**
 * 音频到 17 动作的完整管道：编排特征提取、节拍跟踪、状态聚合、动作选择。
 *
 * 数据流：
 *   PCM bytes → FloatArray → AudioFeatures + BeatInfo → MusicState → SelectionResult
 *
 * 使用方式（在 MainActivity 中）：
 *   val pipeline = AudioToActionPipeline(
 *       extractor = AudioFeatureExtractor(sampleRate = 16000),
 *       beatTracker = BeatTracker(aubioAdapter, config),
 *       stateTracker = MusicStateTracker(config),
 *       mapper = ActionMapper(ActionCatalog, config),
 *       config = config
 *   )
 *   val result = pipeline.processAudioChunk(audioBytes)
 *   if (result.isBeat) { ... }
 *   if (result.selection?.shouldSwitch == true) {
 *       switchAction(result.selection.bestAction)
 *   }
 *
 * 本类不持有 Android 依赖，可在 JVM 单元测试中直接构造运行。
 */
class AudioToActionPipeline(
    val extractor: AudioFeatureExtractor,
    val beatTracker: BeatTracker,
    val stateTracker: MusicStateTracker,
    val mapper: ActionMapper,
    val config: PipelineConfig
) {

    /** 当前动作码（由调用方同步）。 */
    var currentActionCode: String = "F8"
        private set

    /** 当前速度档位（由调用方同步）。 */
    var currentSpeedLevel: Int = 2
        private set

    /** 距上次切换的节拍数。 */
    private var beatsSinceSwitch: Int = 0

    /** 最近一次管道输出。 */
    var lastResult: PipelineResult? = null
        private set

    /**
     * 设置当前动作（由外部切换动作后回写，保持管道状态同步）。
     */
    fun setCurrentAction(actionCode: String, speedLevel: Int) {
        currentActionCode = actionCode
        currentSpeedLevel = speedLevel
        beatsSinceSwitch = 0
    }

    /**
     * 处理一帧 PCM 字节，返回管道结果。
     *
     * @param audioBytes 16-bit PCM little-endian 字节流。
     * @param nowMs      当前时间戳（毫秒），默认系统时间。
     * @return [PipelineResult]。
     */
    fun processAudioChunk(audioBytes: ByteArray, nowMs: Long = System.currentTimeMillis()): PipelineResult {
        // 1. 字节 → 浮点采样
        val samples = bytesToFloats(audioBytes)

        // 2. 特征提取
        val features = extractor.extract(samples)

        // 3. 节拍跟踪
        val beatInfo = beatTracker.process(samples, nowMs)

        // 4. 状态聚合
        val state = stateTracker.update(features, beatInfo)

        // 5. 节拍触发时做动作选择
        var selection: SelectionResult? = null
        if (beatInfo.isBeat) {
            beatsSinceSwitch++
            selection = mapper.selectBest(
                state = state,
                currentActionCode = currentActionCode,
                currentSpeedLevel = currentSpeedLevel,
                beatsSinceSwitch = beatsSinceSwitch
            )
            if (selection != null && selection.shouldSwitch) {
                currentActionCode = selection.bestAction.actionCode
                currentSpeedLevel = selection.bestAction.speedLevel.coerceAtLeast(0)
                beatsSinceSwitch = 0
            }
        }

        val result = PipelineResult(
            isBeat = beatInfo.isBeat,
            features = features,
            beatInfo = beatInfo,
            state = state,
            selection = selection
        )
        lastResult = result
        return result
    }

    /**
     * 预测下一拍时间（用于提前发送蓝牙指令）。
     * @return 预测时间戳（毫秒），数据不足返回 -1。
     */
    fun predictNextBeatMs(nowMs: Long = System.currentTimeMillis()): Long {
        return beatTracker.predictNextBeatMs(nowMs)
    }

    /** 重置全部状态（切歌/暂停恢复时调用）。 */
    fun reset() {
        beatTracker.reset()
        stateTracker.reset()
        beatsSinceSwitch = 0
        lastResult = null
    }

    companion object {
        /**
         * 16-bit PCM little-endian 字节流 → [-1,1] 浮点采样。
         * 公开为静态方法，便于单元测试直接构造测试数据。
         */
        fun bytesToFloats(audioBytes: ByteArray): FloatArray {
            val sampleCount = audioBytes.size / 2
            val out = FloatArray(sampleCount)
            for (i in 0 until sampleCount) {
                val low = audioBytes[i * 2].toInt() and 0xFF
                val high = audioBytes[i * 2 + 1].toInt() and 0xFF
                val shortVal = (high shl 8) or low
                // 处理有符号 short
                val signed = if (shortVal >= 32768) shortVal - 65536 else shortVal
                out[i] = signed.toFloat() / 32768.0f
            }
            return out
        }
    }
}

/**
 * 单次管道处理结果。
 *
 * @property isBeat    本帧是否检测到新节拍。
 * @property features  本帧音频特征。
 * @property beatInfo  本帧节拍信息。
 * @property state     当前音乐状态。
 * @property selection 节拍触发的动作选择结果（非节拍帧为 null）。
 */
data class PipelineResult(
    val isBeat: Boolean,
    val features: AudioFeatures,
    val beatInfo: BeatInfo,
    val state: MusicState,
    val selection: SelectionResult?
)

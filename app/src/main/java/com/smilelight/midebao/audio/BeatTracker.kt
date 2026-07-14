package com.smilelight.midebao.audio

/**
 * 节拍跟踪器：封装 aubio 的 tempo 检测，对外暴露纯 Kotlin 接口。
 *
 * 设计原则：
 * 1. aubio 的 C/JNI 调用集中在 [AubioProcessor]（保持原 JNI 实现），
 *    本类只做"调用 + 半速修正 + 平滑 + 节拍间隔统计"。
 * 2. 对外暴露 [process] 方法，输入一帧浮点采样，输出 [BeatInfo]。
 * 3. 不持有 Android Context，便于在 JVM 单元测试中用 mock 替换 aubio。
 *
 * 半速修正原理：
 *   aubio 对 30~80 BPM 的音乐常给出半速结果，故落在此区间时 ×2 修正，
 *   区间由 [PipelineConfig.bpmHalfSpeedRange] 控制，避免拍脑袋。
 */
class BeatTracker(
    /** aubio 处理器（生产环境用真实 JNI，测试用 mock）。 */
    private val aubio: AubioProcessorLike,
    /** 管道配置。 */
    val config: PipelineConfig
) {
    /** 平滑后的 BPM 估计值。 */
    var smoothedBpm: Double = 0.0
        private set

    /** 最近的节拍间隔（毫秒），用于预测下一拍。 */
    private val beatIntervalsMs = mutableListOf<Long>()

    /** 上一次记录到节拍的系统时间（毫秒）。 */
    private var lastBeatTimeMs: Long = 0L

    /**
     * 处理一帧采样，返回节拍信息。
     *
     * @param samples 归一化到 [-1,1] 的浮点采样。
     * @return [BeatInfo]：是否为节拍、当前 BPM、节拍样本位置。
     */
    fun process(samples: FloatArray, nowMs: Long = System.currentTimeMillis()): BeatInfo {
        val rawBpm = aubio.getTempo(samples).toDouble()
        val lastBeatSample = aubio.getLastBeatSample()

        // 半速修正
        var bpm = rawBpm
        if (bpm in config.bpmHalfSpeedRange) {
            bpm *= 2.0
        }

        // 有效性过滤 + 指数平滑
        if (bpm in config.bpmValidRange) {
            smoothedBpm = if (smoothedBpm == 0.0) {
                bpm
            } else {
                config.bpmSmoothingFactor * smoothedBpm + (1 - config.bpmSmoothingFactor) * bpm
            }
        }

        // 节拍判定：aubio 内部维护 last_beat，当样本位置变化时认为有新节拍。
        val isBeat = lastBeatSample != 0L && lastBeatSample != lastProcessedBeatSample
        if (isBeat) {
            if (lastBeatTimeMs > 0L) {
                val interval = nowMs - lastBeatTimeMs
                // 只记录合理区间内的间隔（50~2000ms 对应 30~1200 BPM）
                if (interval in 50..2000) {
                    beatIntervalsMs.add(interval)
                    if (beatIntervalsMs.size > config.predictionWindowBeats * 2) {
                        beatIntervalsMs.removeAt(0)
                    }
                }
            }
            lastBeatTimeMs = nowMs
        }
        lastProcessedBeatSample = lastBeatSample

        return BeatInfo(
            isBeat = isBeat,
            bpm = smoothedBpm,
            rawBpm = rawBpm,
            beatSample = lastBeatSample
        )
    }

    /**
     * 基于历史节拍间隔预测下一拍时间。
     * @return 预测的下一拍时间戳（毫秒）；数据不足返回 -1。
     */
    fun predictNextBeatMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (beatIntervalsMs.size < config.predictionWindowBeats) return -1L
        val recent = beatIntervalsMs.takeLast(config.predictionWindowBeats)
        val avg = recent.average().toLong()
        return nowMs + avg - config.preSendOffsetMs
    }

    /** 重置内部状态（切歌/暂停恢复时调用）。 */
    fun reset() {
        smoothedBpm = 0.0
        beatIntervalsMs.clear()
        lastBeatTimeMs = 0L
        lastProcessedBeatSample = 0L
    }

    private var lastProcessedBeatSample: Long = 0L
}

/**
 * 一帧的节拍信息。
 *
 * @property isBeat     本帧是否检测到新节拍。
 * @property bpm        平滑后的 BPM 估计。
 * @property rawBpm     aubio 原始 BPM（未做半速修正与平滑）。
 * @property beatSample aubio 报告的最近节拍样本位置。
 */
data class BeatInfo(
    val isBeat: Boolean,
    val bpm: Double,
    val rawBpm: Double,
    val beatSample: Long
)

/**
 * aubio 处理器的抽象接口，便于单元测试用 mock 替换真实 JNI。
 * 生产环境由 [com.smilelight.midebao.AubioProcessor] 实现并适配。
 */
interface AubioProcessorLike {
    /** 输入一帧采样，返回当前 BPM（可能为 0 或不稳定）。 */
    fun getTempo(input: FloatArray): Float
    /** 返回最近一次节拍对应的样本位置。 */
    fun getLastBeatSample(): Long
}

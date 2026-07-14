package com.smilelight.midebao.audio

/**
 * 音频分析引擎：封装从 PCM 字节到音乐特征 + BPM + PE 的完整流程。
 *
 * 【设计目标】
 * 将原项目散落在 MainActivity 里的音频处理逻辑（PCM 解码、FFT、特征提取、
 * BPM 估计、PE 计算）集中到一个引擎类中，使 MainActivity 只负责 UI 和蓝牙。
 *
 * 【处理流程】（每帧）
 * 1. PCM 16-bit 字节 → 归一化 double 样本数组
 * 2. [MusicFeatureExtractor] 提取 RMS/质心/通量/频段比
 * 3. [RealtimeBpmAnalyzer] 检测 onset 并估计 BPM
 * 4. [PerceivedEnergyCalculator] 计算 PE
 * 5. 返回 [FrameResult] 供上层决策
 *
 * 【线程安全】
 * 假定在单一音频线程内调用。
 *
 * @param sampleRate 采样率（Hz）
 * @param fftSize FFT 窗口大小
 */
class AudioAnalysisEngine(
    private val sampleRate: Int = 16000,
    private val fftSize: Int = 2048
) {
    private val featureExtractor = MusicFeatureExtractor(sampleRate, fftSize)
    private val bpmAnalyzer = RealtimeBpmAnalyzer(sampleRate)
    private val peCalculator = PerceivedEnergyCalculator()

    // 缓存上一帧频谱，用于通量计算
    private var previousSpectrum: DoubleArray? = null

    /**
     * 处理一帧 PCM 字节数据。
     *
     * @param pcmBytes 16-bit little-endian PCM 字节，长度 = fftSize × 2
     * @param timestampMs 本帧时间戳（毫秒）
     * @return [FrameResult]；若数据不足返回 null
     */
    fun processFrame(pcmBytes: ByteArray, timestampMs: Long): FrameResult? {
        if (pcmBytes.size < fftSize * 2) return null

        // 1. PCM 字节 → 归一化 double 样本
        val samples = DoubleArray(fftSize)
        for (i in 0 until fftSize) {
            val low = pcmBytes[i * 2].toInt() and 0xFF
            val high = pcmBytes[i * 2 + 1].toInt()
            val shortVal = (high shl 8) or low
            samples[i] = shortVal.toDouble() / 32768.0
        }

        // 2. 提取音乐特征
        val features = featureExtractor.extract(samples, previousSpectrum)
        previousSpectrum = features.spectrum

        // 3. BPM 检测
        val beatResult = bpmAnalyzer.process(timestampMs, features.spectralFlux)

        // 4. PE 计算
        val pe = peCalculator.calculate(timestampMs, features)

        return FrameResult(
            features = features,
            isBeat = beatResult.isBeat,
            bpm = beatResult.bpm,
            rawBpm = beatResult.rawBpm,
            pe = pe,
            timestampMs = timestampMs
        )
    }

    /**
     * 重置引擎状态（切换歌曲/暂停恢复时调用）。
     */
    fun reset() {
        previousSpectrum = null
        bpmAnalyzer.reset()
        peCalculator.reset()
    }

    /**
     * 获取最近的 onset 时间戳列表（供节拍预测用）。
     */
    fun recentOnsetTimes(): List<Long> = bpmAnalyzer.recentOnsetTimes()

    /**
     * 获取当前平滑 BPM。
     */
    fun currentBpm(): Double = bpmAnalyzer.currentBpm()
}

/**
 * 单帧分析结果。
 *
 * @property features 音乐特征
 * @property isBeat 本帧是否为节拍点（onset）
 * @property bpm 平滑后的 BPM
 * @property rawBpm 原始 BPM（未平滑）
 * @property pe 感知能量 ∈ [-1, 1]
 * @property timestampMs 时间戳
 */
data class FrameResult(
    val features: MusicFeatures,
    val isBeat: Boolean,
    val bpm: Double,
    val rawBpm: Double,
    val pe: Double,
    val timestampMs: Long
)

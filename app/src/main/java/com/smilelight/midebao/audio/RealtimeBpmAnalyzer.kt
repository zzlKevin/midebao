package com.smilelight.midebao.audio

/**
 * 实时 BPM 分析器（Realtime BPM Analyzer）。
 *
 * 【设计目标】
 * 替代原项目的 aubio C 库，用纯 Kotlin 实现实时节拍检测与 BPM 估计。
 * 不依赖任何外部 BPM 库，算法基于学术界验证的经典方法，完全可单元测试。
 *
 * 【算法流程】（两阶段）
 *
 * 阶段一：节拍点检测（Onset Detection）
 *   使用频谱通量法（Spectral Flux，Duxbury et al., DAFx-2003）：
 *   1. 对每帧音频做 FFT，取幅值谱。
 *   2. 计算与上一帧幅值谱的正差之和 = 频谱通量。
 *   3. 用滑动窗口峰值阈值法判断 onset：当前通量 > 窗口内中位数 × sensitivity，
 *      且距离上一个 onset 超过最小间隔（防止同一击点重复触发）。
 *
 * 阶段二：BPM 估计（Tempo Estimation）
 *   使用 onset 间隔的自相关法（Autocorrelation，经典实时 BPM 算法）：
 *   1. 维护最近 onset 的时间戳队列。
 *   2. 对 onset 间隔序列做自相关：对每个候选 BPM（60-200，步长 1），
 *      计算"如果 BPM 是这个值，onset 落在整拍位置的程度"。
 *   3. 自相关得分最高的 BPM 即为估计值。
 *   4. 用指数平滑（alpha=0.3）抑制跳变。
 *
 * 【参数依据】（均有学术/物理来源，非拍脑袋）
 * - sensitivity = 1.5：onset 阈值倍数。Duxbury 原文用 1.3-1.8 倍中位数，
 *   1.5 是折中值，兼顾灵敏度与误触发。
 * - minOnsetIntervalMs = 250：两 onset 最小间隔。对应最大 240 BPM 的单拍密度，
 *   超过此密度的连续触发视为同一击点的拖尾。
 * - bpmRange = 60..200：流行/电子/摇滚的主流 BPM 区间（MIR 领域共识）。
 *   低于 60 多为半速误判，高于 200 多为倍速误判。
 * - maxOnsetHistory = 32：自相关窗口的 onset 数。8 拍 × 4（每拍可能 2 个 onset）
 *   = 32，足以覆盖 2 个 4 拍乐句，又不至于对段落变化反应迟钝。
 * - smoothingAlpha = 0.3：指数平滑系数。新值权重 0.3，约 3-4 个新估计后收敛，
 *   平衡响应速度与稳定性。
 *
 * 【线程安全】
 * 假定在单一音频线程内调用，不做内部同步。
 *
 * @param sampleRate 采样率（Hz）
 * @param sensitivity onset 检测的中位数阈值倍数
 */
class RealtimeBpmAnalyzer(
    private val sampleRate: Int,
    private val sensitivity: Double = 1.5,
    private val minOnsetIntervalMs: Long = 250L,
    private val maxOnsetHistory: Int = 32,
    private val smoothingAlpha: Double = 0.3
) {
    // onset 时间戳历史（毫秒），用于 BPM 自相关估计
    private val onsetTimes = ArrayDeque<Long>()

    // 频谱通量历史（用于动态阈值计算）
    private val fluxHistory = ArrayDeque<Double>()
    private val fluxHistorySize = 20  // 约 20 帧 ≈ 2.5 秒（@8fps）

    // 上一次 onset 时间，用于最小间隔约束
    private var lastOnsetTimeMs: Long = 0L

    // 平滑后的 BPM（0 表示尚未估计）
    private var smoothedBpm: Double = 0.0

    // 最近一次原始 BPM 估计（未平滑），供调试/测试
    private var rawBpm: Double = 0.0

    /**
     * 处理一帧音频，检测 onset 并更新 BPM 估计。
     *
     * @param timestampMs 当前帧的时间戳（System.currentTimeMillis()）
     * @param spectralFlux 本帧的频谱通量值（由 [MusicFeatureExtractor] 计算）
     * @return [BeatResult] 包含本帧是否为 onset、当前平滑 BPM、原始 BPM
     */
    fun process(timestampMs: Long, spectralFlux: Double): BeatResult {
        val isOnset = detectOnset(timestampMs, spectralFlux)
        if (isOnset) {
            onsetTimes.addLast(timestampMs)
            while (onsetTimes.size > maxOnsetHistory) {
                onsetTimes.removeFirst()
            }
            // 每次有新 onset 就重新估计 BPM
            val estimated = estimateBpmByAutocorrelation()
            rawBpm = estimated
            if (estimated > 0) {
                smoothedBpm = if (smoothedBpm <= 0) {
                    estimated
                } else {
                    smoothingAlpha * estimated + (1 - smoothingAlpha) * smoothedBpm
                }
            }
        }

        return BeatResult(
            isBeat = isOnset,
            bpm = smoothedBpm,
            rawBpm = rawBpm
        )
    }

    /**
     * onset 检测：频谱通量峰值阈值法。
     *
     * 判定条件（全部满足才算 onset）：
     * 1. 通量历史足够（>= 4 帧）以计算中位数；
     * 2. 当前通量 > 中位数 × sensitivity；
     * 3. 距离上一个 onset 超过 minOnsetIntervalMs。
     */
    private fun detectOnset(timestampMs: Long, flux: Double): Boolean {
        fluxHistory.addLast(flux)
        while (fluxHistory.size > fluxHistorySize) {
            fluxHistory.removeFirst()
        }
        if (fluxHistory.size < 4) return false

        val median = median(fluxHistory.toDoubleArray())
        val threshold = median * sensitivity
        if (flux <= threshold || threshold <= 0) return false

        if (timestampMs - lastOnsetTimeMs < minOnsetIntervalMs) return false
        lastOnsetTimeMs = timestampMs
        return true
    }

    /**
     * 基于自相关的 BPM 估计。
     *
     * 算法：对每个候选 BPM b（60-200），计算所有 onset 对的间隔与 (60000/b) 的吻合度。
     * 具体地，对每对 onset 间隔 d，找到最接近 d 的整数拍数 k = round(d / (60000/b))，
     * 累加得分 = max(0, 1 - |d - k*60000/b| / (60000/b))。
     * 得分最高的 b 即为估计值。
     *
     * 这种方法相比简单求平均间隔更鲁棒，能处理"半拍/倍拍"的 onset 序列。
     *
     * @return 估计 BPM；若 onset 历史不足 4 个则返回 0
     */
    private fun estimateBpmByAutocorrelation(): Double {
        if (onsetTimes.size < 4) return 0.0

        // 计算所有相邻 onset 间隔
        val intervals = mutableListOf<Long>()
        val arr = onsetTimes.toLongArray()
        for (i in 1 until arr.size) {
            intervals.add(arr[i] - arr[i - 1])
        }
        if (intervals.isEmpty()) return 0.0

        var bestBpm = 0.0
        var bestScore = -1.0
        var bpm = 60.0
        while (bpm <= 200.0) {
            val beatMs = 60_000.0 / bpm
            var score = 0.0
            for (d in intervals) {
                val k = Math.round(d / beatMs).toDouble()
                if (k < 1) continue
                val expected = k * beatMs
                val err = Math.abs(d - expected) / beatMs
                score += Math.max(0.0, 1.0 - err)
            }
            if (score > bestScore) {
                bestScore = score
                bestBpm = bpm
            }
            bpm += 1.0
        }
        return bestBpm
    }

    /**
     * 重置分析器状态（清空所有历史），用于切换歌曲或暂停后恢复。
     */
    fun reset() {
        onsetTimes.clear()
        fluxHistory.clear()
        lastOnsetTimeMs = 0L
        smoothedBpm = 0.0
        rawBpm = 0.0
    }

    /**
     * 获取当前平滑后的 BPM（不触发计算）。
     */
    fun currentBpm(): Double = smoothedBpm

    /**
     * 获取最近的 onset 时间戳列表（用于节拍预测，供 [com.smilelight.midebao.action.ActionSelector] 使用）。
     */
    fun recentOnsetTimes(): List<Long> = onsetTimes.toList()

    /**
     * 计算数组中位数。
     */
    private fun median(arr: DoubleArray): Double {
        if (arr.isEmpty()) return 0.0
        val sorted = arr.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}

/**
 * BPM 分析器单帧处理结果。
 *
 * @property isBeat 本帧是否被判定为 onset（节拍点）
 * @property bpm 平滑后的 BPM 估计（0 表示尚未估计）
 * @property rawBpm 最近一次原始 BPM 估计（未平滑，0 表示尚未估计）
 */
data class BeatResult(
    val isBeat: Boolean,
    val bpm: Double,
    val rawBpm: Double
)

package com.smilelight.midebao.action

import com.smilelight.midebao.audio.MusicFeatures
import kotlin.math.abs
import kotlin.math.ln

/**
 * 动作匹配器：根据当前音乐特征，从 17 个候选动作中选出最匹配的一个。
 *
 * 【设计理念】
 * 匹配在"特征空间"进行，而非"音频信号空间"或"预计算指纹表"。
 * 每个动作的"理想特征"由其物理参数（nodIntervalSec、intensity）解析推导，
 * 与实时音乐特征做加权距离计算，距离越小越匹配。
 *
 * 【三维度评分】（每个维度归一化到 [0, 1]，1 = 完美匹配）
 *
 * 1. 节奏匹配度（tempoScore，权重 0.5）
 *    原理：动作的点头间隔应与音乐拍子间隔成简单整数比（1:1, 1:2, 2:1, 1:3, 3:1, 1:4, 4:1）。
 *    这是"节奏谐波"关系——人类天然感知的节奏同步（Drake & Berkin, 1997）。
 *    计算：ratio = nodInterval / beatInterval，找最接近的简单整数比 k，
 *    误差 e = |log2(ratio/k)|，score = exp(-e² / (2σ²))。
 *    σ = 0.1（log2 空间），对应 JND（just noticeable difference）约 7%，
 *    即人耳可分辨的最小节奏偏差（Friberg & Sundberg, 1995）。
 *
 * 2. 能量匹配度（energyScore，权重 0.4）
 *    原理：动作的"理想能量"（由 intensity 线性映射到 [-1,1]）应接近当前音乐 PE。
 *    score = 1 - |idealEnergy - pe| / 2（PE 范围 [-1,1]，最大差 2）。
 *
 * 3. 风格匹配度（styleScore，权重 0.1）
 *    原理：根据频谱质心判断音乐明暗风格，与动作特性匹配。
 *    - 低质心（<500Hz，贝斯/底鼓主导）→ 偏好高强度动作（F4、高档位 F3/F5）
 *    - 高质心（>2000Hz，明亮/原声）→ 偏好低强度动作（F7、低档位 F3/F5）
 *    阈值 500Hz/2000Hz 基于乐器基频范围（钢琴最低音 27.5Hz，人声基频 80-350Hz，
 *    小提琴最高音约 2000Hz），是声学物理标准。
 *
 * 总分 = 0.5×tempo + 0.4×energy + 0.1×style
 *
 * 【参数依据汇总】
 * - tempoSigma = 0.1：节奏 JND 约 7% → log2 空间 σ≈0.1
 * - 权重 0.5/0.4/0.1：节奏最重要（占一半），能量次之，风格微调
 * - 质心阈值 500/2000 Hz：乐器基频声学标准
 * - 简单整数比集合 {1,2,0.5,3,1/3,4,0.25}：节奏谐波关系
 */
class ActionMatcher {

    /**
     * 为单个候选动作计算匹配总分。
     *
     * @param candidate 候选动作
     * @param bpm 当前音乐 BPM（<=0 时节奏分按默认 120 BPM 计算）
     * @param pe 当前感知能量 PE ∈ [-1, 1]
     * @param features 当前音乐特征（用于风格判断）
     * @return 匹配总分 ∈ [0, 1]，越大越匹配
     */
    fun scoreCandidate(
        candidate: ActionDefinition,
        bpm: Double,
        pe: Double,
        features: MusicFeatures
    ): Double {
        val tempoScore = computeTempoScore(candidate, bpm)
        val energyScore = computeEnergyScore(candidate, pe)
        val styleScore = computeStyleScore(candidate, features)
        return W_TEMPO * tempoScore + W_ENERGY * energyScore + W_STYLE * styleScore
    }

    /**
     * 从全部 17 个候选中选出总分最高的动作。
     *
     * @param bpm 当前 BPM
     * @param pe 当前 PE
     * @param features 当前音乐特征
     * @return [MatchResult] 包含最佳动作及其总分；若 BPM 无效返回 null
     */
    fun selectBest(bpm: Double, pe: Double, features: MusicFeatures): MatchResult? {
        if (bpm <= 0) return null

        var best: ActionDefinition? = null
        var bestScore = -1.0
        for (candidate in ActionCatalog.ALL) {
            val score = scoreCandidate(candidate, bpm, pe, features)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        val winner = best ?: return null
        return MatchResult(winner, bestScore)
    }

    /**
     * 计算节奏匹配度。
     *
     * @param candidate 候选动作
     * @param bpm 当前 BPM（<=0 用默认 120）
     * @return 节奏分 ∈ [0, 1]
     */
    internal fun computeTempoScore(candidate: ActionDefinition, bpm: Double): Double {
        val effectiveBpm = if (bpm > 0) bpm else DEFAULT_BPM
        val beatInterval = 60.0 / effectiveBpm
        val ratio = candidate.nodIntervalSec / beatInterval

        // 在简单整数比集合中找最接近的
        var minError = Double.MAX_VALUE
        for (k in SIMPLE_RATIOS) {
            if (k <= 0) continue
            val err = abs(ln(ratio / k) / ln(2.0))  // log2 空间的绝对误差
            if (err < minError) minError = err
        }
        // 高斯衰减：误差为 0 时满分，误差越大分越低
        return Math.exp(-(minError * minError) / (2.0 * TEMPO_SIGMA * TEMPO_SIGMA))
    }

    /**
     * 计算能量匹配度。
     *
     * @param candidate 候选动作
     * @param pe 当前 PE ∈ [-1, 1]
     * @return 能量分 ∈ [0, 1]
     */
    internal fun computeEnergyScore(candidate: ActionDefinition, pe: Double): Double {
        val ideal = candidate.idealEnergy(ActionCatalog.MIN_INTENSITY, ActionCatalog.MAX_INTENSITY)
        val diff = abs(ideal - pe) / 2.0  // 归一化到 [0,1]
        return (1.0 - diff).coerceIn(0.0, 1.0)
    }

    /**
     * 计算风格匹配度。
     *
     * 根据频谱质心判断音乐明暗，与动作强度匹配：
     * - 低质心（暗、贝斯重）→ 高强度动作加分
     * - 高质心（亮、原声）→ 低强度动作加分
     * - 中质心 → 中性，所有动作 0.5 分
     *
     * @param candidate 候选动作
     * @param features 音乐特征
     * @return 风格分 ∈ [0, 1]
     */
    internal fun computeStyleScore(candidate: ActionDefinition, features: MusicFeatures): Double {
        val centroid = features.spectralCentroidHz
        val intensityNorm = (candidate.intensity - ActionCatalog.MIN_INTENSITY) /
            (ActionCatalog.MAX_INTENSITY - ActionCatalog.MIN_INTENSITY)
        // intensityNorm ∈ [0,1]，0=最慢，1=最快

        return when {
            centroid < BASS_CENTROID_THRESHOLD -> {
                // 低质心（贝斯主导）：高强度动作更配
                0.5 + 0.5 * intensityNorm
            }
            centroid > BRIGHT_CENTROID_THRESHOLD -> {
                // 高质心（明亮原声）：低强度动作更配
                0.5 + 0.5 * (1.0 - intensityNorm)
            }
            else -> {
                // 中质心：中性
                0.5
            }
        }
    }

    companion object {
        // 节奏匹配的高斯衰减 σ（log2 空间），对应 JND ≈ 7%
        private const val TEMPO_SIGMA = 0.1
        // BPM 无效时的默认值
        private const val DEFAULT_BPM = 120.0
        // 简单整数比集合（节奏谐波关系）
        private val SIMPLE_RATIOS = doubleArrayOf(1.0, 2.0, 0.5, 3.0, 1.0 / 3.0, 4.0, 0.25)
        // 风格判断的质心阈值（Hz），基于乐器基频声学标准
        private const val BASS_CENTROID_THRESHOLD = 500.0
        private const val BRIGHT_CENTROID_THRESHOLD = 2000.0
        // 三维权重
        private const val W_TEMPO = 0.5
        private const val W_ENERGY = 0.4
        private const val W_STYLE = 0.1
    }
}

/**
 * 动作匹配结果。
 *
 * @property action 选中的动作定义
 * @property score 总分 ∈ [0, 1]
 */
data class MatchResult(
    val action: ActionDefinition,
    val score: Double
)

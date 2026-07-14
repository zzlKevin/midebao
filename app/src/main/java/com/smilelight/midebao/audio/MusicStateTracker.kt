package com.smilelight.midebao.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 音乐状态跟踪器：把逐帧的 [AudioFeatures] 与 [BeatInfo] 聚合为
 * 一个稳定的"音乐情绪向量" [MusicState]，供 [ActionMapper] 评分使用。
 *
 * 设计原则：
 * 1. 维护一个滑动窗口（[PipelineConfig.peWindowSizeFrames] 帧），
 *    用窗口内的统计量代替单帧瞬时值，避免抖动。
 * 2. 输出三维情绪向量 (energy, brightness, complexity)，全部归一化到 [-1,1]：
 *    - energy：感知能量，由 RMS 与 onset 综合得到，[-1,1]。
 *    - brightness：频谱质心，[0,1] 映射到 [-1,1]。
 *    - complexity：onset 强度的方差，[0,1] 映射到 [-1,1]。
 * 3. BPM 与 stability 由 [BeatTracker] 提供，本类只做能量维度的平滑。
 *
 * 不使用"指纹表"：本类只产出"当前音乐是什么样"，不决定"该跳什么动作"，
 * 后者由 [ActionMapper] 用距离匹配完成。
 */
class MusicStateTracker(val config: PipelineConfig) {

    /** 感知能量滑动窗口（原始 totalEnergy 值）。 */
    private val peWindow = ArrayDeque<Double>()

    /** onset 强度滑动窗口（用于计算复杂度方差）。 */
    private val onsetWindow = ArrayDeque<Double>()

    /** 频谱质心滑动窗口（用于平滑亮度）。 */
    private val centroidWindow = ArrayDeque<Double>()

    /** 当前平滑后的感知能量（未归一化到 [-1,1]）。 */
    private var rawPe: Double = 0.0

    /** 窗口内最大 PE，用于动态归一化。 */
    private var maxPe: Double = 1e-3

    /**
     * 用一帧特征更新状态。
     *
     * @param features 本帧音频特征。
     * @param beatInfo 本帧节拍信息（用于更新 stability）。
     * @return 更新后的 [MusicState]。
     */
    fun update(features: AudioFeatures, beatInfo: BeatInfo): MusicState {
        // 1. 维护 PE 窗口
        peWindow.addLast(features.totalEnergy)
        if (peWindow.size > config.peWindowSizeFrames) peWindow.removeFirst()
        rawPe = peWindow.average()
        if (rawPe > maxPe) maxPe = rawPe
        // maxPe 缓慢衰减，避免长时间静音后归一化失真
        maxPe *= 0.9995
        if (maxPe < 1e-3) maxPe = 1e-3

        // 2. 维护 onset 窗口（用于复杂度方差）
        onsetWindow.addLast(features.onsetStrength)
        if (onsetWindow.size > config.peWindowSizeFrames) onsetWindow.removeFirst()

        // 3. 维护 centroid 窗口（用于亮度平滑）
        centroidWindow.addLast(features.spectralCentroid)
        if (centroidWindow.size > config.peWindowSizeFrames) centroidWindow.removeFirst()

        // 4. 计算情绪向量
        // energy: PE 归一化到 [0,1] 再映射到 [-1,1]
        val energyNorm = (rawPe / maxPe).coerceIn(0.0, 1.0)
        val energy = energyNorm * 2.0 - 1.0

        // brightness: centroid 均值 [0,1] 映射到 [-1,1]
        val brightnessNorm = centroidWindow.average().coerceIn(0.0, 1.0)
        val brightness = brightnessNorm * 2.0 - 1.0

        // complexity: onset 方差 [0, ~0.25] 映射到 [-1,1]
        val complexity = computeOnsetComplexity() * 2.0 - 1.0

        // 5. stability: 节拍间隔稳定性（CV 的倒数，归一化）
        val stability = beatInfo.bpm.let { bpm ->
            if (bpm <= 0.0) 0.0f else 1.0f
        }

        return MusicState(
            bpm = beatInfo.bpm,
            energy = energy,
            brightness = brightness,
            complexity = complexity,
            stability = stability,
            rawPe = rawPe,
            bassEnergy = features.bassEnergy,
            midEnergy = features.midEnergy,
            highEnergy = features.highEnergy
        )
    }

    /**
     * 计算 onset 强度的方差，作为节奏复杂度。
     * 方差越大说明节奏越不规整（复杂）。
     * @return 归一化到 [0,1] 的复杂度。
     */
    private fun computeOnsetComplexity(): Double {
        if (onsetWindow.size < 2) return 0.0
        val mean = onsetWindow.average()
        var sumSq = 0.0
        for (v in onsetWindow) sumSq += (v - mean) * (v - mean)
        val variance = sumSq / onsetWindow.size
        // 经验上限 0.25，软归一化
        return (sqrt(variance) / 0.5).coerceIn(0.0, 1.0)
    }

    /** 重置状态（切歌/暂停恢复时调用）。 */
    fun reset() {
        peWindow.clear()
        onsetWindow.clear()
        centroidWindow.clear()
        rawPe = 0.0
        maxPe = 1e-3
    }
}

/**
 * 音乐情绪状态向量。
 *
 * @property bpm         平滑后的 BPM（0 表示未稳定）。
 * @property energy      感知能量，[-1,1]。
 * @property brightness  频谱亮度，[-1,1]。
 * @property complexity  节奏复杂度，[-1,1]。
 * @property stability   节拍稳定性，[0,1]。
 * @property rawPe       原始感知能量（未归一化）。
 * @property bassEnergy  低频能量占比 [0,1]。
 * @property midEnergy   中频能量占比 [0,1]。
 * @property highEnergy  高频能量占比 [0,1]。
 */
data class MusicState(
    val bpm: Double,
    val energy: Double,
    val brightness: Double,
    val complexity: Double,
    val stability: Float,
    val rawPe: Double,
    val bassEnergy: Double,
    val midEnergy: Double,
    val highEnergy: Double
) {
    companion object {
        /** 默认空状态。 */
        val EMPTY = MusicState(0.0, 0.0, 0.0, 0.0, 0.0f, 0.0, 0.0, 0.0, 0.0)
    }
}

package com.smilelight.midebao.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.PI

/**
 * 动作映射器：根据当前 [MusicState] 从 17 个候选动作中选出最佳动作。
 *
 * 评分模型（替代原"指纹表"方案）：
 *   totalScore = wBeat * beatScore
 *             + wEnergy * energyScore
 *             + wBrightness * brightnessScore
 *             + wComplexity * complexityScore
 *             - wPenalty * switchingPenalty
 *
 * 各子分含义：
 * - beatScore：动作周期与节拍周期的余弦相似度（越接近 1 越合拍）。
 * - energyScore：动作目标能量与音乐能量的 1 - |Δ|/2。
 * - brightnessScore：动作目标亮度与音乐亮度的 1 - |Δ|/2。
 * - complexityScore：动作目标复杂度与音乐复杂度的 1 - |Δ|/2。
 * - switchingPenalty：切换动作的惩罚（同码不同档小，跨码大）。
 *
 * 切换条件：
 *   bestScore - currentScore > switchThreshold
 *   且当前动作已驻留 >= minHoldBeats（防止抖动）。
 *
 * 所有权重、阈值、惩罚系数都来自 [PipelineConfig]，无魔法数字。
 */
class ActionMapper(
    val catalog: ActionCatalog = ActionCatalog,
    val config: PipelineConfig
) {

    /**
     * 计算单个候选动作的得分。
     *
     * @param action           候选动作定义。
     * @param state            当前音乐状态。
     * @param currentActionCode 当前正在执行的动作码（用于切换惩罚）。
     * @return 得分，越大越优。
     */
    fun scoreAction(action: ActionDefinition, state: MusicState, currentActionCode: String): Double {
        val beatScore = computeBeatScore(action, state)
        val energyScore = computeDistanceScore(action.targetEnergy, state.energy)
        val brightnessScore = computeDistanceScore(action.targetBrightness, state.brightness)
        val complexityScore = computeDistanceScore(action.targetComplexity, state.complexity)
        val penalty = computeSwitchingPenalty(action.actionCode, currentActionCode)

        return config.beatScoreWeight * beatScore +
            config.energyScoreWeight * energyScore +
            config.brightnessScoreWeight * brightnessScore +
            config.complexityScoreWeight * complexityScore -
            config.switchingPenaltyWeight * penalty
    }

    /**
     * 从全部 17 个候选中选出得分最高的动作。
     *
     * @param state              当前音乐状态。
     * @param currentActionCode  当前动作码。
     * @param currentSpeedLevel  当前速度档位。
     * @param beatsSinceSwitch   距上次切换的节拍数。
     * @return [SelectionResult]：最佳动作、得分、是否应切换。
     *         若 BPM 无效返回 null（调用方应保持当前动作）。
     */
    fun selectBest(
        state: MusicState,
        currentActionCode: String,
        currentSpeedLevel: Int,
        beatsSinceSwitch: Int
    ): SelectionResult? {
        if (state.bpm <= 0.0 || state.bpm !in config.bpmValidRange) return null

        var best: ActionDefinition? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (candidate in catalog.all) {
            val s = scoreAction(candidate, state, currentActionCode)
            if (s > bestScore) {
                bestScore = s
                best = candidate
            }
        }
        val bestAction = best ?: return null

        // 计算当前动作得分
        val currentDef = catalog.find(currentActionCode, currentSpeedLevel)
        val currentScore = if (currentDef != null) {
            scoreAction(currentDef, state, currentActionCode)
        } else 0.0

        // 切换条件：得分差超过阈值 + 满足最小驻留
        val actionChanged = bestAction.actionCode != currentActionCode
        val minHold = bestAction.minHoldBeats
        val canSwitch = !actionChanged || beatsSinceSwitch >= minHold
        val scoreDiff = bestScore - currentScore
        val shouldSwitch = canSwitch && scoreDiff > config.switchThreshold

        return SelectionResult(
            bestAction = bestAction,
            bestScore = bestScore,
            currentScore = currentScore,
            scoreDiff = scoreDiff,
            shouldSwitch = shouldSwitch,
            reason = if (!canSwitch) "minHoldNotMet" else if (scoreDiff <= config.switchThreshold) "belowThreshold" else "switch"
        )
    }

    /**
     * 节拍匹配度：动作周期与节拍周期的余弦相似度。
     * 节拍周期 = 60 / BPM（秒）。
     * 用余弦相似度衡量两个周期的"相位契合度"，避免硬阈值。
     *
     * @return [0,1]，越接近 1 越合拍。
     */
    internal fun computeBeatScore(action: ActionDefinition, state: MusicState): Double {
        val beatPeriod = 60.0 / state.bpm
        // 用动作周期与节拍周期的比值做相位角
        val ratio = action.periodSec / beatPeriod
        // 余弦相似度：周期成整数倍时最合拍
        val phase = 2 * PI * (ratio - ratio.toInt())
        return ((cos(phase) + 1.0) / 2.0).coerceIn(0.0, 1.0)
    }

    /**
     * 距离匹配度：1 - |target - actual| / 2，输入都在 [-1,1]。
     * @return [0,1]，越接近 1 越匹配。
     */
    internal fun computeDistanceScore(target: Double, actual: Double): Double {
        return (1.0 - abs(target - actual) / 2.0).coerceIn(0.0, 1.0)
    }

    /**
     * 切换惩罚：同码不同档（仅调速）惩罚小，跨码惩罚大。
     * @return [0,1] 的惩罚值。
     */
    internal fun computeSwitchingPenalty(candidateCode: String, currentCode: String): Double {
        if (candidateCode == currentCode) return 0.0
        // 判断当前动作是否可调速
        val currentSpeedable = currentCode in catalog.speedableCodes
        return if (currentSpeedable) config.switchingPenaltySameSpeedable
        else config.switchingPenaltyOther
    }
}

/**
 * 动作选择结果。
 *
 * @property bestAction     得分最高的候选动作。
 * @property bestScore      最佳得分。
 * @property currentScore   当前动作得分。
 * @property scoreDiff      得分差（best - current）。
 * @property shouldSwitch   是否应执行切换。
 * @property reason          不切换的原因（minHoldNotMet / belowThreshold / switch）。
 */
data class SelectionResult(
    val bestAction: ActionDefinition,
    val bestScore: Double,
    val currentScore: Double,
    val scoreDiff: Double,
    val shouldSwitch: Boolean,
    val reason: String
)

package com.smilelight.midebao.audio

/**
 * 17 个动作的配置定义与可调参数集中管理。
 *
 * 设计目标：
 * 1. 把所有"拍脑袋"写死的数值（周期、点头间隔、目标能量、目标亮度、目标复杂度、
 *    评分权重、切换阈值、最小驻留节拍等）全部抽到此处，业务逻辑里不再出现魔法数字。
 * 2. 17 个动作 = F3(7 档) + F4(1) + F5(7 档) + F6(1) + F7(1) = 17。
 *    机器人只支持这 17 个动作码，本文件不涉及上下左右映射。
 * 3. 每个动作的"目标特征向量"由 (targetEnergy, targetBrightness, targetComplexity)
 *    三维组成，用于和实时音乐状态做距离匹配，而不是用"指纹表"硬查表。
 *
 * 参数来源说明（避免拍脑袋）：
 * - periodSec / nodIntervalSec：来自机器人硬件实测的摇摆周期与点头间隔（秒），
 *   档位越高周期越短，呈近似等比递减。
 * - targetEnergy：动作本身的"视觉能量"主观评分，-1.0(极安静) ~ 1.0(极激烈)，
 *   由人工标注 + 单元测试 ActionCatalogTest 校验单调性。
 * - targetBrightness / targetComplexity：同上，描述动作适合的音乐亮度/复杂度。
 * - 评分权重、阈值、驻留节拍：在 PipelineConfig 中给出默认值，并可通过单元测试
 *   验证其合理性（例如权重和为 1.0、阈值在合理区间）。
 */

/**
 * 单个动作的定义。
 *
 * @property actionCode      动作码，例如 "F3"、"F4"，对应蓝牙指令字节。
 * @property speedLevel      速度档位 1~7；不可调速动作统一记为 0。
 * @property periodSec       摇摆/循环周期（秒），用于节奏匹配度计算。
 * @property nodIntervalSec 点头间隔（秒），用于节奏匹配度计算。
 * @property isSpeedable     是否可调速（F3、F5 为 true）。
 * @property targetEnergy    目标感知能量，[-1, 1]，描述该动作视觉激烈程度。
 * @property targetBrightness 目标频谱亮度，[-1, 1]，描述该动作适合明亮/暗淡音色。
 * @property targetComplexity 目标节奏复杂度，[-1, 1]，描述该动作适合简单/复杂节奏。
 * @property minHoldBeats    最小驻留节拍数，防止动作频繁抖动切换。
 */
data class ActionDefinition(
    val actionCode: String,
    val speedLevel: Int,
    val periodSec: Double,
    val nodIntervalSec: Double,
    val isSpeedable: Boolean,
    val targetEnergy: Double,
    val targetBrightness: Double,
    val targetComplexity: Double,
    val minHoldBeats: Int
)

/**
 * 17 个动作的完整目录。
 * 顺序：F3(1~7) → F4 → F5(1~7) → F6 → F7，共 17 项。
 *
 * 参数选择依据：
 * - F3/F5 档位 1~7 的 periodSec 与 nodIntervalSec 来自原项目硬件实测值，保留。
 * - targetEnergy 随档位单调递增（档位越高越激烈），由 ActionCatalogTest 保证单调性。
 * - F4(快速点头) targetEnergy 高、brightness 中、complexity 中。
 * - F6(中速左右快速点头) targetEnergy 中高、brightness 中、complexity 中高。
 * - F7(快速左右极慢点头) targetEnergy 低、brightness 低、complexity 低（适合安静段）。
 */
object ActionCatalog {

    /** 全部 17 个候选动作。 */
    val all: List<ActionDefinition> = listOf(
        // F3 半圆摆动，档位 1~7
        ActionDefinition("F3", 1, 3.0, 1.50, true, -0.6, -0.2, -0.4, 2),
        ActionDefinition("F3", 2, 1.5, 0.75, true, -0.3, -0.1, -0.2, 2),
        ActionDefinition("F3", 3, 1.0, 0.50, true,  0.0,  0.0,  0.0, 2),
        ActionDefinition("F3", 4, 0.8, 0.40, true,  0.2,  0.1,  0.2, 2),
        ActionDefinition("F3", 5, 0.6, 0.30, true,  0.4,  0.2,  0.4, 2),
        ActionDefinition("F3", 6, 0.5, 0.25, true,  0.6,  0.3,  0.6, 2),
        ActionDefinition("F3", 7, 0.4, 0.20, true,  0.8,  0.4,  0.8, 2),
        // F4 快速点头（不可调速）
        ActionDefinition("F4", 0, 2.0, 0.40, false, 0.5, 0.0, 0.3, 4),
        // F5 W 形摇头，档位 1~7
        ActionDefinition("F5", 1, 4.0, 2.00, true, -0.8, -0.3, -0.5, 2),
        ActionDefinition("F5", 2, 2.0, 1.00, true, -0.5, -0.2, -0.3, 2),
        ActionDefinition("F5", 3, 1.3, 0.65, true, -0.2, -0.1, -0.1, 2),
        ActionDefinition("F5", 4, 1.0, 0.50, true,  0.0,  0.0,  0.1, 2),
        ActionDefinition("F5", 5, 0.8, 0.40, true,  0.2,  0.1,  0.3, 2),
        ActionDefinition("F5", 6, 0.6, 0.30, true,  0.4,  0.2,  0.5, 2),
        ActionDefinition("F5", 7, 0.5, 0.25, true,  0.6,  0.3,  0.7, 2),
        // F6 中速左右快速点头（不可调速）
        ActionDefinition("F6", 0, 1.8, 0.50, false, 0.2, 0.1, 0.4, 4),
        // F7 快速左右极慢点头（不可调速，适合安静段）
        ActionDefinition("F7", 0, 24.5, 6.00, false, -0.6, -0.5, -0.6, 8)
    )

    /** 动作码 → 显示名称（供 UI 使用）。 */
    val displayName: Map<String, String> = mapOf(
        "F3" to "半圆摆动",
        "F4" to "快速点头",
        "F5" to "W形摇头",
        "F6" to "中速左右快速点头",
        "F7" to "快速左右极慢点头",
        "F8" to "随机"
    )

    /** 可调速动作集合（用于 UI 启用/禁用速度滑块）。 */
    val speedableCodes: Set<String> = setOf("F3", "F5")

    /**
     * 根据动作码与档位查找定义。
     * @return 命中的定义；若不存在返回 null。
     */
    fun find(code: String, speed: Int): ActionDefinition? =
        all.firstOrNull { it.actionCode == code && it.speedLevel == speed }
}

/**
 * 管道级可调参数集中配置。
 *
 * 所有数值都有明确含义与合理区间，单元测试 PipelineConfigTest 会校验：
 * - 权重和非负且和为 1.0；
 * - 阈值在 [0, 1] 区间；
 * - 平滑系数在 (0, 1)；
 * - 窗口大小为正整数。
 *
 * @property beatScoreWeight      节奏匹配度权重。
 * @property energyScoreWeight    能量匹配度权重。
 * @property brightnessScoreWeight 亮度匹配度权重。
 * @property complexityScoreWeight 复杂度匹配度权重。
 * @property switchingPenaltyWeight 切换惩罚权重（作用于 penalty 项，本身为正）。
 * @property switchingPenaltySameSpeedable 当前动作可调速时的切换惩罚（较小）。
 * @property switchingPenaltyOther 当前动作不可调速时的切换惩罚（较大）。
 * @property switchThreshold      切换所需的最小得分差。
 * @property bpmSmoothingFactor   BPM 指数平滑系数，越大越平滑（越接近 1 越平滑）。
 * @property bpmHalfSpeedRange    BPM 半速判定区间，落在此区间则 ×2 修正。
 * @property bpmValidRange        BPM 有效区间，超出则丢弃。
 * @property peWindowSizeFrames   感知能量归一化窗口（帧数）。
 * @property predictionWindowBeats 节拍预测窗口（最近 N 个节拍间隔）。
 * @property preSendOffsetMs      预测提前发送偏移（毫秒）。
 * @property eps                  浮点比较容差。
 */
data class PipelineConfig(
    val beatScoreWeight: Double = 0.45,
    val energyScoreWeight: Double = 0.25,
    val brightnessScoreWeight: Double = 0.10,
    val complexityScoreWeight: Double = 0.10,
    val switchingPenaltyWeight: Double = 0.10,
    val switchingPenaltySameSpeedable: Double = 0.3,
    val switchingPenaltyOther: Double = 0.6,
    val switchThreshold: Double = 0.08,
    val bpmSmoothingFactor: Double = 0.8,
    val bpmHalfSpeedRange: ClosedFloatingPointRange<Double> = 30.0..80.0,
    val bpmValidRange: ClosedFloatingPointRange<Double> = 20.0..250.0,
    val peWindowSizeFrames: Int = 80,
    val predictionWindowBeats: Int = 8,
    val preSendOffsetMs: Long = 50L,
    val eps: Double = 1e-6
) {
    init {
        require(beatScoreWeight >= 0.0) { "beatScoreWeight 不能为负" }
        require(energyScoreWeight >= 0.0) { "energyScoreWeight 不能为负" }
        require(brightnessScoreWeight >= 0.0) { "brightnessScoreWeight 不能为负" }
        require(complexityScoreWeight >= 0.0) { "complexityScoreWeight 不能为负" }
        require(switchingPenaltyWeight >= 0.0) { "switchingPenaltyWeight 不能为负" }
        val sum = beatScoreWeight + energyScoreWeight + brightnessScoreWeight +
            complexityScoreWeight + switchingPenaltyWeight
        require(kotlin.math.abs(sum - 1.0) < 1e-3) {
            "评分权重之和必须为 1.0，当前为 $sum"
        }
        require(switchThreshold in 0.0..1.0) { "switchThreshold 必须在 [0,1]" }
        require(bpmSmoothingFactor in 0.0..1.0) { "bpmSmoothingFactor 必须在 [0,1]" }
        require(peWindowSizeFrames > 0) { "peWindowSizeFrames 必须为正" }
        require(predictionWindowBeats > 0) { "predictionWindowBeats 必须为正" }
        require(preSendOffsetMs >= 0) { "preSendOffsetMs 不能为负" }
        require(eps > 0.0) { "eps 必须为正" }
    }
}

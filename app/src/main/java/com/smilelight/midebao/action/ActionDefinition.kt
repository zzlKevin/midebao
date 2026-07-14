package com.smilelight.midebao.action

/**
 * 动作定义：描述机器人可执行的 17 种舞蹈动作的物理参数。
 *
 * 【设计依据】
 * 每个动作由 4 个物理参数定义，所有匹配计算都基于这些参数推导，
 * 不引入任何"拍脑袋"的目标值。动作的"理想能量"由 [nodIntervalSec] 解析推导
 * （见 [idealEnergy]），而非硬编码。
 *
 * @property code 动作指令码，如 "F3"、"F4"，对应蓝牙协议字节
 * @property speedLevel 速度档位 1-7；不可调速动作为 0
 * @property periodSec 左右摇摆一个完整周期的时长（秒）
 * @property nodIntervalSec 点头间隔（秒），即动作的"内在节拍"周期
 * @property isSpeedable 是否可调速（F3、F5 可调，其余固定）
 */
data class ActionDefinition(
    val code: String,
    val speedLevel: Int,
    val periodSec: Double,
    val nodIntervalSec: Double,
    val isSpeedable: Boolean
) {
    /**
     * 动作的"内在强度"= 点头频率（次/秒）= 1 / nodIntervalSec。
     * 点头越快，动作越激烈。这是物理上可测量的量，非主观设定。
     */
    val intensity: Double
        get() = 1.0 / nodIntervalSec

    /**
     * 动作的理想感知能量（PE 目标值）。
     *
     * 【推导依据】
     * 将所有 17 个动作的 intensity 线性映射到 [-1, +1]：
     *   idealEnergy = 2 × (intensity - minIntensity) / (maxIntensity - minIntensity) - 1
     *
     * 这保证：最慢的动作（F7，intensity=1/6≈0.167）→ PE=-1（最安静），
     * 最快的动作（F3 档7，intensity=5.0）→ PE=+1（最激烈），
     * 中间动作线性插值。无需人工指定每个动作的目标 PE。
     *
     * minIntensity/maxIntensity 由 [ALL_ACTIONS] 全集计算，见 [ActionCatalog]。
     */
    fun idealEnergy(minIntensity: Double, maxIntensity: Double): Double {
        if (maxIntensity <= minIntensity) return 0.0
        return 2.0 * (intensity - minIntensity) / (maxIntensity - minIntensity) - 1.0
    }
}

/**
 * 17 个动作的目录（单例）。
 *
 * 提供动作全集、强度极值（用于 [ActionDefinition.idealEnergy] 推导）、
 * 以及按指令码查找的工具方法。
 */
object ActionCatalog {

    /** 全部 17 个候选动作（F3×7 + F4 + F5×7 + F6 + F7）。 */
    val ALL: List<ActionDefinition> = listOf(
        // F3：半圆摆动，可调速，档位 1-7
        ActionDefinition("F3", 1, 3.0, 1.50, true),
        ActionDefinition("F3", 2, 1.5, 0.75, true),
        ActionDefinition("F3", 3, 1.0, 0.50, true),
        ActionDefinition("F3", 4, 0.8, 0.40, true),
        ActionDefinition("F3", 5, 0.6, 0.30, true),
        ActionDefinition("F3", 6, 0.5, 0.25, true),
        ActionDefinition("F3", 7, 0.4, 0.20, true),
        // F4：快速点头，固定
        ActionDefinition("F4", 0, 2.0, 0.40, false),
        // F5：W 形摇头，可调速，档位 1-7
        ActionDefinition("F5", 1, 4.0, 2.00, true),
        ActionDefinition("F5", 2, 2.0, 1.00, true),
        ActionDefinition("F5", 3, 1.3, 0.65, true),
        ActionDefinition("F5", 4, 1.0, 0.50, true),
        ActionDefinition("F5", 5, 0.8, 0.40, true),
        ActionDefinition("F5", 6, 0.6, 0.30, true),
        ActionDefinition("F5", 7, 0.5, 0.25, true),
        // F6：中速左右快速点头，固定
        ActionDefinition("F6", 0, 1.8, 0.50, false),
        // F7：快速左右极慢点头，固定
        ActionDefinition("F7", 0, 24.5, 6.00, false)
    )

    /** 全集动作强度的最小值（F7 的 1/6.0）。 */
    val MIN_INTENSITY: Double = ALL.minOf { it.intensity }

    /** 全集动作强度的最大值（F3 档7 的 1/0.20 = 5.0）。 */
    val MAX_INTENSITY: Double = ALL.maxOf { it.intensity }

    /**
     * 动作码 → 蓝牙指令（十六进制字符串）映射。
     * 协议格式：FE 55 10 Fx 55 FE。
     */
    val CODE_TO_COMMAND: Map<String, String> = mapOf(
        "F3" to "FE 55 10 F3 55 FE",
        "F4" to "FE 55 10 F4 55 FE",
        "F5" to "FE 55 10 F5 55 FE",
        "F6" to "FE 55 10 F6 55 FE",
        "F7" to "FE 55 10 F7 55 FE",
        "F8" to "FE 55 10 F8 55 FE"  // F8：随机池（非 17 动作之一，用于启动）
    )

    /** 动作码 → 中文显示名。 */
    val CODE_TO_DISPLAY_NAME: Map<String, String> = mapOf(
        "F3" to "半圆摆动",
        "F4" to "快速点头",
        "F5" to "W形摇头",
        "F6" to "中速左右快速点头",
        "F7" to "快速左右极慢点头",
        "F8" to "随机"
    )

    /** 可调速动作码集合。 */
    val SPEEDABLE_CODES: Set<String> = setOf("F3", "F5")

    /**
     * 根据 (动作码, 档位) 查找动作定义。
     * @return 匹配的 [ActionDefinition]；未找到返回 null
     */
    fun find(code: String, speedLevel: Int): ActionDefinition? =
        ALL.firstOrNull { it.code == code && it.speedLevel == speedLevel }

    /**
     * 获取某动作码的所有档位定义（可调速动作返回 7 个，不可调速返回 1 个）。
     */
    fun levelsOf(code: String): List<ActionDefinition> =
        ALL.filter { it.code == code }
}

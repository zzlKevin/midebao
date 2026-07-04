package com.smilelight.midebao

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.animation.doOnEnd
import kotlin.math.*

class MotionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        // 设置透明背景
        setBackgroundColor(Color.TRANSPARENT)
        // 确保 onDraw 被调用
        setWillNotDraw(false)
    }
    private var isReverse = false  // 当前是否处于回程
    private var actionCode: String = "F3"
    private var speedLevel: Int = 2  // 1~7
    private var phase: Float = 0f    // 0~1
    private var isPaused: Boolean = false

    private var animator: android.animation.ValueAnimator? = null
    private var currentPeriodMs: Long = 3000L  // 默认3秒

    private var ratio: Double = 1.5

    // 绘制参数
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val dotPaintWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 确保重绘时重新计算轨迹参数
    }



    fun setPaused(paused: Boolean) {
        isPaused = paused
        if (paused) {
            animator?.pause()
        } else {
            animator?.resume()
            if (animator == null) {
                startAnimation()
            }
        }
    }

    private fun updatePeriod() {
        // 获取基础周期（秒）
        val basePeriod = when (actionCode) {
            "F3" -> 3.0
            "F4" -> 4.0   // 动作2固定周期4秒
            "F5" -> 3.0
            "F6" -> 5.2   // 动作4固定周期5.2秒
            "F7" -> 24.5  // 动作5固定周期24.5秒
            else -> 3.0
        }

        val periodSec = when (actionCode) {
            // 只有动作1(F3)和动作3(F5)受档位影响
            "F3", "F5" -> basePeriod / 2.0.pow(speedLevel - 1)
            // 其他动作固定速度，不受档位影响
            else -> basePeriod
        }

        currentPeriodMs = (periodSec * 1000 * ratio).toLong()
    }

    private fun startAnimation() {
        animator?.cancel()
        val totalDuration = if (actionCode == "F6" || actionCode == "F7") {
            // 动作4/5：去程时长 = 完整周期 - 0.5秒，回程固定0.5秒
            val fullPeriod = currentPeriodMs
            val outboundDuration = (fullPeriod - 500).coerceAtLeast(100)
            outboundDuration + 500
        } else {
            // 其他动作：去程+回程总共 currentPeriodMs * 2
            currentPeriodMs * 2
        }

        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalDuration
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
            addUpdateListener {
                val progress = it.animatedValue as Float
                val elapsed = progress * totalDuration
                if (actionCode == "F6" || actionCode == "F7") {
                    val outboundDuration = totalDuration - 500
                    if (elapsed < outboundDuration) {
                        // 去程：phase 0→1，isReverse = false
                        phase = (elapsed / outboundDuration).toFloat()
                        isReverse = false
                    } else {
                        // 回程：phase 1→0，isReverse = true
                        val reverseElapsed = elapsed - outboundDuration
                        phase = 1 - (reverseElapsed / 500).toFloat()
                        isReverse = true
                    }
                } else {
                    // 其他动作：均匀分配
                    val cycleTime = progress * 2f
                    if (cycleTime < 1f) {
                        phase = cycleTime
                        isReverse = false
                    } else {
                        phase = 2f - cycleTime
                        isReverse = true
                    }
                }
                invalidate()
            }
            start()
        }
        animator = anim
    }

    private fun restartAnimation() {
        animator?.cancel()
        startAnimation()
    }

    override fun onDraw(canvas: Canvas) {
//        super.onDraw(canvas)


        // 清空背景（透明）
//        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val w = width
        val h = height
        if (w == 0 || h == 0) return

        // 计算绘制区域
        val pad = min(w, h) * 0.1f
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w - 2 * pad, h - 2 * pad) / 2f

        // 根据动作类型计算当前点位置
        val point = when (actionCode) {
            "F3" -> pointOnSemicircle(phase, cx, cy, radius)  // phase 0~1
            "F4" -> pointOnSin5(phase, pad, w - pad, cy, radius * 0.38f)
            "F5" -> pointOnW3(phase, pad, w - pad, cy, radius * 0.36f)
            "F6" -> pointOnV4(phase, pad, w - pad, cy, radius * 0.36f, 4, isReverse)
            "F7" -> pointOnV4(phase, pad, w - pad, cy, radius * 0.36f, 2, isReverse)
            "F8" -> pointOnSemicircle(phase, cx, cy, radius)  // phase 0~1
            else -> PointF(cx, cy)
        }
        drawDot(canvas, point, radius * 0.09f)
    }

    private fun drawDot(canvas: Canvas, pt: PointF, barH: Float) {
        val rBg = barH * 3 * 1.5f / 2
        // 黑色圆底
        canvas.drawCircle(pt.x, pt.y, rBg, dotPaint)
        // 白色双胶囊
        val barW = max(1.5f, barH * 0.2f)
        val gap = barH * 0.24f * 3.5f
        val half = (barW + gap) / 2
        drawVerticalPill(canvas, pt.x - half, pt.y, barW, barH)
        drawVerticalPill(canvas, pt.x + half, pt.y, barW, barH)
    }

    private fun drawVerticalPill(canvas: Canvas, cx: Float, cy: Float, barW: Float, barH: Float) {
        val x = cx - barW / 2
        val y = cy - barH / 2
        val r = barW / 2
        val path = Path().apply {
            moveTo(x, y + r)
            arcTo(cx - r, y, cx + r, y + 2 * r, 180f, 180f, false)
            lineTo(x + barW, y + barH - r)
            arcTo(cx - r, y + barH - 2 * r, cx + r, y + barH, 0f, 180f, false)
            close()
        }
        canvas.drawPath(path, dotPaintWhite)
    }

    // ---------- 轨迹计算函数 ----------
    private fun pointOnSemicircle(phase: Float, cx: Float, cy: Float, r: Float): PointF {
        val theta = PI.toFloat() - phase * PI.toFloat()
        return PointF(cx + r * cos(theta), cy + r * sin(theta))
    }

    private fun pointOnSin5(phase: Float, x0: Float, x1: Float, cy: Float, A: Float): PointF {
        val span = x1 - x0
        val px = x0 + phase * span
        val py = cy + A * cos(2 * PI.toFloat() * 5 * phase)
        return PointF(px, py)
    }

    private fun pointOnW3(phase: Float, x0: Float, x1: Float, cy: Float, A: Float): PointF {
        val span = x1 - x0
        val px = x0 + phase * span
        val py = cy - A * cos(4 * PI.toFloat() * phase)
        return PointF(px, py)
    }

    private fun pointOnV4(
        phase: Float,
        x0: Float,
        x1: Float,
        cy: Float,
        A: Float,
        nodN: Int,
        isReverse: Boolean
    ): PointF {
        val span = x1 - x0
        val LEFT = 1800f / 4400f
        val ARC = 800f / 4400f
        val RIGHT = 1800f / 4400f

        if (isReverse) {
            // 回程：从右侧沿圆弧平滑回到左侧（不点头）
            // phase 从 1→0，我们用 s = 1 - phase 从 0→1
            val s = 1 - phase
            val px = x1 - s * span  // 从右到左
            val py = cy - A * sin(PI.toFloat() * s)  // 沿圆弧
            return PointF(px, py)
        }

        // 去程：左侧点头 → 圆弧 → 右侧点头
        if (phase < LEFT) {
            val sub = phase / LEFT
            val yOffset = A * sin(2 * PI.toFloat() * nodN * sub)
            return PointF(x0, cy + yOffset)
        } else if (phase < LEFT + ARC) {
            val s = (phase - LEFT) / ARC
            val px = x0 + s * span
            val py = cy - A * sin(PI.toFloat() * s)
            return PointF(px, py)
        } else {
            val sub = (phase - LEFT - ARC) / RIGHT
            val yOffset = A * sin(2 * PI.toFloat() * nodN * sub)
            return PointF(x1, cy + yOffset)
        }
    }

    // 为了使动画可重用，对外提供方法
    fun setAction(action: String, speed: Int, paused: Boolean = false) {
        this.actionCode = action
        this.speedLevel = speed.coerceIn(1, 7)
        this.isPaused = paused
        this.isReverse = false
        updatePeriod()           // 先更新周期
        if (!isPaused) {
            restartAnimation()   // 再重启动画（使用新周期）
        } else {
            animator?.pause()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isPaused) startAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    private fun pointOnV4Forward(phase: Float, x0: Float, x1: Float, cy: Float, A: Float, nodN: Int): PointF {
        val span = x1 - x0
        val LEFT = 1800f / 4400f
        val ARC = 800f / 4400f
        val RIGHT = 1800f / 4400f

        if (phase < LEFT) {
            val sub = phase / LEFT
            val yOffset = A * sin(2 * PI.toFloat() * nodN * sub)
            return PointF(x0, cy + yOffset)
        } else if (phase < LEFT + ARC) {
            val s = (phase - LEFT) / ARC
            val px = x0 + s * span
            val py = cy - A * sin(PI.toFloat() * s)
            return PointF(px, py)
        } else {
            val sub = (phase - LEFT - ARC) / RIGHT
            val yOffset = A * sin(2 * PI.toFloat() * nodN * sub)
            return PointF(x1, cy + yOffset)
        }
    }

    private fun pointOnV4Backward(phase: Float, x0: Float, x1: Float, cy: Float, A: Float): PointF {
        // 从右到左沿拱形返回（不点头）
        val span = x1 - x0
        // 拱形路径，从右到左，相位 0→1 对应从右到左
        val px = x1 - phase * span
        val py = cy - A * sin(PI.toFloat() * (1 - phase))
        return PointF(px, py)
    }
}
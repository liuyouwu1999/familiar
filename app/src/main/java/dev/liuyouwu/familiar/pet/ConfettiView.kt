package dev.liuyouwu.familiar.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View

/** 双击撒花：从胸口炸开的小方块彩纸（900ms 一轮，画在鱼身窗口内）。
 *  （审计 A4 拆件：原 PetOverlayService inner class 外移——dp 换构造密度；
 *  tick 由自持 Handler 管理 + stop() 显式收口，替代原 Service 的 confettiTick 字段转交） */
class ConfettiView(context: Context, private val density: Float) : View(context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null
    private var progress = 1f
    private val colors = intArrayOf(
        0xFFF5A623.toInt(), 0xFF4C8DF6.toInt(), 0xFF7ED321.toInt(), 0xFFFF6F91.toInt(), 0xFF9B59B6.toInt()
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun dp(v: Float): Int = (v * density).toInt()

    fun burst() {
        stop()   // 重入安全：上一轮没播完也先收干净再开新一轮
        progress = 0f
        visibility = View.VISIBLE
        val start = SystemClock.uptimeMillis()
        val t = object : Runnable {
            override fun run() {
                progress = ((SystemClock.uptimeMillis() - start) / 900f).coerceAtMost(1f)
                invalidate()
                if (progress < 1f) {
                    mainHandler.postDelayed(this, 16L)
                } else {
                    visibility = View.GONE
                }
            }
        }
        tick = t
        mainHandler.post(t)
    }

    /** 摘窗/服务销毁前停帧（原 detach 经 confettiTick 手动 removeCallbacks 的等价路径） */
    fun stop() {
        tick?.let { mainHandler.removeCallbacks(it) }
        tick = null
        progress = 1f
    }

    override fun onDraw(canvas: Canvas) {
        if (progress >= 1f) return
        val cx = width / 2f
        val cy = height * 0.75f
        val n = 10
        for (i in 0 until n) {
            val angle = Math.PI * 2 * i / n + i * 0.7
            val dist = progress * minOf(width, height) * 0.8f
            val x = cx + (Math.cos(angle) * dist).toFloat()
            val y = cy + (Math.sin(angle) * dist).toFloat() - progress * dp(30f)
            val s = (1f - progress) * dp(6f)
            paint.color = colors[i % colors.size]
            paint.alpha = ((1f - progress) * 255).toInt()
            canvas.drawRect(x - s / 2, y - s / 2, x + s / 2, y + s / 2, paint)
        }
    }
}

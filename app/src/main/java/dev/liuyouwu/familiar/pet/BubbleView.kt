package dev.liuyouwu.familiar.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** 漫画式对话泡：圆角白底+深描边+底边尾巴指向鱼头；文字由 TextView 本体画。
 *  （审计 A4 拆件：原 PetOverlayService inner class 原样外移——dpf/dp 换构造密度，
 *  内部类直取的 fishPx 换 minWidthPx） */
class BubbleView(context: Context, density: Float, private val minWidthPx: Int) : TextView(context) {
    private val tailH = 8f * density
    private val tailW = 12f * density
    private val radius = 10f * density
    private val border = 1.5f * density
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A4A63.toInt(); style = Paint.Style.FILL }
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

    /** 尾巴指向目标（鱼心）在泡本体内的 x 坐标：泡被钳回屏内后，尾巴滑向鱼头方向 */
    var tailTargetPx = 70f * density

    init {
        gravity = Gravity.CENTER
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
        textSize = 12f
        setTextColor(0xFF2A3550.toInt())
        // 底部多留尾巴高度：最后一行不压进尾巴区
        setPadding((10f * density).toInt(), (7f * density).toInt(), (10f * density).toInt(), (15f * density).toInt())
    }

    /** 完整短句适配（spec §4，镜像模式同策略）：按给定上限宽实测内容宽；末行出现
     *  省略号即超 4 行，字号 12sp→10sp 降一档重测，仍溢出由 ellipsize END 兜底。
     *  返回定档窗宽（含左右内边距），钳在 [鱼身宽, 上限]——任何输入不糊满屏幕 */
    fun fitContent(maxW: Int): Int {
        textSize = 12f  // 终审 I-1：先复位再降档，防字号粘滞（View 复用，长泡降档后短泡不回 12f）
        val cap = maxW.coerceAtLeast(minWidthPx)
        measure(
            View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val l = layout
        if (l != null && l.lineCount >= 4 && l.getEllipsisCount(l.lineCount - 1) > 0) {
            textSize = 10f
            measure(
                View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }
        return measuredWidth.coerceIn(minWidthPx, cap)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val body = RectF(0f, 0f, w, h - tailH)
        // 尾巴滑向泡本体内"鱼心"的位置（泡整体钳回屏内后仍指向鱼头），钳在泡身内
        val tailX = tailTargetPx.coerceIn(tailW, w - tailW)
        // 描边：先画加粗深色底（泡身+尾巴整体轮廓），再叠白色内层
        val outline = android.graphics.Path().apply {
            addRoundRect(body, radius, radius, android.graphics.Path.Direction.CW)
            moveTo(tailX - tailW / 2, h - tailH - 1f)
            lineTo(tailX, h)
            lineTo(tailX + tailW / 2, h - tailH - 1f)
            close()
        }
        canvas.drawPath(outline, darkPaint)
        val inner = android.graphics.Path().apply {
            addRoundRect(
                RectF(border, border, w - border, h - tailH - border),
                radius, radius, android.graphics.Path.Direction.CW
            )
            moveTo(tailX - tailW / 2 + border, h - tailH - 1f)
            lineTo(tailX, h - border)
            lineTo(tailX + tailW / 2 - border, h - tailH - 1f)
            close()
        }
        canvas.drawPath(inner, lightPaint)
        super.onDraw(canvas)
    }
}

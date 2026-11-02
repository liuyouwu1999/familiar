package dev.liuyouwu.familiar.pet

/** 悬浮窗贴边钳制（纯函数 JVM 可测）。贴边语义（用户裁定）：**可视角色贴住屏缘、
 *  不出屏**——窗口允许出屏的量恰好等于帧图透明边距（alpha bbox 实测：左 22.7% /
 *  右 23.0% / 上 21.1% / 下 4.7%），多一分身体出界，少一分贴不上。
 *  纵向坐标系：NO_LIMITS 后鱼窗请求坐标的挂载原点在状态栏下（originTop=状态栏
 *  inset，挂载后 locationOnScreen 实测校准），物理底=screenH。 */
object PetWindowBounds {

    /** 横向：左缘角色贴左屏（窗口出屏 22.7% 透明边距）/ 右缘同理 23.0%（x 无原点偏移） */
    fun clampX(x: Int, screenW: Int, fishPx: Int): Int =
        x.coerceIn(-fishPx * 227 / 1000, screenW - fishPx + fishPx * 230 / 1000)

    /** 纵向：顶缘角色贴物理屏顶（窗口上探 21.1% 边距+原点偏移）/ 底缘脚贴物理屏底
     *  （窗口下探 4.7% 边距）——都不越过屏缘 */
    fun clampY(y: Int, originTop: Int, screenH: Int, fishPx: Int): Int {
        val min = -originTop - fishPx * 211 / 1000
        val max = screenH - originTop - fishPx + fishPx * 47 / 1000
        return y.coerceIn(min, max.coerceAtLeast(min))
    }
}

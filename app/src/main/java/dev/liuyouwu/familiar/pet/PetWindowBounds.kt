package dev.liuyouwu.familiar.pet

/** 悬浮窗贴边钳制（纯函数 JVM 可测）。帧图自带透明边距（256 画布实测：
 *  左 22.7% / 右 23.0% / 上 21.1% / 下 4.7%），各向允许的出屏量必须压过对应边距，
 *  角色视觉上才贴得到屏缘——「拖不到屏幕边缘」即底部 0% 出屏所致（2026-11-01） */
object PetWindowBounds {

    /** 横向：左出屏 45% / 右出屏 55%（应用内版同手感，横向早有补偿） */
    fun clampX(x: Int, screenW: Int, fishPx: Int): Int =
        x.coerceIn(-fishPx * 45 / 100, screenW - fishPx * 55 / 100)

    /** 纵向：顶出屏 25%；底出屏 10%——窗口底可越物理底边一成身位，脚踩到边
     *  （旧口径 y ≤ screenH - fishPx 零出屏，下边距 4.7% 让脚永远悬空） */
    fun clampY(y: Int, screenH: Int, fishPx: Int): Int =
        y.coerceIn(-fishPx / 4, (screenH - fishPx * 90 / 100).coerceAtLeast(-fishPx / 4))
}

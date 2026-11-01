package dev.liuyouwu.familiar.pet

/** 悬浮窗贴边钳制（纯函数 JVM 可测）。帧图自带透明边距（256 画布实测：
 *  左 22.7% / 右 23.0% / 上 21.1% / 下 4.7%），各向允许的出屏量必须压过对应边距，
 *  角色视觉上才贴得到屏缘。纵向坐标系注意：NO_LIMITS 后鱼窗请求坐标的挂载原点
 *  在状态栏下（originTop=状态栏 inset，挂载后 rootWindowInsets 实测校准），
 *  物理底=screenH——底部直接以物理屏为参照（可探入导航栏区，脚踩物理底边）。 */
object PetWindowBounds {

    /** 横向：左出屏 45% / 右出屏 55%（x 无原点偏移，screenW=物理屏宽） */
    fun clampX(x: Int, screenW: Int, fishPx: Int): Int =
        x.coerceIn(-fishPx * 45 / 100, screenW - fishPx * 55 / 100)

    /** 纵向：顶 = -originTop 再出屏 25%（可探入状态栏）；底 = 物理屏 - originTop，
     *  窗口底越物理屏一成身位（脚踩物理底边。旧口径零出屏+原点偏移，双重贴不到） */
    fun clampY(y: Int, originTop: Int, screenH: Int, fishPx: Int): Int {
        val min = -originTop - fishPx / 4
        return y.coerceIn(min, (screenH - originTop - fishPx * 90 / 100).coerceAtLeast(min))
    }
}

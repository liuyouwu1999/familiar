package dev.liuyouwu.familiar.pet

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** 菜单项动作集合（审计 A4 拆件：菜单视觉构建归 PetMenuBuilder，业务动作归 Service） */
class PetMenuActions(
    val openChat: () -> Unit,
    val openReview: () -> Unit,
    val stroke: () -> Unit,
    val joke: () -> Unit,
    val feed: () -> Unit,
    val performRandom: () -> Unit,
    val hideAway: () -> Unit,
)

/** 长按菜单构建（审计 A4 拆件：原 PetOverlayService.buildMenu 原样外移）。
 *  每项点击统一先收菜单（closeMenu 回调=窗口管理侧）再执行动作；
 *  「复习一下」由 hasUnarchivedEntries 回调 gate（build 主线程不可查库，
 *  读 Service 的 @Volatile 缓存） */
class PetMenuBuilder(
    private val context: Context,
    private val density: Float,
    private val hasUnarchivedEntries: () -> Boolean,
    private val closeMenu: () -> Unit,
    private val actions: PetMenuActions,
) {
    private fun dp(v: Float): Int = (v * density).toInt()
    private fun dpf(v: Float): Float = v * density

    fun build(): LinearLayout {
        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFF8E7.toInt())
                setStroke(dp(2f), 0xFF8B5A2B.toInt())
                cornerRadius = dpf(8f)
            }
        }
        fun row(label: String, action: () -> Unit) {
            if (menu.childCount > 0) {
                menu.addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)).apply {
                    setMargins(dp(8f), 0, dp(8f), 0)
                }.also { (it as? LinearLayout.LayoutParams)!!.gravity = Gravity.CENTER_VERTICAL })
                (menu.getChildAt(menu.childCount - 1) as View).background =
                    android.graphics.drawable.ColorDrawable(0x338B5A2B.toInt())
            }
            menu.addView(
                TextView(context).apply {
                    text = label
                    setTextColor(0xFF5A4632.toInt())
                    textSize = 14f
                    setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                    setOnClickListener { closeMenu(); action() }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        }
        row("转到对话") { actions.openChat() }
        // 复习一下（学习画像 spec §6）：有未归档条目才显示（@Volatile 缓存 gate，
        // buildMenu 主线程不可查库）；点击跳学习画像——AppNavigation 路由 Task 10 注册，
        // 未挂时 no-op 不崩
        if (hasUnarchivedEntries()) {
            row("复习一下") { actions.openReview() }
        }
        row("摸摸头") { actions.stroke() }
        row("讲个冷笑话") { actions.joke() }
        row("喂一口") { actions.feed() }
        row("来段表演") { actions.performRandom() }
        row("躲起来") { actions.hideAway() }
        return menu
    }
}

package dev.liuyouwu.familiar.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * 「垦牧小筑」像素风色板 —— 致敬星露谷物语的木质 UI，色值为本项目自定。
 * 语义：羊皮纸做底、木框做骨架、胡萝卜橙做主行动色、天蓝做用户气泡、星尘金做点缀。
 */

// —— 木框体系（面板四件套）——
val WoodBorder = Color(0xFF42250F)   // 最外圈描边
val Wood = Color(0xFFB07840)         // 框体主色
val WoodLight = Color(0xFFE0A568)    // 上/左受光面
val WoodDark = Color(0xFF6B3F1D)     // 下/右背光面

// —— 纸面体系 ——
val ParchmentBg = Color(0xFFF6E7C8)  // 页面底（羊皮纸）
val PaperLight = Color(0xFFFDF3DC)   // 面板内芯（亮纸）
val PaperShade = Color(0xFFEBD9AE)   // 内嵌/次级面（暗纸）

// —— 墨水（文字）——
val Ink = Color(0xFF42250F)
val InkSoft = Color(0xFF8A6A45)

// —— 行动色 ——
val CarrotOrange = Color(0xFFE8871E)      // 主按钮
val CarrotOrangeLight = Color(0xFFFFB35C) // 按钮受光
val CarrotOrangeDark = Color(0xFFA85B0C)  // 按钮背光
val OnCarrot = Color(0xFFFFF6E4)

// —— 功能色 ——
val SkyBlue = Color(0xFF4E8FD8)       // 用户气泡框体
val SkyBlueLight = Color(0xFF8FBCEC)  // 用户气泡受光
val SkyBlueDark = Color(0xFF2A568F)   // 用户气泡背光
val StarGold = Color(0xFFE8B31E)      // 置顶/星星
val GrassGreen = Color(0xFF5B9E48)    // 成功/在线
val BerryRed = Color(0xFFC4453C)      // 删除/错误
val WarnAmber = Color(0xFF9A6700)     // 琥珀警示（浅色纸面用深琥珀保证可读；夜间主题直接用 StarGold）

// —— 夜间（夜垦）体系 ——
val NightBg = Color(0xFF211910)
val NightPaper = Color(0xFF3B2C1A)
val NightPaperShade = Color(0xFF2E2212)
val NightWood = Color(0xFF7A5228)
val NightWoodLight = Color(0xFF9A6C38)
val NightWoodDark = Color(0xFF523414)
val NightBorder = Color(0xFF150C03)
val NightInk = Color(0xFFF2E1BC)
val NightInkSoft = Color(0xFFBEA072)

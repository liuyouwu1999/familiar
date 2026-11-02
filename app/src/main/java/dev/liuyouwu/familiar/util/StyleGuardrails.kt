package dev.liuyouwu.familiar.util

/**
 * 文风红线（去 AI 味）：会话类功能公共风格约束段，组装 base system 时统一追加在尾部。
 *
 * 背景：各功能回复 AI 味浓（套话开场、排比三连、总结升华收尾、客服腔）。红线段
 * 一次成文、逐类型追加，不散抄进各 skill JSON/用户模板——避免重蹈群规、familiar
 * 模板双份字面量的漂移。
 *
 * 注入范围（SkillHost 装配处裁定）：
 * - familiar / chat / classroom / eli5 追加；
 * - chatroom 自带「去 AI 味红线」段（chatroom.json 群规 15-18 条）、圆桌三角色自带
 *   逐角色禁式——已有等价条款，重复注入互相稀释，均不追加；
 * - 标题生成 / 对话压缩摘要 / 复习教练为机器结构化输出，不经本段（不追加）。
 *
 * 字节稳定性：本段为纯静态常量，追加后各类型 base system 仍逐请求字节一致，
 * 不破坏 KV 前缀缓存锚点（PersonaPromptBuilder.build() 的稳定拼装语义保持——
 * 追加发生在 SkillHost 装配处，persona 层不内嵌本段）。
 */
object StyleGuardrails {

    /** 追加进会话 base system 尾部的完整红线段（含段头）。 */
    const val RED_LINES: String = """# 文风红线（去 AI 味，违反就重写）
1. 套话高频词不出现：「首先/其次/总之」「值得注意的是」「不仅…更是…」「总而言之」「标志着」「赋能」「闭环」「无缝」「至关重要」
2. 不排比三连、不强行对仗；段落结尾不总结升华、不以金句收尾，说完就停
3. 不铺垫开场：「好的，」「当然！」「好问题！」这类起手式删掉，直接回答
4. 不说客服腔：「希望这对你有帮助」「随时问我」等收尾客套不出现
5. 长短句交错，一句能说清就不凑字数；不把一条信息换三种说法重复
6. 少用破折号吊悬念，不拿粗体、emoji、分隔线当装饰
7. 有例子给例子，有细节说细节；结论留余地，不把话说满
8. 本节只管说话方式；翻译、代码、表格等任务格式不受影响，按任务要求来"""

    /** base system 尾部追加红线段（组装侧唯一入口；空 base 原样返回，装配路径不会传空）。 */
    fun withRedLines(base: String): String =
        if (base.isBlank()) base else "$base\n\n$RED_LINES"
}

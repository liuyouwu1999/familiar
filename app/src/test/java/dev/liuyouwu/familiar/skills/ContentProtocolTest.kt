package dev.liuyouwu.familiar.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ContentProtocol 用例集：对应用例复制自 TutorPromptsTest（原文件按 spec §11
 * 保留至 P4，双份并存），改调 ContentProtocol.parse(raw, speakers)；另扩展
 * 分隔符引用取最后一个、29/30 字废稿边界、残缺 TS 尾、speakers 参数化。
 */
class ContentProtocolTest {

    private val speakers = listOf("老师", "小柯", "阿穗")

    private fun ts(json: String) =
        ContentProtocol.STATE_MARKER_BEGIN + json + ContentProtocol.STATE_MARKER_END

    // ===== ---课堂--- 分隔符截断（lastIndexOf）=====

    @Test
    fun `separator cut drops reasoning before separator`() {
        val raw = "推演：学生看不到这段。\n---课堂---\n" +
            "[老师] 快充在电量过半后速度下降，这是析锂防护在起作用。大电流时锂来不及嵌入负极就会析出金属锂。"
        val out = ContentProtocol.parse(raw, speakers)
        assertTrue(out.finalText.startsWith("[老师]"))
        assertFalse(out.finalText.contains("推演"))
        assertEquals(1, out.turns?.size)
        assertEquals("老师", out.turns!![0].speaker)
        assertFalse(out.isDraft)
        assertNull(out.phase)
    }

    @Test
    fun `quoted separator in self-check list cuts at last occurrence`() {
        // 模型自检清单引用了分隔符字样：切第一个会把清单尾巴留下（推演泄漏），必须取最后一个
        val raw = "自检清单：\n1. 分隔符 ---课堂--- 已输出\n2. 台词已带说话人标记\n---课堂---\n" +
            "[老师] 今天讲快充，大电流充电时锂离子来不及嵌入负极就会析锂，系统会主动降流。"
        val out = ContentProtocol.parse(raw, speakers)
        assertTrue(out.finalText.startsWith("[老师]"))
        assertFalse(out.finalText.contains("自检"))
        assertFalse(out.finalText.contains("已输出"))
        assertEquals(1, out.turns?.size)
    }

    // ===== 开场锚点剥离 =====

    @Test
    fun `greeting anchor strips reasoning before it`() {
        val raw = "推演：先想清楚再讲。\n你好！我是你的Python私教。我们今天从变量开始学起。"
        val out = ContentProtocol.parse(raw, speakers)
        assertTrue(out.finalText.startsWith("你好！我是你的"))
        assertFalse(out.finalText.contains("推演"))
        assertNull(out.turns)
    }

    @Test
    fun `anchor drop uses min of greeting and teacher-script anchors`() {
        // 两个锚点都在时取更早的：[老师] 在前 → 从 [老师] 起；后面的问候字样留在台词里不触发
        val raw = "引导语。\n[老师] 先讲结论。\n补充：这里不该出现开场白，你好！我是你的老师这一句不该保留。"
        val out = ContentProtocol.parse(raw, speakers)
        assertTrue(out.finalText.startsWith("[老师]"))
        assertFalse(out.finalText.contains("引导语"))
        assertEquals(1, out.turns?.size)
    }

    @Test
    fun `text already starting at anchor is kept whole`() {
        val raw = "你好！我是你的Python私教。我们今天从摸底开始。"
        val out = ContentProtocol.parse(raw, speakers)
        assertEquals(raw, out.finalText)
        assertNull(out.turns)
    }

    // ===== TS 状态标记提取（复制自 TutorPromptsTest 的 extractState 用例）=====

    @Test
    fun `state marker extracted and removed from text`() {
        val raw = "讲解内容。" + ts(
            """{"v":1,"phase":"teaching","sections":[{"t":"变量","st":"done"}],"current":1,"quizRight":1,"quizTotal":2,"weak":["循环"],"digest":"学了变量"}"""
        )
        val out = ContentProtocol.parse(raw, speakers)
        assertEquals("teaching", out.phase)
        assertEquals("讲解内容。", out.finalText)
        assertFalse(out.finalText.contains(ContentProtocol.STATE_MARKER_BEGIN))
    }

    @Test
    fun `truncated state marker tail is cut and phase null`() {
        // 流中断产物：BEGIN 之后无 END，残缺尾部整段截除，不留碎片
        val raw = "内容。" + ContentProtocol.STATE_MARKER_BEGIN + """{"v":1,"phase":"teach"""
        val out = ContentProtocol.parse(raw, speakers)
        assertNull(out.phase)
        assertEquals("内容。", out.finalText)
        assertFalse(out.finalText.contains(ContentProtocol.STATE_MARKER_BEGIN))
    }

    @Test
    fun `no marker keeps text and phase null`() {
        val raw = "普通回复，讲得挺明白的。"
        val out = ContentProtocol.parse(raw, speakers)
        assertNull(out.phase)
        assertEquals(raw, out.finalText)
    }

    @Test
    fun `placeholder section values are rejected`() {
        val raw = "内容。" + ts("""{"v":1,"sections":[{"t":"节标题","st":"done"}],"current":1}""")
        val out = ContentProtocol.parse(raw, speakers)
        assertNull(out.phase)
        assertEquals("内容。", out.finalText)
    }

    @Test
    fun `placeholder digest is rejected`() {
        val raw = "内容。" + ts("""{"v":1,"digest":"一两句最近学习摘要"}""")
        val out = ContentProtocol.parse(raw, speakers)
        assertNull(out.phase)
        assertEquals("内容。", out.finalText)
    }

    @Test
    fun `double markers last valid wins`() {
        val raw = "内容。" +
            ts("""{"v":1,"phase":"teaching","sections":[{"t":"第一节","st":"done"}]}""") +
            ts("""{"v":1,"phase":"await_user","sections":[{"t":"第二节","st":"done"}],"current":2}""")
        val out = ContentProtocol.parse(raw, speakers)
        assertEquals("await_user", out.phase)
        assertEquals("内容。", out.finalText)
    }

    @Test
    fun `arrow inside value discards state and leaves no marker begin`() {
        // 值里出现 --> 提前截断：状态丢弃（提示词已禁止该序列）；起始序列必须被移除
        val raw = "内容。" + ts("""{"v":1,"digest":"a-->b"}""") + "残留碎片"
        val out = ContentProtocol.parse(raw, speakers)
        assertNull(out.phase)
        assertFalse(out.finalText.contains(ContentProtocol.STATE_MARKER_BEGIN))
    }

    // ===== CoT 草稿步骤头剥除（复制自 TutorPromptsTest 的 stripCoTStepHead 用例）=====

    @Test
    fun `cot step head strips ascii fullwidth and zero-width variants`() {
        assertEquals("哼，鸡胸肉。", ContentProtocol.parse("7. Generate Output.哼，鸡胸肉。", speakers).finalText)
        assertEquals("哼，鸡胸肉。", ContentProtocol.parse("7．Generate Output.哼，鸡胸肉。", speakers).finalText)
        assertEquals("哼，当然吃。", ContentProtocol.parse("\u200b6. Generate Output.哼，当然吃。", speakers).finalText)
        assertEquals("听着就没劲。", ContentProtocol.parse("2. Determine.听着就没劲。", speakers).finalText)
    }

    @Test
    fun `cot step head keeps normal replies untouched`() {
        // 英文术语开头（无编号分隔符）不误伤
        assertEquals("HTTP 404 就是页面不存在", ContentProtocol.parse("HTTP 404 就是页面不存在", speakers).finalText)
        assertEquals("404 是客户端错误", ContentProtocol.parse("404 是客户端错误", speakers).finalText)
        // 纯英文回复（无中文跟随）不剥
        assertEquals("7. Sure thing!", ContentProtocol.parse("7. Sure thing!", speakers).finalText)
        // 正常中文开头的回复不动
        assertEquals("哼，当然吃。", ContentProtocol.parse("哼，当然吃。", speakers).finalText)
        // 编号后直接跟中文（真列表语义）不剥
        assertEquals("1. 讨论", ContentProtocol.parse("1. 讨论", speakers).finalText)
    }

    // ===== 英文废稿判定（29/30 字边界）=====

    @Test
    fun `draft boundary at 29 vs 30 cjk chars`() {
        val s29 = "一二三四五六七八九".repeat(3) + "一二" // 9×3+2 = 29 个汉字
        val s30 = s29 + "十"
        val d29 = ContentProtocol.parse(s29, speakers)
        val d30 = ContentProtocol.parse(s30, speakers)
        assertTrue(d29.isDraft)
        assertEquals(29, d29.savedCjk)
        assertEquals(s29, d29.finalText)
        assertFalse(d30.isDraft)
        assertEquals(30, d30.savedCjk)
        assertEquals(s30, d30.finalText)
    }

    // ===== 剧本拆条 =====

    @Test
    fun `script splits per speaker with preamble merged into first turn`() {
        val raw = "你好！我是你的Python私教。\n[老师] 今天讲变量。\n[小柯] 变量是什么？\n[老师] 变量是装数据的盒子，盒子上的标签就是变量名。"
        val out = ContentProtocol.parse(raw, speakers)
        val turns = out.turns!!
        assertEquals(3, turns.size)
        assertEquals(SpeakerTurn("老师", "你好！我是你的Python私教。\n\n今天讲变量。"), turns[0])
        assertEquals(SpeakerTurn("小柯", "变量是什么？"), turns[1])
        assertEquals(SpeakerTurn("老师", "变量是装数据的盒子，盒子上的标签就是变量名。"), turns[2])
        assertFalse(out.isDraft)
    }

    @Test
    fun `adjacent same-speaker turns merge`() {
        val raw = "[老师] 第一段讲解。\n[老师] 第二段继续。"
        val out = ContentProtocol.parse(raw, speakers)
        assertEquals(listOf(SpeakerTurn("老师", "第一段讲解。\n\n第二段继续。")), out.turns)
    }

    @Test
    fun `non-cjk head line inside turn body is stripped`() {
        val raw = "[老师] Okay, let me think about it.\n好的，我们从浮力说起，浮力方向总是竖直向上。"
        val out = ContentProtocol.parse(raw, speakers)
        assertEquals(listOf(SpeakerTurn("老师", "好的，我们从浮力说起，浮力方向总是竖直向上。")), out.turns)
    }

    @Test
    fun `empty turn body is skipped`() {
        val raw = "[老师] \n[小柯] 我有个问题想不通。"
        val out = ContentProtocol.parse(raw, speakers)
        assertEquals(listOf(SpeakerTurn("小柯", "我有个问题想不通。")), out.turns)
    }

    @Test
    fun `no speaker tags returns null turns`() {
        val out = ContentProtocol.parse("你好！我是你的Python私教。我们今天从摸底开始。", speakers)
        assertNull(out.turns)
    }

    @Test
    fun `speaker list parameterizes the split regex`() {
        val raw = "[老师] 同学们好，今天讲浮力。\n[小明] 老师，为什么船那么重还能浮起来？\n[老师] 因为浮力等于排开水的重量。"
        val custom = ContentProtocol.parse(raw, listOf("老师", "小明"))
        assertEquals(3, custom.turns?.size)
        assertEquals("小明", custom.turns!![1].speaker)
        assertEquals("老师，为什么船那么重还能浮起来？", custom.turns!![1].text)
        // savedCjk 按落库语义计：全部台词拼接、不含说话人标记（9+14+12）
        assertEquals(35, custom.savedCjk)
        assertFalse(custom.isDraft)
        // 默认说话人表不含 [小明]：该行不拆条，相邻 [老师] 合并为一条
        val defaultSpeakers = ContentProtocol.parse(raw, speakers)
        assertEquals(1, defaultSpeakers.turns?.size)
        assertEquals("老师", defaultSpeakers.turns!![0].speaker)
    }
}

package dev.liuyouwu.familiar.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PetSkinJsonTest {

    private val warns = mutableListOf<String>()
    private val warnFn: (String) -> Unit = { warns += it }
    private fun exists(vararg files: String): (String) -> Boolean = { f -> f in files }

    private val fullJson = """
        {
          "name": "测试鱼",
          "accent": "#1976D2",
          "avatar": "idle_0.png",
          "head": "head.png",
          "clips": {
            "idle":  { "frames": [["idle_0.png", 1100], ["idle_1.png", 150]], "loop": true },
            "busy":  { "frames": [["busy_0.png", 240], ["busy_1.png", 240], ["busy_2.png", 240], ["busy_3.png", 240]], "loop": true },
            "done":  { "frames": [["done_0.png", 110], ["done_1.png", 110], ["done_2.png", 110], ["done_3.png", 110], ["done_4.png", 110], ["done_5.png", 400]], "loop": false },
            "error": { "frames": [["error_0.png", 1000]], "loop": true },
            "shy":   { "frames": [["shy_0.png", 1000]], "loop": true },
            "turn":  { "frames": [["turn_0.png", 1000]], "loop": true },
            "sleep": { "frames": [["sleep_0.png", 1000]], "loop": true }
          }
        }
    """.trimIndent()

    private val files = arrayOf(
        "idle_0.png", "idle_1.png", "busy_0.png", "busy_1.png", "busy_2.png", "busy_3.png",
        "done_0.png", "done_1.png", "done_2.png", "done_3.png", "done_4.png", "done_5.png",
        "error_0.png", "shy_0.png", "turn_0.png", "sleep_0.png", "head.png",
    )

    @Test fun `全量解析逐字段`() {
        val s = PetSkinJson.parse("t", fullJson, exists(*files), warnFn)!!
        assertEquals("测试鱼", s.name)
        assertEquals("#1976D2", s.accent)
        assertEquals("idle_0.png", s.avatarFile)
        assertEquals("head.png", s.headFile)
        assertEquals(listOf<Pair<String, Long>>("idle_0.png" to 1100L, "idle_1.png" to 150L), s.clips.getValue(PetHub.Mood.IDLE).frames)
        assertEquals(listOf("done_5.png" to 400L), s.clips.getValue(PetHub.Mood.DONE).frames.takeLast(1))
        assertEquals(false, s.clips.getValue(PetHub.Mood.DONE).loop)
        assertEquals(1000L, s.clips.getValue(PetHub.Mood.ERROR).frames.single().second)
    }

    @Test fun `派生clip同引用`() {
        val s = PetSkinJson.parse("t", fullJson, exists(*files), warnFn)!!
        assertSame(s.clips.getValue(PetHub.Mood.BUSY), s.clips.getValue(PetHub.Mood.THINKING))
        assertSame(s.clips.getValue(PetHub.Mood.BUSY), s.clips.getValue(PetHub.Mood.SEARCHING))
        assertSame(s.clips.getValue(PetHub.Mood.IDLE), s.clips.getValue(PetHub.Mood.SPEAKING))
    }

    @Test fun `BUSY缺失时THINKING派生落IDLE`() {
        val json = """{"name":"鱼","clips":{"idle":{"frames":[["idle_0.png",100]],"loop":true}}}"""
        val s = PetSkinJson.parse("t", json, exists("idle_0.png"), warnFn)!!
        assertSame(s.clips.getValue(PetHub.Mood.IDLE), s.clips.getValue(PetHub.Mood.THINKING))
        assertSame(s.clips.getValue(PetHub.Mood.IDLE), s.clips.getValue(PetHub.Mood.SPEAKING))
    }

    @Test fun `畸形json拒载`() {
        assertNull(PetSkinJson.parse("t", "{oops", exists(*files), warnFn))
        assertTrue(warns.isNotEmpty())
    }

    @Test fun `name空白拒载`() {
        assertNull(PetSkinJson.parse("t", """{"name":" ","clips":{"idle":{"frames":[["idle_0.png",1]]}}}""", exists("idle_0.png"), warnFn))
    }

    @Test fun `clips空拒载`() {
        assertNull(PetSkinJson.parse("t", """{"name":"鱼","clips":{}}""", exists(*files), warnFn))
    }

    @Test fun `idle无有效帧拒载`() {
        val json = """{"name":"鱼","clips":{"idle":{"frames":[["ghost.png",100]]},"busy":{"frames":[["busy_0.png",1]]}}}"""
        assertNull(PetSkinJson.parse("t", json, exists(*files), warnFn))
    }

    @Test fun `未知状态键告警忽略_含派生键非法`() {
        // dance 自 P6 任务4b 起是合法表演键（见表演解析用例），此处改用真正未知的 fly
        val json = """{"name":"鱼","clips":{"idle":{"frames":[["idle_0.png",100]]},"fly":{"frames":[["idle_0.png",1]]},"thinking":{"frames":[["idle_0.png",1]]}}}"""
        val s = PetSkinJson.parse("t", json, exists("idle_0.png"), warnFn)!!
        assertEquals(4, s.clips.size) // IDLE + 派生 THINKING/SEARCHING/SPEAKING（派生无条件注入）
        assertTrue(warns.any { it.contains("fly") })
        assertTrue(warns.any { it.contains("thinking") })
    }

    @Test fun `表演clip解析_optional键缺失即无此动作`() {
        // 表演键是可选实现：声明了就解析（one-shot loop=false 由 pet.json 声明）；不声明就没有键，
        // 运行时 restartFramePlayer 对缺键 mood 回落 IDLE clip——橘版没帧不崩的机制所在
        val json = """{"name":"鱼","clips":{"idle":{"frames":[["idle_0.png",100]]},"dance":{"frames":[["dance_0.png",130],["dance_1.png",130]],"loop":false},"hum":{"frames":[["hum_0.png",100]]}}}"""
        val s = PetSkinJson.parse("t", json, exists("idle_0.png", "dance_0.png", "dance_1.png", "hum_0.png"), warnFn)!!
        assertEquals(listOf<Pair<String, Long>>("dance_0.png" to 130L, "dance_1.png" to 130L), s.clips.getValue(PetHub.Mood.DANCE).frames)
        assertEquals(false, s.clips.getValue(PetHub.Mood.DANCE).loop)
        assertEquals("hum_0.png" to 100L, s.clips.getValue(PetHub.Mood.HUM).frames.single())
        // 缺键皮肤（橘版未跟量）：未声明的表演键就没有对应条目（运行时帧播玩器回落 IDLE）
        val noDance = PetSkinJson.parse("t", """{"name":"鱼","clips":{"idle":{"frames":[["idle_0.png",100]]},"hum":{"frames":[["hum_0.png",100]]}}}""", exists("idle_0.png", "hum_0.png"), warnFn)!!
        assertTrue(PetHub.Mood.DANCE !in noDance.clips.keys)
    }

    @Test fun `ms非法丢帧`() {
        val json = """{"name":"鱼","clips":{"idle":{"frames":[["idle_0.png",0],["idle_1.png",-5],["idle_2.png","x"],["idle_3.png",100]]}}}"""
        val s = PetSkinJson.parse("t", json, exists("idle_3.png"), warnFn)!!
        assertEquals(listOf("idle_3.png" to 100L), s.clips.getValue(PetHub.Mood.IDLE).frames)
    }

    @Test fun `帧条目类型宽容丢帧`() {
        val json = """{"name":"鱼","clips":{"idle":{"frames":["裸字符串",["只有一列"],["idle_0.png",100]]}}}"""
        val s = PetSkinJson.parse("t", json, exists("idle_0.png"), warnFn)!!
        assertEquals(listOf("idle_0.png" to 100L), s.clips.getValue(PetHub.Mood.IDLE).frames)
    }

    @Test fun `路径穿越丢帧`() {
        val json = """{"name":"鱼","clips":{"idle":{"frames":[["../secret.png",100],["sub/f.png",100],["a\\b.png",100],["idle_0.png",100]]}}}"""
        val s = PetSkinJson.parse("t", json, exists("idle_0.png"), warnFn)!!
        assertEquals(listOf("idle_0.png" to 100L), s.clips.getValue(PetHub.Mood.IDLE).frames)
    }

    @Test fun `浮点毫秒丢帧`() {
        // 1100.5 是合法 JSON 数字但非整数毫秒：丢帧照载（FrameEntryAdapter 用 nextString+toLongOrNull 消化，
        // nextLong 遇浮点会抛 NumberFormatException → 整皮拒载，违背宽容分层）
        val json = """{"name":"鱼","clips":{"idle":{"frames":[["idle_0.png",1100.5],["idle_0.png",1100]]}}}"""
        val s = PetSkinJson.parse("t", json, exists("idle_0.png"), warnFn)!!
        assertEquals(listOf("idle_0.png" to 1100L), s.clips.getValue(PetHub.Mood.IDLE).frames)
    }

    @Test fun `不存在文件丢帧`() {
        val json = """{"name":"鱼","clips":{"idle":{"frames":[["ghost.png",100],["idle_0.png",100]]}}}"""
        val s = PetSkinJson.parse("t", json, exists("idle_0.png"), warnFn)!!
        assertEquals(listOf("idle_0.png" to 100L), s.clips.getValue(PetHub.Mood.IDLE).frames)
    }

    @Test fun `avatar_head越界作废走默认链`() {
        val json = """{"name":"鱼","avatar":"../x.png","head":"../y.png","clips":{"idle":{"frames":[["idle_0.png",100]]}}}"""
        val s = PetSkinJson.parse("t", json, exists("idle_0.png"), warnFn)!!
        assertNull(s.avatarFile)
        assertNull(s.headFile)
        assertEquals("idle_0.png", imageFile(s, Kind.HEAD))   // head→avatar→idle 首帧
        assertEquals("idle_0.png", imageFile(s, Kind.AVATAR))
    }

    @Test fun `imageFile取图链`() {
        val json = """{"name":"鱼","avatar":"idle_0.png","head":"head.png","clips":{"idle":{"frames":[["idle_0.png",100],["idle_1.png",1]]}}}"""
        val s = PetSkinJson.parse("t", json, exists("idle_0.png", "idle_1.png", "head.png"), warnFn)!!
        assertEquals("head.png", imageFile(s, Kind.HEAD))
        assertEquals("idle_0.png", imageFile(s, Kind.AVATAR))
    }

    @Test fun `accent非法为null`() {
        val json = """{"name":"鱼","accent":"red","clips":{"idle":{"frames":[["idle_0.png",100]]}}}"""
        val s = PetSkinJson.parse("t", json, exists("idle_0.png"), warnFn)!!
        assertNull(s.accent)
    }

    @Test fun `loop缺省true`() {
        val json = """{"name":"鱼","clips":{"idle":{"frames":[["idle_0.png",100]]}}}"""
        val s = PetSkinJson.parse("t", json, exists("idle_0.png"), warnFn)!!
        assertTrue(s.clips.getValue(PetHub.Mood.IDLE).loop)
    }

    /** workingDir 直读 assets 本体（test/resources 镜像已删——镜像会让 assets 改动测不出）：
     *  AGP 单测 workingDir=app/ 模块目录，与「内置资产目录18文件齐」同款双路径定位兼容仓库根跑 */
    private fun builtinJson(color: String): String {
        val base = listOf(java.io.File("src/main/assets/pets"), java.io.File("app/src/main/assets/pets")).first { it.isDirectory }
        return java.io.File(base, "$color/pet.json").readBytes().decodeToString()
    }

    /** 内置两皮共用的全 7 clip 逐帧断言（16 帧帧表：idle2+busy4+done6+error/shy/turn/sleep 各1） */
    private fun assertBuiltinClips(s: PetSkin) {
        assertEquals(listOf<Pair<String, Long>>("idle_0.png" to 1100L, "idle_1.png" to 150L), s.clips.getValue(PetHub.Mood.IDLE).frames)
        assertEquals((0..3).map { "busy_$it.png" to 240L }, s.clips.getValue(PetHub.Mood.BUSY).frames)
        assertEquals((0..4).map { "done_$it.png" to 110L } + listOf("done_5.png" to 400L), s.clips.getValue(PetHub.Mood.DONE).frames)
        assertEquals(listOf("error_0.png" to 1000L), s.clips.getValue(PetHub.Mood.ERROR).frames)
        assertEquals(listOf("shy_0.png" to 1000L), s.clips.getValue(PetHub.Mood.SHY).frames)
        assertEquals(listOf("turn_0.png" to 1000L), s.clips.getValue(PetHub.Mood.TURN).frames)
        assertEquals(listOf("sleep_0.png" to 1000L), s.clips.getValue(PetHub.Mood.SLEEP).frames)
        assertEquals(false, s.clips.getValue(PetHub.Mood.DONE).loop)   // 播完定住末帧，不循环
    }

    @Test fun `内置蓝资产petjson逐帧断言`() {
        // 蓝皮全量动作皮——idle 单帧定格（空闲不自主活动），sleep 15 帧入睡序列
        // （播完定住蜷姿）、turn 8 帧，另有 9 个表演 clip（one-shot）。基态三帧表（busy/done/error/shy png）不变
        val s = PetSkinJson.parse("blue", builtinJson("blue"), { true }, warnFn)!!
        assertEquals("蓝色大肥鱼", s.name)
        assertEquals("#1976D2", s.accent)
        assertEquals("idle_0.png", s.avatarFile)   // 头像链仍指旧 png 首帧
        assertEquals("head.png", s.headFile)
        // idle 单帧定格：不循环、不自主活动（需要互动/表演时走对应 clip）
        assertEquals(listOf<Pair<String, Long>>("idle_0.webp" to 9000L), s.clips.getValue(PetHub.Mood.IDLE).frames)
        assertEquals((0..3).map { "busy_$it.png" to 240L }, s.clips.getValue(PetHub.Mood.BUSY).frames)
        assertEquals(listOf("error_0.png" to 1000L), s.clips.getValue(PetHub.Mood.ERROR).frames)
        assertEquals((0..7).map { "turn_$it.webp" to 160L }, s.clips.getValue(PetHub.Mood.TURN).frames)
        assertEquals(15, s.clips.getValue(PetHub.Mood.SLEEP).frames.size)
        assertEquals(false, s.clips.getValue(PetHub.Mood.SLEEP).loop)   // 入睡序列播完定住蜷姿
        // 表演组：9 个 one-shot，帧数 16/32 两档（显式清单——PERFORMANCE_MOODS 在 PetHub object 上，
        // JVM 未 mock 环境不能引用）
        val perfMoods = listOf(PetHub.Mood.DANCE, PetHub.Mood.WAVE, PetHub.Mood.JUMP, PetHub.Mood.HUM,
            PetHub.Mood.TAIL, PetHub.Mood.SNEAK, PetHub.Mood.TOKEN, PetHub.Mood.MEAL, PetHub.Mood.EAT)
        val perf = perfMoods.associateWith { s.clips[it] }
        assertEquals(9, perf.values.filterNotNull().size)   // 蓝皮全实现
        perf.values.filterNotNull().forEach { assertEquals(false, it.loop) }
        assertEquals(16, s.clips.getValue(PetHub.Mood.DANCE).frames.size)
        assertEquals(32, s.clips.getValue(PetHub.Mood.TOKEN).frames.size)
    }

    @Test fun `内置橘资产petjson逐帧断言`() {
        // P6 任务5：橘版跟量——与蓝版同一 clip 结构（批量换色管线产物，帧名相同、目录不同、
        // 色相 -200° 对齐手工橘版基准 20°）。人设字段（name/accent/头像链）保持橘版原值
        val s = PetSkinJson.parse("orange", builtinJson("orange"), { true }, warnFn)!!
        assertEquals("中转站的大肥鱼", s.name)
        assertEquals("#D97757", s.accent)
        assertEquals("idle_0.png", s.avatarFile)
        assertEquals("head.png", s.headFile)
        assertEquals(1, s.clips.getValue(PetHub.Mood.IDLE).frames.size)   // 空闲定格单帧
        assertEquals(15, s.clips.getValue(PetHub.Mood.SLEEP).frames.size)
        assertEquals(false, s.clips.getValue(PetHub.Mood.SLEEP).loop)
        val perfMoods = listOf(PetHub.Mood.DANCE, PetHub.Mood.WAVE, PetHub.Mood.JUMP, PetHub.Mood.HUM,
            PetHub.Mood.TAIL, PetHub.Mood.SNEAK, PetHub.Mood.TOKEN, PetHub.Mood.MEAL, PetHub.Mood.EAT)
        perfMoods.forEach { m ->
            val c = s.clips[m]
            assertTrue("$m 橘版应有表演 clip（one-shot）", c != null && !c.loop)
        }
    }

    @Test fun `内置资产目录文件齐`() {
        // PNG/webp 落盘断言（直读 assets 本体）：AGP 单测 workingDir=app/ 模块目录，兼容从仓库根跑。
        // 蓝橘两皮同构：基础 18（旧 png 头像链/基态帧 + pet.json）+ 248 帧 webp（16×7 + 8 + 32×4），
        // 双向集合断言，多文件/缺文件都红
        val base = listOf(java.io.File("src/main/assets/pets"), java.io.File("app/src/main/assets/pets")).first { it.isDirectory }
        val baseFiles = (
            (0..1).map { "idle_$it.png" } + (0..3).map { "busy_$it.png" } + (0..5).map { "done_$it.png" } +
                listOf("error_0.png", "shy_0.png", "turn_0.png", "sleep_0.png", "head.png", "pet.json")
            ).toSet()
        val perfFiles = (
            (0..15).map { "dance_$it.webp" } + (0..15).map { "wave_$it.webp" } + (0..15).map { "jump_$it.webp" } +
                (0..15).map { "eat_$it.webp" } + (0..15).map { "sneak_$it.webp" } + (0..15).map { "sleep_$it.webp" } +
                (0..15).map { "idle_$it.webp" } + (0..7).map { "turn_$it.webp" } +
                (0..31).map { "hum_$it.webp" } + (0..31).map { "meal_$it.webp" } +
                (0..31).map { "tail_$it.webp" } + (0..31).map { "token_$it.webp" }
            ).toSet()
        listOf("blue", "orange").forEach { color ->
            assertEquals("$color 目录文件集漂移", baseFiles + perfFiles, java.io.File(base, color).list()!!.toSet())
        }
    }
}

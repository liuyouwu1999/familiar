package dev.liuyouwu.familiar.viewmodel.skillhooks

import dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity
import dev.liuyouwu.familiar.ui.screen.chatroom.ChatRoomSetupResult
import dev.liuyouwu.familiar.ui.screen.chatroom.ChatRoomTemplates
import dev.liuyouwu.familiar.ui.screen.chatroom.ParticipantDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天室成员 system 拼装快照（P4 删除批 B：util/ChatRoomPrompts 删除后，
 * memberSystem/groupRules 断言的迁移新家——ChatRoomHooks.memberPrompt/groupRules
 * 为迁自它的唯一实现，原双跑对照锚点收拢为直接快照；被删断言逐条映射见
 * task-6-report.md。roundFor 端到端等价锚点在 SkillHostTest 16/17）。
 */
class ChatRoomHooksTest {

    private fun member(
        identity: String = "",
        traits: String = "",
        speechStyle: String = "",
        catchphrase: String = "",
        exampleDialogs: String = "",
        personality: String = "毒舌但心软的像素小精灵",
        displayName: String = "阿宅"
    ) = ChatRoomParticipantEntity(
        conversationId = 1L,
        modelName = "test-model",
        displayName = displayName,
        personality = personality,
        identity = identity,
        traits = traits,
        speechStyle = speechStyle,
        catchphrase = catchphrase,
        exampleDialogs = exampleDialogs
    )

    @Test
    fun `identity blank keeps legacy personality prompt unchanged`() {
        val s = ChatRoomHooks.memberPrompt(member(identity = ""))
        assertTrue(s.contains("你正在群聊中扮演「阿宅」"))
        assertTrue(s.contains("你的人设：毒舌但心软的像素小精灵"))
        assertTrue(s.contains("绝不代替其他角色或用户发言"))
        assertTrue(s.contains("一次只说 1-3 句"))
        assertTrue(s.contains("不要加角色名前缀"))
        assertTrue(s.contains("自然衔接前面的对话"))
        assertFalse(s.contains("PERSONA_LOAD"))
        assertFalse(s.contains("记忆说明"))
    }

    @Test
    fun `blank personality falls back to default member description`() {
        val s = ChatRoomHooks.memberPrompt(member(identity = "", personality = ""))
        assertTrue(s.contains("一个有趣的群聊成员"))
    }

    @Test
    fun `identity present builds persona card system with group rules appended`() {
        val s = ChatRoomHooks.memberPrompt(
            member(
                identity = "学习圆桌的讲师",
                traits = "一次只讲一个概念，讲完抛一个问题",
                speechStyle = "口语化",
                catchphrase = "就这？",
                exampleDialogs = "<START>\n用户：讲讲\n阿宅：就这？"
            )
        )
        // v2 卡路径：PersonaPromptBuilder 输出在场
        assertTrue(s.contains("PERSONA_LOAD"))
        assertTrue(s.contains("你自称「阿宅」"))
        assertTrue(s.contains("用户叫「小刘鸭」"))   // 角色卡称呼走独立设置，默认"小刘鸭"
        assertFalse(s.contains("每次回复必须以「阿宅」自称、以「小刘鸭」称呼用户"))   // 群聊不强制每轮称呼
        assertTrue(s.contains("学习圆桌的讲师"))
        assertTrue(s.contains("<START>"))
        // 聊天室角色不注入记忆：记忆说明行不得出现（虚假陈述）
        assertFalse(s.contains("记忆说明"))
        // 群规段拼在 build 输出之后
        assertTrue(s.indexOf("提问选项") < s.indexOf("发言要求"))
        assertTrue(s.contains("绝不代替其他角色或用户发言"))
        assertTrue(s.contains("一次只说 1-3 句"))
    }

    @Test
    fun `member prompt is deterministic across calls`() {
        val p = member(identity = "圆桌里爱抬杠的同学", traits = "专挑含糊处追问")
        assertEquals(ChatRoomHooks.memberPrompt(p), ChatRoomHooks.memberPrompt(p))
    }

    @Test
    fun `group rules text is shared verbatim between both paths`() {
        val legacy = ChatRoomHooks.memberPrompt(member(identity = ""))
        val v2 = ChatRoomHooks.memberPrompt(member(identity = "讲师"))
        val rules = ChatRoomHooks.groupRules("阿宅")
        assertTrue(legacy.endsWith(rules))
        assertTrue(v2.endsWith(rules))
    }

    // ===== 建室 wiring 契约（P4 T3 后：结果只承载原始表单值，opening/标题推导迁 VM）=====

    @Test
    fun `setup result carries raw template and topic for vm side derivation`() {
        // P4 T3 改造：openingMessage()/titleOverride() 从结果移除——
        // - 开场锚点断言迁 SkillHostTest `19 roundtable assembly …`（openingMessage 行为
        //   渲染 skill JSON 模板，BuiltinPromptSnapshotTest 钉模板快照）；
        // - "课题即标题" 断言迁 VM createChatRoomConversation（真机清单覆盖）。
        // 此处只钉结果载体本身：模板分流判定 + 原值透传（trim 推导在 VM 侧）。
        val roundtable = ChatRoomSetupResult(
            template = ChatRoomTemplates.ROUNDTABLE,
            topic = " 为什么天空是蓝色的 ",
            participants = listOf(ParticipantDraft(modelName = "m", displayName = "讲师"))
        )
        assertTrue(roundtable.isRoundtable)
        assertEquals(" 为什么天空是蓝色的 ", roundtable.topic)

        val casual = ChatRoomSetupResult(
            template = ChatRoomTemplates.CASUAL,
            topic = "",
            participants = emptyList()
        )
        assertFalse(casual.isRoundtable)
    }
}

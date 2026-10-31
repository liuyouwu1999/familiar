package dev.liuyouwu.familiar

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.liuyouwu.familiar.backup.BackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份协议数据结构测试（纯 JVM，无 AppDatabase）：
 * - v2 三张新表字段可解析，v1 旧备份缺字段必须为 null 而不抛异常；
 * - v3：白名单双标识（TalkWithLLM/Familiar）、人设卡七字段与 messages.segments 可空往返、
 *   v2 旧备份缺新字段解析为空卡并由纯映射函数落非空实体列。
 */
class BackupProtocolTest {

    private val gson = Gson()

    @Test
    fun `FORMAT_VERSION is 4`() {
        assertEquals(4, BackupManager.FORMAT_VERSION)
    }

    @Test
    fun `白名单双标识`() {
        assertNull(BackupManager.validateHeader("TalkWithLLM", 3))
        assertNull(BackupManager.validateHeader("Familiar", 2))
        assertNotNull(BackupManager.validateHeader("OtherApp", 3))
        assertNotNull(BackupManager.validateHeader("Familiar", 5))   // 版本过新
    }

    @Test
    fun `v2 json with participants templates presets parses`() {
        val json = """
            {
              "version": 2,
              "exportedAt": 1000,
              "app": "TalkWithLLM",
              "conversations": [{"id": 1, "title": "A", "systemPrompt": "", "createdAt": 1, "updatedAt": 2, "isChatRoom": true, "isPinned": false, "pinnedAt": null}],
              "messages": [],
              "participants": [
                {"conversationId": 1, "modelName": "qwen3", "displayName": "小助手", "personality": "活泼", "colorHex": "#FF0000", "sortOrder": 0}
              ],
              "promptTemplates": [{"name": "默认", "content": "你好", "isDefault": true, "createdAt": 5}],
              "personaPresets": [{"name": "小红", "personality": "温柔", "colorHex": "#00FF00", "createdAt": 6}]
            }
        """.trimIndent()
        val data = gson.fromJson(json, BackupManager.BackupData::class.java)
        assertEquals(1, data.participants?.size)
        assertEquals("小助手", data.participants!![0].displayName)
        assertEquals("qwen3", data.participants!![0].modelName)
        assertEquals(1, data.promptTemplates?.size)
        assertTrue(data.promptTemplates!![0].isDefault)
        assertEquals(1, data.personaPresets?.size)
        assertEquals("温柔", data.personaPresets!![0].personality)
    }

    @Test
    fun `v1 json without new fields parses with null extras`() {
        val json = """
            {
              "version": 1,
              "exportedAt": 1000,
              "app": "TalkWithLLM",
              "conversations": [{"id": 1, "title": "A", "systemPrompt": "", "createdAt": 1, "updatedAt": 2, "isChatRoom": false, "isPinned": false, "pinnedAt": null}],
              "messages": [{"conversationId": 1, "role": "user", "content": "hi", "timestamp": 3}]
            }
        """.trimIndent()
        // v1 旧备份导入新版必须不抛异常；缺省三表为 null，由 import 侧 orEmpty() 兜底
        val data = gson.fromJson(json, BackupManager.BackupData::class.java)
        assertEquals(1, data.conversations.size)
        assertEquals(1, data.messages.size)
        assertNull(data.participants)
        assertNull(data.promptTemplates)
        assertNull(data.personaPresets)
    }

    @Test
    fun `v2 旧备份缺新字段解析为空卡`() {
        val v2Json = """{"version":2,"app":"TalkWithLLM","conversations":[],"messages":[],
            "participants":[],"promptTemplates":[],
            "personaPresets":[{"name":"旧预设","personality":"外向","colorHex":"#1976D2","createdAt":1}]}"""
        val data = gson.fromJson(v2Json, BackupManager.BackupData::class.java)
        val preset = data.personaPresets!!.single()
        assertEquals("旧预设", preset.name)
        assertNull(preset.exampleDialogs)                              // 新字段可空，旧文件无值
        assertNull(preset.selfName)
        assertNull(preset.address)
    }

    @Test
    fun `v3 导出含卡与分段字段`() {
        // 与 BackupManager.export 同款 Gson（serializeNulls），键名即导出 JSON 的真实键名
        val exportGson = GsonBuilder().serializeNulls().create()
        val preset = BackupManager.BackupPreset(
            name = "小红", personality = "温柔", colorHex = "#00FF00", createdAt = 6,
            identity = "猫娘管家", traits = "黏人", speechStyle = "软萌",
            catchphrase = "喵~", exampleDialogs = "用户：早\n小红：早安喵",
            selfName = "小红", address = "主人"
        )
        val presetJson = exportGson.toJson(preset)
        listOf(
            "identity", "traits", "speechStyle", "catchphrase",
            "exampleDialogs", "selfName", "address"
        ).forEach { key -> assertTrue("preset JSON 应含键 $key: $presetJson", presetJson.contains("\"$key\":")) }
        assertEquals("猫娘管家", preset.identity)

        val participant = BackupManager.BackupParticipant(
            conversationId = 1, modelName = "qwen3", displayName = "小助手",
            identity = "管家", traits = "稳重", speechStyle = "敬语",
            catchphrase = "遵命", exampleDialogs = "示例", selfName = "管家", address = "大人"
        )
        val participantJson = exportGson.toJson(participant)
        listOf(
            "identity", "traits", "speechStyle", "catchphrase",
            "exampleDialogs", "selfName", "address"
        ).forEach { key -> assertTrue("participant JSON 应含键 $key: $participantJson", participantJson.contains("\"$key\":")) }

        val message = BackupManager.BackupMessage(
            conversationId = 1, role = "assistant", content = "你好", timestamp = 9,
            segments = "[{\"type\":\"text\",\"text\":\"你好\"}]"
        )
        val messageJson = exportGson.toJson(message)
        assertTrue("message JSON 应含键 segments: $messageJson", messageJson.contains("\"segments\":"))
        assertEquals("[{\"type\":\"text\",\"text\":\"你好\"}]", message.segments)
    }

    @Test
    fun `v3 preset 带 id 导出导入往返 id 保留`() {
        // 终审 Important：备份若丢 preset.id，恢复时自增重排会拉断/错绑 familiar 卡的 preset_id 关联。
        // 与 BackupMessage/BackupConversation 一致，主键随备份走，toEntity 显式落位（Dao REPLACE）
        val exportGson = GsonBuilder().serializeNulls().create()
        val preset = BackupManager.BackupPreset(
            id = 42, name = "小红", personality = "温柔", colorHex = "#00FF00", createdAt = 6,
            identity = "猫娘管家"
        )
        val json = exportGson.toJson(preset)
        assertTrue("preset JSON 应含键 id: $json", json.contains("\"id\":42"))

        // 备份文件解析回 BackupPreset 再经 toEntity 落库：显式 id 必须原样保留
        val parsed = gson.fromJson(json, BackupManager.BackupPreset::class.java)
        assertEquals(42L, parsed.id)
        assertEquals(42L, BackupManager.toEntity(parsed).id)
    }

    @Test
    fun `v2 卡缺字段恢复映射为空卡`() {
        // v2 备份无卡字段（Gson 解析为 null），纯映射函数落非空实体列：
        // 五个内容字段 -> ""，selfName/address -> "我"/"主人"（与实体迁移默认一致）
        val v2Preset = gson.fromJson(
            """{"name":"旧预设","personality":"外向","colorHex":"#1976D2","createdAt":5}""",
            BackupManager.BackupPreset::class.java
        )
        val presetEntity = BackupManager.toEntity(v2Preset)
        assertEquals("旧预设", presetEntity.name)
        assertEquals("", presetEntity.identity)
        assertEquals("", presetEntity.exampleDialogs)
        assertEquals("我", presetEntity.selfName)
        assertEquals("主人", presetEntity.address)

        val v2Participant = gson.fromJson(
            """{"conversationId":1,"modelName":"qwen3","displayName":"小助手","sortOrder":2}""",
            BackupManager.BackupParticipant::class.java
        )
        val participantEntity = BackupManager.toEntity(v2Participant)
        assertEquals("", participantEntity.identity)
        assertEquals("我", participantEntity.selfName)
        assertEquals("主人", participantEntity.address)
        assertEquals(2, participantEntity.sortOrder)

        // segments 可空：v2 旧消息无该字段，映射后实体保持 null（实体列本就可空）
        val v2Message = gson.fromJson(
            """{"conversationId":1,"role":"user","content":"hi","timestamp":3}""",
            BackupManager.BackupMessage::class.java
        )
        val messageEntity = BackupManager.toEntity(v2Message)
        assertNull(messageEntity.segments)
        assertEquals("hi", messageEntity.content)
        // Agent 内核四列同款可空模式：旧备份缺字段解析为 null，toEntity 直映落 null 列
        assertNull(messageEntity.reasoning)
        assertNull(messageEntity.blocks)
        assertNull(messageEntity.toolMeta)
        assertNull(messageEntity.turnId)
    }

    // ===== P5：Agent 内核结构化四列（reasoning/blocks/toolMeta/turnId）随备份走 =====

    @Test
    fun `BackupMessage agent columns export round trip and toEntity direct map`() {
        // 与 BackupManager.export 同款 Gson（serializeNulls），键名即导出 JSON 的真实键名
        val exportGson = GsonBuilder().serializeNulls().create()
        val message = BackupManager.BackupMessage(
            id = 7, conversationId = 1, role = "assistant", content = "你好", timestamp = 9,
            reasoning = "思考原文",
            blocks = """[{"type":"text","text":"你好"}]""",
            toolMeta = """[{"callId":"b1","name":"echo","argsJson":"{\"q\":\"hi\"}"}]""",
            turnId = "t-1",
        )
        val json = exportGson.toJson(message)
        listOf("reasoning", "blocks", "toolMeta", "turnId").forEach { key ->
            assertTrue("message JSON 应含键 $key: $json", json.contains("\"$key\":"))
        }

        // 备份文件解析回 BackupMessage → toEntity 直映实体列（id 随备份走，Dao REPLACE）
        val parsed = gson.fromJson(json, BackupManager.BackupMessage::class.java)
        assertEquals("思考原文", parsed.reasoning)
        assertEquals(message.blocks, parsed.blocks)
        assertEquals(message.toolMeta, parsed.toolMeta)
        assertEquals("t-1", parsed.turnId)
        val entity = BackupManager.toEntity(parsed)
        assertEquals("思考原文", entity.reasoning)
        assertEquals(message.blocks, entity.blocks)
        assertEquals(message.toolMeta, entity.toolMeta)
        assertEquals("t-1", entity.turnId)
        assertEquals(7L, entity.id)
    }

    @Test
    fun `v3 及更早备份缺 agent 列解析为 null 且映射落 null`() {
        val old = gson.fromJson(
            """{"id":3,"conversationId":1,"role":"user","content":"hi","timestamp":3}""",
            BackupManager.BackupMessage::class.java
        )
        assertNull(old.reasoning)
        assertNull(old.blocks)
        assertNull(old.toolMeta)
        assertNull(old.turnId)
        val entity = BackupManager.toEntity(old)
        assertNull(entity.reasoning)
        assertNull(entity.blocks)
        assertNull(entity.toolMeta)
        assertNull(entity.turnId)
    }

    // ===== v4：大肥鱼记忆 + familiar 设置 =====

    @Test
    fun `v3 旧备份缺 v4 字段解析为 null`() {
        val json = """{"version":3,"exportedAt":1,"app":"Familiar","conversations":[],"messages":[]}"""
        val data = gson.fromJson(json, BackupManager.BackupData::class.java)
        assertNull(data.familiarMemory)
        assertNull(data.familiar)
    }

    @Test
    fun `v4 familiarMemory 与 familiar 设置往返`() {
        val exportGson = GsonBuilder().serializeNulls().create()
        val familiar = BackupManager.BackupFamiliar(
            address = "主人", selfName = "大肥鱼", mode = "proactive", pacing = false
        )
        val json = exportGson.toJson(
            BackupManager.BackupData(
                exportedAt = 1, conversations = emptyList(), messages = emptyList(),
                familiarMemory = "# 大肥鱼记忆文件（自动维护，仅记录真实交互，保持简洁）\n",
                familiar = familiar
            )
        )
        assertTrue("导出应含 familiarMemory: $json", json.contains("\"familiarMemory\":"))

        val data = gson.fromJson(json, BackupManager.BackupData::class.java)
        assertTrue(data.familiarMemory!!.startsWith("# 大肥鱼记忆文件"))
        assertEquals("大肥鱼", data.familiar!!.selfName)
        assertEquals("主人", data.familiar!!.address)
        assertEquals("proactive", data.familiar!!.mode)
        assertEquals(false, data.familiar!!.pacing)
    }

    @Test
    fun `v4 familiar 部分字段缺失导入侧跳过对应项`() {
        val json = """{"version":4,"app":"Familiar","conversations":[],"messages":[],
            "familiar":{"address":"铲屎官"}}"""
        val data = gson.fromJson(json, BackupManager.BackupData::class.java)
        assertEquals("铲屎官", data.familiar!!.address)
        assertNull(data.familiar!!.selfName)
        assertNull(data.familiar!!.mode)
        assertNull(data.familiar!!.pacing)
    }
}

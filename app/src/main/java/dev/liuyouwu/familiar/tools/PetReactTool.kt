package dev.liuyouwu.familiar.tools

import com.google.gson.JsonObject
import dev.liuyouwu.familiar.pet.PetHub

/**
 * pet_react（P6 任务4b）：让模型指挥大肥鱼表演——调研阶段2「AI 状态联动」核心件
 * （对标 dsh 生态 pet_say {text,mood} / clawd-on-desk 状态联动）。execute 经注入的
 * perform 缝调 [PetHub.perform]：默认参直连 PetHub（生产装配）；JVM 测试注入 lambda，
 * 不触 PetHub object（其 mainHandler 字段在未 mock JVM 会炸——PetStateResolver 同款规避）。
 *
 * 门与预算：本地动作零成本，不进 SessionBudgetGate；connected=false 时 perform 内部
 * no-op（她只做连上模型之后的事），execute 照常返回成功文案。频率纪律写进 description
 * （点缀用、一次回复最多一次），不程序化限流。
 */
class PetReactTool(
    private val perform: (PetHub.Mood, String?) -> Unit = { action, line -> PetHub.perform(action, line) },
) : AgentTool {

    override val name = "pet_react"
    override val description =
        "让桌宠大肥鱼做一个动作并可配一句话。用于情绪节点：完成庆祝(jump/dance)、打招呼(wave)、" +
            "讲得有趣时活跃气氛(hum/tail/dance)、投喂或吃到东西(token/meal/eat)、鬼鬼祟祟(sneak)。" +
            "是点缀，不要每轮都调；一次回复最多调用一次。"
    override val parametersJson =
        """{"type":"object","properties":{"action":{"type":"string","enum":["dance","wave","jump","hum","tail","sneak","token","meal","eat"],"description":"动作名"},"text":{"type":"string","description":"表演时说的话，可省略"}},"required":["action"]}"""

    override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String {
        val action = args.get("action")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IllegalArgumentException("缺少必填参数 action")
        val mood = MOODS[action]
            ?: throw IllegalArgumentException("未知动作 $action（可选：dance/wave/jump/hum/tail/sneak/token/meal/eat）")
        val text = args.get("text")?.takeIf { it.isJsonPrimitive }?.asString
        perform(mood, text?.takeIf { it.isNotBlank() })
        return "已让大肥鱼表演「$action」"
    }

    companion object {
        /** 动作名 → mood 唯一映射（与 PetSkinJson STATE_KEYS 的表演子集同源同值） */
        val MOODS: Map<String, PetHub.Mood> = mapOf(
            "dance" to PetHub.Mood.DANCE, "wave" to PetHub.Mood.WAVE, "jump" to PetHub.Mood.JUMP,
            "hum" to PetHub.Mood.HUM, "tail" to PetHub.Mood.TAIL, "sneak" to PetHub.Mood.SNEAK,
            "token" to PetHub.Mood.TOKEN, "meal" to PetHub.Mood.MEAL, "eat" to PetHub.Mood.EAT,
        )
    }
}

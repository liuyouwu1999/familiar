package dev.liuyouwu.familiar.skills

/**
 * skill 注册表（spec §6.4 pi 语义）：`builtin` 被同 id 的 `user` 定义覆盖。
 *
 * 两个构造入口：[fromAssets]（Android 侧从 `assets/skills/` 读，P3 接线）与
 * [fromAssetsJsons]（JSON 字符串集合，单测 test resources 注入——该目录与
 * assets 逐字节一致，一致性由任务步骤中的 bash diff 核对）。
 */
class SkillRegistry(
    private val builtin: Map<String, SkillDefinition>,
    private val user: Map<String, SkillDefinition> = emptyMap(),
) {

    /** user 同 id 覆盖 builtin；未知 id → null */
    fun get(id: String): SkillDefinition? = user[id] ?: builtin[id]

    /** builtin 键序在前、user 独有项在后；同 id 只保留 user 版本（pi 覆盖语义） */
    fun all(): List<SkillDefinition> {
        val ids = LinkedHashSet<String>()
        ids.addAll(builtin.keys)
        ids.addAll(user.keys)
        return ids.mapNotNull { get(it) }
    }

    companion object {

        /**
         * 从 JSON 字符串集合构造内置表。键为来源标识（如 assets 文件名 "chat.json"），
         * 仅用于告警定位；skill id 以 [SkillJson.parse] 的解析结果为准。
         *
         * 宽容加载（spec §6.4）：单条畸形/拒载仅告警并跳过，不拖垮整体；
         * 解析出的 id 重复时保留先到者并告警（内置资产按 id 命名，重复即打包错误）。
         */
        fun fromAssetsJsons(jsons: Map<String, String>, warn: (String) -> Unit = {}): SkillRegistry {
            val builtin = LinkedHashMap<String, SkillDefinition>()
            for ((source, json) in jsons) {
                val def = SkillJson.parse(json) { msg -> warn("[$source] $msg") }
                if (def == null) continue
                if (builtin.containsKey(def.id)) {
                    warn("[$source] SkillDefinition id '${def.id}' 重复，保留先到者")
                } else {
                    builtin[def.id] = def
                }
            }
            return SkillRegistry(builtin)
        }

        /**
         * Android assets 目录加载（P3 Task 6）：读 `assets/<dir>` 目录下全部 `.json`
         * 文件为字符串集合后走 [fromAssetsJsons]（宽容语义同源：单文件畸形拒载不拖垮整体）。
         *
         * **不可单测缝**：[AssetManager] 是 Android 框架操作（JVM 单测读不到 assets），
         * 单测一律经 fromAssetsJsons 注入 test resources 的 JSON map
         * （`src/test/resources/skills/` 与 assets 逐字节一致，由任务步骤 bash diff 核对）。
         * 键为 `<dir>/<file>` 路径（仅告警定位用）；非 `.json` 文件跳过；目录不存在
         * （list 返回 null/空）→ 空注册表 + 单条告警（P5 接线时上层降级处理）。
         */
        fun fromAssets(assets: android.content.res.AssetManager, dir: String, warn: (String) -> Unit = {}): SkillRegistry {
            val names = try {
                assets.list(dir).orEmpty()
            } catch (e: Exception) {
                warn("assets 目录 '$dir' 读取失败：${e.message}")
                return fromAssetsJsons(emptyMap(), warn)
            }
            if (names.isEmpty()) warn("assets 目录 '$dir' 为空或不存在，内置 skill 注册表为空")
            val jsons = LinkedHashMap<String, String>()
            for (name in names.sorted()) {
                if (!name.endsWith(".json")) continue
                val path = "$dir/$name"
                jsons[path] = try {
                    assets.open(path).bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    warn("[$path] 读取失败：${e.message}")
                    continue
                }
            }
            return fromAssetsJsons(jsons, warn)
        }
    }
}

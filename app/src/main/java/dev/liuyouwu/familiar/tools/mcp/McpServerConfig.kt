package dev.liuyouwu.familiar.tools.mcp

/**
 * 一台 MCP 服务器的用户配置（Task 1）：KEY_MCP_SERVERS 里 Gson JSON 数组的元素。
 *
 * - [name] 用户可读名，也是身份键（saveMcpServer 按 name upsert / deleteMcpServer 按 name 删）；
 *   合法性由 [requireLegalName] 把关（唯一权威，repository 与 UI 双调）；
 * - [slug] 规范化标识（计算属性，与 companion [slugOf] 同源），供下游派生工具名等；
 * - [tokenCipher] Bearer token 密文（util/CryptoStore AES-GCM，与 KEY_API_KEY_ENC 同口径）；
 *   Keystore 不可用时明文回退也存此字段（saveMcpServer 返回 false 由 UI 提示）；
 *   null/空 = 未配置 token；编辑不改 token 的场景由调用方透传原值（tokenPlain=null）保留；
 * - [enabled] 单服务器开关（总开关是 SettingsRepository.mcpEnabled，默认 false）。
 */
data class McpServerConfig(
    val name: String,
    val url: String,
    val tokenCipher: String? = null,
    val enabled: Boolean = false
) {
    /** 规范化标识：[slugOf] 的计算属性形态，随 name 派生 */
    val slug: String
        get() = slugOf(name)

    companion object {

        /** 合法 slug 字符集：字母/数字/下划线/连字符，其余一律折叠 */
        private val NON_SLUG_CHARS = Regex("[^a-zA-Z0-9_-]")
        private val CONSECUTIVE_UNDERSCORES = Regex("_+")

        /**
         * slug 规范化（纯函数）：非 `[a-zA-Z0-9_-]` → "_"，连续 "_" 折叠为单个 "_"。
         * 恒返回折叠串、不返回空：非空输入至少产出 1 个字符（全非法字符折叠为 "_"，
         * 如 slugOf("文件")=="_"）；空串输入折叠结果为空串（由 [requireLegalName] 拒收）。
         */
        fun slugOf(name: String): String {
            return CONSECUTIVE_UNDERSCORES
                .replace(NON_SLUG_CHARS.replace(name, "_"), "_")
        }

        /**
         * 拒存判据唯一权威（saveMcpServer 与 UI 双调）：[slugOf] 结果 isBlank
         * 或全 "_"（没有任何字母/数字/连字符）→ IllegalArgumentException。
         * 防止全中文/纯符号名折叠成 "_" 后多个服务器 slug 撞车。
         */
        fun requireLegalName(name: String) {
            val slug = slugOf(name)
            require(slug.isNotBlank() && slug.any { it != '_' }) {
                "MCP 服务器名 \"$name\" 规范化后无有效字符（slug=\"$slug\"），请改用含字母/数字的名字"
            }
        }
    }
}

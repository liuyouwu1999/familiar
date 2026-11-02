package dev.liuyouwu.familiar.util

/** 嵌入（embedding）模型只能用于向量化，选去做对话必报错，各模型列表应过滤掉。 */
fun isEmbeddingModel(id: String): Boolean = id.contains("embed", ignoreCase = true)

/**
 * 模型 id 的展示名：llama.cpp 这类后端把 id 返回成服务器文件全路径
 * （如 /home/me/models/xx/Qwen3-8B-Q6_K.gguf），原样上顶栏又长又泄露目录结构。
 * 展示层（顶栏副标题/连接页当前模型/模型列表）统一用它：取文件名、去 gguf 后缀；
 * 内部落库与请求仍用完整 id，不受影响。
 */
fun modelDisplayName(id: String): String {
    if (id.isBlank()) return id
    val name = id.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = name.removeSuffix(".gguf").removeSuffix(".GGUF")
    return cleaned.ifBlank { id }
}

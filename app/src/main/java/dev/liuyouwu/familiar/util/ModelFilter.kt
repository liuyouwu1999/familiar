package dev.liuyouwu.familiar.util

/** 嵌入（embedding）模型只能用于向量化，选去做对话必报错，各模型列表应过滤掉。 */
fun isEmbeddingModel(id: String): Boolean = id.contains("embed", ignoreCase = true)

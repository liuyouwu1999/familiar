package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.util.modelDisplayName
import org.junit.Assert.assertEquals
import org.junit.Test

/** 模型 id 展示名：路径型 id（llama.cpp 全路径）取文件名去后缀，普通 id 原样。 */
class ModelDisplayNameTest {

    @Test
    fun `llamacpp 全路径取文件名去 gguf 后缀`() {
        assertEquals(
            "Huihui-Qwen3.8-27B-abliterated-Q6_K",
            modelDisplayName("/home/rn/models/Qwen3.8-27B-abliterated/Huihui-Qwen3.8-27B-abliterated-Q6_K.gguf"),
        )
    }

    @Test
    fun `无目录的文件名同样去后缀`() {
        assertEquals("qwen2_5-7b-instruct", modelDisplayName("qwen2_5-7b-instruct.gguf"))
    }

    @Test
    fun `ollama 短名与 openai id 原样返回`() {
        assertEquals("qwen3.8-27b", modelDisplayName("qwen3.8-27b"))
        assertEquals("gpt-4o-mini", modelDisplayName("gpt-4o-mini"))
    }

    @Test
    fun `反斜杠路径也按文件名处理`() {
        assertEquals("model-q4", modelDisplayName("C:\\models\\model-q4.gguf"))
    }

    @Test
    fun `空串与非常规输入不崩`() {
        assertEquals("", modelDisplayName(""))
        assertEquals("   ", modelDisplayName("   "))
        // 去完名字为空的极端（id 就是 ".gguf"）回退原值
        assertEquals(".gguf", modelDisplayName(".gguf"))
    }
}

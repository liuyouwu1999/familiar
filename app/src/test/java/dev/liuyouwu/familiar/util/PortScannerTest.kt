package dev.liuyouwu.familiar.util

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二段端口扫描单测（连接设置"填 IP 未填端口"兜底）：端口段构成、
 * sweep 首中即返、全空返 null、**晚到的命中不被早完成的未命中吞掉**
 * （select 过滤实现的历史坑，回归钉死）。
 */
class PortScannerTest {

    private val commonPorts = listOf(8080, 11434, 1234, 5000, 8000, 1337, 7860)

    @Test
    fun `扩展端口段覆盖 8081 且与常用名单去重`() {
        val ports = PortScanner.extendedPortsExcluding(commonPorts)
        assertTrue("8081 必须在扩展段里", 8081 in ports)
        assertTrue("3000 段在列", 3000 in ports)
        assertTrue("5001 段在列", 5001 in ports)
        commonPorts.forEach { p ->
            assertTrue("常用名单已在一段探过，扩展段不得重复: $p", p !in ports)
        }
        assertEquals("无重复元素", ports.size, ports.toSet().size)
        assertEquals("8000-8099(100) + 3000-3009(10) + 5000-5010(11) 去重 8080/5000/8000", 118, ports.size)
    }

    @Test
    fun `sweep 命中端口回填完整地址与模型列表`() = runBlocking {
        val hitUrl = "http://192.168.1.100:8082"
        val seen = mutableListOf<String>()
        val result = PortScanner.sweep(
            hostUrl = "http://192.168.1.100",
            ports = listOf(8080, 8081, 8082),
        ) { base ->
            seen.add(base)
            // 命中放末位并延迟：前面的未命中探测先完成，避免"命中先到取消其余探测"
            // 的正当行为反过来吞掉本测试对 :8080 探测顺序的断言（CI 机器时序实证的 flake）
            if (base == hitUrl) {
                delay(100)
                listOf("qwen3.8-27b")
            } else {
                null
            }
        }
        assertEquals(hitUrl, result?.first)
        assertEquals(listOf("qwen3.8-27b"), result?.second)
        assertTrue("探测地址应是改端口后的完整 URL", "http://192.168.1.100:8080" in seen)
    }

    @Test
    fun `sweep 全部未命中返回 null`() = runBlocking {
        val result = PortScanner.sweep(
            hostUrl = "http://192.168.1.100",
            ports = listOf(8081, 8082),
        ) { null }
        assertNull(result)
    }

    @Test
    fun `sweep 晚到命中不被早完成的未命中吞掉`() = runBlocking {
        // 端口 8080 立即返回未命中；8081 稍晚返回命中——先完成的未命中
        // 不得把仍在途的 8081 从 pending 里滤掉（回归：按 isCompleted 过滤会丢命中）
        val result = PortScanner.sweep(
            hostUrl = "http://192.168.1.100",
            ports = listOf(8080, 8081),
        ) { base ->
            if (base.endsWith(":8080")) {
                null
            } else {
                delay(50)
                listOf("m")
            }
        }
        assertEquals("http://192.168.1.100:8081", result?.first)
    }

    @Test
    fun `sweep 命中后取消其余探测`() = runBlocking {
        var lateProbed = false
        val result = PortScanner.sweep(
            hostUrl = "http://192.168.1.100",
            ports = listOf(8080, 8081, 9999),
        ) { base ->
            when {
                base.endsWith(":8080") -> null
                base.endsWith(":8081") -> listOf("m")
                else -> {
                    delay(10_000)
                    lateProbed = true
                    null
                }
            }
        }
        assertEquals("http://192.168.1.100:8081", result?.first)
        assertTrue("命中后慢探测应被取消", !lateProbed)
    }
}

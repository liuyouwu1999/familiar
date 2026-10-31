package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.data.update.AssetPicker
import dev.liuyouwu.familiar.data.update.ChangelogExtractor
import dev.liuyouwu.familiar.data.update.GitHubHttpException
import dev.liuyouwu.familiar.data.update.Release
import dev.liuyouwu.familiar.data.update.ReleaseAsset
import dev.liuyouwu.familiar.data.update.ReleaseHttp
import dev.liuyouwu.familiar.data.update.ReleaseJsonParser
import dev.liuyouwu.familiar.data.update.Sha256SumsParser
import dev.liuyouwu.familiar.data.update.UpdateCheckResult
import dev.liuyouwu.familiar.data.update.UpdateCleanupPolicy
import dev.liuyouwu.familiar.data.update.UpdateRepository
import dev.liuyouwu.familiar.data.update.VersionCompare
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {

    // ---------------- VersionCompare ----------------

    @Test
    fun `版本比较逐段数值短侧补零`() {
        assertTrue(VersionCompare.compare("1.1", "1.2") < 0)
        assertTrue(VersionCompare.compare("1.10", "1.9") > 0)
        assertEquals(0, VersionCompare.compare("1.1", "1.1.0"))
        assertTrue(VersionCompare.compare("1.1.1", "1.1") > 0)
    }

    @Test
    fun `v 前缀与空白被容忍`() {
        assertTrue(VersionCompare.isUpdateAvailable("1.1", "v1.2"))
        assertTrue(VersionCompare.isUpdateAvailable("1.1", " 1.2 "))
        assertFalse(VersionCompare.isUpdateAvailable("1.1", "v1.1"))
    }

    @Test
    fun `非法 tag 安全侧视为无更新`() {
        assertFalse(VersionCompare.isUpdateAvailable("1.1", null))
        assertFalse(VersionCompare.isUpdateAvailable("1.1", ""))
        assertFalse(VersionCompare.isUpdateAvailable("1.1", "latest"))
        assertFalse(VersionCompare.isUpdateAvailable("1.1", "1.2.3-beta"))
        assertEquals(0, VersionCompare.compare("1.1", "x.y"))
    }

    // ---------------- ReleaseJsonParser ----------------

    @Test
    fun `解析完整 release 载荷`() {
        val json = """
            {"tag_name":"v1.2","html_url":"https://github.com/o/r/releases/tag/v1.2",
             "body":"```changelog\n修复 A\n新增 B\n```",
             "assets":[
               {"name":"Familiar-1.2.apk","browser_download_url":"https://x/Familiar-1.2.apk"},
               {"name":"SHA256SUMS.txt","browser_download_url":"https://x/SHA256SUMS.txt"},
               {"name":"空url资产","browser_download_url":""}
             ]}
        """.trimIndent()
        val r = ReleaseJsonParser.parse(json)!!
        assertEquals("v1.2", r.tagName)
        assertEquals(2, r.assets.size)   // 空 url 资产被剔除
        assertNull(ReleaseJsonParser.parse("{\"tag_name\":\"\"}"))
        assertNull(ReleaseJsonParser.parse("not json"))
    }

    @Test
    fun `body 为 null 字面量时返回 null 而非崩溃`() {
        val json = """{"tag_name":"v1.2","html_url":"u","body":null,"assets":[]}"""
        val r = ReleaseJsonParser.parse(json)!!
        assertNull(r.body)
    }

    // ---------------- AssetPicker ----------------

    @Test
    fun `挑 apk 且官方前缀优先`() {
        val assets = listOf(
            ReleaseAsset("random-app.apk", "u1"),
            ReleaseAsset("Familiar-1.2.apk", "u2"),
            ReleaseAsset("SHA256SUMS.txt", "u3"),
        )
        assertEquals("u2", AssetPicker.pick(assets)?.url)
        assertNull(AssetPicker.pick(listOf(ReleaseAsset("SHA256SUMS.txt", "u3"))))
    }

    // ---------------- Sha256SumsParser ----------------

    @Test
    fun `星号与文本双格式兼容且忽略非法行`() {
        val text = """
            abc00000000000000000000000000000000000000000000000000000000000ff  Familiar-1.2.apk
            ddd11111111111111111111111111111111111111111111111111111111111ee *other.apk
            garbage line
        """.trimIndent()
        val sums = Sha256SumsParser.parse(text)
        assertEquals("abc00000000000000000000000000000000000000000000000000000000000ff", sums["Familiar-1.2.apk"])
        assertEquals("ddd11111111111111111111111111111111111111111111111111111111111ee", sums["other.apk"])
        assertEquals(2, sums.size)
        assertTrue(Sha256SumsParser.parse("garbage").isEmpty())
    }

    // ---------------- ChangelogExtractor ----------------

    @Test
    fun `changelog 围栏优先`() {
        val body = "前置说明\n```changelog\n修复 A\n新增 B\n```\n后置"
        assertEquals(listOf("修复 A", "新增 B"), ChangelogExtractor.extract(body))
    }

    @Test
    fun `无围栏降级去 markdown`() {
        val body = "## 标题\n- [链接](https://x)\n`代码`"
        assertEquals(listOf("标题", "链接", "代码"), ChangelogExtractor.extract(body))
        assertTrue(ChangelogExtractor.extract(null).isEmpty())
        assertTrue(ChangelogExtractor.extract("  ").isEmpty())
    }

    // ---------------- UpdateRepository 编排 ----------------

    private class FakeHttp(private val responses: Map<String, String>, private val errors: Map<String, Exception> = emptyMap()) : ReleaseHttp {
        override fun get(url: String): String {
            errors[url]?.let { throw it }
            return responses[url] ?: throw IOException("no stub for $url")
        }
    }

    private val api = UpdateRepository.DEFAULT_API_URL
    private val sumsUrl = "https://x/SHA256SUMS.txt"
    private val okJson = """
        {"tag_name":"v9.9","html_url":"https://github.com/o/r/releases/tag/v9.9",
         "body":"```changelog\n大更新\n```",
         "assets":[
           {"name":"Familiar-9.9.apk","browser_download_url":"https://x/Familiar-9.9.apk"},
           {"name":"SHA256SUMS.txt","browser_download_url":"$sumsUrl"}
         ]}
    """.trimIndent()

    @Test
    fun `无新版直接 UpToDate 不再请求 sums`() {
        val json = okJson.replace("v9.9", "v1.0")
        var sumsFetched = false
        val http = object : ReleaseHttp {
            override fun get(url: String): String {
                if (url == sumsUrl) sumsFetched = true
                return json
            }
        }
        assertTrue(UpdateRepository(http).check("1.1") is UpdateCheckResult.UpToDate)
        assertFalse(sumsFetched)
    }

    @Test
    fun `有新版带 sums 可安装`() {
        val http = FakeHttp(
            mapOf(
                api to okJson,
                sumsUrl to "ff000000000000000000000000000000000000000000000000000000000000aa  Familiar-9.9.apk",
            ),
        )
        val r = UpdateRepository(http).check("1.1") as UpdateCheckResult.Available
        assertTrue(r.canInstall)
        assertEquals(listOf("大更新"), r.changelog)
        assertEquals("Familiar-9.9.apk", r.asset?.name)
    }

    @Test
    fun `sums 拉取失败不阻断 Available 但不可应用内安装`() {
        val http = FakeHttp(mapOf(api to okJson), mapOf(sumsUrl to IOException("boom")))
        val r = UpdateRepository(http).check("1.1") as UpdateCheckResult.Available
        assertFalse(r.canInstall)
        assertNull(r.sums)
    }

    @Test
    fun `403-429 映射限流其余映射网络`() {
        assertEquals(
            UpdateCheckResult.Failed.Reason.RATE_LIMITED,
            (UpdateRepository(FakeHttp(emptyMap(), mapOf(api to GitHubHttpException(403)))).check("1.1") as UpdateCheckResult.Failed).reason,
        )
        assertEquals(
            UpdateCheckResult.Failed.Reason.NETWORK,
            (UpdateRepository(FakeHttp(emptyMap(), mapOf(api to IOException("net")))).check("1.1") as UpdateCheckResult.Failed).reason,
        )
        assertEquals(
            UpdateCheckResult.Failed.Reason.BAD_RESPONSE,
            (UpdateRepository(FakeHttp(mapOf(api to "garbage"))).check("1.1") as UpdateCheckResult.Failed).reason,
        )
    }

    // ---------------- UpdateCleanupPolicy ----------------

    private val activePending = Triple(7L, android.app.DownloadManager.STATUS_PENDING, "a.apk")
    private val activeRunning = Triple(7L, android.app.DownloadManager.STATUS_RUNNING, "a.apk")
    private val doneDownload = Triple(7L, android.app.DownloadManager.STATUS_SUCCESSFUL, "a.apk")

    @Test
    fun `活动下载目标文件保留`() {
        assertFalse(UpdateCleanupPolicy.shouldDelete("a.apk", activePending, null, "1.1"))
        assertFalse(UpdateCleanupPolicy.shouldDelete("a.apk", activeRunning, null, "1.1"))
    }

    @Test
    fun `待装版本更新期间目录内文件保守保留`() {
        // 校验通过后 downloadId 即清：进程死亡后无法辨认哪个文件是待装包，宁可保留孤儿
        // 也不能误删待装包断掉「去授权→返回→续装」链路；版本追上后全部清理
        assertFalse(UpdateCleanupPolicy.shouldDelete("a.apk", doneDownload, "1.2", "1.1"))
        assertFalse(UpdateCleanupPolicy.shouldDelete("orphan.apk", activeRunning, "1.2", "1.1"))
    }

    @Test
    fun `无待装或待装过期时全部清理`() {
        assertTrue(UpdateCleanupPolicy.shouldDelete("a.apk", doneDownload, "1.1", "1.1"))
        assertTrue(UpdateCleanupPolicy.shouldDelete("a.apk", doneDownload, null, "1.1"))
        assertTrue(UpdateCleanupPolicy.shouldDelete("a.apk", null, null, "1.1"))
        assertTrue(UpdateCleanupPolicy.shouldDelete("orphan.apk", activeRunning, "1.1", "1.1"))
    }
}

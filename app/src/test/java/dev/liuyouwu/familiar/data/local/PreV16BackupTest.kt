package dev.liuyouwu.familiar.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PreV16Backup（升级前自动备份）纯缝测试：不触碰任何 Android 框架类
 * （Prefs/VersionReader/DbFileCopier 全部以测试替身注入，路径用普通 File）。
 *
 * 7 用例对应任务简报 Step 1：15 / 14（跨版本直升）/ 1（区间下界）→ 复制+置标记；
 * 16 → 不复制；0（无库）→ 不复制但置标记；copier 返回 false → 置标记不抛；
 * 已置标记 → 完全 no-op。
 */
class PreV16BackupTest {

    /** 内存版标记替身：记录 markDone 调用次数以便断言 no-op */
    private class FakePrefs : Prefs {
        var done = false
            private set
        var markCount = 0
            private set

        override fun isDone(): Boolean = done

        override fun markDone() {
            done = true
            markCount++
        }
    }

    /** 记录调用的 copier 替身：可注入返回值（true/false）或直接抛异常 */
    private class RecordingCopier(
        private val result: Boolean = true,
        private val throwable: Throwable? = null,
    ) : DbFileCopier {
        var calls = 0
            private set
        var lastSrc: String? = null
            private set
        var lastDest: String? = null
            private set

        override fun copyWithCheckpoint(dbPath: String, destPath: String): Boolean {
            calls++
            lastSrc = dbPath
            lastDest = destPath
            throwable?.let { throw it }
            return result
        }
    }

    /** 版本可注入、读取次数可断言的 reader 替身 */
    private class StubReader(private val version: Int) : VersionReader {
        var calls = 0
            private set

        override fun readVersion(dbPath: String): Int {
            calls++
            return version
        }
    }

    private val dbFile = File("/data/user/0/dev.liuyouwu.familiar/databases/familiar_database")
    private val destFile = File("/data/user/0/dev.liuyouwu.familiar/files/db_backup_pre_v16.db")

    private fun backup(
        prefs: Prefs,
        reader: VersionReader,
        copier: DbFileCopier,
    ) = PreV16Backup(prefs, reader, copier, dbFile, destFile)

    @Test
    fun `版本15备份并置标记且路径正确`() {
        val prefs = FakePrefs()
        val reader = StubReader(15)
        val copier = RecordingCopier(result = true)

        backup(prefs, reader, copier).runOnce()

        assertEquals(1, copier.calls)
        assertEquals(dbFile.absolutePath, copier.lastSrc)
        assertEquals(destFile.absolutePath, copier.lastDest)
        assertTrue(prefs.done)
    }

    @Test
    fun `版本14跨版本直升同样备份`() {
        val prefs = FakePrefs()
        val copier = RecordingCopier(result = true)

        backup(prefs, StubReader(14), copier).runOnce()

        assertEquals(1, copier.calls)
        assertTrue(prefs.done)
    }

    @Test
    fun `版本1区间下界同样备份`() {
        val prefs = FakePrefs()
        val copier = RecordingCopier(result = true)

        backup(prefs, StubReader(1), copier).runOnce()

        assertEquals(1, copier.calls)
        assertTrue(prefs.done)
    }

    @Test
    fun `版本16不复制`() {
        val prefs = FakePrefs()
        val reader = StubReader(16)
        val copier = RecordingCopier(result = true)

        backup(prefs, reader, copier).runOnce()

        assertEquals(0, copier.calls)
    }

    @Test
    fun `版本0无库不复制但置标记`() {
        val prefs = FakePrefs()
        val reader = StubReader(0)
        val copier = RecordingCopier(result = true)

        backup(prefs, reader, copier).runOnce()

        assertEquals(0, copier.calls)
        assertTrue(prefs.done)
    }

    @Test
    fun `copier返回false置标记且不抛`() {
        val prefs = FakePrefs()
        val copier = RecordingCopier(result = false)

        backup(prefs, StubReader(15), copier).runOnce()

        assertEquals(1, copier.calls)
        assertTrue(prefs.done)
    }

    @Test
    fun `已置标记则完全no-op`() {
        val prefs = FakePrefs().apply { markDone() }
        val before = prefs.markCount
        val reader = StubReader(15)
        val copier = RecordingCopier(result = true)

        backup(prefs, reader, copier).runOnce()

        assertEquals(0, reader.calls)
        assertEquals(0, copier.calls)
        assertFalse(prefs.markCount > before)
        assertEquals(1, prefs.markCount)
    }
}

package dev.liuyouwu.familiar.data.local

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * 备份标记缝：SharedPreferences 的最小面（生产包真机存储，测试用内存替身）。
 * 纯 JVM 单测不依赖任何 Android 框架类，故不直接暴露 [SharedPreferences]。
 */
interface Prefs {
    fun isDone(): Boolean

    fun markDone()
}

/** 版本读取缝：读 db 的 PRAGMA user_version（Room schema 版本即写在此处）。 */
fun interface VersionReader {
    fun readVersion(dbPath: String): Int
}

/** 复制缝：WAL checkpoint 合并 + 整文件复制，失败返回 false（不抛）。 */
fun interface DbFileCopier {
    fun copyWithCheckpoint(dbPath: String, destPath: String): Boolean
}

/**
 * PreV16Backup：v16 迁移前的旧库自动备份（Agent 内核 P2，任务 6）。
 *
 * [runOnce] 语义（尽力而为，绝不阻断启动/升级）：
 * - 标记已置 → 直接返回；
 * - 版本 1..15（含跨版本直升，如 14→16）→ checkpoint+复制旧库；
 *   **复制成功与否都置标记**，失败仅 log 告警——重试无意义：下次启动时迁移已完成，
 *   备到的将是迁移后的新库而非旧数据；
 * - 版本 0（无库/读不出）或 ≥16（已是新库）→ 不复制，置标记返回。
 *
 * ## 线程与竞态决策（评审 P1-4 裁定）
 * 版本读取（只读、廉价）同步执行；检测到待升级时 **checkpoint+复制也同步执行**：
 * - 一次性成本，上界 = 库文件大小；消息表内联 base64 的重度用户会付出一次性主线程 IO；
 * - 取舍依据：异步复制会与懒打开的 Room 首次写入竞态（备份可能捕获写了一半的状态），
 *   数据安全 > 一次性启动耗时；
 * - **P3 真机清单项**：实测升级耗时，若超阈值（如 >2s）改为"启动页阻塞 UI"方案。
 *
 * 调用点：[dev.liuyouwu.familiar.FamiliarApp.onCreate] 末尾——DB 懒打开，
 * 该处必然先于首次 [AppDatabase.getInstance]。
 */
class PreV16Backup(
    private val prefs: Prefs,
    private val reader: VersionReader,
    private val copier: DbFileCopier,
    private val dbFile: File,
    private val destFile: File,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {

    /** 幂等入口：只在首次满足条件时复制一次（成功或失败都消耗这次机会）。 */
    fun runOnce() {
        if (prefs.isDone()) return
        when (val version = reader.readVersion(dbFile.absolutePath)) {
            in 1..15 -> {
                // copier 自身约定不抛；runCatching 兜底（本类运行在 Application.onCreate，
                // 任何异常都不允许外溢）
                val ok = runCatching {
                    copier.copyWithCheckpoint(dbFile.absolutePath, destFile.absolutePath)
                }.getOrDefault(false)
                if (!ok) log("pre-v16 备份失败（尽力而为，不阻断升级），version=$version", null)
            }
            else -> Unit // 0=全新安装/库不存在；≥16=已迁移，均无需备份
        }
        prefs.markDone()
    }

    companion object {
        private const val TAG = "PreV16Backup"
        private const val PREFS_NAME = "pre_v16_backup"
        private const val KEY_DONE = "pre_v16_backup_done"

        /** 与 [AppDatabase.getInstance] 的 databaseBuilder 名字保持一致 */
        private const val DB_NAME = "familiar_database"
        private const val DEST_NAME = "db_backup_pre_v16.db"

        /** 生产接线入口：FamiliarApp.onCreate 末尾调用（一行）。 */
        fun runOnce(context: Context) {
            val ctx = context.applicationContext
            PreV16Backup(
                prefs = object : Prefs {
                    private val sp: SharedPreferences =
                        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                    override fun isDone(): Boolean = sp.getBoolean(KEY_DONE, false)

                    override fun markDone() {
                        sp.edit().putBoolean(KEY_DONE, true).apply()
                    }
                },
                reader = VersionReader { path ->
                    // 文件不存在/损坏/异常 → 0（视为全新安装，无需备份）
                    runCatching {
                        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
                            .use { it.version }
                    }.getOrDefault(0)
                },
                copier = DbFileCopier { src, dest ->
                    runCatching {
                        // 先 TRUNCATE checkpoint 把 WAL 增量合并进主文件再复制，
                        // 否则 File.copyTo 只拷到主文件、丢 WAL 里的近期写入
                        SQLiteDatabase.openDatabase(src, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                        }
                        File(src).copyTo(File(dest), overwrite = true)
                        true
                    }.getOrDefault(false) // 任何失败都只返回 false：备份尽力而为，不抛
                },
                dbFile = ctx.getDatabasePath(DB_NAME),
                destFile = File(ctx.filesDir, DEST_NAME),
                log = { msg, tr -> Log.w(TAG, msg, tr) },
            ).runOnce()
        }
    }
}

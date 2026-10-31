package dev.liuyouwu.familiar.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import dev.liuyouwu.familiar.data.repository.dataStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** DownloadManager 状态快照（status 取平台 DownloadManager.STATUS_* 常量） */
data class UpdateDmState(
    val status: Int,
    val bytesSoFar: Long,
    val totalBytes: Long,
    val localPath: String?,
)

/**
 * `update/` 目录清理策略——只保留两类文件，其余一律删：
 * ① 活动下载的目标文件（状态 ∈ PENDING/RUNNING/PAUSED）；
 * ② pendingVersion 比当前版本新时的目录内文件。
 *
 * ② 看似过宽（连孤儿旧包也保留），实为唯一安全口径：校验通过后 downloadId 即清，
 * 进程死亡后只剩 pendingVersion 标记，无法辨认目录里哪个文件是待装包——宁可保留
 * （磁盘残留有限，版本追上后全清），不能误删待装包断掉「去授权→返回→续装」链路。
 */
object UpdateCleanupPolicy {

    fun shouldDelete(
        fileName: String,
        active: Triple<Long, Int, String?>?,
        pendingVersion: String?,
        currentVersion: String,
    ): Boolean {
        // 保留①：活动下载目标文件（pending/running/paused；successful/failed 不在此列，交②或删）
        if (active != null && active.third == fileName && active.second in ACTIVE_KEEP_STATUSES) {
            return false
        }
        // 保留②：判空先于比较
        if (pendingVersion != null && VersionCompare.compare(currentVersion, pendingVersion) < 0) {
            return false
        }
        return true
    }

    private val ACTIVE_KEEP_STATUSES = intArrayOf(
        DownloadManager.STATUS_PENDING,
        DownloadManager.STATUS_RUNNING,
        DownloadManager.STATUS_PAUSED,
    )
}

/** 系统 DownloadManager 薄壳——enqueue/query/cancel/清理/安装意图，不做业务判断 */
class UpdateDownloader(private val context: Context) {

    // 清持久化键是 DataStore 挂起写：文件删除在调用线程同步完成，键清理后台 fire-and-forget
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun dm(): DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** 入队下载；enqueue 前防御性删除同名残留（断点/损坏旧包不干扰本次下载） */
    fun enqueue(asset: ReleaseAsset): Long {
        File(context.getExternalFilesDir(null), "update/${asset.name}").delete()
        val req = DownloadManager.Request(asset.url.toUri())
            .setMimeType("application/vnd.android.package-archive")
            .setTitle("Familiar 更新下载")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, "update/${asset.name}")
        return dm().enqueue(req)
    }

    /** 按 id 查状态；查无（记录已被删/刚入队未落库）返回 null */
    fun query(id: Long): UpdateDmState? {
        dm().query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            val localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            return UpdateDmState(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                bytesSoFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                totalBytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                localPath = localUri?.let { Uri.parse(it).path },
            )
        }
    }

    fun cancel(id: Long) {
        dm().remove(id)
    }

    fun fileOf(state: UpdateDmState): File? = state.localPath?.let { File(it) }

    /** `update/` 目录逐文件套清理策略；无待装（或待装已过期）时同步清持久化键 */
    fun cleanup(pendingVersion: String?, currentVersion: String, activeId: Long?) {
        val active = activeId?.let { id ->
            query(id)?.let { s -> Triple(id, s.status, s.localPath?.substringAfterLast('/')) }
        }
        val files = File(context.getExternalFilesDir(null), DIR).listFiles().orEmpty()
        for (f in files) {
            if (UpdateCleanupPolicy.shouldDelete(
                    fileName = f.name,
                    active = active,
                    pendingVersion = pendingVersion,
                    currentVersion = currentVersion,
                )
            ) {
                f.delete()
            }
        }
        if (pendingVersion == null || VersionCompare.compare(currentVersion, pendingVersion) >= 0) {
            scope.launch {
                // 清键失败不致命（下次 cleanup 会再试），不让异常崩掉进程
                runCatching {
                    context.dataStore.edit {
                        it.remove(KEY_UPDATE_DOWNLOAD_ID)
                        it.remove(KEY_UPDATE_PENDING_VERSION)
                    }
                }
            }
        }
    }

    /** 应用内安装意图：FileProvider content URI + 授权读 + 新任务栈 */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private companion object {
        const val DIR = "update"
    }
}

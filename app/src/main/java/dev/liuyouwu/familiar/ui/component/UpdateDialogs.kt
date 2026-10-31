package dev.liuyouwu.familiar.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.liuyouwu.familiar.data.update.ChangelogExtractor
import dev.liuyouwu.familiar.data.update.UpdateCheckResult
import dev.liuyouwu.familiar.viewmodel.UpdateUiState

/**
 * 检查更新弹窗（溢出菜单「检查更新」入口；自动检查发现新版时也弹此窗）。
 * 单弹窗承载全部非授权状态：版本信息 + 自动检查开关 + 状态区（检查中/有新版/已是最新/
 * 失败/下载进度/下载失败/校验失败），按钮随状态切换。
 */
@Composable
fun UpdateAboutDialog(
    state: UpdateUiState,
    versionLabel: String,
    autoCheckEnabled: Boolean,
    onCheck: () -> Unit,
    onToggleAutoCheck: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onBrowser: () -> Unit,
    onCopyLink: () -> Unit,
    onViewNotes: () -> Unit,
    onOpenProjectPage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检查更新") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "当前版本 $versionLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )

                // 自动检查开关（下载/待装态隐藏，避免打断进行中操作）
                if (state !is UpdateUiState.Downloading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("每天自动检查更新", modifier = Modifier.weight(1f))
                        Switch(checked = autoCheckEnabled, onCheckedChange = onToggleAutoCheck)
                    }
                }

                when (state) {
                    is UpdateUiState.Checking -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在检查…", modifier = Modifier.padding(top = 2.dp))
                        }
                    }

                    is UpdateUiState.AvailableShow -> AvailableContent(
                        check = state.check,
                        onViewNotes = onViewNotes,
                        onCopyLink = onCopyLink,
                    )

                    is UpdateUiState.UpToDate -> Text("已是最新版本")

                    is UpdateUiState.CheckFailed -> {
                        Text(
                            when (state.reason) {
                                UpdateCheckResult.Failed.Reason.RATE_LIMITED -> "检查失败：GitHub 限流，稍后再试"
                                UpdateCheckResult.Failed.Reason.BAD_RESPONSE -> "检查失败：服务器响应格式异常"
                                UpdateCheckResult.Failed.Reason.NETWORK -> "检查失败：网络不可达或连接超时"
                            },
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (state.canOpenProjectPage) {
                            TextButton(onClick = onOpenProjectPage, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                Text("打开项目主页", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    is UpdateUiState.Downloading -> {
                        Text("正在下载更新…${state.percent}%")
                        LinearProgressIndicator(
                            progress = state.percent / 100f,
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                    }

                    is UpdateUiState.DownloadFailed -> Text(
                        "下载失败，重试或用浏览器下载",
                        color = MaterialTheme.colorScheme.error,
                    )

                    is UpdateUiState.VerifyFailed -> Text(
                        "安装包校验失败，文件已删。重新检查，或去发布页手动下载",
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Text("看看 GitHub 上有没有新版")
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateUiState.AvailableShow -> if (state.check.canInstall) {
                    TextButton(onClick = onInstall) {
                        Text("立即下载安装", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    TextButton(onClick = onBrowser) { Text("浏览器下载") }
                }

                is UpdateUiState.Downloading -> TextButton(onClick = onCancelDownload) { Text("取消下载") }

                is UpdateUiState.DownloadFailed,
                is UpdateUiState.VerifyFailed,
                is UpdateUiState.UpToDate,
                -> TextButton(onClick = onCheck) { Text("重新检查") }

                is UpdateUiState.CheckFailed -> TextButton(onClick = onCheck) { Text("重试") }

                else -> TextButton(onClick = onCheck) { Text("立即检查") }
            }
        },
        dismissButton = {
            when (state) {
                is UpdateUiState.AvailableShow -> Row {
                    TextButton(onClick = onBrowser) { Text("浏览器下载") }
                    TextButton(onClick = onDismiss) { Text("以后再说") }
                }

                else -> TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/** 有新版本正文：新版本号 + changelog（前 12 行，超出提示）+ 无法应用内安装时的说明 + 发布页/复制链接出口 */
@Composable
private fun AvailableContent(
    check: UpdateCheckResult.Available,
    onViewNotes: () -> Unit,
    onCopyLink: () -> Unit,
) {
    Text(
        "发现新版本 ${check.release.tagName}",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    if (check.changelog.isNotEmpty()) {
        check.changelog.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
        }
        // 「有后续被折叠」用 limit+1 探测——恰好整 12 行时不得误标「已折叠」
        val hasMore = ChangelogExtractor.extract(check.release.body, limit = check.changelog.size + 1).size > check.changelog.size
        if (hasMore) {
            Text(
                "（更新说明有省略）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
    if (!check.canInstall) {
        Text(
            "这个版本装不了应用内更新，用浏览器下载",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
    Row {
        TextButton(onClick = onViewNotes, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text("查看发布页", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = onCopyLink, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(
                "复制下载链接",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

/** 「安装未知应用」授权说明弹窗（去授权 → 返回 App 复查 → 就绪即续装） */
@Composable
fun UpdatePermissionDialog(
    onGrant: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("需要安装权限") },
        text = {
            Text("安装前需要「安装未知应用」授权。去系统设置打开它，回到 app 会接着装")
        },
        confirmButton = {
            TextButton(onClick = onGrant) { Text("去授权", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text("以后再说") }
        },
    )
}

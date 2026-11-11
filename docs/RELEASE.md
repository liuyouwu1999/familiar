# Familiar 发版流程（2026-11-11 固化首版，借鉴 NovaX-Player 发版体系）

> 发版主干流程、红线与冒烟清单在此固化；增补条目时在本文件追加并注明出处。
> 配套：`scripts/release.sh`（流程的机械执行部分）；`.github/workflows/ci.yml`（每次 push/PR 均全量跑 build-test 与 release-verify 两 job，含 tag push）。

## 主干流程

1. **版本定档**：`app/build.gradle.kts` 手动 bump `versionCode`（+1）与 `versionName`（四段数值 `x.y.z.w`），commit 后**立即本地打 tag**：`git tag v<版本>`（脚本构建前校验 tag 已存在，防"改版本忘打 tag"；push 在步骤 7）。
2. **构建**：`scripts/release.sh`（内部跑单测 + assembleRelease + badging/签名核验 + 归档）。
3. **产物核验**（脚本自动，缺失时降级为警告并人工核对）：`aapt dump badging` 核对 versionCode/versionName 与定档一致；`apksigner verify --print-certs` 核对**签名证书 SHA-256 = `ae4e8a9292e631ac491d1ec373089ccb56e51bc72808918e9c6e43cd4d59286f`**（基线取自 v1.0.0.0 正式签名——签名不一致用户将无法覆盖安装，应用内更新链断裂）。
4. **归档**：`Familiar-<版本>.apk` + `SHA256SUMS.txt` 存 `_release/`（gitignored），脚本内置 `sha256sum -c` 自检。
5. **发版冒烟**：下方清单逐项打勾（**release 变体**，真机优先；2026-11-10 首轮实证为 debug 变体，release 变体每版发前必须重跑）。
6. **终审（push 之前）**：diff 自查 + 可选 gitleaks。**终审必须在 push 之前——公开历史无后悔药。**
7. **push**：`git push origin main`；`git push origin v<版本>`（tag 在步骤 1 已打）。
8. **盯双 CI 绿**：main 与 tag push 各触发一跑，**双绿才算发版完成；CI 红禁止发 Release**。
9. **发 Release**：`gh release create v<版本> _release/Familiar-<版本>.apk _release/SHA256SUMS.txt --title "Familiar v<版本>" --notes-file <body 文件>`（body 约定见下）。
10. **发布后验证**：模拟器装上一版 Release APK → 应用内检查更新 → 下载 → SHA 校验 → 安装提示，全链实测。

## 更新器兼容约束（发版资产的硬前提，违反即存量用户更新链断裂）

来源：`data/update/` 现行代码（v1.0.0.0 与现行零差异，即存量用户手里的更新器逻辑）。

| 约束 | 代码依据 | 违反后果 |
|---|---|---|
| APK 资产名以 `.apk` 结尾，官方包用 `Familiar` 前缀 | AssetPicker：过滤 .apk 后 Familiar 前缀优先 | 挑错包或挑不到包 |
| **必须上传精确名为 `SHA256SUMS.txt` 的校验和资产**（固定名，不带版本号） | UpdateRepository.SUMS_ASSET_NAME 精确匹配；`canInstall = asset != null && sums != null` | 应用内安装被禁用，存量用户只能手动下载 |
| sums 行内文件名与 APK 资产名**逐字一致** | 更新器按文件名键查 sums | 校验失败，应用内安装被禁 |
| tag 必须为 `v<versionName>`，剥 v 后纯数值点分段 | VersionCompare.parse：非法输入安全侧判"无更新" | 存量用户收不到更新提示 |
| Release body 末尾放 ```changelog 围栏（要点 ≤12 行） | ChangelogExtractor：围栏优先提取进应用内弹窗；无围栏降级纯文本 | 更新弹窗展示降级文本（不阻断但观感差） |
| 必须是正式 Release（非 draft/prerelease） | 检查更新走 `/releases/latest`，只返回正式版 | 存量用户拉不到新版本 |
| versionName 严格大于上一版；versionCode 严格递增 | VersionCompare + Android 覆盖安装规则 | 用户侧无法更新 |

## Release body 约定

- 结构：markdown 说明段（GitHub 网页正常渲染）+ 末尾 ```changelog 围栏（应用内更新弹窗提取，≤12 行）。
- 围栏内每行即弹窗一行：写**用户视角**要点，不写内部任务编号/审计编号。

## 发版红线

1. **严禁 `git push --tags`**——会连带推送历史 tag；只推单个 `git push origin v<版本>`。
2. **终审在 push 之前**。
3. **CI 红禁止发 Release**；main 与 tag 两跑双绿才算发版完成。
4. 拷贝文件进仓库后 `git update-index --chmod=+x gradlew`（Windows 工作树丢执行位，2026-11-10 CI 首跑实证）。
5. 正式签名密钥永不离开本机；CI 的 release-verify 用一次性 keystore（keytool 现生成、有效期 1 天、仅验证构建链，`.gitignore` 已覆盖 `keystore.properties`/`*.keystore`）。

## 发版冒烟清单（release 变体，真机优先；2026-11-11 首版固化，出处=audit-remediation 修复轮各任务验收项+2026-11-10 debug 变体真机/模拟器冒烟；每版发前在 release 变体重跑）

| # | 冒烟项 | 预期表现 |
|---|---|---|
| 1 | 冷启动 → 连接本地 LLM → 发送 → 流式 → 收线落库 | 全链零崩溃；token/速率实时；回复正常渲染 |
| 2 | 历史会话打开（真实迁移数据优先） | 消息完整渲染（Markdown/列表/图片），零崩溃 |
| 3 | 键盘 insets（targetSdk 35） | 键盘弹出输入栏不被遮挡；流式上滑读历史不被拽回，贴底持续跟随 |
| 4 | 桌宠：挂载/拖拽/气泡；退后台按设置 detach | 挂载无"幽灵鱼"；拖动位置记忆；气泡正常 |
| 5 | R6：运行中撤销悬浮窗权限 → 重启 app | 无崩溃、无附加重试风暴，app 可用 |
| 6 | 上一版 Release APK 覆盖安装（Room 迁移） | 首启数据完整、迁移零崩溃、无空库 |
| 7 | 工具闸门：提示词诱导 http_get | 弹审批框；拒绝后 isError 回填、工具未执行、会话不中断 |
| 8 | 诊断转储开关（可选）：关→发消息无文件；开→同进程再发即出文件 | 不重启生效 |
| 9 | 双端 logcat | 全程零 FATAL EXCEPTION |

## 历版教训

- 2026-11-10：Windows 工作树 gradlew 缺执行位致 CI 首跑失败（红线 4 来源）。
- 2026-11-10：GitHub 国际链路间歇阻断——push 失败重试即可，勿 force-push；`~/.gitconfig` 的 github 专属代理失效时用 `git -c http.https://github.com/.proxy= push` 一次性覆盖。

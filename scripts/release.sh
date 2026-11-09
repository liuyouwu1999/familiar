#!/usr/bin/env bash
# 发布脚本：校验 tag 与 versionName 一致 → 单元测试 → assembleRelease → 产物改名 → 生成 SHA256SUMS.txt
#
# 用法：
#   bash scripts/release.sh            # 完整发布（跑测试 + 打包 + 改名 + 校验和）
#   bash scripts/release.sh --dry-run  # 只输出资产命名清单，不构建
#
# 发布前提：tag v<versionName> 已打（应用内检查更新按 tag 与资产名定位）。
# 产物在 app/build/outputs/apk/release/ 下（.gitignore 内，不入库）。
set -euo pipefail

cd "$(dirname "$0")/.." # 脚本假定在仓库根运行，自动切到仓库根

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "ERROR: 请在 familiar 仓库内运行"; exit 1; }

DRY=${1:-} # --dry-run
[ -z "$DRY" ] || [ "$DRY" = "--dry-run" ] || { echo "用法: bash scripts/release.sh [--dry-run]"; exit 2; }

# versionName 从 app/build.gradle.kts 提取（现场格式：versionName = "1.0.0.0"）
VERSION_NAME=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n 1)
[ -n "$VERSION_NAME" ] || { echo "ERROR: 未能从 app/build.gradle.kts 提取 versionName"; exit 1; }
TAG="v$VERSION_NAME"

# 发布前提：tag v<versionName> 必须已存在，防止 versionName 改了忘打 tag
git rev-parse -q --verify "refs/tags/$TAG^{commit}" >/dev/null || { echo "ERROR: tag $TAG 不存在（versionName=$VERSION_NAME），先打 tag 再发布"; exit 1; }

echo "== 资产命名清单 =="
echo "Familiar-$VERSION_NAME.apk / SHA256SUMS.txt"

if [ "$DRY" = "--dry-run" ]; then
  echo "dry-run 结束"
  exit 0
fi

./gradlew :app:testDebugUnitTest :app:assembleRelease

cd app/build/outputs/apk/release
# AGP 默认产物名是 app-release.apk；若现场已配 outputFileName（产物直接叫 Familiar-<版本>.apk）则跳过 mv
if [ -f "app-release.apk" ]; then
  mv app-release.apk "Familiar-$VERSION_NAME.apk"
elif [ -f "Familiar-$VERSION_NAME.apk" ]; then
  echo "产物已是 Familiar-$VERSION_NAME.apk（已配 outputFileName），跳过改名"
else
  echo "ERROR: app/build/outputs/apk/release 下未找到 APK 产物" >&2
  exit 1
fi

sha256sum "Familiar-$VERSION_NAME.apk" > SHA256SUMS.txt
cat SHA256SUMS.txt

echo "== Release body 模板 =="
echo '```changelog'
echo '（本次变更要点）'
echo '```'

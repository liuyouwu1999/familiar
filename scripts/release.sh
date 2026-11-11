#!/usr/bin/env bash
# 发版脚本——docs/RELEASE.md 主干流程步骤 2/3/4 的机械执行（流程与红线见该文档）。
#
# 用法：
#   bash scripts/release.sh            # 完整发版（单测 + 打包 + badging/签名核验 + 改名 + 校验和 + 归档）
#   bash scripts/release.sh --dry-run  # 只输出资产命名清单，不构建
#
# 手动前置：app/build.gradle.kts 已 bump versionCode/versionName 且已 commit；
# tag v<版本> 已打（仅本地 git tag v<版本>，push 在流程步骤 7；应用内检查更新按 tag 与资产名定位）。
# 产出归档到 _release/（gitignored）：Familiar-<版本>.apk + SHA256SUMS.txt
# （固定名=存量 v1.0.0.0+ 更新器精确匹配的硬依赖，见 docs/RELEASE.md 兼容约束表）。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT_DIR"

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "ERROR: 请在 familiar 仓库内运行"; exit 1; }

DRY=${1:-} # --dry-run
[ -z "$DRY" ] || [ "$DRY" = "--dry-run" ] || { echo "用法: bash scripts/release.sh [--dry-run]"; exit 2; }

# versionName/versionCode 从 app/build.gradle.kts 提取（现场格式：versionName = "1.0.0.0" / versionCode = 5）
VERSION_NAME=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n 1)
VERSION_CODE=$(sed -n 's/^[[:space:]]*versionCode = \([0-9][0-9]*\).*/\1/p' app/build.gradle.kts | head -n 1)
[ -n "$VERSION_NAME" ] || { echo "ERROR: 未能从 app/build.gradle.kts 提取 versionName"; exit 1; }
[ -n "$VERSION_CODE" ] || { echo "ERROR: 未能提取 versionCode"; exit 1; }
TAG="v$VERSION_NAME"

# 发布前提：tag v<versionName> 必须已存在（本地），防止 versionName 改了忘打 tag
git rev-parse -q --verify "refs/tags/$TAG^{commit}" >/dev/null || { echo "ERROR: tag $TAG 不存在（versionName=$VERSION_NAME），先 git tag $TAG 再发布"; exit 1; }

echo "== 资产命名清单 =="
echo "Familiar-$VERSION_NAME.apk / SHA256SUMS.txt（固定名，存量更新器硬依赖）"
echo "[红线提醒] 严禁 git push --tags；终审须在 push 之前；main 与 tag 双 CI 绿才发 Release（docs/RELEASE.md）"

if [ "$DRY" = "--dry-run" ]; then
  echo "dry-run 结束"
  exit 0
fi

# SDK 定位三级探测：ANDROID_HOME → adb 反推 → local.properties 的 sdk.dir（Java 转义格式还原）
SDK_DIR="${ANDROID_HOME:-}"
if [ -z "$SDK_DIR" ] || [ ! -d "$SDK_DIR/build-tools" ]; then
  ADB_BIN=$(command -v adb 2>/dev/null || true)
  [ -n "$ADB_BIN" ] && SDK_DIR=$(dirname "$(dirname "$ADB_BIN")")
fi
if [ -z "$SDK_DIR" ] || [ ! -d "$SDK_DIR/build-tools" ]; then
  LP_RAW=$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | head -n 1)
  LP_CAND="${LP_RAW//'\:'/:}"    # Java 转义 \: → :
  LP_CAND="${LP_CAND//'\\'/'/'}" # Java 转义 \\ → /（顺带完成 Windows 分隔符转换）
  LP_CAND=$(echo "$LP_CAND" | sed 's|^\([A-Za-z]\):|/\L\1|') # D: → /d
  [ -d "$LP_CAND/build-tools" ] && SDK_DIR="$LP_CAND"
fi
[ -n "$SDK_DIR" ] && [ -d "$SDK_DIR/build-tools" ] || { echo "ERROR: 未定位到 Android SDK（ANDROID_HOME/adb/local.properties 三级探测均失败）"; exit 1; }
echo "sdk: $SDK_DIR"
AAPT=$(ls "$SDK_DIR"/build-tools/*/aapt.exe "$SDK_DIR"/build-tools/*/aapt 2>/dev/null | sort | tail -n 1 || true)
APKSIGNER=$(ls "$SDK_DIR"/build-tools/*/apksigner.bat "$SDK_DIR"/build-tools/*/apksigner 2>/dev/null | sort | tail -n 1 || true)

# 签名证书基线（docs/RELEASE.md：v1.0.0.0 起 release 签名证书 SHA-256，覆盖安装/应用内更新链的硬前提）
CERT_BASELINE="ae4e8a9292e631ac491d1ec373089ccb56e51bc72808918e9c6e43cd4d59286f"

# 1. 单测 + release 构建
./gradlew :app:testDebugUnitTest :app:assembleRelease

# 2. 改名（AGP 默认产物 app-release.apk；若已配 outputFileName 则产物直接叫 Familiar-<版本>.apk）
OUT=app/build/outputs/apk/release
if [ -f "$OUT/app-release.apk" ]; then
  mv "$OUT/app-release.apk" "$OUT/Familiar-$VERSION_NAME.apk"
elif [ -f "$OUT/Familiar-$VERSION_NAME.apk" ]; then
  echo "产物已是 Familiar-$VERSION_NAME.apk（已配 outputFileName），跳过改名"
else
  echo "ERROR: $OUT 下未找到 APK 产物" >&2
  exit 1
fi
APK="$ROOT_DIR/$OUT/Familiar-$VERSION_NAME.apk"

# 3a. badging 核验：产物元数据与定档逐字一致
if [ -n "$AAPT" ]; then
  BADGING=$("$AAPT" dump badging "$APK" 2>/dev/null | grep '^package:' | head -n 1)
  echo "badging: $BADGING"
  echo "$BADGING" | grep -q "versionCode='$VERSION_CODE'" || { echo "ERROR: badging versionCode 与定档（$VERSION_CODE）不符"; exit 1; }
  echo "$BADGING" | grep -q "versionName='$VERSION_NAME'" || { echo "ERROR: badging versionName 与定档（$VERSION_NAME）不符"; exit 1; }
else
  echo "WARN: 未找到 aapt——跳过 badging 自动核验，请人工核对"
fi

# 3b. 签名核验：证书 SHA-256 必须等于基线（签名不一致 = 用户无法覆盖安装）
if [ -n "$APKSIGNER" ]; then
  CERT=$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | grep 'certificate SHA-256 digest' | awk '{print $NF}' | head -n 1)
  echo "cert-sha256: $CERT"
  [ "$CERT" = "$CERT_BASELINE" ] || { echo "ERROR: 签名证书与基线不符（基线 $CERT_BASELINE）——禁止发布"; exit 1; }
else
  echo "WARN: 未找到 apksigner——跳过签名自动核验，请人工核对"
fi

# 4. 校验和 + 归档（_release/ 在仓库根，gitignored）+ 自检
( cd "$OUT" && sha256sum "Familiar-$VERSION_NAME.apk" > SHA256SUMS.txt )
cat "$OUT/SHA256SUMS.txt"
mkdir -p "$ROOT_DIR/_release"
cp "$APK" "$OUT/SHA256SUMS.txt" "$ROOT_DIR/_release/"
( cd "$ROOT_DIR/_release" && sha256sum -c "SHA256SUMS.txt" )

echo "== Release body 模板（围栏内为应用内更新弹窗行，≤12 行，用户视角）=="
echo '```changelog'
echo '（本次变更要点）'
echo '```'
echo "== 发布步骤（docs/RELEASE.md 步骤 7-9）：push main + tag 盯双 CI 绿 → gh release create $TAG =="

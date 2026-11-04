#!/usr/bin/env bash
# 發佈新版到 GitHub Release（App 內的「雲端更新」從這裡下載）。
#
# 用法（Git Bash，在 repo 任何位置皆可）：
#   1. 先調高 app/build.gradle.kts 的 versionCode 與 versionName
#   2. bash TOOLS/release.sh "這一版的更新內容"
#
# 腳本會：建置 debug APK → 確認是用共用金鑰簽章（否則手機無法覆蓋更新）→
# 產生 .sha256 → 推送目前分支 → 建立 Release v<versionName> 並上傳 APK 與 .sha256。
# 建置需要 local.properties 的 rewardadguard.signing.* 設定（見 app/build.gradle.kts）。
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="wahaha232/reward-ad-guard"
# 共用簽章金鑰的憑證 SHA-256（D:\Claude\keystore\KEYSTORE_INFO.txt）
EXPECTED_CERT="520f639b3a03c5d2b457cedd9f34dad6b1ed713c7c9dc564095649b5ec439dc8"

VERSION=$(grep -E '^\s*versionName = "' app/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')
TAG="v$VERSION"
NOTES="${1:-$TAG}"
APK="dist/RewardAdGuard-$TAG.apk"

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "Release $TAG 已存在，請先調高 app/build.gradle.kts 的 versionName" >&2
  exit 1
fi
if [ -n "$(git status --porcelain -- app TOOLS)" ]; then
  echo "app/ 或 TOOLS/ 有未提交的變更，請先提交" >&2
  exit 1
fi

# 建置（路徑含空格，gradlew.bat 交給 PowerShell 執行）
JAVA_ARG=""
[ -n "${JAVA_HOME:-}" ] && JAVA_ARG="-Dorg.gradle.java.home=$(cygpath -w "$JAVA_HOME")"
powershell -NoProfile -Command "& '.\\gradlew.bat' $JAVA_ARG assembleDebug --console=plain; exit \$LASTEXITCODE"

BUILT="app/build/outputs/apk/debug/reward-ad-guard-debug.apk"
SDK_DIR=$(grep -E '^sdk.dir=' local.properties | sed -E 's/^sdk.dir=//; s/\\\\/\//g; s/\\:/:/')
APKSIGNER=$(ls -d "$SDK_DIR"/build-tools/*/ | sort -V | tail -1)apksigner.bat
CERT=$(cmd //c "$(cygpath -w "$APKSIGNER")" verify --print-certs "$(cygpath -w "$BUILT")" \
  | grep -m1 "certificate SHA-256 digest" | awk '{print $NF}')
if [ "$CERT" != "$EXPECTED_CERT" ]; then
  echo "APK 不是用共用金鑰簽章（$CERT），手機無法覆蓋更新，停止發版" >&2
  exit 1
fi

mkdir -p dist
cp "$BUILT" "$APK"
(cd dist && sha256sum "$(basename "$APK")" > "$(basename "$APK").sha256")

git push origin HEAD
gh release create "$TAG" "$APK" "$APK.sha256" --repo "$REPO" \
  --target "$(git rev-parse HEAD)" --title "$TAG" --notes "$NOTES"
echo "已發佈 $TAG"

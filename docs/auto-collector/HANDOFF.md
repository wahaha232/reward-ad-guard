# 接手說明（另一台裝置）

## 一句話狀態

階段 1 已提交並推送，正在做階段 2 的 `CollectorEngine`；手機在 2026-11-12 離線，實機驗證待補。

## 環境

- 守衛 repo：`D:\Visual Studio Code\PROJECT\Reward Ad Guard`（`feature/auto-collector`）
- pc-collector 唯讀參考：`D:\Claude\pc-collector-clean`（`origin/main`，v0.2.5）
- JDK 17：`C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot`
- 每次開工：
  ```powershell
  git -C "D:\Visual Studio Code\PROJECT\Reward Ad Guard" fetch origin
  git -C "D:\Visual Studio Code\PROJECT\Reward Ad Guard" checkout feature/auto-collector
  git -C "D:\Visual Studio Code\PROJECT\Reward Ad Guard" pull --ff-only
  $env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot'
  Set-Location 'D:\Visual Studio Code\PROJECT\Reward Ad Guard'
  .\gradlew.bat "-Dorg.gradle.java.home=$env:JAVA_HOME" assembleDebug testDebugUnitTest --console=plain
  ```

## 常用指令

```powershell
# 除錯入口（只讀）
adb shell am start -n com.rewardadguard.app/com.rewardadguard.app.debug.DebugTestActivity -f 0x10008000 --es cmd collector_snapshot
adb shell am start -n com.rewardadguard.app/com.rewardadguard.app.debug.DebugTestActivity -f 0x10008000 --es cmd collector_corners

# 看輸出
adb logcat -d -s RewardAdGuardDebug

# 安裝前一定要驗憑證（共用金鑰 SHA-256 520f639b…439dc8）
apksigner verify --print-certs "app\build\outputs\apk\debug\reward-ad-guard-debug.apk"
```

## 注意

- 不要 `uiautomator dump`（會踢掉無障礙服務）。
- 不要修改 `gradle.properties`、`local.properties` 後提交。
- 不要 push `main`、不要發 Release、不要改版本號、不要 force push。
- 測試紀錄、截圖、資料庫只能進私人 repo `reward-collector-testlogs`。
- 目前已知：ML Kit 讓 APK 約 54 MB；GitHub 對 >50 MB 有警告。
- 進度與下一步以 `PROGRESS.md` 為準。

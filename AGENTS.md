# 給 AI 開發助手（Kilo 等）的專案規則

獎勵廣告守衛（Reward Ad Guard）Android App，套件 `com.rewardadguard.app`，只給擁有者自己用。
回覆與文件一律用**繁體中文**。

## 1. 簽章金鑰（最重要）

手機上的 App 只能用**同一把金鑰**簽章的 APK 覆蓋更新。用錯金鑰只能先移除舊版，這樣會清掉資料、無障礙授權和省電白名單。

- 共用金鑰：`D:\Claude\keystore\android-debug-gpstracker-gpslocator.keystore`（Android 標準 debug 金鑰）
- 憑證 SHA-256：`520f639b3a03c5d2b457cedd9f34dad6b1ed713c7c9dc564095649b5ec439dc8`
- 設定寫在 `local.properties`（不進 git）的 `rewardadguard.signing.*`。
- **新電腦、新 clone 或 worktree 開工前先執行一次：**

  ```powershell
  powershell -ExecutionPolicy Bypass -File TOOLS\setup_signing.ps1
  ```

  腳本會驗證金鑰指紋，再寫入 `local.properties`（缺 `sdk.dir` 也會補上）。
  結束碼：0 成功；2 找不到金鑰檔；3 找不到 keytool；4 指紋不符。
- 沒設定時 Gradle 只會印警告，然後改用該電腦自己的 debug 金鑰，**建出來的 APK 裝不上手機**。看到 `rewardadguard.signing.storeFile is not set` 就先跑上面的腳本。
- **禁止**把金鑰檔、`local.properties` 或任何密碼提交到 repo（這個 repo 是公開的）。

## 2. 建置

路徑含空格，要用 PowerShell 執行 gradlew，並指定 JDK 17：

```powershell
& .\gradlew.bat "-Dorg.gradle.java.home=C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot" testDebugUnitTest assembleDebug --console=plain
```

APK 輸出在 `app/build/outputs/apk/debug/reward-ad-guard-debug.apk`。

## 3. 版號規則

- 每次要裝到手機的測試版：`app/build.gradle.kts` 的 versionName 加一個 patch（例如 1.2.27 → 1.2.28），versionCode +1。**先提交再建置**（APK 內含 commit hash）。
- 不可加 `-beta` 這類後綴（UpdateManager.isNewer 會忽略後綴，會擋掉正式版的雲端更新）。

## 4. 正式發行（雲端更新）

只有擁有者同意後才發行：

1. 把開發分支快轉合併到 `main`，並推送。
2. 在 `main` 上執行 `bash TOOLS/release.sh "更新內容"`（Git Bash；要先設好 `JAVA_HOME`）。
   腳本會檢查 APK 是不是共用金鑰簽的，不是就停止；檢查通過才建立 GitHub Release `v<versionName>`。
3. App 內「雲端更新」從 Releases 下載。HyperOS 更新後**不會自動重啟無障礙服務**，要打開 App 一次。

## 5. 手機實測

- 安裝：`adb install -r app\build\outputs\apk\debug\reward-ad-guard-debug.apk`
- **不要用 uiautomator**（會把守衛的無障礙服務踢掉）。看畫面用 `adb exec-out screencap -p`。
- 安裝前先確認沒有收取在跑：`files/collector_runs/` 最新那一輪沒有 `summary.json`，就代表還在進行中。
- debug 指令：`adb shell am start -n com.rewardadguard.app/com.rewardadguard.app.debug.DebugTestActivity -f 0x10008000 --es cmd <指令>`
  （`collector_run --es apps bitwalk --es dryrun true`、`collector_stop`、`collector_resume`、`dump_state`…，詳見 DebugTestActivity.kt）。**不要加 `-S`**。
- 收取紀錄：`adb exec-out run-as com.rewardadguard.app tar -cf - files/collector_runs > runs.tar`
  （中文用 tar 抓才不會亂碼）。
- 改完要在手機實測通過，才推送或發行。

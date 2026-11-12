# 自動收取開發進度（唯一真相）

最後更新：2026-11-12（MW-EDDIE）
分支：`feature/auto-collector`（基底 `origin/fix/close-assist-1102`，已推送）
基準測試：**244 個、0 失敗**（階段 0 基準 224＋階段 1 新增 20）

## 階段狀態

| 階段 | 狀態 | 說明 |
|---|---|---|
| 0 準備 | 完成 | repo 乾淨、分支建立、基準測試 224／0 失敗（11-12 再加 20 個階段 1 測試） |
| 1 基礎建設 | 部分完成 | 程式與測試已提交（`ccaacbb`～`a9a6dad`）；11-12 補強審查發現並補文件；實機驗證只完成桌面 smoke test，BitWalk 待測 |
| 2 收取引擎 | 進行中 | |
| 3 BitWalk | 未開始 | |
| 4 其他 App | 未開始 | |
| 5 介面與收尾 | 未開始 | |

## 目前在做什麼

階段 2：移植 pc-collector `apps/base.py` 的收取狀態機到 `collector/CollectorEngine`，先做乾跑與 fixtures 回歸測試。

## 下一步

1. 完成 `CollectorEngine`（開 App、彈窗、頁籤、找獎勵、廣告處理、獎勵確認、停止條件）與 `collector_run` 除錯指令。
2. 用 collector-fixtures 對 `_pick_reward`、廣告判定、關閉鈕／下一步、獎勵對話框寫 JVM 測試。
3. 階段 3：BitWalk profile 與實機（或待實機）驗證。
4. 階段 4、5 依序補上。

## 待實機測試清單

- 階段 1：BitWalk 首頁 `collector_snapshot`（應含「可兌換 N 個印章」「收看廣告影片」）、`collector_corners`。11-12 只測到桌面（OCR 655 ms、node 69／OCR 37），裝置隨後離線。
- 階段 3：BitWalk dryRun、真實收取 ≥30 個、install card「←」、獎勵對話框、導流攔截。
- 其他 App 依階段 4 順序。

## 已知問題

- 2026-11-12 手機無線偵錯 `192.168.137.219:5555` 中途變成 `offline`，未修改手機設定；實機測試順延。
- APK 因 ML Kit 內建中文模型＋四 ABI native lib，由 9.96 MiB 增至 54.14 MB（GitHub 對 >50 MB 發警告，未阻擋推送）。
- pc-collector 的 `corner_regions` 只搜左上／右上兩個角帶；移植照原範圍，未加下方角帶（避免誤點）。
- 階段 1 審查發現（11-12 已排入修正）：Actor 的 back/home/launch 未檢查 RUNNING、非同步返回未重查互斥、CollectorEnvironment 狀態切換非原子、截圖複製在主執行緒、fixture 測試斷言恆真。

## 最近提交

- `9d7b966` Note in the master prompt that phase 1 is mostly committed already
- `ad6df78` Add the auto-collector plan and the master prompt for unattended development
- `a9a6dad` Ship the debug APK built from 48998f9
- `48998f9` Let the collector own the screen while it runs
- `7031b37` Keep the screen awake and pause for the lock screen
- `b1ed793` Give the collector one pause-aware actor
- `6991725` Find corner ad buttons without OpenCV
- `05b2644` Read the screen from nodes, with ML Kit OCR as fallback
- `ccaacbb` Read pc-collector's recorded screens as snapshots

## 環境

- 守衛 repo：`D:\Visual Studio Code\PROJECT\Reward Ad Guard`
- pc-collector 乾淨參考副本：`D:\Claude\pc-collector-clean`（v0.2.5，只讀）
- JDK：`C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot`
- 建置：`.\gradlew.bat "-Dorg.gradle.java.home=$env:JAVA_HOME" assembleDebug testDebugUnitTest --console=plain`

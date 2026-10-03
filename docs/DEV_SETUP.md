# 阿皮小夥伴：開發環境與交接

這份文件說明如何在另一台電腦接手、建置及更新裝置。一般操作請看 [README](../README.md)，版本差異請看 [版本紀錄](../CHANGELOG.md)。[PLAN.md](PLAN.md) 保留早期設計與研究，部分內容已不是目前的做法。

這台電腦的專案位於 `C:\Projects\PBGW`，Google Maps 金鑰也放在這裡的 `secrets.properties`。換電腦時可以選自己的目錄，不必照搬這個路徑。

## 目前程式碼變動（1.2.6）

| 功能 | 對應程式碼 | 目前做法 |
|---|---|---|
| 名稱與圖示 | `settings.gradle`、`values/strings.xml`、兩份 `colors.xml`、`data/WaypointStore.kt` | 顯示名稱、設定引導與 GPX 建立程式名稱改為「阿皮小夥伴」；啟動圖示底色固定橙色 `#F57C00`、花瓣白色。套件名稱仍為 `app.pikminbloom.gps`，專案路徑仍是 `C:\Projects\PBGW`。 |
| 浮動視窗關閉 | `ui/OverlayService.kt`、`ui/MainActivity.kt`、`layout/overlay_bar.xml`、`drawable/ic_close.xml` | 展開後右上角「×」關閉控制列與搖桿；取消搖桿接手，保留巡邏與暫停狀態。本次巡邏抑制自動顯示，手動開啟或新巡邏可恢復，不改自動顯示設定。 |
| 搖桿速度 | `data/JoystickSpeeds.kt`、`ui/JoystickSpeedDialog.kt`、`PatrolService`、`WalkSimulator`、`OverlayService` | 每次開啟都選本次速度；取消不開啟，手動速度與自動巡邏分開。步行、快走、慢跑計步，交通工具不計步。 |
| Google Maps | `data/Prefs.kt`、`ui/MainActivity.kt` | 未儲存地圖選擇時，有金鑰就用 Google Maps；保留手動選擇。啟動切換地圖時沿用已設定的中心，避開尚未完成版面的 MapView。 |
| 真實 GPS 步數服務 | `service/RealGpsStepsService.kt`、`AndroidManifest.xml` | 獨立定位前景服務，使用實機 GPS、排除模擬定位，與模擬巡邏互斥；支援暫停、繼續、停止與達標結束。 |
| 定時步數與抖動 | `steps/TimedStepCounter.kt` | 保存小數累計，總步數不超過目標；每 3～8 秒更新抖動，沿用速度抖動設定，執行中改設定也會套用。 |
| GPS 移動分析 | `geo/GpsMotionAnalyzer.kt` | 使用精度、可靠 GPS 速度與座標視窗判斷移動／停止。移動時抖動偏多、停止時偏少；不可靠時回到一般抖動。 |
| Health Connect | `steps/StepInjector.kt`、兩個步數服務、原有 `StepWriteOutbox` | 新模式寫入手動輸入步數，不新增距離；兩種模式共用待寫檔、每日上限與原本的重試識別碼。 |
| 操作與除錯 | `ui/MainActivity.kt`、`ui/RealGpsStepsDialog.kt`、`menu/main.xml`、`strings_real_steps.xml`、debug receiver | 新增模式入口、目標與速率輸入、移動判斷及目前速率；debug 版提供暫停啟動與狀態查詢。 |
| 測試 | `JoystickSpeedTest`、`TimedStepCounterTest`、`TimedStepOutboxTest`、`GpsMotionAnalyzerTest` | 本次變動包含速度選擇、抖動方向、漂移、訊號逾時、暫停、目標與重試測試。 |

## 1. 下載專案

~~~powershell
git clone https://github.com/sotaru/PBGW.git
Set-Location PBGW
~~~

原始碼、測試與說明檔都在 Git 裡。下列資料留在本機，不要上傳：

- `local.properties`：Android SDK 的位置。
- `secrets.properties`：選用的 Google Maps API 金鑰；沒有金鑰也能建置，使用 OpenStreetMap。
- `../keystore/`：選用的 release 簽章與 `keystore.properties`，放在專案旁邊，不是專案裡。
- `app/build/`、`build/`、`docs/test/`：APK、建置結果與可能含私人資訊的測試截圖。

換電腦時，若要直接更新先前安裝的 debug APK，也要安全保留原本的 debug 簽章，通常位於 `%USERPROFILE%/.android/debug.keystore`。不要把簽章檔或密碼放進 Git。

## 2. 安裝工具

建議使用 Android Studio 隨附的 Java 環境，再透過 SDK Manager 安裝：

- Android SDK platform 37。
- Build Tools 36.0.0。
- platform-tools，內含 ADB。

專案使用 Gradle 9.3.1、Android Gradle Plugin 9.1.1、Kotlin 2.2.10。Gradle 由專案內的 wrapper 啟動，不需另外全域安裝；版本以專案設定檔為準。

Android Studio 可以協助設定 SDK 位置。若要手動建立根目錄的 `local.properties`，請換成自己的路徑：

~~~properties
sdk.dir=C:/Users/<使用者>/AppData/Local/Android/Sdk
~~~

## 3. 建置與測試

Windows PowerShell（Android Studio 安裝在預設位置時）：

~~~powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat assembleDebug testDebugUnitTest --console=plain
~~~

Linux／macOS（已設定 Java 與 Android SDK）：

~~~bash
./gradlew assembleDebug testDebugUnitTest --console=plain
~~~

第一次建置需要網路下載套件。若缺少 SDK 元件，先到 SDK Manager 安裝，再重新執行。

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
- Release APK：執行 `assembleRelease`，輸出到 `app/build/outputs/apk/release/app-release.apk`。
- 版本在 `app/build.gradle` 的 `versionCode`／`versionName`；目前是 8／1.2.6。
- 本次有 172 項單元測試；其中定時步數 12 項、待寫佇列整合 2 項、GPS 移動分析 9 項、搖桿速度 5 項。
- 目前結果為 171 項通過、1 項略過、0 項失敗。既有花朵辨識測試需要未納入 Git 的 `live_user5.png`，缺少圖片時會略過；這不代表步數、GPS 或搖桿測試失敗。

### 簽章要注意什麼？

Debug 和 release 使用相同套件名稱：`app.pikminbloom.gps`。要保留資料直接更新，必須使用相同簽章。

Release 建置會讀取 `../keystore/keystore.properties`。沒有這個檔案仍能建置，但會改用 debug 簽章，不能拿來更新原本用其他 release 簽章安裝的 App。

這台電腦未設定獨立 release 簽章，因此本次沿用原有 debug 簽章產生 release APK。Release 的 App 不可偵錯，也不含 debug receiver；這兩件事與簽章種類不同。安裝前先比對 APK 與手機原有 App 的簽章指紋，確認可以直接更新。

若安裝出現簽章不符，先找回原簽章，不要直接解除安裝。解除安裝會移除設定、路線及本機待寫步數。

Google Maps 的金鑰與簽章 SHA-1 設定，請看 [Google Maps 設定說明](GOOGLE_MAPS.md)。

## 4. 更新手機或平板

開啟 USB 偵錯，並在裝置上允許這台電腦連線。更新前先暫停巡邏，讓恢復紀錄有時間保存；安裝可能中斷正在執行的服務。

~~~powershell
$adbPath = '<Android SDK 路徑>/platform-tools/adb.exe'
& $adbPath devices -l
& $adbPath -s '<裝置序號>' install -r app/build/outputs/apk/debug/app-debug.apk
& $adbPath -s '<裝置序號>' shell am start --activity-clear-top -n app.pikminbloom.gps/.ui.MainActivity
~~~

同時接著手機、平板或模擬器時，一定要指定 `-s`，避免裝錯台。`install -r` 在簽章相符時保留資料，但不會自動完成 Health Connect 等特殊授權。

上方指令安裝的是 debug 版。正式版用以下指令建置，再以相同簽章直接更新：

~~~powershell
.\gradlew.bat assembleRelease --console=plain
& $adbPath -s '<實機序號>' install -r app/build/outputs/apk/release/app-release.apk
& $adbPath -s '<實機序號>' shell am start --activity-clear-top -n app.pikminbloom.gps/.ui.MainActivity
& $adbPath -s '<實機序號>' shell dumpsys package app.pikminbloom.gps | Select-String 'versionCode=|versionName=|pkgFlags='
~~~

本版應顯示 `versionName=1.2.6`、`versionCode=8`，`pkgFlags` 不含 `DEBUGGABLE`。再確認手機上的「阿皮小夥伴」主畫面、Google Maps 底圖與右上角選單能正常開啟。安裝成功與功能測試分開記錄；正式版沒有 debug receiver，不能用 debug 廣播驗證控制行為。

2026-10-03 已用 `install -r` 將 release 1.2.6 更新到 NX721J，確認版本 1.2.6／8、不可偵錯且沒有 debug receiver。主畫面與系統應用程式資訊都顯示「阿皮小夥伴」，圖示底色為橙色；Google Maps、原有設定、定位及 Health Connect 步數授權保留。更新驗證期間未新增健康步數。

浮動視窗「×」已在模擬器驗證關閉控制列與搖桿、保留巡邏及暫停狀態，繼續後不會自動重開；手動開啟與新巡邏可恢復顯示。實機也已確認關閉圖示可收掉控制列。這些控制測試期間未寫入健康步數。

更新後再檢查模擬位置 App、定位、Health Connect 及浮動控制列權限。部分廠牌會重設權限，請依實際狀況處理，不要每次都盲目改權限。

待寫步數放在 App 的 `noBackupFilesDir/step_outbox.json`，正常更新會保留，但不會隨系統備份移到另一台裝置。

## 5. 安全地做測試

先跑單元測試，再用模擬器驗證。避免在使用者的真實健康資料裡新增測試步數。

Debug 版本才有 `DebugCommandReceiver`。以下指令只讀取狀態，不會新增步數：

~~~powershell
& $adbPath -s '<裝置序號>' shell am broadcast -n app.pikminbloom.gps/.debug.DebugCommandReceiver -a app.pikminbloom.gps.DEBUG_CMD --es cmd status
& $adbPath -s '<裝置序號>' logcat -d -s PikminGPS
~~~

其他命令請看 `app/src/debug/java/app/pikminbloom/gps/debug/DebugCommandReceiver.kt`：

- `write_steps` 會真的寫入 Health Connect，不是唯讀檢查。
- `delete_steps_today` 會刪除本 App 今天寫入的健康紀錄。
- `start` 等啟動巡邏的命令，應在主畫面可見時執行，避免被系統的背景限制擋下。

這些廣播命令只存在於 debug 版。Release 版沒有 `DebugCommandReceiver`，安裝後改以介面與套件資訊檢查，避免建立測試健康紀錄。

### 真實 GPS 步數驗證

先確認沒有模式正在執行，且 `no_backup/step_outbox.json` 的 `batches` 為空。這個模式即使從暫停開始，也會重試既有待寫資料；舊巡邏的資料若含距離紀錄，仍需相應的距離寫入權限。

在 App 主畫面可見時，以 debug 版執行：

~~~powershell
& $adbPath -s '<實機序號>' shell am broadcast -n app.pikminbloom.gps/.debug.DebugCommandReceiver -a app.pikminbloom.gps.DEBUG_CMD --es cmd start_real_steps --el target 1 --ei rate 100 --ez start_paused true
& $adbPath -s '<實機序號>' shell am broadcast -n app.pikminbloom.gps/.debug.DebugCommandReceiver -a app.pikminbloom.gps.DEBUG_CMD --es cmd real_steps_status
& $adbPath -s '<實機序號>' shell am broadcast -n app.pikminbloom.gps/.debug.DebugCommandReceiver -a app.pikminbloom.gps.DEBUG_CMD --es cmd stop_real_steps
~~~

`REAL_STEPS` 顯示 `jitterPct`、`currentRate`、`motion` 與累計／寫入／待寫步數，不輸出座標。部分裝置會限制 Logcat，可直接檢查畫面的暫停、真實 GPS 與步數狀態。驗證時保持暫停，不執行 `resume_real_steps`；結束後確認服務已停止、步數均為 0。

`motion` 為 `MOVING`、`STATIONARY` 或 `UNKNOWN`。座標視窗為 10～20 秒，新狀態需確認至少 3 秒；定位誤差超過 25 公尺或 15 秒未更新時回到 `UNKNOWN`。室內訊號不足顯示 `UNKNOWN` 是正常結果，不代表已通過戶外移動測試。偏向抖動與漂移情境由單元測試驗證，實機長時間測試另行記錄。

若只想在模擬器測試巡邏、不寫入步數，可以先關閉步數寫入：

~~~powershell
& $adbPath -s '<模擬器序號>' shell am broadcast -n app.pikminbloom.gps/.debug.DebugCommandReceiver -a app.pikminbloom.gps.DEBUG_CMD --es cmd configure_movement --ez inject_steps false
~~~

這會修改設定，測試後請恢復原設定。失敗重試的測試優先使用假的寫入介面；模擬器測權限不足時，也要先確認寫入權限確實已關閉。

操作紀錄、恢復資料、待寫清單與截圖可能包含座標或健康資料。分享前先移除私人資訊，不要提交到公開 GitHub。

## 6. 提交到 GitHub

提交前先看差異，確認只有這次要上傳的原始碼、測試與說明檔：

~~~powershell
git status --short
git diff --check
git diff
~~~

使用自己的 `origin`（`sotaru/PBGW`），不要誤推到 `upstream`。如果遠端有新修改，先整合再推送，不要直接強制覆蓋。

金鑰、簽章、APK 及私人測試資料不納入原始碼提交。若要另外發佈 APK 或建立 GitHub Release，應再確認要發佈的版本與範圍。

# 阿皮小夥伴：開發環境與交接

這份文件說明如何在另一台電腦接手、建置及更新裝置。一般操作請看 [README](../README.md)，版本差異請看 [版本紀錄](../CHANGELOG.md)。[PLAN.md](PLAN.md) 保留早期設計與研究，部分內容已不是目前的做法。

這台電腦的專案位於 `C:\Projects\PBGW`，Google Maps 金鑰也放在這裡的 `secrets.properties`。換電腦時可以選自己的目錄，不必照搬這個路徑。

## 目前版本：1.3.0／11

介面與互動重整、座標容錯解析、設定搜尋及驗證，詳見 [UI 與 code review](UI_REVIEW.md)。完整操作已移至 [使用指南](USER_GUIDE.md)。

| 功能 | 對應程式碼 | 目前做法 |
|---|---|---|
| 主畫面與地圖 | `MainActivity.kt`、`activity_main.xml`、`PanelScrollView.kt` | 路線／跳轉／工具直接入口，狀態摘要及可收合、捲動面板。地圖可見區域隨面板高度調整；平板面板最高寬 600dp。 |
| 共用視覺與操作 | `themes.xml`、兩份 `colors.xml`、`ActionSheet.kt`、`UiForms.kt` | 日夜主題、說明式底部面板及標籤表單；固定品牌配色取代桌布動態色。 |
| 座標貼上 | `CoordinatePasteParser.kt`、`CoordinatePasteDialog.kt`、`CollectionRouteTest.kt` | 正規化全形字元，逐行找一組十進位座標，略過無效行並保留行號。每頁只顯示目前步驟，返回保留輸入；最後套用才存檔。 |
| 設定 | `SettingsActivity.kt`、`SettingInput.kt`、`SettingInputTest.kt` | 本機搜尋、單位及有效範圍、保留錯誤輸入；負海拔可輸入。 |
| 大花與浮動控制 | `WaypointDialogs.kt`、`item_waypoint.xml`、`overlay_bar.xml` | 大花資料與按鈕分列、48dp 操作範圍；浮動按鈕分兩排，保留拖曳、關閉及搖桿功能。 |
| Review 修正 | `Prefs.kt`、`MockLocationController.kt`、`MainActivity.kt`、`WaypointDialogs.kt` | 排除非有限數值，明確處理 FLP 權限例外；修正家的輸入及附近搜尋位置。 |

1.2.x 的採花規劃、步數佇列、GPS 分析與搖桿核心沿用原實作；歷史差異見 [版本紀錄](../CHANGELOG.md)。

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
- 版本在 `app/build.gradle` 的 `versionCode`／`versionName`；目前是 11／1.3.0。
- 本版有 193 項單元測試：192 項通過、1 項略過、0 項失敗。缺少本機 `live_user5.png` 的既有辨識圖片測試會略過。
- Debug／release 建置及 release 必要的 `lintVitalRelease` 已通過。
- 一般 `lintDebug` 在這組 AGP／Kotlin／AndroidX 工具上會因 Kotlin UAST 檢查器崩潰。要完成其餘檢查，可明確選用備用流程：

```powershell
.\gradlew.bat lintDebug --init-script scripts/lint-kotlin-workaround.init.gradle --console=plain
```

這個 init script 只在該次執行停用 `UnsafeOptInUsageError`、`UnsafeOptInUsageWarning` 與 `RepeatOnLifecycleWrongUsage`；不改 App 設定、不建立忽略錯誤的 baseline。其餘檢查為 0 錯誤、51 警告，並不代表全部 Lint 檢查已通過。停用項目、人工 review 與驗證範圍見 [UI review](UI_REVIEW.md)。

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

本版應顯示 `versionName=1.3.0`、`versionCode=11`，`pkgFlags` 不含 `DEBUGGABLE`。再確認手機上的「阿皮小夥伴」主畫面、Google Maps 底圖與右上角選單能正常開啟。安裝成功與功能測試分開記錄；正式版沒有 debug receiver，不能用 debug 廣播驗證控制行為。

2026-10-03 已更新 TB373FU 的 release 1.2.7，確認版號 9、不可偵錯，裝置上的 APK 雜湊與本機 release 相符。更新前暫停蚊香巡邏，更新後從 checkpoint 接回原位置、距離與步數，再恢復巡邏。原有自訂家與設定保留。

2026-10-03 已用 `install -r` 將 NX721J 從 release 1.2.6 更新到 1.2.8。更新前程式閒置，沒有執行中的服務；簽章比對相同後才安裝。已確認版本 10、不可偵錯且無 debug receiver，裝置 APK 與本機正式版的 SHA-256 相符。更新前後主畫面的家、大花與閒置狀態資料一致，定位與步數讀寫權限仍已授權。Google Maps 及「貼上座標規劃採花路線」畫面可正常開啟；取消後資料不變，沒有啟動巡邏或寫入測試健康步數。模擬器的完整匯入與巡邏驗證見下節。

2026-10-03 已用 `install -r` 將 release 1.2.6 更新到 NX721J，確認版本 1.2.6／8、不可偵錯且沒有 debug receiver。主畫面與系統應用程式資訊都顯示「阿皮小夥伴」，圖示底色為橙色；Google Maps、原有設定、定位及 Health Connect 步數授權保留。更新驗證期間未新增健康步數。

浮動視窗「×」已在模擬器驗證關閉控制列與搖桿、保留巡邏及暫停狀態，繼續後不會自動重開；手動開啟與新巡邏可恢復顯示。實機也已確認關閉圖示可收掉控制列。這些控制測試期間未寫入健康步數。

更新後再檢查模擬位置 App、定位、Health Connect 及浮動控制列權限。部分廠牌會重設權限，請依實際狀況處理，不要每次都盲目改權限。

待寫步數放在 App 的 `noBackupFilesDir/step_outbox.json`，正常更新會保留，但不會隨系統備份移到另一台裝置。

## 5. 安全地做測試

1.2.8 的 `CollectionRouteTest` 以固定亂數產生六點資料，將精確規劃結果與獨立窮舉比較；300 點測試確認每點恰好一次、結果不劣於原順序、重算結果一致。另測無效行號、不同分隔符號、重複合併、數量上限，以及採花設定保留原速度與步數上限。

模擬器 UI 已確認錯誤輸入不套用、第二筆可改選成家、合併重複點、預覽前不保存、新路線保留舊路線，以及強制關閉重開後仍保留家與採花模式。關閉步數寫入，以一般設定 `LOOP`／不自動回家啟動採花路線，確認兩朵大花各抵達一次、完成一圈後步行回家並 `PARKED`，今日寫入步數保持 0。此結果不代表遊戲已自動採花；遊戲操作需手動完成。

另確認暫停後強制關閉，從 checkpoint 接回仍保持暫停；繼續後將一般設定改為 `PINGPONG`／8 圈回家，採花路線仍只走一圈後回家停住。回家後的中斷紀錄也能恢復停住狀態。整段驗證未寫入健康步數。

1.2.7 已在模擬器重現「巡邏停止後設定新家，座標已改但房子仍留在舊位置」，並確認修正後標記與座標一致、重開後仍以新家置中。測試期間關閉步數寫入。

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

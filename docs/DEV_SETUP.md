# 開發環境與交接

這份文件說明如何在另一台電腦接手、建置及更新裝置。一般操作請看 [README](../README.md)，版本差異請看 [版本紀錄](../CHANGELOG.md)。[PLAN.md](PLAN.md) 保留早期設計與研究，部分內容已不是目前的做法。

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
- 版本在 `app/build.gradle` 的 `versionCode`／`versionName`；目前是 5／1.2.3。
- 1.2.3 有 144 項單元測試；之後新增測試，數量也會增加。
- 目前結果為 143 項通過、1 項略過。既有花朵辨識測試需要未納入 Git 的 `live_user5.png`，缺少這張本機圖片時會自動略過；不是搖桿測試失敗。

### 簽章要注意什麼？

Debug 和 release 使用相同套件名稱：`app.pikminbloom.gps`。要保留資料直接更新，必須使用相同簽章。

Release 建置會讀取 `../keystore/keystore.properties`。沒有這個檔案仍能建置，但會改用 debug 簽章，不能拿來更新原本用其他 release 簽章安裝的 App。

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

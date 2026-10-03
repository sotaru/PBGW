# 阿皮小夥伴（PBGW）

Android 定位與步數工具。可走訪大花路線、從目前位置向外繞圈、跳到指定座標，或保留真實 GPS，依本次目標逐步新增 Health Connect 步數。不需要 root 或 Shizuku；遊戲操作仍需自己完成。

目前版本：**1.3.1／12**。[下載 APK](https://github.com/sotaru/PBGW/releases/latest) · [完整使用指南](docs/USER_GUIDE.md) · [版本紀錄](CHANGELOG.md)

> 模擬位置與步數不是實際運動紀錄，請勿用來判斷健康狀況。操作可能違反遊戲規則或影響帳號；本專案不保證遊戲採計步數或帳號安全。

## 第一次使用

1. 開啟「工具 → 初始設定」，完成精確定位。
2. 要模擬位置，在 Android「開發者選項 → 選擇模擬位置應用程式」選「阿皮小夥伴」。真實 GPS 步數模式可略過。
3. 要寫步數，允許 Health Connect 讀寫。模擬巡邏需步數與距離；真實 GPS 步數模式只需步數。
4. 要在背景執行，檢查電池與背景活動限制。要浮動控制列或搖桿，允許「顯示在其他應用程式上層」。
5. 在遊戲開啟 Health Connect 步數與背景存取授權。寫入成功仍可能受資料來源優先順序影響。

App 支援 Android 9 以上；較舊裝置需另裝 Health Connect。Pikmin Bloom 透過 Health Connect 讀步數需要 Android 14 以上，見 [Android 官方說明](https://developer.android.com/health-and-fitness/health-connect/availability)與 [Pikmin Bloom 官方說明](https://niantic.helpshift.com/hc/en/23-pikmin-bloom/faq/4942-health-connect/?p=web)。

## 主畫面怎麼用

| 入口 | 用途 |
|---|---|
| **路線** | 切換路線、查看大花；點名稱看位置，下方按鈕排序、編輯或刪除。也可搜尋附近地標與飾品候選位置，或一次清除家與所有大花。 |
| **跳轉** | 跳到十字準星或輸入座標，也可設定家的位置。跳轉不會修改家。 |
| **工具** | 貼上座標規劃採花、匯入／匯出、掃描大花、切換地圖、開浮動控制列及初始設定。 |
| **開始巡邏** | 選大花路線、蚊香巡邏或真實 GPS 步數；每種模式先說明用途。 |
| **詳細** | 展開座標、步數、距離與速度；收合後保留狀態摘要與主要操作。 |
| **右上角設定** | 搜尋設定，調整速度、步幅、每日上限與其他參數。數字會檢查範圍。 |

主畫面保留開始、暫停、繼續、回家與停止等適用操作；**暫停維持模擬位置，停止解除模擬定位**。面板太高時可捲動。介面跟隨系統日夜模式，平板上的控制面板會限制寬度。

## 一次清除家與所有大花

要重新規劃，開啟「路線 → 清除家與所有大花」。確認視窗會列出全部路線的大花總數；按「全部清除」後，自訂家、上次記住的家，以及所有路線的大花點都會清空。這包含其他路線，不只目前這一條。

路線名稱、巡邏設定和步數資料會保留。清除後無法復原；想保留大花點，請先用「工具 → 匯出 JSON」備份，家的座標則另外記下。巡邏、步數模式與掃描必須先停止；有中斷紀錄時，先續走或放棄。下次開始巡邏，會重新取得真實位置作為家，也可以先設定新的自訂家。

## 貼上文字，規劃採花路線

先停止目前模式並處理未完成的中斷點，再到「工具 → 貼上座標規劃採花路線」。可以直接貼混雜文字：

```text
今天的採花清單
家【２５．０３３０，１２１．５６５４】
大花 A：緯度:25.0348, 經度:121.5680
備註：這行沒有座標
大花 B (25.0356 / 121.5620) 已確認
```

App 逐行找出一組有效的緯度、經度；沒有座標或超出範圍就略過，再讀下一行。選家的畫面會列出有效座標、原行號、重複合併數及略過行號。空行與 `#` 註解不計入略過數。第一個有效座標預設為家，可改選；至少需兩個不同座標。

按「規劃路線」檢查順序及距離，最後「套用家與大花」才會儲存。新增獨立路線並保留舊路線；套用的家是全域自訂家，切換其他路線也會使用它。

一次最多一個家與 **300 朵大花**、100,000 字。14 朵以內求出回家一圈的最短座標順序；更多點使用近似規劃，結果不比原順序長，但不保證全域最短。距離是座標中心間的直線，並非道路導航。採花路線只走一輪，最後步行回家停住；遊戲內採花需手動操作。

解析順序固定為緯度、經度，支援十進位與科學記號。每行只取一組，小數座標對優先；同樣優先度取第一組。無法判斷每一組數字是否真的代表位置，請在預覽核對。度分秒及地圖短網址不在支援範圍內。

## 其他常用功能

- **蚊香巡邏**：不必加入大花，從目前位置向外繞圈。本次線寬可臨時修改，預設線寬在設定中調整。
- **真實 GPS 步數**：保留真實定位，按時間新增步數。畫面分別顯示累計、已寫入與待寫入；暫停期間不補計。
- **搖桿**：每次開啟先選本次速度；交通工具不計步，關閉後回到原巡邏方式。
- **浮動視窗**：兩排按鈕與較大的點按範圍。「×」關閉視窗與搖桿，巡邏繼續。
- **Google Maps／OpenStreetMap**：有本機 Maps 金鑰時預設 Google Maps，保留既有手動選擇。金鑰設定見 [說明](docs/GOOGLE_MAPS.md)。
- **步數重試**：寫入失敗會保留本機待寫資料，下次啟動模式時重試。正常更新保留資料，解除安裝或清除資料會移除。

詳見 [使用指南](docs/USER_GUIDE.md)。地標搜尋不保證是遊戲大花；本 App 不自動點擊遊戲。

## 建置與更新

準備 Android Studio 隨附的 Java、Android SDK platform 37、Build Tools 36.0.0 與 platform-tools。Windows：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat assembleDebug testDebugUnitTest assembleRelease --console=plain
```

Linux／macOS：`./gradlew assembleDebug testDebugUnitTest assembleRelease --console=plain`。

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
- Release APK：`app/build/outputs/apk/release/app-release.apk`。
- 用相同簽章與 `adb install -r` 更新，保留設定、路線與待寫步數；請先暫停巡邏。
- Release 不可偵錯、沒有 debug receiver。簽章與環境交接見 [開發說明](docs/DEV_SETUP.md)。

Google Maps 金鑰、簽章、`local.properties` 與私人測試截圖不納入 Git。

## 本版檢查

193 項單元測試：192 項通過、1 項既有辨識圖片測試因缺少本機圖片而略過。建置與模擬器操作的驗證範圍、Lint 工具限制及已修正問題，見 [UI 與 code review](docs/UI_REVIEW.md)。實機更新紀錄保留在 [開發說明](docs/DEV_SETUP.md)，不代表本版已安裝到使用者手機。

本專案位於 [sotaru/PBGW](https://github.com/sotaru/PBGW)，上游為 [SmailDot/Pikmin-Bloom-GPS](https://github.com/SmailDot/Pikmin-Bloom-GPS)。與 Pikmin Bloom 營運商及任天堂無關，不整合 Google Fit。

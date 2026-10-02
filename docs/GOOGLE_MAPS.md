# Google Maps 設定

App 預設使用 OpenStreetMap，不需要金鑰。Google Maps 的切換功能已加入，但要先設定 API 金鑰，才能真正載入 Google 地圖。沒金鑰也能建置，其他功能照常可用。

## 準備與本機設定

依 [Google 官方設定步驟](https://developers.google.com/maps/documentation/android-sdk/start)，建立 Google Cloud 專案、設定所需的帳務、啟用 **Maps SDK for Android**，再建立 API 金鑰。帳號與帳務由你自己操作，請先確認 Google 顯示的費用與使用限制。

建立金鑰後，記得限制它的使用範圍：

1. 在「應用程式限制」選 **Android apps**，填入套件名稱 `app.pikminbloom.gps`，以及 APK 簽章的 SHA-1 指紋。
2. 在「API 限制」只允許 **Maps SDK for Android**。

用以下命令查看目前電腦的 debug 簽章 SHA-1；請找 `debug` 的結果：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat signingReport
```

在專案根目錄建立 `secrets.properties`，加入這一行，並換成自己的金鑰：

```properties
MAPS_API_KEY=你的金鑰
```

`secrets.properties` 已排除在 Git 之外。不要把金鑰放進原始碼、上傳 GitHub，或直接貼到對話裡。也可以使用環境變數 `MAPS_API_KEY`，或放在 `local.properties`。如果同時設定，會依序使用 `secrets.properties`、`local.properties`、環境變數的值。

設定後重新建置並安裝：

```powershell
.\gradlew.bat assembleDebug --console=plain
```

更新 APK 的方式見 [開發環境說明](DEV_SETUP.md)。安裝後再到 App 右上角選單 →「切換地圖來源」→ Google Maps。

## 設定後要確認什麼？

裝置需要可用的 Google Play 服務。切換後，確認地圖能載入、可拖曳與縮放，長按選單、準星、大花標記、路線和目前模擬位置都正常，再試著切回 OpenStreetMap。沒有金鑰時，App 會顯示提示並繼續使用 OpenStreetMap。

如果有金鑰卻還是空白地圖，先確認 API 已啟用、帳務正常、套件名稱與 SHA-1 都填對，再看 Logcat 是否有 Google Maps 授權錯誤。Debug 和 release 若使用不同簽章，要分別加入對應的 SHA-1；換電腦後的 debug 簽章也可能不同。

目前沒有設定金鑰，已確認可以建置，也確認缺少金鑰時會顯示提示。實際 Google 地圖是否能正常載入，還要等設定金鑰後再測試。

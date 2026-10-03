# Google Maps 設定

App 尚未儲存地圖選擇時，有設定 API 金鑰就預設使用 Google Maps；沒有金鑰則使用 OpenStreetMap。手動切換後會記住你的選擇。沒金鑰也能建置，其他功能照常可用；Google Maps 仍需要裝置有可用的 Google Play 服務。

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

在專案根目錄建立 `secrets.properties`，加入這一行，並換成自己的金鑰。這台電腦的位置是 `C:\Projects\PBGW\secrets.properties`；專案保留在 `C:\Projects\PBGW`，不需要移到 `C:\soft\PBGW`。

```properties
MAPS_API_KEY=你的金鑰
```

`secrets.properties` 已排除在 Git 之外。不要把金鑰放進原始碼、上傳 GitHub，或直接貼到對話裡。也可以使用環境變數 `MAPS_API_KEY`，或放在 `local.properties`。如果同時設定，會依序使用 `secrets.properties`、`local.properties`、環境變數的值。

設定後重新建置並安裝：

```powershell
.\gradlew.bat assembleDebug --console=plain
```

更新 APK 的方式見 [開發環境說明](DEV_SETUP.md)。尚未儲存地圖選擇的 App，安裝有金鑰的版本後就會預設開啟 Google Maps；若先前已手動選擇 OpenStreetMap，可到 App 右上角選單 →「切換地圖來源」→ Google Maps。

## 設定後要確認什麼？

裝置需要可用的 Google Play 服務。切換後確認地圖載入、拖曳、縮放、長按選單與準星正常。模擬巡邏另檢查大花、路線與模擬位置；真實 GPS 步數模式檢查實機位置與狀態顯示，再試著切回 OpenStreetMap。沒有金鑰時，App 會顯示提示並繼續使用 OpenStreetMap。

如果有金鑰卻還是空白地圖，先確認 API 已啟用、帳務正常、套件名稱與 SHA-1 都填對，再看 Logcat 是否有 Google Maps 授權錯誤。Debug 和 release 若使用不同簽章，要分別加入對應的 SHA-1；換電腦後的 debug 簽章也可能不同。

沒有金鑰時使用 OpenStreetMap，手動選 Google Maps 會顯示提示。本次已在模擬器確認有金鑰的 Google Maps 地圖可載入；其他裝置仍需各自確認。地圖來源只決定底圖，真實 GPS 步數模式的定位由手機 GPS 提供。

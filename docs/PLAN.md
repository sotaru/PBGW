# 皮克敏巡花助手 (Pikmin Bloom GPS) — 規格與實作計畫

> 本文件同時是給人看的計畫，也是給實作代理 (implementation agents) 的任務書。API 名稱維持英文。

> 這份文件保留早期設計與研究，部分環境和做法已經改變。目前的操作與限制請以 [README](../README.md)、[版本紀錄](../CHANGELOG.md) 和實際程式為準；開發交接請看 [DEV_SETUP](DEV_SETUP.md)。

## 0. 背景與結論

- 原始 repo (lokey0905/POGO_Manager) 只是「下載入口 / 版本檢查」的管理器，本身**沒有** GPS 模擬與步數功能；Pikmin 相關功能全靠外部 App（GPS JoyStick、DeFit）。
- 因此本專案改為：保留 Gradle/AGP 骨架與 Material 3 主題，其餘全部重寫成單一目的 App。
- 目標裝置：POCO X6 Pro (2311DRK48G)，Android 16 (API 36)，HyperOS 3.0，Pikmin Bloom 152.0，Health Connect 為系統模組。

## 1. 使用者需求 → 功能

| # | 需求 | 實作 |
|---|------|------|
| 1 | 自動巡邏、採收巨大花朵 (Big Flowers) | 使用者在地圖上標記大花位置（或輸入座標）。App 以「模擬走路」在各大花之間移動；抵達後在大花 40 m 圈內繞行一段時間（讓遊戲在圈內種花，種滿 300 朵開花；開花後 1 小時內在圈內種花可獲得果實遠征），並以通知+震動提醒使用者「已抵達，可在遊戲內點大花往下滑領花蜜」。遊戲內點擊無法可靠自動化（大花在畫面上的位置不固定），故 v1 不做自動點擊。 |
| 2 | 巡邏時計步（培育花苗） | 依模擬距離換算步數（距離 / 步幅，預設 0.70 m），每 60 秒把 `StepsRecord`（+`DistanceRecord`）寫入 Health Connect。Pikmin Bloom 需在遊戲設定中改用 Health Connect 讀步數，並授權背景讀取。每日花苗步數上限 50,000（可設定）。 |
| 3 | 想結束時「自動回家」 | 開始巡邏前先記錄真實 GPS 位置為 Home。按「回家」後以走路速度沿直線走回 Home（或選擇瞬移），到達後解除模擬定位，遊戲看到的位置與真實位置無縫接軌。 |

### 非目標 (v1)
- 不做遊戲畫面自動點擊 (AccessibilityService)。
- 不需要 root / Shizuku。
- 不做 Google Fit（API 已淘汰）。

## 2. 遊戲機制要點（研究結果，設計依據）

- 大花位於固定 POI（Wayspot）。無公開 API，使用者需自行標記。
- 大花圈半徑 **40 m**：圈內任何玩家種的花都算；累積 300 朵開花，開花維持 23 小時。
- **花蜜**：靠近開花的大花（約 100 m 內）打開資訊頁往下滑，每人每次開花只能領一次。
- **果實**：開花後 1 小時內在 40 m 圈內種花，可獲得果實遠征（需手動派遣）。
- **種花靠 GPS 位移，不靠步數**：地面為 5×5 m 格子，每格 5 分鐘內只能種一次 → 巡邏路線應避免原地小圈打轉，繞行要涵蓋不同格子。
- 種花速度上限約 15–20 km/h；安全模擬速度 4–8 km/h。花瓣按時間消耗（40+ 皮克敏 6 瓣/分）。
- 遊戲對 Pikmin 的定位授權為「一律允許」時，種花可在背景進行。
- 步數來源：Android 14+ 用 Health Connect；Pikmin 需授權 `READ_STEPS` 與「背景存取」。花苗每日步數上限 50,000。
- Niantic 三振政策明文禁止 GPS 造假；社群觀察 Pikmin 執法很少，但風險存在，UI 需顯示免責提示。

## 3. 架構

單一 module `app`，package `app.pikminbloom.gps`。

```
geo/      LatLng, GeoMath                 純 Kotlin，可單元測試
route/    PatrolPlanner                   把 waypoints 轉成路線段（含圈內繞行）
sim/      WalkSimulator                   每 tick 產生一個 Sample（位置/速度/方位/精度）
mock/     MockLocationController          LocationManager test providers + FLP mock mode
steps/    StepInjector                    Health Connect 寫入 / 讀回 / 今日總量
service/  PatrolService, PatrolNotifications   前景服務、1 Hz 迴圈、狀態 StateFlow
data/     Models (已寫好), Prefs, WaypointStore
ui/       MainActivity(地圖+控制), WaypointListActivity/Dialog, SettingsActivity, SetupActivity, HealthRationaleActivity
debug/    (src/debug) DebugCommandReceiver   讓 adb 可以驅動測試
```

### 3.1 已存在的共用型別（`data/Models.kt`, `geo/LatLng.kt`）
`LatLng`, `Waypoint(id,name,lat,lon,radiusM,dwellSec)`, `LoopMode{LOOP,PINGPONG,ONCE}`, `ReturnMode{WALK,TELEPORT}`, `PatrolConfig(...)`, `PatrolPhase{IDLE,STARTING,WALKING,DWELLING,PAUSED,RETURNING_HOME,STOPPING}`, `PatrolState(...)`。**不要改欄位名稱**；需要新增欄位可以加，但要保持既有欄位。

### 3.2 各模組公開 API（契約）

#### geo/GeoMath.kt (object GeoMath)
```kotlin
fun distanceM(a: LatLng, b: LatLng): Double                  // haversine
fun bearingDeg(a: LatLng, b: LatLng): Double                  // [0,360)
fun destination(from: LatLng, bearingDeg: Double, distanceM: Double): LatLng
fun interpolate(a: LatLng, b: LatLng, fraction: Double): LatLng
fun offsetMeters(p: LatLng, northM: Double, eastM: Double): LatLng
fun normalizeBearing(deg: Double): Double
```

#### route/PatrolPlanner.kt
```kotlin
data class RouteSegment(val from: LatLng, val to: LatLng, val waypointIndex: Int?, val kind: SegmentKind)
enum class SegmentKind { TRAVEL, ORBIT }
data class PatrolPlan(val segments: List<RouteSegment>, val totalLengthM: Double)

object PatrolPlanner {
    /** Build one lap. start = current position. For each waypoint: TRAVEL to the circle edge, then ORBIT
     *  segments inside the circle that cover many distinct 5 m cells for approximately dwellSec at speedMps
     *  (e.g. a rosette / spiral / lawn-mower pattern; NEVER a tight circle smaller than 10 m). */
    fun planLap(start: LatLng, waypoints: List<Waypoint>, config: PatrolConfig, order: List<Int>): PatrolPlan
    fun orderFor(lap: Int, count: Int, mode: LoopMode): List<Int>   // LOOP: 0..n-1 ; PINGPONG: alternate reversed ; ONCE: only lap 0
    fun planReturnHome(from: LatLng, home: LatLng): PatrolPlan       // single TRAVEL segment
}
```

#### sim/WalkSimulator.kt
```kotlin
data class Sample(
    val position: LatLng, val speedMps: Double, val bearingDeg: Double, val accuracyM: Float,
    val altitudeM: Double, val distanceDeltaM: Double, val segmentIndex: Int, val waypointIndex: Int?,
    val kind: SegmentKind, val arrivedAtWaypoint: Int?  /* non-null exactly once when a new waypoint circle is entered */,
    val lapFinished: Boolean,
)
class WalkSimulator(config: PatrolConfig, random: kotlin.random.Random = kotlin.random.Random.Default) {
    fun load(plan: PatrolPlan)                       // resets progress along a new plan
    fun advance(dtSec: Double): Sample               // moves speed*dt (with jitter) along the plan; clamps at end
    fun current(): Sample                            // last sample without moving (used while PAUSED: re-push same fix)
    val finished: Boolean
    fun setSpeed(mps: Double)
}
```
Realism rules: speed jitter ±`speedJitterPct` re-drawn every 3–8 s (not every tick), lateral GPS noise ≤ 1.0 m applied to the *reported* position only (progress along the path is exact), accuracy uniform in [accuracyMinM, accuracyMaxM] changing slowly, bearing = segment bearing, altitude = config.altitudeM ± 0.3 m drift. When speed is 0 (paused), report speed 0 and keep bearing.

#### mock/MockLocationController.kt
```kotlin
class MockLocationController(context: Context) {
    fun isMockAppSelected(): Boolean               // AppOpsManager.unsafeCheckOpNoThrow(OPSTR_MOCK_LOCATION, uid, pkg) == MODE_ALLOWED
    fun start(config: PatrolConfig)                // addTestProvider gps (+network,+fused on API31+ if enabled), setTestProviderEnabled; FLP setMockMode(true) if enabled. Catch IllegalArgumentException (already exists) and SecurityException (-> throw MockNotAllowedException)
    fun push(sample: Sample)                       // build Location (lat, lon, alt, accuracy, speed, bearing, time=now, elapsedRealtimeNanos=now, verticalAccuracy 1.5f, speedAccuracy 0.3f, bearingAccuracy 5f, extras satellites=9 usedInFix=9); setTestProviderLocation for every provider; FLP setMockLocation
    fun stop()                                     // setTestProviderEnabled(false) + removeTestProvider for each, FLP setMockMode(false); swallow exceptions
    suspend fun currentRealLocation(timeoutMs: Long = 15_000): LatLng?   // ONLY valid when mock is stopped: FusedLocationProviderClient.getCurrentLocation(PRIORITY_HIGH_ACCURACY) with CancellationToken, fallback lastLocation, fallback LocationManager.getLastKnownLocation(GPS/NETWORK)
    fun openMockAppPicker(activity: Activity)      // Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
}
class MockNotAllowedException(msg: String) : Exception(msg)
```
Providers: `LocationManager.GPS_PROVIDER`, `LocationManager.NETWORK_PROVIDER`, and on API ≥ 31 `LocationManager.FUSED_PROVIDER` — each wrapped in its own try/catch so a failure on one does not abort the others. API ≥ 31 uses `ProviderProperties.Builder()` (accuracy FINE, power LOW for network/fused, HIGH for gps, altitude/speed/bearing supported); API < 31 uses the 10-arg overload with `Criteria` constants.

#### steps/StepInjector.kt
```kotlin
class StepInjector(context: Context) {
    val sdkStatus: Int                                     // HealthConnectClient.getSdkStatus
    val isAvailable: Boolean
    val requiredPermissions: Set<String>                   // write+read steps, write+read distance
    suspend fun hasPermissions(): Boolean
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>>   // PermissionController.createRequestPermissionResultContract()
    suspend fun write(start: Instant, end: Instant, steps: Long, distanceM: Double): Boolean
        // StepsRecord + DistanceRecord, zone offsets from system zone, metadata = Metadata.autoRecorded(device = Device(type = Device.TYPE_PHONE, manufacturer = Build.MANUFACTURER, model = Build.MODEL)) with clientRecordId = "pbgps-steps-<startEpochMs>"; records must be <= 1 h span, end <= now; returns false (never throws) on failure and logs the reason
    suspend fun stepsToday(): Long                         // aggregate COUNT_TOTAL from local midnight to now (all sources)
    suspend fun stepsWrittenByUsToday(): Long               // readRecords filtered by dataOrigin == our package
    suspend fun deleteOurRecordsToday()                    // deleteRecords by time range for our dataOrigin
    fun openHealthConnectSettings(context: Context)
}
```
Metadata factory (connect-client 1.1.0): `androidx.health.connect.client.records.metadata.Metadata.autoRecorded(device: Device, clientRecordId: String? = null)`, `Device(type = Device.TYPE_PHONE, manufacturer = ..., model = ...)`.

#### service/PatrolService.kt (LifecycleService, foregroundServiceType="location")
Intent actions (constants in companion): `ACTION_START`, `ACTION_PAUSE`, `ACTION_RESUME`, `ACTION_RETURN_HOME`, `ACTION_STOP`, `ACTION_SKIP_WAYPOINT`.
`ACTION_START` extras: none required — the service loads waypoints + config from `WaypointStore`/`Prefs`. Optional extras: `EXTRA_HOME_LAT/EXTRA_HOME_LON` (override captured home), `EXTRA_START_AT_INDEX`.

State: `companion object { val state: StateFlow<PatrolState>; val events: SharedFlow<PatrolEvent> }` (process-local; the UI collects it). `PatrolEvent`: `ArrivedAtWaypoint(index, name)`, `LapFinished(lap)`, `ReturnedHome`, `Error(message)`, `StepsWritten(count, todayTotal)`.

Lifecycle:
1. START → phase STARTING: `startForeground` immediately (within 5 s, type location) with the patrol notification; acquire PARTIAL_WAKE_LOCK; check `isMockAppSelected()` else emit Error + stop; capture home = `currentRealLocation()` unless an override / a saved home from Prefs is used (UI decides and passes extras); persist home to Prefs; `mock.start()`; build plan with `PatrolPlanner.planLap(home, waypoints, config, orderFor(0,...))`; `sim.load(plan)`; phase WALKING.
2. Tick loop: `while (isActive) { tick(); delay(config.tickMs) }` on a coroutine (Dispatchers.Default); each tick: if PAUSED → `mock.push(sim.current().copy(speed 0))` (Android 12+ needs ≥1 Hz re-push or the fix decays); else `sample = sim.advance(dt)`, `mock.push(sample)`, accumulate distance, steps = floor(distance / strideM) accrued; on `arrivedAtWaypoint` → phase DWELLING, event + high-priority notification (vibrate) if `notifyOnArrival`; when ORBIT segments end → WALKING; on `lapFinished` → next lap per LoopMode (ONCE → auto RETURN_HOME).
3. Step flush: every `stepFlushIntervalSec` (and on pause/stop/return-home completion) write accrued steps since last flush with `StepInjector.write(lastFlush, now, steps, distance)` if `injectSteps` and `stepsWrittenToday + steps <= dailyStepCap` (clamp; do not write over the cap). Update `stepsWrittenToday` in state (read once at start via `stepsWrittenByUsToday()`, then accumulate).
4. RETURN_HOME → phase RETURNING_HOME: `sim.load(planReturnHome(current, home))`; WALK mode walks at config speed; TELEPORT pushes home directly; when `sim.finished` → push home fix 3 more ticks, `mock.stop()`, flush steps, event ReturnedHome, stopSelf.
5. STOP → phase STOPPING: flush, `mock.stop()` (position stays wherever it was — warn in UI that this may look like a teleport), release wakelock, stopForeground(REMOVE), stopSelf.
6. Notification (CHANNEL_PATROL, low importance, ongoing): title = phase text, text = "第 N 個大花 · 已走 1.2 km · 今日步數 3,450"; actions: 暫停/繼續, 回家, 停止 (PendingIntents to the service). Update at most once per 5 s.
7. Robustness: `onTaskRemoved` keeps running; `START_STICKY` is NOT used (do not auto-restart a spoof after process death; instead on restart, if mock providers linger, `mock.stop()` in `onCreate` of the Application? No — leave to service). Catch every exception in tick, publish `lastError`, and keep going except for MockNotAllowedException which stops the service.

#### data/Prefs.kt & data/WaypointStore.kt
- `Prefs(context)`: reads/writes `PatrolConfig` from `PreferenceManager.getDefaultSharedPreferences` using string keys that match `res/xml/preferences.xml` (keys: `speed_kmh` (String, default "4.7"), `speed_jitter_pct`, `stride_cm` (default "70"), `loop_mode`, `inject_steps` (Boolean), `step_flush_sec`, `daily_step_cap`, `accuracy_min_m`, `accuracy_max_m`, `altitude_m`, `notify_on_arrival`, `return_mode`, `mock_network`, `mock_fused`, `flp_mock_mode`, `default_radius_m`, `default_dwell_sec`). Also `home: LatLng?` (`home_lat`/`home_lon` as Long bits), `lastPosition`.
- `WaypointStore(context)`: JSON file `waypoints.json` in `filesDir`; `load(): List<Waypoint>`, `save(list)`, `add/update/remove/move(from,to)`, `exportJson(): String`, `importJson(text)`, `importGpx(text)` (wpt elements), `exportGpx(): String`. Expose `StateFlow<List<Waypoint>>`.

#### ui/
- `MainActivity`: full-screen osmdroid `MapView` (Mapnik tiles, multi-touch, zoom 17 default centered on home or last position or Taiwan 25.03,121.56). Overlays: Waypoint markers (numbered, tap → edit/delete/reorder bottom sheet), 40 m circle (`Polygon.pointsAsCircle`) + orbit radius circle per waypoint, Home marker, current simulated position marker (with bearing), planned route `Polyline`, walked trail. Long-press on map → "新增大花" dialog (name, radius, dwell). FAB menu: 我的位置 (real or simulated), 大花清單, 設定, 初始設定檢查. Bottom card: status line (phase, 目前目標, 距離, 已走距離, 本次步數, 今日步數, 速度) + buttons: **開始巡邏 / 暫停 / 繼續**, **回家**, **停止**. On 開始巡邏: run pre-flight checks (location permission, notification permission (API 33+), mock app selected, ≥1 waypoint, Health Connect permissions if injectSteps — offer to continue without steps), then `startForegroundService(ACTION_START)`. If a saved home exists and the mock is currently inactive, ask "使用上次的家 / 重新定位". Collect `PatrolService.state` with `repeatOnLifecycle(STARTED)`.
- `SetupActivity`: checklist with status icons and one-tap fix buttons: (1) 定位權限, (2) 通知權限, (3) 開發者選項 → 選擇模擬位置應用程式 (button opens developer settings; text explains: 若「開發者選項」未開啟，到「關於手機」連點 MIUI/OS 版本 7 次), (4) Health Connect 可用 + 權限 (button launches permission contract), (5) 電池最佳化排除 (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS), (6) Pikmin Bloom 設定說明 (text: 遊戲內 設定 → 隱私與步數 → 步數 → 使用 Health Connect；並在 Health Connect → 應用程式權限 → Pikmin Bloom 開啟「背景存取」；定位權限設為「一律允許」), (7) 開啟 Pikmin Bloom 按鈕. Re-check on `onResume`.
- `SettingsActivity` + `SettingsFragment` (PreferenceFragmentCompat, `res/xml/preferences.xml`) with the keys above (EditTextPreference numeric inputType, ListPreference for loop/return mode, SwitchPreferenceCompat). Include a "重設今日寫入的步數 (刪除本 App 今日寫入的 Health Connect 紀錄)" preference and an "關於 / 免責聲明" preference.
- `HealthRationaleActivity`: shows `@string/health_rationale_body`.
- All UI strings in `res/values/strings.xml` in Traditional Chinese (zh-TW). Service-only strings live in `res/values/strings_service.xml`.

#### src/debug/AndroidManifest.xml + debug/DebugCommandReceiver.kt (debug build only)
Exported `BroadcastReceiver` with action `app.pikminbloom.gps.DEBUG_CMD`, extras: `cmd` in {`start`,`pause`,`resume`,`return_home`,`stop`,`set_waypoints`,`set_home`,`write_steps`}, `waypoints` (String "lat,lon,name;lat,lon,name"), `home` ("lat,lon"), `steps` (Int), `minutes` (Int). It writes to WaypointStore/Prefs and forwards to PatrolService. Purpose: `adb shell am broadcast -a app.pikminbloom.gps.DEBUG_CMD --es cmd start ...` for automated device tests. Must not exist in release.

## 4. 手機端設定步驟（使用者）
1. 開發者選項 → USB 偵錯（已完成）→ **選擇模擬位置應用程式 = 皮克敏巡花助手**。
2. App 初始設定頁完成所有檢查。
3. Pikmin Bloom：設定 → 隱私與步數 → 步數 → **Health Connect**；Health Connect → 應用程式權限 → Pikmin Bloom → 允許讀取步數 + **背景存取**。Pikmin 定位權限「一律允許」。
4. 在地圖長按加入大花 → 開始巡邏 → 遊戲內開啟種花 → 抵達提醒時到遊戲點大花往下滑領花蜜 → 想結束按「回家」。

## 5. 測試計畫
- 單元測試 (JUnit, pure Kotlin)：GeoMath（已知距離/方位/目的地反算）、PatrolPlanner（每個 waypoint 至少一個 TRAVEL + ORBIT，ORBIT 全在 radius 內，總長度合理，LOOP/PINGPONG/ONCE 順序）、WalkSimulator（速度 × 時間 ≈ 走過距離、jitter 在範圍內、arrivedAtWaypoint 只觸發一次、finished 行為、暫停不前進）。
- 裝置測試 (adb, debug build)：`adb shell appops set app.pikminbloom.gps android:mock_location allow`；`am broadcast ... set_waypoints/start`；`adb shell dumpsys location | grep -A3 "last location"` 觀察 gps/fused 位置每秒更新且座標沿路線移動；`adb shell cmd location get-last-location`（若可用）；開啟 Pikmin Bloom 截圖確認人物移動；Health Connect：用 `write_steps` 寫入後以 App 內「今日步數」讀回；回家：位置回到 home 且 test providers 移除（`dumpsys location` 不再顯示 mock）。

## 6. 風險
- Pikmin 若之後開始拒絕 `isMock` 定位，非 root 方案無解（目前社群普遍可用）。
- Health Connect 步數是否被 Pikmin 採計取決於遊戲端讀取方式（recordingMethod 過濾未知）；先以 `autoRecorded` + `TYPE_PHONE` 測試，必要時改 `activelyRecorded`。
- HyperOS 省電策略會殺前景服務：需電池最佳化排除 + 自啟動允許。

---

# 附錄：2026-09-10 實機測試與需求變更

## A. 實機測試結果（POCO X6 Pro / Android 16 / Pikmin Bloom 152.0）

全部通過：三個 test provider（gps/network/fused）+ FLP mock 皆生效、巡邏沿路線移動、抵達大花觸發事件、
每 60 秒寫入 Health Connect、按回家後走回原點並自動移除 provider（實測 158 m）。
遊戲端「生活記錄」地圖出現模擬走路的腳印軌跡，種花正常。

## B. 已知陷阱：Health Connect 資料來源優先順序（**必讀**）

寫入 `StepsRecord` 成功 **不代表** 遊戲讀得到。Health Connect 只把列在
**管理資料 → 資料來源與優先順序** 清單中的 App 計入每日總計。

實測數據：本 App 已寫入 984 步，`readRecords`（自己的 dataOrigin）讀得到 984，
但 `aggregate(COUNT_TOTAL)` 回傳 0，Health Connect UI 顯示「772 步 · 小米運動健康」。
把「皮克敏巡花助手」加入資料來源後，總計立刻變成 1,756。

該畫面的官方說明文字即是證據：
> 如果從清單移除資料來源，該來源仍會具有寫入權限，但其資料就不會再計入總數。

因應措施：`StepInjector.stepsAreCountedInTotals()` 比對 aggregate 與自身 origin 的差異，
偵測到被忽略就提示使用者，並提供 `openHealthConnectDataSources()` 直達該設定頁。

另注意：`aggregate()` 在背景呼叫會丟 `SecurityException ... must be in foreground`，
除非宣告 `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND`（已加入 manifest）。

「使用手機追蹤測量」模式讀的是硬體計步器，**任何 App 都無法寫入**，遊戲必須維持 Health Connect 模式。

## C. 需求變更

1. **速度上限 20 km/h**：原本 `Prefs.config()` 把 speedMps 夾在 2.5 m/s（9 km/h），
   使用者設 20 被默默降速。改為 `MAX_SPEED_MPS = 20/3.6`。
   注意遊戲約在 15–20 km/h 停止種花，UI 需提示建議值 4–12 km/h。
2. **繞行改為開關**：新增 `PatrolConfig.orbitAtWaypoints`（預設 **false**）與偏好設定
   `orbit_at_waypoints`。關閉時 `PatrolPlanner.planLap` 忽略 `dwellSec`，
   只走到大花圈邊緣就前往下一個。`default_dwell_sec` 依賴此開關。
3. **浮動控制列**：`ui/OverlayService`，TYPE_APPLICATION_OVERLAY 懸浮於遊戲上方，
   可拖曳、可收合，提供暫停/繼續/回家/停止與即時狀態。

## D. 自動化可行性結論（2026-09-10 實機調查）

### D1. 自動點擊領花蜜 — **不做**
- Unity 畫面對無障礙服務完全不透明。五個不同遊戲畫面的 `uiautomator dump` 產生
  **位元組完全相同**的 3375 bytes XML，唯一節點是
  `com.nianticlabs.pikmin:id/unitySurfaceView`（SurfaceView, bounds [0,0][1220,2712]）。
  連「商店」「好友」等大按鈕都沒有節點。
- 因此既無法定位大花，**也沒有任何回饋管道確認點擊是否成功**（沒有 window content change 事件）。
- 盲點座標亦不可行：地圖被拖曳後永不自動回正（實測靜置 15 秒畫面不變），
  且縮放/旋轉/鏡頭俯角皆持久化。實測兩次盲點全部點錯（開到皮克敏派遣頁與詳細卡）。
- 風險：`AccessibilityManager.getEnabledAccessibilityServiceList()` 不需權限即可被任何 App 讀取，
  是成熟的偵測向量。改定位已違反 §3.1，再加自動點擊等於多一類獨立可偵測的違規（§6 禁止 automation）。
- 報酬過低：每朵大花每次開花僅 1–3 花蜜。
- 替代方案：維持現行「抵達時通知 + 震動」，由使用者手動點兩下。

### D2. 自動偵測大花位置 — **用 OpenStreetMap 候選點**
- 無任何公開資料源列出大花座標；大花是 Wayspot 的**輪替子集**，官方會增刪。
- 官方 Wayfarer 地圖需登入且無 API；IITC 匯出違反 Niantic 條款；
  MITM 遊戲流量需 root + Frida，會觸發偵測，一律不採用。
- 採用 **Overpass API（OpenStreetMap）** 產生候選點：`data/OverpassClient.kt`。
  依 Wayfarer 收錄傾向對 historic / memorial / tourism=artwork / place_of_worship /
  playground 等標籤排序，過濾 access=private 與軍事區，25 m 內去重，命中率約 3–6 成。
  ODbL 僅在再散布時需標註，本機清單不觸發。

## E. 第二階段構想：俯瞰模式螢幕辨識（**尚未實作，需使用者同意**）

使用者 2026-09-10 指出：遊戲底部中央的**俯瞰模式**切換鈕，會把畫面變成接近正上方的平面地圖。
這推翻了附錄 D1 中「畫面雜訊太多、鏡頭俯角不定」的部分論據：

| | 走路視角 | 俯瞰模式 |
|---|---|---|
| 投影 | 3D 斜角，有俯仰 | 接近正交，近似平面 |
| 背景 | 密集花毯（高雜訊） | 單色綠地 + 淡色道路（低雜訊） |
| 大花外觀 | 立體花朵，會被遮擋 | 高對比獨立圖示 |
| 可排除的干擾 | 難 | 蘑菇有「👤N」徽章與圓餅計時器，特徵明確 |

### E1. 用途 A（高價值）：自動推導大花座標
比自動點擊更有價值。流程：

1. 切到俯瞰模式，按右下角「回到目前位置」鈕，讓人物回到**已知的畫面位置**。
2. 截圖 →偵測花朵圖示（飽和色塊 + 下方細長綠莖；排除帶徽章的蘑菇）。
3. **用我們自己控制的 GPS 當比例尺校準**：把模擬位置往正北移動固定距離（例如 50 m），
   再截一張圖，比對地物像素位移量 → 得到「公尺 / 像素」與畫面北方方位。
   這是本方案的關鍵，因為遊戲不公開地圖縮放參數，但**我們控制定位，所以可以自己造一把尺**。
4. 每個偵測到的花朵：像素偏移 → 公尺偏移 → `GeoMath.offsetMeters(playerPos, north, east)` → 經緯度。
5. 寫進 `WaypointStore`，成為巡邏點。

準確度預期遠高於附錄 D2 的 OpenStreetMap 候選點（那是猜 Wayspot，命中率 3–6 成），
因為這是遊戲畫面上**真實存在**的大花。

### E2. 用途 B（低價值）：自動點擊領花蜜
同一套偵測結果可以拿來 `dispatchGesture` 點擊 + 下滑。
但報酬僅每朵花每次開花 1–3 花蜜，且遊戲需在範圍內（約 100 m）才能領。

### E3. 成本與風險（**這是要不要做的決定點**）
- 需要 `AccessibilityService`（`canTakeScreenshot` + `canPerformGestures`）或 `MediaProjection`。
- `AccessibilityManager.getEnabledAccessibilityServiceList()` **不需任何權限**即可被任何 App 讀取，
  是成熟且廉價的偵測向量。改定位已違反條款 §3.1，這會再加一條 §6（禁止 automation）。
- 遊戲美術更新會讓 template 失效，需要維護。
- HyperOS 會積極清掉無障礙服務，需額外白名單設定。

### E4. 建議
- 階段一（現行）：OpenStreetMap 候選點匯入。零額外權限、零額外風險。
- 階段二：本方案，**預設關閉、獨立開關、明確風險說明**，由使用者自行決定。
  若要做，先做 E1（座標推導），E2（自動點擊）可再議 — E1 只讀畫面不注入觸控，
  風險較低且價值高得多。

### E5. 使用者回報的關鍵互動細節（2026-09-10）

**俯瞰模式點花是兩段式的：**
1. 點擊花朵 → 畫面跳出**花朵名稱標籤**
2. 點擊該名稱標籤 → 進入花朵資訊頁
3. 在資訊頁上**往下滑** → 領取花蜜

這改變了附錄 D1 的結論。原本判定「不可行」的最大理由是
「Unity 無節點 → 點下去之後無法確認發生了什麼」。
但名稱標籤的出現本身就是**視覺回饋訊號**：

| 步驟 | 動作 | 成功判定 |
|---|---|---|
| 1 | 點花朵座標 | 截圖出現名稱標籤 → 命中 |
| 2 | 點名稱標籤 | 截圖出現資訊頁（大面積面板） → 命中 |
| 3 | 下滑 | 截圖出現花蜜獲得動畫／數量變化 |

每一步都可用截圖驗證，失敗可退回重試，不會盲目往下做。
這比原先假設的「一次盲點」可靠得多。

**測試素材注意事項：** 校準與辨識的截圖必須在**真的有大花的區域**擷取。
使用者回報先前的測試座標（往北 100 m）是郊區空地，畫面上沒有花朵，
那種截圖無法建立 ground truth。應在使用者實際遊玩的位置附近小範圍移動
（校準只需要 50 m 位移，同一批花朵仍會留在畫面上）。

## F. 斷點續走（2026-09-11，實際使用回報）

**症狀：** 手機過熱時 HyperOS 殺掉本 App。test provider 不會隨程序消失，遊戲角色停在被殺的位置。
重開 App 按開始：舊流程先清掉殘留 provider（暴露真實 GPS）、再抓真實位置當家、從頭走路線。
遊戲看到「從中斷點瞬移回家、再沿路線走出去」，是本 App 能做出的最容易被偵測的動作。
`PikminGpsApp.onCreate` 的殘留清理讓它更糟：使用者還沒按任何鍵，角色就已經跳回真實位置。

**解法：** `service/PatrolCheckpoint.kt`
- 巡邏中每 5 秒在 engine thread 原子寫入（temp + rename）：位置、家、路線 id、圈數、目標大花索引、階段、步數帳目。
- 所有正常結束路徑（stop / 回家完成 / 啟動失敗）在 `teardown()` 刪除。
- App 啟動時若存在可續走的存檔（12 小時內）：**不清理** provider，讓遊戲繼續停在原地。
- `MainActivity.onResume` 跳出對話框：
  - 從中斷點繼續：`mock.start(keepExisting = true)`（addTestProvider 原地取代，無空窗），
    立刻推送系統仍持有的最後一筆 mock fix（`lastParkedMockFix()`，與遊戲畫面完全一致），
    用存檔的家、剩餘的大花從該點繼續規劃。
  - 從中斷點走回家：同上，但直接規劃回家路徑。
  - 放棄：刪除存檔、移除 provider，遊戲看到一次瞬移（對話框明講）。
- 有未處理存檔時 `handleStart` 一律拒絕，主畫面的「開始」會導回選擇對話框，避免誤觸。
- 家沿用存檔的家而非重新定位：被殺通常發生在幾分鐘內，使用者沒移動；而且殘留 provider 還在遮蔽真實 GPS，
  先移除再定位正是要避免的瞬移。若使用者真的移動了，選「放棄」重新開始即可。

**實測（run-as kill -9）：** 殺後遊戲停在 22.759428,120.337840；續走從 22.759428,120.337840 接回，
零位移，接著走向下一個目標。

## G. 巡邏中即時控制（2026-09-12，使用者回報 5 個問題 + 搖桿）

回報的症狀與原因：

| # | 症狀 | 原因 | 修法 |
|---|------|------|------|
| 1 | 走路中調速度沒反應 | `PatrolService` 只在 start 時讀一次 `prefs.config()`；`WalkSimulator` 建構時把速度抄進私有欄位 | 服務註冊 `OnSharedPreferenceChangeListener`（強參考），任何 `Prefs.config()` 會讀的 key 變動就在 engine thread 重讀並呼叫 `sim.updateConfig()`，下一個 tick 生效 |
| 2 | 走路中刪掉／新增大花沒反應 | `waypoints` 欄位只在 start 時抄一份；`loadLap` 用的是舊清單 | 服務 collect `WaypointStore.waypoints`；有變動就 `replanRemaining()`：以「本圈已到過的 id」(`doneThisLap`) 過濾新順序、從目前位置重新規劃。正在繞圈或在交通工具腿上時先記 `replanPending`，等該腿走完再套用。目前這朵若沒被改動，`sim.remainingLegsOfCurrentWaypoint()` 把剩下的腿原樣保留（第一段縮短成從現在位置開始），畫面上完全無感 |
| 3 | 想自訂家（人在台灣、想待在日本） | 家一律是當下真實位置（C 節的需求） | `Prefs.customHome`（家的位置… 選單：地圖中心／輸入座標／改回真實位置）。有自訂家時 start 不定位直接從那裡出發；回家走回自訂家後進入新階段 **PARKED**：mock 維持、每秒重推同一點，通知與主畫面明講「按停止才回真實 GPS」。「再巡一圈」從家再載一圈；「停止」才 `mock.stop()`。沒設自訂家時行為完全不變 |
| 4 | 看不到交通工具 | `TravelMode` 只有飾品之旅在用 | `PatrolService.travelOverride`（StateFlow）：主畫面「移動方式」按鈕、浮動列車子圖示循環（步行→腳踏車→汽機車→高速公路→飛機）。`WalkSimulator.travelOverride` 套在所有沒有自帶 travelMode 的腿上；交通工具不計步。**抵達下一朵大花時自動切回步行**（車是拿來到達的，忘了關會整圈不種花）。大花標記／清單的「從這裡開始」在巡邏中變成「立刻前往這朵」= `ACTION_GO_TO`，把它插到最前面重新規劃 |
| 5 | 自動領花蜜 | 未實作（見 D1／E2） | 維持不做；可行路徑是 AccessibilityService `dispatchGesture`（不需要節點樹），但點花→點名字→下拉→關閉的座標要在實機上量，需要附近有開花的大花 |
| + | 搖桿 | — | `JoystickView`（第二個 overlay 視窗，左下角 150dp，上＝北）。`PatrolService.joystick` StateFlow：`enabled` 切換進出 **MANUAL** 階段，向量每 tick 讀一次。`sim.advanceManual(dt, bearing, magnitude)` 從目前位置往該方向走 magnitude × 速度（交通工具覆蓋一樣適用，步數照 countsSteps）。關掉搖桿：`leaveManual()` 從所在位置接回本圈剩下的大花。搖桿期間暫停再繼續、回家、立刻前往都會先離開手動模式再規劃 |
| + | 掃描一直顯示「遊戲畫面被遮蔽」 | 文案誤導：其實是**擷取到的畫面全黑**（遊戲在執行中偵測到錄影開始就對錄影遮蔽，螢幕本身正常），浮動列一行放不下解法 | `ScanState.WaitingForBirdsEye(blank=true)`；浮動列顯示短句並自動展開，主畫面跳出說明對話框附「開啟 Pikmin Bloom」按鈕（先把遊戲滑掉再由本 App 開啟就不會黑） |

新階段：`PatrolPhase.PARKED`、`PatrolPhase.MANUAL`。`PatrolState` 新增 `travelOverride`、`homeIsCustom`。
新事件：`ParkedAtHome`、`TravelModeChanged(mode, automatic)`、`Replanned(reason)`、`ConfigChanged(speedKmh)`。

Debug receiver 新指令：`goto --ei index`、`travel --es mode CAR|BIKE|HIGHWAY|PLANE|WALK`、
`joystick --ez on --ef bearing --ef magnitude`、`set_custom_home --es home "lat,lon"`、
`add_waypoint --es waypoint "lat,lon,name"`、`remove_waypoint --ei index`。

**尚未實機驗證**（手機不在身邊時完成）：以上全部只跑過單元測試（`LiveControlsTest`，8 個）與編譯。
下次接上手機要驗：(a) 設定改速度 → logcat `config changed`；(b) 走路中刪／加大花 → `re-planned`，畫面不跳；
(c) 自訂家 → 開始直接出現在該點、回家後 `parked`、停止才跳回；(d) 汽機車 → 速度 45 km/h、抵達自動 `back to walking`；
(e) 搖桿 → `joystick took over` / `put away`，關掉後接回路線。

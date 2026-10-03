# 阿皮小夥伴：俯瞰模式測試截圖與校準

本資料夾用於大花螢幕辨識與地圖校準。GPS 移動分析與定時步數使用獨立的合成資料測試，不依賴這些截圖；缺少圖片時，相關 GPS 與步數測試仍會執行。一般操作見 [README](../../../../../README.md)，目前測試與交接方式見 [開發環境說明](../../../../../docs/DEV_SETUP.md)。

以下保留原始裝置、日期、像素、位移與校準紀錄。PNG 留在本機，不納入 Git。

Real Pikmin Bloom screenshots used to validate `app/src/main/java/app/pikminbloom/gps/vision/`.
Captured 2026-09-10 12:09–12:26 CST on **POCO X6 Pro** (`PFQCKJPJSWJVB6JR`), screen **1220 × 2712**,
via `adb shell screencap -p`. `ground_truth.md` in this folder lists, per image, the pixel position
of every Big Flower plus the distractors on screen.

## ⚠ These files are local-only

The repository already states the policy in `.gitignore`:

> Device test screenshots contain the real location and game account; keep them local.

These PNGs show the account's real neighbourhood (Kaohsiung), the player's Mii, friend avatars and
in-game state, so a `.gitignore` in this folder excludes `*.png` for exactly the reason `/docs/test/`
is excluded. **The Kotlin tests are written to skip cleanly when the PNGs are absent**, so a fresh
clone still builds and the synthetic tests still run; only the real-image scoring is skipped, and
each such test prints why. To re-create the set, follow "How to re-capture" below.

## Simulated positions for the calibration pair

The app's own mock GPS is the ruler. Both frames were taken in bird's-eye mode with the camera
**recentered**, so the player icon sits at the same screen pixel in both — **(612, 1355)**.

| frame | logcat time | simulated position |
|---|---|---|
| `cal_a.png` | 12:25:56.769 | `22.758543, 120.337862` |
| `cal_b.png` | 12:26:17.432 | `22.759087, 120.337859` |

Difference, via `GeoMath.offsetMeters`' flat-Earth constants
(111 194.93 m/deg lat, ×cos 22.7588° for lon):

```
north = +60.490 m
east  =  -0.308 m
total =  60.49 m over 20.7 s
```

Those are the numbers hard-coded in `MapCalibrationTest.RealCalibration.MOVEMENT`.

### Deviation from the original recipe

The task called for frames **40 s** apart. The patrol app was configured at 20 km/h, so 40 s is
**230 m** — at the scale measured below that is 731 px, more than the usable overlap of the frame,
and the two frames end up sharing no features at all. That attempt is kept as
`cal_far_a.png` / `cal_far_b.png` (positions `22.760301,120.337852` → `22.762374,120.337854`,
+230.5 m north) so the failure is on the record. The pair actually used is **20.7 s / 60.5 m**,
which keeps ~90 % of the scene in view.

The patrol was paused for each capture (`--es cmd pause` / `resume`), so both frames are of a
stationary player and the displacement between them is exact rather than smeared by a 1 Hz tick.

## Calibration derived from the pair

`MapCalibrationTest > real calibration pair` prints:

```
5 hits in cal_a, 5 hits in cal_b
ok: 5 matches, residual 6.4 px
pixel translation dx=0.0 dy=194.0
metresPerPixel=0.3118  screenNorthDeg=0.3  matches=5  residual=6.4 px
```

Cross-checked against the same two PNGs measured by hand (`ground_truth.md`): translation
**(0, +192) px**, i.e. **0.3151 m/px**. The automatic and manual figures agree to **1.0 %**.

Both numbers are plausible:

* **0.31 m/px** means the 1220 × 2712 frame covers about **384 m × 854 m** of ground. For a
  street-level game map on a 6.7" phone that is the right order of magnitude, and it matches the
  fact that the 230 m move nearly cleared the view.
* **screenNorthDeg = 0.3°** says north is straight up the screen, which is what the game's own
  compass needle shows in both frames — an independent check that came from the artwork, not from
  the maths.

## How to re-capture

1. `adb shell monkey -p com.nianticlabs.pikmin -c android.intent.category.LAUNCHER 1`, wait ~30 s.
2. Tap the compass button at the bottom centre, **(610, 2592)**. A two-button pill appears.
3. Tap the pill's right icon, **(676, 2586)** — that is the bird's-eye map toggle.
   (The left icon, (545, 2586), goes back to the walk view; that is how `walk_*.png` were taken.)
4. A recenter button appears at **(1106, 2596)** once the camera has been panned.
5. For the calibration pair, with the patrol app in the FOREGROUND (Android refuses to start a
   location foreground service from the background):
   ```
   am broadcast -a app.pikminbloom.gps.DEBUG_CMD -n app.pikminbloom.gps/.debug.DebugCommandReceiver \
       --es cmd set_waypoints --es waypoints "<a point ~500 m due north>,cal"
   am broadcast ... --es cmd start
   am broadcast ... --es cmd pause
   ```
   then foreground the game, recenter, `status` + `screencap` (frame A), `resume`, wait ~11 s,
   `pause`, recenter, `status` + `screencap` (frame B), and finally `return_home` then `stop`.
   Read both positions out of `adb logcat -d -s PikminGPS | Select-String STATUS`.

## Contents

| file | what it is |
|---|---|
| `be_1.png` … `be_6.png` | bird's-eye frames at six pan states |
| `walk_1.png`, `walk_2.png` | walk-view negative controls |
| `cal_a.png`, `cal_b.png` | the calibration pair actually used (60.5 m apart) |
| `cal_far_a.png`, `cal_far_b.png` | the rejected 40 s / 230 m attempt |
| `ground_truth.md` | hand-read flower positions and measured colour statistics |

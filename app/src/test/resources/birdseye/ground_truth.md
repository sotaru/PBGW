# 俯瞰模式截圖：人工標註資料

Captured 2026-09-10 12:09–12:26 CST on the POCO X6 Pro (`PFQCKJPJSWJVB6JR`), Pikmin Bloom,
screen **1220 × 2712**. All coordinates below are **full-resolution pixels of the PNG**,
origin top-left, read off by hand from 1:1 crops.

以下座標是歷史截圖的像素標註，不是實機 GPS 位置。GPS 移動判斷另由 `GpsMotionAnalyzerTest` 驗證；本次保留原有像素、顏色統計與標註結果。資料來源與校準方式見 [README](README.md)，目前操作見 [專案說明](../../../../../README.md)。

For each Big Flower two points are given:

* **bloom** — the centre of the coloured bloom head (what a blob detector finds).
* **anchor** — the base of the stem where it meets the ground (what actually maps to a lat/lon).

The vertical bloom→anchor offset is consistently **80–110 px** at this zoom
(≈ 25–35 m at the measured 0.315 m/px — see `README.md`), which is exactly why the
ground anchor matters and the bloom centroid cannot be used directly.

## How the view was reached

Game bottom bar → compass button (610, 2592) → a two-button pill appears at the bottom
centre: **left (545, 2586) = walk view**, **right (676, 2586) = bird's-eye map**.
A **recenter** button appears at (1106, 2596) whenever the camera has been panned off the player.

## UI chrome present in bird's-eye mode

| element | approx. box (px) |
|---|---|
| Android status bar | full width, y 0–110 |
| north compass button | x 1044–1156, y 164–276 |
| bottom two-button pill | x 415–800, y 2510–2660 |
| bottom-left back button | x 55–185, y 2525–2655 |
| bottom-right recenter button (only when panned) | x 1040–1175, y 2530–2660 |

## Distractors present

* **Mushroom clusters** — 4–6 red/blue/purple/yellow caps drawn together, ~200 px wide.
  Every cluster carries a **dark teal "person count" pill** (H≈152, S≈0.40, V≈0.50) with a
  white 👤N glyph, and a **pie-timer disc** (white circle with a red sector) immediately to its
  left. Both sit ~60–110 px above the cluster.
* **Player avatar pill** — white circle with a face, ~110 px across, with a white/green
  triangular pointer below it.
* **Faint white circles** — the flowers' own radius rings, very low contrast.
* **Flower carpets** — dense speckle of tiny planted flowers (red / lilac / yellow), each only a
  few px across. These are the main source of false positives.

## be_1.png  (recentered, flower-rich; identical scene to be_3.png)

player avatar pill ≈ (608, 1355)

| # | flower | bloom (x,y) | anchor (x,y) |
|---|---|---|---|
| 1 | pale-blue daisy, orange centre | 163, 1418 | 163, 1500 |
| 2 | pale-blue daisy, orange centre | 90, 1460 | 88, 1547 |
| 3 | pale violet bell flower | 138, 1575 | 133, 1682 |
| 4 | magenta carnation | 628, 1618 | 622, 1725 |
| 5 | pale-blue daisy, orange centre | 795, 1855 | 795, 1940 |

Mushroom clusters (must NOT be reported): ≈ (760, 1450), (860, 1690), (900, 2110).
Their badges: ≈ (755, 1380), (843, 1608), (880, 2035).

## be_2.png  (panned north-east, mostly empty grass + roads + river)

player avatar pill ≈ (1090, 2265)

| # | flower | bloom (x,y) | anchor (x,y) |
|---|---|---|---|
| 1 | pale-blue daisy | 930, 2355 | 925, 2445 |
| 2 | pale-blue daisy | 852, 2405 | 848, 2492 |
| 3 | pale violet bell flower | 910, 2522 | 908, 2625 |

No mushrooms in frame. Flower 3 sits low enough that its anchor is behind the bottom UI row.

## be_3.png  (recentered again — same scene as be_1, taken ~40 s later)

Same five flowers as `be_1.png`, within a few px. Kept as a repeatability check.

## be_4.png  (panned north, almost empty)

player avatar pill ≈ (610, 2300)

| # | flower | bloom (x,y) | anchor (x,y) |
|---|---|---|---|
| 1 | pale-blue daisy | 167, 2432 | 165, 2520 |
| 2 | pale-blue daisy | 88, 2478 | 85, 2565 |

Mushroom cluster at the bottom edge ≈ (740, 2620) with badge ≈ (700, 2560).
Flower 2's stem base is partly hidden behind the bottom-left back button.

## be_5.png  (panned south-east — the richest frame, 5 flowers + 4 mushroom clusters)

player avatar pill ≈ (1128, 985)

| # | flower | bloom (x,y) | anchor (x,y) |
|---|---|---|---|
| 1 | pale-blue daisy | 931, 1408 | 925, 1475 |
| 2 | purple starburst | 698, 1495 | 705, 1570 |
| 3 | pale-blue daisy | 520, 1573 | 526, 1652 |
| 4 | pale-blue daisy | 946, 1683 | 950, 1770 |
| 5 | white daisy, yellow centre | 530, 2190 | 527, 2270 |
| 6 | pink daisy, yellow centre | 937, 2285 | 933, 2372 |
| — | violet bloom, **clipped by the bottom edge** | 921, 2690 | off-frame |

**Correction, recorded honestly:** flowers 6 and the clipped violet were *not* in my first pass over
this screenshot. The detector flagged both, I scored them as false positives, and only on opening
the debug render did I see they are real Big Flowers that I had missed by eye. Flower 6 is now
counted as ground truth; the clipped violet is in the test's `ignored` list, because it is a genuine
flower whose stem base is below the frame and therefore cannot be placed — neither a hit nor a miss.

Mushroom clusters: red ≈ (1010, 1880), blue ≈ (920, 2070), purple ≈ (1090, 2065),
red ≈ (100, 2380). Badges ≈ (1045, 1800), (955, 1992), (95, 2295).

## be_6.png  (panned west over the river — NO Big Flowers)

Contains only grass, roads, the river, one yellow mushroom cluster ≈ (800, 2000) with a "👤1"
badge ≈ (790, 1930), and a red mushroom cluster **clipped by the left edge** ≈ (20, 1930) whose
badge is cut off with it. Player avatar pill ≈ (112, 1880).

**This frame is a pure negative control: the correct answer is zero flowers.**
It is also the frame that produces the capture set's only remaining false positive — see the note
on edge-clipped mushrooms in the report.

## cal_a.png / cal_b.png  (the calibration pair — see README.md)

Same scene as `be_1.png`, camera recentered in both frames, player pill at **(612, 1355)**
in both.

| # | flower | cal_a bloom | cal_a anchor | cal_b bloom | cal_b anchor |
|---|---|---|---|---|---|
| 1 | pale-blue daisy | 155, 1490 | 150, 1575 | 155, 1680 | 152, 1765 |
| 2 | pale-blue daisy | 78, 1536 | 75, 1620 | 78, 1728 | 75, 1812 |
| 3 | pale violet | 130, 1650 | 128, 1755 | 128, 1843 | 127, 1945 |
| 4 | magenta carnation | 620, 1692 | 620, 1795 | 620, 1880 | 620, 1985 |
| 5 | pale-blue daisy | 786, 1930 | 782, 2018 | 786, 2126 | (off / low) |

Hand-measured feature translation A→B: **(0, +192) px**, spread ±4 px over the five flowers.

## walk_1.png / walk_2.png  (negative controls, walk view)

Ordinary 3-D walk view: dense flower carpet filling the whole frame, a road band, the avatar
in the middle, and the right-hand button column. **No Big Flower is identifiable**, and the
detector is not expected to produce meaningful output here — these exist to show what the
detector must never be pointed at. `walk_2.png` additionally has the friends strip open.

## cal_far_a.png / cal_far_b.png  (rejected calibration attempt, kept for the record)

Taken exactly 40 s apart as originally specified. At the app's configured 20 km/h that is
**230 m**, which at 0.315 m/px is 731 px — more than the usable overlap of the frame, so the
two frames share almost no features and no calibration can be derived from them.
Superseded by `cal_a`/`cal_b` (11 s, 60 m).

時間註記：11 秒是重拍步驟中的移動等待時間；README 的截圖時間為 12:25:56.769 與 12:26:17.432，相差約 20.7 秒。校準位移與取樣時間請以來源紀錄為準，原句保留作為當時的操作紀錄。

## Measured colours (be_1, HSV, H in degrees, S/V in 0..1)

| region | H p10 / p50 / p90 | S p10 / p50 / p90 | V p10 / p50 / p90 |
|---|---|---|---|
| open grass | 80 / 106 / 113 | 0.27 / 0.47 / 0.53 | 0.67 / 0.71 / 0.80 |
| dense flower carpet | 63 / 106 / 117 | 0.17 / 0.42 / 0.49 | 0.63 / 0.69 / 0.81 |
| daisy centre (orange) | 32 / 39 / 206 | 0.30 / 0.60 / 0.64 | 0.78 / 0.91 / 0.96 |
| violet bloom core | 256 / 265 / 272 | 0.08 / **0.17** / 0.33 | 0.78 / 0.82 / 0.89 |
| magenta carnation | 110 / 312 / 319 | 0.35 / 0.66 / 0.70 | 0.44 / 0.59 / 0.71 |
| mushroom cap (red) | 5 / 355 / 358 | 0.48 / 0.62 / 0.68 | 0.77 / 0.84 / 0.90 |
| badge pill background | 148 / 153 / 156 | 0.03 / 0.40 / 0.43 | 0.49 / **0.50** / 0.94 |
| stem (on) | 96 / 112 / 121 | 0.19 / 0.45 / 0.55 | 0.61 / **0.66** / 0.77 |
| grass 12 px beside the same stem | 94 / 113 / 118 | 0.23 / 0.45 / 0.48 | 0.63 / **0.64** / 0.74 |

Two of these numbers drove the design:

1. **The violet bloom is only S≈0.17.** A saturation-only gate tuned to reject grass
   (S up to 0.53) cannot see it. The separating feature is hue, not saturation: grass never
   leaves 63–121°, so "hue far from the grass band" is the primary test and saturation is only
   a weak secondary gate.
2. **A stem is statistically identical to the grass beside it** (ΔV ≈ 0.02, same hue and
   saturation). Absolute HSV thresholds cannot find a stem; only local contrast can, and even
   that is marginal — in the final tuning the stem scan succeeds on roughly a quarter of hits and
   the rest fall back to a fixed offset below the bloom centroid. See `README.md`.

## Measured bloom-centre → stem-base offsets

The 15 flowers whose stem base is fully in frame:

```
be_1   82  87 107 107  85
be_5   67  75  79  87  80
cal_a  85  84 105 103  88
```

median **85 px**, range 67–107. At the measured 0.315 m/px that is **27 m**, with a ±7 m spread —
which is why the ground anchor is not optional: using the bloom centroid directly would put every
waypoint ~27 m north of the real flower, and Pikmin Bloom's flower circle is only 40 m across.

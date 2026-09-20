# iOS Home Screen (Calendar) — Factual Reference

Source: the shipped iOS SwiftUI app at `/Users/cov/JustADiary` (read-only reference; the Android
project lives at `/Users/cov/JustGDiary`). Every number and rule below carries a `file:line`
citation. **Where code and docs/mockups disagree, the shipped code is authoritative** and the
divergence is recorded in §8.

Reference device: **iPhone 18 Pro** — portrait `402 × 874 pt`, landscape `874 × 402 pt`
(measured safe areas: portrait `T62 L0 B34 R0`, landscape `T0 L62 B20 R62`
`docs/design-system.md:316-317`, `docs/adaptive-layout-plan.md:149`).

Files read for this document:

| Alias used below | Path |
|---|---|
| `HomeView` | `/Users/cov/JustADiary/JustDiary/Views/Home/HomeView.swift` |
| `CalendarLayout` | `/Users/cov/JustADiary/JustDiary/Views/Home/CalendarLayout.swift` |
| `CalendarGrids` | `/Users/cov/JustADiary/JustDiary/Views/Home/CalendarGrids.swift` |
| `CalendarPaneViews` | `/Users/cov/JustADiary/JustDiary/Views/Home/CalendarPaneViews.swift` |
| `DayContentView` | `/Users/cov/JustADiary/JustDiary/Views/Home/DayContentView.swift` |
| `MorphViews` | `/Users/cov/JustADiary/JustDiary/Views/Home/MorphViews.swift` |
| `DragPagePager` | `/Users/cov/JustADiary/JustDiary/Views/Home/DragPagePager.swift` |
| `RootView` | `/Users/cov/JustADiary/JustDiary/Views/RootView.swift` |
| `Components` | `/Users/cov/JustADiary/JustDiary/Views/Components/Components.swift` |
| `DesignSystem` | `/Users/cov/JustADiary/JustDiary/Views/Components/DesignSystem.swift` |
| `AdaptiveLayout` | `/Users/cov/JustADiary/JustDiary/Views/Components/AdaptiveLayout.swift` |
| `DynamicType` | `/Users/cov/JustADiary/JustDiary/Views/Components/DynamicType.swift` |
| `Theme` | `/Users/cov/JustADiary/JustDiary/Services/Theme.swift` |
| `HomeViewModel` | `/Users/cov/JustADiary/JustDiary/ViewModels/HomeViewModel.swift` |
| `L10n` | `/Users/cov/JustADiary/JustDiary/Services/L10n.swift` |
| `Assets` | `/Users/cov/JustADiary/JustDiary/Resources/Assets.xcassets/<name>.colorset/Contents.json` |
| `design-system` | `/Users/cov/JustADiary/docs/design-system.md` |
| `adaptive-layout-plan` | `/Users/cov/JustADiary/docs/adaptive-layout-plan.md` |

Three files outside the enumerated list were opened only because the required content (diary entry
card body, block styles, inline images) physically lives there: `Views/Diary/MediaViews.swift`
(`DiaryPartsView`), `Views/Diary/ReadTextView.swift` (`ReadTextView`), and
`Views/Diary/RichTextEngine.swift` (`EditorBlockStyle` / `PartsCodec`). They are labelled
`[extra]` at each citation.

---

## 1. Global tokens

### 1.1 Color tokens (light / dark hex)

Brand seed is a compile-time constant: `Color(hex: 0x2563EB)` → **#2563EB** (`Theme.swift:4`,
exposed by `Theme.primary()` at `Theme.swift:6`; matches `docs/design-system.md:206` "品牌色是刻意固定的").

`onPrimary` is not an asset: `UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: 0x191C20) : .white }`
(`Theme.swift:9`) → light **#FFFFFF**, dark **#191C20**.

All other tokens are asset-catalog dynamic colors fetched by name (`Theme.swift:30-44`) and
defined in `Assets`:

| Token | Light | Dark | Accessor : line |
|---|---|---|---|
| `bg` | **#FAF9FD** | **#101318** | `Theme.bg()` `Theme.swift:30` |
| `onSurface` | **#191C20** | **#E1E2E8** | `Theme.onSurface()` `Theme.swift:31` |
| `onSurfaceVariant` | **#44474F** | **#C5C6D0** | `Theme.onSurfaceVariant()` `Theme.swift:32` |
| `outlineVariant` | **#C5C6D0** | **#44474F** | `Theme.outlineVariant()` `Theme.swift:33` |
| `glassDim` | **#FFFFFF @ 45.1 %** | **#262B36 @ 50.2 %** | `Theme.glassDim()` `Theme.swift:34` |
| `glassBorder` | **#FFFFFF @ 54.9 %** | **#FFFFFF @ 20.0 %** | `Theme.glassBorder()` `Theme.swift:35` |
| `flowLight` | **#FFFFFF @ 80 %** | **#FFFFFF @ 34.9 %** | `Theme.flowLight()` `Theme.swift:36` |
| `blobA` | **#93C5FD @ 50.2 %** | **#1E40AF @ 50.2 %** | `Theme.blobA()` `Theme.swift:37` |
| `blobB` | **#BFDBFE @ 60.0 %** | **#1E3A8A @ 54.9 %** | `Theme.blobB()` `Theme.swift:38` |
| `blobC` | **#E0E7FF @ 43.1 %** | **#312E81 @ 43.1 %** | `Theme.blobC()` `Theme.swift:39` |
| `shadowColor` | **#000000 @ 7.8 %** | **#000000 @ 14.9 %** | `Theme.shadowColor()` `Theme.swift:40` |
| `primaryContainer` | **#DBEAFE** | **#1E3A8A** | `Theme.primaryContainer()` `Theme.swift:12` |
| `quoteBg` | **#E4EAF9** | **#2B3344** | `Theme.quoteBgUIColor()` `Theme.swift:44` |
| `AccentColor` | **#2563EB** (single appearance, sRGB 0.145/0.388/0.922) | — | `Assets/AccentColor.colorset` |

Named-only, home-relevant accessors: `Theme.shadowColor()` is used by the future-date toast
(`HomeView.swift:60`); `Theme.outlineVariant()` by the split divider (`HomeView.swift:379`), the
Canvas row rules (`CalendarGrids.swift:208`, `:285`) and the card border (`Components.swift:135`);
`Theme.glassBorder()` only by `GlassIconBadge` (`Components.swift:637`).

Colors synthesized from tokens on the home screen (no new asset):

- `Theme.primary()` at `cellAlpha` — selected-day disc (`CalendarGrids.swift:87-89`) and today ring (`:93-96`).
- `Theme.onSurface().opacity(0.5)` — out-of-month day number (`CalendarGrids.swift:111`).
- `Theme.onSurfaceVariant()` vs `.opacity(0.6)` — weekday header weekday vs weekend (`CalendarGrids.swift:16`).
- `Theme.onSurfaceVariant().opacity(0.8)` — the lunar full label in the day-title row (`HomeView.swift:564`) and in `DayPane` (`CalendarPaneViews.swift:167`).
- `Theme.outlineVariant().opacity(0.35 * dividerAlpha)` — month row rules (`CalendarGrids.swift:208`).
- `Theme.outlineVariant().opacity(0.4)` — split-pane 0.5 pt vertical rule (`HomeView.swift:379`).
- `Theme.glowColor()` = `seed.opacity(0.15)` (`Theme.swift:14`) — primary button shadow (`Components.swift:219`).
- `Theme.flowMaskColor()` = `seed.opacity(0.05)`, `Theme.flowLightColor()` = `seed.opacity(0.40)` (`Theme.swift:16-18`) — `FlowLightOverlay`, which is **never instantiated** (dead; §8.4).

Hex parsing: `Color(hex:)` / `UIColor(hex:)` read `RRGGBB[AA]` and treat `alpha == 0` as `1.0`
(`Theme.swift:48-54`, `:62-68`).

### 1.2 Radii (`DesignSystem.swift:13-34`)

| Token | Value | Home-screen use |
|---|---|---|
| `Radius.card` | **20** | diary entry card (`DayContentView.swift:105`), flow-light overlay (`Components.swift:656`) |
| `Radius.panel` | **26** | floating panels (not used by home) (`DesignSystem.swift:17`) |
| `Radius.field` | **14** | inset sub-surfaces (not used by home) (`DesignSystem.swift:20`) |
| `Radius.image` | **14** | inline diary image (`MediaViews.swift:30`), rounded attachment in the attributed pipeline (`RichTextEngine.swift:829`) |
| `Radius.badge` | **12** | icon badges (not used by home) (`DesignSystem.swift:24`) |
| `Radius.bar` | **4** | thick strokes (not used by home) (`DesignSystem.swift:26`) |
| `Radius.hairline` | **1** | **"has a diary" underline corner radius** (`CalendarGrids.swift:131`, `DesignSystem.swift:28`) |
| `concentric(outer:inset:)` | `max(0, outer - inset)` | nesting rule (`DesignSystem.swift:31-33`) |

### 1.3 Spacing (`DesignSystem.swift:40-46`)

| Token | Value | Home-screen use |
|---|---|---|
| `Spacing.card` | **12** | diary card padding (`DayContentView.swift:104`) |
| `Spacing.rowIcon` | **12** | icon-to-text gap (not used by home) (`DesignSystem.swift:43`) |
| `Spacing.hitTarget` | **44** | header back capsule `minHeight` (`HomeView.swift:127`) |

Ad-hoc home spacing (not tokens): header `HStack` spacing **10** (`HomeView.swift:110`), header
title `HStack` spacing **6** (`:115`), header horizontal padding **16** / top **12** (`:37-38`),
header `minHeight` **52** (`:148`), day-content `VStack` spacing **16** (`DayContentView.swift:20`),
diary card inner `VStack` spacing **10** (`DayContentView.swift:76`), meta `VStack` spacing **4**
(`DayContentView.swift:78`), parts `VStack` spacing **8** (`MediaViews.swift:62`),
`DayPane` heading `HStack` spacing **10** (`CalendarPaneViews.swift:156`), page padding **16**
(`AdaptiveLayout.swift:74`), landscape `topPad` **8** (`HomeView.swift:328`), landscape pane
`gutter` **16** (`HomeView.swift:346`).

### 1.4 Type scale (`DesignSystem.swift:55-86`)

| Token | pt | Home-screen use |
|---|---|---|
| `display` | **32** | month big title (`CalendarGrids.swift:310` default), year title (`CalendarGrids.swift:355`) |
| `pageTitle` | **24** | **landscape** month title (`HomeView.swift:371`) |
| `sheetTitle` | 17 | not home |
| `cardTitle` | 20 | not home |
| `headerTitle` | **18** | `‹ <title>` header capsule (`HomeView.swift:119`) |
| `statValue` | 18 | not home |
| `rowTitle` | **15** | day-title row (`HomeView.swift:553`), `DayPane` heading (`CalendarPaneViews.swift:158`), empty-state text (`DayContentView.swift:56`) |
| `rowSub` | 13 | not home |
| `rowValue` | 15 | not home |
| `sectionTitle` | 13 | not home |
| `chip` | **14** | `InfoCapsule` text (`Components.swift:385`) |
| `badge` | 12 | not home |
| `meta` | **13** | future-date toast (`HomeView.swift:48`) |
| `caption` | **12** | lunar label in day-title row (`HomeView.swift:563`), `DayPane` lunar (`CalendarPaneViews.swift:166`), diary-card time/location lines (`DayContentView.swift:88`) |

Numeric literals used on the home screen outside the token table (all authoritative):

- header chevron **16 pt semibold** (`HomeView.swift:117`)
- mini-month card label **15 pt** semibold, bold + `Theme.primary()` when the card is the current
  month (`CalendarGrids.swift:333,336`)
- mini day number (`CalendarLayout.miniDayFont`) **clamp 9…14**, formula below (`CalendarLayout.swift:176-178`)
- mini lunar font **6** with `lunarAlpha = 0` (`CalendarLayout.swift:234-235`)
- month/week day number **20 pt medium**, selected day **semibold** (`CalendarLayout.swift:152,161`;
  `CalendarGrids.swift:148`)
- month/week lunar **11 pt medium** (`CalendarLayout.swift:153,162`)
- weekday header **clamp 11…14**, `min(max(cellW * 0.26, 11), 14)` (`CalendarLayout.swift:143`)
- empty-state icon **26 pt** (`DayContentView.swift:53`)
- `DayPane`-supplied `DayContentView` uses the same sizes; the offline share/read pipeline uses
  28/22/17/15 for h1/h2/p/quote (`RichTextEngine.swift:26-33`)

### 1.5 Dynamic Type model and ceilings

Model (`DesignSystem.swift:107-219`):

- `RootView` publishes the system `DynamicTypeSize` into `\.diaryDynamicTypeSize` (`RootView.swift:17,65`; key + default `.large` at `DynamicType.swift:17-27`). The `fullScreenCover` re-injects it explicitly (`RootView.swift:97`).
- `.diaryFont(_:weight:)` → `Font.system(size: DynamicTypeMetrics.scaled(size, for: typeSize))` (`DynamicType.swift:29-37,52-54`).
- `.diaryCalendarFont(_:weight:)` → **half-rate** growth: `size * calendarMultiplier` (`DynamicType.swift:39-48,61-63`).
- `DynamicTypeMetrics.textStyle(for:)` maps a design size to the nearest Apple style by default size: `34+ → .largeTitle`, `28..<34 → .title1`, `22..<28 → .title2`, `20..<22 → .title3`, `17..<20 → .body`, `16..<17 → .callout`, `15..<16 → .subheadline`, `13..<15 → .footnote`, `12..<13 → .caption1`, else `.caption2` (`DesignSystem.swift:143-156`).
- `multiplier` returns exactly **1** at `.large` (unchanged design), else `min(ceiling, max(1, UIFontMetrics(forTextStyle:).scaledValue(for:)/size))` (`DesignSystem.swift:190-196`).

Ceilings per text style (`DesignSystem.swift:124-136`):

| Style | Ceiling |
|---|---|
| `.largeTitle` | **1.35** |
| `.title1` | **1.40** |
| `.title2`, `.title3` | **1.45** |
| `.body` | **1.50** |
| `.callout` | **1.55** |
| `.subheadline` | **1.60** |
| `.footnote` | **1.65** |
| `.caption1` | **1.70** |
| default (`.caption2`) | **1.75** |

AX5 outcomes for the design sizes this app uses (`DesignSystem.swift:119-120`, doc table at
`design-system.md:55-57`): 32 → **44.8**, 28 → **39.2**, 24 → **34.8**, 22 → **31.9**, 20 → **29**,
18 → **27**, 17 → **25.5**, 16 → **24.8**, 15 → **24**, 13 → **21.5**, 12 → **20.4**, 11 → **19.3**.

Calendar dampening (`DesignSystem.swift:217-219`):
`calendarMultiplier = 1 + (multiplier(for: 15) - 1) * 0.5` — i.e. half the `.subheadline` growth.
It is applied **only to the Canvas-drawn day number and lunar text**, and only to their font sizes:
`MonthCanvas.drawnMetrics` and `WeekRowCanvas.drawnMetrics` (`CalendarGrids.swift:185-191`,
`:270-276`). Cell geometry, circle diameter and underline are computed from the *unscaled*
`DayMetrics`, so the drawn text can outgrow its cell at large sizes (divergence §8.3).
The weekday header uses the same damped factor via `.diaryCalendarFont` (`CalendarGrids.swift:15`).
The month/lunar font sizes are further clamped by cell height: `MonthPane.dayFont(for:) = min(20, max(13, cellH * 0.34))`
and `lunarFont(for:) = min(11, max(9, cellH * 0.18))` (`CalendarPaneViews.swift:101-107`).

### 1.6 Animations

| Name | Definition | : line |
|---|---|---|
| morph duration | `0.6 s`; DEBUG `SLOW_MORPH=1` or `-slow-morph` → `3.0 s` | `CalendarLayout.swift:103-109` |
| `morphSlideAnimation` | `.timingCurve(0.2, 0.75, 0.3, 1.0, duration: morphDuration)` | `CalendarLayout.swift:121-123` |
| `morphAnimation` (year↔month) | identical to `morphSlideAnimation` | `CalendarLayout.swift:113` |
| `reducedMorphAnimation` | `.linear(duration: 0.18)` | `CalendarLayout.swift:117` |
| day selection snap | `.snappy(duration: 0.3)` (today in month mode) | `HomeView.swift:280` |
| landscape day select | `.snappy(duration: 0.25)` | `HomeView.swift:504` |
| pager page settle | `.snappy(duration: 0.3)` | `DragPagePager.swift:79,94` |
| toast in/out | `.snappy(duration: 0.25)` | `HomeView.swift:305,311` |
| `Animation.diaryQuick` | `.easeOut(duration: 0.22)` | `Components.swift:7` |
| `Animation.diaryStandard` | `.easeInOut(duration: 0.3)` (header capsule opacity vs `mode`) | `Components.swift:8`, `HomeView.swift:138` |
| `FlowLightOverlay` cycle | 4.0 s loop, 60 fps `TimelineView`, pauses after 10 s (`paused`) or when reduce-motion is on | `Components.swift:658-686` |
| image-preview dismiss spring | `.diaryQuick` on snap-back | `MediaViews.swift:167` |

With `accessibilityReduceMotion` on: `zoomAnimation` and `slideAnimation` both become
`reducedMorphAnimation` (0.18 s linear) (`HomeView.swift:161-167`); the geometry still travels —
it is a shorter, non-sweeping move, not a cross-fade only. Reduce-motion is also a `TimelineView`
pause condition in `FlowLightOverlay` (`Components.swift:658`).

---

## 2. Portrait structure (all three modes)

### 2.1 Global portrait container

- `HomeView.body` is a `GeometryReader`; its `size` inside a `TabView` page equals the full
  screen because `.ignoresSafeArea(edges: .bottom)` is applied (`HomeView.swift:28-29,44`):
  `402 × 874`.
- Non-split branch: `header` with `.padding(.horizontal, 16)` / `.padding(.top, 12)`
  (`HomeView.swift:36-38`), then `calendarArea(size:)`.
- `calendarArea` height: **`h = max(320, size.height - 64)`** (`HomeView.swift:415`) → 874 − 64 =
  **810 pt** for iPhone 18 Pro portrait. Width `w = size.width = 402`.
- `header` frame: `.frame(minHeight: 52)` (`HomeView.swift:148`); `HStack(spacing: 10)` (`:110`);
  total header slot = 12 (top pad) + 52 = **64 pt**, which is exactly the `- 64` above.
- Layers are a `ZStack(alignment: .top)` of `yearLayer`, `monthLayer`, `weekLayer`, each
  `opacity(mode == … ? 1 : 0)` and `allowsHitTesting(mode == …)` (`HomeView.swift:422-433`),
  plus the active morph view; the whole stack is `.frame(width: w, height: h).clipped()`
  (`HomeView.swift:456-457`). While `morphing` (either morph non-nil) the three base layers are
  **not built at all** (`HomeView.swift:423`).
- Bottom: `.overlay(alignment: .bottom)` future-date toast, `padding(.horizontal, 16)`,
  `padding(.vertical, 10)`, `.padding(.bottom, 24)`, glass capsule, `shadow(radius: 12, y: 4)`,
  `sensoryFeedback(.warning)`, auto-dismiss after **2.5 s** (`HomeView.swift:45-64,303-315`).

### 2.2 Month mode (portrait)

`monthLayer(w:h:)` (`HomeView.swift:477-498`):
`DragPagePager(axis: .vertical, pageSize: h)` over `CalendarLayout.allMonthKeys`, then `MonthPane`.

`MonthPane` vertical budget (`CalendarPaneViews.swift:39-73`):

| Slot | Formula / value | : line |
|---|---|---|
| big title | `CalendarLayout.bigTitleH` = **72** | `CalendarLayout.swift:126`, `CalendarPaneViews.swift:29,35` |
| weekday header | `CalendarLayout.weekdayHeaderH` = **30** | `CalendarLayout.swift:125`, `CalendarPaneViews.swift:30,36` |
| grid origin Y | 72 + 30 = **102** | `CalendarPaneViews.swift:37` |
| rows | `CalendarLayout.displayedWeeks(inMonth:ws:).count` (6 when the month needs 6 rows, **5** when the trailing row has no day in the month) | `CalendarLayout.swift:322-335`, `CalendarPaneViews.swift:40` |
| cell height | `CalendarLayout.monthCellH(areaH:) = (areaH - 72 - 30) / 6` — **always divided by 6**, even for a 5-row month | `CalendarLayout.swift:145-147`, `HomeView.swift:492` |
| cell width | `width / 7` = 402/7 = **57.43** | `CalendarLayout.swift:141` |
| canvas frame | `height = rowH * weeks.count` | `CalendarPaneViews.swift:66` |
| tail | `Spacer(minLength: 0)` then `.frame(width:height: areaH, alignment: .top).clipped()` | `CalendarPaneViews.swift:69-72` |

For 402 × 874: `areaH = 810`, `cellH = (810 − 102) / 6 = 708 / 6 =` **118 pt**;
`cellW = 57.43 pt`; 5-row months render `5 × 118 = 590 pt` of grid and leave 118 pt of empty space
above the tab bar.

Month metrics (`MonthPane.monthMetrics`): `cellW 57.43`, `cellH 118`, `dayFont 20`, `lunarFont 11`,
`lunarAlpha 1` if `AppLanguage.isZh` else `0`, `dividerAlpha 1` (`CalendarPaneViews.swift:75-82`,
`HomeView.swift:493`; `showsLunar = AppLanguage.isZh` at `HomeView.swift:23`).

Adjacent-month days are **not drawn and not tappable** in portrait month mode: `showAdjacent: false`
(`CalendarPaneViews.swift:64`) makes both the draw loop (`CalendarGrids.swift:211-214`) and
`dayAt(point:)` (`CalendarGrids.swift:249-251`) skip them.

### 2.3 Week mode (portrait)

`weekLayer(w:h:)` (`HomeView.swift:511-544`) is a `VStack(spacing: 0)`:

| Slot | Value / formula | : line |
|---|---|---|
| weekday header | `CalendarLayout.weekdayHeaderH` = **30**, `cellW = w/7` | `HomeView.swift:513-514` |
| week strip | `CalendarLayout.weekStripH` = **68** (always a single week row) | `CalendarLayout.swift:133`, `HomeView.swift:538-540` |
| strip bottom | `weekdayHeaderH + weekStripH` = **98** | `HomeView.swift:547` |
| day-title row | `CalendarLayout.dayTitleH` = **40**, preceded by a `Divider()` and followed by a `Divider()` | `CalendarLayout.swift:134`, `HomeView.swift:550,569-570` |
| day content | `h - stripBottom`, i.e. 810 − 98 = **712** for iPhone 18 Pro | `HomeView.swift:578` |

Day-title row contents (`HomeView.swift:551-569`): `Text(L10n.weekHeaderTitle(selectedDate))`
(`rowTitle` 15 semibold, `lineLimit(1)`, `minimumScaleFactor(0.6)`, `allowsTightening`);
`Spacer()`; when Chinese, `Text(Lunar.fullLabel(selectedDate))` at `caption` 12 in
`onSurfaceVariant().opacity(0.8)`; `padding(.horizontal, 20)`.
`weekHeaderTitle` renders `index_week_head` = `2026年9月15日 周二` (zh) / `Tue, Sep 15, 2026` (en)
(`L10n.swift:185-192`, `design-system.md:165`).

The strip page is a `WeekRowCanvas(week: CalendarLayout.weekOf(start, ws:))` with
`weekMetrics(width:lunar:)` → `cellW = w/7`, `cellH = 68`, `dayFont 20`, `lunarFont 11`,
`lunarAlpha = lunar ? 1 : 0`, `dividerAlpha = 0` (`CalendarLayout.swift:158-165`).
`WeekRowCanvas` draws the whole 7-day window **including adjacent-month days** at cell alpha
`adjacentAlpha` (default 1) (`CalendarGrids.swift:287-291`).
Horizontal swipe = previous/next week: `DragPagePager(axis: .horizontal, pageSize: w)` over
`allWeekKeys(weekStart:)`, and the handlers add the day delta to `selectedDate`
(`HomeView.swift:515-526`). Tap a day in the strip = `Haptics.tap()` + `vm.select(day)` +
`reloadDayBlocks()` (`HomeView.swift:533-536`).

### 2.4 Year mode (portrait)

`yearLayer(w:h:)` (`HomeView.swift:460-475`) = `DragPagePager(axis: .vertical, pageSize: h,
disabled: morphing)` over `CalendarLayout.allYears` (1900…2100, `CalendarLayout.swift:267`),
page = `YearPageView`.

`YearPageView` geometry (`CalendarGrids.swift:340-439`, formulas in `CalendarLayout.swift:136-243`):

| Slot | Formula | Value at 402 × 810 |
|---|---|---|
| year title slot | `yearTitleH` = **62** | 62 (`CalendarLayout.swift:136`) |
| title row height | `yearTitleH - 8` = **54**, `HStack(spacing: 8)`, `padding(.horizontal, 20)`, then `Divider().padding(.horizontal, 20)` | `CalendarGrids.swift:367-369` |
| title text | `L10n.fmt("date_year", year)` at `TypeSize.display` 32 bold, `Theme.primary()` | `CalendarGrids.swift:354-358` |
| zodiac | (zh only) `Lunar.yearZodiacLabel` at `caption` 12, `onSurfaceVariant().opacity(0.7)` — computed from June 1 of that year | `CalendarGrids.swift:360-365` |
| grid top pad | **8** | `CalendarGrids.swift:389` |
| grid horizontal pad | `yearPad` = **16** | `CalendarLayout.swift:137`, `CalendarGrids.swift:390` |
| card size | `width = (W − 16·2 − 6·2)/3`, `height = (H − 62 − 8 − 6·3)/4` | `CalendarLayout.swift:167-170` |
| spacing | `yearSpacing` = **6** | `CalendarLayout.swift:139` |
| card rect | `x = 16 + col·(w + 6)`, `y = 62 + 8 + row·(h + 6)` | `CalendarLayout.swift:182-190` |
| mini grid rect | card inset by `miniPad` = **6** on all sides, pushed down by `miniTitleH` = **17** | `CalendarLayout.swift:192-201` |
| mini title rect | `x = card.minX + 6`, `y = card.minY + 6`, `w = card.width − 12`, `h = 17` | `CalendarLayout.swift:213-219` |
| card-internal offsets | title `(6, 6)`; grid `(6, 6 + 17) = (6, 23)` | `CalendarLayout.swift:222-223` |

For 402 × 810: `card = ((402 − 32 − 12)/3, (810 − 62 − 8 − 18)/4) =` **(119.33, 180.5)**;
`grid = (119.33 − 12, 180.5 − 12 − 17) =` **(107.33, 151.5)**;
`miniCellW = 107.33/7 =` **15.33**, `miniCellH = 151.5/6 =` **25.25**.

Mini-month drawing:
- `miniMetrics` (`CalendarLayout.swift:227-237`): `cellW = grid.width/7`, `cellH = grid.height/6`,
  `dayFont = miniDayFont(cellW:cellH:)`, `lunarFont = 6`, `lunarAlpha = miniLunarHidden = 0`,
  `dividerAlpha = 0`.
- `miniDayFont(cellW:cellH:) = min(14, max(9, min(cellW * 0.95, cellH * 0.5)))`
  (`CalendarLayout.swift:176-178`) → `min(14, max(9, min(14.56, 12.63))) =` **12.63 pt** at 402 pt width.
- `weeks(inMonth:ws:)` here returns a **fixed 6 rows** (`CalendarLayout.weeks`, `CalendarLayout.swift:337-356`), unlike the portrait grid which uses `displayedWeeks`.
- Mini-month content is not hit-testable; the whole card is one `onTapGesture` → `onSelectMonth(month)` (`CalendarGrids.swift:381-384`).
- The current month's label is bold + `Theme.primary()`, other months semibold + `onSurface()`
  (`CalendarGrids.swift:329-336`).
- Selected-day marking inside minis uses the same `DayDraw` (solid disc when selected, ring when
  today, underline when flagged), but the mini underline length is `min(20, cellW * 0.5)` = **7.67 pt**.

### 2.5 `CalendarDensity` (the shared density ladder)

`CalendarLayout.swift:14-58`:

- `minimumRowHeight` = **44** ("20 pt day number + 11 pt lunar + selection disc must fit").
- `resolve(availableHeight:rows:wantsLunar:typeSize:)`:
  `needed = max(1, rows)`, `perRow = round(availableHeight / needed)`;
  if `perRow >= 44 + 16` (**60**) → `.month(lunar: wantsLunar)`;
  else if `perRow >= 44` → `.month(lunar: false)`;
  else → `.weekStrip`.
- `rows(monthWeeks:)` = `1` for `.weekStrip`, else `monthWeeks`.
- `showsLunar`, `isWeekStrip` flags (`CalendarLayout.swift:44-52`).
- The `typeSize` parameter is accepted but unused (dead, §8.4).

Portrait does **not** call `resolve`; it hard-codes `.month(lunar: showsLunar)`
(`HomeView.swift:493`) and ignores the row count in the cell-height formula.

---

## 3. Landscape / wide-window split layout

### 3.1 Trigger

`AdaptiveLayout.splitsMasterDetail = contentWidth >= 700 && size.width > size.height`
(`AdaptiveLayout.swift:41-43`); `contentWidth = size.width − leading − trailing`
(`:32-34`). iPhone 18 Pro landscape: `874 − 62 − 62 = 750 ≥ 700` and `874 > 402` → **split**.
The `size` used is the **screen bounds**, not the geometry content size: `AdaptiveLayoutReader.resolve`
takes `scene.screen.bounds` and derives insets from the difference (`AdaptiveLayout.swift:139-166`;
rationale at `design-system.md:319-321`). The environment value is injected once at `RootView`
(`RootView.swift:68`) and re-injected into the editor cover (`RootView.swift:101`).

Rotating into the split calls `settleToMonth()` (`HomeView.swift:67-71`), which clears
`ymMorph`/`mwMorphMonth`, zeroes `zoom`/`expand`, sets `mode = .month`, all inside a
`Transaction(disablesAnimations = true)` (`HomeView.swift:82-94`).

### 3.2 Vertical budget (`HomeView.splitCalendarArea`, `HomeView.swift:322-399`)

| Quantity | Formula | Value |
|---|---|---|
| `pagePad` | literal **16** | 16 (`:327`) |
| `topPad` | literal **8** | 8 (`:328`) |
| `bottomClearance` | `layout.bottomInset + 44` | 20 + 44 = **64** (`:331`) |
| `paneH` | `max(220, size.height − topPad − bottomClearance)` | `max(220, 402 − 8 − 64) =` **330** (`:332`) |
| `headerH` | `compactMonthTitleH + compactWeekdayHeaderH` = 34 + 26 | **60** (`:334`) |
| `weeks` | `displayedWeeks(inMonth:ws:).count` | 5 or 6 (`:335`) |
| `density` | `CalendarDensity.resolve(availableHeight: paneH − headerH, rows: weeks, wantsLunar: showsLunar)` | 270 / 5 or 6 → see below (`:336-338`) |
| `rows` | `density.rows(monthWeeks: weeks)` | 5, 6, or 1 (`:339`) |
| `cellH` | `max(44, round((paneH − headerH) / rows))` | 270/6 = **45**, 270/5 = **54**, or 270 for a week strip (`:340-341`) |

Density at 402 pt landscape: `availableHeight = 270`; `perRow = round(270/6) = 45` →
`45 >= 60`? no → `45 >= 44`? yes → **`.month(lunar: false)`**; `perRow = round(270/5) = 54` → same
result. So the shipped landscape month always **hides the lunar row** on iPhone 18 Pro, and falls
to `.weekStrip` only when `round(availableHeight / max(1, weeks)) < 44` (e.g. `paneH − 60 < 4·44 = 176`
for a 4-row window). The docs' "六周格 + 农历" and "≥ 6×60" tiers are unreachable here.

### 3.3 Horizontal budget

| Quantity | Formula | Value | : line |
|---|---|---|---|
| `calendarW` | `min(layout.masterWidth, max(260, size.width − pagePad·2 − 260))` | `min(345, max(260, 402)) =` **345** | `HomeView.swift:345` |
| `dayW` | `max(240, size.width − pagePad·2 − calendarW − gutter − 0.5)` | `max(240, 717.5 − 345 − 16 − 0.5) =` **356** | `HomeView.swift:347` |
| `gutter` | literal **16**, split into `.padding(.horizontal, gutter / 2)` on a **0.5 pt** divider | 8 + 8 around the rule | `HomeView.swift:346,378-381` |
| fit check | `345 + 16 + 356 + 0.5 = 717.5` = `750 − 2·16` exactly | — | `:395-396` |
| outer frame | `.frame(width: size.width, height: size.height, alignment: .topLeading)` | 874 × 402 | `:398` |

`layout.masterWidth = min(440, max(280, round(contentWidth * 0.46)))`
(`AdaptiveLayout.swift:52-54`) → `round(750 * 0.46) = 345`.

Both panes are `.frame(width: …, height: paneH).clipped()`: the left pane page is
`MonthPane(month:…, width: calendarW, areaH: paneH, cellH: cellH, density: density,
titleHeight: compactMonthTitleH, weekdayHeight: compactWeekdayHeaderH, titleFont: TypeSize.pageTitle)`
(`HomeView.swift:361-373`); the right pane is `DayPane(blocks:dayKey:isFuture:openEditor:bottomInset:
maxColumnWidth: 560, headingHeight: 44, onTodayTap:)` (`HomeView.swift:384-393`).

Landscape `MonthPane` metrics (`CalendarPaneViews.swift:75-91`): `cellW = 345/7 =` **49.29**,
`cellH = cellH` (45 or 54), `dayFont = min(20, max(13, cellH·0.34))` → **15.3** (cellH 45) / **18.36**
(cellH 54), `lunarFont` computed but `lunarAlpha = 0`, `dividerAlpha = 1` for `.month`, `0` for
`.weekStrip`. For `.weekStrip` the row height is re-clamped: `rowH = min(cellH, weekStripH)` = `min(270, 68)`
(`CalendarPaneViews.swift:43`) and only `selectedRow` is drawn with `showAdjacent: true`
(`:50-57,110-113`).

Vertical month swipe in landscape: the same `DragPagePager(axis: .vertical, pageSize: paneH)`
(`HomeView.swift:353-360`); `selectMonthPage(key)` carries the selected day into the new month with
`comps.day = min(day, range(of: .day, in: .month, for: month)?.count ?? day)` (`:402-411`).

Landscape tap behaviour differs from portrait: `selectDay(_:)` only updates the selection with
`.snappy(duration: 0.25)` + `reloadDayBlocks()` when `splitsMasterDetail`; otherwise it runs the
portrait `openDay` morph (`HomeView.swift:501-509`).

**Landscape header**: the entire header (back capsule + today capsule) is **not built** in the split
branch (`HomeView.swift:31-41`); "today" lives in the right pane's heading row
(`CalendarPaneViews.swift:171-174`). `showYearPage()` is unreachable from the split layout
(`HomeView.swift:250-252`, and the comment at `:32-33,251`).

`DayPane` (`CalendarPaneViews.swift:118-181`):
- heading: `HStack(spacing: 10)`, `padding(.horizontal, 20)`, `frame(maxWidth: maxColumnWidth)` then
  `.frame(maxWidth: .infinity)`, `.frame(height: headingHeight = 44)`; contents =
  `L10n.formatDayKey(dayKey)` (`rowTitle` 15 semibold, `lineLimit(1)`, `minimumScaleFactor(0.6)`,
  id `home.dayHeading`), lunar `caption` 12 when `AppLanguage.isZh`, `Spacer(minLength: 8)`,
  `InfoCapsule(L10n.str("index_today"))` (id `home.todayChip`).
- body: `DayContentView(…, bottomPadding: 24)`, `.frame(maxWidth: 560)` + `.frame(maxWidth: .infinity)`,
  `.padding(.bottom, max(bottomInset, 24))` → 24 pt on iPhone 18 Pro landscape (bottomInset 20)
  (`CalendarPaneViews.swift:137-147`).

---

## 4. Every visual element with real constants

### 4.1 Header (portrait / non-split only)

- Container: `HStack(spacing: 10)`, `.frame(minHeight: 52)`, outer `.padding(.horizontal, 16)` /
  `.padding(.top, 12)` (`HomeView.swift:110,148,37-38`).
- **Back capsule** (left): `Button` label = `HStack(spacing: 6)` of `Image(systemName: "chevron.left")`
  at `16 pt semibold` + `Text(titleText)` at `TypeSize.headerTitle` (18) `.medium` with
  `lineLimit(1)`, `minimumScaleFactor(0.7)`, `allowsTightening(true)`, `contentTransition(.opacity)`;
  `foregroundStyle(Theme.onSurface())`, `padding(.horizontal, 14)`, `frame(minHeight: Spacing.hitTarget = 44)`;
  background = `Capsule().fill(.clear).glassEffect(.regular.interactive(true), in: Capsule())`
  (`HomeView.swift:111-134`). Accessibility id `home.header.back`. `opacity(mode == .year ? 0 : 1)`,
  `allowsHitTesting(mode != .year)`, `.animation(.diaryStandard, value: mode)` (`:136-138`).
- `titleText` by mode (`HomeView.swift:98-107`): year → `L10n.fmt("date_year", vm.yearPage)` (`2026年` / `2026`);
  month → same with the month page's year; week → `L10n.monthFull(vm.monthPage)` (`MMMM`: `九月` / `September`).
- **Today capsule** (right): `InfoCapsule(text: relativeDayLabel(), action: todayTapped)` —
  `Text` at `chip` 14 `.medium`, `onSurface()`, `lineLimit(1)`, `minimumScaleFactor(0.8)`,
  `padding(.horizontal, 12)`, `padding(.vertical, 7)`, `.buttonStyle(.glass)`,
  `.buttonBorderShape(.capsule)` (`HomeView.swift:144`, `Components.swift:375-396`).
  Label = `L10n.relativeDayLabel(from: selectedDate, to: Date())` →
  `今天` / `昨天` / `明天` / `N天前` / `N天后` (`L10n.swift:209-218`).
- **Future-date toast**: `Text(L10n.str("index_future_toast"))` at `TypeSize.meta` 13 in
  `onSurface()`, `padding(.horizontal, 16)`, `padding(.vertical, 10)`, glass `Capsule`,
  `.shadow(color: Theme.shadowColor(), radius: 12, y: 4)`, `.padding(.bottom, 24)`,
  transition `.opacity.combined(with: .move(edge: .bottom))` (`HomeView.swift:46-63`).

### 4.2 Big month title

`MonthBigTitle(month:height:fontSize:)` (`CalendarGrids.swift:305-322`):
`Text(L10n.monthFull(month))` at `fontSize` `.bold`, `lineLimit(1)`, `minimumScaleFactor(0.6)`,
`Theme.onSurface()`, `.frame(maxWidth: .infinity, alignment: .leading)`, `padding(.horizontal, 20)`,
`.frame(height: height)`.
Portrait: `height = CalendarLayout.bigTitleH = 72`, `fontSize = TypeSize.display = 32`
(`CalendarPaneViews.swift:29,31`). Landscape: `34` / `24` (`HomeView.swift:369-371`).
Accessibility id `home.monthTitle` (`CalendarPaneViews.swift:96`, `MorphViews.swift:62,131`).

### 4.3 Weekday header

`WeekdayHeaderView` (`CalendarGrids.swift:3-21`): `HStack(spacing: 0)`, 7 cells of exactly `cellW`;
`fontSize` default = `CalendarLayout.weekdayFontSize(cellW:) = min(max(cellW * 0.26, 11), 14)`
(`CalendarLayout.swift:143`); font applied via `.diaryCalendarFont(size)` (half-rate dynamic type);
names = `L10n.weekdayNames(weekStart:)` = `veryShortWeekdaySymbols` rotated so the week start is
index 0 (`L10n.swift:142-147`).
Weekend color rule: `let isWeekend = weekStart == "sunday" ? (i == 0 || i == 6) : (i >= 5)`;
weekend `Theme.onSurfaceVariant().opacity(0.6)`, weekday `Theme.onSurfaceVariant()`
(`CalendarGrids.swift:13-16`).
Default `cellW`: portrait `width/7` (`CalendarPaneViews.swift:46`, `HomeView.swift:513`), morph
`size.width/7` (`MorphViews.swift:65,134`).

### 4.4 Day cells — all states (`DayDraw.draw`, `CalendarGrids.swift:50-135`)

Inputs: `day`, `col`, `rowY`, `DayMetrics`, `alpha`, `selectedKey`, `todayKey`, `flags`,
optional `anchorMonth`, `isDark`, `adjacentAlpha`.

State derivation (`:62-72`):
`isSelected = dayKey == selectedKey`; `isToday = dayKey == todayKey`;
`isFuture = dayKey > todayKey` (string compare on `yyyyMMdd` keys);
`inMonth = anchorMonth == nil || same month`; then
`cellAlpha = alpha; if isFuture { cellAlpha *= 0.35 } else if !inMonth { cellAlpha *= adjacentAlpha }`.
Whole cell is skipped when `cellAlpha <= 0.01` (`:73`).

Layout math (`:75-84`):
`lineH = lunarAlpha > 0.01 ? (lunarFont + 5) * lunarAlpha : 0`
(`DayDraw.lunarLineH`, `:24-26`);
`contentH = dayFont + 4 + lineH`;
`top = rowY + (cellH − contentH) / 2`;
`numY = top + dayFont / 2`; `lunarY = top + dayFont + 4 + lineH / 2`;
`cx = (col + 0.5) * cellW`; `circleC = top + contentH / 2`;
`circleD = min(cellW − 3, contentH + 14, cellH − 3)`.

| State | Drop / stroke | Text color | : line |
|---|---|---|---|
| **selected** | filled ellipse of diameter `circleD` centered at `(cx, circleC)` in `Theme.primary().opacity(cellAlpha)` | `Theme.onPrimary()` (white / #191C20), font weight **semibold** | `:86-89,101-103,116` |
| **today** (not selected) | stroked ellipse, `lineWidth = 1.5`, inset `0.75`, diameter `circleD − 1.5`, in `Theme.primary().opacity(cellAlpha)` | `Theme.primary()` | `:90-97,104-106` |
| **in-month, plain** | none | `Theme.onSurface()` | `:107-109` |
| **adjacent month** | none | `Theme.onSurface().opacity(0.5)`; whole cell also `× adjacentAlpha` (morph only — portrait month never draws these) | `:110-113,70-72` |
| **future** (any month) | none | same as the row above; whole cell `× 0.35`; **`isFuture` wins over `!inMonth`** (it is an `else if`) | `:68-72` |
| **has diary** (`flags.contains(dayKey)`) | rounded rect `width = min(20, cellW * 0.5)`, height **2**, `cornerRadius = Radius.hairline = 1`, y = `numY + dayFont / 2 + 2`, centered on `cx`; fill = `Theme.onPrimary()` when selected else `Theme.primary()`, at `cellAlpha` | — | `:127-134` |

Canvas text is drawn inside `context.drawLayer { layer.opacity = cellAlpha; … }` with `anchor: .center`
(`:117-125`); the lunar line is drawn only when `lineH > 0.5`, in
`textColor.opacity(isSelected ? 0.95 : 0.75)` (`:120-123`).
Resolved-text cache: keyed by `(text, size quantized to 0.5, weight, color, isDark)`, cap
`maxEntries = 24_000` with `removeAll(keepingCapacity: true)` on overflow (`:28-42,137-167`).

Month row rules (`MonthCanvas` draw loop, `CalendarGrids.swift:198-209`): for each week index
`i > 0` where `rowInMonth` (the week contains a day of the anchor month, or `showAdjacent` is true),
fill `CGRect(x: 0, y: i*cellH − 0.5, width: cellW*7, height: 1)` with
`Theme.outlineVariant().opacity(0.35 * dividerAlpha)`. `WeekRowCanvas` optionally draws one rule at
`y = 0` when `showDivider` (`:283-286`), used by the month→week morph.

Per-day opacities actually reached on the home screen at 402 pt portrait (`cellW 57.43`,
`cellH 118`, `dayFont 20`, `lunarFont 11`): `lineH = 16`, `contentH = 40`, `top = rowY + 39`,
`numY = rowY + 49`, `lunarY = rowY + 69`, `circleD = min(54.43, 54, 115) =` **54**.
In landscape (`.month(lunar: false)`, `cellW 49.29`, `cellH 45`, `dayFont 15.3`):
`lineH = 0`, `contentH = 19.3`, `top = rowY + 12.85`, `circleD = min(46.29, 33.3, 42) =` **33.3**.
In the year page (`cellW 15.33`, `cellH 25.25`, `dayFont 12.63`): `circleD = min(12.33, 26.63, 22.25) =`
**12.33**, hence the `miniDayFont` clamp comments about not colliding vertically.

### 4.5 Day panel (portrait week mode and landscape right pane)

`DayContentView` (`DayContentView.swift:6-67`):
- `ScrollView(showsIndicators: false)` → `VStack(alignment: .leading, spacing: 16)`;
  `.padding(.horizontal, 20)`, `.padding(.top, 18)`, `.padding(.bottom, bottomPadding)`,
  `.frame(maxWidth: .infinity, alignment: .leading)`.
  `bottomPadding` default **96** (portrait full-screen scroll, `DayContentView.swift:15`;
  passed by `HomeView.swift:571-576` without overriding), **24** from `DayPane`
  (`CalendarPaneViews.swift:143`).
- When `blocks` is non-empty: one `DiaryBlockCard` per `EditBlock` (`id: \.id`), then, **only if
  `dayKey == today`**, `GlassPrimaryButton(title: L10n.str("index_continue_write")) { Haptics.tap(); openEditor(dayKey) }`
  with `.frame(maxWidth: .infinity)` (`DayContentView.swift:21-35`).
- Empty state (`DayContentView.swift:50-66`): `VStack(spacing: 12)` — `Image(systemName: isFuture ? "moon.stars" : "square.and.pencil")` at `diaryFont(26)` in
  `onSurfaceVariant().opacity(0.5)`; `Text(isFuture ? index_future_empty : index_day_empty)` at
  `rowTitle` 15 in `onSurfaceVariant()`; and, when not future and the day is today,
  `GlassPrimaryButton(title: index_write)`. `.padding(.top, 56)`, `.frame(maxWidth: .infinity)`.
- `GlassPrimaryButton` (`Components.swift:177-227`): label `HStack(spacing: 6)`, optional icon at
  12/13 pt semibold, text `chip` 14 (`compact`) or `rowTitle` 15, `.medium`, `lineLimit(1)`,
  `minimumScaleFactor(0.85)`, `foregroundStyle(.white.opacity(enabled ? 1 : 0.55))`,
  `padding(.horizontal, compact ? 14 : 20)`, `padding(.vertical, compact ? 7 : 12)`,
  `contentShape(Capsule())`; background `Capsule().fill(Theme.primary())` +
  `.glassEffect(.regular.tint(Theme.primary()).interactive(true), in: Capsule())` +
  `.shadow(color: Theme.glowColor(), radius: 10, y: 3)` (disabled: `primaryContainer` at 0.6)
  (`:216-224`).
- `fullScreenCover(item: $previewImage)` → `ImagePreviewView` (`DayContentView.swift:45-47`).

### 4.6 Diary entry cards

`DiaryBlockCard` (`DayContentView.swift:69-107`):
- outer `VStack(alignment: .leading, spacing: 10)`, `.padding(Spacing.card = 12)`,
  `.diaryCard(cornerRadius: Radius.card = 20)`.
- **Meta line** (only when `block.startTimeUtc > 0 || !block.locText.isEmpty`):
  inner `VStack(alignment: .leading, spacing: 4)`;
  `Label(L10n.timeOf(block.startTimeUtc), systemImage: "clock")` when `startTimeUtc > 0`;
  `Label(block.locText, systemImage: "location.fill")` with `lineLimit(2)`,
  `multilineTextAlignment(.leading)`; the block is styled `diaryFont(TypeSize.caption = 12)` in
  `Theme.onSurfaceVariant()`; `contentShape(Rectangle())` + `onTapGesture { Haptics.tap(); onOpenDiary() }`
  (`DayContentView.swift:77-96`). Note: time and location share one color — the mockup's
  "品牌色时间" is not implemented (divergence §8.2).
- body: `DiaryPartsView(parts: ContentFlatten.parseContentCached(block.contentJson), onImageTap:, onTapText:)`
  (`DayContentView.swift:97-102`).

### 4.7 Block styles (h1 / h2 / p / quote), inline images, quote background

`[extra]` The card body is an attributed string built by `PartsCodec` and rendered by `ReadTextView`
(`MediaViews.swift:71-76` → `ReadTextView.swift:12-51`; `FittedTextView` sizes itself via
`sizeThatFits` at the available width, `isEditable = false`, `isScrollEnabled = false`,
`textContainer.lineFragmentPadding = 0`, `textContainerInset = (2,0,2,0)` from
`MediaViews.swift:75`).

Block ladder (`[extra] RichTextEngine.swift:19-58`, mirrored in `design-system.md:87-92`):

| Style | `ContentPart.type` | Design size | Apple style | `lineSpacing` | `paragraphSpacing` |
|---|---|---|---|---|---|
| h1 | `h1` | **28** | Title 1 | `28 × 0.13` = **3.64** | `28 × 0.4` = **11.2** |
| h2 | `h2` | **22** | Title 2 | `22 × 0.13` = **2.86** | `22 × 0.3` = **6.6** |
| p | `p` | **17** | Body | `17 × 0.13` = **2.21** | **0** |
| quote | `quote` | **15** | Subheadline | `15 × 0.5` = **7.5** | `15 × 0.4` = **6.0** |

- `lineSpacing = self == .quote ? designSize * 0.5 : designSize * 0.13` (`RichTextEngine.swift:40-42`).
- `paragraphSpacing` switch (`RichTextEngine.swift:46-53`).
- Paragraph style is built with `alignment = center ? .center : .left` plus the two values above
  (`RichTextEngine.swift:844-850`).
- Runs use `Theme.onSurfaceUIColor()`; `strikethroughStyle = 1` when `run.strike`, `underlineStyle = 1`
  when `run.underline`; quote runs add `.backgroundColor = Theme.quoteBgUIColor()`
  (`RichTextEngine.swift:893-897`). The terminating newline carries the same block attributes
  (`:901-909`).
- **Quote background** = `quoteBg` asset: light **#E4EAF9**, dark **#2B3344**
  (`Theme.swift:44`, `Assets/quoteBg.colorset`); a quote is also forced left-aligned when restyled
  (`RichTextEngine.swift:396-398`).
- TipTap-style list/todo markers are `MarkerAttachment`s for `•` / `☐` / `☑`, drawn with body
  attributes; a completed todo gets `strikethroughStyle = 1` and `onSurface` at **0.45** alpha
  (`RichTextEngine.swift:860-867`).
- **Inline images**: `DiaryPartsView` splits `ContentPart`s into text chunks and image chunks, each
  image its own `DiaryImageView` (`MediaViews.swift:60-101`), inside a `VStack(spacing: 8)`.
  `DiaryImageView` width `w = min(max(40, availableWidth), max(40, displayW))`,
  height `fitH = displayH * w / displayW`, `.scaledToFit()`, `.clipShape(RoundedRectangle(cornerRadius:
  Radius.image = 14, style: .continuous))`, decorative `Spacer(minLength: 0)` on both sides,
  `.aspectRatio(displayW / displayH, contentMode: .fit)`,
  decoded at `maxPixel = max(displayW, displayH) * 3` (`MediaViews.swift:19-43`);
  tap → `Haptics.tap()` → `onImageTap(src, h/w)` → `PreviewItem` → `ImagePreviewView`
  (`MediaViews.swift:32-35`, `DayContentView.swift:26`, `CalendarPaneViews.swift:132,149-151`).
  In the attributed pipeline an image is a `PayloadAttachment` with
  `Screen.width - 76` fallback max width (landscape/limited by `imageMaxWidth`), rounded to
  `Radius.image` (`RichTextEngine.swift:820-834`).
- **Image preview** (`MediaViews.swift:139-179`): black backdrop
  `Color.black.opacity(max(0, 1 − |dragOffset.height| / 600))`; image scaled to fit, decoded at
  `maxPixel 1600`; `scaleEffect(max(0.6, 1 − |dy|/800))`; dismiss when
  `|translation.height| > 140 || |predictedEndTranslation.height| > 900`, else snap back with
  `.diaryQuick`; tapping the backdrop dismisses; `.accessibilityAddTraits(.isModal)` + an
  accessibility action that dismisses.

### 4.8 Background and content-card surface

- Every tab is wrapped in `.diaryBackground()` (`RootView.swift:37,41,48,52`), i.e.
  `BlobBackground()` behind the content (`Components.swift:89-103`).
- `BlobBackground` (non-dense, `Components.swift:74-81`): `Theme.bg()` plus three blurred circles —
  `blobA` 280 × 280 blurred 60 at `(-60, 220)`; `blobB` 240 × 240 blurred 60 at `(width + 20, 640)`;
  `blobC` 200 × 200 blurred 55 at `(width − 30, −10)`; all `.ignoresSafeArea()`.
- `diaryCard` (`Components.swift:114-144`): `.continuous` `RoundedRectangle` filled with
  `Color(.secondarySystemGroupedBackground)`; two strokes — `onSurface` at 0.55 (only when
  `colorSchemeContrast == .increased`, else 0.0) width 1, and `outlineVariant` at 0.9/0.45 width
  1.5/0.5; shadow `Theme.shadowColor().opacity(0.14)`, radius 8, y 2 (interactive variant:
  0.22 / 12 / y 4).
- Liquid-glass boundary: content cards are **not** glass; glass is reserved for the control layer
  (capsules, toast, buttons) (`Components.swift:106-113`, `design-system.md:176-180`).

---

## 5. Animation: full morph geometry

Both morph views are `Animatable` with `animatableData: Double` = `progress`
(`MorphViews.swift:15-25,107-117`) and both are hit-test-disabled
(`MorphViews.swift:91,147`). `progress` is `zoom` for year↔month and `expand` for month↔week
(`HomeView.swift:15,17`). Interpolation helper `CL.lerp` / `CL.clamp01`
(`CalendarLayout.swift:65-82`).

### 5.1 Year ↔ month (zoom morph)

State (`HomeView.swift:16-17,178-269`): `ymMorph = (year, month)`, `zoom`.
- **Month → year** (`showYearPage`, `:252-269`): in a `disablesAnimations` transaction set
  `yearPage = year(monthPage)`, `ymMorph = (yearPage, monthPage)`, `mode = .year`, `zoom = 0`; then
  `withAnimation(zoomAnimation) { zoom = 1 }`, clearing `ymMorph` on completion.
- **Year → month** (`openMonthFromYear`, `:178-201`): build `monthDate` from `(yearPage, month, day: 1)`;
  in a `disablesAnimations` transaction `vm.selectMonth(monthDate)`, `ymMorph = (yearPage, monthDate)`,
  `mode = .month`, `zoom = 1`; then animate `zoom → 0`.
  `Haptics.tap()` fires first (`:180`); both entry points are guarded by `guard ymMorph == nil` (`:179`).

Per-frame geometry (`MorphViews.swift:27-92`), with `t = clamp01(1 − progress)`:

| Term | Formula | : line |
|---|---|---|
| month number | `monthNum = component(.month, from: month)` | `:29` |
| mini grid rect | `miniRect = CalendarLayout.miniGridRect(month: monthNum, in: size)` | `:30` |
| full grid rect | `fullRect = fullMonthGridRect(in: size) = (0, bigTitleH + weekdayHeaderH, size.width, size.height − 72 − 30)` | `:31`, `CalendarLayout.swift:239-244` |
| interpolated grid | `grid = lerp(miniRect, fullRect, t)` | `:32` |
| mini metrics | `CalendarLayout.miniMetrics(in: size)` | `:34` |
| full metrics | `monthMetrics(width: size.width, areaH: size.height, lunar: showsLunar)` | `:35` |
| metrics | `DayMetrics.lerp(mini, full, t)` then **overridden**: `cellW = grid.width / 7`, `cellH = grid.height / 6` | `:36-38` |
| lunar alpha | `showsLunar ? clamp01((t − 0.5) / 0.5) : 0` (appears from t = 0.5) | `:39` |
| divider alpha | `clamp01((t − 0.55) / 0.45)` | `:40` |
| `late` (month chrome opacity) | `clamp01((t − 0.55) / 0.45)` (same value) | `:41,68` |
| anchor point for year scaling | `UnitPoint(x: card.midX / size.width, y: card.midY / size.height)` of `yearCardRect(month: monthNum)` | `:42-43` |
| year opacity | `clamp01((progress − 0.45) / 0.55)` | `:44` |
| year scale | `1 + (1 − progress) * 0.6` → **1.6 → 1.0** | `:45,55` |
| mini label opacity | `clamp01((progress − 0.7) / 0.3)` | `:46,87` |

Composition (`:47-91`): `ZStack(alignment: .topLeading)` containing (a) `YearPageView(…, hiddenMonth: monthNum)`
scaled/opacity'd per above, (b) a `VStack(spacing: 0)` with a **fixed 72 pt slot** holding
`MonthBigTitle` and a **fixed 30 pt** `WeekdayHeaderView`, both at `late` opacity, (c) the
interpolated `MonthCanvas(weeks: CalendarLayout.weeks(inMonth: month, ws: …), showAdjacent: false,
onTapDay: nil)` at `frame(grid.size).offset(grid.minX, grid.minY)`, and (d)
`MiniMonthLabel(year:month:)` positioned with `frame(width:height:alignment: .leading)` +
`offset(title.minX, title.minY)` at `labelOpacity`. The whole stack is
`.frame(size).clipped().allowsHitTesting(false)` (`:89-91`).

Divergence notes: the morph uses `CalendarLayout.weeks` (fixed 6 rows) while the settled portrait
month uses `displayedWeeks` (5 for short months) (`MorphViews.swift:69` vs `CalendarPaneViews.swift:40`);
and the mini month font comes from `miniMetrics` on both sides deliberately to avoid a size jump
(`CalendarLayout.swift:225-237`, `CalendarGrids.swift:414-419`).

### 5.2 Month ↔ week (expand morph)

State (`HomeView.swift:18,203-248`): `mwMorphMonth`, `expand`.
- **Month → week** (`openDay(day)`, `:203-222`): guard `mwMorphMonth == nil`; `Haptics.tap()`;
  in a `disablesAnimations` transaction `vm.select(day)`, `mwMorphMonth = vm.monthPage`, `mode = .week`,
  `expand = 0`; then animate `expand → 1`, clear `mwMorphMonth` on completion,
  `reloadDayBlocks()`.
- **Week → month** (`goBack()` when `mode == .week`, `:227-242`): guard `!morphing`; in a
  `disablesAnimations` transaction `vm.selectMonth(DateUtil.monthFirst(selectedDate))`,
  `mwMorphMonth = vm.monthPage`, `mode = .month`, `expand = 1`; then animate `expand → 0`.
  (Note this **changes the selection to the 1st of the month** on the way back.)

Per-frame geometry (`MorphViews.swift:119-179`), with `progress = expand`:

| Term | Formula | : line |
|---|---|---|
| weeks | `CalendarLayout.weeks(inMonth: month, ws: weekStart)` (always 6) | `:120` |
| selected row | `selRow = weekRowIndex(of: selectedDate, in: month, ws:) = (lead + day − 1) / 7` | `:121`, `CalendarLayout.swift:364-368` |
| title slot | `titleH = bigTitleH = 72` | `:122` |
| header slot | `headH = weekdayHeaderH = 30` | `:123` |
| month cell height | `cellH = monthCellH(areaH: size.height) = (size.height − 72 − 30) / 6` | `:124` |
| month metrics | `monthMetrics(width: size.width, areaH: size.height, lunar:)` | `:125` |
| week metrics | `weekMetrics(width: size.width, lunar:)` → `cellW = size.width/7`, `cellH = weekStripH = 68`, `dividerAlpha = 0` | `:126`, `CalendarLayout.swift:158-165` |
| strip bottom | `stripBottom = headH + weekStripH = 98` | `:127` |
| content progress | `contentT = clamp01((progress − 0.25) / 0.75)` | `:128` |

Element-by-element:

1. **Month big title**: `.offset(y: −progress * 72)`, `.opacity(1 − clamp01(progress * 2))` — fully
   faded out at `progress = 0.5` (`:130-133`).
2. **Weekday header**: `.frame(width: size.width, height: 30)`,
   `.offset(y: lerp(72, 0, progress))` — constant opacity (`:134-136`).
3. **Six rows** (`row(_:…)`, `:150-179`), `base = 72 + 30 + i * cellH`:
   - selected row (`i == selRow`): `y = lerp(base, 30, progress)` (**slides up to the header
     bottom**), `alpha = 1`, `metrics = DayMetrics.lerp(mMetrics, wMetrics, progress)` (cell grows
     to 68 and the divider alpha fades to 0) (`:157-160`).
   - rows above (`i < selRow`): `y = base − progress * (base + cellH)` (slide off the top),
     `alpha = 1 − clamp01(progress * 1.4)` → gone at `progress ≈ 0.714` (`:161-163`).
   - rows below (`i > selRow`): `y = base + progress * (size.height − base)` (slide off the bottom),
     same alpha ramp (`:164-166`).
   - each row is a `WeekRowCanvas(…, alpha:, showDivider: i > 0, anchorMonth: month,
     adjacentAlpha: progress, onTapDay: nil)` framed to `metrics.cellH` and offset by `y`
     (`:168-178`). Because `adjacentAlpha = progress`, out-of-month days in the strip fade in as
     the morph completes.
4. **Content** (the real day block, passed as a `@ViewBuilder` closure from
   `HomeView.swift:451-453` = `dayContentBlock(w:h:)`):
   `.offset(y: stripBottom + (dayTitleH + 20) * (1 − contentT))` = `98 + 60 * (1 − contentT)`
   (so y: **158 → 98**), `.opacity(clamp01((progress − 0.3) / 0.5))` → invisible until 0.3,
   fully visible at 0.8 (`:141-143`).
5. Container: `ZStack(alignment: .top)`, `.frame(width: size.width, height: size.height, alignment: .top)`,
   `.clipped()`, `.allowsHitTesting(false)` (`:145-147`).

### 5.3 Pager drag / rubber-band math (`DragPagePager.swift`)

- Pages built: `prevKey` and `nextKey` only when they exist **and** (`!disabled || offset != 0`)
  (`:15-22,24,38`); the current page is always built and `.clipped()` (`:31-37`).
- Gesture: `DragGesture(minimumDistance: 8)` (`:59`), enabled by `.gesture(…, isEnabled: !disabled)`
  (`:48`).
- `onChanged`: `t = axis == .vertical ? translation.height : translation.width`;
  if `t > 0 && prevKey == nil` → `raw = rubber(t)`; if `t < 0 && nextKey == nil` →
  `raw = −rubber(−t)`; then `offset = min(pageSize, max(−pageSize, raw))` (`:60-69`).
- Rubber band: `rubber(x) = c * x / (x + c)` with `c = pageSize * 0.25` (`:101-104`).
- `onEnded`: `p = predictedEndTranslation` (axis component);
  `goNext = nextKey != nil && (p < −90 || (p < 0 && t < −60))`;
  `goPrev = prevKey != nil && (p > 90 || (p > 0 && t > 60))` (`:70-75`).
  - Commit: animate `offset` to `∓pageSize` with `.snappy(duration: 0.3)`, then in a
    `disablesAnimations` transaction set `offset = 0` **and** call `onPageChange(target)` together
    (`:76-92`).
  - Cancel: animate `offset` back to `0` with `.snappy(duration: 0.3)` (`:93-97`).
- External `current` changes reset `offset` to 0 in a `disablesAnimations` transaction (`:49-55`).

Thresholds on iPhone 18 Pro: portrait month/year pages `pageSize = 810` (rubber `c = 202.5`,
commit at ±810); portrait week pages `pageSize = 402` (`c = 100.5`); landscape month pages
`pageSize = 330` (`c = 82.5`).

### 5.4 Other home animations

- Today in month mode: `withAnimation(.snappy(duration: 0.3)) { vm.resetToToday() }` +
  `reloadDayBlocks()` (`HomeView.swift:279-283`); in week mode the same animation around
  `vm.select(now)` (`:285-289`).
- Today tapped while in year mode: `vm.resetToToday()` then `openMonthFromYear(month(now))`, i.e.
  the year→month zoom (`HomeView.swift:275-277`).
- `.uiTickChanged` resets everything to today with **no** animation via `resetToToday()`
  (`HomeView.swift:75-78,293-301`, `RootView.swift:138-140`).
- `.diaryVersionChanged` re-runs `loadInitial()` (`HomeView.swift:72-74`).

---

## 6. Interaction table

| # | Gesture / target | Condition | Result | : line |
|---|---|---|---|---|
| 1 | Tap header back capsule (`‹ <title>`) | `mode != .year`; not morphing (`goBack` guards `!morphing`) | week → month morph (`vm.selectMonth(monthFirst(selectedDate))`, `expand 1→0`); month → year zoom (`showYearPage`); year → nothing | `HomeView.swift:111-113,224-248` |
| 2 | Tap header capsule while `mode == .year` | — | Not built/hit-testable: `opacity 0`, `allowsHitTesting(false)` | `HomeView.swift:136-137` |
| 3 | Tap today capsule (header, portrait) | `!morphing` (guard inside `todayTapped`) | year → reset + year→month zoom; month → snap selection to today; week → snap selection to today | `HomeView.swift:271-291` |
| 4 | Tap today capsule (`DayPane` heading, landscape) | Split layout only; id `home.todayChip` | same `todayTapped` | `CalendarPaneViews.swift:171-174` |
| 5 | Tap a day cell (portrait month) | Not morphing (layer not built while morphing); day must be in the anchor month | `Haptics.tap()` → `openDay(day)` = month→week morph | `HomeView.swift:495,203-222`, `CalendarGrids.swift:222-227,249-251` |
| 6 | Tap a day cell (landscape left pane) | Split layout | `Haptics.tap()` + `.snappy(0.25)` selection change + `reloadDayBlocks()`; **no** week morph | `HomeView.swift:372,501-509` |
| 7 | Tap a day in the week strip | `mode == .week` | `Haptics.tap()`, `vm.select(day)`, `reloadDayBlocks()` — **does not collapse the strip**, even for the already-selected day (which would otherwise re-run the `mwMorphMonth == nil` path) | `HomeView.swift:533-537`, `MorphViews.swift:168-176` (morph copy has `onTapDay: nil`) |
| 8 | Tap weekday-header cell | — | No gesture; plain `Text` | `CalendarGrids.swift:8-19` |
| 9 | Tap the dark space below a 5-row month grid | — | `SpatialTapGesture` → `dayAt` returns nil → ignored | `CalendarGrids.swift:222-227,243-253` |
| 10 | Vertical swipe in the month pager | `!morphing`; not disabled | previous/next month; portrait also `vm.selectMonth`; landscape `selectMonthPage` (carries the day-of-month, clamped to the month length) | `DragPagePager.swift:58-98`, `HomeView.swift:478-484,353-360,402-411` |
| 11 | Horizontal swipe in the week pager | `mode == .week`; `!morphing` | previous/next week; the same weekday shifts by ±7 days (`delta` from week starts) and blocks reload | `HomeView.swift:515-526` |
| 12 | Vertical swipe in the year pager | `mode == .year`; `!morphing` | previous/next year (`vm.yearPage = key`); flags are **not** reloaded | `HomeView.swift:461-466` |
| 13 | Any pager swipe while `morphing` | `disabled == true` and the whole pager is not built | no gesture, no construction (performance guard) | `HomeView.swift:423,465,482,519` |
| 14 | Tap a mini month in the year page | `mode == .year` | `Haptics.tap()` → `openMonthFromYear(month)` = year→month zoom; `hiddenMonth` (the morph's source month) is a `Color.clear` placeholder | `CalendarGrids.swift:376-384,377-379`, `HomeView.swift:178-201` |
| 15 | Tap meta line in a diary card | `startTimeUtc > 0 || !locText.isEmpty` | `Haptics.tap()` + `onOpenDiary()` → `openEditor(dayKey)` | `DayContentView.swift:91-95` |
| 16 | Tap card text | always | `Haptics.tap()` + `onOpenDiary()` | `DayContentView.swift:99-102` → `ReadTextView.swift:158` |
| 17 | Tap an inline image | always | `Haptics.tap()` + full-screen `ImagePreviewView` | `MediaViews.swift:32-35`, `DayContentView.swift:25-27` |
| 18 | Drag down in the image preview | ≥ 140 pt translation or ≥ 900 predicted | dismiss; else snap back with `.diaryQuick` | `MediaViews.swift:163-171` |
| 19 | Tap the preview backdrop | — | dismiss | `MediaViews.swift:148-150` |
| 20 | Tap "继续写 / 写日记" primary button | non-empty blocks and today (continue) or empty state and today (write) | `Haptics.tap()` + `openEditor(dayKey)` | `DayContentView.swift:29-34,58-61` |
| 21 | Tap future-date toast | — | no action; auto-dismiss after 2.5 s | `HomeView.swift:46-62,303-315` |
| 22 | Scroll the day content | — | `ScrollView(showsIndicators: false)`, no bounce configuration | `DayContentView.swift:19` |
| 23 | Rotate into the split | `splitsMasterDetail` becomes true | `settleToMonth()`: morph/year state discarded without animation | `HomeView.swift:67-71,82-94` |
| 24 | Tap a day while a morph is running | The morph views are `.allowsHitTesting(false)` and the base layers are not built | In practice **nothing on the calendar is tappable**. Two exceptions remain reachable because they are not covered by `morphing`: the header back capsule (fires `goBack`’s `!morphing` guard → no-op, no feedback) and the day pane's DIARY content (tap → `openEditor`; it is built in both `weekLayer` and the morph's `content` closure) | `MorphViews.swift:91,147`, `HomeView.swift:423,225` |

---

## 7. Accessibility semantics exposed

| Element | Semantics | : line |
|---|---|---|
| Header back capsule | id `home.header.back`; **no** custom label (title text is the label) | `HomeView.swift:135` |
| Month canvas (`MonthCanvas`) | `.accessibilityElement(children: .ignore)`; label = `L10n.fmt("date_month_title", year, monthName)`; value = `L10n.formatDayKey(dayKeyOf(selectedDate))`; `.accessibilityAdjustableAction` (increment/decrement → `onAdjacentDaySelected`); `.accessibilityAction` (activate → `onTapDay(selectedDate)`) | `CalendarGrids.swift:228-240` |
| Mini month card | `.accessibilityElement(children: .ignore)`, `.accessibilityAddTraits(.isButton)`, label `L10n.monthFull(monthDate)`, id `year.month.<1…12>` | `CalendarGrids.swift:435-438` |
| Month title text | id `home.monthTitle` (used by landscape/portrait UI tests) | `CalendarPaneViews.swift:96`, `MorphViews.swift:62,131` |
| `DayPane` heading | date text id `home.dayHeading`; today capsule id `home.todayChip` | `CalendarPaneViews.swift:163,173` |
| Diary images | `DiaryImageView` adds `.isImage`; the attributed-pipeline attachment has no separate trait | `MediaViews.swift:42` |
| Image preview | `.accessibilityAddTraits(.isModal)` + a default action that dismisses | `MediaViews.swift:175-178` |
| Markers/chips | `GlassChip` and `GlassActionChip` add `.isSelected` when `active`; `PressableGlassIcon` adds `.isSelected` when `active` and always sets `.accessibilityLabel` | `Components.swift:371,452,324-325` |
| Canvas day numbers / weekday names | **Not** accessibility elements (single `Canvas`, one merged element) — VoiceOver cannot address individual dates | `CalendarGrids.swift:195-220,228` |
| Header today capsule | Label is the localized relative-day string (`今天` / `昨天` / …); no id | `Components.swift:385`, `L10n.swift:209-218` |
| Test-only state probe | Invisible `Text` with id `app.state` when launched with `-ui-test-state` | `RootView.swift:73-85` |

Known accessibility gap: because `MonthPane` never passes `onAdjacentDaySelected`, the
`.accessibilityAdjustableAction` guard returns immediately and the adjustable action does nothing;
the doc itself flags that the increment/decrement actions have no labels and that `a11y_next_day` /
`a11y_prev_day` were removed as unused (`CalendarPaneViews.swift:59-65`,
`CalendarGrids.swift:233-237`, `design-system.md:228-232`). See §8.4.

---

## 8. Divergences and dead code

### 8.1 Code vs design docs (`design-system.md`, `adaptive-layout-plan.md`)

| # | Doc claim | Shipped code | Verdict |
|---|---|---|---|
| 1 | Landscape left pane `340` (`adaptive-layout-plan.md:58`) or `345` (`:107`) | `masterWidth` = `min(440, max(280, round(contentWidth * 0.46)))` = **345**, then `calendarW = min(masterWidth, max(260, size.width − 32 − 260))` = **345** (`AdaptiveLayout.swift:52-54`, `HomeView.swift:345`) | Code = 345; only `:107` is correct, `:58` is stale |
| 2 | Landscape "顶部 8pt、底部 64pt → 日历区约 330pt；标题槽 34 + 星期栏 26 = 60，剩 270 给 5–6 行 → 每格 45–54pt（日号 15–18pt）" (`adaptive-layout-plan.md:120-122`) | exactly 330 / 60 / 270 / 45–54 with `dayFont = min(20, max(13, cellH * 0.34))` = 15.3–18.36 (`HomeView.swift:332-341`, `CalendarPaneViews.swift:101-103`) | Code matches the prose; **the prose at `:115-117` contradicts it** (see #3) |
| 3 | Density tiers `≥ 6×60 + title → month with lunar` (`adaptive-layout-plan.md:115-117`) | `perRow >= 44 + 16 = 60` for lunar (`CalendarLayout.swift:35`) — the threshold is per-row, not "6×60 + title", and is only reachable with ≥ 360 pt of grid height | Doc prose is a restatement; code is authoritative |
| 4 | "横屏一般到不了" a lunar month | At 270/6 = 45 and 270/5 = 54, iPhone 18 Pro landscape always resolves to `.month(lunar: false)` | Code confirms the doc's parenthetical |
| 5 | "竖屏一律不动" / single-column portrait (`adaptive-layout-plan.md:13,46`) | portrait `mode` year/month/week morph path is intact; `resolve` is never called in portrait (`HomeView.swift:31-41,493`) | matches |
| 6 | `CalendarLayout.density(areaH:)` (`adaptive-layout-plan.md:283`) | the shipped API is `CalendarDensity.resolve(availableHeight:rows:wantsLunar:typeSize:)` (`CalendarLayout.swift:29-42`) | naming drift only |
| 7 | `MonthCanvas` supports `rowOnly` (`adaptive-layout-plan.md:284`) | no such parameter; week-strip single-row drawing is done by `MonthPane.selectedRow` + `.frame(width/height: rowH)` (`CalendarPaneViews.swift:43,49-57,110-113`) | naming drift only |
| 8 | `displayedWeeks` used for density (`adaptive-layout-plan.md:301-302`) | confirmed (`HomeView.swift:335`, `CalendarLayout.swift:322-335`) | matches |
| 9 | `yearSpacing` "从 10 收到 6" (`CalendarLayout.swift:138`) and mockup spec still says 迷你月网格间距 10pt (`docs/design/landscape/frames-phone-portrait.js`) | code = **6** | code authoritative |
| 10 | Doc §2.2 says the landscape floating bar is "底部居中 64pt" (`adaptive-layout-plan.md:71`), while `devices.js:20-23` says it is a **left-edge vertical capsule** | code only depends on `safeAreaInsets` (`AdaptiveLayout.swift:24,32-34`; `HomeView.swift:331`); no layout branches on the bar's position | code is agnostic; docs contradict each other |
| 11 | `design-system.md:166` says day-title uses `dayTitle` (`9月15日 周二`); the week-mode row in code uses `weekHeaderTitle` (`2026年9月15日 周二`) | `HomeView.swift:552` uses `weekHeaderTitle`; `DayPane` uses `formatDayKey`→`dayTitle` (`CalendarPaneViews.swift:157`) | both exist on purpose: week row = full date, landscape pane = month+day |
| 12 | `docs/design/landscape/devices.js:23-24` says the left column is **52 %** of the available width (`750 × 0.52 = 390`) and the design-pane engine uses `paneW: sidebar ? 300 : 340` (`engine.js:468`) | code = 46 % clamped to 280…440, i.e. 345 (`AdaptiveLayout.swift:52-54`) | docs contradict each other and the code; code authoritative |
| 13 | `CalendarDensity.minimumRowHeight` comment says "20pt 日号 + 11pt 农历 + 选中圆" | portrait month ignores `minimumRowHeight` entirely; the value is only consumed by `resolve`, i.e. by the landscape path where the lunar row is off and `dayFont` is 15.3 pt | comment describes the intent, not the portrait behaviour |

### 8.2 Code vs mockups (`docs/design/landscape/frames-phone-portrait.js`, `frames-phone.js`)

| # | Mockup claim | Shipped code |
|---|---|---|
| 14 | Portrait month cell height "本稿 56pt" | code draws 118 pt cells at 402 × 874 (`(810 − 72 − 30)/6`) |
| 15 | Selection disc `min(格宽−2, 内容高+10, 格高−2)` | `circleD = min(cellW − 3, contentH + 14, cellH − 3)` (`CalendarGrids.swift:84`) |
| 16 | "有日记" underline 20 pt wide (`frames-phone-portrait.js` spec/annot) | `min(20, cellW * 0.5)` — at portrait cellW 57.43 that is 20, but in the year page it is 7.67, and in landscape 24.6 → clamped to 20 (`CalendarGrids.swift:128`) |
| 17 | Bottom reserve "120pt（TabBarClearance）" on the month screen | the portrait month grid has **no** bottom reserve: `Spacer(minLength: 0)` then `.clipped()` (`CalendarPaneViews.swift:69`); `TabBarClearance` (`Components.swift:589-599`) is not used by `HomeView` at all (only `DayContentView`'s 96 pt padding and `DayPane`'s 24 pt) |
| 18 | Year page: 迷你月字号 10pt, 网格间距 10pt | `miniDayFont` = 12.63 pt at 402 pt, `yearSpacing` = 6 (`CalendarLayout.swift:139,176-178`) |
| 19 | Year page: 年标题 32pt 高 62pt | matches (`CalendarGrids.swift:355,368`) |
| 20 | Year page note "选中日在当前月里是实心小圆" | mini grid draws every month with the same selected date; a selected day can appear in two adjacent mini months (the 6-row windows overlap by design) |
| 21 | Landscape left pane `390pt (约 52%)`, title row `32pt` only one line, month cells `390/7 = 56` | 345 pt, `compactMonthTitleH = 34` at `TypeSize.pageTitle = 24`, cells 49.29 × 45/54 (`HomeView.swift:334,345,369-371`) |
| 22 | Landscape right-pane heading contains a **year capsule** (`ph-home-landscape` note/annots, `homeLandscape` markup line 62) | shipped `DayPane` heading is date + lunar + `今天` only; there is **no** year capsule anywhere in the split layout (`CalendarPaneViews.swift:154-180`, `HomeView.swift:31-33`) |
| 23 | Landscape "两栏对半：月历约 390 / 右栏约 330", `gap: 32px` | 345 / 356 with a 16 pt gutter (`HomeView.swift:345-347`) |
| 24 | Week-strip fallback: "一行 7 天，**横向翻周**", "周条高度 52pt", plus a "本月还有 / 上周 / 下周" summary row | shipped fallback draws only the selected row, height `min(cellH, weekStripH = 68)`, has **no** week paging and **no** summary row (`CalendarPaneViews.swift:43,50-57`); the vertical month pager stays vertical (`HomeView.swift:353-360`) |
| 25 | Week view card meta line "品牌色时间" | time and location share `Theme.onSurfaceVariant()` (`DayContentView.swift:88-89`) |
| 26 | Portrait week/day card "间距 16 / 卡片 padding 12 / 圆角 20" | matches (`DayContentView.swift:20,104-105`) |

### 8.3 Internal code-vs-code inconsistencies (same behaviour, two rules)

| # | Inconsistency | Lines |
|---|---|---|
| 27 | Morph uses `CalendarLayout.weeks` (always 6 rows) but the settled portrait month uses `displayedWeeks` (5 for short months) — the last row's rule and the months that fit differ between the animating and settled frames | `MorphViews.swift:69,120` vs `CalendarPaneViews.swift:40`; the comment at `CalendarGrids.swift:200-202` explains the `rowInMonth` workaround |
| 28 | The morph's expanding week row is built with `showAdjacent` implicitly false (`WeekRowCanvas` has no such parameter — it always draws all 7 days), but `adjacentAlpha = progress`, so neighbouring-month days are drawn at reduced alpha during the morph and at full alpha in the settled week strip | `MorphViews.swift:175` vs `HomeView.swift:529-532` |
| 29 | Canvas-drawn day/lunar fonts are scaled by `calendarMultiplier` while the cell geometry, disc diameter and underline are not, so at large Dynamic Type sizes the disc can no longer contain the two-line content | `CalendarGrids.swift:185-191,270-276`, `DesignSystem.swift:217-219`, `CalendarGrids.swift:84` |
| 30 | `HomeView` hard-codes `pagePad: CGFloat = 16` / `topPad = 8` while `AdaptiveLayout.pagePadding = 16` exists (`HomeView.swift:327-328` vs `AdaptiveLayout.swift:74`) | values agree today; two sources of truth |
| 31 | `AdaptiveLayout.contentColumn(660)` exists and is unit-tested (`AdaptiveLayoutTests.swift:33-34`) but the home screen's landscape right pane uses a hard-coded `maxColumnWidth: 560` instead | `HomeView.swift:389` vs `AdaptiveLayout.swift:62-64` |
| 32 | `MonthCanvas.onAdjacentDaySelected` is an optional closure that is never passed by any home call site, so the adjustable action is a silent no-op | `CalendarGrids.swift:181,233-237`, `CalendarPaneViews.swift:50-65` |

### 8.4 Dead / unreachable code paths on the home screen

| # | Item | Evidence |
|---|---|---|
| 33 | `HomeView.splitCalendarArea`'s `todayAction` indirection (`let todayAction = todayTapped`) is redundant but reachable; **`showYearPage()`** is unreachable from landscape because the split branch builds no header (`HomeView.swift:348` and `:250-252,32-33`) |
| 34 | `mode == .year` is unreachable while split: `settleToMonth()` forces `.month` on rotation and there is no year entry point (`HomeView.swift:67-71`) |
| 35 | `DayContentView.showFutureToast: () -> Void` is stored and **never called** — the closure passed by `HomeView` (`showFutureDateToast`) is dead, so the future-date toast can never appear (`DayContentView.swift:12`; `HomeView.swift:576`; `DayPane` passes `{}` at `CalendarPaneViews.swift:142`). Documented as a known dead property at `design-system.md:268` |
| 36 | `MonthPane.portraitGridOriginY` is declared and never referenced (`CalendarPaneViews.swift:37`) |
| 37 | `CalendarLayout.miniLunarHidden` is only ever assigned to `lunarAlpha`; combined with `lunarFont: 6` and `dividerAlpha: 0` it means the mini months draw no lunar text and no row rules at all (`CalendarLayout.swift:180,234-236`) |
| 38 | `CalendarDensity.resolve`'s `typeSize: CGFloat = 1` parameter is never read (`CalendarLayout.swift:29-30`) |
| 39 | `CalendarMode.year` navigation from month is only reachable in the non-split branch, and `HomeView`'s `case .year: break` in `goBack()` can never run (`HomeView.swift:245-246`) |
| 40 | `FlowLightOverlay` + `Theme.flowLightColor/flowMaskColor/flowLight` are never instantiated (decorative component kept as a hook) (`Components.swift:649-689`, `Theme.swift:16-18,36`; `design-system.md:258-261`) |
| 41 | `DragPagePager`'s neighbour pages are skipped while `disabled && offset == 0`, so a disabled pager renders exactly one page (`DragPagePager.swift:22-24`) |
| 42 | `MorphProgressLog` / `morphLog` markers run only under `#if DEBUG` with the `-morph-log` launch argument (`HomeView.swift:169-176`, `MorphViews.swift:19-23,112-116`) |
| 43 | `HomeView` builds `weekLayer` even in landscape split? — **No**: `weekLayer` is only reached from `calendarArea`, which is only in the non-split branch (`HomeView.swift:31-41,511`) |
| 44 | Screen-size fallbacks `Screen.size` (390 × 844) and `AdaptiveLayout.defaultValue` (390 × 844) apply only before a scene exists/previews (`Components.swift:28-34`, `AdaptiveLayout.swift:80-83`) |

---

## 9. Reference numbers at a glance (iPhone 18 Pro)

| Quantity | Portrait 402 × 874 | Landscape 874 × 402 |
|---|---|---|
| Header slot | 12 + 52 = **64** | hidden |
| Calendar area | `max(320, 874 − 64)` = **810 × 402** | `paneH = max(220, 402 − 8 − 64)` = **330** |
| Title slot | **72** (`bigTitleH`, 32 pt bold) | **34** (`compactMonthTitleH`, 24 pt bold) |
| Weekday header | **30** | **26** |
| Grid available height | 810 − 102 = **708** | 330 − 60 = **270** |
| Rows | 6 (5 for short months, cell height still `/6`) | 6 or 5 from `displayedWeeks`, or 1 (week strip) |
| Cell | **57.43 × 118** | **49.29 × 45** (6 rows) / **× 54** (5 rows) / **× 68** (strip, clamped) |
| Day / lunar font | 20 / 11 | 15.3 / (lunar alpha 0) |
| Selection disc | **54** | **33.3** |
| Pane columns | single | **345** + 16 + **356** (divider 0.5) |
| Day panel | `dayTitleH 40` + `810 − 98 = 712` scroll | heading **44** + `330 − 44 − 24` scroll |
| Density (lunar?) | always lunar when `AppLanguage.isZh` | never lunar at this size |
| Column cap | — | heading text **560** (hard-coded, not `contentColumn`) |

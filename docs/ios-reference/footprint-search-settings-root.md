# iOS UI Spec — FOOTPRINT · SEARCH · SETTINGS · ROOT TAB BAR

Source of truth: `/Users/cov/JustADiary` (iOS 27.0 deployment target, SwiftUI, Liquid Glass).
Every number below is quoted from code with `file:line`. Mockups: `docs/design/landscape/screens/ph-*.png`
(cross-checked, discrepancies listed in §9).

Environment assumed by all numbers: **iPhone 18 Pro / iOS 27**, portrait `402 × 874 pt`,
landscape `874 × 402 pt`. Portrait safe area `T62 L0 B34 R0`; landscape `T0 L62 B20 R62`
(`docs/adaptive-layout-plan.md:312-317`). Landscape usable content width = `874 − 62 − 62 = 750 pt`.

---

## 0. File inventory

| Role | File | Lines |
|---|---|---|
| Root / tab bar | `JustDiary/Views/RootView.swift` | 169 |
| Footprint | `JustDiary/Views/Footprint/FootprintView.swift` | 330 |
| Search | `JustDiary/Views/Search/SearchView.swift` | 515 |
| Settings | `JustDiary/Views/Settings/SettingsView.swift` | 462 |
| Shared components | `JustDiary/Views/Components/Components.swift` | 702 |
| Tokens + Dynamic Type math | `JustDiary/Views/Components/DesignSystem.swift` | 220 |
| Dynamic Type plumbing | `JustDiary/Views/Components/DynamicType.swift` | 73 |
| Adaptive layout | `JustDiary/Views/Components/AdaptiveLayout.swift` | 184 |
| Colors | `JustDiary/Services/Theme.swift` + `JustDiary/Resources/Assets.xcassets/*.colorset` | 69 |
| Haptics | `JustDiary/Services/Haptics.swift` (no `Haptic.swift`) | 10 |
| View models | `FootprintViewModel.swift` (109) · `SearchViewModel.swift` (312) · `SettingsViewModel.swift` (178) |
| Strings | `JustDiary/Resources/Localizable.xcstrings` (196 keys, `en` + `zh-Hans`) |

---

## 1. Global foundations (apply to all three screens)

### 1.1 Corner radii — `DesignSystem.swift:13-34`

| Token | Value | Use |
|---|---|---|
| `Radius.card` | **20** (`:15`) | every content card, every list row card, page section card |
| `Radius.panel` | **26** (`:17`) | floating control surfaces, sheets, editor format bar |
| `Radius.field` | **14** (`:20`) | search containers inside cards, highlighted location row |
| `Radius.image` | **14** (`:22`) | inline images |
| `Radius.badge` | **12** (`:24`) | icon badges, small tinted squares, location-sheet row highlight |
| `Radius.bar` | **4** (`:26`) | chart bars |
| `Radius.hairline` | **1** (`:28`) | 2 pt-tall marks |

Nested rounded shapes must use `Radius.concentric(outer:inset:) = max(0, outer − inset)` (`:31-33`).
All cards use `style: .continuous` (squircle), e.g. `Components.swift:128`.

### 1.2 Spacing — `DesignSystem.swift:40-46`

| Token | Value |
|---|---|
| `Spacing.card` | **12** (gap between cards in a stack) |
| `Spacing.rowIcon` | **12** (gap between a row's icon badge and its text) |
| `Spacing.hitTarget` | **44** (Apple minimum) |

Ad-hoc gaps actually used by these three screens: **12** between cards, **10** between search results,
**8** between chips in the year bar, **6** between chips inside a filter group,
**2** between a settings row title and its subtitle, **16** between the two landscape filter groups.

### 1.3 Type scale — `DesignSystem.swift:55-86`

| Role | pt | Weight | Where |
|---|---|---|---|
| `TypeSize.pageTitle` | **24** | `.medium` | `PageHeader` title on all three screens |
| `TypeSize.cardTitle` | **20** | — | not used here |
| `TypeSize.statValue` | **18** | `.semibold` | footprint stat numbers |
| `TypeSize.headerTitle` | **18** | — | not used here |
| `TypeSize.sheetTitle` | **17** | `.medium` | sheet titles |
| `TypeSize.rowTitle` | **15** | `.regular` | footprint tree rows, settings row titles, search field text, search result body base, empty-state text |
| `TypeSize.rowValue` | **15** | `.regular` | settings trailing value **and** its chevron |
| `TypeSize.chip` | **14** | `.regular` (chips) / `.medium` (buttons) | all chips, buttons, footprint header summary, search cancel |
| `TypeSize.meta` | **13** | `.regular` | search snippet, busy overlay text |
| `TypeSize.rowSub` | **13** | `.regular` | settings row subtitle |
| `TypeSize.sectionTitle` | **13** | `.medium` | card section headers ("通用", "年度记录天数", "地点清单"), landscape filter group labels (regular) |
| `TypeSize.badge` | **12** | `.medium` | counts, filter capsules, result date capsule, count badge |
| `TypeSize.caption` | **12** | `.regular` | stat labels, chart axis, result time, sheet field labels, filter group labels |

Fonts are **not** `Font.system(size:)` directly: everything goes through
`.diaryFont(pt, weight:)` (`DynamicType.swift:52-54`), which resolves the size through
`DynamicTypeMetrics.scaled` (`DesignSystem.swift:199-201`) using `UIFontMetrics` for the nearest
Apple text style. **At the default category every value is returned unchanged** (`:190-196`).

### 1.4 Dynamic Type ceilings — `DesignSystem.swift:107-136`

Per-style growth caps (`ceiling(for:)`): largeTitle 1.35, title1 1.40, title2/title3 1.45,
body 1.50, callout 1.55, subheadline 1.60, footnote 1.65, caption1 1.70, default 1.75 (`:124-136`).

Resulting AX5 sizes documented in code (`:118-120`) and in `docs/design-system.md:55-57`:

| Design pt | 32 | 28 | 24 | 22 | 20 | 18 | 17 | 16 | 15 | 13 | 12 | 11 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| AX5 drawn | 44.8 | 39.2 | 34.8 | 31.9 | 29 | 27 | 25.5 | 24.8 | 24 | 21.5 | 20.4 | 19.3 |

Style→size mapping (`:143-156`): 34+ largeTitle, 28–33 title1, 22–27 title2, 20–21 title3,
17–19 body, 16 callout, 15 subheadline, 13–14 footnote, 12 caption1, else caption2.
So: 24 → title2, 18 → body, 15 → subheadline, 14 → footnote, 13 → footnote, 12 → caption1.

`DynamicTypeSize` is published once by `RootView` (`RootView.swift:65`) and read via
`@Environment(\.diaryDynamicTypeSize)` (`DynamicType.swift:17-27`). Calendar-drawn text uses a
half-rate `diaryCalendarFont` (`DynamicType.swift:61-63`) — not used on these three screens.

### 1.5 Colors — `Theme.swift` + asset catalog

Brand seed `0x2563EB` (`Theme.swift:4`), `AccentColor` = (0.145, 0.388, 0.922) = `#2563EB`.

| Token | Light | Dark |
|---|---|---|
| `bg` | `#FAF9FD` | `#101318` |
| `onSurface` | `#191C20` | `#E1E2E8` |
| `onSurfaceVariant` | `#44474F` | `#C5C6D0` |
| `outlineVariant` | `#C5C6D0` | `#44474F` |
| `primaryContainer` | `#DBEAFE` | `#1E3A8A` |
| `glassDim` | white α0.45 | `#262B36` α0.50 |
| `glassBorder` | white α0.55 | white α0.20 |
| `shadowColor` | black α0.078 | black α0.149 |
| `blobA` | `#93C5FD` α0.50 | `#1E40AF` α0.50 |
| `blobB` | `#BFDBFE` α0.60 | `#1E3A8A` α0.55 |
| `blobC` | `#E0E7FF` α0.43 | `#312E81` α0.43 |
| `onPrimary` | white | `#191C20` (`Theme.swift:8-10`) |

Derived: `glowColor` = seed α**0.15** (`Theme.swift:14`) — used as the location-row selected fill;
`flowLightColor` = seed α0.40, `flowMaskColor` = seed α0.05 (`:16-18`).

### 1.6 Page background — `Components.swift:60-103`

`BlobBackground` = `Theme.bg()` + three blurred circles, `.ignoresSafeArea()`:
circle A 280×280 blur 60 at `(−60, 220)`; B 240×240 blur 60 at `(width+20, 640)`;
C 200×200 blur 55 at `(width−30, −10)` (`:75-80`).
Applied to all four tabs by `RootView` via `.diaryBackground()` (`RootView.swift:37,41,48,52`).

### 1.7 Content card — `diaryCard(cornerRadius: Radius.card)` — `Components.swift:114-144`

* Fill `Color(.secondarySystemGroupedBackground)` (system white / `#1C1F26`).
* Border, normal contrast: `outlineVariant` α**0.45**, lineWidth **0.5** (`:135-136`).
* Border, `colorSchemeContrast == .increased`: `onSurface` α**0.55** lineWidth **1** *plus*
  `outlineVariant` α**0.9** lineWidth **1.5** (`:126,132-136`).
* Shadow `shadowColor` α**0.14**, radius **8**, y **2** (non-interactive);
  `interactive: true` → α**0.22**, radius **12**, y **4** (`:139-141`).
* Content-area cards deliberately do **not** use Liquid Glass; glass is reserved for the
  control layer (`:106-113`, `docs/design-system.md:176-206`).

### 1.8 Page header — `Components.swift:148-173`

`HStack(alignment: .center, spacing: 12)`: title `TypeSize.pageTitle` (24) `.medium`,
`onSurface`, `lineLimit(1)`, `minimumScaleFactor(0.7)`, `allowsTightening(true)`; `Spacer(minLength: 8)`;
trailing slot. `frame(minHeight: 52)` — deliberately `min`, not `height`, so accessibility sizes expand (`:168-171`).

### 1.9 Chips and capsules

| Component | Lines | Metrics |
|---|---|---|
| `GlassChip` | `Components.swift:350-373` | text 14, fg active `primary` / idle `onSurface`, `lineLimit 1`, `minScale 0.9`, padding h**12** v**7**, `.glass` + capsule, `.isSelected` trait when active |
| `GlassActionChip` | `:424-454` | HStack spacing **4**; icon 11 `.medium`; text 14; padding h**10** v**6**; `.glass` capsule |
| `InfoCapsule` | `:375-396` | text 14 `.medium`, padding h12 v7, `.glass` capsule |
| `GlassCountBadge` | `:400-422` | HStack spacing **5**; icon 10 `.semibold`; text 12 `.medium`; fg `primary`; padding h**10** v**5**; `Capsule().fill(primaryContainer)` (no glass) |
| `PressableGlassIcon` | `:295-327` | glass circle, visual diameter **44**, label box `max(28, size−8)` = 36 (glass style adds 4 pt/side); icon **17** `.medium` hierarchical; tint active `primary` / idle `onSurface`; `.symbolEffect(.bounce, value: active)`; `.isSelected` when active |
| `GlassIconButton` | `:271-293` | default `size` 32; icon **14** `.semibold` (**16** when `size > 36`); tint default `onSurfaceVariant`; hit frame `minWidth/minHeight 44`; `.glass` + `.circle` border shape |

### 1.10 Buttons

`GlassPrimaryButton` (`Components.swift:177-227`): HStack spacing **6**; optional icon 13 `.semibold`
(12 when `compact`); label `TypeSize.rowTitle` 15 `.medium` (14 when `compact`), `lineLimit 1`,
`minScale 0.85`; fg white α1 (α**0.55** when disabled); padding h**20** v**12** (compact h**14** v**7**);
background `Capsule().fill(primary)` + `.glassEffect(.regular.tint(primary).interactive(true))`
+ shadow `glowColor` radius 10 y 3 (compact 8/2). Disabled = `primaryContainer` α0.6.
Haptics fire on every tap (`:187`).

`GlassSecondaryButton` (`:229-269`): icon 13 `.semibold`; label 15 `.medium`; fg `onSurfaceVariant`;
padding h**20** v**12**; `Capsule().fill(glassDim())` + `.glassEffect(.regular.interactive(true))`.

### 1.11 Empty state — `Components.swift:458-480`

`VStack(spacing: 12)`: SF Symbol at **26 pt**, `onSurfaceVariant` α**0.5**; text `TypeSize.rowTitle`
(15) `onSurfaceVariant`, centered; optional compact primary button; `frame(maxWidth: .infinity)`,
`padding(.top, 56)`.

### 1.12 Search field — `Components.swift:531-581` (Liquid Glass)

`HStack(spacing: 8)`: `magnifyingglass` 15 pt `onSurfaceVariant`; `TextField` 15 pt, tint `primary`,
`.submitLabel(.search)`; trailing `xmark.circle.fill` 15 pt `onSurfaceVariant` shown only when
`!text.isEmpty`; caller-supplied trailing slot. `padding(.horizontal, 14)`, `frame(minHeight: 46)`,
background `Capsule().fill(.clear).glassEffect(.regular.interactive(true), in: Capsule())`.
No `diaryCard`, no opaque fill — deliberate (`docs/design-system.md:184-186`).

### 1.13 Rows and dividers

`GlassIconBadge` (`Components.swift:616-645`): `RoundedRectangle(cornerRadius: 12, style: .continuous)`
filled `primaryContainer` + 1 pt `glassBorder` stroke; **38 × 38** at default; grows to
`max(38, scaled(16) + 12)` with text size and stays square (`:626-628`); glyph 16 pt `onSurface`.

`RowDivider` (`:601-614`): system `Divider` overlaid with `outlineVariant` α**0.5**;
inset constant `RowDivider.textInset = 12 + 38 + 12 = `**`62`** (`:605`) so the rule starts under the
row title, not under the badge.

`TabBarClearance` (`:589-599`): `Color.clear.frame(height: 120 × multiplier(rowTitle=15))` —
120 pt at default, 192 pt at AX5. Used by Search (`SearchView.swift:257`) and Settings
(`SettingsView.swift:16`).

### 1.14 Animations — `Components.swift:6-9`

`Animation.diaryQuick` = `.easeOut(duration: 0.22)`; `Animation.diaryStandard` =
`.easeInOut(duration: 0.3)`. `Haptics.tap()` = `UIImpactFeedbackGenerator(style: .light)` with
`prepare()` then `impactOccurred()` (`Haptics.swift:3-9`).

### 1.15 Page margins and vertical rhythm

All three screens use `.adaptivePagePadding()` = leading **16** / trailing **16**
(`AdaptiveLayout.swift:74-76,169-183`). It must **not** add safe-area insets again: in landscape the
geometry origin already sits inside the safe area (`AdaptiveLayout.swift:66-73`,
`docs/adaptive-layout-plan.md:293-298`). Each screen's root also has `.padding(.top, 12)`
(`FootprintView.swift:70`, `SearchView.swift:58`, `SettingsView.swift:20`).

---

## 2. ROOT NAVIGATION — the tab bar

### 2.1 Structure — `RootView.swift:8-13, 24-59`

```swift
enum AppTab: Hashable { case home, footprint, search, settings }   // :8-13
TabView(selection: activeTab) { … }                               // :31
```

Four tabs, declared inline with `Tab(title, systemImage:, value:)`:

| # | `AppTab` | Icon (`systemImage`) | Label key | en | zh-Hans |
|---|---|---|---|---|---|
| 1 | `.home` | `house.fill` (`:32`) | `index_title` | Diary | 日记 |
| 2 | `.footprint` | `figure.walk` (`:39`) | `footprint_title` | Footprint | 足迹 |
| 3 | `.search` | `magnifyingglass` (`:43`) | `search_title` | Search | 搜索 |
| 4 | `.settings` | `gearshape.fill` (`:50`) | `settings_title` | Settings | 设置 |

Each tab's content is the screen `.diaryBackground()`-wrapped (`:37,41,48,52`). Home and Search
receive a closure that opens the diary editor (`:33-36, 44-47`), which sets
`appState.editorDayKey` + `appState.presentEditor = true`; the editor is presented as a
`fullScreenCover` at `:86-102`.

### 2.2 Styling

* **No custom tab bar.** System default floating (Liquid Glass) bottom bar — explicit decision,
  `docs/design-system.md:309-310`, `docs/adaptive-layout-plan.md:64-88`.
* `.tabBarMinimizeBehavior(.onScrollDown)` (`RootView.swift:55`) — the bar minimizes when the user
  scrolls down and restores on scroll up.
* `.tint(Theme.primary())` (`:59`) — selected item icon **and** label are brand `#2563EB`;
  unselected items use the system secondary label color. There is no custom selected pill in code.
* `.preferredColorScheme(AppConfigService.colorScheme)` (`:60`) — light/dark follows the app setting.
* Environment published to the whole tree: `\.diaryDynamicTypeSize` (`:65`), `\.locale` (`:66`),
  `\.adaptiveLayout` via `.adaptiveLayoutReader()` (`:68`).
* `.id("\(AppLanguage.current)#\(settingsTick)")` (`:72`) rebuilds the whole tree on language /
  settings change — this is how a language switch applies without relaunch.

### 2.3 Measured geometry (UI-test probes, `docs/adaptive-layout-plan.md:66-77`)

| Orientation | `app.tabBars` frame | Bar height | Button height | Content clearance the screens reserve |
|---|---|---|---|---|
| Portrait | `(0, 791, 402, 83)` | 83 | **54** | `TabBarClearance()` 120 pt, or footprint's `max(24, bottomInset + 44)` = `max(24, 34+44)` = **78** |
| Landscape | `(0, 338, 874, 64)` | 64 | **36** | footprint `max(24, 20+44)` = **64**; the bar is centred at the bottom |

Minimum hit target is the platform's 44 pt (`Spacing.hitTarget`).

### 2.4 Mockup rendering of the bar (`docs/design/landscape/mockup.css:276-324`)

The design file draws (not code) a floating capsule: `padding 5 px`, `border-radius 999 px`,
`gap 2 px`, fill `--glass-strong` (`rgba(255,255,255,.78)` light / `rgba(30,34,42,.78)` dark),
`backdrop-filter blur(24px) saturate(180%)`, shadow `0 8px 26px rgba(0,0,0,.20)` plus an inset
`0 0 0 1px var(--glass-border)`, `bottom: 20px`. Each tab: `gap 6 px`, `padding 8 px 15 px`,
`border-radius 999 px`, font **14 px** weight **500**, colour `onSurface` at **α0.72**, icon **15×15**
(`:314-320`). Active tab (`:321-324`): background
`color-mix(in srgb, var(--primary) 16%, transparent)` (i.e. `#2563EB` at 16 %), colour `primary`,
opacity 1, weight **600**. This is a *depiction* of the system bar, not app code.

---

## 3. FOOTPRINT (`FootprintView.swift`)

Data: `FootprintViewModel` — `stats` (provinces / cities / countries / blocks / days),
`nodes` (recursive country → province → city tree, `FootprintNode`), `yearly` (`FootprintYearStat`
per year: distinct days + distinct places), `locatedCount`, `unlocated`, `yearFilter`,
`timeKind` (`.all` / `.thisWeek` / `.thisMonth` / `.thisYear` / `.custom`), `customFrom/To`,
`expanded: Set<String>`, `timeRangeLabel` (`FootprintViewModel.swift:7-31`);
`hasPlaces = !nodes.isEmpty` (`:31`).

Tree semantics (`FootprintDataService.swift:9-18, 47-60, 78-114`): `id` = place components joined by
`\u{1}` and is stable across reloads; `count` = number of blocks at or below the node; children sorted
by count **descending**, then localized name ascending (`:97-103`); a node is a leaf when it has no
children. Aggregation needs no coordinates.

### 3.1 Structure top-to-bottom (portrait, 402×874)

```
VStack(spacing: 0) · .padding(.top, 12)                             :10, :70
├─ PageHeader("足迹") + trailing summary text        .padding(.horizontal, 16)   :11-17
├─ horizontal ScrollView of year chips + custom chip, gap 8      .adaptivePagePadding()  :19-32
└─ vertical ScrollView, gap 12 between blocks                    .adaptivePagePadding()  :34-68
   ├─ statsCard                                                                     :93-103
   ├─ trendCard()            only when vm.yearly.count > 1                          :40
   ├─ listCard(scrollable: false)                                                   :41
   └─ .padding(.top, 10) · .padding(.bottom, max(24, bottomInset + 44))   :65, :67
```
No navigation bar, no toolbar, no floating action button. Reload triggers: `.task { await vm.load() }`
(`:71`) and `.diaryVersionChanged` notification → `reload()` (`:72-74`).

**Header trailing text** (`:12-15`): `L10n.fmt("footprint_summary", locatedCount, unlocated)`,
`TypeSize.chip` **14**, `onSurfaceVariant`, `lineLimit(1)`.
`footprint_summary` = `Located %1$ld · Unlocated %2$ld` / `已标注 %1$ld 处 · 未记录 %2$ld 篇`.
Note: `locatedCount` is post-filter (`FootprintViewModel.swift:82`) while `unlocated` is
pre-filter (`:84`), so the two numbers can come from different filter scopes.

### 3.2 Year / time filter chip row — `:19-32, 83-89, 243-249`

`ScrollView(.horizontal, showsIndicators: false)` → `HStack(spacing: 8)` →
`GlassChip("全部", value:"all")` → one `GlassChip` per year (`vm.years`, descending, `:90`) →
`GlassActionChip(label: vm.timeRangeLabel, active: vm.timeKind == .custom)`.

* Year chip active ⇔ `vm.timeKind == .all && vm.yearFilter == value` (`:84`).
* Year chip tap: set `yearFilter`, `applyTimeKind(.all)`, `reload()` (`:85-87`).
* Custom chip label: `L10n.dateRange(from,to)` when a custom range exists, else
  `footprint_time_custom` = Custom / 自定义 (`FootprintViewModel.swift:24-29`).
* Custom chip tap: `Haptics.tap()` + open the time sheet, `.presentationDetents([.medium])` (`:25-29, :75-78`).
* Chip visuals: `GlassChip` = 14 pt, padding h12 v7, `.glass` capsule (see §1.9).

### 3.3 Stats card — `:93-121`

`HStack(spacing: 0)` of five equal cells, `.padding(.vertical, 14)`, `diaryCard(cornerRadius: 20)`.
Each `statCell` = `VStack(spacing: 3)`, `frame(maxWidth: .infinity)`:

| Order | Value (`TypeSize.statValue` 18 `.semibold`, `onSurface`) | Label (`TypeSize.caption` 12, `onSurfaceVariant`) | String keys |
|---|---|---|---|
| 1 | provinces count | 省市 / Provinces | `footprint_stat_provinces` |
| 2 | cities count | 城市 / Cities | `footprint_stat_cities` |
| 3 | countries count | 国家 / Countries | `footprint_stat_countries` |
| 4 | blocks | 片段 / Blocks | `footprint_stat_blocks` |
| 5 | days | 天数 / Days | `footprint_stat_days` |

Both value and label are `lineLimit(1)` + `minimumScaleFactor(0.6)` because five cells share the
row width (`:111, :118`).

### 3.4 Trend card — `:125-155`

* `VStack(alignment: .leading, spacing: 10)`, `padding(14)`, `frame(maxWidth: .infinity, alignment: .leading)`,
  `diaryCard(20)`.
* Title `footprint_trend_title` = "年度记录天数" / "Diary days per year", `TypeSize.sectionTitle`
  **13** `.medium`, `onSurfaceVariant`.
* Swift Charts `BarMark`: `x = year` (label `footprint_trend_year`), `y = days` (label
  `footprint_trend_days`), `.foregroundStyle(Theme.primary())`, `.cornerRadius(Radius.bar)` = **4**
  (`:131-138`).
* Y axis: `AxisMarks(position: .leading)` with `AxisGridLine()` + `AxisValueLabel()`
  (leading grid line + numbers). No X grid lines.
* Height: **132** by default (`chartHeight ?? 132`, `:145`); landscape passes `max(72, blockH − 58)`.
* Axis fonts follow the environment via `.diaryFont(TypeSize.caption)` = **12** (`:149`).
* `.accessibilityLabel(L10n.str("footprint_trend_title"))` on the whole chart (`:150`).
* Rendered only when `vm.yearly.count > 1` (`:40, :52`) — 0 or 1 year ⇒ no chart card at all.

### 3.5 Place list card — `:166-189`

`VStack(alignment: .leading, spacing: 0)`, `padding(14)`, `frame(maxWidth: .infinity, alignment: .topLeading)`,
`diaryCard(20)`; landscape adds `maxHeight: .infinity`.

* Title `footprint_list_title` = "地点清单" / "Places", 13 `.medium` `onSurfaceVariant`,
  `.padding(.bottom, 8)`.
* Non-empty → `FootprintNodeList(nodes:depth:0,expanded:)`; landscape wraps it in its own
  `ScrollView` (`scrollable: true`).
* Empty → `GlassEmptyState(systemImage: "mappin.slash", text: footprint_empty)`:
  26 pt symbol `onSurfaceVariant` α0.5, text 15 `onSurfaceVariant` centered, `padding(.top, 56)`.
  `footprint_empty` = "No places recorded yet" / "还没有地点记录".

**Tree row** — `FootprintNodeRow` (`:268-329`), one row per node, **no dividers**, no card per row:

| Slot | Spec | Line |
|---|---|---|
| Row container | `HStack(spacing: 8)`, `padding(.leading, depth × 16)`, `padding(.vertical, 9)`, `contentShape(Rectangle())`, `.buttonStyle(.plain)` | `:288, :309-313` |
| Leading icon | depth 0 `globe.asia.australia`, depth 1 `map`, depth ≥2 `mappin`; 12 pt `.medium`; `frame(width: 16)`; tint = leaf ? `onSurfaceVariant` : `primary` | `:289-292, :323-329` |
| Name | `TypeSize.rowTitle` 15, `onSurface`, `lineLimit(1)` | `:293-296` |
| Spacer | `minLength: 8` | `:297` |
| Count | 12 pt `.medium`, `onSurfaceVariant`, `.monospacedDigit()` (bare number, e.g. `9`) | `:298-301` |
| Disclosure chevron | `chevron.right`, `TypeSize.caption` 12 `.semibold`, `onSurfaceVariant`, `rotationEffect(.degrees(isExpanded ? 90 : 0))`; **omitted on leaves** | `:302-307` |
| Expansion | `withAnimation(.diaryQuick)` inserts/removes `node.id` in `expanded`; `Haptics.tap()`; leaves return early | `:281-286` |
| a11y | `.accessibilityIdentifier("footprint.node")`, `.accessibilityAddTraits(isExpanded ? .isSelected : [])` | `:314-315` |

There are **no country flags** anywhere in this screen (verified by grep). The mockup draws the same
globe/map/pin SF Symbols (`ph-footprint-portrait.png`).

### 3.6 Time-filter sheet — `:193-249`

`VStack(spacing: 16)`, `padding(20)`, `padding(.bottom, 8)`, `.presentationDetents([.medium])`.

1. `GlassSheetHeader(title: search_time_title, onClose:)` → 17 pt `.medium` title + `GlassIconButton("xmark")`.
   Title string: "自定义时间范围" / "Custom time range".
2. `HStack(spacing: 8)`: three `GlassChip`s — 本周 `thisWeek`, 本月 `thisMonth`, 今年 `thisYear`.
   Tap ⇒ `applyTimeKind(kind)`, dismiss, `reload()` (`:243-249`).
3. Label `search_time_start` ("开始日期" / "Start date") 12 pt `onSurfaceVariant`, then
   `DatePicker("", selection:, displayedComponents: .date).datePickerStyle(.compact).tint(primary)`.
   Default shown value = `vm.customFrom ?? DateUtil.monthFirst(Date())`; on change
   `vm.customFrom = DateUtil.startOfDay($0)` (`:207-212`).
4. Same for `search_time_end`; default `vm.customTo ?? Date()` (`:218-223`).
5. `HStack(spacing: 12)`, `.padding(.top, 12)`:
   `GlassSecondaryButton(search_time_clear, fullWidth: true)` → `clearTimeFilter()` + dismiss + reload;
   `GlassPrimaryButton(search_time_apply, fullWidth: true)` → `applyCustomTime()` + dismiss + reload.
   Strings: 清除/Clear, 确定/Apply.

---

## 4. SEARCH (`SearchView.swift`)

Data: `SearchViewModel` — `keyword` (live text), `keywords` (committed terms), `timeKind`,
`customFrom/To`, `locFilter{country,region1,noLoc}`, `results`, `total`, `hasMore`, `searching`,
`indexing`, `offset`, `locOptions`, `noLocCount`, `locSearch`, `locLoading`
(`SearchViewModel.swift:45-76`). Debounce: **250 ms** after a keystroke (`:153-160`).
Page size / offset paging via `loadMore()` (`:246-248`).

### 4.1 Structure top-to-bottom (portrait)

```
VStack(spacing: 0) · .padding(.top, 12)                       :27, :58
├─ header (PageHeader "搜索" + result-count badge)      .adaptivePagePadding()   :28-29, :128-134
├─ [hidden when hideTopControls]
│   ├─ searchBar                                        .padding(.top, 10)        :32-34
│   └─ filterPanel                                      .padding(.top, 12)        :35-37
│      (top block height measured into topControlsHeight)                         :39-49
├─ filterSummary  (only when vm.hasAnyCondition)        .padding(.top, 8)         :50-55
└─ resultsList                                                                   :56, :237-266
```
Transitions: top controls `.opacity + .move(edge: .top)` (`:48`); filter summary same (`:54`).
Animations: `.diaryStandard` on `hideTopControls` (`:59`), `.diaryQuick` on `hasAnyCondition` (`:60`).

**Header** (`:128-134`): `PageHeader("搜索")`; trailing shows `GlassCountBadge(text:
L10n.fmt("search_result_count", vm.total), icon: "circle.fill")` only when `vm.total > 0`.
`search_result_count` = "%d entries found" / "找到 %d 条日记". Badge = 12 pt `.medium`, 10 pt
`circle.fill` icon, fg `primary`, padding h10 v5, `primaryContainer` capsule.

### 4.2 Search bar — `:136-155`

`GlassSearchField(text: $vm.keyword, placeholder: search_placeholder,
focus: $searchFocused, onSubmit: vm.commitKeyword)` with a trailing slot that appears while focused:
`Text(search_cancel)` in `TypeSize.chip` 14 `.medium`, `primary`, plain button, `.transition(.opacity)`;
`.animation(.easeOut(duration: 0.2), value: searchFocused)`.
`search_placeholder` = "Search diary content…" / "搜索日记内容…"; `search_cancel` = Cancel / 取消.

### 4.3 Filter panel — `:157-235`

Wrapper: `padding(10)`, `diaryCard(20)`.
* **Portrait** (`layout.isPortrait`): `VStack(alignment: .leading, spacing: 6)` — time group then place group.
* **Landscape**: `HStack(alignment: .top, spacing: 16)` — the two groups side by side, saving ≈60 pt of height.

Each group = `VStack(alignment: .leading, spacing: 6)` + `frame(maxWidth: .infinity, alignment: .leading)`:
* Label 12 pt `onSurfaceVariant`: `search_filter_time` = "时间范围"/"Time range";
  `search_filter_loc` = "地点"/"Place".
* Horizontal `ScrollView`, `HStack(spacing: 6)` of `GlassChip`s.

Time chips (`:182-190`), in order: 全部时间 (`.all`, `search_all_time`), 本周 (`.thisWeek`),
本月 (`.thisMonth`), 今年 (`.thisYear`), `vm.timeRangeLabel` (`.custom`; `L10n.dateRange` or
"自定义"/"Custom", `SearchViewModel.swift:140-145`). Tap `.custom` ⇒ open the time sheet;
any other ⇒ `setTimeKind(kind)` which re-searches (`:212-220`).

Place chips (`:200-207`), in order: 全部地点 (`search_all_loc`; country `""`, noLoc false) and
`vm.locFilterLabel` as the custom chip (`SearchViewModel.swift:71-76`: noLoc → "未记录地点";
empty country → "选择地点…"/"Pick a place…"; country only → `"<country> · 仅国家"`;
both → `"<country> · <region1>"`).
Active logic (`:222-235`): the custom chip is active when `country != "" || noLoc`; the "all" chip is
active when the filter is empty. Tapping an active chip resets the location filter; tapping the empty
custom chip opens the location sheet (`.large` detent); tapping a real row selects it and closes the sheet.

### 4.4 Active-condition summary row (the "keyword row") — `:73-126`

Shown only when `vm.hasAnyCondition` (`:50`), i.e. any committed keyword, the live non-empty keyword,
or a time/location filter (`SearchViewModel.swift:95-97`).

```
HStack(spacing: 8)                                          :74
├─ horizontal ScrollView → HStack(spacing: 6) of filterCapsule(item)   :75-81
└─ GlassIconButton("xmark.circle.fill", size: 28,
     accessibilityLabel: search_clear_all) → searchFocused = false; vm.clearAll()   :82-86
.padding(.leading, 12) · .padding(.trailing, 4) · .frame(minHeight: 44) · .diaryCard(20)   :88-91
```

Items come from `activeFilterItems` (`SearchViewModel.swift:99-138`), in this order: the
**live** keyword (only if not yet committed) rendered as `“<term>”` with `value == nil`, each
**committed** keyword `“<term>”` with `value == term`, then time, then location.

`filterCapsule` (`:94-126`): `HStack(spacing: 4)`: label 12 pt `.medium` `lineLimit(1)` +
`xmark` **9 pt** `.semibold`; fg `primary`; `padding(.horizontal, 10)`, `padding(.vertical, 6)`;
background `Capsule().fill(Theme.primaryContainer())`; `.buttonStyle(.plain)`;
`.accessibilityLabel(item.label)` + `.accessibilityHint(search_clear_all)`.
Tap: keyword with a value → `removeKeyword`; keyword without value → clear the input and drop focus;
time/location → `clearFilter(kind)` (`:96-106`).

### 4.5 Results list — `:237-266`

`ScrollView` → `LazyVStack(spacing: 10)` → `.adaptivePagePadding()` + `.padding(.top, 10)`,
`.scrollDismissesKeyboard(.immediately)`.
Order inside the stack: result rows → "load more" button → empty state → `TabBarClearance()`.

**Result row** (`resultItem`, `:312-342`):

| Slot | Spec | Line |
|---|---|---|
| Card | `HStack(spacing: 10)`, `padding(14)`, `diaryCard(20)` | `:313, :340-341` |
| Left column | `VStack(alignment: .leading, spacing: 6)` | `:314` |
| Date capsule | `HStack(spacing: 6)`: `calendar` 11 pt + `L10n.formatDayKey(dayKey)`; 12 pt `.medium`; fg `primary`; padding h**8** v**4**; `Capsule().fill(primaryContainer)` | `:315-326` |
| Snippet | `HighlightedText(snippet:summary:keyword:)`, 13 pt, `onSurface`, `lineLimit(2)` | `:327-330` |
| Spacer | pushes the right column | `:332` |
| Time | `L10n.timeOf(updatedUtc)`, 12 pt, `onSurfaceVariant` | `:333-335` |
| Chevron | `chevron.right` 12 pt, `onSurfaceVariant` | `:336-338` |
| Tap | `.onTapGesture { Haptics.tap(); vm.openDiary?(item.dayKey) }` — plain gesture, **not** a Button | `:242-245` |

`HighlightedText` (`:483-514`): if the snippet contains the `<hl>` marker, segments come from
`SearchUtil.highlightSnippet`; else the summary is re-scanned for `combinedKeyword`.
A hit segment is drawn with `foregroundColor = primary`, `backgroundColor = primaryContainer`,
and `font = .system(size: scaled(TypeSize.meta), weight: .medium)` (`:502-510`). `L10n.formatDayKey`
yields e.g. "9月16日 周三" / "Wed, Sep 16" and `L10n.timeOf` follows the device 12/24-hour setting.

**Load more** (`:247-252`): `GlassPrimaryButton(search_load_more, compact: true)` → `vm.loadMore()`,
`.padding(.vertical, 8)`. Shown while `vm.hasMore`.

### 4.6 Empty / loading states — `:281-310`

| Condition | UI | Top padding |
|---|---|---|
| `vm.indexing \|\| vm.searching` | `VStack(spacing: 12)`: `ProgressView().controlSize(.large)` tinted `primary` + text 15 `.medium` `onSurface` — "正在准备搜索索引…"/"Preparing search index…" or "正在搜索…"/"Searching…" | 60 |
| `vm.hasAnyCondition` and no rows | `VStack(spacing: 16)`: `ContentUnavailableView.search(text: vm.combinedKeyword)` (system "No Results for ...") + `GlassPrimaryButton(search_clear_all, compact: true)` → clears focus and calls `vm.clearAll()` | 60 |
| no condition at all | `ContentUnavailableView { Label("搜索你的日记"/"Search your diary", systemImage: "magnifyingglass") } description: { Text("输入关键词，或选择时间与地点筛选"/"Type a keyword or filter by time and place") }` | 60 |

The list-level empty state is only rendered when `vm.results.isEmpty && !vm.searching` (`:253`).

### 4.7 Hiding the top controls on scroll — `:263-279`

`.onScrollGeometryChange` reads `contentOffset.y`, `contentSize.height`, `containerSize.height`.
* Already hidden ⇒ hide while `y > 8`.
* Visible ⇒ hide when `y > 24` **and** `contentSize.height > containerSize.height + extra + 24`,
  where `extra = topControlsHeight` (measured, fallback **170**) (`:274`).
* Any state change is animated with `.diaryStandard` (`:278`). Refocusing the field immediately
  re-shows the controls (`:64-68`).

### 4.8 Custom time sheet — `:346-396`

Identical structure to the footprint sheet (§3.6, same `search_time_*` strings and a `.medium` detent)
with two differences: the quick chips call `applyQuickRange(kind)` (which dismisses the sheet itself,
`SearchViewModel.swift:179-185`) and "Clear" calls `clearTimeFilter()` which also dismisses
(`:187-193`).

### 4.9 Location picker sheet — `:398-448`

`.presentationDetents([.large])`, `VStack(spacing: 10)`, `padding(20)`, `padding(.bottom, 8)`,
`onAppear { vm.loadLocationOptions() }`.

1. `GlassSheetHeader(search_loc_title)` = "选择地点"/"Choose a location".
2. `HStack(spacing: 8)`: `magnifyingglass` 14 pt `onSurfaceVariant` + `TextField(search_loc_search)`
   15 pt tint `primary`, `.submitLabel(.search)`; `padding(.horizontal, 12)`, `frame(minHeight: 44)`,
   `diaryCard(cornerRadius: Radius.field /*14*/, interactive: true)` (`:415-418`).
   Placeholder "搜索地点…"/"Search places…".
3. Hint `search_loc_sort_hint` = "按日记数量排序"/"Sorted by entries count", 12 pt `onSurfaceVariant`,
   leading (`:419-422`).
4. `ScrollView` + `VStack(spacing: 0)` of rows, `.scrollDismissesKeyboard(.immediately)`:
   `search_all_loc` row, then a "未记录地点 (N)" row when `vm.noLocCount > 0` (`:426-428`), then
   `vm.filteredOptions` rendered as `"<country> · <region1>"` (`:429-432`).
   If the filtered list is empty and not loading: `search_loc_empty` = "暂无地点数据"/"No location data yet",
   13 pt `onSurfaceVariant`, `padding(.top, 40)` (`:433-438`).

**Location row** (`locRow`, `:450-480`): `HStack`: label 15 pt, tint `primary` when selected else
`onSurface`, `lineLimit(1)`; `Spacer`; count 12 pt `onSurfaceVariant` when non-nil; `checkmark`
**13 pt** `.semibold` `primary` when selected; `padding(.horizontal, 12)`,
`frame(minHeight: 46)`; background
`RoundedRectangle(cornerRadius: Radius.badge /*12*/, style: .continuous)`
filled `active ? Theme.glowColor() /* #2563EB @15% */ : .clear` (`:472-477`);
`.buttonStyle(.plain)`; tap → `selectLocRow(...)` fires haptics, sets the filter and closes the sheet
(`SearchViewModel.swift:172-177`).

---

## 5. SETTINGS (`SettingsView.swift`)

Data: `SettingsViewModel` wrapping `AppSettings` (`dayStartHour` default **4**, `autoTime` true,
`autoLoc` true, `allowHistoryEdit` false, `themeMode` "system", `appLanguage` "system",
`weekStart` "monday", `remindEnabled` true, `remindHour` **21**, `remindMinute` **0** —
`Models/AppSettings.swift:4-15`), plus live status strings `locStatusText` / `notifStatusText`
(`SettingsViewModel.swift:25-34`) and `busyText` / `resultAlert` for async work.

### 5.1 Structure top-to-bottom

```
ScrollView(showsIndicators: false)                        :8
└─ VStack(spacing: 12)                                    :9
   ├─ header (PageHeader("设置"), no trailing)             :101-103
   ├─ generalCard    通用                                  :231-256
   ├─ rulesCard      日记规则                               :276-317
   ├─ reminderCard   提醒                                  :321-345
   ├─ dataCard       数据                                  :349-367
   ├─ aboutCard      关于                                  :371-377
   └─ TabBarClearance()                                    :16
   .adaptivePagePadding() · .padding(.top, 12)             :19-20
```
Refresh triggers: `.task { await vm.refreshStatus() }` (`:22`), `.uiTickChanged` → `refreshSettings()`
(`:23-25`), `didBecomeActive` → `refreshStatus()` (`:26-28`).
Single column in **both** orientations (`docs/adaptive-layout-plan.md:193-196`).

### 5.2 Card and row primitives

`card { … }` (`:107-112`): `VStack(spacing: 0)` + `diaryCard(cornerRadius: 20)`.

`sectionTitle(_:)` (`:114-122`): `TypeSize.sectionTitle` **13** `.medium`, `onSurfaceVariant`,
leading-aligned, `padding(.horizontal, 16)`, `padding(.top, 12)`, `padding(.bottom, 0)`.

`rowLabel(icon:title:sub:value:)` (`:169-199`) — the shared row body:

| Slot | Spec | Line |
|---|---|---|
| HStack | `HStack(spacing: 12)` (text gap = `Spacing.rowIcon`) | `:170` |
| Icon badge | `GlassIconBadge(systemName:)` — 38×38, radius 12, `primaryContainer` fill + 1 pt `glassBorder`, glyph 16 pt `onSurface` | `:171` |
| Text column | `VStack(alignment: .leading, spacing: 2)` | `:172` |
| Title | `TypeSize.rowTitle` **15**, `onSurface` | `:173-175` |
| Subtitle | optional, `TypeSize.rowSub` **13**, `onSurfaceVariant`, `lineLimit(2)` | `:176-181` |
| Spacer | `minLength: 8` | `:183` |
| Value | `TypeSize.rowValue` **15**, `onSurfaceVariant`, `lineLimit(1)`, `minimumScaleFactor(0.7)`, `.trailing` | `:186-191` |
| Chevron | `chevron.right`, `TypeSize.rowValue` **15**, `onSurfaceVariant` | `:192-194` |
| Row frame | `padding(.horizontal, 12)`, `frame(minHeight: 56)`, `contentShape(Rectangle())` | `:196-198` |

`valueRow` (`:124-132`) = that body inside a `Button(.plain)` with `Haptics.tap()`.
`menuRow` (`:142-152`) = that body as a `Menu` label with `.buttonStyle(.plain)`,
`.menuOrder(.fixed)` and `.accessibilityValue(value)`.
`option(_:selected:action:)` (`:155-167`) = a menu entry; selected ones render
`Label(title, systemImage: "checkmark")` and carry `.isSelected`.
`switchRow` (`:201-227`) = HStack(spacing 12) with badge + text column + `Spacer(minLength: 8)` +
`Toggle("", isOn:).labelsHidden().tint(Theme.primary()).accessibilityLabel(title)`;
`.onChange` fires `Haptics.tap()` then the callback; `padding(.horizontal, 12)`, `frame(minHeight: 56)`.

Separators: `RowDivider(horizontalPadding: RowDivider.textInset /* 62 */)` between rows
(`:244, 284, 293, 298, 304, 311, 329, 337, 361`).

### 5.3 `generalCard` — 通用 / General (`:231-256`)

| Row | Icon | Title / Subtitle | Control | Options |
|---|---|---|---|---|
| 1 | `globe` | 语言 / 应用内显示语言 (`settings_language`, `settings_language_sub`) | `Menu` | 跟随系统 `system`, 中文 `zh`, English `en` — value label = `langLabel` (`:266-272`) |
| 2 | `paintpalette` | 主题 / 切换浅色、深色或跟随系统 (`settings_theme`, `settings_theme_sub`) | `Menu` | 跟随系统 `system`, 浅色 `light`, 深色 `dark` — value = `themeLabel` (`:258-264`) |

### 5.4 `rulesCard` — 日记规则 / Diary rules (`:276-317`)

| # | Icon | Title / Subtitle (keys → en) | Control | Value / default |
|---|---|---|---|---|
| 1 | `sun.horizon` | 新一天的开始时间 / 在此时间之前写的内容，归入前一天 (`settings_day_start[_sub]`) | `valueRow` → hour wheel sheet | `L10n.dayStartLabel(hour)`; hour **4** renders "凌晨 4:00" / "4:00 AM" (`time_label_dawn` = `%1$ld:00 AM`) |
| 2 | `calendar` | 一周从哪一天开始 / 选择日历从周一开始还是周日开始 (`settings_week_start[_sub]`) | `Menu` | 周一 `monday` / 周日 `sunday` (default monday) |
| 3 | `clock` | 自动插入时间 / 打开编辑器时自动记录开始时间 (`settings_auto_time[_sub]`) | `Toggle` | default **true** |
| 4 | `location` | 自动插入地点 / 需要位置信息权限 (`settings_auto_loc[_sub]`) | `Toggle` | default **true** |
| 4b | `location.circle` | 位置权限 / 地点仅保存在本机，不会上传 (`settings_loc_permission[_sub]`) | `valueRow` | shown **only when `autoLoc`** (`:303`); value = 精确/模糊/未授权 (`settings_loc_exact` / `_fuzzy` / `_none`) from `LocStatus`; tap requests permission or opens system Settings (`SettingsViewModel.swift:95-108`) |
| 5 | `pencil` | 允许修改 / 删除历史日记 / 仅可修改或删除已有编辑块，历史日期不可新增内容 (`settings_history_edit[_sub]`) | `Toggle` | default **false** |

### 5.5 `reminderCard` — 提醒 / Reminders (`:321-345`)

| # | Icon | Title / Subtitle | Control | Notes |
|---|---|---|---|---|
| 1 | `bell` | 写日记提醒 / 当天未写日记时提醒我 (`settings_remind_enabled[_sub]`) | `Toggle` | default **true**; ON requests notification permission, OFF cancels (`SettingsViewModel.swift:79-93`) |
| 2 | `clock.badge` | 提醒时间 / 每天提醒一次 (`settings_remind_time[_sub]`) | `valueRow` → time wheel sheet | shown only when `remindEnabled` (`:328`); value = `L10n.timeLabel(21, 0)` |
| 3 | `bell.badge` | 通知权限 / 关闭后无法收到提醒 (`settings_notif_permission[_sub]`) | `valueRow` | shown only when `remindEnabled && notifStatusText == "未开启"` (`:336`); tap requests permission |

### 5.6 `dataCard` — 数据 / Data (`:349-367`)

| # | Icon | Title / Subtitle | Control | Options |
|---|---|---|---|---|
| 1 | `square.and.arrow.up` | 导出全部日记 / 生成 .jdiary 备份包 (`settings_export[_sub]`) | `Menu`, empty value | 仅日记数据 → `runExport(includeSettings: false)`; 日记 + 设置 → `true` |
| 2 | `square.and.arrow.down` | 导入备份 / 从其他设备恢复日记 (`settings_import[_sub]`) | `valueRow`, empty value | opens the document picker |

### 5.7 `aboutCard` — 关于 / About (`:371-377`)

One `valueRow`: icon `info.circle`, title 版本/Version (`settings_version`), **no subtitle**,
value = `SettingsStore.appVersion` (`CFBundleShortVersionString`, fallback "1.0" —
`Models/AppSettings.swift:84-86`), action `{}` (no-op, still shows the chevron).

### 5.8 Sheets, dialogs, overlay

| Surface | Spec | Line |
|---|---|---|
| Export share sheet | `ShareLink(item: url, preview: SharePreview("EntryAbility_label"))`, `.buttonStyle(.glassProminent)`, `padding(24)`, `presentationDetents([.height(170)])` | `:29-36` |
| Import picker | `DocumentPicker` (`UIDocumentPickerViewController(forOpeningContentTypes: [.data])`, single selection) | `:37-44, 433-462` |
| Import mode | `confirmationDialog(settings_import_mode_title, titleVisibility: .visible)` with 跳过已有日记 / 覆盖已有日记 / 取消(cancel) — kept as a dialog because it confirms a destructive overwrite | `:45-54` |
| Day-start picker | `.presentationDetents([.medium])`, `hourPickerSheet`: `VStack(spacing: 16)` + `padding(20)`, `GlassSheetHeader`, `Picker(.wheel)` over `0..<24` labelled with `L10n.timeLabel(hour:minute:0)`, `labelsHidden`, `GlassPrimaryButton(save, fullWidth: true)` | `:55-62, 388-407` |
| Reminder time picker | `.presentationDetents([.medium])`, `timePickerSheet`: `DatePicker(displayedComponents: .hourAndMinute).datePickerStyle(.wheel)`, locale injected, same Save button | `:63-75, 414-430` |
| Busy overlay | `Color.black.opacity(0.25).ignoresSafeArea()` + `VStack(spacing: 14)`: `ProgressView().controlSize(.large)` tint `primary` and busy text (`settings_export_busy` = "正在生成备份…", `settings_import_busy` = "正在导入备份…") 13 pt `.medium` `onSurface`; container `padding(.horizontal, 32)`, `padding(.vertical, 26)`, `diaryCard(20)`; `.transition(.opacity)` | `:76-95` |
| Result alerts | `appAlert(item: $vm.resultAlert)` → `Components.swift:502-519` | `:96` |

Alert strings: export failed / import failed / import complete
(`settings_export_failed_title|_msg`, `settings_import_failed_title|_msg`,
`settings_import_success_title|_msg` = "Added %d days, skipped %d, overwritten %d (%d blocks, %d images)."),
`settings_settings_restored`, `settings_day_recalc_title|_msg` ("%d entries kept their original date…").

---

## 6. Interaction table (complete)

### 6.1 Root / tab bar

| Control | Action | Result |
|---|---|---|
| Tab 日记 (house.fill) | select `.home` | shows `HomeView`; tab re-render is cheap (Home keeps its own state) |
| Tab 足迹 (figure.walk) | select `.footprint` | shows `FootprintView`; `.task` loads data once |
| Tab 搜索 (magnifyingglass) | select `.search` | shows `SearchView` |
| Tab 设置 (gearshape.fill) | select `.settings` | shows `SettingsView` |
| Scroll down on any tab | system minimize behavior | floating bar minimizes (`.tabBarMinimizeBehavior(.onScrollDown)`) |
| Tap a search result / "write" entry point | `openDiary(dayKey)` | `appState.editorDayKey = dayKey`, `presentEditor = true` → `fullScreenCover` diary editor; on dismiss `bumpDiaryVersion()` (`RootView.swift:86-90`) |

### 6.2 Footprint

| Control | Action | Effect |
|---|---|---|
| Year chip 全部 / 2024… | tap | `yearFilter = value`, `timeKind = .all`, `reload()`, haptic (`:85-87`) |
| Custom chip (自定义 / date range) | tap | haptic + time sheet (medium detent) (`:25-29`) |
| Place row (non-leaf) | tap | haptic, animate expand/collapse, chevron rotates to 90°, row gets `.isSelected` (`:281-315`) |
| Place row (leaf) | tap | no-op (`guard !node.isLeaf else { return }`) |
| Sheet quick chip 本周/本月/今年 | tap | set kind, dismiss, reload (`:243-249`) |
| Sheet start/end `DatePicker` | change | writes `customFrom` / `customTo` (start-of-day), no immediate reload |
| Sheet 清除 | tap | `timeKind = .all`, clear dates, dismiss, reload (`:226-230`) |
| Sheet 确定 | tap | `timeKind = .custom`, dismiss, reload (`:231-235`) |
| Scroll | — | vertical only; no pull-to-refresh, no infinite scroll |
| Empty list | — | `mappin.slash` + "还没有地点记录" (`:182-184`) |

### 6.3 Search — see §4 for details

| Control | Action | Effect |
|---|---|---|
| Search field typing | 250 ms debounce | `doSearch(reset: true)` (`SearchViewModel.swift:153-160`) |
| Search field Submit | `commitKeyword()` | trims, clears the input, appends to `keywords` (dedup), re-searches (`:211-219`) |
| Search field ✕ | tap | clears the live text only |
| 取消 (appears while focused) | tap | drops focus; the top controls stay visible |
| Time chip ×5 | tap | all/week/month/year re-search; custom opens the sheet (`:212-220`) |
| Place chip ×2 | tap | "all" resets; custom opens the location sheet; active chip resets (`:222-235`) |
| Filter capsule | tap | removes that one condition and re-searches (`:96-106`) |
| Clear-all icon | tap | drops focus + `clearAll()` (clears keywords, time and place) (`:82-86`) |
| Result row | tap | haptic + opens the diary for that `dayKey` (`:242-245`) |
| 加载更多 | tap | `loadMore()` appends the next page (`:247-252`) |
| Scroll down | — | hides search bar + filter panel once the content is taller than the viewport; keyboard dismisses immediately (`:262-279`) |
| Location sheet row | tap | haptic, sets the filter, closes the sheet, re-searches (`SearchViewModel.swift:172-177`) |

### 6.4 Settings

| Control | Action | Effect |
|---|---|---|
| 语言 menu | pick | `setLanguage` → save + reload + `bumpUiTick()` + reminder re-arm (`SettingsViewModel.swift:46-52`) |
| 主题 menu | pick | `setTheme` → save + `AppConfigService.applyAll()` (`:40-44`) |
| 新一天的开始时间 | pick hour → 保存 | `save()` + `recomputeDayKeys`; if conflicts > 0 shows `settings_day_recalc_title/msg` alert (`:110-121`) |
| 一周从哪一天开始 | pick | `setWeekStart` + save + `bumpUiTick()` (`:54-58`) |
| 自动插入时间 toggle | on/off | `setAutoTime` + save (`:60-63`) |
| 自动插入地点 toggle | on/off | `setAutoLoc` + save; ON requests permission and backfills block regions (`:65-72`) |
| 位置权限 row | tap | notDetermined → request; otherwise open system Settings; refresh status after 800 ms (`:95-108`) |
| 允许修改 / 删除历史日记 toggle | on/off | `setAllowHistoryEdit` + save (`:74-77`) |
| 写日记提醒 toggle | on/off | ON requests notification permission + ensures the daily reminder; OFF cancels all (`:79-93`) |
| 提醒时间 row | pick time → 保存 | `applyRemindTime` → save + re-arm (`:123-126`) |
| 通知权限 row | tap | `requestNotificationPermission()` then refresh (`:128-133`) |
| 导出 menu | pick scope | busy overlay → export → share sheet, or a failure alert (`:135-150`) |
| 导入备份 row | tap | document picker → mode dialog (skip/overwrite) → busy overlay → result alert (`:152-177`, `SettingsView.swift:45-54`) |
| 版本 row | tap | no-op action, still renders a chevron |

---

## 7. Accessibility semantics

**Provided by the system** (no app code):
* Tab items: label = the localized `Tab` title, icon described by the SF Symbol; selected state is
  the standard tab-bar trait. No custom labels or hints exist for the tab bar.
* `ContentUnavailableView` / `ContentUnavailableView.search` supply title + description semantics.
* `Toggle` (settings switches) get `accessibilityLabel(title)` explicitly (`SettingsView.swift:219`)
  and standard on/off value semantics; `.labelsHidden()` removes the empty visual label.
* `DatePicker` / `Picker` / `Menu` keep their platform semantics; `Menu` also gets
  `.accessibilityValue(value)` (`:151`), and the selected menu entry carries `.isSelected` (`:166`).
* `ShareLink` inherits the localized share semantics from `SharePreview`.

**Explicit in code**:

| Element | Semantics | Line |
|---|---|---|
| Footprint tree row | `.accessibilityIdentifier("footprint.node")` (UI tests address rows by this), `.accessibilityAddTraits(.isSelected)` when expanded; role = button via `Button` + `.plain` | `FootprintView.swift:314-315` |
| Footprint trend chart | `.accessibilityLabel("年度记录天数")` on the chart container | `:150` |
| Any chip / glass button | `.accessibilityAddTraits(.isSelected)` when active (`GlassChip`, `GlassActionChip`, `PressableGlassIcon`) | `Components.swift:371, 452, 325` |
| `GlassIconButton` | `.accessibilityLabel(label ?? systemName)` — so the ✕/clear buttons read a human label; callers pass `search_clear_all` for the search clear-all | `:291`, `SearchView.swift:83` |
| Search filter capsule | `.accessibilityLabel(item.label)` (e.g. `“雾”`) + `.accessibilityHint("清除全部条件")` | `SearchView.swift:124-125` |
| Settings info capsule rows | value is part of the button label (title + subtitle + value are separate `Text`s inside one `Button`) | `SettingsView.swift:124-132` |

**Increase-contrast**: `diaryCard` switches to 1 pt `onSurface` α0.55 + 1.5 pt `outlineVariant` α0.9
borders (`Components.swift:120,126,132-136`).

**Reduce-motion**: only `FlowLightOverlay` reacts (`:650, 658`); it is not instantiated by these screens.

**Known gaps (worth fixing in a port, do not copy blindly)**:
1. Footprint stat cells are two independent `Text`s — VoiceOver reads "7" then "省市", and no
   `.accessibilityElement(children: .combine)` merges them.
2. The footprint tree row's count is a separate `Text`; the row reads as "中国" then "9".
3. `AccessibilityIdentifier` exists only for footprint rows; search/settings rows have none.
4. Search result rows are tap gestures on a card, not buttons — no button trait, no hint that they open
   the diary.
5. The search field's inline ✕ (`xmark.circle.fill`) has no explicit label.
6. `GlassIconBadge` images expose raw SF Symbol names to the accessibility tree inside settings rows.
7. `docs/design-system.md:224-235` already records that the calendar's adjustable action lost its
   `a11y_next_day` / `a11y_prev_day` labels — unrelated to these three screens but part of the same audit.

---

## 8. Landscape deltas (874 × 402, usable 750 × 382)

Derived values are computed from `layout.size` = **screen bounds** (`874 × 402`) and
`layout.contentWidth` = **750** (`AdaptiveLayout.swift:32-34`).

| Screen | Portrait | Landscape |
|---|---|---|
| Footprint | 1 column: stats → trend → list, all scrolling | `VStack(spacing: 12)`: stats, then `HStack(alignment: .top, spacing: 12)` of `trendCard(chartHeight:)` (flex) + `listCard(scrollable: true)` pinned to `listPaneWidth`, wrapped in `.frame(height: blockH)` (`FootprintView.swift:43-61`) |
| Footprint sizes | chart height 132 (`:145`) | `blockH = max(140, min(190, 402 − 262)) = `**`140`** (`:48`); `chartHeight = max(72, 140 − 58) = `**`82`** (`:53`); `listPaneWidth = min(320, max(240, 750 × 0.4)) = `**`300`** (`:160-162`) |
| Footprint bottom gap | `max(24, 34 + 44) = `**`78`** (`:67`) | `max(24, 20 + 44) = `**`64`** (clears the floating bar whose top edge is y = 338) |
| Search | time group stacked over place group (`:161-165`) | two groups side by side `HStack(alignment: .top, spacing: 16)` (`:167-170`) |
| Search results | single vertical column | identical single column, rows simply wider (`docs/adaptive-layout-plan.md:182-188`) |
| Settings | single column | single column, unchanged (the landscape mockup's "2 columns" is not implemented — see §9) |

Portrait-only: footprint's `layout.isPortrait` branch (`:36-42`). All three screens share
`layout.isPortrait = size.height >= size.width` (`AdaptiveLayout.swift:46`).

---

## 9. Mockup cross-check and discrepancies

Read: `ph-footprint-portrait.png`, `ph-search-portrait.png`, `ph-settings-portrait.png`,
`ph-footprint-landscape.png`, `ph-search-landscape.png`, `ph-settings-landscape.png`
(2× exports of `docs/design/landscape/phone.html`).

Confirmed visually:
* Footprint: title 足迹 + "已标注 14 处 · 未记录 3 篇", active 全部 chip and inactive year chips +
  自定义, five-cell stat row (7/9/4/14/13 → 省市/城市/国家/片段/天数), "年度记录天数" bar card with
  leading y-axis (0/3/4/6) and 2024/2025/2026, "地点清单" card with globe icons, counts and chevrons.
* Search: glass capsule search field, "时间范围"/"地点" label + chip groups, empty state
  "搜索你的日记 / 输入关键词，或选择时间与地点筛选" with a magnifier glyph.
* Settings: five cards in order, section titles, 38 pt icon badges, title+subtitle+value+chevron rows,
  blue toggles, and the "凌晨 4:00" / "周一" values.
* Landscape footprint: stats row, then chart | list side by side, list ≈300 pt wide, floating tab bar
  overlapping the last row (hence the 64 pt bottom pad).

Discrepancies to be aware of:
1. **`ph-settings-landscape.png` renders no card content** — only the device frame and the Dynamic
   Island are drawn; the caption claims "两列卡片 / 2 列 · 不等高" while both the code
   (`SettingsView.swift:9`) and `docs/adaptive-layout-plan.md:193-196` specify a **single column**.
   Treat the code as authoritative; the mockup is a defective export.
2. **`ph-search-landscape.png` draws the time/place chips inside the search row** and a separate
   "关键词" row. The implementation keeps the search field, the two-group filter panel and the
   combined condition-summary row as three distinct blocks (§4.2–4.4). The mockup is schematic.
3. **`ph-footprint-landscape.png` caption says the split threshold is 900 pt**; code and
   `docs/adaptive-layout-plan.md:47-51` use **700 pt**.
4. The task brief mentions "flags" in the place list — **there are none**; rows use
   `globe.asia.australia` / `map` / `mappin` (`FootprintView.swift:323-329`).
5. Mockup chrome tokens (`mockup.css:11-50`) match `DesignSystem.swift` exactly
   (radii 20/26/14/12/4, gaps 12/44, type ladder 32/24/20/18/15/13/14/12/13/12, `--primary #2563EB`),
   so the design file and the code agree on the token layer.

---

## 10. Porting checklist (what another platform must reproduce)

1. **Token layer first**: radii 20/26/14/12/4, spacing 12/12/44, type ladder above, brand `#2563EB`,
   the light/dark token table in §1.5, and an OS-text-size scaling curve with per-role ceilings
   (§1.4) that keeps the ladder monotonic.
2. **Card component**: system grouped background + 0.5 pt outlineVariant α0.45 + shadow α0.14/8/2,
   radius 20, `continuous` corners; swap to the high-contrast variant when the OS reports increased contrast.
3. **Control layer**: chips/capsules are translucent and interactive; content cards are opaque.
   Do not blur content cards.
4. **Tab bar**: 4 destinations, system-provided bottom bar, brand-tinted selection, minimise-on-scroll,
   labels Diary/Footprint/Search/Settings, icons house/图/figure.walk/magnifyingglass/gearshape,
   and content clearance of 120 pt (or 64–78 pt of derived inset) beneath the last row.
5. **Per-screen vertical order** exactly as §3.1, §4.1, §5.1 — including the "trend card only with
   ≥2 years" rule, the "top controls hide on scroll" rule, and the "location permission row only when
   auto-location is on" / "reminder time row only when reminders are on" conditional rows.
6. **Empty states**: 5 distinct ones (§6.1 footprint, §4.6 search ×3, §4.9 location sheet).
7. **A11y**: keep the identifiers, the `.isSelected` traits for expanded tree nodes / active chips /
   selected menu entries, the hint on removable filter capsules, and add the six fixes listed in §7.

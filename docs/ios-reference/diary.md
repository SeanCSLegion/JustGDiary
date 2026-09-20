# iOS diary READ screen, EDITOR screen and share-as-long-image renderer — factual reference

Scope: the **iOS (SwiftUI + UIKit-backed) implementation only**. This document extracts what the
shipped iOS code does; it contains no cross-platform guidance and no porting recommendations.

Sources (all cited as `path:line`):
`JustDiary/Views/Diary/DiaryPageView.swift`, `ReadTextView.swift`, `RichTextView.swift`,
`RichTextEngine.swift`, `MediaViews.swift`; `JustDiary/Views/Components/Components.swift`,
`DesignSystem.swift`, `DynamicType.swift`, `AdaptiveLayout.swift`;
`JustDiary/Services/ImageShareService.swift`, `MediaStore.swift`, `Theme.swift`, `Haptics.swift`,
`SearchUtil.swift`, `LocationService.swift`; `JustDiary/ViewModels/DiaryViewModel.swift`;
`JustDiary/Models/ContentPart.swift`, `DiaryModels.swift`;
`JustDiary/Resources/Localizable.xcstrings`; `docs/editor-typography.md`; `docs/design-system.md`.

Reference device: **iPhone 18 Pro**, portrait 402×874 pt, landscape 874×402 pt.
Measured safe areas (`docs/design-system.md:314-317`): portrait T62 L0 B34 R0; landscape T0 L62 B20 R62
(landscape tab bar frame measured `(0, 338, 874, 64)`).

---

## 0. Geometry and tokens these screens use

| Token | Value | Defined at | Used by |
|---|---|---|---|
| `Screen.safeAreaBottom` | presenting scene key-window `safeAreaInsets.bottom` | `JustDiary/Views/Components/Components.swift:44-55` | format-bar bottom padding |
| `Radius.card` | 20 | `JustDiary/Views/Components/DesignSystem.swift:15` | hero card, block card, editor card, `diaryCard` default |
| `Radius.panel` | 26 | `DesignSystem.swift:17` | format bar (`FontToolbar`) surface |
| `Radius.image` | 14 | `DesignSystem.swift:22` | inline images (read, editor, inserted bitmap) |
| `Radius.badge` | 12 | `DesignSystem.swift:24` | `GlassIconBadge`; share-renderer checkbox |
| `Radius.concentric(outer:inset:)` | `max(0, outer - inset)` | `DesignSystem.swift:31-33` | share-renderer nested corners |
| `Spacing.card` | 12 | `DesignSystem.swift:41` | block-card padding |
| `Spacing.hitTarget` | 44 | `DesignSystem.swift:45` | search prev/next buttons |
| `TypeSize.cardTitle` | 20 | `DesignSystem.swift:63` | read-hero date |
| `TypeSize.meta` | 13 | `DesignSystem.swift:83` | hero meta, block time/location, editor time, search steppers |
| `TypeSize.caption` | 12 | `DesignSystem.swift:85` | hero "Today" badge, block location, location chip glyph |
| `TypeSize.rowTitle` | 15 | `DesignSystem.swift:69` | search field text, tab-bar clearance factor |
| `TypeSize.badge` | 12 | `DesignSystem.swift:81` | `GlassCountBadge` |
| `TypeSize.chip` | 14 | `DesignSystem.swift:79` | chips |
| `AdaptiveLayout.pagePadding` | 16 (leading and trailing) | `AdaptiveLayout.swift:74-76` | page padding applied outside the content column |
| `contentColumn(maxWidth)` | `min(maxWidth, max(240, size.width - 2*16))` | `AdaptiveLayout.swift:62-64` | read ≤ 660, edit ≤ 620 |

Computed for the reference device (using `size.width` from the scene's `screen.bounds`,
`AdaptiveLayout.swift:139-166`):

- Portrait: read column `min(660, 402-32) = 370 pt`; edit column `min(620, 370) = 370 pt`.
- Landscape: `size.width = 874`, so read column `= 660 pt`, edit column `= 620 pt`
  (`DiaryPageView.swift:146`).
- `contentWidth` (used only for `splitsMasterDetail`) = `874 − 62 − 62 = 750 pt`
  (`AdaptiveLayout.swift:32-34`); `splitsMasterDetail = contentWidth >= 700 && width > height`
  (`AdaptiveLayout.swift:41-43`) ⇒ **true in landscape**, so the format bar becomes vertical
  (`DiaryPageView.swift:22`).

Page background is `BlobBackground(dense: true)` (`DiaryPageView.swift:14`), i.e. `Theme.bg()` plus
three blurred circles drawn at fixed positions (`Components.swift:60-87`, dense branch `:68-73`).
Content cards use `diaryCard` = `secondarySystemGroupedBackground` fill, hairline border
(`outlineVariant` @ 0.45, or `onSurface` @ 0.55 + `outlineVariant` @ 0.9 at increased contrast) and a
soft shadow (`shadowColor` @ 0.14, radius 8, y 2 by default; @ 0.22 / radius 12 / y 4 when
`interactive: true`) (`Components.swift:114-144`). Liquid Glass is used only on the control layer
(`Components.swift:272-293`, `diaryCard` doc comment `:106-113`; `docs/design-system.md:176-206`).

Page scroll container: `ScrollView(showsIndicators: false)` → `VStack(spacing: 12)`,
`.padding(.top, 10)`, `maxWidth = contentColumn(660 or 620)`, then `adaptivePagePadding()`
(16 pt each side), then `frame(maxWidth: .infinity)` (`DiaryPageView.swift:122-148`). Block
identity changes animate with `.animation(.diaryStandard, value: vm.blocks.map(\.id))`
(`DiaryPageView.swift:149`).

The top bar is a floating `safeAreaInset(edge: .top, spacing: 0)` with
`.padding(.horizontal, 16)`, `.padding(.top, 4)`, `.padding(.bottom, 6)`
(`DiaryPageView.swift:155-160`); the page itself uses `.ignoresSafeArea(edges: .bottom)`
(`DiaryPageView.swift:30`).

Bottom clearance: when the keyboard is up, a `Color.clear` spacer of `keyboardHeight + 160`; when
down, `TabBarClearance(base: 140)`, whose height is `140 × multiplier(TypeSize.rowTitle = 15)`
(`DiaryPageView.swift:137-141`; `Components.swift:589-599`).

---

## 1. READ mode

### 1.1 Vertical order
`[readSearchBar if vm.showSearch] → readHero → blockCard(0…n) → bottom spacer`, 12 pt apart
(`DiaryPageView.swift:123-142`). Read mode is the default state after `load()`
(`DiaryViewModel.swift:45-57`, `isRead = true` at `:56`).

### 1.2 Top bar (`DiaryPageView.swift:189-236`)
`HStack(spacing: 8)` + `.padding(.horizontal, 4)` (`:190`, `:235`).

| Position | Control | Symbol / label | Condition | Action |
|---|---|---|---|---|
| leading | `PressableGlassIcon` | `chevron.left`, a11y `a11y_back` ("Back" / 返回) | always | `vm.handleBack()` (`:191-194`) |
| trailing | `GlassPrimaryButton` | `trash`, title `L10n.fmt("editor_delete_count", count)` ("Delete (%d)" / 删除(%d)) | when `vm.selectMode` | `Haptics.tap()`; `vm.confirmDeleteSelected()` (`:196-201`) |
| trailing 1 | `PressableGlassIcon` | `magnifyingglass`, a11y `search_title` ("Search" / 搜索), `active: vm.showSearch` | read mode, not select mode | with `.diaryQuick`: toggle `showSearch`; on close clears `searchText` and `hits` (`:202-209`) |
| trailing 2 | `PressableGlassIcon` | `square.and.pencil`, a11y `index_write` ("Write" / 写日记) | read mode **and** `vm.canEditToday` | `vm.enterWrite()` (`:210-215`) |
| trailing 3 | `PressableGlassIcon` | `square.and.arrow.up`, a11y `a11y_share` ("Share" / 分享) | read mode | `vm.shareDiary()` (`:216-219`) |

`canEditToday` = `actualDayKey == DateUtil.dayKeyOf(Date())` (`DiaryViewModel.swift:39-41`); the
pencil is therefore hidden on past days (adding blocks to a past day is refused anyway,
`DiaryViewModel.swift:109-114`).

`PressableGlassIcon` geometry (`Components.swift:295-327`): visual circle diameter `size = 44`
(`:299`); because `.glass` adds 4 pt/side the label box is `labelSide = max(28, size - 8) = 36`
(`:307`, `:318`); glyph `.diaryFont(17, weight: .medium)` (`:315`), `.symbolRenderingMode(.hierarchical)`
(`:316`), tint `active ? Theme.primary() : Theme.onSurface()` (`:317`),
`.buttonBorderShape(.circle)` (`:322`), `.symbolEffect(.bounce, value: active)` (`:323`), a11y label
falls back to the raw system name (`:324`), `.isSelected` when active (`:325`).

`GlassPrimaryButton` (`Components.swift:177-227`) with `compact = false` (default `:182`):
icon `.diaryFont(13, weight: .semibold)` (`:193`), title `.diaryFont(TypeSize.rowTitle = 15,
weight: .medium)` `.lineLimit(1)` `.minimumScaleFactor(0.85)` (`:196-198`),
padding horizontal 20 / vertical 12 (`:201-202`), capsule fill `Theme.primary()` + brand-tinted
glass (`:216-219`), shadow `Theme.glowColor()` radius 10 y 3 (`:219`).

There is one `Spacer()` between the leading button and the trailing group (`DiaryPageView.swift:195`);
the read-mode trailing buttons are adjacent (no spacers between them).

### 1.3 In-page search bar (`DiaryPageView.swift:240-277`)
Rendered as the **first item of the scroll content** when `vm.showSearch` (`DiaryPageView.swift:125-127`).

- Field: `GlassSearchField(text: $vm.searchText, placeholder: L10n.str("read_search_placeholder"))`
  ("Search this entry…" / 在当前日记中查找…) (`:241-243`).
  `GlassSearchField` (`Components.swift:531-581`): `HStack(spacing: 8)` (`:551`) with magnifier glyph
  `.diaryFont(TypeSize.rowTitle = 15)` in `onSurfaceVariant` (`:552-554`), `TextField`
  `.diaryFont(15)`, tint `Theme.primary()`, `.submitLabel(.search)` (`:555-560`), clear button
  `xmark.circle.fill` at 15 pt shown only when text is non-empty (`:561-570`); container
  `.padding(.horizontal, 14)`, `.frame(minHeight: 46)`, background
  `Capsule().fill(.clear).glassEffect(.regular.interactive(true), in: Capsule())` (`:573-579`).
- Trailing slot, only when `vm.hits.count > 0` (`DiaryPageView.swift:244`):
  1. `GlassCountBadge(text: "\(min(vm.hitIndex + 1, vm.hits.count))/\(vm.hits.count)")` (`:245`).
     `GlassCountBadge` = `HStack(spacing: 5)`, text `.diaryFont(TypeSize.badge = 12, weight: .medium)`
     in `Theme.primary()`, padding h10/v5, `Capsule().fill(Theme.primaryContainer())`
     (`Components.swift:400-422`).
  2. `chevron.up` button, a11y `read_search_prev` ("Previous match" / 上一个匹配) → `vm.stepHit(-1)`
     (`:246-248`).
  3. `chevron.down` button, a11y `read_search_next` ("Next match" / 下一个匹配) → `vm.stepHit(1)`
     (`:249-251`).
- Stepper buttons are deliberately **plain** `Button`s, not glass (glass-on-glass refracts nothing)
  (`DiaryPageView.swift:256-262`). Geometry (`:263-277`): glyph
  `.diaryFont(TypeSize.meta = 13, weight: .semibold)` in `Theme.onSurfaceVariant()`,
  `.frame(minWidth: Spacing.hitTarget = 44, minHeight: 44)`, `.contentShape(Circle())`,
  `.buttonStyle(.plain)`, `Haptics.tap()` before the action, real a11y labels (`:265-276`).

Search semantics (`DiaryViewModel.swift:65-105`):
- Debounce **150 ms** after every `searchText` change, guarded by a generation counter so a newer
  keystroke cancels the pending compute (`:65-73`).
- `computeHits()` trims the keyword, lowercases it, and walks every block/part, recording
  `SearchHit(blockId:partIndex:start:len:)` for each non-overlapping occurrence of the **whole
  trimmed phrase** in `ContentFlatten.flattenPart(part).lowercased()` (`:75-99`). Empty keyword
  clears hits and resets `hitIndex = 0` (`:76-81`).
- `stepHit(delta)` wraps modulo: `hitIndex = (hitIndex + delta + hits.count) % hits.count`, then sets
  `scrollTarget = "block-\(hits[hitIndex].blockId)"` (`:101-105`). The view scrolls that id with
  `anchor: .center` under `withAnimation(.diaryStandard)` and clears `scrollTarget`
  (`DiaryPageView.swift:161-167`).
- Highlight: `ReadTextView.Coordinator.applyHighlight` runs only when `keyword` is non-empty
  (`ReadTextView.swift:105-107`). `SearchUtil.highlightSegments` splits the keyword on whitespace into
  terms, finds all case-insensitive occurrences, sorts and merges overlaps, and returns
  `(text, hit)` segments (`SearchUtil.swift:20-59`). Each hit segment gets
  `.backgroundColor = Theme.primaryContainerUIColor()` and `.foregroundColor = Theme.primaryUIColor()`,
  and the run's own design size is re-resolved (never a hard-coded 15 pt) with weight
  `.bold` if the run was bold else `.medium` (`ReadTextView.swift:113-138`).
  Note the two algorithms differ: hit **counting** is phrase-based (`DiaryViewModel.swift:89`)
  while **highlighting** is whitespace-term-based (`SearchUtil.swift:21-23`).

### 1.4 Read hero card (`DiaryPageView.swift:281-327`)
`VStack(alignment: .leading, spacing: 6)` (`:282`):

1. Date row `HStack(spacing: 8)` (`:283`):
   - `Text(L10n.formatDayKey(vm.actualDayKey))` at `.diaryFont(TypeSize.cardTitle = 20, weight: .medium)`,
     `Theme.onSurface()` (`:284-286`). Examples from `docs/design-system.md:166`: `9月15日 周二` / `Tue, Sep 15`.
   - If `vm.canEditToday`: `Text(L10n.str("index_card_today"))` ("Today" / 今日) at
     `.diaryFont(TypeSize.caption = 12, weight: .medium)`, white, padding h8/v3,
     `Capsule().fill(Theme.primary())`, `shadow(glowColor, radius: 6, y: 1)` (`:287-297`).
2. Meta `VStack(alignment: .leading, spacing: 4)` (`:299`):
   - If `vm.blocks.first` exists (`:300`):
     - `HStack(spacing: 8)` of `L10n.timeOf(first.startTimeUtc)` and
       `L10n.fmt("read_hero_segments", vm.blocks.count)` ("%1$ld moments" / "%1$ld 个片段"),
       both `.diaryFont(TypeSize.meta = 13)` in `Theme.onSurfaceVariant()` (`:301-308`).
     - If `first.locText` is non-empty: `Label(first.locText, systemImage: "location.fill")` at
       `.diaryFont(13)`, `onSurfaceVariant`, `.lineLimit(2)`, `.multilineTextAlignment(.leading)`
       (`:309-315`).
     - Only the **first** block's time is shown, not a range (`:302`).
   - Else: `Text(L10n.str("read_day_empty"))` ("No entry for this day." / 这一天没有留下日记。)
     at `.diaryFont(TypeSize.meta = 13)`, `onSurfaceVariant` (`:316-320`).
3. Container `.padding(14)`, `.frame(maxWidth: .infinity, alignment: .leading)`,
   `.diaryCard(cornerRadius: Radius.card = 20)`, `.padding(.bottom, 2)` (`:323-326`).

### 1.5 Block card (`DiaryPageView.swift:331-382`)
`VStack(alignment: .leading, spacing: 8)` (`:334`):

- Header `HStack(alignment: .top, spacing: 8)` (`:335`):
  - In `vm.selectMode`: `Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")`
    at `.diaryFont(16)`, tint `isSelected ? Theme.primary() : Theme.onSurfaceVariant()`,
    `.padding(.top, 1)` (`:336-341`).
  - `VStack(alignment: .leading, spacing: 3)` (`:342`):
    - `Text(L10n.timeOf(block.startTimeUtc))` at `.diaryFont(TypeSize.meta = 13, weight: .bold)`,
      `Theme.primary()` (`:343-345`).
    - If `block.locText` non-empty: `Label(locText, systemImage: "location.fill")` at
      `.diaryFont(TypeSize.caption = 12)`, `onSurfaceVariant`, `.lineLimit(2)`,
      `.multilineTextAlignment(.leading)` (`:346-352`).
  - `Spacer()` (`:354`); if `vm.editingIndex == index`: `GlassCountBadge(text: "Editing" / 编辑中)`
    (`:355-357`).
  - Header row is a tap target: `.contentShape(Rectangle())` + `.onTapGesture { handleBlockTap(...) }`
    (`:359-362`).
- Body: `DiaryPartsView(parts:, keyword: vm.showSearch ? vm.searchText : "",
  onToggleTodo:, onImageTap:, onTapText:)` (`:363-373`).
  - `onToggleTodo` → `vm.toggleTodo(block:partIndex:itemIndex:)`; `onImageTap` →
    `vm.previewImage = PreviewItem(src: src, ratio: ratio)`; `onTapText` → same handler as the header.
- Card: `.padding(Spacing.card = 12)` (`:375`), `.diaryCard(cornerRadius: Radius.card = 20)` (`:376`),
  `.onLongPressGesture(minimumDuration: 0.4) { vm.enterSelect(block.id) }` (`:377-379`),
  `.accessibilityElement(children: .contain)` (`:380`),
  `.accessibilityAddTraits(isSelected ? .isSelected : [])` (`:381`).
- `handleBlockTap` (`:384-395`): in select mode, `Haptics.tap()` then toggle membership of
  `vm.selectedIds` (no mode change); otherwise `vm.enterEditBlock(index: index)`.
  `enterSelect` is refused on past days unless `settings.allowHistoryEdit`
  (`DiaryViewModel.swift:157-167`).

### 1.6 Block style table (read rendering)
Styles are defined by `EditorBlockStyle` (`RichTextEngine.swift:19-89`) and drawn by
`PartsCodec.attributedString` (`RichTextEngine.swift:802-910`). Sizes are **design sizes**; the drawn
size is `size × DynamicTypeMetrics.multiplier(size, typeSize)` (`DesignSystem.swift:190-201`).
Text color for every block is `Theme.onSurfaceUIColor()` (`:858`, `:893`; `Theme.swift:42`).

| Stored `style` (`ContentPartStyle`) | Menu name (en/zh) | Design size | Apple text style | `lineSpacing` | `paragraphSpacing` | Background | Marker |
|---|---|---|---|---|---|---|---|
| `title` | Heading / 大标题 (`editor_font_heading`) | **28** | Title 1 | `28 × 0.13 = 3.64` | `28 × 0.4 = 11.2` | none | — |
| `heading` | Subheading / 小标题 (`editor_font_sub`) | **22** | Title 2 | `22 × 0.13 = 2.86` | `22 × 0.3 = 6.6` | none | — |
| `body` | Body / 正文 (`editor_font_body`) | **17** | Body | `17 × 0.13 = 2.21` | `0` | none | — |
| `quote` | (its own toolbar button) / 引用 | **15** | Subheadline | `15 × 0.5 = 7.5` | `15 × 0.4 = 6.0` | `Theme.quoteBgUIColor()` over the whole paragraph; alignment forced `.left` | — |
| `list` | — | 17 (body attributes) | Body | 2.21 | 0 | none | `circle.fill`, 15×15 pt × multiplier(17), y-offset −2, tint `onSurfaceVariant` |
| `todo` | — | 17 (body attributes) | Body | 2.21 | 0 | none | `square` (not done) / `checkmark.square.fill` (done), same geometry; done ⇒ `.strikethroughStyle = 1` + `onSurface` @ α 0.45; tint `primary` when done else `onSurfaceVariant` |
| `image` | — | — | — | — | — | — | `NSTextAttachment` with a pre-rounded bitmap |

Citations: sizes `RichTextEngine.swift:26-33`; line spacing `:40-42`; paragraph spacing `:46-53`;
menu order `:58`; stored-name mapping `:60-79`; style names `ContentPart.swift:123-130`;
Apple styles `docs/editor-typography.md:17-22`; marker symbols/bounds/tints
`RichTextEngine.swift:781-799`; quote background `:207`, `:390-393`, `:897`; todo done treatment
`:860-863`; paragraph style construction `:844-850`; trailing-newline attribute carry-over `:904-909`.
`paragraphSpacing` and `lineSpacing` are **rendered only** and re-derived from the block style on
load (`RichTextEngine.swift:44-53`; `docs/design-system.md:94`).

Inline run styles (`TextRun`, `ContentPart.swift:11-57`): `bold` → `.bold` weight
(`RichTextEngine.swift:889-892`), `italic` → symbolic trait **plus** an oblique matrix
`CGAffineTransform(a: 1, b: 0, c: 0.24, d: 1, tx: 0, ty: 0)` for CJK
(`RichTextEngine.swift:125-138`, `:277-286`), `strike` → `.strikethroughStyle = 1` (`:895`),
`underline` → `.underlineStyle = 1` (`:896`). Alignment is center only when `part.align == "center"`
(`RichTextEngine.swift:872-874`, `:962-964`).

### 1.7 Read body plumbing
`DiaryPartsView` (`MediaViews.swift:46-102`) groups consecutive non-image parts into one
`ReadTextView` chunk and gives every image its own `DiaryImageView`; the outer
`VStack(alignment: .leading, spacing: 8)` (`:62`). Text chunks receive
`textContainerInset = UIEdgeInsets(top: 2, left: 0, bottom: 2, right: 0)` (`:75`).

`ReadTextView` (`ReadTextView.swift:12-166`): `FittedTextView` overrides `intrinsicContentSize` to
`sizeThatFits(width)` with a 320 pt width fallback before layout (`:4-10`); the view is
`isEditable = false`, `isScrollEnabled = false`, `backgroundColor = .clear`,
`textContainer.lineFragmentPadding = 0`, `delegate = nil`, `isSelectable = false` (`:26-32`) — read
text cannot be selected or copied. A `UITapGestureRecognizer` resolves the tapped character offset
and dispatches in this order: to-do range → `onToggleTodo(partIndex, itemIndex)`; image range →
`onImageTap(src, h/w)`; otherwise `onTapText()` (`:140-159`). Simultaneous recognition is allowed
(`:161-164`). Rebuild is skipped unless parts / keyword / Dynamic Type size changed (`:41-51`).

### 1.8 Empty state, share
- An empty day is **not** a `GlassEmptyState`; the hero simply renders
  `read_day_empty` at 13 pt `onSurfaceVariant` (`DiaryPageView.swift:316-320`). There is no
  illustration and no call-to-action in read mode; the write entry point is the top-bar pencil, and
  it exists only for today. (`GlassEmptyState` exists at `Components.swift:458-480` but is not
  referenced by any diary view.)
- Share (`DiaryPageView.swift:53-60`, `DiaryViewModel.swift:507-526`): `shareDiary()` maps blocks to
  `ShareBlock(time:loc:parts:)`, resolves `isDark` from
  `UITraitCollection.current.userInterfaceStyle == .dark` (`:512`), then renders on
  `Task.detached(priority: .userInitiated)` (`:514`). Success presents a `.sheet` containing
  `ShareLink(item: Image(uiImage:), preview: SharePreview(L10n.formatDayKey(actualDayKey), image:))`
  with `.buttonStyle(.glassProminent)`, `.padding(24)`, `.presentationDetents([.height(170)])`
  (`DiaryPageView.swift:53-59`). Failure shows an info alert `read_share_failed_title` / `read_share_failed_msg`
  ("Share failed" / "Couldn't generate the image…") (`DiaryViewModel.swift:520-523`).
  The share sheet shows a single 170 pt-tall share button, not an image preview.

### 1.9 Inline image rendering (read) — `MediaViews.swift:13-44`
- Stored geometry defaults: `w = 300`, `h = 200` when absent (`MediaViews.swift:65-66`).
- Display width `w = min(max(40, geo.size.width), max(40, displayW))` (`:21`);
  height `fitH = max(1, displayH × w / max(displayW, 1))` (`:22`).
- Bitmap decode: `DiaryImageStore.shared.image(for: src, maxPixel: max(displayW, displayH) * 3)`
  (`:25`). `DiaryImageStore` keeps an `NSCache` with `countLimit = 200` and
  `totalCostLimit = 150 MB` (`MediaStore.swift:50-55`); the cache key is the raw `src`
  (`MediaStore.swift:57-67`, `:120-122`) — it is **not** content-addressed.
- Clip: `RoundedRectangle(cornerRadius: Radius.image = 14, style: .continuous)` (`:30`);
  horizontal centering via `Spacer(minLength: 0)` on both sides (`:23`, `:37`);
  container `.aspectRatio(max(displayW,1)/max(displayH,1), contentMode: .fit)` (`:41`);
  `.accessibilityAddTraits(.isImage)` (`:42`).
- Tap: `Haptics.tap()` then `onTap?( )`, which the block card turns into
  `PreviewItem(src:, ratio: h/w)` and a `.fullScreenCover` (`:32-35`; `DiaryPageView.swift:61-63`, `:368-370`).
- `ItemPreview`/`ImagePreviewView` (`MediaViews.swift:139-179`): black backdrop
  `opacity(max(0, 1 - abs(dragOffset.height) / 600))` (`:146`); bitmap decoded at
  `maxPixel = 1600` (`:151`); image `.scaledToFit()`, `scaleEffect(max(0.6, 1 - abs(dy)/800))`
  and `.offset(dragOffset)` (`:154-156`); on drag end it dismisses when
  `abs(value.translation.height) > 140 || abs(value.predictedEndTranslation.height) > 900`,
  otherwise springs back with `withAnimation(.diaryQuick)` (`:163-170`); tapping the backdrop
  dismisses (`:148-150`); `.accessibilityAddTraits(.isModal)` and a default
  `.accessibilityAction { dismiss() }` (`:175-178`).

---

## 2. EDIT mode

Edit mode is entered by `vm.enterWrite()` (new block) or `vm.enterEditBlock(index:)`
(existing block); both set `isRead = false` and `autoFocusEditor = true` inside
`withAnimation(.diaryStandard)` (`DiaryViewModel.swift:109-155`).

### 2.1 Entering / leaving edit mode (`DiaryViewModel.swift`)
- `enterWrite()` (`:109-131`): refused when `actualDayKey != today`, with info alert
  `editor_history_no_add_title` / `editor_history_no_add_msg` ("Cannot add blocks" /
  "Past entries can only be edited in place…"). Clears `loadParts`, `editingOriginalParts`,
  `location`; sets `locating = false`; `loadToken += 1`; `autoFocusEditor = true`;
  `isRead = false`, `editingIndex = nil`; then `beginLocateIfNeeded()` (`:115-130`).
- `enterEditBlock(index:)` (`:133-155`): refused for past days unless `settings.allowHistoryEdit`
  (alert `editor_readonly_title` / `editor_readonly_msg`). Captures
  `editingOriginalParts = parts`, sets `editingIndex`, `loadParts`, `loadToken += 1`,
  `autoFocusEditor = true`, `startUtc = block.startTimeUtc`, rebuilds the location snapshot from the
  block's own columns (`:141-151`).
- `handleBack()` (`:169-186`): in select mode clears selection and returns; in edit mode with a
  non-empty editor shows confirm `editor_exit_title` / `editor_exit_content_msg`
  ("Leave editor" / "You have unsaved content.") with confirm label `discard`
  ("Discard" / 放弃) → dismiss; otherwise dismisses immediately.
- `saveEditor()` (`:213-266`): empty parts ⇒ dismiss if new and no blocks exist, else return to
  read (`:214-225`). Editing an existing block ⇒ `updateBlockContent(blockId:contentJson:locText:locPrecision:)`,
  preserving coordinates and the address but writing the chosen precision/text (`:227-248`).
  New block ⇒ if `settings.autoLoc` and `location == nil`, confirm `editor_location_retry` /
  `editor_location_missing_msg` ("Location unavailable" / "还没有获取到地点…") with confirm
  `editor_save_anyway` ("Save Anyway" / 仍然保存), then a 0.3 s delay before continuing
  (`:251-264`); then cross-day check `editor_cross_day_title` / `editor_cross_day_msg` with confirm
  `editor_save_exit` ("Save & exit" / 保存并退出) (`:270-281`); then insert by day key (`:283-316`).
  Failure ⇒ `editor_save_failed_title` / `editor_save_failed_msg` (`:318-321`).
- Discard (`:196-209`): no-op when `controller.isEmpty()`; otherwise confirm
  `editor_discard_title` / `editor_discard_msg` ("Discard changes?" /
  "Your unsaved changes will be lost and can't be recovered.") with confirm `discard`;
  on confirm `loadParts = editingOriginalParts; loadToken += 1; controller.refreshTypingAttributes()`
  (`:205-209`). There is **no undo stack** — this restores the snapshot captured on entry
  (`docs/editor-typography.md:55`).
- After save/reload: `reloadAndShowRead()` re-fetches blocks, sets `autoFocusEditor = false`, and
  animates back to read with `editingIndex = nil`, `selectMode = false`, `selectedIds = []`
  (`:323-336`).

### 2.2 Edit top bar (`DiaryPageView.swift:220-233`)
Three trailing `PressableGlassIcon`s, leading `chevron.left` unchanged:

| # | Symbol | a11y label | Action |
|---|---|---|---|
| 1 | `photo` | `a11y_insert_image` ("Insert image" / 插入图片) | `showPhotoPicker = true` (`:221-224`) |
| 2 | `arrow.counterclockwise` | `editor_discard` ("Discard Changes" / 放弃修改) | `vm.confirmDiscardEditing()` (`:225-228`) |
| 3 | `checkmark`, `active: true` | `save` ("Save" / 保存) | `vm.saveEditor()` (`:229-232`) |

`active: true` gives the checkmark a `Theme.primary()` tint and `.isSelected`
(`Components.swift:317`, `:325`; `DiaryPageView.swift:229`).

`PhotoPicker` (`MediaViews.swift:104-137`): `PHPickerConfiguration` with `filter = .images`,
`selectionLimit = 1`, `preferredAssetRepresentationMode = .current` (`:108-111`); the first picked
`UIImage` is loaded and handed to `vm.insertImage` on the main queue (`:125-135`).
`insertImage` downsamples to `maxPixel: 2048`, encodes JPEG at `compressionQuality: 0.85`
(falling back to PNG), writes `images/img_<epoch ms>.jpg` under the documents directory, and calls
`controller.insertImage(processed, src:)` (`DiaryViewModel.swift:488-503`); failure ⇒ alert
`editor_image_failed_title` / `editor_image_failed_msg` (`:500-501`).

### 2.3 Editor card (`DiaryPageView.swift:399-423`)
`VStack(alignment: .leading, spacing: 4)` (`:400`):

1. Header `VStack(alignment: .leading, spacing: 6)` (`:401`):
   - **Time capsule**: `Text(L10n.timeOf(vm.startUtc))` at
     `.diaryFont(TypeSize.meta = 13, weight: .bold)`, `Theme.primary()`,
     padding h10/v5, background `Capsule().fill(Theme.primaryContainer())` (`:402-409`).
     `startUtc` is initialised to now (`DiaryViewModel.swift:25`) and, when editing an existing
     block, replaced with `block.startTimeUtc` (`DiaryViewModel.swift:147`).
   - `locationRow` (`:410`).
2. `RichTextView(controller: vm.controller, placeholder: <see below>, autoFocus: vm.autoFocusEditor,
   loadToken: vm.loadToken, loadParts: vm.loadParts)` (`:412-416`). There is deliberately **no
   `minHeight`** here — the representable reports its own minimum (`:417-419`).
3. `.padding(10)` (`:421`), `.diaryCard(cornerRadius: Radius.card = 20, interactive: true)` (`:422`).
   `interactive: true` ⇒ shadow `shadowColor` @ 0.22, radius 12, y 4 (`Components.swift:139-141`).

### 2.4 Location row state machine (`DiaryPageView.swift:431-504`)
Only a **new** block looks a location up; an existing block keeps the address it was written with
(comment `:425-430`; guards `DiaryViewModel.swift:390`, `:442`).

| Condition | Rendered |
|---|---|
| `vm.isEditingExistingBlock` && `vm.location != nil` | `locationMenu(text: vm.locationText)` (`:434-435`) |
| `vm.isEditingExistingBlock` && no location && `settings.autoLoc` | static chip `search_loc_no_loc` ("No place recorded" / 未记录地点), tint `onSurfaceVariant` (`:436-438`) |
| `vm.isEditingExistingBlock` && no location && `!settings.autoLoc` | nothing (`if` has no else) (`:433-438`) |
| new block && `settings.autoLoc` && location != nil | `locationMenu(text:)` (`:440-441`) |
| new block && `settings.autoLoc` && `vm.locating` | static chip `editor_location_fetching` ("Locating…" / 位置获取中…), tint `primary` (`:442-443`) |
| new block && `settings.autoLoc` && neither | plain button chip `editor_location_retry_action` ("Retry location" / 重新获取位置), tint `primary`, `Haptics.tap()` then `vm.beginLocate()` (`:444-452`) |
| new block && `!settings.autoLoc` | nothing (`:439`) |

`locationMenu` (`:458-488`): a `Menu` with `.menuOrder(.fixed)` (`:485`) listing
`vm.precisionOptions` as buttons; the active precision gets
`Label(L10n.precisionLabel(precision), systemImage: "checkmark")`, others a plain `Text` (`:460-471`);
when `vm.canRelocate` a `Divider()` + `editor_location_retry_action` button follows (`:472-480`);
every selection runs `Haptics.tap()` then the action. A11y: label `search_loc_title`
("Choose a location" / 选择地点), value = current address text (`:486-487`);
`buttonStyle(.plain)` (`:484`).

Precision options (`DiaryViewModel.swift:457-463`):
- Existing block (`recordedPrecision` present) →
  `LocationResolver.availablePrecisions(for: region, recorded:)`
  (`LocationService.swift:182-191`): the recorded level if it is `exact` or `street`, then `district`
  if `region3` is non-empty, `city` if `region2` is non-empty, always `province`.
- New block → `LocationResolver.availablePrecisions()` (`LocationService.swift:193-198`):
  `LocPrecision.all = [exact, street, district, city, province]` when location is authorized and
  precise, else `[province, city, district]` (`DiaryModels.swift:84-93`).
- `applyPrecision` (`DiaryViewModel.swift:421-437`) sets `locPrecision`, restores the verbatim
  recorded text when the precision returns to the recorded level, else re-derives text from the
  placemark or the stored region.

Chip label (`:490-504`): `HStack(spacing: 4)` with `location.fill` at `.diaryFont(TypeSize.caption = 12)`
and the text at `.diaryFont(TypeSize.meta = 13)` `.lineLimit(1)`, tinted by parameter, padding h10/v5,
background `Capsule().fill(Theme.primaryContainer())`.

Location acquisition (`DiaryViewModel.swift:382-419`): `beginLocateIfNeeded()` runs only when
`settings.autoLoc && location == nil` (`:382-385`); `beginLocate()` returns immediately for an
existing block (`:390`); if location is unauthorized it requests permission and re-checks after
**600 ms** (`:391-403`); otherwise `locating = true` and `fetchLocation()` runs.
`fetchLocation()` resolves a snapshot and stores `nil` when the resolved `locText` is empty
(coordinates with no address are treated as no location) (`:408-419`).

### 2.5 Rich text input view (`RichTextView.swift:4-141`)
`PlaceholderTextView` (a `UITextView`) configuration (`:85-100`):
`backgroundColor = .clear`, `isScrollEnabled = false`,
`textContainerInset = UIEdgeInsets(top: 10, left: 12, bottom: 10, right: 12)`,
`textContainer.lineFragmentPadding = 0`, `textColor = Theme.onSurfaceUIColor()`,
`tintColor = Theme.primaryUIColor()`, `keyboardDismissMode = .interactive`,
`alwaysBounceVertical = false`, `showsVerticalScrollIndicator = false`.

- **Placeholder**: a real `UILabel` (`:9-16`), color `Theme.onSurfaceVariantUIColor()` (`:13`),
  `numberOfLines = 0` (`:14`), font = the editor body font (`:25-28`). Frame:
  `x = inset.left + 5`, `y = inset.top`, `width = bounds.width - inset.left - inset.right - 10`,
  `height = ceil(font.lineHeight)` (`:50-60`). Hidden as soon as `text` is non-empty (`:62-64`,
  `:66-69`).
- **Minimum height**: `minimumHeight = (160 × DynamicTypeMetrics.multiplier(for: EditorDesignSize.body /*17*/, typeSize:)).rounded()`
  (`:29`; stored default 160 at `:42`). Intrinsic size =
  `CGSize(width: bounds.width > 0 ? bounds.width : 340, height: max(minimumHeight, sizeThatFits(...).height))`
  (`:44-48`). So 160 pt at the default category, ≈240 pt at AX5
  (`docs/editor-typography.md:37`, `docs/design-system.md:213`).
- **Placeholder strings**: `editor_placeholder_new` ("Start writing your thoughts…" /
  开始记录这一刻的想法…) when `editingIndex == nil`, otherwise `editor_placeholder_edit`
  ("Edit this block…" / "修改这段内容…") (`DiaryPageView.swift:413`).
- **Auto-focus**: when `autoFocus` is true, `becomeFirstResponder()` is called after **0.25 s**
  (`RichTextView.swift:106-110`). The format bar's `onTap` also calls `becomeFirstResponder()`
  (`DiaryPageView.swift:17-19`). `autoFocusEditor` is set true by `enterWrite`/`enterEditBlock`
  (`DiaryViewModel.swift:125`, `:146`) and false by `reloadAndShowRead` (`:329`).
- **Image width**: in `updateUIView`, when `bounds.width > 40`,
  `imageMaxWidth = max(60, width - inset.left - inset.right)` i.e. `width - 24` (`:121-131`). If that
  changed and `loadParts` is non-empty, the content is reloaded (`:125-130`).
- **Load**: `loadToken` change → `controller.clear()` for empty parts, else `controller.load(parts:)`
  (`:132-139`). `controller.load` sets the attributed string, resets typing attributes and puts the
  caret at 0 (`RichTextEngine.swift:697-705`).
- **Newline guard**: when the replacement text is `"\n"`,
  `controller.shouldBlockNewline(at: caret)` can veto it (centered empty paragraph)
  (`RichTextView.swift:153-164`; `RichTextEngine.swift:505-509`).
- **Text-size changes**: `updateUIView` calls `uiView.applyTypeSize` and
  `controller.reapplyTypeSize(typeSize)` when the environment category changed
  (`RichTextView.swift:114-120`); `reapplyTypeSize` re-parses with the old category, rebuilds at the
  new one and clamps the caret (`RichTextEngine.swift:710-723`).

### 2.6 Image insert (editor) — `RichTextEngine.swift:675-690`
- `viewWidth = tv.bounds.width > 0 ? tv.bounds.width - 24 : 343` (`:677`).
- `maxW = min(viewWidth, 343)` (`:678`); `ratio = image.size.height / max(1, image.size.width)` (`:679`);
  `displayH = max(40, maxW × ratio)` (`:680`).
- Attachment payload is `AttachmentPayload(src: src, w: maxW, h: displayH)` (`:681`); the bitmap is
  pre-rendered rounded with `DiaryImageStore.rounded(image, size: (maxW, displayH), radius: Radius.image = 14)`
  (`:682`; `MediaStore.swift:86-98`); bounds `(0, 0, maxW, displayH)` (`:683`).
- A `"\n"` with the base typing attributes is appended (`:685`), the attachment is inserted at the
  caret (`:686-687`) and the caret is placed after the newline (`:688`).
- On **reload**, `PartsCodec.attributedString` clamps the stored width:
  `storedW = max(1, part.w ?? 300)`, `storedH = max(1, part.h ?? 200)`,
  `fallbackW = max(60, Screen.width - 76)`, `maxW = max(60, imageMaxWidth ?? fallbackW)`,
  `w = min(storedW, maxW)`, `h = storedH × w / storedW`, decode
  `maxPixel = max(storedW, storedH) × 3` (`RichTextEngine.swift:819-835`).

### 2.7 The full format bar (`RichTextView.swift:196-337`)
Container (`:264-336`): `ScrollView(vertical ? .vertical : .horizontal, showsIndicators: false)`
(`:275`) wrapping `AnyLayout` — `VStackLayout(spacing: 6)` when vertical, `HStackLayout(spacing: 6)`
when horizontal (`:276-277`). Inner `.padding(.vertical, vertical ? 2 : 8)` (`:318`), outer
`.padding(.horizontal, vertical ? 6 : 12)` (`:320`), background = `RoundedRectangle(cornerRadius:
Radius.panel = 26, style: .continuous)` with a `.clear` fill under
`.glassEffect(.regular.interactive(true), in: …)` and `.shadow(Theme.shadowColor(), radius: 14, y: 4)`
(`:321-331`). Button state is re-read through `controller.formatTick` (`:335`; `:266-273`).
The bar is only shown when `!vm.isRead` (`DiaryPageView.swift:16`) and has `.transition(.opacity)`
(`:27`).

Button factory `btn(_:accessibilityLabel:active:disabled:action:)` (`:201-222`): every tap runs
`Haptics.tap()`, then the action, then `onTap?()` (refocus). Glyph
`.diaryFont(15, weight: .medium)`, `.frame(minWidth: 34, minHeight: 40)`, `.contentShape(Capsule())`,
background `Capsule().fill(active ? Theme.primary() : .clear)`, foreground
`active ? .white : Theme.onSurface()`, `.disabled(disabled)` with `.opacity(disabled ? 0.35 : 1)`,
`.accessibilityAddTraits(active ? .isSelected : [])` (`:209-221`).

Order and behaviour:

| # | Item | Symbol / label | Active rule | Disabled | Action |
|---|---|---|---|---|---|
| 1 | Style menu (`:227-262`) | `textformat.size` + `chevron.down`; a11y label `editor_tool_style` ("Paragraph Style" / 段落样式), a11y value = current style (`:260-261`) | `currentBlockStyle() != .body` ⇒ primary capsule (`:229`, `:255`) | — | `controller.applyBlockStyle(style)` (`:235`) |
| 2 | Center (`:279-282`) | `text.aligncenter`, a11y `editor_tool_center` ("Center" / 居中) | `controller.isCenterActive()` | `list || quote || todo` (`:280`, `:273`) | `controller.toggleCenter()` |
| 3 | Divider (`:283-285`) | — width 20 / height 20 depending on axis (`:284`), overlay `outlineVariant` @ 0.5 (`:285`) | — | — | — |
| 4 | List (`:286-289`) | `list.bullet`, a11y `editor_tool_list` ("List" / 列表) | `controller.isListActive()` | — | `controller.toggleList()` |
| 5 | Quote (`:290-293`) | `text.quote`, a11y `editor_tool_quote` ("Quote" / 引用) | `controller.isQuoteActive()` | — | `controller.toggleQuote()` |
| 6 | To-do (`:294-297`) | `checklist`, a11y `editor_tool_todo` ("To-do" / 待办) | `controller.isTodoActive()` | — | `controller.toggleTodo()` |
| 7 | Divider (`:298-300`) | same as #3 | — | — | — |
| 8 | Bold (`:301-304`) | `bold`, a11y `editor_tool_bold` ("Bold" / 粗体) | `activeStyles().bold` | — | `controller.toggleBold()` |
| 9 | Italic (`:305-308`) | `italic`, a11y `editor_tool_italic` ("Italic" / 斜体) | `activeStyles().italic` | — | `controller.toggleItalic()` |
| 10 | Strikethrough (`:309-312`) | `strikethrough`, a11y `editor_tool_strike` ("Strike" / 删除线) | `activeStyles().strike` | — | `controller.toggleStrike()` |
| 11 | Underline (`:313-316`) | `underline`, a11y `editor_tool_underline` ("Underline" / 下划线) | `activeStyles().underline` | — | `controller.toggleUnderline()` |

Style-menu contents: `EditorBlockStyle.menuStyles = [.title, .heading, .body]` (`RichTextEngine.swift:58`),
rendered with a checkmark `Label` on the current one and `.menuOrder(.fixed)`
(`RichTextView.swift:231-243`, `:259`). **Quote is deliberately not in the menu** — it keeps its own
toolbar button (`RichTextEngine.swift:55-57`). Menu label box is
`HStack(spacing: 3)` of `textformat.size` (15 pt medium) + `chevron.down` (9 pt semibold),
`.frame(minWidth: 46, minHeight: 40)` (`:245-256`).

There is **no colour control** in the format bar (`docs/read-edit-screen-design-spec.md:414-415`;
no such code exists in `RichTextView.swift:264-336`).

### 2.8 Paragraph / selection semantics (`RichTextEngine.swift:314-673`)
- **Character styles** apply to the selected range, or to `typingAttributes` when the selection is
  empty (`:237-271`, `:296-312`). Bold/italic toggle the trait on each run (or on the typing font);
  strike/underline flip between 1 and 0 (`:301-309`).
- **Paragraph styles** apply to **every paragraph the caret/selection touches**, over the paragraph
  range including the terminating newline, applied back-to-front because restyling can drop a
  list/todo marker and shift later ranges (`:325-340`, `:409-432`). A partial selection restyles the
  whole line (`docs/editor-typography.md:51` comment; `:321-324`).
- **Mutual exclusion**: title/heading/quote/body replace list & to-do markers — the marker character
  is removed first (`:369-373`); list/todo first reset the paragraph to `.body` with
  `cancelCenter: true` (`:535-540`); center alignment cannot coexist with list/quote/todo (guard
  `:438`, plus the disabled toolbar button); quote restyle forces `alignment = .left` (`:396-398`).
- **Empty-paragraph rule**: on an empty line a toggle changes only the *typing* attributes and never
  the adjacent line (`:442-459`, `:606-651`). `paragraphRange` returns a zero-width range at the
  caret for an empty line, and at the end of text after a trailing newline (`:622-651`).
- **Centered empty line**: `shouldBlockNewline(at:)` returns true, so Return is swallowed — no
  cascade of blank centered lines (`:505-509`; `RichTextView.swift:153-164`).
- **Typing attributes** for a block (`:200-209`): font at the block's design size with
  `.diaryBlockStyle`, `foregroundColor = onSurface`, paragraph style with the block's
  `lineSpacing`/`paragraphSpacing`, and `backgroundColor = quoteBg` only for `.quote`.
  `baseTypingAttributes()` = body (`:211-213`).
- **State probes** for the bar: `activeStyles()` (`:653-673`, selection range start, or typing
  attributes when empty), `isCenterActive()` (`:483-500`), `isListActive()/isTodoActive()`
  (`:558-567`, attachment kind at the paragraph start), `isQuoteActive()` (`:585-599`, non-clear
  background at the paragraph start or in typing attributes), `currentBlockStyle()` (`:343-362`,
  probes the **start** of the paragraph for `.diaryBlockStyle`, then non-clear background ⇒ quote,
  then the size ladder).
- `notifyFormatChange()` bumps the observable `formatTick` and calls `onFormatChange`
  (`:222-225`); `RichTextEngine` mutations go through `apply` which copies the storage, mutates, and
  writes back (`:737-744`).

### 2.9 Keyboard show/hide
`handleKeyboard(_:)` (`DiaryPageView.swift:100-116`), driven by
`UIResponder.keyboardWillChangeFrameNotification` (`:36-38`):
- Height = `frame.origin.y < Screen.height ? frame.height : 0` — a frame starting at or below the
  screen height counts as hidden (`:101-102`).
- Early-out when the height is unchanged (`:103`).
- Duration = `keyboardAnimationDurationUserInfoKey` ?? `0.25` (`:104`).
- Curve mapping (`keyboardAnimationCurveUserInfoKey`, default 0) (`:105-112`):
  `1 → .easeIn(duration:)`, `2 → .easeOut(duration:)`, `3 → .linear(duration:)`,
  default → `.easeInOut(duration:)`.
- `withAnimation(animation) { vm.keyboardHeight = height }` (`:113-115`).

Scroll reactions:
- On leaving read mode, `scrollTo("editor-card", anchor: .center)` after **0.3 s**
  (`DiaryPageView.swift:168-176`).
- When `keyboardHeight` becomes > 0 in edit mode, the same scroll after **0.15 s**
  (`:177-185`). Both use `withAnimation(.diaryStandard)`.

### 2.10 Format-bar offset formula (both states)
The bar lives in `ZStack(alignment: .bottom)` over the content (`DiaryPageView.swift:13-29`) with the
bottom safe area ignored (`:30`), so it re-adds the safe area itself:

```
.padding(.bottom, vm.keyboardHeight > 0
                    ? vm.keyboardHeight + 8
                    : Screen.safeAreaBottom + 12)
```
(`DiaryPageView.swift:23-26`). Concrete values on the reference device: keyboard up ⇒
8 pt above the keyboard; keyboard down ⇒ portrait `34 + 12 = 46 pt`, landscape `20 + 12 = 32 pt`
above the screen bottom (`Screen.safeAreaBottom`, `Components.swift:44-55`;
`docs/design-system.md:314-317`). The bar also has `.transition(.opacity)` (`:27`) and sets
`.environment(\.fontToolbarVertical, layout.splitsMasterDetail)` (`:22`).

### 2.11 Landscape vertical layout
`fontToolbarVertical` is an environment value defaulting to `false`
(`RichTextView.swift:185-194`), set from `layout.splitsMasterDetail`
(`DiaryPageView.swift:22`). On the reference device landscape this is `true`
(`AdaptiveLayout.swift:32-43`, `750 >= 700 && 874 > 402`), so the bar becomes
`ScrollView(.vertical)` + `VStackLayout(spacing: 6)` with `.padding(.vertical, 2)` and
`.padding(.horizontal, 6)` (`RichTextView.swift:275-320`). The bar stays inside the
bottom-aligned `ZStack`, i.e. it is vertically stacked but still bottom-positioned — only the
stack axis changes; no right-edge anchoring exists (`DiaryPageView.swift:13`, `:17-27`).

---

## 3. Storage invariants

### 3.1 Semantic block style vs design size (decoupled)
Two editor-internal `NSAttributedString.Key`s carry the model (`RichTextEngine.swift:111-121`):
- `.diaryBlockStyle` (`"com.cov.justdiary.blockStyle"`) — the paragraph's semantic style
  (`title`/`heading`/`body`/`quote`), persisted as `ContentPart.style`.
- `.diaryDesignSize` (`"com.cov.justdiary.designSize"`) — the **unscaled** design size the drawn
  font was derived from. **Never persisted.**

The drawn font is always `DynamicTypeMetrics.font(designSize, …)`
(`RichTextEngine.swift:123-138`; `DesignSystem.swift:206-209`). Paragraph type is read from
`.diaryBlockStyle`, never inferred from the point size, so changing the ladder cannot rewrite saved
entries (`RichTextEngine.swift:72-79`, `:948-960`; `docs/editor-typography.md:41-51`;
`docs/design-system.md:97-114`).

### 3.2 Persisted content format (v2)
`edit_block.content_json` holds `{"v":2,"parts":[…]}` (`ContentPart.swift:153-164`, `:198-205`).

Persisted per part (`ContentPart.swift:61-78`): `style`, `text`, `items`, `done`, `src`, `runs`,
`w`, `h`, `align`.
Persisted per run (`ContentPart.swift:11-57`): `text` plus `bold`/`italic`/`strike`/`underline`,
and only flags that are **on** are written — `"bold": false` is never emitted (`:44-52`; test named in
`docs/editor-typography.md:120`).
`style` values are the semantic names `title`/`heading`/`body`/`list`/`todo`/`quote`/`image`
(`ContentPart.swift:123-130`).

**Not persisted:**
- Any font size. `TextRun` has no `size`, and `PartsCodec.parts(from:)` explicitly does not read the
  drawn size (`ContentPart.swift:3-16`; `RichTextEngine.swift:983-999`).
- `.diaryDesignSize` / `.diaryBlockStyle` (text-storage-only keys, `RichTextEngine.swift:113-121`).
- `lineSpacing` / `paragraphSpacing` — rendered only, re-derived from the block style on load
  (`RichTextEngine.swift:44-53`; `docs/design-system.md:94`).
- Text colour (there is no colour model at all).
- Timestamps and location: these live in `edit_block` columns (time, location text, coordinates,
  precision, region) not in `content_json` (`DiaryModels.swift:12-30`;
  `DiaryViewModel.swift:283-306`, `:470-484`).

### 3.3 Round-trip invariant
Load → `PartsCodec.attributedString` → `PartsCodec.parts(from:)` must return the same block styles,
alignment, text and inline flags in **all 12 Dynamic Type categories**
(`docs/editor-typography.md:121`; `docs/design-system.md:132-135`). Mechanisms:
- `attributedString` writes the block style and design size on every run and on each paragraph's
  terminating newline (`RichTextEngine.swift:881-910`).
- In the text storage, sizes must be the design sizes (28/22/17/15), never the drawn sizes
  (`docs/design-system.md:116-120`; `typingAttributes` uses `block.designSize`,
  `RichTextEngine.swift:200-209`).
- `parts(from:)` reads `.diaryBlockStyle` first, then a non-clear background ⇒ `quote`, then the size
  ladder as a last resort (`RichTextEngine.swift:948-960`).
- Empty lines are skipped; a line whose text starts with `"• "` is a list item and `"☐ "` / `"☑ "`
  a to-do item (legacy/plain-text path) (`RichTextEngine.swift:924-946`); consecutive list or todo
  lines merge into one `ContentPart` with parallel `done` flags (`:1008-1023`).

### 3.4 Fallback rules (exactness matters)
- Design size (`EditorFont.designSize`, `RichTextEngine.swift:173-183`): `.diaryDesignSize` is
  authoritative; otherwise, at `.large`, the raw point size; otherwise an **exact** match against
  `EditorDesignSize.authored` scaled values within `< 0.01 pt`; only unmatched (imported) sizes fall
  back to `point / multiplier(point, typeSize)`. Approximate division is banned because
  `typingAttributes` loses the custom keys from the second typed character on, and drift would push a
  paragraph across the heading threshold (`docs/design-system.md:122-128`;
  `docs/editor-typography.md:48-51`).
- Size → block style thresholds: `size >= (28+22)/2 = 25` ⇒ title;
  `size >= (22+17)/2 = 19.5` ⇒ heading; else body (`RichTextEngine.swift:104-108`).
- Stored style → editor style: `title`/`heading`/`quote` map to themselves, **everything else**
  (`body`, `list`, `todo`, `image`, unknown) maps to `.body` (`RichTextEngine.swift:72-79`).
- Unknown persisted style strings are passed through unchanged by `ContentPartStyle.normalized`
  (`ContentPart.swift:142-144`).
- v1 compatibility: the legacy `type` key and HTML names `h1`/`h2`/`p`/`ul`/`img` are mapped, and a
  per-run `size` is dropped (`ContentPart.swift:80-83`, `:99-105`, `:132-144`;
  `docs/editor-typography.md:101-109`). `parseContent` tries the v2 envelope first, then a bare v1
  array (`ContentPart.swift:185-194`); `serializeContent` writes v2 only with
  `.sortedKeys, .withoutEscapingSlashes` (`:198-205`).
- Image `src` is normalized to an `images/` key on read and when content passes through
  `ImagePathUtil.normalizeContent` (`ContentPart.swift:208-231`); the file path resolves under the
  app's Documents directory (`:233-237`).

---

## 4. Share long-image renderer (`JustDiary/Services/ImageShareService.swift`)

### 4.1 Canvas and margins
| Constant | Value | Line |
|---|---|---|
| `width` | 720.0 pt | `:10` |
| `blockLeft` (left margin for content) | 108.0 pt | `:11` |
| `side` (right margin) | 48.0 pt | `:12` |
| `tlLineX` (timeline centre x) | 61.0 pt | `:13` |
| `tlLineW` (timeline width) | 3.0 pt | `:14` |
| `dateY` | 64.0 pt | `:15` |
| `footerH` | 34.0 pt | `:16` |
| `contentWidth` | `720 − 108 − 48 = 564.0 pt` | `:17` |

`render(dayKey:blocks:isDark:)` (`:212-251`) uses `UIGraphicsImageRendererFormat` with
`scale = 2`, `opaque = true` and canvas `720 × layout.height` (`:218-221`), so the exported PNG is
**1440 px** wide.

### 4.2 Vertical layout algorithm (`:54-78`)
```
y = dateY (64)
y += 70                              // date text
y += startLineHeight(startLine)      // wrapped sub-line, 40 pt per line, min 1 line
y += 2 + 24                          // hairline gap
for each block:
    y += 36                          // time line
    y += 20                          // gap before location
    y += 34                          // location line
    for each part:  y += partRect(part).height
    y += 36                          // block gap
y += footerH (34)
```
`startLineHeight` (`:80-91`) measures char-by-char with a 28 pt regular font against `contentWidth`
and returns `lines × 40`.

### 4.3 Per-part geometry and fonts (`partRect` `:93-116`, `fontForPart` `:182-190`,
`lineHeightForPart` `:192-200`, `drawPart` `:318-374`)
| Style | Rect height | Font | Colour | Drawing detail |
|---|---|---|---|---|
| `title` | `52 + 12 = 64` | 36 bold | `palette.text` | single line at x (`:99-100`, `:184`, `:322`) |
| `heading` | `46 + 10 = 56` | 32 semibold | `palette.text` | single line at x (`:101-102`, `:185`, `:324`) |
| `body` / default | `lines × 50 + 10` | 31 regular | `palette.text` | wrapped at `contentWidth`, line height 50, drawn at context alpha 0.9 (`:112-114`, `:188`, `:367-372`) |
| `quote` | `wrappedHeight(text, 30 pt, width = contentWidth-22, lineHeight 40, inkRatio 0.87)` | 30 regular | `palette.text` on `palette.quoteBg` | filled rounded box at `x+22`, width `contentWidth-22`, corner radius `concentric(Radius.card=20, inset 4) = 16` (`:103-105`, `:186`, `:325-335`) |
| `list` | `items.count × 55` | 30 regular | `palette.text` | per item at `y + i×55`: bullet Ø11 `accentA` with 8 pt glow at `x+12, iy+15`; text wrapped at `x+24`, width `contentWidth-24`, line height 55 (`:106-108`, `:187`, `:336-343`, `:376-382`) |
| `todo` | `items.count × 55` | 30 regular | `palette.text` (done rows `text` @ α 0.45) | checkbox 28×28 at `x, iy+11`: checked = gradient `accentA → accentB`, radius `concentric(Radius.badge=12, 4) = 8`, white check path lineWidth 3.2 round cap/join; unchecked = 2 pt stroke. Text at `x+42`, width `contentWidth-42` (`:109`, `:344-353`, `:384-409`) |
| `image` | `max(120, contentWidth × ratio)` | — | — | rect `(x, y, contentWidth, h)`, radius `Radius.card = 20`, clipped, bitmap drawn (`:109-111`, `:135-147`, `:354-366`). Ratio from stored `w/h` if both > 0, else the loaded bitmap, else **0.75** (`:135-147`). Missing file ⇒ placeholder: gradient `bgMid → accentA @ α 0.08`, radius 20, centred label `share_image_placeholder` ("Image" / 图片) at 26 pt `palette.faint` (`:411-424`) |

`wrappedHeight` (`:118-133`): wraps with the same glyph measurement, then
`max(1, lines - 1) × lineHeight + ink + 40`, where `ink = max over lines of (max glyph ratio × font.pointSize)`
and the glyph ink ratio is **0.87 CJK** (`0x4E00…0x9FFF`, `0x3400…0x4DBF`), **0.75 ASCII**
(`< 128`), **0.98 other** (`:118-133`, `:450-459`). Glyph width is measured via `boundingRect`
(`:175-180`).

### 4.4 Timeline, node, hairline, footer
- **Hairline** (`:274-278`): horizontal linear gradient `clear → accentA @ 0.6 → clear`
  (stops `[0, 0.5, 1]`) from `x = blockLeft (108)` to `x = width - side (672)` at the hairline y.
- **Timeline** (`:280-290`): clipped rect at `x = tlLineX - tlLineW/2 = 59.5`, width 3, from the
  post-hairline y to `layout.height - footerH - 20`; vertical gradient
  `accentA @ 0.9 → accentA @ 0.35 (stop 0.6) → accentA @ 0` (`:281-288`).
- **Node** (`:292-303`), drawn per block at `(tlLineX, y + 36)` (`:236`): filled ellipse Ø30 in
  `accentA` with `setShadow(offset: .zero, blur: 18, color: accentA)` (`:294-297`), then a stroke
  ellipse Ø36 at 6 pt `accentA` (`:298-300`), then a white filled ellipse Ø10 (`:301-302`).
- **Text positions** (`render` `:222-250`): date at `(blockLeft, dateY=64)` in 62 pt bold
  `palette.text` (`:226-227`); start line wrapped at `contentWidth` in 28 pt regular,
  line height 40, `palette.sub` (`:229-231`); per block, time `L10n.timeOf(block.time)` at
  `(blockLeft, y+36)` in 26 pt bold `accentA` (`:237-238`), then location wrapped at `contentWidth`
  in 24 pt regular, line height 34, `palette.sub` (`:240-242`).
- **Footer** (`:305-316`): text `"· \(L10n.str("EntryAbility_label")) ·"` = `· 一页时光 ·`
  (`Localizable.xcstrings` key `EntryAbility_label`, both en and zh-Hans = `一页时光`), 24 pt regular
  `palette.faint`, centred by computing `totalWidth = count × 6 + measured width` and drawing
  character by character with +6 pt tracking (`:310-315`), at `y = layout.height - footerH` (`:249`).

### 4.5 Background orbs and palettes
`drawBackground` (`:253-261`): full-height linear gradient `bgTop → bgMid @ 0.5 → bgBottom`
(`:254-257`), then three radial orbs via `drawOrb`, each `color @ α0.45 → α0` (`:263-272`):
`accentA` at `(620, -80)` r 460; `accentB` at `(-60, 300)` r 380; `accentC` at `(450, height+60)` r 500
(`:258-260`).

Palettes (`:31-45`):

| Token | light | dark |
|---|---|---|
| `text` | `#191C20` | `#E1E2E8` |
| `sub` | `#44474F` | `#C5C6D0` |
| `faint` | `#191C20` @ α 0.38 | `#E1E2E8` @ α 0.38 |
| `bgTop` | `#FBFBFE` | `#101318` |
| `bgMid` | `#F3F6FD` | `#131826` |
| `bgBottom` | `#F7F2FF` | `#131826` |
| `accentA` | `#6E8BFF` | `#4A7DFF` |
| `accentB` | `#9B6BFF` | `#A56BFF` |
| `accentC` | `#3FB6FF` | `#38BDF8` |
| `quoteBg` | `#E4EAF9` | `#2B3344` |

`Palette.surface2` returns `bgMid` and `Palette.quoteBg` returns `accentA @ 0.08`
(`:440-448`). The renderer reuses only `Radius.card`, `Radius.badge` and
`Radius.concentric(outer:inset:)` from the app's token set (`:331`, `:391`, `:405`, `:416`;
`docs/design-system.md:25-28`); its colours, orbs and 720 pt canvas are renderer-local.

---

## 5. Animation and haptics constants

| Constant / behaviour | Value | Citation |
|---|---|---|
| `Animation.diaryQuick` | `.easeOut(duration: 0.22)` | `Components.swift:7` |
| `Animation.diaryStandard` | `.easeInOut(duration: 0.3)` | `Components.swift:8` |
| `Haptics.tap()` | `UIImpactFeedbackGenerator(style: .light)`, `prepare()` then `impactOccurred()` | `Haptics.swift:3-9` |
| Haptic call sites | every glass button, format-bar button, search stepper, block tap in select mode, location chip/menu/retry, inline image tap | `Components.swift:187`, `:237`, `:280`, `:311`, `:356`, `:431`; `RichTextView.swift:204`; `DiaryPageView.swift:199`, `:266`, `:446`, `:462`, `:475`; `MediaViews.swift:33` |
| Block list animation | `.animation(.diaryStandard, value: vm.blocks.map(\.id))` | `DiaryPageView.swift:149` |
| Format bar appear/disappear | `.transition(.opacity)` | `DiaryPageView.swift:27` |
| Search bar toggle | `withAnimation(.diaryQuick)` | `DiaryPageView.swift:205-208` |
| Select-mode enter | `withAnimation(.diaryQuick)` | `DiaryViewModel.swift:163-166` |
| Preview drag spring-back | `withAnimation(.diaryQuick)` | `MediaViews.swift:167-169` |
| Mode transitions (write/edit/save/delete-reload) | `withAnimation(.diaryStandard)` | `DiaryViewModel.swift:126-129`, `:152-154`, `:219-222`, `:330-335` |
| Hit/scroll navigation | `withAnimation(.diaryStandard)` with `anchor: .center` | `DiaryPageView.swift:163-165`, `:171-173`, `:180-182` |
| Editor auto-focus delay | 0.25 s | `RichTextView.swift:107` |
| Scroll-to-editor on mode change | 0.3 s delay | `DiaryPageView.swift:170` |
| Scroll-to-editor on keyboard change | 0.15 s delay | `DiaryPageView.swift:179` |
| Delay before cross-day alert | 0.3 s | `DiaryViewModel.swift:259` |
| Search debounce | 150 ms | `DiaryViewModel.swift:69` |
| Location permission re-check | 600 ms | `DiaryViewModel.swift:394` |
| Active-button bounce | `.symbolEffect(.bounce, value: active)` on `PressableGlassIcon` | `Components.swift:323` |
| Preview dismiss thresholds | drag `> 140 pt` or predicted end velocity `> 900` | `MediaViews.swift:164` |
| Preview backdrop fade divisor | `600`; image scale divisor `800`, floor `0.6` | `MediaViews.swift:146`, `:155` |
| Keyboard animation duration fallback | 0.25 s | `DiaryPageView.swift:104` |
| Keyboard curve → SwiftUI animation | 1 `.easeIn`, 2 `.easeOut`, 3 `.linear`, default `.easeInOut` | `DiaryPageView.swift:106-112` |
| `FlowLightOverlay` (exists, never instantiated) | 60 fps `TimelineView`, 4 s cycle, pauses after 10 s, honours `accessibilityReduceMotion` | `Components.swift:649-689`; `docs/design-system.md:258-261` |

---

## 6. Accessibility semantics

- Every glass icon button takes an explicit `accessibilityLabel`; `PressableGlassIcon` falls back to
  the raw SF Symbol name only if none is passed (`Components.swift:324`).
- Active toggles add `.accessibilityAddTraits(.isSelected)`: search toggle
  (`DiaryPageView.swift:203-209` via `PressableGlassIcon`), selected block card
  (`DiaryPageView.swift:381`), format-bar buttons (`RichTextView.swift:221`), glass/action chips
  (`Components.swift:325`, `:371`, `:452`).
- Block cards are accessibility **containers** — `.accessibilityElement(children: .contain)` — so
  VoiceOver can still reach body text and to-do rows behind the card's own tap gesture
  (`DiaryPageView.swift:380`).
- Label + value pairs: the style menu exposes label `editor_tool_style` and value = current style
  (`RichTextView.swift:260-261`); the location menu exposes label `search_loc_title` and value =
  the address (`DiaryPageView.swift:486-487`).
- Search steppers are plain buttons with real labels `read_search_prev` / `read_search_next`, added
  deliberately to avoid glass-on-glass and to stop VoiceOver announcing `chevron.up` / `chevron.down`
  (`DiaryPageView.swift:256-277`; `docs/design-system.md:198-201`).
- Inline images carry `.accessibilityAddTraits(.isImage)` (`MediaViews.swift:42`); the full-screen
  preview carries `.isModal` plus a default `.accessibilityAction { dismiss() }`
  (`MediaViews.swift:175-178`).
- Read text views are not selectable, not editable and have no `accessibilityLabel` of their own; the
  attributed text is read by VoiceOver as text (`ReadTextView.swift:26-32`).
- Dynamic Type: everything uses `.diaryFont`/`EditorFont` (`DynamicType.swift:52-54`,
  `RichTextView.swift:209`); the editor input minimum height scales with the body multiplier
  (`RichTextView.swift:29`); no fixed heights clip the editor content (`DiaryPageView.swift:417-419`;
  `docs/design-system.md:210-220`).
- UI-test-only probe: `-ui-test-editor-state` renders a `Text` with `accessibilityIdentifier
  "editor.state"` at `1×1 pt`, opacity 0.02, `allowsHitTesting(false)`, exposing block styles and
  text (`DiaryPageView.swift:64-77`, `:83-96`).
- Known omissions in a11y: to-do rows are text attachments, so tapping to toggle is not exposed as a
  labelled control (`MediaViews.swift:71-76`, `ReadTextView.swift:148-152`); the search stepper
  buttons have no `accessibilityValue` (only the label); the preview image has no label.

---

## 7. Interaction table

| # | Control | Mode | Placement | Trigger | Result | Feedback / a11y |
|---|---|---|---|---|---|---|
| 1 | `chevron.left` glass circle (44 pt) | both | top-left | tap | select mode: exit select + clear ids; edit with non-empty editor: confirm → dismiss; else dismiss | `Haptics.tap`; label "Back" (`DiaryPageView.swift:191-194`; `DiaryViewModel.swift:169-186`) |
| 2 | `trash` `GlassPrimaryButton` "Delete (n)" | select | top-right | tap | confirm `editor_delete_title` → delete selected blocks, bump version, reload | `Haptics.tap`; shows selected count (`DiaryPageView.swift:196-201`; `DiaryViewModel.swift:340-355`) |
| 3 | `magnifyingglass` glass circle | read | top-right | tap | `.diaryQuick` toggle search bar; closing clears text + hits | `active` ⇒ primary tint + bounce; `.isSelected`; label "Search" (`DiaryPageView.swift:202-209`) |
| 4 | `square.and.pencil` glass circle | read, today only | top-right | tap | `enterWrite()` | label "Write" (`DiaryPageView.swift:210-215`) |
| 5 | `square.and.arrow.up` glass circle | read | top-right | tap | render long image → share sheet (or "Share failed") | label "Share"; detached `userInitiated` task (`DiaryPageView.swift:216-219`; `DiaryViewModel.swift:507-526`) |
| 6 | `photo` glass circle | edit | top-right | tap | PHPicker (1 image) → downsample/write/insert | label "Insert image" (`DiaryPageView.swift:221-224`; `MediaViews.swift:104-137`; `DiaryViewModel.swift:488-503`) |
| 7 | `arrow.counterclockwise` glass circle | edit | top-right | tap | no-op if empty; else confirm `editor_discard_title` → restore entry snapshot | label "Discard Changes"; `Haptics.tap` (`DiaryPageView.swift:225-228`; `DiaryViewModel.swift:196-209`) |
| 8 | `checkmark` glass circle, active | edit | top-right | tap | save (with location/cross-day confirmations as applicable) | primary tint + `.isSelected`; label "Save" (`DiaryPageView.swift:229-232`; `DiaryViewModel.swift:213-316`) |
| 9 | Search text field | read + search | first row of scroll content | type | 150 ms debounce → recompute hits → highlight | clear ✕ at 15 pt when non-empty (`DiaryPageView.swift:240-243`; `DiaryViewModel.swift:65-99`) |
| 10 | `chevron.up` / `chevron.down` plain 44×44 | read + search | inside the search capsule | tap | wrap to prev/next hit; scroll `block-<id>` to centre | `Haptics.tap`; labels "Previous match"/"Next match" (`DiaryPageView.swift:246-251`, `:263-277`) |
| 11 | Block header row | read | inside block card | tap | select mode: toggle id; else `enterEditBlock(index)` (past days without `allowHistoryEdit` ⇒ "Read-only content") | select mode: `Haptics.tap`, checkmark circle 16 pt, `.isSelected` (`DiaryPageView.swift:359-362`, `:384-395`) |
| 12 | Block body text | read | inside block card | tap (not on to-do/image) | same as #11 | — (`DiaryPageView.swift:371-373`; `ReadTextView.swift:158`) |
| 13 | To-do row | read | inside block body | tap on the row | immediately persists the `done` flip via `updateBlockContent` and bumps the version | no explicit a11y label; row is struck through at α 0.45 when done (`ReadTextView.swift:148-152`; `DiaryViewModel.swift:359-378`) |
| 14 | Inline image | read | inside block body | tap | full-screen preview cover | `Haptics.tap`; container `.isImage` (`MediaViews.swift:32-35`, `:42`; `DiaryPageView.swift:61-63`) |
| 15 | Long press (0.4 s) | read | anywhere on a block card | hold | `enterSelect(block.id)` with `.diaryQuick` | past days refused without `allowHistoryEdit` (`DiaryPageView.swift:377-379`; `DiaryViewModel.swift:157-167`) |
| 16 | Style menu | edit | format bar, first item | tap | choose Heading / Subheading / Body for every paragraph touched | `Haptics.tap` + refocus; `.isSelected` when current ≠ body; a11y label + value (`RichTextView.swift:227-262`) |
| 17 | `text.aligncenter` | edit | format bar | tap | toggle centred alignment | disabled at 0.35 opacity while list/quote/todo active (`RichTextView.swift:279-282`, `:219`) |
| 18 | `list.bullet` | edit | format bar | tap | toggle bullet list on the paragraph(s) | `.isSelected` when active (`RichTextView.swift:286-289`) |
| 19 | `text.quote` | edit | format bar | tap | toggle quote (background block, alignment forced left) | `.isSelected` (`RichTextView.swift:290-293`) |
| 20 | `checklist` | edit | format bar | tap | toggle to-do marker | `.isSelected` (`RichTextView.swift:294-297`) |
| 21 | `bold` / `italic` / `strikethrough` / `underline` | edit | format bar | tap | toggle the character style on the selection, or on typing attributes when the selection is empty | `.isSelected`; localized labels (`RichTextView.swift:301-316`) |
| 22 | Time capsule | edit | editor card top | none | static block start time | — (`DiaryPageView.swift:402-409`) |
| 23 | Location chip (`Menu`) | edit, with location | under the time capsule | tap | choose a precision (checkmark on current); new block also offers "Retry location" | `Haptics.tap`; a11y label "Choose a location", value = address (`DiaryPageView.swift:458-488`) |
| 24 | "Retry location" chip | edit, new block | under the time capsule | tap | `beginLocate()` (permission prompt + 600 ms re-check if needed) | `Haptics.tap` (`DiaryPageView.swift:444-452`; `DiaryViewModel.swift:387-406`) |
| 25 | Preview image drag | full-screen preview | — | drag with translation > 140 pt or predicted end > 900 | dismiss | otherwise spring back with `.diaryQuick` (`MediaViews.swift:157-172`) |
| 26 | Preview backdrop | full-screen preview | — | tap | dismiss | `.isModal` + default dismiss action (`MediaViews.swift:146-150`, `:175-178`) |
| 27 | Back gesture/chevron while editing | edit | top-left | tap | confirm "Leave editor" → discard and dismiss | `Haptics.tap` (`DiaryViewModel.swift:177-185`) |

---

## 8. Known gaps

### 8.1 Things the docs state that the code does not do
1. **Landscape format bar is not right-edge anchored.** `RichTextView.swift:183-184` and
   `docs/adaptive-layout-plan.md` describe the vertical bar as sitting to the right of the body, but
   the bar remains a child of the bottom-aligned `ZStack` (`DiaryPageView.swift:13-28`) and only the
   stack axis changes (`RichTextView.swift:275-320`). There is no trailing/frame anchoring.
2. **`docs/design-system.md:89-92` is stale.** It documents the persisted style column as
   `ContentPart.type` with values `h1` / `h2` / `p`, while the code persists `ContentPart.style`
   with `title` / `heading` / `body` (`ContentPart.swift:61-63`, `:123-130`); `h1`/`h2`/`p` survive
   only as v1 read-compatibility aliases (`ContentPart.swift:80-83`, `:132-144`).
3. **`docs/editor-typography.md:37` AX5 ≈ 240 pt** is a derived value
   (`160 × multiplier(17, .accessibility5)`), not a constant in code; only the default 160 pt is
   literal (`RichTextView.swift:29`, `:42`).
4. **Highlighting is not per-hit.** `docs/read-edit-screen-design-spec.md:150-153` describes
   highlighting "per run"; in code every `ReadTextView` chunk recomputes highlights independently
   from its own attributed string (`ReadTextView.swift:105-137`), while hit counting is over flattened
   part text (`DiaryViewModel.swift:85-96`). The two use different matching rules (phrase vs
   whitespace-separated terms), so the Nth highlighted occurrence does not necessarily correspond to
   hit index N.
5. **`docs/design-system.md:286` / editor-typography claims "no undo stack"** — true, and the code
   does not disable the system shake-to-undo either; it is simply never surfaced
   (`docs/editor-typography.md:55`; no `undoManager` reference anywhere in the diary sources).

### 8.2 Dead code / write-only state in scope
1. `SearchHit.partIndex`, `SearchHit.start`, `SearchHit.len` are constructed
   (`DiaryViewModel.swift:91`) but never read — only `blockId` is used
   (`DiaryViewModel.swift:104`; `SearchHit` at `:529-534`).
2. `PreviewItem.ratio` is constructed (`DiaryPageView.swift:369`, `MediaViews.swift:5-9`) but never
   read by `ImagePreviewView`, which scales the image with `.scaledToFit()` (`MediaViews.swift:139-179`).
   `docs/design-system.md:267-268` lists it as a write-only property.
3. `DiaryImageStore.invalidate(src:)` / `invalidateAll()` and `ContentPartCache.invalidate()` have no
   callers (`MediaStore.swift:31-36`, `:70-76`; `docs/design-system.md:262-266`).
4. `GlassEmptyState` (`Components.swift:458-480`) is instantiated by other screens but never by the
   diary read screen.
5. `FlowLightOverlay` (`Components.swift:649-689`) has no instantiation anywhere
   (`docs/design-system.md:258-261`).
6. `EditBlock.diaryId` is written but never read (`DiaryModels.swift:14`;
   `docs/design-system.md:267-268`).
7. `DiaryImageStore` cache key is the raw `src` string (`MediaStore.swift:120-122`), and
   `src` is a millisecond timestamp filename (`DiaryViewModel.swift:491-497`), i.e. not
   content-addressed; an overwriting import can therefore hit a stale cache entry
   (`docs/design-system.md:262-266`).

### 8.3 Missing features (verified absent from these sources)
1. **No colour picker / text-colour control.** The format bar exposes only style, centre, list,
   quote, to-do and four character styles (`RichTextView.swift:264-336`); `TextRun` has no colour
   field (`ContentPart.swift:11-57`).
2. **No undo/redo stack.** Discard restores the snapshot captured on entry
   (`DiaryViewModel.swift:196-209`); `editor_redo` was deleted
   (`docs/editor-typography.md:36`; missing from `Localizable.xcstrings`).
3. **No map view.** A location is only a one-to-two-line `Label` with `location.fill`
   (`DiaryPageView.swift:309-315`, `:346-352`).
4. **No monospaced paragraph style** — it would require a new `ContentPart.style` value and support in
   both the read and share renderers (`docs/editor-typography.md:147-148`).
5. **No in-app font-size setting and no arbitrary point sizes.** Sizes follow the system text-size
   category only (`docs/editor-typography.md:145-146`; `RichTextEngine.swift:26-33`).
6. **No dedicated read-mode empty state / illustration / CTA** — only the hero line
   `read_day_empty` (`DiaryPageView.swift:316-320`).
7. **No share-image preview.** The share sheet holds a single `ShareLink` button in a 170 pt detent;
   the rendered image itself is only the link's `preview:` (`DiaryPageView.swift:53-59`).
8. **No read-mode text selection or copy** (`ReadTextView.swift:26-32`: `isSelectable = false`).
9. **No map/fold/hinge reservation hook** — `GeometryProxy.reservedRegion` and the fold "avoid area"
   environment hook are explicitly not implemented (`docs/design-system.md:330-333`, `:339-341`).
10. **No search-result highlighting inside images, and no search index over image alt text** — search
    flattens part text only (`DiaryViewModel.swift:85-96`; `ContentFlatten.flattenPart`,
    `ContentPart.swift:167-178`).
11. **To-do rows are not exposed as accessible controls** (no toggle action/label); toggling is only
    reachable by tapping the text attachment (`ReadTextView.swift:148-152`).
12. **A past-day block saved without a location can never gain one**, by design: only a new block
    looks a location up (`DiaryViewModel.swift:390`, `:425-430`, `:441-445`), and saving a new block
    without a location asks for confirmation first (`DiaryViewModel.swift:251-264`).

---

### Cross-check summary of headline numbers
- Editor ladder: **28 / 22 / 17 / 15** pt design sizes (title / heading / body / quote)
  (`RichTextEngine.swift:26-33`); line spacing `0.13×` (quote `0.5×`) and paragraph spacing
  `0.4× / 0.3× / 0 / 0.4×` (`:40-53`).
- Read column ≤ **660 pt**, edit column ≤ **620 pt**, page padding **16 pt** each side
  (`DiaryPageView.swift:146`; `AdaptiveLayout.swift:62-76`).
- Format bar offset: `keyboardHeight + 8` when up, `safeAreaBottom + 12` when down
  (`DiaryPageView.swift:26`); editor minimum input height **160 pt** × body multiplier
  (`RichTextView.swift:29`).
- Search debounce **150 ms** (`DiaryViewModel.swift:69`); preview dismiss at **140 pt** drag or
  **900** predicted velocity (`MediaViews.swift:164`).
- Share canvas **720 pt** wide at `scale = 2` (1440 px), content column **564 pt**,
  timeline at x **61**, footer **34 pt** (`ImageShareService.swift:10-17`, `:218-221`).

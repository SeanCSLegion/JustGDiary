# JustGDiary Android architecture

This repository is the actively updated Android client. Product semantics, feature scope, data meaning and observable outcomes follow iOS (`../JustADiary`); Android interaction and visual presentation follow Google Material 3. The retired Harmony client is historical reference only.

## Source ownership

- `app/.../app`: `MainActivity`, startup, deep links and dependency wiring.
- `ui/root`: app shell, four destinations and full-screen diary route.
- `ui/home`, `ui/diary`, `ui/footprint`, `ui/search`, `ui/settings`: screen composition, screen-only subcomponents and screen ViewModels. Keep screen-specific code in its screen folder.
- `ui/components`: reusable Compose components shared by multiple screens.
- `ui/adaptive`: one window geometry snapshot distributed from the root. Use current width/height and insets; do not branch on device category.
- `ui/theme`: Material 3 color, type, shape and motion tokens. Do not reproduce Liquid Glass.
- `data` and `data/db`: SQLite schema, repositories and cross-client serialization contracts.
- `model`: domain and persisted data types; `service`: platform and infrastructure operations.
- `app/src/test` and `app/src/androidTest`: deterministic logic tests and device/UI checks.

## Separation rules

1. Composables render state and emit user actions. ViewModels coordinate screen state; repositories own queries and persistence; services wrap system APIs.
2. A component used by one screen stays under that screen. Put only genuinely shared UI in `ui/components`; avoid generic catch-all packages.
3. Screen state is owned by its ViewModel or explicit navigation state, not shared through mutable globals.
4. Keep SQLite schema, content JSON, backup structure and user-visible behavior aligned with iOS. Keep Android Material 3 controls, 48dp touch targets, semantic colors, snackbar/dialog/sheet patterns native to Android.
5. The supported product is phone portrait and phone landscape, including foldable window changes. No tablet-specific navigation, layout or orientation behavior is maintained. Landscape and foldable behavior is specified in [`landscape-and-foldables.md`](landscape-and-foldables.md); no new foldable implementation is planned in this alignment pass.

## Screen specifications

- [Home calendar](screens/home.md)
- [Diary read and edit](screens/diary.md)
- [Footprint](screens/footprint.md)
- [Search](screens/search.md)
- [Settings](screens/settings.md)

The platform design baseline is Material 3 in [`android-design/01-design-system.md`](android-design/01-design-system.md). iOS feature parity and shared data contracts are maintained by the paired screen pages and `android-design/07-ios-map-and-plan.md`.

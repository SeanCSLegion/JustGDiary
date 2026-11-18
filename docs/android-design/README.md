# Android design documentation

Current architecture and feature behavior are maintained in:

- [`../architecture.md`](../architecture.md): source boundaries, code ownership and cross-client rules.
- [`../screens/`](../screens/): current Home, Diary, Footprint, Search and Settings behavior by screen.
- [`../landscape-and-foldables.md`](../landscape-and-foldables.md): phone landscape and foldable window contract. Tablet-specific layouts are retired.
- [`01-design-system.md`](01-design-system.md): Google Material 3 tokens, accessibility and interaction components.
- [`02-adaptive-and-navigation.md`](02-adaptive-and-navigation.md): current window geometry and bottom navigation rules.
- [`07-ios-map-and-plan.md`](07-ios-map-and-plan.md): iOS to Android source mapping and migration history.

The detailed `03-home.md`, `04-diary.md`, `05-search-footprint-settings.md`, `09-test-checklist.md` and mockups were written during the earlier tablet-oriented migration. Treat their tablet-only examples, device matrices and validation items as retired historical material; the paired screen specs and adaptive rules above are authoritative for current work. Do not add tablet support based on those archived examples.

## Platform contract

Product semantics and shared data formats follow iOS. Android appearance and controls follow Google Material 3. The application supports phone portrait, phone landscape and current window changes on foldables. There is no tablet mode flag, tablet-specific navigation, or tablet layout commitment.

# Window layout and navigation

This specification describes phone portrait, phone landscape and window changes on foldables. Tablet-specific navigation and layouts have been retired. Do not use a device-category or `sw600dp` branch to restore them.

## One geometry source

`RootScreen` reads the current Compose window bounds once and provides `AdaptiveLayout` through `LocalAdaptiveLayout`. Full-screen diary routes use `AdaptiveLayoutHost`. Screen components consume this value rather than reading their own screen/window dimensions.

`contentWidth = width - safeLeading - safeTrailing`. All values are current window dp measurements, so split-screen and foldable resizing are naturally reflected. There is no tablet mode flag, orientation policy, NavigationRail, or tablet breakpoint.

## Navigation

The four destinations (Home, Footprint, Search, Settings) use a Material 3 `NavigationBar` at every window size. It participates in layout through `Scaffold`; it is not a floating overlay. Full-screen diary and editor routes sit above the destination shell. Platform back behavior and state restoration remain consistent across rotation/window changes.

## Phone layout behavior

| Screen | Portrait | Landscape when space permits |
| --- | --- | --- |
| Home | Month/week/year calendar flow and selected-day content | Calendar and selected-day content side by side; retain selected date and settle the calendar to month mode as already implemented |
| Diary | One readable column, capped at 660dp | Same one-column flow and width cap |
| Footprint | Vertical statistics, trend and location hierarchy | Trend and location list side by side when minimum widths fit |
| Search | Search controls and results in a vertical flow | Filters and results may sit side by side when minimum widths fit |
| Settings | One-column scroll | One-column scroll |

Home and Footprint use the current geometry-based `splitsMasterDetail` predicate. A portrait window stays in portrait flow even if it is wide. Do not add per-device branches. Search can use the same orientation/space intent while keeping filters visible and reversible.

## Foldable continuity

Retain the existing window-driven layouts. When the window resizes or changes orientation, preserve selected date, diary text/caret, search query and criteria, expanded place state, and scroll position where the owning screen supports restoration. Do not infer a hinge location from device dimensions. Hinge-aware behavior requires a separately reviewed design note before implementation.

## Visual and interaction rules

Use Material 3 semantic roles and components, 48dp minimum touch targets, Snackbar for transient feedback, AlertDialog for confirmation, and ModalBottomSheet for parameter selection. Do not reproduce iOS Liquid Glass. Keep insets applied once at the shell or route host.

## Focused verification

- Verify Home stays single-column in portrait and switches only when landscape content widths meet the documented minimums.
- Verify rotation/window resizing does not lose the active page state.
- Verify the bottom NavigationBar remains reachable and does not overlap content.
- Verify diary text remains capped and editable with the keyboard visible.
- Verify no UI behavior depends on `sw600dp`, `is_tablet`, or a tablet navigation branch.

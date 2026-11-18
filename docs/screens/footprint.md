# Footprint

**Code:** `ui/footprint/FootprintScreen.kt` and `FootprintViewModel.kt`; aggregation and location policy live in `data`/`service`, not in Composables.

**Flow:** show location totals and trend, then a country/province/city hierarchy. Expanding a location reveals matching days and opens the associated diary. Empty and partial-location states remain explicit. New-location capture and historical backfill keep their deliberately distinct precision rules (`docs/location-recording.md`).

**Responsive behavior:** portrait is a vertical reading flow. Landscape may place the trend and location list side by side when usable width permits; width is derived from the window and insets. No tablet-only variant.

**Visual/accessibility:** Material 3 chart colors and surfaces; charts expose a textual summary and meaningful TalkBack descriptions. See `android-design/05-search-footprint-settings.md` for existing implementation details.

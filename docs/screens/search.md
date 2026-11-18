# Search

**Code:** `ui/search/SearchScreen.kt` and `SearchViewModel.kt`; FTS query, segmentation and SQL stay in `data`/`service`.

**Flow:** search diary text, apply visible date and location filters, remove a single filter or clear all, inspect loading/empty/no-result/error/result states, then open a result at its diary date. New criteria invalidate stale in-flight results so an older query cannot replace the current list.

**Responsive behavior:** portrait stacks filters and results; landscape may place them side by side when the minimum widths fit. Keep criteria visible and reversible; do not introduce a tablet-only persistent sidebar.

**Visual/accessibility:** Material 3 search field, chips, sheets and progress feedback; TalkBack announces result count and loading/empty states. Detailed current behavior is in `android-design/05-search-footprint-settings.md`.

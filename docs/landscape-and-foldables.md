# Android landscape and foldable behavior

Phone landscape is supported. Existing layout adaptations stay in place; this document sets the maintenance contract and does not schedule new foldable-specific implementation.

- Main navigation remains a Material 3 `NavigationBar` at all window sizes.
- Layout decisions use the current window width, height and safe drawing insets, never tablet/device-class checks.
- In landscape, Home and Footprint may use their existing side-by-side layout when both content columns meet their minimum widths. Search may place filters beside results when the same geometry predicate is met. Diary remains one readable, width-capped column. Settings remains one column.
- In portrait, Home retains the month/week/year flow. A wide portrait window does not automatically become a two-pane page.
- Foldable window changes must not discard the selected date, search criteria, editor contents or scroll state. Existing window-driven behavior is retained; do not add hinge-specific UI until a separate design decision is documented.
- New foldable work proposals must describe the window state, orientation, insets/hinge information available, affected screen, state continuity, fallback behavior and device validation before implementation.

Tablet-specific rails, tablet layouts, tablet rotation policy and tablet mockups are retired. Historical migration logs may mention them as past decisions; they do not define current behavior.

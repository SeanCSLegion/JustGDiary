# Home calendar

**Code:** `ui/home/` (`HomeScreen`, `HomeViewModel`, calendar grid/canvas/layout, month flow and morph components). State transitions belong in the ViewModel or narrowly scoped navigation state; geometry and drawing are separate helpers.

**Flow:** month is the entry state; month/week/year transitions preserve the selected date. Selecting a date updates its diary preview; future dates cannot create entries and provide user feedback. Diary markers, today and selection are independent visual states.

**Gestures:** vertical month flow changes months; date selection and year/month morph retain their documented anchors. In landscape, when the two minimum columns fit, the calendar and selected-day content may appear side by side. Portrait keeps the three-state calendar. No tablet-only variants.

**Visual/accessibility:** Material 3 semantic colors and typography, 48dp targets, TalkBack descriptions for dates and states. Keep motion interruptible and selection understandable without color alone. See `android-design/03-home.md` for detailed behavior and `landscape-and-foldables.md` for responsive rules.

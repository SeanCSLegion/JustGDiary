# Diary read and edit

**Code:** `ui/diary/` owns reading, editing, rich-text spans/codec, format actions, media, page state and sharing. `DiaryPageViewModel` coordinates state; data persistence remains under `data` and media services.

**Flow:** open a date from Home, read its ordered entries, enter edit mode, save or confirm abandoning dirty changes. Image insertion, location/time metadata, paragraph and inline formatting, checklist behavior and sharing keep the same user-visible meaning as iOS.

**Invariants:** content JSON stores semantic styles, not platform font sizes; legacy data remains readable. Reading and editing use the same capped text column. Keyboard changes preserve the active caret and keep the format controls reachable. Deletion and backup import preserve media references.

**Visual/accessibility:** Material 3 top app bar, surfaces and sheets; 48dp actions and TalkBack labels. No tablet-specific presentation. See `android-design/04-diary.md` and `android-design/08-diary-implementation-plan.md`.

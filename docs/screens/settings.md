# Settings

**Code:** `ui/settings/` contains settings screen sections, rows and dialogs; `SettingsViewModel` owns settings state and routes persistence/system work to services.

**Flow:** grouped appearance, locale, date/time, reminder, backup and about controls. Changes apply immediately where safe. Before changing the day boundary, explain and confirm any diary reassignment conflicts. Backup overwrite requires a clear confirmation. Version shown in the UI comes from the Android build version.

**Layout and design:** one-column scroll on phones, including landscape. Use Material 3 controls, dialogs, snackbars and bottom sheets; dynamic color follows the app's established setting. Keep touch targets at least 48dp and labels accessible. See `android-design/05-search-footprint-settings.md`.

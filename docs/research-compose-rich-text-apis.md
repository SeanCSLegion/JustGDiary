# Google's official rich-text editing APIs for Jetpack Compose — evidence-backed report

**Target project:** `/Users/cov/JustGDiary` (Kotlin, Compose BOM `2026.09.00`, material3 `1.4.0`, compileSdk 37, minSdk 33)
**Date of investigation:** artifacts and docs as resolved on this machine.
**Method:** (a) `./gradlew :app:dependencies`, (b) `javap` on the real `classes.jar` extracted from the resolved AARs, (c) **full source inspection** of the `-sources.jar` and `-samples-sources.jar` published alongside those exact AARs, (d) `curl`/`web_fetch` of primary Google docs.

> Conventions: **[VERIFIED]** = I read the actual artifact/doc. **[UNVERIFIED]** = search-result snippet or could not fetch. **[NOT PRESENT]** = I grepped/javap'd the whole artifact and the symbol does not exist.

---

## 1. Exact resolved dependency versions — [VERIFIED]

Command run (BUILD SUCCESSFUL, exit 0):

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:dependencies --configuration debugRuntimeClasspath
```

| Artifact | Resolved | Evidence |
|---|---|---|
| `androidx.compose.foundation:foundation` | **1.12.1** | `+--- androidx.compose.foundation:foundation -> 1.12.1` |
| `androidx.compose.ui:ui-text` | **1.12.1** | constraint `androidx.compose.ui:ui-text-android:1.12.1 (c)` |
| `androidx.compose.material3:material3` | **1.4.0** | `+--- androidx.compose.material3:material3:1.4.0 (*)` |
| `androidx.compose.ui:ui` | **1.12.1** | `+--- androidx.compose.ui:ui -> 1.12.1 (*)` |

- material3 is **pinned to 1.4.0** in `gradle/libs.versions.toml` (`material3 = "1.4.0"`, comment *"material3 单独 pin 以锁定稳定版"*), so it does **not** follow the BOM. Only `material3` and `material:material-icons-core` (**1.7.8**) are off the 1.12.1 line.
- AARs I actually opened: `foundation-android/1.12.1/…/foundation.aar`, `ui-text-android/1.12.1/…/ui-text.aar`, `ui-android/1.12.1/…/ui.aar`, `material3-android/1.4.0/…/material3.aar` (all under `~/.gradle/caches/modules-2/files-2.1/`), each with matching `-sources.jar` / `-samples-sources.jar`.
- **[VERIFIED from release notes]** Compose Foundation **1.12.0 = August 12, 2026**; **1.12.1 = September 09, 2026**. 1.12.1's only release-note sentence is the version/commits boilerplate — i.e. **1.12.1 is a pure bug-fix release over 1.12.0** ([release notes](https://developer.android.com/jetpack/androidx/releases/compose-foundation)).

---

## 2. What the resolved artifacts ACTUALLY contain — [VERIFIED by `javap` + sources]

### 2.1 `TextFieldState` / `TextFieldBuffer` / `rememberTextFieldState` — all present

`androidx.compose.foundation.text.input.TextFieldState` (public final class, **not** experimental):

```java
public final class androidx.compose.foundation.text.input.TextFieldState {
  public final java.lang.CharSequence getText();
  public final long getSelection-d9O1mEE();                       // TextRange
  public final androidx.compose.ui.text.TextRange getComposition-MzsxiRA();
  public final androidx.compose.foundation.text.input.TextFieldTextStyles getTextStyles();
  public final androidx.compose.foundation.text.input.UndoState getUndoState();   // @ExperimentalFoundationApi
  public final void edit(kotlin.jvm.functions.Function1<? super TextFieldBuffer, kotlin.Unit>);
  public final TextFieldBuffer startEdit();  public final void commitEdit(TextFieldBuffer);
  public final void finishEditing();
}
```

`androidx.compose.foundation.text.input.TextFieldStateKt` (top-level members):

```java
public static rememberTextFieldState-Le-punE(String, long, Composer, int, int);  // rememberTextFieldState()
public static void setTextAndPlaceCursorAtEnd(TextFieldState, String);
public static void setTextAndSelectAll(TextFieldState, String);
public static void clearText(TextFieldState);
public static TextFieldBuffer toTextFieldBuffer(TextFieldState);
public static TextFieldTextStylesImpl getTextFieldTextStyles(TextFieldBuffer);
```

### 2.2 The rich-text style API — present, and it is **range annotations on the buffer**

`TextFieldBuffer` style surface, verbatim from `javap` (all are **stable** — the source carries only `@OptIn(ExperimentalFoundationApi::class)` *internally*, which does not make them experimental):

```java
public final void addStyle(androidx.compose.ui.text.SpanStyle, int start, int end);
public final void addStyle(androidx.compose.ui.text.ParagraphStyle, int start, int end);
public final TrackedRange<SpanStyle>      addStyle(SpanStyle,      TextRange range, ExpandPolicy);
public final TrackedRange<ParagraphStyle> addStyle(ParagraphStyle, TextRange range, ExpandPolicy);
public final java.util.List<TrackedRange<SpanStyle>>      getSpanStyles(TextRange range);
public final java.util.List<TrackedRange<ParagraphStyle>> getParagraphStyles(TextRange range);
public final boolean        removeStyle(TrackedRange<?>);
public final SpanStyle      getSpanStyle(TrackedRange<SpanStyle>);      public final void setSpanStyle(TrackedRange<SpanStyle>, SpanStyle);
public final ParagraphStyle getParagraphStyle(TrackedRange<ParagraphStyle>); public final void setParagraphStyle(TrackedRange<ParagraphStyle>, ParagraphStyle);
// extension properties (TextFieldBuffer.kt:860–971):
public final boolean   isValid(TrackedRange<?>);                            // val TrackedRange<*>.isValid
public final TextRange getTextRange(TrackedRange<?>);  public final void setTextRange(TrackedRange<?>, TextRange);
public final SpanStyle getSpanStyle(TrackedRange<SpanStyle>);               // var TrackedRange<SpanStyle>.spanStyle
public final ParagraphStyle getParagraphStyle(TrackedRange<ParagraphStyle>); // var .paragraphStyle
public final ExpandPolicy getExpandPolicy(TrackedRange<?>); public final void setExpandPolicy(TrackedRange<?>, ExpandPolicy);
```

`ExpandPolicy` is a `@JvmInline value class` with `InsideOnly`, `AtStart`, `AtEnd` (and Both) — whether a style grows when text is inserted exactly at its boundary. `TrackedRange<T>` is a public, **non-experimental** class; its KDoc (TrackedRange.kt:23–47) says it "acts as a unique handle to a specific style range", its properties "automatically update when the text is edited", and *"This object's lifecycle is bound to the `TextFieldBuffer` … **Do not keep a reference of it.**"*

`androidx.compose.foundation.text.input.TextFieldTextStyles` — the read-only observer, also **non-experimental**:

```kotlin
interface TextFieldTextStyles {
    fun getSpanStyles(range: TextRange): List<AnnotatedString.Range<SpanStyle>>
    fun getParagraphStyles(range: TextRange): List<AnnotatedString.Range<ParagraphStyle>>
}
```

Its KDoc documents the exact intersection semantics (crucial for a caret toolbar): for a **collapsed** query `[10,10)` a style is returned iff `style.start <= 10 < style.end` — "Touching end → NOT Returned; Touching start → Returned". Reached via `TextFieldState.textStyles`; `TextFieldState.kt:176-195` KDoc: *"Use this property when you only need to read or observe the current text styles, such as **updating a formatting toolbar** based on the style of the currently selected text."*

### 2.3 `TextStyle` — **the premise in the brief is wrong** [VERIFIED]

`androidx.compose.ui.text.TextStyle` in 1.12.1 is **the classic, long-standing immutable style class**, not a new rich-text class:

```java
public final class androidx.compose.ui.text.TextStyle {        // <-- implements NOTHING
  public TextStyle(SpanStyle, ParagraphStyle, PlatformTextStyle);
  public final SpanStyle toSpanStyle();
  public final ParagraphStyle toParagraphStyle();
  public final TextStyle merge(TextStyle);  public final TextStyle plus(SpanStyle);
  public final TextStyle plus(ParagraphStyle); ...
}
```

- It lives in `androidx.compose.ui.text` (there is **no** `TextStyle` in `androidx.compose.ui.text.font`).
- It does **not** implement `AnnotatedString.Annotation`, so it **cannot** be used as a range style inside a text field.
- In `BasicTextField(state, …)` it is only the field's **base** style (`textStyle: TextStyle = TextStyle.Default`). Per-range styling is exclusively `SpanStyle` / `ParagraphStyle`.
- Independently confirmed old: the 1.0.0-alpha11 release note already lists `TextStyle` among classes made non-data-classes.

### 2.4 `ParagraphStyle` / `SpanStyle` / `AnnotatedString` [VERIFIED]

```java
public final class androidx.compose.ui.text.ParagraphStyle implements AnnotatedString$Annotation {
  // textAlign, textDirection, lineHeight, textIndent, platformStyle, lineHeightStyle,
  // lineBreak, hyphens, textMotion, + merge/plus/copy
}
public final class androidx.compose.ui.text.SpanStyle implements AnnotatedString$Annotation {
  // color/brush, fontSize, fontWeight, fontStyle, fontSynthesis, fontFamily, fontFeatureSettings,
  // letterSpacing, baselineShift, textGeometricTransform, localeList, background,
  // textDecoration, shadow, platformStyle, drawStyle, + merge/plus/copy
}
public final class androidx.compose.ui.text.AnnotatedString {
  public AnnotatedString(List<? extends AnnotatedString.Range<? extends AnnotatedString.Annotation>>, String);
  public List<AnnotatedString.Range<SpanStyle>> getSpanStyles();
  public List<AnnotatedString.Range<ParagraphStyle>> getParagraphStyles();
  public List<AnnotatedString.Range<String>> getStringAnnotations(int, int); // + tag overload
}
public final class AnnotatedString$Range<T> { T item; int start; int end; String tag; }
public interface AnnotatedString$Annotation {}   // marker
```

The `AnnotatedString(text, annotations: List<Range<out Annotation>>)` constructor is **public** — a `SpanStyle`/`ParagraphStyle` range list converts to an `AnnotatedString` for *read-only* `Text` rendering in one call. (There is no reverse path from an editable field — §2.5.)

### 2.5 Conversion helpers — the ones the brief asks about **do not exist** [VERIFIED, NOT PRESENT]

Grepped the complete `-sources.jar` of `foundation-android-1.12.1` and `ui-text-android-1.12.1`, plus `javap` on all four `classes.jar`:

| Symbol asked about | Status |
|---|---|
| `TextFieldState.toAnnotatedString()` | **[NOT PRESENT]** |
| `AnnotatedString.toTextFieldState()` | **[NOT PRESENT]** |
| `TextFieldState.toAnnotatedString()` / `fromAnnotatedString` on any type | **[NOT PRESENT]** |
| `TextFieldBuffer.changeSpanStyle(...)` | **[NOT PRESENT]** |
| `TextFieldBuffer.changeParagraphStyle(...)` | **[NOT PRESENT]** |
| `TextFieldState.setTextAndPlaceCursorAtEnd` | **present** (but takes `String`, not styles) |
| `TextFieldState.setTextAndSelectAll`, `clearText` | **present** |
| `TextFieldState.toTextFieldBuffer()` | **present** — one-way *copy* into a mutable buffer |
| `TextFieldBuffer.addStyle` / `getSpanStyles` / `getParagraphStyles` / `removeStyle` | **present** (see §2.2) |
| `TextFieldBuffer.addAnnotation(Annotation, int, int)` | **present but `internal`** (javap name: `addAnnotation$foundation`) — you cannot store arbitrary custom annotations |

**Consequence:** `TextFieldState` ⇄ `AnnotatedString` round-tripping must be **written by hand** (`state.edit { getSpanStyles(TextRange(0, length)) }` to read; `addStyle(...)` to write). There is no framework bridge.

### 2.6 `BasicTextField` overloads [VERIFIED from `BasicTextField.kt` source]

```kotlin
// BasicTextField.kt:189-208 — NO @ExperimentalFoundationApi => STABLE
@Composable
fun BasicTextField(
    state: TextFieldState, modifier: Modifier = Modifier, enabled: Boolean = true, readOnly: Boolean = false,
    inputTransformation: InputTransformation? = null,
    textStyle: TextStyle = TextStyle.Default,          // androidx.compose.ui.text.TextStyle
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
    onTextLayout: (Density.(getResult: () -> TextLayoutResult?) -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    cursorBrush: Brush = BasicTextFieldDefaults.CursorBrush,
    outputTransformation: OutputTransformation? = null, decorator: TextFieldDecorator? = null,
    scrollState: ScrollState = rememberScrollState(),
)
```

- `BasicTextField(value: String, onValueChange: (String) -> Unit, …)` and `BasicTextField(value: TextFieldValue, …)` **still exist and are NOT `@Deprecated` in 1.12.1**. The only `@Deprecated` occurrences in `BasicTextField.kt` are two `@Deprecated("Maintained for binary compatibility", level = DeprecationLevel.HIDDEN)` shims (lines 998, 1038).
- **They get deprecated in 1.13.0-alpha03** ([release notes](https://developer.android.com/jetpack/androidx/releases/compose-foundation)): *"BasicTextField overloads that accept `value`, `onValueChange` parameters are deprecated. Please migrate to the `BasicTextField` overload that uses `TextFieldState` to hoist its state. (I636bf, b/552879150)"*. Same for Material 2 text fields.
- **material3 1.4.0 also has state-based overloads** [VERIFIED by javap]: `material3.TextFieldKt.TextField(TextFieldState, Modifier, enabled, readOnly, TextStyle, TextFieldLabelPosition, …, InputTransformation, OutputTransformation, KeyboardOptions, KeyboardActionHandler, TextFieldLineLimits, …, ScrollState, Shape, TextFieldColors, PaddingValues, MutableInteractionSource)` plus the identical-shaped `OutlinedTextFieldKt.OutlinedTextField(TextFieldState, …)`.
- History (release notes, [VERIFIED]): `BasicTextField(state)`, `TextFieldState`, `InputTransformation`, `OutputTransformation`, `TextFieldLineLimits`, `TextFieldDecorator` graduated to **stable in 1.7.0-alpha05**; `rememberTextFieldState` stable in 1.7.0-alpha06; **`BasicTextField using a TextFieldState is now stable` in 1.7.0**.

### 2.7 The styled-text feature flag — the single most important production risk [VERIFIED]

`androidx.compose.foundation.ComposeFoundationFlags` (`@ExperimentalFoundationApi object`), line 174:

```kotlin
/** This flag controls whether [BasicTextField]'s formatted text features are enabled. */
// TODO: Remove this flag once it has soaked (b/494340211)
@field:Suppress("MutableBareField") @JvmField var isBasicTextFieldStyledTextEnabled = true
```

Behaviour in `TextFieldBuffer` when the flag is **false** [VERIFIED in source]:
- `addStyle(style, range, ExpandPolicy)` → **`throw IllegalStateException("The ComposeFoundationFlags.isBasicTextFieldStyledTextEnabled must be enabled to use this API.")`**
- `getSpanStyles(range)` / `getParagraphStyles(range)` → **`emptyList()`** (silent)
- `addStyle(style, start, end)` → only permitted inside `OutputTransformation`, and then **untracked and not part of state**.

The flag's own KDoc says the flags are *"considered temporary, and there should be no expectation for these flags be around for an extended period of time"*, and are *"always 'on' in the published artifact"*. So it defaults to `true`, but **the entire rich-text path is a togglable code path with a known removal TODO (b/494340211)** — i.e. it has not fully "soaked".

Also relevant: `isInheritedTextStyleEnabled = false` (default **off**).

### 2.8 Annotation / experimental status summary [VERIFIED]

| Symbol | 1.12.1 status |
|---|---|
| `BasicTextField(state: TextFieldState, …)` | **stable** |
| `TextFieldState`, `rememberTextFieldState`, `TextFieldBuffer` | **stable** |
| `TextFieldState.textStyles`, `TextFieldTextStyles` | **stable** |
| `TrackedRange<T>`, `ExpandPolicy` | **stable** |
| `TextFieldBuffer.addStyle / getSpanStyles / getParagraphStyles / removeStyle / setSpanStyle / setParagraphStyle` | **stable** (internally `@OptIn`) |
| `TextFieldState.undoState` (`UndoState`) | **`@ExperimentalFoundationApi`** — removed in **1.13.0-alpha03** per release note *"Removed `@ExperimentalFoundationApi` annotation from `undoState` in `TextFieldState`"* |
| `ChangeList.forEachChange` / `forEachChangeReversed` | `@ExperimentalFoundationApi` |
| `ComposeFoundationFlags` | `@ExperimentalFoundationApi` |
| `TextFieldState.setSelection` | does not exist; use `edit { selection = TextRange(...) }` / `originalSelection` |

**What "experimental" means practically:** `@ExperimentalFoundationApi` is a `@RequiresOptIn` marker — you must add `@OptIn(ExperimentalFoundationApi::class)` (or a module-wide compiler flag). It carries **no API-stability guarantee**: signatures may change in a minor release, and `androidx` routinely renames/removes such members (this report itself documents `AnnotatedOutputTransformation` → `addStyle` churn between 1.9.0-alpha04 and 1.9.0-beta01). For a shipped app it means: pin the exact version, expect to touch the code on upgrade, and R8/lint will flag usage.

---

## 3. Google's official documentation and samples

### 3.1 Docs — the complete `/develop/ui/compose/text/` section [VERIFIED: href-scraped from the doc nav]

`autofill`, `configure-layout`, `display-text`, `emoji`, `fonts`, **`migrate-state-based`**, **`style-paragraph`**, **`style-text`**, **`user-input`**, `user-interactions`.

- **"Migrate to state-based text fields" — `https://developer.android.com/develop/ui/compose/text/migrate-state-based`** [VERIFIED, fetched]. Value-based → state-based pairs: `value/onValueChange` → `rememberTextFieldState()`; `onValueChange` filtering → `InputTransformation`; `visualTransformation` + `OffsetMapping` → `outputTransformation`; `maxLines` → `lineLimits = TextFieldLineLimits.MultiLine(1, 10)`.
  - **Its only "rich text" example is markdown markers, not styles.** Section *"Updating the state (complex)"* shows `OldTextFieldAddMarkdownEmphasis` → `NewTextFieldAddMarkdownEmphasis`: `markdownState.edit { insert(originalSelection.max, "**"); insert(originalSelection.min, "**"); selection = TextRange(originalSelection.min + 2, originalSelection.max + 2) }`. Quoted guidance: *"a button adds the Markdown decorations to make the text bold around the cursor or the current selection."* **`SpanStyle`/`ParagraphStyle`/`addStyle` are never mentioned on this page.**
  - Recommended ViewModel pattern, verbatim: *"Keeping a reference to a `TextFieldState` within your ViewModel is not an inherently bad practice"*; and it warns that `rememberTextFieldState()` includes `rememberSaveable` + `TextFieldState.Saver`, so ViewModel-owned state needs separate saved-state handling.
  - Links the official blog post `https://medium.com/androiddevelopers/effective-state-management-for-textfield-in-compose-d6e5b070fbe5` [link VERIFIED in the page HTML; content **NOT VERIFIED** — unreachable from this machine].
- **"Style text" — `.../style-text`** [VERIFIED, fetched]: `AnnotatedString` / `SpanStyle` / `ParagraphStyle` / `buildAnnotatedString` / `AnnotatedString.fromHtml` for **read-only `Text`**. No text-field editing content.
- **"Style paragraph" — `.../style-paragraph`** [VERIFIED, fetched]: `ParagraphStyle` for read-only text.
- **"Configure text fields" — `.../user-input`** [VERIFIED, fetched]: documents both `BasicTextField` and `material3.TextField` `TextFieldState` overloads side by side, `TextFieldState.setTextAndPlaceCursorAtEnd`, gradient via `TextField`'s `TextStyle`. **No rich-text/formatting section.**
- **Bottom line: Google's prose docs do NOT document rich-text editing in Compose text fields.** The only documentation is the API reference + the compiled samples.

### 3.2 The one official rich-text-editor sample [VERIFIED — read from the published artifact]

`androidx/compose/foundation/samples/BasicTextFieldSamples.kt` → **`BasicTextFieldTrackedRangeToggleBoldSample`, lines 730–841** of `foundation-android-1.12.1-samples-sources.jar` (`.../foundation-android/1.12.1/1706524b039dfc9f721a4e8f21cb6838aa16c34c/foundation-1.12.1-samples-sources.jar`). It is referenced by `TextFieldTextStyles`' own `@sample` tag, so it **is** the canonical example. Its KDoc:

> *"This sample demonstrates a realistic rich-text editor scenario using the `TrackedRange` and `TextFieldTextStyles` APIs. It implements a "Toggle Bold" formatting function on the current selection."*

Essential API usage, quoted verbatim (abridged):

```kotlin
val state = rememberTextFieldState("Hello World")

// toolbar pressed-state, from the read-only observer
val isSelection100PercentBold by derivedStateOf {
    val selection = state.selection
    if (selection.collapsed) false else {
        var boldCoverage = 0
        for (style in state.textStyles.getSpanStyles(selection)) {   // List<Range<SpanStyle>>
            if (style.item.fontWeight == FontWeight.Bold) {          // AnnotatedString.Range.item
                boldCoverage += (minOf(style.end, selection.max) - maxOf(style.start, selection.min))
            }
        }
        boldCoverage == selection.length
    }
}

fun TextFieldBuffer.boldSelection() {                                // runs inside state.edit { }
    var mergedStart = selection.min; var mergedEnd = selection.max
    for (style in getSpanStyles(selection).filter { it.spanStyle.fontWeight == FontWeight.Bold }) {
        mergedStart = minOf(mergedStart, style.textRange.start)      // TrackedRange.textRange (var)
        mergedEnd   = maxOf(mergedEnd,   style.textRange.end)
        removeStyle(style)
    }
    addStyle(SpanStyle(fontWeight = FontWeight.Bold), TextRange(mergedStart, mergedEnd), ExpandPolicy.AtEnd)
}

Button(onClick = { state.edit {
    if (selection.collapsed) return@edit     // <-- collapsed caret is NOT handled
    if (isSelection100PercentBold) unBoldSelection() else boldSelection()
} }) { Text("B") }
BasicTextField(state = state, textStyle = LocalTextStyle.current)
```

Its own comment concedes the modelling burden: *"For simplicity, this sample keeps bold styles non-overlapping and contiguous, assuming they are applied exclusively through this method."* `unBoldSelection()` is ~30 lines of manual split/truncate logic. Sibling samples in the same file: **`BasicTextFieldTrackedRangeSample`** (677–728: markdown `**bold**` auto-formatting inside `InputTransformation` via `addStyle(..., ExpandPolicy.InsideOnly)` then `delete(trackedRange.textRange.start, …)` — proves styles can be added from `InputTransformation`), **`BasicTextFieldTrackedRangeTextRangeSetterSample`** (529–564: 4-case "wipe a style" algorithm), **`BasicTextFieldUndoSample`** (599–637: `state.undoState.undo()/.redo()/.clearHistory()` gated on `.canUndo`/`.canRedo`).

**Cross-check against the live androidx-main branch [VERIFIED]:** `raw.githubusercontent.com/androidx/androidx/androidx-main/.../BasicTextFieldSamples.kt` (HTTP 200) has an **identical set of `…Sample` function names** — `BasicTextFieldTrackedRangeToggleBoldSample` is still the only rich-text-editor sample upstream.

### 3.3 Other Google sample repos — **no rich-text editor anywhere** [VERIFIED]

| Repo | Result |
|---|---|
| `github.com/android/compose-samples` (main, 1412 files) | **No rich-text editor.** Contains `JetLagged, JetNews, Jetcaster, Jetchat, Jetsnack, Reply`. Filename scan for `rich\|SpanStyle\|ParagraphStyle\|TextFieldState\|Editor\|Markdown\|format` over **all** `.kt` paths matched only `Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/MessageFormatter.kt` (read-only message formatting). **Zero files named `*TextField*`.** |
| `github.com/android/nowinandroid` (main, 1588 files) | **No rich-text editor** — text-related files are all *search* feature plumbing (`SearchScreen.kt`, `SearchContentsRepository.kt`, …). |
| `github.com/android/user-interface-samples` (main, 2035 files) | **No** file matching `rich\|editor\|spannable\|styled`. |

### 3.4 Material 3 [COULD NOT VERIFY]

`https://m3.material.io/components/text-fields/overview` returns HTTP 200 but is a client-rendered SPA: after stripping scripts the served HTML contains only font `@font-face` declarations and no body copy, and grepping for `rich text|formatting|bold|italic|styling|markdown|toolbar` yields **zero** matches. I therefore **cannot** confirm or deny any M3 "text format bar" guidance. No such guidance appeared in any Google developer doc I could fetch.

---

## 4. Known limitations and gotchas

### 4.1 Inline images in an editable text field: **NOT SUPPORTED** [VERIFIED]

Three independent pieces of evidence:

1. `grep -rn "InlineTextContent\|appendInlineContent\|Placeholder" commonMain/androidx/compose/foundation/text/input/` → **no matches**. The entire editable-text pipeline contains no inline-content concept.
2. `androidx/compose/foundation/text/InlineTextContent.kt` KDoc: *"Used to insert composables into the text layout. This method can be used together with the **`inlineContent` parameter of `[BasicText]`**. … `[BasicText]` will try to find an `[InlineTextContent]` in the map defined by `inlineContent`…"* — read-only only.
3. `TextFieldLayoutStateCache.measure(...)` (the layout path used by the state-based field) builds `AnnotatedString(text = visualText.toString(), annotations = annotations ?: emptyList())` and calls `textMeasurer.measure(text, style, …, fontFamilyResolver)` — **no `inlineContent` argument exists on that path**.

**Conclusion: images in this project must be separate blocks (as the brief already assumes), never inline inside the editable text.**

### 4.2 Per-paragraph styles and caret toggling: **supported, but you own the algebra** [VERIFIED]

- **Per-paragraph styles: YES.** `ParagraphStyle` is an `AnnotatedString.Annotation`, and `addStyle(paragraphStyle, range, ExpandPolicy)` / `getParagraphStyles(range)` exist. `ParagraphStyle` carries `textAlign`, `textDirection`, `lineHeight`, `textIndent`, `lineHeightStyle`, `lineBreak`, `hyphens`, `textMotion` — enough for title/heading/body/quote/center.
- **Style at the caret: no dedicated API.** You compute it yourself with `state.textStyles.getSpanStyles(TextRange(caret, caret))` (documented collapsed-query semantics in §2.2 — returns styles covering the caret). The official sample does **not** do this: it short-circuits `if (selection.collapsed) return@edit` and reports `false`.
- **Styles are stored as raw, independently-added ranges and are never normalized.** `getSpanStyles` KDoc: *"Styles are returned in the same order they were originally added to the buffer."* Overlapping/adjacent identical styles stay separate entries, and the range query returns *intersecting* (not *contained*) styles. The sample's `unBoldSelection()` exists purely to undo that fragmentation. **Any paragraph-style model must implement its own merge/split/normalize.**
- **Carets between styles are ambiguous by construction** — with `[0,5)` bold and `[5,10)` not bold, the caret at 5 reads as bold (touching-start rule). Typing then inherits a `TrackedRange`-driven `ExpandPolicy`, so behaviour depends on the `ExpandPolicy` you passed when the style was created.

### 4.3 Bug surface / unimplemented pieces

- **`undoState` is `@ExperimentalFoundationApi` in 1.12.1** [VERIFIED, `TextFieldState.kt:228`], only de-annotated in 1.13.0-alpha03.
- **`edit {}` interacts with undo history** [VERIFIED release notes, 1.9.0 and 1.10.0-alpha01]: *"`TextFieldState.edit { }` no longer clears the undo history. Instead it creates a standalone undo entry. If the desired behavior is to clear the undo stack after an edit call, please use `TextFieldState.undoState.clearHistory()`."* — i.e. **every programmatic formatting call you make from a toolbar becomes a user-visible undo step** unless you clear history.
- **Framework undo is IME/user-edit-oriented, not document-model-oriented.** `TextUndoManager` exposes only `canUndo/canRedo/undo(state)/redo(state)/record(...)/clearHistory()`; `record` takes an `internal` `TextUndoOperation`. You cannot record a semantic "changed paragraph style" operation through public API.
- **`InputTransformation` is not a styling hook by design.** Its KDoc [VERIFIED]: *"A function that is ran after every change made to a `TextFieldState` by user input and can change or reject that input. … ran after hardware and software keyboard events, when text is pasted or dropped into the field, or when an accessibility service changes the text."* Rejection is `revertAllChanges()`. It runs on **every** change, so heavy style normalization there is a per-keystroke cost.
- **IME composing + styling: [COULD NOT VERIFY].** `TextFieldBuffer` has `composingAnnotations`, `setComposition$foundation`, `commitComposition$foundation`, `hasComposition$foundation` — all **`internal`**, and `TextFieldCharSequence.getComposingAnnotations()` is public but the list is framework-managed. I found no public API and no official statement about styling the composing region; treat styled-IME-composition behaviour as **unverified and a risk to test on device with a CJK/GBoard IME**.
- **`changeSpanStyle`/`changeParagraphStyle` do not exist** (§2.5) — the brief's assumption is incorrect.
- **There is no `AnnotatedString` bridge** (§2.5) — you must hand-write both directions.
- **Lint rule**: since 1.9.0-alpha01 there is *"lint check support for `TextFieldState` to make sure it is remembered inside composition. Consider using `rememberTextFieldState` instead."* — do not construct `TextFieldState` directly in a composable body.
- **`TrackedRange` must not be retained** across edit blocks (KDoc, §2.2) — it is invalidated when its style collapses to zero length (`isValid` → false), and 1.12.0-beta01 note: *"prevented crashes when querying properties of invalidated `TrackedRange`s"*, i.e. this used to crash.
- **`getSpanStyles` signature churn** — 1.12.0-beta01: *"Updated `getSpanStyles` and `getParagraphStyles` to take a `TextRange` instead of separate start/end indices. Relaxed bounds checking on style queries to no longer throw exceptions."* Any tutorial written against 1.11 signatures will not compile.
- **Issue b/389978748** — search result title: *"BasicTextField with TextFieldState does not support AnnotatedString and lacks partial styling features"* [UNVERIFIED title from a search snippet; **issuetracker.google.com is unreachable from this machine**, so I could not read its status]. The same bug ID is cited by the release notes against 1.9.0-alpha03 *"Added `AnnotatedOutputTransformation`"*, which was then reworked: 1.9.0-alpha04 *"Replaced `addAnnotation` in `AnnotatedOutputTransformation` with `addStyle`"*, 1.9.0-beta01 *"Removed `AnnotatedOutputTransformation` and carried `addStyle` functions to `TextFieldBuffer`. `addStyle` can still only be called from `OutputTransformation`."* The 1.12 `TrackedRange` API is the continuation of that work — so **the feature request behind b/389978748 is effectively what shipped in 1.12.0**.

### 4.4 Mixing `EditText`/`Spannable` in `AndroidView` with Compose text fields

**[COULD NOT VERIFY — no official guidance found.]** I searched Google's docs, release notes and sample repos and found **no** official statement discouraging `AndroidView`-wrapped `EditText`+`Spannable`, and no official recommendation for or against it. Note also that Google's own guidance is contradictory in spirit: the docs *do* have a dedicated **"Using Views in Compose"** interop page (listed in the doc nav) but never mention it in any text-field context.

What **is** verified and is the real trade-off: the two stacks have **two independent text models** — `Spannable` on the platform side and `TextFieldState`+`TrackedRange` on the Compose side — with no bridge in either direction. Their focus/IME/undo/selection lifecycles are separate; the Compose field owns a `PlatformTextInputSession` (`AndroidTextInputSession.android.kt`, `LegacyPlatformTextInputServiceAdapter.android.kt`) and the `EditText` owns a real `InputConnection`. Any screen mixing both will have two IME clients.

---

## 5. Recommendation for this project

**Context I verified in the repo:** the project **already ships option (c)**: `app/src/main/java/com/cov/justdiary/ui/diary/EditorTextArea.kt` uses `AndroidView { AppCompatEditText(...) }`, with `SpannableBridge.kt` (custom `BlockStyleSpan : android.text.style.ParagraphStyle`, `StyleSpan`/`StrikethroughSpan`/`UnderlineSpan`, `ImageSpan` carrying `src`/`w`/`h`), `EditorModel.kt` (`EditorLine`, `InlineFlag{Bold,Italic,Strike,Underline}`, `EditorBlockStyle`), and `model/ContentPart.kt` (`ContentDocument(v=2, parts)`, `ContentPart(style, align, text, runs: List<TextRun>, items, done, src, w, h)`, `TextRun(bold, italic, strike, underline, text)`). The test suite is `430` `@Test` methods across 37 classes under `app/src/test` (grep count).

**Recommendation: target (a) `TextFieldState` + `SpanStyle`/`ParagraphStyle` range annotations — but only if inline images inside the text flow are genuinely dropped, and only with the flag/experimental risks accepted.** Keep (c) as the shipping implementation until an (a)-based prototype passes the same tests.

### Why (a) and not (b)

| | (a) `TextFieldState` + Span/ParagraphStyle ranges | (b) `BasicTextField(TextFieldValue(AnnotatedString))` | (c) `AndroidView` + `EditText`+`Spannable` |
|---|---|---|---|
| Status in 1.12.1 | `BasicTextField(state)` **stable**; range-style API **stable but behind `isBasicTextFieldStyledTextEnabled`** | value/`TextFieldValue` overloads **not yet deprecated in 1.12.1**, deprecated in 1.13.0-alpha03 | platform API, no Compose involvement |
| Style model | `Range<SpanStyle>` / `Range<ParagraphStyle>` — **closest thing to a cross-platform attribute model in Compose** | `AnnotatedString` (same ranges, but as *input value* only) | `Spannable` spans + custom `ParagraphStyle` span |
| Does typing inherit the style? | **Yes** — `TrackedRange` + `ExpandPolicy` shift ranges as text is edited; new text at a boundary is included per policy | **No** — you must rebuild the whole `AnnotatedString` on every keystroke; nothing tracks ranges | Yes (platform `TextWatcher`/span flags) |
| Read style at caret for a toolbar | `state.textStyles.getSpanStyles(TextRange(c, c))` (documented collapsed-query rule) | you must diff your own value | `getSpans(pos, pos, …)` |
| Inline images in the text flow | **impossible** (§4.1) | impossible | **possible** (`ImageSpan`) — currently used |
| Undo/redo | `undoState` (`@ExperimentalFoundationApi`); every `edit{}` is an undo entry | none built in | platform undo |
| Effort to adopt here | **high**: hand-written `ContentPart/TextRun` ⇄ `Range<SpanStyle>`/`Range<ParagraphStyle>` bridge + merge/normalize + revert `EditorTextArea`/`SpannableBridge` | high *and* wrong direction (deprecated, no tracking) | already done |
| Cross-platform `content_json` fit | good for *marks*; **poor for paragraph `style`** (see below) | same | already solved by `SpannableBridge` |

Option **(b) is not recommended at all**: it is the overload Google is deprecating, it has no range tracking while typing, and it offers nothing (a) does not.

### The one modelling mismatch to plan for

`content_json` is **segment-based**: a `ContentPart` has one `style` for the whole block, and `runs: List<TextRun>` where each run carries **booleans** (`bold/italic/strike/underline`). Compose's model is **range-based**: independent `Range<SpanStyle>` / `Range<ParagraphStyle>` lists over one flat text, returned un-normalized and un-merged.

So the adapter must:
1. **On load (`content_json` → `TextFieldState`)**: flatten parts to one text (block separators are your choice), then per block `addStyle(ParagraphStyle(fontSize=…, lineHeight=…, textAlign=…), blockRange, ExpandPolicy.AtEnd)`, and per run `addStyle(SpanStyle(fontWeight=…, fontStyle=…, textDecoration=…), runRange, ExpandPolicy.AtEnd)`.
2. **On save (`TextFieldState` → `content_json`)**: `state.edit { getSpanStyles(TextRange(0, length)); getParagraphStyles(TextRange(0, length)) }`, then **normalize**: collect all distinct offsets, split into maximal segments with a constant attribute set, merge adjacent identical segments, and rebuild `runs`. This normalization is 100% pure logic — it belongs in `app/src/test`, alongside the existing 430 tests, and is the part most likely to break iOS byte-compatibility. Write it first and test it against `app/src/test/resources/golden/`.
3. **Paragraph styles that Compose cannot express as `ParagraphStyle`**: `list`, `todo` (markers) and `align=center` semantics. The project currently models list/todo markers as **real characters** (`"• "`, `"☐ "`, `"☑ "`) and stores them in `ContentPart.items`/`done`, *not* as spans. `ParagraphStyle` has **no** textAlign-independent list/todo concept. Keep markers as real characters (as today) and only map `textAlign = TextAlign.Center` for `center`, `textIndent`/`lineHeight` for `quote`, and font metrics for `title`/`heading`. Do **not** try to encode `list`/`todo`/`image` as `ParagraphStyle` ranges — the release notes note that `AnnotatedString.fromHtml` *"won't be able to display bullet lists yet"*, confirming the framework has no list model.
4. **Inline images stay separate blocks** — this is now a **hard** requirement under (a), not a preference. Under (c) the project currently relies on `ImagePayloadSpan` round-tripping through the `EditText` (`EditorModel.kt:45–50`: *"`src` 与宽高**能穿过 EditText 往返** —— 否则用户插入的图片会在下一次编辑时丢失"*). Dropping that is the largest behavioural change of the migration.

### Risk register for adopting (a) on 1.12.1

1. **Feature flag** `isBasicTextFieldStyledTextEnabled` is documented as *temporary* with TODO b/494340211; if a future release flips or removes it, `getSpanStyles` **silently returns `emptyList()`** — a style-loss bug that unit tests on JVM would not catch (it needs an instrumented/Compose test). Add a startup assertion + a golden round-trip test.
2. **`undoState` is experimental** and every programmatic `edit {}` becomes an undo entry — call `undoState.clearHistory()` after toolbar-driven formatting, or keep the model-level **放弃修改** snapshot as the only rollback path（撤销/重做已确认不做，`EditorHistory` 已删除）.
3. **No `AnnotatedString` bridge** — budget real time for step 2 above.
4. **IME composing + styles is unverified** (§4.3) — test with GBoard CJK input before committing.
5. `TrackedRange` must never be stored; `getSpanStyles` returns raw, un-normalized ranges.
6. Expect API churn at 1.13 (deprecations land there) — pin `1.12.1` exactly and re-read the release notes before bumping.

### If the decision is "keep (c)"

That is defensible and is the *lowest-risk* choice for this codebase: it is already built, `SpannableBridge` already solves the `content_json` round-trip (including the `ImageSpan` `src/w/h` payload), `ParagraphStyle` spans are natively supported by `Spannable` (which — per the project's own `SpannableBridge` KDoc — is *better* than iOS's `NSAttributedString`, where custom attributes are lost from the second typed character), and all 430 tests stay valid. The cost is that it permanently diverges from Google's direction (the value-based Compose overloads are deprecated as of 1.13) and that no Compose-side improvement will ever land in it.

**Suggested sequencing:** implement the pure-logic `ContentPart/TextRun` ⇄ `Range<SpanStyle>`/`Range<ParagraphStyle>` **normalizer + bridge** first, as a standalone tested module with no UI. If it round-trips the golden JSON files in `app/src/test/resources/golden/` and survives an on-device IME/composing check against `BasicTextField(state)` in a spike screen, adopt (a) for a *text-only* editor and render images as separate blocks. Otherwise keep (c). Because the bridge is UI-independent, that decision can be deferred without wasting the work.

---

## 6. Explicitly NOT verified / could not verify

1. **`issuetracker.google.com` is unreachable from this machine** (curl and `web_fetch` both time out). Issue titles/IDs quoted in §4.3 are search-result titles only — **except** where the release notes themselves cite the bug ID (b/389978748, b/417991824, b/494340211, b/552879150, b/556234973), which *is* verified.
2. **`android-developers.googleblog.com` and `medium.com` are unreachable** — the official blog post *"Effective state management for TextField in Compose"* was seen **only as a hyperlink** inside Google's own migration doc, never as content. **No blog post about rich-text editing in Compose was located at all.**
3. **m3.material.io** serves a JS-only shell; any Material 3 "text format bar" / rich-text guidance could **neither be confirmed nor refuted**.
4. **IME composing-region styling behaviour** — only `internal` API found; not verified either way.
5. **Whether the styled-text flag ever shipped `false`**, or when it becomes permanent — not verified (only `TODO: Remove this flag once it has soaked (b/494340211)`).
6. **No on-device spike was run** — all runtime-behaviour claims come from KDoc/source/`javap`, not execution.
7. `developer.android.com` direct fetches timed out; docs were read via the `developer.android.google.cn` locale mirror. Release-note text is quoted in English from the `?hl=en` variant.

---

## 7. Reproducing this investigation

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd /Users/cov/JustGDiary && ./gradlew :app:dependencies --configuration debugRuntimeClasspath
ls ~/.gradle/caches/modules-2/files-2.1/androidx.compose.foundation/foundation-android/1.12.1/   # + ui/, ui-text/, material3/
mkdir -p /tmp/rt/c && cd /tmp/rt/c && unzip -o <the>.aar classes.jar
javap -classpath classes.jar androidx.compose.foundation.text.input.{TextFieldBuffer,TextFieldState,TextFieldTextStyles,TextFieldStateKt}
javap -classpath classes.jar androidx.compose.foundation.text.BasicTextFieldKt
javap -classpath classes.jar androidx.compose.ui.text.{TextStyle,ParagraphStyle,SpanStyle,AnnotatedString}   # TextStyle implements NOTHING
unzip -o foundation-android-1.12.1-sources.jar -d fsrc          # paths are commonMain/androidx/...
unzip -o foundation-1.12.1-samples-sources.jar  -d fsamp        # BasicTextFieldSamples.kt
grep -rn "isBasicTextFieldStyledTextEnabled" fsrc/commonMain
grep -rn "toAnnotatedString\|changeSpanStyle" fsrc utsrc        # -> no such public API
```

---

## Sources

- [Compose Foundation release notes](https://developer.android.com/jetpack/androidx/releases/compose-foundation) — versions, dates, every quoted release-note sentence
- [Compose UI release notes](https://developer.android.com/jetpack/androidx/releases/compose-ui) — `AnnotatedString.Annotation.Saver` public (1.13.0-alpha01)
- Docs: [migrate-state-based](https://developer.android.com/develop/ui/compose/text/migrate-state-based) · [user-input](https://developer.android.com/develop/ui/compose/text/user-input) · [style-text](https://developer.android.com/develop/ui/compose/text/style-text) · [style-paragraph](https://developer.android.com/develop/ui/compose/text/style-paragraph)
- API refs: [TextFieldTextStyles](https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/input/TextFieldTextStyles) · [TrackedRange](https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/input/TrackedRange) · [TextFieldBuffer](https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/input/TextFieldBuffer) · [TextFieldState](https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/input/TextFieldState)
- [androidx `BasicTextFieldSamples.kt` (androidx-main)](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/foundation/foundation/samples/src/main/java/androidx/compose/foundation/samples/BasicTextFieldSamples.kt) · [compose-samples](https://github.com/android/compose-samples) · [nowinandroid](https://github.com/android/nowinandroid) · [user-interface-samples](https://github.com/android/user-interface-samples)
- [Material 3 text fields](https://m3.material.io/components/text-fields/overview) (JS-only; unverifiable) · issue [b/389978748](https://issuetracker.google.com/issues/389978748) (unreachable) · blog [Effective state management for TextField in Compose](https://medium.com/androiddevelopers/effective-state-management-for-textfield-in-compose-d6e5b070fbe5) (link verified, content not fetched)

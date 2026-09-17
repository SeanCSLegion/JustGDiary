# JustADiary 富文本编辑器 → Android(Kotlin) 迁移技术报告

> 只读分析，未修改 `/Users/cov/JustADiary` 下任何文件。
> 关键结论均附 `文件:行号`。源码基线：`RichTextEngine.swift` 1024 行 / `RichTextView.swift` 337 行 / `ContentPart.swift` 242 行 / `DiaryPageView.swift` 499 行 / `DiaryViewModel.swift` 534 行 / `MediaViews.swift` 180 行 / `MediaStore.swift` 123 行 / `ContentFormatTests.swift` 167 行。

---

## 0. 架构总览（先读这一节）

iOS 编辑器是**教科书式的「语义模型 + UIKit 桥接」双层结构**，这是本次迁移最重要的结论：跨平台要复刻的是**下层的纯数据模型**，而不是上层 UIKit 代码。

```
SwiftUI 层        DiaryPageView / FontToolbar / DiaryPartsView / DiaryImageView
                  ↓ 单向：controller 方法调用 + formatTick 观察
控制器层          RichEditorController(@Observable)   ← 无持久化，全部基于 UITextView
                  ↓
编解码层          PartsCodec  (NSAttributedString ⇄ [ContentPart])   ← 纯函数，无状态
                  ↓
持久化模型        ContentPart / TextRun / ContentDocument / ContentFlatten   ← 纯 Foundation
                  ↓
桥接层            RichTextView(UIViewRepresentable) → PlaceholderTextView(UITextView)
```

- **纯模型层（可直接 Kotlin 翻译）**：`ContentPart.swift:11-242` 全部、`RichTextEngine.swift:19-184`（`EditorBlockStyle` / `EditorDesignSize` / `EditorFont`）、`RichTextEngine.swift:802-1023`（`PartsCodec`）、`RichTextEngine.swift:747-800`（`AttachmentPayload` / `PayloadAttachment` / `MarkerAttachment` 的**数据部分**）。
- **真 UIKit 层（必须重写）**：`RichEditorController`（`RichTextEngine.swift:186-745`，全部方法直接操作 `UITextView.textStorage`）、`PlaceholderTextView`、`RichTextView.Coordinator`（`RichTextView.swift:143-180`）、`ReadTextView`（`ReadTextView.swift`，整文件）。
- **注意一个容易误判的点**：`RichTextEngine.swift` 虽然 1024 行，但**不是纯文本模型层**——它 `import UIKit`（`:2`），文件内混有三层：纯枚举（`:19-184`）、UIViewController 级控制器（`:186-745`）、纯编解码（`:747-1023`）。迁移时必须**按类型切分**，不能整文件搬运。

---

## 1. `content_json` 格式完整规范

### 1.1 v2 信封结构（`ContentDocument`）

定义在 `ContentPart.swift:153-164`：

```swift
struct ContentDocument: Codable, Equatable {
    static let currentVersion = 2      // :155
    var v: Int                          // :157
    var parts: [ContentPart]            // :158
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `v` | Int | 是 | 格式版本。唯一合法值 `2`（`ContentPart.swift:155`）。v1 无此字段 |
| `parts` | `[ContentPart]` | 是 | 段落数组，**顺序即正文顺序**，无 id / 无层级 |

**编码器配置**（`ContentPart.swift:198-205`）——这是格式契约的一部分，Android 端必须逐字节对齐才能保证备份互导：

```swift
encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]   // :202
```

- `.sortedKeys`：**键按字典序输出**（不是声明序），保证同一份内容在任何设备上字节一致。
- `.withoutEscapingSlashes`：`images/a.jpg` 而不是 `images\/a.jpg`（`:201` 注释明确说明）。
- 编码失败兜底返回 `"{}"`（`:203-204`）；`v` 由 `init(parts:)` 自动写入（`:160-163`），**没有 decode 侧的版本校验**，`v` 只在编码时被写入。

### 1.2 `ContentPart` 字段级定义（`ContentPart.swift:61-115`）

| 字段 | 类型 | 出现于哪种 style | 说明 |
|---|---|---|---|
| `style` | String | 全部 | 段落语义类型，取值见 1.3。**唯一决定字号**（`:117-122` 注释） |
| `text` | String? | body/title/heading/quote | 纯文本紧凑形式。**编辑器不写**，仅为手写 / 导入内容保留（`:63-67`）。解析时 `runs` 为空才读 `text`（`RichTextEngine.swift:874-875`） |
| `runs` | `[TextRun]?` | body/title/heading/quote | 行内样式分段。**编辑器只写这个** |
| `align` | String? | 任意文本段落 | 仅一个合法值 `"center"`；缺省 / `nil` = 左对齐（`RichTextEngine.swift:873`, `:961-964`） |
| `items` | `[String]?` | list / todo | 列表项文本数组 |
| `done` | `[Bool]?` | todo | 与 `items` **按下标一一对应**；缺失时按全 `false` 处理（`RichTextEngine.swift:814`） |
| `src` | String? | image | 图片相对路径，规范形式 `images/<basename>` |
| `w` / `h` | Double? | image | **存储宽高（pt）**，用于算渲染比例；编辑插入时写入的是计算出的显示尺寸 |
| `type` | — | v1 only | 同 `style` 的旧键，见 1.5 |

**`items`/`done` 的双层语义**：一个 `ContentPart` 承载**连续**的多条列表项。`appendList`（`RichTextEngine.swift:1008-1022`）只在 `parts.last` 同类型时才追加，否则新开一个 part：

```swift
if let last = parts.last, last.style == ContentPartStyle.todo, done != nil { ...append... }
else if let last = parts.last, last.style == ContentPartStyle.list, done == nil { ...append... }
else { parts.append(ContentPart(style: type, items: [item], done: done.map { [$0] })) }
```

所以 `[{list,items:["甲","乙"]}, {body,...}, {list,items:["丙"]}]` 是**三段**，编辑器不会跨正文合并。测试断言了这点：`ContentFormatTests.swift:151-157`（"consecutive list items stay one part"）。

### 1.3 `style` 取值全集（`ContentPartStyle`，`ContentPart.swift:123-145`）

| 值 | 语义 | 编辑态字号来源 | 阅读端渲染 |
|---|---|---|---|
| `"title"` | 大标题 | `EditorBlockStyle.title` = 28 | `PartsCodec.appendLine` + Title1 字号 |
| `"heading"` | 小标题 | `.heading` = 22 | Title2 |
| `"body"` | 正文 | `.body` = 17 | Body |
| `"quote"` | 引用 | `.quote` = 15 | Body 字号 + 底色 + 斜体 |
| `"list"` | 无序列表 | 绘制用 body 属性（`EditorBlockStyle(partStyle:)` default → `.body`，`:72-79`） | 项目符号 attachment |
| `"todo"` | 待办列表 | 同 body | 勾选框 attachment |
| `"image"` | 图片 | 无字体 | `NSTextAttachment` / 独立 `DiaryImageView` |

**关键解耦**：`list` / `todo` / `image` 有独立的持久化名，但**绘制时全部套用 body 属性**。`ContentFormatTests.swift:111-119` 明确断言：

```swift
for other in [ContentPartStyle.list, ContentPartStyle.todo, ContentPartStyle.image, "monospaced"] {
    XCTAssertEqual(EditorBlockStyle(partStyle: other), .body)
}
```

最后一项 `"monospaced"` 暗含重要契约：**未知 style 值必须降级为 body，不得抛错**（`editor-typography.md:147` 也说明等宽样式已规划但未实现）。

### 1.4 `TextRun` 字段级定义（`ContentPart.swift:11-57`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `text` | String | 必填（`:37` `try c.decode`，缺失即 decode 失败） |
| `bold` | Bool? | 仅 `true` 时存在 |
| `italic` | Bool? | 同上 |
| `strike` | Bool? | 同上 → `NSAttributedString.Key.strikethroughStyle = 1` |
| `underline` | Bool? | 同上 → `.underlineStyle = 1` |

三项编码/解码契约：

1. **只写 `true`，`false` 视为 nil**（`:44-52`）：
   ```swift
   if bold == true { try c.encode(true, forKey: .bold) }   // 注意：不是 c.encode(bold)
   ```
2. **解码时 `false` 归一成 nil**（`:54-56`）：`try c.decodeIfPresent(Bool.self, forKey: key) == true ? true : nil`。测试 `ContentFormatTests.swift:78`：`XCTAssertNil(parts[0].runs?.first?.bold, "a legacy false reads as 'not set'")`。
3. **未知键静默忽略**——这是 v1 `size` 能被丢弃的实现机制。`TextRun` 用显式 `CodingKeys`（`:18-20`，只有 5 个键），`Codable` 合成实现**不会**因多余键失败。

> ⚠️ **`TextRun` 里没有 `size`**，且永远不会有（`:5-9` 注释）。字号唯一来源是所属 block 的 `style`。

### 1.5 v1 兼容解码规则（三条独立机制）

v1 是**裸数组**，与 v2 信封不同构，所以 `parseContent` 是**两级降级**（`ContentPart.swift:185-194`）：

```swift
static func parseContent(_ json: String) -> [ContentPart] {
    guard !json.isEmpty, let data = json.data(using: .utf8) else { return [] }   // :186
    let decoder = JSONDecoder()
    if let document = try? decoder.decode(ContentDocument.self, from: data) {     // :188 先试 v2
        return document.parts
    }
    return (try? decoder.decode([ContentPart].self, from: data)) ?? []            // :193 再试 v1 数组
}
```

**机制 B — `type` → `style` 键映射**（`ContentPart.swift:99-105`）：

```swift
let modern = try c.decodeIfPresent(String.self, forKey: .style)
let legacy = modern == nil
    ? try decoder.container(keyedBy: LegacyCodingKeys.self).decodeIfPresent(String.self, forKey: .type)
    : nil
style = ContentPartStyle.normalized(modern ?? legacy ?? ContentPartStyle.body)
```

注意：**只有 `style` 缺失时才尝试 `type`**，且**再缺失时默认为 `body`**（不会失败）。

**机制 C — HTML 名归一**（`ContentPart.swift:132-144`）：

```swift
private static let legacyNames: [String: String] = [
    "h1": title, "h2": heading, "p": body, "ul": list, "img": image
]
static func normalized(_ raw: String) -> String { legacyNames[raw] ?? raw }
```

映射表**没有** `quote`/`todo` 条目 → v1 的 `"quote"` 与 `"todo"` 原样通过（因为它们恰好同名）。未知值原样保留（不抛错）。

**机制 D — v1 每 run 的 `size` 被丢弃**：靠 1.4 第 3 点（未知键忽略）。`ContentFormatTests.swift:55` 与 `:164` 两处断言落库 JSON 不含 `"size"`。

**v1 升级唯一入口**（`ContentPart.swift:223-231`）：

```swift
static func normalizeContent(_ json: String) -> String {
    var parts = ContentFlatten.parseContent(json)
    for i in parts.indices { if let src = parts[i].src { parts[i].src = normalizeSrcKey(src) } }
    return ContentFlatten.serializeContent(parts)      // 总是重编码 → 总是 v2
}
```

即 **parse + serialize 本身就是迁移**。`editor-typography.md:105-109` 说明三条调用路径（保存编辑 / 导入备份 / 启动时 `DiaryRepository.migrateContentFormat()`）都走这里，且 `SettingsStore.contentFormatVersion` 保证只跑一次；迁移只改 `content_json`，不动 `search_text`，因此 FTS 索引不受影响。

**路径归一**（`ContentPart.swift:208-215`）：

```swift
static func normalizeSrcKey(_ src: String) -> String {
    if src.hasPrefix("images/") { return src }
    let basename = (src as NSString).lastPathComponent
    return "images/" + basename
}
```

`"a.jpg"` → `"images/a.jpg"`（`ContentFormatTests.swift:88-90` 断言）。`resolveImagePath`（`:233-237`）= `Documents/` + key。

**容错边界**（`ContentFormatTests.swift:102-107`）：`""` / `"[]"` / `"null"` / `"{"` 全部解码为 `[]`，**永不抛错**。Android 端必须同样宽容，否则一个损坏的 block 会让整篇日记无法打开。

### 1.6 真实 JSON 示例

**(A) 编辑器实际写出的 v2**（按 `sortedKeys` 排序，等价于测试 fixture `ContentFormatTests.swift:14-26`）：

```json
{
  "parts": [
    {"runs":[{"text":"标题"}],"style":"title"},
    {"runs":[{"text":"小标题"}],"style":"heading"},
    {"align":"center","runs":[{"text":"正文"},{"bold":true,"text":"加粗"}],"style":"body"},
    {"runs":[{"italic":true,"text":"引用"}],"style":"quote"},
    {"items":["甲","乙"],"style":"list"},
    {"done":[true,false],"items":["做","没做"],"style":"todo"},
    {"h":200,"src":"images/a.jpg","style":"image","w":300}
  ],
  "v": 2
}
```

> 键序依据：`sortedKeys` 是**字典序**，故 `align` < `runs` < `style`；`paragraphs` 内 `done` < `h` < `items` < `src` < `style` < `w`。顶层 `parts` < `v`。图标 `text`/`bold` 同 run 时 `bold` < `text`。**Android 的 JSON 序列化器必须能配置同样的排序 + 不转义斜杠**，否则备份文件 diff 不稳定（功能上不影响解析，但会破坏「字节级一致」的备份校验假设）。

**(B) v1 原始形态**（`ContentFormatTests.swift:63-71` 逐字）：

```json
[{"type":"h1","runs":[{"text":"标题","size":22,"bold":false}]},
 {"type":"h2","runs":[{"text":"小标题","size":18}]},
 {"type":"p","runs":[{"text":"正文","size":15,"bold":true}],"align":"center"},
 {"type":"quote","text":"引用"},
 {"type":"ul","items":["甲"]},
 {"type":"todo","items":["做"],"done":[true]},
 {"type":"img","src":"a.jpg","w":10,"h":20}]
```

注意 v1 的 `size` 值（22/18/15）是**旧梯级** `{13,15,18,22}`，与 v2 新梯级 `{28,22,17,15}` 在 15 处重叠——这正是 `editor-typography.md:45-46` 说明的「无法区分应用自己写的 15 和导入文档里用户自定义的 15」，也是必须靠 `style` 而非字号判定块类型的根因。

**(C) 图片在编辑器文本存储中的真实状态**：图片**不是** JSON 里的文本，而是 `NSTextAttachment` + U+FFFC（attachment 字符占 1 个 UTF-16 位置）。`parts(from:)` 在扫描时遇到 `payload.kind == "image"` 就**跳过该字符**并产出 `ContentPart(style: "image", src:, w:, h:)`（`RichTextEngine.swift:972-978`）：

```swift
if let payload = (attrs[.attachment] as? PayloadAttachment)?.payload, payload.kind == "image" {
    parts.append(ContentPart(style: ContentPartStyle.image,
                             src: ImagePathUtil.normalizeSrcKey(payload.src),
                             w: Double(payload.w), h: Double(payload.h)))
    cursor = effectiveEnd; continue
}
```

项目符号 / 勾选框同理（`:937-946`），且**兼容纯文本前缀兜底**（`:926-933`）：`"• "` → bullet、`"☐ "` / `"☑ "` → todo（后者带 done 状态）。这是导入纯文本内容时的降级路径。

**(D) `flattenPart`（搜索/预览用的纯文本投影，`ContentPart.swift:167-178`）**：优先 `runs` 拼接 → 再 `text` → 再 `items` 拼接 → 否则 `""`。**图片产出空串**。搜索索引（`DiaryViewModel.computeHits` `:85-96`）和 `:ui-test-editor-state` 探针（`DiaryPageView.swift:89-95`）都基于它。

---

## 2. 编辑器往返不变量（round-trip invariants）

不变量在 `docs/design-system.md:80-143`（第三节，标注「最重要」）与 `docs/editor-typography.md` 中被显式声明，并由 `ContentFormatTests` + `EditorTypeSizeUITests` 双重守护。

### 2.1 被源码/测试显式断言的不变量

| # | 不变量 | 断言位置 |
|---|---|---|
| I1 | **落库 JSON 是 v2 信封**，`v == 2` | `ContentFormatTests.swift:53` |
| I2 | **落库 JSON 绝不含 `"size"`** | `ContentFormatTests.swift:55`, `:164-165` |
| I3 | **落库 JSON 绝不含 `"type"`**（v1 键） | `ContentFormatTests.swift:56` |
| I4 | **serialize → parse 幂等**：`parseContent(json) == parts` | `ContentFormatTests.swift:57` |
| I5 | **未开启的行内样式不写成 `false`**，且每 flag 只出现一次 | `ContentFormatTests.swift:93-100` |
| I6 | **全部 12 档 `DynamicTypeSize` 下** load → parse 保持 `style` / `align` / 文本 / `bold` / `italic` / `underline` / `strike` | `ContentFormatTests.swift:124-144`（`for typeSize in DynamicTypeSize.allCases`） |
| I7 | **连续 list / todo 合并为单个 part**，`done` 状态不丢 | `ContentFormatTests.swift:146-158` |
| I8 | **`EditorBlockStyle` ↔ 持久化名双向一一对应**，未知值 → `.body` | `ContentFormatTests.swift:111-120` |
| I9 | v1 输入经 `normalizeContent` 后必为 v2、无 `size`、路径已归一 | `ContentFormatTests.swift:85-90` |
| I10 | 空 / 损坏 JSON → `[]`，不抛错 | `ContentFormatTests.swift:102-107` |
| I11 | 最大辅助功能字号下建立 body/title/heading/quote → 保存 → **重新打开已保存块**（非新建）→ 再保存，三轮样式与文本不变 | `editor-typography.md:127-129`（`testBlockStylesSurviveReSaveAtLargestTextSize`） |
| I12 | 「放弃修改」恢复到保存前内容 | `editor-typography.md:131-132` |
| I13 | 空编辑区高度：默认 160pt，AX5 约 240pt | `editor-typography.md:133-134` |

### 2.2 哪些操作**会**改写已有内容

| 操作 | 改写范围 | 代码 |
|---|---|---|
| 改段落样式 `applyBlockStyle` | 选区**覆盖的整段**（含换行符），**并删除段首 bullet/todo attachment** | `RichTextEngine.swift:325-340`；删标记在 `:369-373` |
| 开列表 / 待办 `toggleMarker` | 先 `restyle(..., to: .body, cancelCenter: true)` 抹掉标题/引用/居中，再插入 attachment | `:519-556`（`:538-539`） |
| 引用 `toggleQuote` | 整段重刷字体 + 底色，强制左对齐 | `:569-583`, `restyle` `:390-402` |
| 居中 `toggleCenter` | 只改 `paragraphStyle.alignment`，**保留其他属性** | `:434-481` |
| 加粗 / 斜体 | 选区按 `enumerateAttribute(.font)` 逐 run 改 symbolicTraits | `:237-271` |
| 删除线 / 下划线 | 选区按 `enumerateAttribute` 逐 run toggle 0/1 | `:296-312` |
| 插入图片 | 在光标处插入 attachment + 一个 `\n`，光标移到其后 | `:675-690` |
| 加载 / 重新渲染 `load` / `reapplyTypeSize` | **整个 textStorage 重建** | `:697-705`, `:710-723` |
| 「放弃修改」`discardEditing` | `loadParts = editingOriginalParts` 后整体重载 | `DiaryViewModel.swift:205-209` |

### 2.3 哪些操作**绝不**改写已有内容（这是设计的核心）

**N1 — 改字号梯级/改系统文字大小，绝不改写任何已存日记。**
根因是「块类型与字号解耦」（`RichTextEngine.swift:5-18` 顶部注释 + `design-system.md:97-111`）：

- 块类型**显式存储**在 `.diaryBlockStyle` run 属性（`RichTextEngine.swift:117-120`），落库为 `ContentPart.style`；
- `parts(from:)` **优先读块类型**（`:952-960`），字号推断**仅**作为无块类型时的兜底；
- `reapplyTypeSize` 用**旧** `typeSize` 解析出设计字号、再用**新** `typeSize` 重建（`:710-723`），所以只是重绘，不落库。

**N2 — 绘制字号绝不落库。** `parts(from:)` **刻意不读**字号（`:991-994` 注释「The drawn size is deliberately *not* read here」）。一旦读了，AX5 下 17pt 正文被画成 25.5pt，反推就会把段落判成标题（`design-system.md:116-120`）。

**N3 — 兜底反解**必须精确**。** `.diaryDesignSize` 缺失时（UIKit 从光标处重同步 `typingAttributes` 会丢自定义键，**从第二个字符起**丢失，`:161-168`），先拿绘制值与编辑器自产字号集做**精确匹配**（`EditorDesignSize.authored`，容差 `< 0.01`），只有导入内容才走除法（`:173-183`）：

```swift
for design in EditorDesignSize.authored
where abs(DynamicTypeMetrics.scaled(design, for: typeSize) - point) < 0.01 { return design }
return point / DynamicTypeMetrics.multiplier(for: point, typeSize: typeSize)   // :182
```

近似反解的后果有实测记录：旧梯级下 `15 → 18.75`，而 `18.75 ≥ 18`（旧 h2 阈值），再存一次就真的把段落变成标题（`design-system.md:122-128`）。**这是移植时最容易被忽略、也最容易造成静默数据损坏的一条。**

**N4 — 行距 / 段后距不落库**，读取时由块类型重新推导（`RichTextEngine.swift:44-53` 注释「Rendered only: like alignment it is derived from the block type on load rather than persisted」）。

**N5 — 尾随空行被丢弃**。`parts(from:)` 遇到 trim 后为空的行 `continue`（`:924`），因此 `"...正文\n\n"` 和 `"...正文\n"` 解析结果相同——**段落间的空行不参与持久化**，文件末尾换行也不会产生空 part。

**N6 — 段落终止换行符继承块属性**（`:900-909`），使得「段尾回车继续同一样式」；但它本身在解析时被跳过（N5），所以不产生额外 part。

**N7 — 解析是"只读 + 保守降级"**：无块类型、无底色、无字号 → 直接 `.body`（`:958-959`），不猜测。

### 2.4 已知的「改写副作用」（迁移时必须保留或明确修掉）

1. **删标记改变后续 NSRange**：`applyBlockStyle` 因此必须**从后往前**遍历段落（`:330-333` 注释「Back to front: restyling a paragraph can drop its list/todo marker, which shifts every later range by one.」）。Android 端若用 span 数组重算，顺序约定同样成立。
2. **空行操作会牵连相邻行**：`toggleCenter` / `toggleMarker` / `paragraphRange` 有大量特判（`:445-459`, `:488-492`, `:606-651`），核心意图是「当前行的空段只影响后续输入，不影响上一行」。`paragraphRange(in:around:)` 在光标位于尾随换行之后时返回**零宽范围**（`:629-631`），在空段时也返回零宽（`:641-643`）。这些特判是 iOS 上多轮 bug 修复的沉淀，Android 重写时必须逐条对照。
3. **`apply` 每次全量替换 textStorage**（`:737-744`）：`NSMutableAttributedString(attributedString: text)` → 改 → `text.replaceCharacters(in: 全范围, with: attributed)`。这**同时摧毁撤销栈**并让每次格式操作是 O(n)。
4. **居中与列表/引用/待办互斥**：`toggleCenter` 开头直接 `return`（`:436-438`），工具栏也 disable（`RichTextView.swift:279-282`）。
5. **居中空段禁止连续回车**：`shouldBlockNewline`（`:505-509`）+ delegate `shouldChangeTextIn` 返回 `false`（`RichTextView.swift:153-164`）。

### 2.5 撤销：**没有**撤销栈

全仓库无 `undoManager` / `NSUndoManager` / `allowsUndo`（grep 仅命中注释）。`DiaryViewModel.swift:190-195` 明确写着「there is no undo stack to get them back」。替代方案只有：

- 「放弃修改」按钮 = `editingOriginalParts` 快照整体回滚（`DiaryViewModel.swift:196-209`）；
- 二次确认（`:196-203`），因为历史上这个按钮是「Redo」图标 + 单击静默丢弃（`editor-typography.md:36` E3）。

→ **Android 端有免费升级空间**（见 §8）。

---

## 3. `RichTextEngine` 完整 API 与职责

### 3.1 定位判定：**混合层，不是纯模型层**

`RichTextEngine.swift:1-3` 同时 `import SwiftUI` / `import UIKit` / `import Observation`。但按类型切分后：

| 类型 | 行号 | 是否依赖 UIKit | 迁移性质 |
|---|---|---|---|
| `EditorBlockStyle` | `:19-89` | 仅 `CGFloat` | ✅ 纯模型，直译 |
| `EditorDesignSize` | `:91-109` | 仅 `CGFloat` | ✅ 纯模型，直译 |
| `NSAttributedString.Key` 扩展（`diaryDesignSize` / `diaryBlockStyle`） | `:111-121` | UIKit 键空间 | ⚠️ 概念可移植（Android 用自定义 `Span` 类） |
| `EditorFont` | `:123-184` | `UIFont` / `UIFont.Weight` / `CGAffineTransform` | ⚠️ 依赖 DynamicTypeMetrics（见 §5） |
| `RichEditorController` | `:186-745` | **重度** `UITextView` / `NSTextStorage` | ❌ 必须重写 |
| `AttachmentPayload` | `:747-761` | `CGFloat` | ✅ 纯模型（对应 Kotlin `data class`） |
| `PayloadAttachment` | `:763-774` | `NSTextAttachment` 子类 | ❌ 重写（Android 用 `ImageSpan` / `ReplacementSpan`） |
| `MarkerAttachment` | `:776-800` | `UIImage(systemName:)` / `SymbolConfiguration` | ❌ 重写（Android 用 `ImageSpan` + vector drawable） |
| `PartsCodec` | `:802-1023` | `NSAttributedString` / `NSParagraphStyle` / `NSString` | ⚠️ 逻辑直译，类型替换 |

### 3.2 `RichEditorController` 逐个公开成员（附行号）

**存储属性 / 观察状态**

| 成员 | 行号 | 类型 | 职责 |
|---|---|---|---|
| `textView` | `:188` | `weak var UITextView?` | 唯一的 UI 引用；控制器**不持有** textView（避免保留环）。所有操作前 `guard let tv = textView else { return }` |
| `onFormatChange` | `:189` | `(() -> Void)?` | 由 `RichTextView.makeUIView` 注入（`RichTextView.swift:102-104`），回调到 Coordinator 刷新 placeholder |
| `imageMaxWidth` | `:190` | `CGFloat?` | 由 SwiftUI 布局回写（`RichTextView.swift:125-126`），用于图片显示宽 |
| `formatTick` | `:191` | `private(set) var Int` | **观察触发点**。`@Observable` 使 `FontToolbar.body` 依赖它（`RichTextView.swift:335` `.onChange(of: controller.formatTick)`），从而在每次编辑/移动光标/格式变更后重算按钮激活态 |
| `dynamicTypeSize` | `:196` | `DynamicTypeSize = .large` | 用户系统字号档位。**编辑器无法自己读 SwiftUI environment**（`:193-195` 注释），由 `RichTextView` 推入（`RichTextView.swift:98`） |

**生命周期 / 输入属性**

| 方法 | 行号 | 签名 | 说明 |
|---|---|---|---|
| `typingAttributes(for:)` | `:200-209` | `(EditorBlockStyle) -> [NSAttributedString.Key: Any]` | 下一字符的完整属性：字体 + `.diaryDesignSize` + `.diaryBlockStyle` + `foregroundColor` + `paragraphStyle`(lineSpacing/paragraphSpacing) + 引用底色。**块切换后的重置模板** |
| `baseTypingAttributes()` | `:211-213` | `() -> [...]` | = `typingAttributes(for: .body)` |
| `refreshTypingAttributes()` | `:215-218` | `() -> Void` | 重置 typing 属性并 `notifyFormatChange()` |
| `notifyFormatChange()` | `:222-225` | `() -> Void` | `formatTick += 1; onFormatChange?()`。**所有变更路径的公共出口** |

**字符样式（inline）**

| 方法 | 行号 | 空选区行为 |
|---|---|---|
| `toggleBold()` / `toggleItalic()` | `:229-235` | 走 `toggleFontStyle` |
| `toggleFontStyle(_:)`（private） | `:237-271` | 有选区：遍历 `.font` run，增删 symbolic trait 后重建 `UIFont(descriptor:size:)`（`:250-253`）；**空选区：只改 `typingAttributes[.font]`**（`:257-268`） |
| `toggleStrike()` / `toggleUnderline()` | `:288-294` | 走 `toggleLineStyle` |
| `toggleLineStyle(key:)`（private） | `:296-312` | 有选区：`enumerateAttribute(key)` 逐 run 写 `active ? 0 : 1`（`:300-306`）；空选区改 typing（`:308-310`） |
| `applyItalicSlant(_:trait:adding:)`（private static） | `:277-286` | CJK 无真斜体轮廓 → 对 descriptor 加 `CGAffineTransform(a:1,b:0,c:0.24,d:1,...)` 斜切矩阵（`:281-282`）。**Android 需等价处理**：`Paint.setTextSkewX(-0.24f)` 或 `Typeface.create(..., ITALIC)` 后配合 `textSkewX` |

**段落样式**

| 方法 | 行号 | 语义 |
|---|---|---|
| `applyBlockStyle(_:)` | `:325-340` | 对选区触及的**所有段落**（`paragraphRanges(covering:)`）逐个 `restyle`；**从后往前**（`:332`）；最后重置 typing（`:339`） |
| `currentBlockStyle()` | `:343-362` | 工具栏菜单的当前态。**探测段落起点而非光标处**（`:350-354` 注释：UIKit 从第二个字符起丢自定义键，光标处字符可能已无键）。降级链：`.diaryBlockStyle` → 非透明底色 → 字号推断 |
| `restyle(_:range:to:cancelCenter:)`（private） | `:365-403` | 单段核心。① 删段首 bullet/todo attachment（`:369-373`）；② 遍历 `.font` 保留 bold/italic 重建（`:376-389`）；③ 重刷 `.backgroundColor`（`:390-393`）；④ 重刷 `.paragraphStyle` 并可选强制左对齐（`:394-402`） |
| `paragraphRanges(covering:)`（private） | `:409-432` | 手写按 `\n`(0x0A) 切行；空选区用 `location >= start && location <= lineEnd`，非空用 `NSIntersectionRange`（`:422-427`）。**不使用 NSString 的 paragraph API** |
| `toggleCenter()` | `:434-481` | 与 list/quote/todo 互斥（`:436-438`）；空行只改 typing（`:445-459`）；否则改整段 `paragraphStyle.alignment`（`:461-469`），并同步 typing（`:470-480`） |
| `isCenterActive()` | `:483-500` | 空行 / 空文档读 typing（`:484-486`, `:490-492`），否则读段落首字符的 `paragraphStyle.alignment` |
| `shouldBlockNewline(at:)` | `:505-509` | 空段 + 居中 → `true`（阻止回车级联空行） |
| `toggleList()` / `toggleTodo()` | `:511-517` | 走 `toggleMarker(kind:)` |
| `toggleMarker(kind:)`（private） | `:519-556` | 定位行首（`:525`）→ 读段首 attachment payload 判是否已标记（`:527-530`）→ 未标记则先 `restyle(to: .body, cancelCenter: true)`（`:538-539`）→ 标记存在则删字符（`:541-547`）否则插 `MarkerAttachment`（`:548-551`）→ 重置 typing 为 body（`:555`） |
| `isListActive()` / `isTodoActive()` | `:558-560` | 走 `currentMarkerKind()` |
| `currentMarkerKind()`（private） | `:562-567` | 读段落首字符 `.attachment` 的 `payload.kind` |
| `toggleQuote()` | `:569-583` | 依据 `isQuoteActive()` 在 `.quote` / `.body` 间翻转，整段 `restyle(cancelCenter: true)` |
| `isQuoteActive()` | `:585-599` | 空文档 / 空行读 typing 底色（`:587-593`），否则读段首 `.backgroundColor` 是否非透明 |
| `paragraphRange(around:)`（private） | `:601-604` | 便捷转发 |
| `paragraphIsEmpty(in:location:)`（private static） | `:609-620` | 用 `NSString.getParagraphStart(_:end:contentsEnd:for:)` 判 `contentStart - paraStart == 0`；光标在尾随 `\n` 之后也算空（`:614`） |
| `paragraphRange(in:around:)`（private） | `:622-651` | 同族。**两个关键特判**：尾随换行后返回零宽（`:629-631`）；空段返回零宽（`:641-643`）。**明确不加 1**——`paraEnd` 已越过终止换行，再加会污染相邻空行（`:644-648`） |
| `activeStyles()` | `:653-673` | 返回 `(bold, italic, strike, underline)`。有选区读选区**起点**字符属性（`:655-664`），空选区读 typing（`:666-672`） |

**内容进出 / 媒体**

| 方法 | 行号 | 说明 |
|---|---|---|
| `insertImage(_:src:)` | `:675-690` | `viewWidth = bounds.width - 24`，`maxW = min(viewWidth, 343)`（`:677-678`）→ `displayH = max(40, maxW * ratio)`（`:680`）→ 建 `PayloadAttachment` + `DiaryImageStore.rounded(radius: Radius.image)`（`:681-683`）→ **尾部补一个 `\n`**（`:685`）→ 插入并把光标移到 attachment+换行之后（`:686-688`） |
| `currentParts()` | `:692-695` | `PartsCodec.parts(from: tv.textStorage, typeSize: dynamicTypeSize)`。**唯一落库前出口** |
| `load(parts:)` | `:697-705` | 整体 `setAttributedString`，typing 重置，`selectedRange = 0` |
| `reapplyTypeSize(_:)` | `:710-723` | 旧档位解析 → 记光标 → 换档位 → 重建 → **光标按 `min(caret, length)` 恢复**（`:721`） |
| `clear()` | `:725-730` | 清空并重置 typing |
| `isEmpty()` | `:732-735` | `textStorage.string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty`。**决定「放弃修改」是否弹确认**（`DiaryViewModel.swift:197`）与返回时的未保存提示（`:177`） |
| `apply(_:)`（private） | `:737-744` | **所有格式变更的统一写入路径**：复制全文 → 闭包改副本 → `replaceCharacters(in: 全文范围)` → `notifyFormatChange()` |

### 3.3 **NSRange ⇄ content_json 双向映射**

**正向（存储 → 文本，`PartsCodec.attributedString(from:imageMaxWidth:typeSize:)`，`:803-842`）**

```
[ContentPart] ──per part──┬─ list  → 每个 item 一行：MarkerAttachment("bullet") + 文本 + "\n"   (:808-811)
                          ├─ todo  → 每个 item 一行：MarkerAttachment("todo", done) + 文本 + "\n" (:812-818)
                          ├─ image → PayloadAttachment + "\n"；w = min(storedW, maxW)，h 按比例 (:819-835)
                          └─ 其他  → appendLine(part, block:) → runs 逐段 append + 属性化"\n" (:836-838)
```

- `maxW = max(60, imageMaxWidth ?? max(60, Screen.width - 76))`（`:823-824`）——**两项兜底**：外部传入的 `imageMaxWidth`（编辑态）或屏幕宽 - 76（阅读态）。图片**按存储宽高比缩放到 maxW**（`:826`），`h = storedH * w / storedW`。
- `appendLine(_ runs:...)`（`:881-910`）：**字号来自 block 而不是 run**（`:887-889` 注释 "The size comes from the block, never from the run"）；`paragraphStyle` 应用到**整行及终止换行**（`:894`, `:904-909`）。
- `appendLine(_ part:...)`（`:870-879`）：`runs` 为空且 `text` 存在时用 `TextRun(text: text)` 合成（v1 / 手写内容路径）。
- 未知 style 落入 `default` → `EditorBlockStyle(partStyle:)` → `.body`（`:836-838`）。

**反向（文本 → 存储，`PartsCodec.parts(from:typeSize:)`，`:912-1006`）**

逐行扫描（手写 `\n` 循环，`:916-923`），对每行：

1. **丢弃空白行**（`:924`）→ 不变量 N5。
2. **纯文本 marker 兜底**：`"• "`（`:926-928`）、`"☐ "` / `"☑ "`（`:930-933`）。
3. **attachment 判定**：段首 payload 为 `todo` / `bullet` → 切掉首字符作为 item（`:937-946`）。图片 attachment 在 run 循环里单独处理（`:972-978`）。
4. **块类型判定**（三级降级，`:952-960`）：`.diaryBlockStyle` → 非透明底色 = quote → 字号推断 → `.body`。
5. **对齐判定**（`:961-964`）：`paragraphStyle.alignment == .center` → `"center"`，否则 `align = nil`。
6. **run 切分**（`:965-1001`）：用 `attributes(at:effectiveRange:)` 拿连续同属性区间；只写**实际开启**的 flag（`bold ? true : nil` 等，`:995-999`）；**不读字号**（`:991-994`）。
7. `runs` 为空则丢弃该行（`:1002`）。
8. 产出 `ContentPart(style: block.partStyle, runs:, align:)`（`:1003`）；list/todo 走 `appendList` 合并（`:1008-1022`）。

> **重要实现细节**：正向用 Swift 的 `NSRange` + `NSAttributedString`，反向用 `NSString.character(at:)` 手写扫描而非 `enumerateSubstrings`/`getParagraphStart`——因为需要精确控制「attachment 占 1 个位置」「尾随换行跳过」。Android 端等价实现是 `CharSequence` + `Spanned.getSpans()` + 手写 `indexOf('\n')` 循环。

### 3.4 输入法（IME marked text）处理状况

**现状：iOS 侧完全依赖 UITextView 内建，没有任何 marked text 代码。**

- 全仓库无 `markedTextRange` / `setMarkedText` / `unmarkText` 使用。
- 唯一的中文相关处理是**斜体斜切**（`:273-286`）和 **CJK 墨迹比例**用于导出（`ImageShareService.swift:122-130`，CJK 0.87 / ASCII 0.75 / 其他 0.98）。
- 因此**没有「拼音候选期间格式属性被破坏」的显式防护**——这正是 `.diaryDesignSize` 会在第二个字符起丢失（`:161-168`）的同类问题。iOS 靠「起点探测 + 精确兜底」缓解（§2.3 N3）。

→ **Android 结论**：Compose `TextFieldState.composition: TextRange?` 显式暴露 IME 组合区间；`EditText` 则有 `BaseInputConnection.setComposingText` + `Spannable` 的 composing span 自动管理。**两条路线在 IME 上都比 iOS 现状更可控**，但必须新增一条 iOS 没有的规则：**格式操作不得作用于 composition range**（否则中文选词被加粗/改样式会打断输入法）。这是净新增工作量，不是移植工作量。

### 3.5 选区与撤销

- **选区**：全程 `tv.selectedRange`（`NSRange`），读写无封装。空选区（`length == 0`）= 光标，此时所有 toggle 改为写 `typingAttributes`（未来输入），这也是「光标态工具栏按钮显示的是将输入的样子」的实现（`:653-673` 注释 "Empty caret: report what the next typed characters will look like"）。
- **撤销**：无（§2.5）。`apply` 的全量替换会清空 `UITextView` 的隐式撤销栈。**Android 可白送**（§8）。

---

## 4. UIKit 依赖点清单

### 4.1 `RichTextView` 是否 `UIViewRepresentable` 包装 `UITextView`？——是，且是双层

```swift
struct RichTextView: UIViewRepresentable {     // RichTextView.swift:72
    let controller: RichEditorController       // :73
    var placeholder: String                    // :74
    var autoFocus = false                      // :75
    var loadToken: Int = 0                     // :76  ← 命令式「重新加载」令牌
    var loadParts: [ContentPart] = []          // :77
    @Environment(\.diaryDynamicTypeSize) private var typeSize   // :79

    func makeCoordinator() -> Coordinator { Coordinator(self) }        // :81-83
    func makeUIView(context: Context) -> PlaceholderTextView { ... }   // :85-112
    func updateUIView(_ uiView: PlaceholderTextView, context: Context) // :114-141
}
```

- `PlaceholderTextView: UITextView`（`:4-70`）——因为 `UITextView` **没有原生 placeholder**，用 `UILabel` 自绘（`:9`, `:52-59`）。
- **`loadToken` 是一个命令式重新加载协议**：`DiaryViewModel` 每次 `enterWrite` / `enterEditBlock` / `discardEditing` 都 `loadToken += 1`（`DiaryViewModel.swift:124`, `:145`, `:207`），`updateUIView` 比对 `context.coordinator.lastToken` 决定 `clear()` 还是 `load(parts:)`（`RichTextView.swift:132-139`）。**Android 侧可以用声明式状态替代，这是个「可以不照搬」的设计。**

### 4.2 UIKit API 逐条清单及职责

**A. `PlaceholderTextView`（`RichTextView.swift:4-70`）**

| API | 行号 | 职责 |
|---|---|---|
| `UITextView.init(frame:textContainer:)` | `:11-16` | 基类初始化 |
| `UILabel` | `:9`, `:13-14` | 自绘 placeholder（颜色 `Theme.onSurfaceVariantUIColor()`，`numberOfLines = 0`） |
| `UITextView.font` + `UILabel.font` | `:26-28` | 手动按 `EditorDesignSize.body` 缩放（否则提示文字不随系统字号变化，`:22-24` 注释） |
| `UITextView.minimumHeight`（自定义） | `:42` | 输入区最小高：`(160 * multiplier(body, typeSize)).rounded()`（`:29`）。默认 160pt，AX5 ≈ 240pt（`editor-typography.md:37`, `:133-134`） |
| `intrinsicContentSize` override | `:44-48` | 让 SwiftUI 得到自适应高度（`width = bounds.width > 0 ? bounds.width : 340`，`height = max(minimumHeight, sizeThatFits(...))`） |
| `layoutSubviews` + `textContainerInset` | `:50-60` | 手工摆 `placeholderLabel`（`x = inset.left + 5`，宽 `bounds.width - inset.left - inset.right - 10`，高 `ceil(font.lineHeight)`）。**注释说明曾用固定 22pt 导致放大字号下提示被裁** |
| `UITextView.text` override (`didSet`) | `:62-64` | 切换 placeholder 可见性 |
| `refreshPlaceholder()` | `:66-69` | 同上，供外部调用 |

**B. `makeUIView`（`:85-112`）**

| API | 行号 | 职责 |
|---|---|---|
| `PlaceholderTextView(frame:.zero, textContainer: nil)` | `:86` | `nil` = 用默认 `NSTextContainer`/`NSLayoutManager`/`NSTextStorage` 链 |
| `backgroundColor` / `textColor` / `tintColor` | `:87`, `:92-93` | 主题色（`Theme.onSurfaceUIColor()` / `primaryUIColor()`） |
| `isScrollEnabled = false` | `:88` | **关键**：不滚动 → 靠 `intrinsicContentSize` 撑高，由外层 SwiftUI `ScrollView` 滚动（`DiaryPageView.swift:126`） |
| `textContainerInset` | `:89` | `UIEdgeInsets(top:10, left:12, bottom:10, right:12)`；**同时决定图片可用宽**（`:123-124`） |
| `textContainer.lineFragmentPadding = 0` | `:90` | 去掉默认 5pt 行内边距 |
| `UITextViewDelegate`（`context.coordinator`） | `:91` | 见下表 D |
| `keyboardDismissMode = .interactive` | `:94` | 交互式下拉收键盘 |
| `alwaysBounceVertical = false` / `showsVerticalScrollIndicator = false` | `:95-96` | 关闭滚动指示 |
| `controller.dynamicTypeSize = typeSize` | `:98` | **把 SwiftUI environment 注入控制器**（控制器读不到 environment） |
| `controller.textView = tv` | `:101` | 反向弱引用 |
| `controller.onFormatChange = { coordinator.notifyFormatChange() }` | `:102-104` | 变更回调 |
| `DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { tv.becomeFirstResponder() }` | `:106-110` | **自动聚焦**（延迟 0.25s 等视图上屏）。由 `vm.autoFocusEditor` 驱动（`DiaryPageView.swift:408`） |

**C. `updateUIView`（`:114-141`）**

| 逻辑 | 行号 | 职责 |
|---|---|---|
| `uiView.placeholder = placeholder` | `:115` | 同步提示文案（新建 / 编辑两套，`DiaryPageView.swift:407`） |
| 字号档位变化检测 | `:116-120` | `appliedTypeSize != typeSize` → `uiView.applyTypeSize(typeSize)` + `controller.reapplyTypeSize(typeSize)`。**这是系统字号变更时重绘但不落库的入口**（不变量 N1） |
| **图片宽度自适应** | `:121-131` | `width > 40` 时算 `available = max(60, width - inset.left - inset.right)`；与 `controller.imageMaxWidth` 差 > 1 就更新，**并且在 `lastToken == loadToken` 时立刻 `controller.load(parts:)` 重排图片** |
| `loadToken` 变化 | `:132-139` | 空 parts → `clear()`，否则 `load(parts:)` |
| `uiView.refreshPlaceholder()` | `:140` | 每次更新后刷新占位 |

**D. `Coordinator`（`:143-180`）— `NSObject, UITextViewDelegate`**

| Delegate / 方法 | 行号 | 职责 |
|---|---|---|
| `textView(_:shouldChangeTextIn:replacementText:)` | `:153-164` | `text == "\n"` 且 `shouldBlockNewline(at: caret)` → 返回 `false`。**唯一的输入拦截点**（居中空段防连续回车） |
| `textViewDidChange(_:)` | `:166-171` | 刷新 placeholder + `controller.notifyFormatChange()`。**注意：无内容同步逻辑**（不写回 model） |
| `textViewDidChangeSelection(_:)` | `:173-175` | 光标移动也触发 formatTick → 工具栏状态跟随光标 |
| `notifyFormatChange()` | `:177-179` | 仅刷新 placeholder |
| `lastToken` / `appliedTypeSize` | `:146-147` | 命令式同步状态 |

**E. `FontToolbar`（`RichTextView.swift:196-337`）** — `@Environment(\.fontToolbarVertical)` 私有 EnvironmentKey（`:185-194`），横屏时竖排贴右侧（`DiaryPageView.swift:22`）。玻璃效果用 `.glassEffect(.regular.interactive(true), in: RoundedRectangle(...))`（`:326-330`）+ `Radius.panel` 圆角 + 阴影。

**F. `ReadTextView`（独立的全量 UIKit 桥接，`ReadTextView.swift`）**

| API | 行号 | 职责 |
|---|---|---|
| `FittedTextView: UITextView` | `:4-10` | 只 override `intrinsicContentSize` 自适应高度 |
| `isEditable = false` / `isSelectable = false` | `:26`, `:32` | 只读渲染 |
| `UITapGestureRecognizer` + `UIGestureRecognizerDelegate` | `:35-37`, `:161-164` | 自绘点击命中：`shouldRecognizeSimultaneouslyWith` 返回 `true` |
| `closestPosition(to:)` / `offset(from:to:)` | `:143-147` | **把点击坐标转成字符偏移**（todo / 图片命中测试的桥梁） |
| `todoRanges` / `todoCallbacks` / `imageRanges` / `imageCallbacks` | `:56-59` | 重建时同步维护的命中表（`:73-104`） |
| `NSLocationInRange(offset, range)` | `:148`, `:153` | 命中判定 |
| `attributed.isEqual(to: tv.attributedText)` | `:108` | **避免无谓重设 attributedText** 的性能优化 |
| `SearchUtil.highlightSegments` + 逐段加底色 | `:113-138` | 搜索高亮。**注释说明曾硬编码 15pt medium，会重置标题字号**（`:122-125`）——现在按 run 自己的 `designSize` 解析（`:126-132`） |

**G. 其他 UIKit 触点**

| 位置 | API | 职责 |
|---|---|---|
| `DiaryPageView.swift:36`, `:100-116` | `UIResponder.keyboardWillChangeFrameNotification` + `keyboardFrameEndUserInfoKey` / `keyboardAnimationDurationUserInfoKey` / `keyboardAnimationCurveUserInfoKey` | 键盘避让：算高度 + 还原动画曲线（`curveValue` 1/2/3 → easeIn/easeOut/linear，默认 easeInOut），`withAnimation` 写 `vm.keyboardHeight` |
| `DiaryPageView.swift:26`, `:141-145`, `:169-177` | — | 格式栏底部内边距 = `keyboardHeight + 8`；滚动区补 `keyboardHeight + 160`；键盘弹起后 0.15s 自动 `scrollTo("editor-card", anchor: .center)` |
| `MediaViews.swift:104-137` | `PHPickerViewController` / `PHPickerConfiguration` / `PHPickerViewControllerDelegate` / `NSItemProvider.loadObject(ofClass: UIImage.self)` | **图片采集**：`filter = .images`，`selectionLimit = 1`，`preferredAssetRepresentationMode = .current` |
| `MediaViews.swift:13-44` | `GeometryReader` + `Image(uiImage:)` | 阅读端图片：**独立 SwiftUI 视图而非 attachment**（与编辑态不同！） |
| `MediaStore.swift:86-98` | `UIGraphicsImageRenderer` / `UIBezierPath(roundedRect:cornerRadius:)` / `draw(in:)` | 圆角合成（`Radius.image`） |
| `MediaStore.swift:105-118` | `CGImageSourceCreateWithData` / `CGImageSourceCreateThumbnailAtIndex` + `kCGImageSourceThumbnailMaxPixelSize` | **真正的降采样**（见 §7） |
| `MediaStore.swift:50-68` | `NSCache<NSString, UIImage>` | 图片缓存（`countLimit = 200`，`totalCostLimit = 150MB`，cost = `bytesPerRow * height`） |
| `DiaryViewModel.swift:512` | `UITraitCollection.current.userInterfaceStyle == .dark` | 导出时判断深浅色 |
| `RichTextEngine.swift:792-797` | `UIImage.SymbolConfiguration(pointSize:weight:)` / `UIImage(systemName:withConfiguration:)` / `withTintColor(_:renderingMode: .alwaysTemplate)` | **列表符号 / 勾选框是 SF Symbol 图片**：todo → `checkmark.square.fill` / `square`；bullet → `circle.fill`（`:786-790`），尺寸 `15 * scale`，`bounds.y = -2` |

> **Android 对应**：SF Symbol → `vector drawable`（Material Symbols `check_box`/`check_box_outline_blank`/`circle`）；`NSTextAttachment` → `ImageSpan`；`textContainerInset` → `EditText` 的 `padding` + `includeFontPadding`。

---

## 5. 段落样式与字体模型

### 5.1 语义段落字号梯级：确切数值与来源

**来源：Apple HIG › Typography 的 iOS 默认文本样式**，硬编码于 `RichTextEngine.swift:26-33`：

```swift
var designSize: CGFloat {
    switch self {
    case .title:   return 28   // Title 1
    case .heading: return 22   // Title 2
    case .body:    return 17   // Body
    case .quote:   return 15   // Subheadline
    }
}
```

`EditorDesignSize`（`:91-99`）导出具名常量与「全部自产字号」集合：

```swift
static let h1 = 28; static let h2 = 22; static let body = 17; static let quote = 15
static let authored: [CGFloat] = EditorBlockStyle.allCases.map(\.designSize)   // [28,22,17,15]
```

**旧梯级为 `{13, 15, 18, 22}`**（`editor-typography.md:45`），2026-09-15 改为 `{28,22,17,15}`（E1，`editor-typography.md:34`）。文档对照表在 `editor-typography.md:17-22` 与 `design-system.md:87-92`（后者仍写 v1 的 `ContentPart.type` = `h1`/`h2`/`p`，属**文档滞后**，以实现 `ContentPart.swift:123-130` 的 v2 名为准）。

### 5.2 行距 / 段后距：**按字号比例，不写死**

`RichTextEngine.swift:40-42`：

```swift
var lineSpacing: CGFloat { self == .quote ? designSize * 0.5 : designSize * 0.13 }
```

| 样式 | lineSpacing | 绝对值 |
|---|---|---|
| title | `28 * 0.13` | **3.64** |
| heading | `22 * 0.13` | **2.86** |
| body | `17 * 0.13` | **2.21** |
| quote | `15 * 0.5` | **7.5** |

`RichTextEngine.swift:46-53`：

```swift
var paragraphSpacing: CGFloat {
    switch self {
    case .title:   return designSize * 0.4   // 11.2
    case .heading: return designSize * 0.3   // 6.6
    case .quote:   return designSize * 0.4   // 6.0
    case .body:    return 0
    }
}
```

历史包袱（`:36-39` 注释）：行距曾是**固定 2pt、引用 7pt**，且用 `size == quote` 判断引用块——即**用字号反推块类型**（同 E5，`editor-typography.md:38`）。改为比例 + 块类型判定后，放大字号不再显得拥挤。

### 5.3 随系统文字大小缩放：`DynamicTypeMetrics`

**实现位置**：`JustDiary/Views/Components/DesignSystem.swift:107-220`。核心链路：

```swift
static func font(_ size: CGFloat, weight: UIFont.Weight = .regular, typeSize: DynamicTypeSize) -> UIFont {
    UIFont.systemFont(ofSize: scaled(size, for: typeSize), weight: weight)      // :206-209
}
static func scaled(_ size: CGFloat, for typeSize: DynamicTypeSize) -> CGFloat {
    size * multiplier(for: size, typeSize: typeSize)                            // :199-201
}
static func multiplier(for size: CGFloat, typeSize: DynamicTypeSize) -> CGFloat {
    guard typeSize != .large else { return 1 }                                  // :191 默认档位恒等
    let style = textStyle(for: size)                                            // 按设计字号就近匹配 Apple 文本样式
    let scaled = UIFontMetrics(forTextStyle: style).scaledValue(for: size, compatibleWith: traits(for: typeSize))
    let raw = scaled / max(size, 0.01)
    return min(ceiling(for: style), max(1, raw))                                // :195 关键：钳制
}
```

**三要素**：

1. **就近匹配文本样式**（`textStyle(for:)`，`:143-156`）：`34+ → .largeTitle`、`28..<34 → .title1`、`22..<28 → .title2`、`20..<22 → .title3`、`17..<20 → .body`、`16..<17 → .callout`、`15..<16 → .subheadline`、`13..<15 → .footnote`、`12..<13 → .caption1`、其他 `.caption2`。→ 编辑梯级映射：28→title1、22→title2、17→body、15→subheadline。**默认档位（`.large`）返回恒等**，设计稿大小原样保留（`:140-142` 注释）。
2. **`UIContentSizeCategory` 映射**（`category(for:)`，`:161-177`）：`DynamicTypeSize` 12 档 → `UIContentSizeCategory`；用 `UITraitCollection(preferredContentSizeCategory:)` 构造并加锁缓存（`:179-187`）。
3. **每样式上限 `ceiling`**（`:124-136`）——**这是本项目的刻意偏离**：

```swift
case .largeTitle: return 1.35
case .title1:     return 1.40
case .title2, .title3: return 1.45
case .body:       return 1.50
case .callout:    return 1.55
case .subheadline: return 1.60
case .footnote:   return 1.65
case .caption1:   return 1.70
default:          return 1.75
```

**为什么需要上限**（`:100-123` 注释）：Apple 未钳制曲线在 AX5 下把 body 从 17pt 拉到约 **53pt**，而且 body **长得比标题更快**，会**颠倒层级**——15pt 正文变得比 22pt 小标题还大，「日记正好在用户最需要看清结构时丢掉结构」。上限保证**整个梯级单调**，同时让辅助小字长得比显示大字更多。

**AX5 实测结果表**（`:118-120` 注释，覆盖本应用全部用到的字号）：

| 设计字号 | AX5 绘制值 | 对应上限 |
|---|---|---|
| 32 → | 44.8 | (1.40) |
| 28 → | 39.2 | (1.40 title1) |
| 24 → | 34.8 | (1.45) |
| 22 → | 31.9 | (1.45 title2) |
| 20 → | 29 | (1.45) |
| 18 → | 27 | (1.50) |
| **17 →** | **25.5** | (1.50 body) |
| 16 → | 24.8 | (1.55) |
| **15 →** | **24** | (1.60 subheadline) |
| 13 → | 21.5 | (1.65) |
| 12 → | 20.4 | (1.70) |
| 11 → | 19.3 | (1.75) |

→ **编辑梯级 AX5：28→39.2 / 22→31.9 / 17→25.5 / 15→24，严格单调**。`editor-typography.md:28` 与 `design-system.md:122-123` 都点名这是必须保持的性质，改上限时必须重核该表（`design-system.md:61`）。

### 5.4 为什么块类型与字号解耦

完整论证在 `editor-typography.md:41-51` 与 `design-system.md:97-111`。三个理由：

1. **改梯级会静默改写已有日记**：字号同时承担「字号」与「块类型」两个职责时，`>= 22 → h1`、`>= 18 → h2` 的推断规则一旦遇到新梯级就会重新分类历史数据。
2. **旧新梯级在 15 处重叠** `{13,15,18,22}` ∩ `{28,22,17,15}` = `{15, 22}`，**无法区分「应用自己写的 15」和「导入文档里用户自定义的 15」**，因此一次性迁移不可能正确。
3. **v2 已无 `size`**，字号的唯一来源是块样式，迁移问题自然消失（`editor-typography.md:94-95`）。

**实现手段**：把块类型放进文本存储的自定义 run 属性（`RichTextEngine.swift:117-120`）：

```swift
static let diaryDesignSize = NSAttributedString.Key("com.cov.justdiary.designSize")  // 未缩放的 design size
static let diaryBlockStyle = NSAttributedString.Key("com.cov.justdiary.blockStyle")  // 块类型
```

两者**都是编辑器内部状态、绝不持久化**（注释明确）；持久化形式是 `ContentPart.style`。

### 5.5 日历与编辑器的缩放速率：**不同**

- **编辑器 / 阅读器 / 编解码**：走**完整** `multiplier(for:)`（含每样式上限）。
- **日历**：`calendarMultiplier(for:)`（`DesignSystem.swift:217-219`）——**半速**：

```swift
static func calendarMultiplier(for typeSize: DynamicTypeSize) -> CGFloat {
    1 + (multiplier(for: 15, typeSize: typeSize) - 1) * 0.5
}
```

理由（`:211-216` 注释 + `design-system.md:63-66`）：日历用 `Canvas` 绘制，**格子尺寸由屏幕固定**，日号跟满曲线会撑破格子；星期栏走 `.diaryCalendarFont(_:)`（`DynamicType.swift:61-63`）同样半速以避免撑破固定高度的条带。

→ **Android 迁移时「日历半速」这条业务规则必须单独保留**，不要顺手统一成 `fontScale`。

### 5.6 字号注入路径（编辑器特有）

`PartsCodec` / `ReadTextView` 在 SwiftUI 之外建 `NSAttributedString`，**用不了 `.diaryFont`**，所以显式接收 `DynamicTypeSize`（`editor-typography.md` 与 `design-system.md:68-73`）：

- `RootView` 通过 `\.diaryDynamicTypeSize` 下发（`DynamicType.swift:17-27`，`RootView.swift:66`）；
- **`fullScreenCover` 内的 `DiaryPageView` 需要单独注入**——presentation 不继承更内层 `.environment`（`design-system.md:71-73`，`RootView.swift:93-98`）；
- `RichTextView` 读 environment（`RichTextView.swift:79`）后**推入控制器** `controller.dynamicTypeSize = typeSize`（`:98`），因为控制器读不到 environment（`RichTextEngine.swift:193-195`）。

---

## 6. 编辑器工具栏 / 交互清单

### 6.1 格式栏按钮（`FontToolbar`，`RichTextView.swift:196-337`）

按 `body`（`:264-319`）中的实际顺序：

| # | 图标 (SF Symbol) | 无障碍标签 key | 激活条件 | 禁用条件 | 动作 | 行号 |
|---|---|---|---|---|---|---|
| 1 | `textformat.size` + `chevron.down` | `editor_tool_style` | `currentBlockStyle() != .body` | — | **样式 Menu**：`title` / `heading` / `body`（`EditorBlockStyle.menuStyles`，`RichTextEngine.swift:58`） | `:227-262` |
| 2 | `text.aligncenter` | `editor_tool_center` | `isCenterActive()` | `list \|\| quote \|\| todo` | `toggleCenter()` | `:279-282` |
| — | `Divider` | — | — | — | 视觉分隔 | `:283-285` |
| 3 | `list.bullet` | `editor_tool_list` | `isListActive()` | — | `toggleList()` | `:286-289` |
| 4 | `text.quote` | `editor_tool_quote` | `isQuoteActive()` | — | `toggleQuote()` | `:290-293` |
| 5 | `checklist` | `editor_tool_todo` | `isTodoActive()` | — | `toggleTodo()` | `:294-297` |
| — | `Divider` | — | — | — | 视觉分隔 | `:298-300` |
| 6 | `bold` | `editor_tool_bold` | `activeStyles().bold` | — | `toggleBold()` | `:301-304` |
| 7 | `italic` | `editor_tool_italic` | `activeStyles().italic` | — | `toggleItalic()` | `:305-308` |
| 8 | `strikethrough` | `editor_tool_strike` | `activeStyles().strike` | — | `toggleStrike()` | `:309-312` |
| 9 | `underline` | `editor_tool_underline` | `activeStyles().underline` | — | `toggleUnderline()` | `:313-316` |

**按钮通用规格**（`btn(_:accessibilityLabel:active:disabled:action:)`，`:201-222`）：`Haptics.tap()` → 动作 → `onTap?()`；图标 `.diaryFont(15, weight: .medium)`；激活时白字 + `Theme.primary()` 胶囊底；`minWidth: 34, minHeight: 40`；禁用 `opacity(0.35)`；`accessibilityAddTraits(active ? .isSelected : [])`。

**顶栏按钮**（`DiaryPageView.topBar`，`:183-230`）：

| 模式 | 按钮 | 行号 |
|---|---|---|
| 通用 | `chevron.left` 返回 → `vm.handleBack()` | `:185-188` |
| 选择态 | `trash` + `editor_delete_count` 计数 → `confirmDeleteSelected()` | `:190-195` |
| 阅读态 | `magnifyingglass` 搜索 | `:197-203` |
| 阅读态（今天可编辑） | `square.and.pencil` → `enterWrite()` | `:204-209` |
| 阅读态 | `square.and.arrow.up` → `shareDiary()` | `:210-213` |
| 编辑态 | `photo` → `showPhotoPicker = true` | `:215-218` |
| 编辑态 | `arrow.counterclockwise` → `confirmDiscardEditing()` | `:219-222` |
| 编辑态 | `checkmark`（active）→ `saveEditor()` | `:223-226` |

### 6.2 插入图片流程

```
顶栏 photo 按钮 (DiaryPageView.swift:215-218)
  → showPhotoPicker = true                                    (:48)
  → .sheet { PhotoPicker { image in vm.insertImage(image) } } (:48-52)
  → PHPickerViewController (filter .images, limit 1, .current)(MediaViews.swift:107-114)
  → itemProvider.loadObject(ofClass: UIImage.self)            (MediaViews.swift:128)
  → 主线程回调 onPicked                                      (MediaViews.swift:130-132)
  → DiaryViewModel.insertImage(_:)                            (:488-503)
        processed = DiaryImageStore.downsample(image, maxPixel: 2048) ?? image      (:489)
        data = processed.jpegData(compressionQuality: 0.85) ?? pngData()           (:490)
        fileName = "img_<epochMillis>.jpg"                                          (:491-492)
        写入 DiaryRepository.imagesDir()                                             (:494-496)
        controller.insertImage(processed, src: "images/\(fileName)")                 (:497)
  → RichEditorController.insertImage(_:src:)                  (RichTextEngine.swift:675-690)
        maxW = min(bounds.width - 24, 343)；displayH = max(40, maxW * ratio)         (:677-680)
        rounded(radius: Radius.image) + attachment.bounds                            (:681-683)
        尾部补 "\n"，插入，光标移到其后                                               (:685-688)
```

**失败提示**：写盘失败 → `alertItem = .info(editor_image_failed_title / editor_image_failed_msg)`（`:498-502`）。

### 6.3 待办勾选

**编辑器内不可点！** 编辑态的勾选框只是 `MarkerAttachment` 图片，**没有任何点击手势或 `UITextViewDelegate` 附件交互**（`RichTextView.swift` 只有 `shouldChangeTextIn` / `textViewDidChange` / `textViewDidChangeSelection` 三个 delegate 方法）。

**勾选只在阅读态**（`ReadTextView.swift:91-98`, `:140-159`）：

```
Coordinator.rebuild() 维护 todoRanges/todoCallbacks（按 parts 顺序压入 (partIndex, itemIndex)）
  → handleTap: gesture.location(in:) → closestPosition(to:) → offset(from:beginningOfDocument,to:)
  → NSLocationInRange(offset, range) 命中 → parent.onToggleTodo?(pi, ii)
  → DiaryPageView.swift:359-361 → vm.toggleTodo(block:partIndex:itemIndex:)
  → DiaryViewModel.swift:359-378：parseContentCached → 改 done[i] → serializeContent → updateBlockContent
```

命中范围包含**整行**而非仅勾选框（`ReadTextView.swift:92-96`，从 marker 到行尾换行符）。`DiaryPartsView` 在跨 chunk 时做 `pi + chunk.offset` 偏移修正（`MediaViews.swift:73`）。

### 6.4 列表行为

- **列表 marker 是行首的一个 attachment 字符**（U+FFFC），格式为 `MarkerAttachment` + 正文文本 + `\n`（`RichTextEngine.swift:852-868`）。
- **可插入的 kind 只有两种**：`"bullet"`（`toggleList`）与 `"todo"`（`toggleTodo`）——**没有有序列表、没有缩进层级**。
- **开启列表会先清掉其他块样式**（`:538-539` 先 `restyle(to: .body, cancelCenter: true)`）。
- **连续行自动合并为同一 part**（`appendList`，`:1008-1022`）。
- **列表与标题/引用/待办互斥**（`:315-319` 注释）；居中与列表/引用/待办互斥（`:316-319`, `:436-438`）。
- 待办 done 态渲染：`strikethroughStyle = 1` + 前景色 `alpha 0.45`（`:860-863`）。

### 6.5 撤销 / 重做

**没有撤销栈**（§2.5）。实际存在的是：

| 行为 | 触发 | 条件 | 实现 |
|---|---|---|---|
| 返回时提示未保存 | `handleBack()` | `!isRead && !controller.isEmpty()` | `DiaryViewModel.swift:177-186`，`.confirm(editor_exit_title / editor_exit_content_msg, confirmLabel: "discard")` → `onDismiss` |
| **放弃修改**（二次确认） | `confirmDiscardEditing()` | `!controller.isEmpty()` | `:196-203`，`.confirm(editor_discard_title / editor_discard_msg, "discard")` → `discardEditing()` |
| 实际回滚 | `discardEditing()`（private） | — | `:205-209`：`loadParts = editingOriginalParts; loadToken += 1; controller.refreshTypingAttributes()` |
| 空编辑器直接返回 | `saveEditor()` | `parts.isEmpty && editingIndex == nil && blocks.isEmpty` | `:216-218` |

**触发条件汇总**：二次确认只在**编辑器非空**时出现（`:197` `guard !controller.isEmpty()`）。空编辑器点「放弃修改」**静默无操作**。

**历史 bug（已在 E3 修）**：按钮曾是 `arrow.counterclockwise` + 「重做/Redo」文案 + **单击静默丢弃整段编辑**（`editor-typography.md:36`，`DiaryViewModel.swift:190-195` 注释）。另修掉连带 bug：新日记的 `editingOriginalParts` 会沿用上一次编辑过的块，现在 `enterWrite()` 显式清空（`DiaryViewModel.swift:116-119`），否则新日记里点放弃会恢复出上一个块的内容。

### 6.6 键盘避让

见 §4.2 G。要点：监听 `keyboardWillChangeFrameNotification` → `height = frame.origin.y < Screen.height ? frame.height : 0`（`DiaryPageView.swift:102`，注意这行判断把「键盘收起」时 frame 在屏幕外的情况归零）→ 还原动画曲线（`:104-112`）→ `withAnimation { vm.keyboardHeight = height }`（`:113-115`）→ 三处消费：格式栏底部内边距（`:26`）、滚动区占位 `keyboardHeight + 160`（`:141-145`）、以及弹起后自动滚到编辑卡片（`:169-177`）。

### 6.7 焦点管理

- **自动聚焦**：`autoFocusEditor`（`DiaryViewModel.swift:28`，由 `enterWrite` / `enterEditBlock` 置 `true`，`:125`, `:146`，`reloadAndShowRead` 置 `false`，`:329`）→ `RichTextView.makeUIView` 中 `DispatchQueue.main.asyncAfter(0.25) { tv.becomeFirstResponder() }`（`RichTextView.swift:106-110`）。
- **点格式栏回到编辑器**：`FontToolbar(controller: vm.controller, onTap: { vm.controller.textView?.becomeFirstResponder() })`（`DiaryPageView.swift:17-19`）+ 每个按钮动作后调用 `onTap?()`（`RichTextView.swift:205`）。**这是防止「点按钮时键盘收起」的关键**。
- **无显式 `resignFirstResponder`**：靠 `keyboardDismissMode = .interactive`（`RichTextView.swift:94`）。

### 6.8 「放弃修改」二次确认的触发条件（完整）

1. UI 只在**编辑态**显示该按钮（`DiaryPageView.swift:214-227` 的 `else` 分支）。
2. `confirmDiscardEditing()` 先 `guard !controller.isEmpty()`（`DiaryViewModel.swift:197`）——**空编辑器不弹窗、不动作**。
3. 弹 `.confirm`，确认回调才 `discardEditing()`（`DiaryViewModel.swift:198-203`）。
4. 另有**两条独立**的二次确认，容易与「放弃修改」混淆：
   - **返回时的未保存确认**（`:178-183`，标题 `editor_exit_title`）；
   - **保存时无位置的确认**（`:251-264`，`editor_location_retry` / `editor_save_anyway`）+ 后续**跨天确认**（`:270-281`，`editor_cross_day_title`）。位置规则详见 `docs/location-recording.md:11-19`。

---

## 7. 图片 / 媒体处理

### 7.1 采集方式：`PHPickerViewController`（PhotosUI），**不是** SwiftUI `PhotosPicker`

```swift
struct PhotoPicker: UIViewControllerRepresentable {     // MediaViews.swift:104
    var onPicked: (UIImage) -> Void
    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .images                          // :109 仅图片
        config.selectionLimit = 1                        // :110 单选
        config.preferredAssetRepresentationMode = .current  // :111 避免转码
        ...
    }
}
```

回调：`result.itemProvider.loadObject(ofClass: UIImage.self)`（`:128`）→ 主线程 `onPicked(image)`（`:130-132`）。**无多选、无相册权限弹窗（PHPicker 免权限）、无相机入口**。

### 7.2 降采样尺寸与压缩参数（常量值）

`DiaryViewModel.insertImage`（`:488-503`）：

| 常量 | 值 | 位置 |
|---|---|---|
| 插入前降采样 `maxPixel` | **2048** | `DiaryViewModel.swift:489` |
| JPEG 压缩质量 | **0.85** | `DiaryViewModel.swift:490` |
| 降采样中间编码质量（`downsample` 内部） | **0.9**（JPEG），失败退回 PNG | `MediaStore.swift:82` |
| 兜底 | `downsample(...) ?? image`（**降采样失败用原图**） | `DiaryViewModel.swift:489` |

`DiaryImageStore.downsample(_:maxPixel:)`（`MediaStore.swift:78-84`）：

```swift
guard maxPixel > 0 else { return image }
let maxDim = max(image.size.width, image.size.height)
guard maxDim > maxPixel else { return image }         // 已足够小则原样返回
guard let data = image.jpegData(compressionQuality: 0.9) ?? image.pngData() else { return image }
return loadDownsampled(data: data, maxPixel: maxPixel)
```

`loadDownsampled(data:maxPixel:)`（`:105-118`）——**真正的降采样走 ImageIO thumbnail 路径**：

```swift
let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
CGImageSourceCreateWithData(data as CFData, sourceOptions)
let options = [
    kCGImageSourceCreateThumbnailFromImageAlways: true,
    kCGImageSourceCreateThumbnailWithTransform: true,       // 应用 EXIF 方向
    kCGImageSourceShouldCacheImmediately: true,
    kCGImageSourceThumbnailMaxPixelSize: maxPixel,          // 限制最长边
] as CFDictionary
CGImageSourceCreateThumbnailAtIndex(source, 0, options)
```

`loadDownsampled(at:maxPixel:)`（`:100-103`）是同一函数的磁盘入口。

**渲染时按显示尺寸再降采样**（避免解码全图）：

| 场景 | maxPixel | 位置 |
|---|---|---|
| 编辑态 / 阅读态 attachment | `max(storedW, storedH) * 3` | `RichTextEngine.swift:827` |
| 阅读端 SwiftUI `DiaryImageView` | `max(displayW, displayH) * 3` | `MediaViews.swift:25` |
| 全屏预览 `ImagePreviewView` | **1600** | `MediaViews.swift:151` |

`× 3` 是给 @3x 屏留的像素余量。

**图片缓存**（`MediaStore.swift:47-68`）：`NSCache<NSString, UIImage>`，`countLimit = 200`，`totalCostLimit = 150 * 1024 * 1024`（150MB），cost = `cgImage.bytesPerRow * cgImage.height`（`:65`）。`invalidate(src:)` / `invalidateAll()`（`:70-76`）。

**圆角合成**（`MediaStore.swift:86-98`）：`UIGraphicsImageRenderer(size:)` + `UIBezierPath(roundedRect:cornerRadius:)`，`aspect-fit` 居中绘制，半径 `Radius.image`。

### 7.3 存储命名

```swift
let stamp = Int64(Date().timeIntervalSince1970 * 1000)      // 毫秒时间戳
let fileName = "img_\(stamp).jpg"                            // DiaryViewModel.swift:491-492
try FileManager.default.createDirectory(at: DiaryRepository.imagesDir(), withIntermediateDirectories: true)
let target = DiaryRepository.imagesDir().appendingPathComponent(fileName)
try data.write(to: target)
```

- 目录：`DiaryRepository.imagesDir()`（Documents 下）。
- **逻辑键**（存在 JSON 里）：`"images/img_<stamp>.jpg"`（`DiaryViewModel.swift:497`）。
- **物理路径**：`Documents/images/img_<stamp>.jpg`（`ImagePathUtil.resolveImagePath`，`ContentPart.swift:233-237`）。
- **归一键**：`normalizeSrcKey`（`:209-215`）确保旧数据 `"a.jpg"` → `"images/a.jpg"`；`collectImageSrcs`（`:239-241`）用于备份/清理时收集引用。
- **无去重、无 hash**：同一张图重复插入会产生多个文件（时间戳唯一）。**迁移时若要保留备份兼容性，命名规则必须原样保留**；若要改，必须写迁移把新名写回 `src`。

### 7.4 正文中的表示与渲染尺寸计算

**两套完全不同的渲染路径**（迁移时必须注意）：

**(A) 编辑态 + 阅读态的 `ReadTextView`：`NSTextAttachment`**

存储的 `w`/`h` 是**显示尺寸（pt）**，插入时写入 `min(viewWidth-24, 343)` 与按比例的高度（`RichTextEngine.swift:677-680`）。加载时：

```swift
let storedW = max(1, CGFloat(part.w ?? 300));  let storedH = max(1, CGFloat(part.h ?? 200))
let fallbackW = max(60, Screen.width - 76)
let maxW = max(60, imageMaxWidth ?? fallbackW)
let w = min(storedW, maxW)          // 只缩不放
let h = storedH * w / storedW
DiaryImageStore.shared.image(for: src, maxPixel: max(storedW, storedH) * 3)
attachment.bounds = CGRect(x: 0, y: 0, width: w, height: h)
```

（`RichTextEngine.swift:820-831`）。**宽度随编辑器可用宽自适应**，并由 `updateUIView` 在宽度变化 > 1pt 时触发重载（`RichTextView.swift:121-131`）。

**(B) 阅读端的 `DiaryPartsView`：拆成独立 SwiftUI 图片视图**

`DiaryPartsView.buildChunks`（`MediaViews.swift:81-101`）**把 parts 数组按 image 切块**：遇到 `style == "image"` 就单独产出一个 `Chunk(image:)`，其余文本合成另一个 `Chunk(parts:)` 交给 `ReadTextView`。图片由 `DiaryImageView` 渲染：

```swift
let w = min(max(40, geo.size.width), max(40, displayW))     // MediaViews.swift:21
let fitH = max(1, displayH * w / max(displayW, 1))          // :22
.aspectRatio(max(displayW,1)/max(displayH,1), contentMode: .fit)   // :41
```

→ 阅读端是**原生 SwiftUI 视图**（可 `.onTapGesture`），编辑端是**文本附件**。**Android 端没必要照搬这个分裂**：`ImageSpan` + `ClickableSpan` 或 Compose 的 `InlineTextContent` 都能统一。

### 7.5 分享 / 导出时的处理

`shareDiary()`（`DiaryViewModel.swift:507-526`）：

```swift
let shareBlocks = blocks.map { ShareBlock(time:, loc:, parts: ContentFlatten.parseContentCached($0.contentJson)) }
let isDark = UITraitCollection.current.userInterfaceStyle == .dark
Task.detached(priority: .userInitiated) { ShareRenderer.render(dayKey:blocks:isDark:) ... }
```

**`ShareRenderer` 是与编辑器完全独立的第三套渲染实现**（`JustDiary/Services/ImageShareService.swift`，458 行）：

| 常量 | 值 | 行号 |
|---|---|---|
| 画布宽 | `720.0` | `:10` |
| 左边距 / 右边距 | `blockLeft = 108.0` / `side = 48.0` | `:11-12` |
| 时间轴 x / 宽 | `61.0` / `3.0` | `:13-14` |
| 日期基线 y | `64.0` | `:15` |
| 页脚高 | `34.0` | `:16` |
| 内容宽 | `720 - 108 - 48 = 564` | `:17` |

**导出字号是硬编码的 pt（不走 Dynamic Type）**（`fontForPart`，`:182-190`）：

| style | 字号 / 字重 | 行高 |
|---|---|---|
| title | **36 / bold** | 52 + 12 |
| heading | **32 / semibold** | 46 + 10 |
| quote | **30 / regular** | 40 |
| list / todo | **30 / regular** | 55 |
| body（default） | **31 / regular** | 50 |

**导出图片尺寸**（`imageHeight`，`:135-147`）：优先 `part.w/part.h` 算比例，缺失则读磁盘图片实际比例，全失败兜底 `0.75`；高 = `max(120, 564 * ratio)`。

**导出文本换行是自研的**（`:149-180`）：逐字符 `boundingRect` 测量 + CJK/ASCII/其他墨迹比例（0.87 / 0.75 / 0.98，`:122-130`）——**完全不复用 `PartsCodec`**。

→ **迁移判定**：`ShareRenderer` 是一个**纯 CoreGraphics 绘制管线**，Android 端对应 `Canvas` + `StaticLayout`/`TextPaint`，属**独立重写项**（与编辑器无耦合），但**常量表必须逐项对齐**否则导出长图样式会漂移。分享出口是 `ShareLink(item: Image(uiImage:), preview: SharePreview(...))`（`DiaryPageView.swift:53-60`）。

---

## 8. Android 落地方案对比

### 8.0 迁移的第一性判断

这套编辑器的**语义模型（`ContentPart` / `TextRun` / `ContentDocument`）是平台无关的资产**，占工作量的核心但可 1:1 移植；**真正难的是「用字符偏移的富文本容器实现段落级语义 + 行内样式 + 不污染存储」**。Android 三条路线在这个核心问题上的差别最大。

补充一个关键前提：**Android 的段落/行内样式模型天然比 iOS 更接近本项目需求**。`Spannable` 支持 `ParagraphStyle` 标记接口（`AlignmentSpan`、`LeadingMarginSpan`、`LineHeightSpan`），且 span 可携带**任意自定义数据**（自定义 `CharacterStyle` / 自定义 `ParagraphStyle` 子类），正好对应 `.diaryBlockStyle` / `.diaryDesignSize` 这种「编辑器内部、不落库」的元数据。**iOS 要 hack 的 `NSAttributedString.Key`，在 Android 是官方设计的一部分。**

---

### 8.1 路线 (a)：纯 Compose `BasicTextField` + `TextFieldState` / `OutputTransformation`

**API 现状（2026-08 文档快照）**

- `TextFieldState` 已稳定（`rememberTextFieldState` / `TextFieldState()` + `edit {}`）。
- `TextFieldState.text: CharSequence`、`selection: TextRange`、**`composition: TextRange?`（IME 组合区间，直接可读）**。
- `TextFieldState` **有 `textStyles: TextFieldTextStyles`**（用于「工具栏按选区样式高亮」——正对 §6.1 的 `activeStyles()` 场景）。
- `TextFieldBuffer` 提供 **`addStyle(spanStyle:start:end:)` / `addStyle(paragraphStyle:start:end:)`**、`getSpanStyles(range:)` / `getParagraphStyles(range:)`、`removeStyle(trackedRange:)`、`TrackedRange<SpanStyle>` / `TrackedRange<ParagraphStyle>`、`spanStyle` / `paragraphStyle` / `textRange` / `expandPolicy`。
- `TextFieldState.undoState: UndoState`（`@ExperimentalFoundationApi`）**内建撤销栈**。
- 关键限制：**`addStyle` 的可用范围受 `ComposeFoundationFlags.isBasicTextFieldStyledTextEnabled` 控制**。该 flag **关闭（当前默认）**时：`addStyle` **只能在 `OutputTransformation` 内调用**，且文档明确写「added in this mode will **not become part of the underlying text state**, nor will its boundaries be tracked or updated during subsequent edits」，并建议「apply styles only after the text content has been fully determined」。flag 开启后，`addStyle` 可在任意 `TextFieldBuffer` 作用域（含 `InputTransformation` / `TextFieldState.edit`）调用，样式会被跟踪、边界随编辑自动调整（等效 `ExpandPolicy.AtEnd`）。
- 相关官方 issue：`BasicTextField with TextFieldState does not support AnnotatedString and lacks partial styling features`（[issuetracker 389978748](https://issuetracker.google.com/issues/389978748)）——**这正是「想给选区加粗」这条路的已知痛点**。

**适配度评估**

| 维度 | 支持程度 | 说明 |
|---|---|---|
| 段落样式语义化 | ⚠️ 中 | `addStyle(ParagraphStyle, start, end)` 可表达对齐/行距/段落间距；但**没有 `NSParagraphStyle.backgroundColor` 的等价物**（引用底色要自己画背景或靠 `SpanStyle.background`）。要复刻「块类型与字号解耦」，需要自定义「段落语义 → ParagraphStyle/SpanStyle」映射表，且**必须每帧重算**（`OutputTransformation` 是纯函数） |
| 行内样式 | ⚠️ 中（依赖 flag） | flag 关闭时样式**不在 text state 里**，意味着「选区加粗」必须先写进**自己的语义模型**（一份 `List<ContentPart>`），再由 `OutputTransformation` 投影出来。这其实**与本项目架构高度契合**——iOS 的权威就是 `[ContentPart]`，编辑器只是视图。但代价是**要自建「偏移 ⇄ 语义模型」映射**，等价于把 iOS 的 `PartsCodec` 双向版重写一遍 |
| 待办 | ⚠️ 需自建 | Compose 无 attachment；勾选框要靠 `InlineTextContent`（可嵌入 Composable）或自定义 `Appendable` + 可点击 span。`InlineTextContent` 是 `AnnotatedString` 层，**在 `OutputTransformation` 里能否用**需验证 |
| IME 中文输入 | ⚠️ 中 | `composition: TextRange?` **显式可用**，优于 iOS 现状；但 flag 关闭时样式是「投影」的，IME 组合区在投影时容易错位；必须新增「格式操作跳过 composition range」规则 |
| 撤销栈 | ✅ **最好** | `TextFieldState.undoState` 内建；比 iOS（无撤销）更强 |
| 大文本性能 | ❌ 风险最高 | `OutputTransformation` 在**每次输入**都要重算全文/全段落样式。iOS 现状已经是「每次格式操作 `replaceCharacters` 全文 O(n)」（`RichTextEngine.swift:737-744`），Compose 路线把它变成**每次按键**都要 O(n) 投影（除非自己做增量缓存 + `derivedStateOf`）。大日记（数十 KB）下有卡顿风险 |
| 依赖风险 | ⚠️ 高 | 依赖 `ComposeFoundationFlags` / `TrackedRange` / styled-text API 的**实验性演进**；`ExperimentalFoundationApi` / `ExperimentalFoundationStyleApi` 并存，跨版本破坏性变更概率不低 |

**结论**：**可行但风险最集中**——它把最难的「偏移映射 + 增量重算」推给了业务代码，且核心 API 仍在实验期。**不建议作为首选**，除非团队已有深度 Compose 文本栈经验并接受逐版本跟进。

---

### 8.2 路线 (b)：Compose 中 `AndroidView` 桥接 `EditText` + `Spannable`（**推荐**）

**为什么这条路结构上与 iOS 对称**

| iOS | Android 原生等价 | 备注 |
|---|---|---|
| `UIViewRepresentable` 包 `UITextView` | `AndroidView` 包 `AppCompatEditText` | 完全对称的桥接层 |
| `NSTextStorage`（可变富文本） | `SpannableStringBuilder` | 都是「字符序列 + 属性区间」 |
| `NSRange` (location, length) | `Int` 字符偏移 / `TextRange` | UTF-16 偏移语义**一致**（emoji 代理对都是 2） |
| `NSAttributedString.Key.diaryBlockStyle` | **自定义 `ParagraphStyle` 标记 span** | Android 原生支持承载任意 payload，**比 iOS 更干净** |
| `NSAttributedString.Key.diaryDesignSize` | 自定义 `CharacterStyle`/span 字段 | 同上 |
| `.font` = `UIFont` | `CharacterStyle` → `TextPaint.textSize` | `sp` 单位天然随系统字号 |
| `.paragraphStyle` (alignment/lineSpacing/paragraphSpacing) | `AlignmentSpan.Standard` / `LineHeightSpan` / `LeadingMarginSpan` / `LineSpacingSpan`(API 34+) | 需要组合多个 span |
| `.backgroundColor`（引用底色） | `BackgroundColorSpan` | **直接对应** |
| `.strikethroughStyle` / `.underlineStyle` | `StrikethroughSpan` / `UnderlineSpan` | 直接对应 |
| `.attachment` = `NSTextAttachment` | **`ImageSpan`**（含 `ReplacementSpan` 自定义） | 直接对应；`ImageSpan` 可带 payload 引用 |
| `typingAttributes` | `EditText` 的 `getPaint()`/`setPaint` + 插入时应用的 span | ⚠️ **无完美等价物**（见下） |
| `textViewDidChangeSelection` | `onSelectionChanged` / `SpanWatcher` | 对称 |
| `shouldChangeTextIn` | `InputFilter` / `TextWatcher.beforeTextChanged` | 对称 |
| `NSUndoManager` | **`android.undo.UndoManager`（API 23+）** | iOS 未用，Android 建议启用 |
| `UITraitCollection.userInterfaceStyle` | `Configuration.uiMode` | 对称 |
| `UIFontMetrics` 每样式上限 | `sp` + 自定义 `fontScale` 钳制 | 见 §8.4 |

**逐项评估**

| 维度 | 支持程度 | 说明 |
|---|---|---|
| 段落样式语义化 | ✅ **最好** | `Spannable` 的 `ParagraphStyle` 标记接口 + 自定义 payload span，可**直接**表达「块类型是显式存储的」这一核心不变量。`ContentPart.style` 与 span 一一对应，`restyle()` 逻辑（`RichTextEngine.swift:365-403`）可几乎逐行翻译：删段首 marker span → 重建 `CharacterStyle` → 重建对齐/行距 span |
| 行内样式 | ✅ 最好 | `StyleSpan(Typeface.BOLD/ITALIC)`、`StrikethroughSpan`、`UnderlineSpan` 全是一等公民。`apply` 的 `enumerateAttribute` 对应 `getSpans(start, end, Class)` + `setSpan` |
| 待办 | ✅ 好 | `ImageSpan(drawable, ALIGN_CENTER)` 画勾选框；阅读态加 `ClickableSpan` 或 `TextView.onTouchEvent` + `getOffsetForPosition`（对应 `closestPosition(to:)`）；编辑态**不设点击**（与 iOS 一致） |
| IME 中文输入 | ✅ 最好 | `EditText` 通过 `BaseInputConnection.setComposingText` 原生管理 composing span，`Spannable` 的输入法组合是平台成熟路径（十余年打磨）。中文/日文/韩文 IME 是最稳的一条。但仍要加「格式操作避开 `getSpan(start,end,ComposingText)` 区间」的保护 |
| 撤销栈 | ✅ **可白送** | `UndoManager`（API 23+）挂到 `EditText` 即可，`setAllowUndo(true)`。**这是相对 iOS 的功能增强**（iOS 完全没有撤销栈，只有全量放弃）。注意：`apply` 式「全量替换文本」必须改掉，否则撤销粒度会变成「整篇」——建议格式操作只 `setSpan`/`removeSpan`，不重写文本 |
| 大文本性能 | ✅ 最好 | `SpannableStringBuilder.setSpan` 只触发局部重排；`EditText` 的 `Layout`/`DynamicLayout` 增量更新。**关键是把 iOS 的 `apply` 全量替换（`RichTextEngine.swift:737-744`）改成增量 span 操作**——这既修掉 iOS 的性能问题，也让撤销粒度正确。用 `StaticLayout` 时注意 `BoringLayout` 优化（单行段落） |
| `typingAttributes` 等价物 | ⚠️ 唯一真风险 | Android 无「下一字符属性」概念。做法：编辑前把当前块类型的 span **插在光标处的零宽 `SpannableStringBuilder` 位置**（或 `setSpan(spans, selStart, selStart, SPAN_MARK_POINT)`），让后续输入自然继承；更稳的做法是在 `TextWatcher.afterTextChanged` 里为新增区间**主动补 span** |
| 依赖风险 | ✅ 最低 | 全平台 API，无第三方库；`EditText` 不会被弃用 |

**推荐结论：路线 (b)。** 理由三条：
1. **结构对称**：与 iOS 的 `UIViewRepresentable → UITextView` 一一对应，`RichEditorController` 的**方法语义可逐条迁移**（`selectedRange` → `selectionStart/End`，`textStorage` → `SpannableStringBuilder`），迁移可验证性高。
2. **样式模型原生契合**：「块类型显式存储」这个 iOS 上要靠自定义 `NSAttributedString.Key` 实现的架构决策，在 Android 是语言级支持。
3. **风险最低且免费增强**：IME 最稳、性能最好、白送撤销栈。

**代价**：需要写 `AndroidView` 桥接层（≈ `RichTextView.swift` 337 行的 Kotlin 版）+ 处理 Compose 与 `EditText` 的滚动/焦点/尺寸同步。这部分工作与路线 (a) 的「自建偏移映射」相比更**确定**、更好估。

---

### 8.3 路线 (c)：第三方富文本库

**候选与维护状态**（GitHub API 实测，2026 时间线）

| 库 | 语言 | Stars | 最后 push | archived | 结论 |
|---|---|---|---|---|---|
| [wasabeef/richeditor-android](https://github.com/wasabeef/richeditor-android) | Java | 6,346 | **2024-05-21** | 否 | ❌ **两年多无提交**，223 open issues，Java + 已过时的 `WebView`/`EditText` 混血方案 |
| [wordpress-mobile/AztecEditor-Android](https://github.com/wordpress-mobile/AztecEditor-Android) | Kotlin | 729 | 2026-03-16 | 否 | ⚠️ **仍在维护但为 WordPress 编辑器服务**，126 open issues，MPL-2.0。核心模型是 **HTML**（`AztecText` ⇄ HTML），不是语义段落模型 |
| [MohamedRejeb/compose-rich-editor](https://github.com/MohamedRejeb/compose-rich-editor) | Kotlin (KMP) | 1,853 | **2026-09-07** | 否 | ✅ **最活跃**，Apache-2.0。但版本仍是 **`v1.0.0-rc14`**（未发布 1.0 stable），82 open issues |
| [Goooler/Compose-Rich-Editor](https://github.com/Goooler/Compose-Rich-Editor) | Kotlin | 0 | 2025-07-02 | 否 | ❌ 只是上面那个的 **fork**（`"fork": true`, parent = MohamedRejeb），0 star，勿用 |
| [Darkrock-Studios/ComposeTextEditor](https://github.com/Darkrock-Studios/ComposeTextEditor) | Kotlin | 64 | 2026-09-15 | 否 | ⚠️ 活跃但**极新、极小**（4 forks, MIT），"Compose's missing Rich Text editor"，生产验证不足 |
| [pChochura/richtext-compose](https://github.com/pChochura/richtext-compose) | Kotlin | 小 | — | — | ⚠️ 「TextField 内样式」组件，单点能力，不是完整编辑器 |

**逐库适配度（针对本场景）**

| 库 | 段落语义样式 | 行内样式 | 待办 | IME | 撤销 | 大文本 | 迁移适配度 |
|---|---|---|---|---|---|---|---|
| wasabeef/richeditor-android | ❌ 无（HTML 思路） | ✅ | ❌ | ⚠️ | ❌ | ❌ WebView 路线 | **不适用**；且已停更 |
| AztecEditor-Android | ⚠️ 有 heading/list 概念但**以 HTML 为权威** | ✅ | ⚠️ | ✅ Kotlin + 原生 span，成熟 | ⚠️ 有自有 history | ✅ 为 WordPress 长文优化 | **勉强**：需要把 HTML 模型 ⇄ `content_json` 双向转换，且会丢掉 `align`/`done`/`w`/`h` 的精确语义；MPL-2.0 需注意。**不值得为了省桥接层而引入一个 HTML 中间表示** |
| MohamedRejeb/compose-rich-editor | ⚠️ 基于 `RichTextState`（自有模型）+ HTML/Markdown 导出 | ✅ | ⚠️ 有 checkbox 支持需验证 | ✅ KMP，活跃 | ⚠️ 有 undo/redo | ⚠️ 纯 Compose，长文性能待验 | **候选但有前提**：它的 `RichTextState` 是**另一套语义模型**，与本项目 `ContentPart` 需要双向映射；且 **`v1.0.0-rc14` 未 stable**，API 会动。若团队接受「库模型 ⇄ content_json」转换成本，可省掉文本容器实现 |
| ComposeTextEditor | 未验证 | 未验证 | 未验证 | 未验证 | 未验证 | 未验证 | 太新，需自建 POC 才能判断 |

**结论：路线 (c) 不推荐作为主路线。** 核心理由：
1. **本项目的语义模型已经比任何第三方库的模型更贴合业务**（`title/heading/body/quote/list/todo/image` + `align` + `done[]` + `w/h` + v1 兼容），引入库意味着多一层「库模型 ⇄ content_json」转换，这层转换的正确性还要写测试——**净工作量未必比路线 (b) 的桥接层小**。
2. **备份兼容是硬约束**：`content_json` 必须能被 iOS 端原样读回。库的 HTML/Markdown 中介会引入精度损失（尤其 `align`、`done`、图片 `w/h`）。
3. 主流候选（MohamedRejeb）**仍未 stable**，`rc14` 阶段 API 变更会直接传导。

**可以用库的地方**：如果只需要**富文本展示**（阅读态）而非编辑，`compose-rich-editor` 的渲染部分或直接用 `AnnotatedString` + `Text` 手写更简单——**本项目的阅读态本来就建议直接用 Compose `Text` 渲染 `[ContentPart]`，不需要任何库**（对应 `DiaryPartsView` / `ReadTextView` 的职责，去掉 `ReadTextView` 这一层 UIKit 桥接反而更简单）。

---

### 8.4 三条路线的横向评分

| 维度 | (a) Compose 纯 | (b) EditText+Spannable | (c) 第三方库 |
|---|---|---|---|
| 段落样式语义化 | 6/10 | **9/10** | 5/10（模型不匹配） |
| 行内样式 | 6/10（依赖 flag） | **9/10** | 8/10 |
| 待办 | 5/10 | **8/10** | 6/10 |
| IME 中文输入 | 6/10 | **9/10** | 8/10 |
| 撤销栈 | **9/10**（内建 undoState） | 8/10（UndoManager，需自己接） | 8/10 |
| 大文本性能 | 4/10（每键 O(n) 投影） | **9/10**（增量 span） | 6/10 |
| API 稳定性 | 4/10（实验 flag） | **10/10**（平台 API） | 5/10（rc14） |
| 备份兼容可控性 | **9/10** | **9/10** | 4/10（HTML 中介） |
| 迁移对称性（对照 iOS） | 5/10 | **10/10** | 5/10 |
| 实现工作量确定性 | 低（探索性） | **高（可逐条对照 iOS）** | 中 |
| **合计倾向** | 备选 | **首选** | 不推荐 |

### 8.5 字号模型在 Android 上的映射（§5 的落地）

`UIFontMetrics` + 每样式上限**没有直接等价物**。Android 的事实标准是 `sp`（随 `Configuration.fontScale` 线性缩放）。**直接等号会破坏 §5.3 的核心性质（梯级单调）**：Android `fontScale` 最大值通常 1.3（部分定制 ROM 到 2.0），线性放大会让 17pt body 在 `fontScale = 2.0` 时变 34pt，超过未放大的 28pt title。

**推荐做法**：移植 `DynamicTypeMetrics` 的**钳制逻辑本身**，用 `fontScale` 作为曲线输入：

```kotlin
object EditorMetrics {
    val ceilings = mapOf(   // 直接照搬 DesignSystem.swift:124-136
        "title" to 1.40f, "heading" to 1.45f, "body" to 1.50f, "quote" to 1.60f
    )
    fun multiplier(designSize: Float, style: String, fontScale: Float) =
        (1f + (fontScale - 1f)).coerceIn(1f, ceilings[style] ?: 1.75f)
    fun scaled(designSize: Float, style: String, fontScale: Float) =
        designSize * multiplier(designSize, style, fontScale)
}
```

- **默认档位恒等**：`fontScale == 1.0f` 返回 `designSize` 原值（对应 `DesignSystem.swift:191` 的 `guard typeSize != .large else { return 1 }`），保证设计稿大小在默认设置下不变。
- **上限表逐项对齐**：28 → 1.40、22 → 1.45、17 → 1.50、15 → 1.60（来自 `DesignSystem.swift:126-131` 的 `title1/title2/body/subheadline`）。
- **只在绘制时应用，绝不写回 `content_json`**（不变量 N2）。
- **日历半速单独保留**（`DesignSystem.swift:217-219`）：`1 + (multiplier(body, fontScale) - 1) * 0.5`。
- Android 还多一个 iOS 没有的坑：**`fontScale` 变化会触发 `Activity` recreate**（未在 manifest 声明 `android:configChanges="fontScale"` 时）→ 编辑中的草稿会丢。必须在 ViewModel / `SavedStateHandle` 里保存 `[ContentPart]` 和光标位置。（对应 iOS 的 `reapplyTypeSize` + 光标恢复，`RichTextEngine.swift:710-723`。）

### 8.6 迁移时必须保留的「暗坑」清单（从 §2.4 提炼）

Android 重写时若忽略任意一条，都会造成**静默数据损坏或样式错乱**：

1. **块类型显式存储，绝不从字号反推**（`RichTextEngine.swift:952-960`）；字号推断只作导入兜底。
2. **兜底反解必须对自产字号做精确匹配**（`:173-183`），否则每次保存都漂移。
3. **绘制字号绝不落库**（`:991-994`）。
4. **段落操作必须从后往前**，因为「删段首 marker 会改变后续偏移」（`:330-333`）。
5. **尾随空行不落库**（`:924`）。
6. **`done` 数组与 `items` 按下标对齐**，缺失时按全 `false`（`:814`）。
7. **连续 list/todo 必须合并为一个 part**（`:1008-1022`）。
8. **空行上的段落操作只影响后续输入，不碰相邻行**（`:445-459`, `:606-651`）。
9. **格式操作必须跳过 IME composition range**（iOS 没做，Android 必须补）。
10. **`content_json` 序列化器要支持键排序 + 不转义斜杠**（`ContentPart.swift:202`）。
11. **未知 `style` 值降级为 `body`，不抛错**（`ContentPart.swift:142-144`）。
12. **v1 裸数组 + `type` 键 + HTML 名 + `size` 必须能读**（`ContentPart.swift:99-144`, `:185-194`）。

---

## 9. 迁移判定表

工作量粗估单位：**人天**（1 人天 = 1 名熟练 Android 工程师 1 个有效工作日）。含实现 + 单元测试，不含联调与设备矩阵测试。总计按路线 (b) 估算。

### 9.1 数据与模型层（平台无关，可直译）

| 功能点 | 判定 | 理由 | 人天 |
|---|---|---|---|
| `ContentDocument` / `ContentPart` / `TextRun` 数据类 | **自然迁移** | 纯 Kotlin `@Serializable` / `data class`；字段在 `ContentPart.swift:61-115` 已列全 | 0.5 |
| v2 信封解析 + `sortedKeys` / `withoutEscapingSlashes` 序列化 | **自然迁移** | kotlinx.serialization 需自定义 serializer 保序；或用 `JsonObject` + 手动排序 | 1.0 |
| v1 兼容解码（裸数组 / `type` 键 / HTML 名 / 每 run `size`） | **需重新设计** | 两级降级解析（`ContentPart.swift:185-194`）在 kotlinx.serialization 里不直观；建议手写 `JsonElement` 解析 | 1.5 |
| `ContentPartStyle` 常量 + `normalized` 映射表 | **自然迁移** | 纯字符串映射（`ContentPart.swift:132-144`） | 0.25 |
| `ContentFlatten`（`flattenPart` / `flattenContent` / `parseContent` / `serializeContent`） | **自然迁移** | 纯函数 | 0.5 |
| `ImagePathUtil`（`normalizeSrcKey` / `normalizeContent` / `resolveImagePath` / `collectImageSrcs`） | **自然迁移** | 路径逻辑用 `java.io.File` 等价替换（`ContentPart.swift:208-241`） | 0.75 |
| `ContentPartCache`（LRU 300，`NSLock`） | **自然迁移** | 换 `LruCache` / 加锁 HashMap（`MediaStore.swift:7-43`） | 0.5 |
| **v1→v2 迁移 + `contentFormatVersion` 一次性开关** | **需重新设计** | Android 侧是全新安装（无 v1 数据）→ **可降为「只保留读取能力」**；但若要从 iOS 备份导入则必须完整（`editor-typography.md:107-109`） | 1.0 |
| **ContentFormatTests 全套移植** | **自然迁移** | 12 档字号往返、v1 升级、flag 省略、未知 style、空/损坏 JSON——逐条对应 `ContentFormatTests.swift:48-166` | 2.0 |

**小计 ≈ 8.0 人天**

### 9.2 编辑器内核（路线 b）

| 功能点 | 判定 | 理由 | 人天 |
|---|---|---|---|
| `EditorBlockStyle` / `EditorDesignSize`（28/22/17/15、行距 0.13/0.5、段后距 0.4/0.3/0.4/0） | **自然迁移** | 纯常量与计算属性（`RichTextEngine.swift:19-109`） | 0.5 |
| `DynamicTypeMetrics` 等价物（`fontScale` + 每样式上限 + 单调性） | **需重新设计** | `UIFontMetrics` 无等价物，需按 §8.5 自建；必须写单调性单测（`DesignSystem.swift:124-196`） | 1.5 |
| 自定义 span：块类型 + 设计字号（承载编辑器内部元数据） | **需重新设计** | iOS 用 `NSAttributedString.Key`（`RichTextEngine.swift:117-120`）；Android 应改为自定义 `ParagraphStyle`/`CharacterStyle` 子类，**架构上更干净但需新设计** | 1.5 |
| 文本容器桥接（`AndroidView` + `EditText`） | **需重新设计** | iOS 是 `UIViewRepresentable`（`RichTextView.swift:72-141`）；Android 要处理 Compose↔View 的尺寸/滚动/焦点同步 | 2.5 |
| placeholder（含随字号缩放、最小高 160pt × multiplier） | **自然迁移** | `PlaceholderTextView`（`RichTextView.swift:4-70`）逻辑可直译；`minimumHeight` 缩放见 `:29` | 0.75 |
| 段落边界计算（`paragraphRange` / `paragraphRanges` / `paragraphIsEmpty`） | **需重新设计** | iOS 有 3 个特判（尾随换行零宽、空段零宽、不加 1），是多次 bug 修复沉淀（`RichTextEngine.swift:606-651`）；Android 需重新验证 | 2.0 |
| 段落样式应用（`applyBlockStyle` / `restyle`，含删 marker + 保留 bold/italic + cancelCenter） | **需重新设计** | 结构可对照，但 span 增删语义与 `NSAttributedString` 不同；**必须从后往前**（`:330-333`） | 2.5 |
| 引用（底色 + 强制左对齐 + 15pt） | **自然迁移** | `BackgroundColorSpan` + `AlignmentSpan`（`:390-402`） | 0.75 |
| 居中（与 list/quote/todo 互斥） | **自然迁移** | `AlignmentSpan.Standard`（`:434-500`） | 0.75 |
| 列表 / 待办（`MarkerAttachment` → `ImageSpan`，可切换、互斥、重置 typing） | **需第三方库/自建** | Android 无 attachment，用 `ImageSpan` + 自定义 payload（`RichTextEngine.swift:519-567`, `:776-800`） | 2.0 |
| 行内样式：粗体 / 斜体（含 **CJK 斜切 0.24**） | **自然迁移** | `StyleSpan` + `TextPaint.textSkewX`（iOS 用 `CGAffineTransform c:0.24`，`:277-286`） | 1.0 |
| 行内样式：删除线 / 下划线 | **自然迁移** | `StrikethroughSpan` / `UnderlineSpan`（`:288-312`） | 0.5 |
| `activeStyles()` / `isCenterActive()` / `isListActive()` / `isQuoteActive()` / `isTodoActive()` / `currentBlockStyle()` | **需重新设计** | 每个都有「有选区 vs 空选区（typing）」「空段特判」两套逻辑（`:343-362`, `:483-500`, `:585-599`, `:653-673`）；Android 无 typingAttributes，需自建「下一字符属性」 | 2.5 |
| **`typingAttributes` 等价物** | **需重新设计** | Android 唯一的真空白区（§8.2）。方案：零宽 span 继承 或 `TextWatcher` 主动补 span | 2.0 |
| 空段禁止连续回车（居中态） | **自然迁移** | `InputFilter` 替换 delegate `shouldChangeTextIn`（`RichTextView.swift:153-164`） | 0.5 |
| **IME composition 保护** | **需重新设计** | iOS 无此逻辑；Android 必须新增（§3.4） | 1.0 |
| **撤销 / 重做（`UndoManager`）** | **自然迁移（且是增强）** | iOS 完全没有（§2.5）；Android 挂 `UndoManager`。注意必须改掉 iOS 的全量替换写法 | 1.0 |
| 「放弃修改」二次确认 + `editingOriginalParts` 快照 | **自然迁移** | `DiaryViewModel.swift:196-209` 逻辑直译 | 0.5 |

**小计 ≈ 26.5 人天**

### 9.3 `PartsCodec` 双向映射

| 功能点 | 判定 | 理由 | 人天 |
|---|---|---|---|
| `attributedString(from:)` → `buildSpannable(parts)` | **需重新设计** | 结构可照搬（`RichTextEngine.swift:803-910`），但图片/marker 从 `NSTextAttachment` 改 `ImageSpan`，段落属性从 `NSParagraphStyle` 改多 span 组合 | 2.0 |
| `parts(from:)` → `parseParts(spannable)` | **需重新设计** | 手写 `\n` 扫描 + attachment 判定 + run 切分（`:912-1006`）；Android 用 `getSpans` 但「连续同属性区间」需自己归一化 | 2.5 |
| 图片尺寸自适应（`min(storedW, maxW)`、`maxPixel × 3`、阅读端 chunk 拆分） | **需重新设计** | 编辑器用 attachment、阅读端用独立图片视图（iOS 分裂成两套，`:819-835` vs `MediaViews.swift:81-101`）；Android 建议统一为 `ImageSpan` + 可点击 | 1.5 |
| 尾随空行丢弃 / `done` 对齐 / list 合并 | **需重新设计** | 三条不变量分散在 `:924`, `:1008-1022`，容易漏 | 1.0 |
| 往返单测（12 档 × 全部 fixture） | **自然迁移** | 直接对应 `ContentFormatTests.swift:124-158` | 1.5 |

**小计 ≈ 8.5 人天**

### 9.4 阅读态 / 展示

| 功能点 | 判定 | 理由 | 人天 |
|---|---|---|---|
| `[ContentPart]` → Compose `Text(AnnotatedString)` 渲染 | **需重新设计（但要简化）** | iOS 的 `ReadTextView`（`ReadTextView.swift` 166 行）是完整的 UIKit 桥接；Android **不需要**——直接 `AnnotatedString` + `Text` 即可，**反而减少一层** | 2.0 |
| 图片展示 + 点击预览（`DiaryImageView` / `ImagePreviewView`） | **自然迁移** | Compose `Image` + `AsyncImage`/`rememberAsyncImagePainter`；预览用 `Dialog`/`fullScreenCover` 等价物（`MediaViews.swift:13-44`, `:139-180`） | 1.5 |
| 待办点击勾选（行级命中 + 回写 `done[]`） | **需重新设计** | iOS 用 `closestPosition(to:)`（`ReadTextView.swift:140-159`）；Android 用 `getOffsetForPosition` 或 Compose `ClickableText` / `TextLayoutResult.getOffsetForPosition` | 1.5 |
| 搜索高亮（按 run 自身 designSize 解析，不重置标题字号） | **需重新设计** | iOS 有一段专门的修复（`ReadTextView.swift:113-138`）；Android 要保证高亮不覆盖字号 | 1.0 |

**小计 ≈ 6.0 人天**

### 9.5 图片 / 媒体

| 功能点 | 判定 | 理由 | 人天 |
|---|---|---|---|
| 采集：`PHPickerViewController` → `PickVisualMedia` / Photo Picker | **自然迁移** | `ActivityResultContracts.PickVisualMedia` 是直接对应（`MediaViews.swift:104-137`）；免权限 | 0.75 |
| 降采样（`maxPixel = 2048`，ImageIO thumbnail 路径） | **需第三方库** | `kCGImageSourceThumbnailMaxPixelSize` 无平台等价物；用 **Coil** / **Glide** 的 `downsample`，或 `BitmapFactory.Options.inSampleSize` 手写（Android 的 2 的幂采样精度不如 ImageIO） | 1.5 |
| 压缩（JPEG 0.85）+ 命名 `img_<epochMillis>.jpg` + `images/` 目录 | **自然迁移** | `Bitmap.compress(JPEG, 85, ...)`（`DiaryViewModel.swift:488-497`） | 0.75 |
| 渲染尺寸计算 + 圆角合成（`Radius.image`）+ `NSCache` → `LruCache` | **自然迁移** | `DiaryImageStore`（`MediaStore.swift:47-98`）；圆角换 `RoundedBitmapDrawable` 或 Compose `clip(RoundedCornerShape)` | 1.0 |
| **导出长图 `ShareRenderer`（458 行，独立 CoreGraphics 管线）** | **需重新设计** | 硬编码 720/108/48/61/3/64/34/564 常量 + 每样式字号 36/32/31/30 + 自研逐字符换行（`ImageShareService.swift:9-199`）；Android 用 `Canvas` + `StaticLayout` 重写，**常量必须逐项对齐** | 4.0 |
| 分享出口（`ShareLink` → `Intent.ACTION_SEND` + `FileProvider`） | **自然迁移** | `DiaryPageView.swift:53-60` | 0.75 |

**小计 ≈ 8.75 人天**

### 9.6 UI / 交互 / 平台集成

| 功能点 | 判定 | 理由 | 人天 |
|---|---|---|---|
| 格式栏（9 个按钮 + 2 个分隔 + 样式 Menu + 横屏竖排） | **自然迁移** | `FontToolbar`（`RichTextView.swift:196-337`）逐条对应；`fontToolbarVertical` environment → 横竖屏断点 | 2.0 |
| 顶栏（返回 / 搜索 / 编辑 / 分享 / 插图 / 放弃 / 保存 / 多选删除） | **自然迁移** | `DiaryPageView.swift:183-230` | 1.5 |
| 键盘避让（高度 + 动画曲线还原 + 自动滚动） | **需重新设计** | iOS 监听 `keyboardWillChangeFrameNotification` 手动算（`DiaryPageView.swift:100-116`）；Android 用 `WindowInsets.ime` + `imeNestedScroll`（**更简单，但语义不同**） | 1.5 |
| 焦点管理（autoFocus 延迟 0.25s + 点工具栏回焦） | **自然迁移** | `RichTextView.swift:106-110` + `DiaryPageView.swift:17-19` | 0.5 |
| 位置（写日记时取一次、只可调精度、不可重取） | **需重新设计** | 规则本体是纯业务（`docs/location-recording.md:11-19`），但 Android 需重写定位 + 逆地理（`FusedLocationProvider` + `Geocoder`，与 `CLGeocoder`/MapKit 结果不同） | 3.0 |
| 自动保存 / 草稿（fontScale 变化导致 Activity recreate） | **需重新设计** | Android 特有；iOS 由 SwiftUI 状态天然持有（见 §8.5） | 1.0 |
| 设置项（自动定位 / 允许编辑历史 / 夜间 / 语言） | **自然迁移** | `SettingsStore` → `SharedPreferences` / `DataStore` | 1.0 |

**小计 ≈ 10.5 人天**

### 9.7 汇总

| 模块 | 人天 |
|---|---|
| 9.1 数据与模型层 | 8.0 |
| 9.2 编辑器内核（路线 b） | 26.5 |
| 9.3 `PartsCodec` 双向映射 | 8.5 |
| 9.4 阅读态 / 展示 | 6.0 |
| 9.5 图片 / 媒体 | 8.75 |
| 9.6 UI / 交互 / 平台集成 | 10.5 |
| **合计** | **≈ 68.25 人天** |
| 缓冲（+20%，含设备矩阵 / IME 实测 / 备份互导验证） | **≈ 82 人天** |

**若要改用路线 (a)（纯 Compose）**：9.2 的 26.5 人天中，「`typingAttributes` 等价物」「段落边界」「样式应用」会被「偏移 ⇄ 语义模型双向映射 + 增量重算」替换，叠加 `OutputTransformation` 每键 O(n) 的优化工作，**净增 ≈ 8–12 人天且不确定性更高**；同时 IME 与性能两个高风险项需要早期 POC 验证（建议 **≥3 人天的 spike** 才能给出可信估算）。

**判定分布统计**：自然迁移 24 项 / 需第三方库 1 项（图片降采样）/ 需重新设计 17 项。**"自然迁移"占比 ~57%，且集中在最核心的语义模型 + 格式常量 + 交互契约上**——这正是路线 (b) 可信度高的原因。

### 9.8 建议的实施顺序

1. **第 1 步（无 UI，可立即验证）**：9.1 全部 + 9.3 的往返单测。用 iOS 端的真实 `content_json` 样本作 golden file，先证明**字节级兼容**（含 `sortedKeys` 排序）。这一步不依赖任何 UI 决策，且是后续所有工作的正确性基线。
2. **第 2 步（POC，3 人天）**：路线 (a) vs (b) 的 spike——同一个最小需求（**中文 IME 输入 + 选区加粗 + 段落级样式切换 + 100KB 文本性能**）在两条路上各实现一遍。**这是唯一能消除估算不确定性的手段**，尤其针对路线 (a) 的 `ComposeFoundationFlags` 行为。
3. **第 3 步**：按选定的路线做 9.2，优先打通「块类型显式存储 + 字号不落库」这条不变量，并立刻用 §9.1 的 golden file 做端到端往返断言。
4. **第 4 步**：9.4 / 9.5（阅读态与媒体，独立性强，可并行）。
5. **第 5 步**：9.6（平台集成），其中**位置**与**导出长图**是两个可独立分包的大块。
6. **贯穿全程**：把 §8.6 的 12 条暗坑做成 code review checklist + 单测，每条至少一个用例。

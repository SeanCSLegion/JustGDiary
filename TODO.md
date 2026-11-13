# JustGDiary Android · TODO / 待办清单

> 审计日期：**2026-11-12**；P1 收口日期：**2026-11-13**
> 代码基线：`main` @ `1f23c52`（R34/R35 已提交；P1 改动在 R36 工作树）
> 验证：`./gradlew clean assembleDebug testDebugUnitTest` → **636 / 636 通过**（61 个测试类，0 失败）

这份清单只列**当前还没做**的事情，已经完成的历史项不重复。每条尽量给出：
`优先级 · 问题 · 证据（文件:行） · 建议动作 · 验收方式`。

优先级约定：

| 级别 | 含义 |
| --- | --- |
| **P0** | 会丢用户内容、会恢复出坏数据、或阻断正常使用 |
| **P1** | 功能缺失 / 与 iOS 明显不一致 / 高频体验问题 |
| **P2** | 性能、可访问性、工程化、可维护性 |
| **P3** | 可选增强，需要产品拍板后再做 |

---

## 0. 本轮结论（先看这几条）

1. **Android 功能面已经完整**：首页三态 / 日记阅读·编辑·多选·分享 / 搜索 / 足迹 / 设置 / 备份互导都已落地，636 条 JVM 测试全绿。当前不是“缺大功能”，而是**数据安全、平台体验和长期可维护性**的收口。
2. **P0-0 已修复**：一份构造过的 `.jdiary` 曾可以把文件写到 `images/` 之外，甚至删掉数据库 / 设置；现在 `src`/`file` 非法即整包拒绝。
3. **P0-1 已修复**：编辑器内容过去只存在 Compose 的 `remember` 里，Activity 重建或进程被回收就会丢；现在有磁盘草稿 + `ON_STOP` 落盘，已在模拟器验证。
4. **P0-2 已修复**：`allowBackup` 已关闭，备份只走设置页 `.jdiary`，不会再出现“系统恢复半套数据”。
5. **P0-5 已修复**：首页“今天”现在遵循 `day_start_hour`；月态逐日无障碍节点也在 P1-3 补齐（仅无障碍开启时挂）。
6. **iOS 最近 17 个提交里需要跟的 3 件事已全部落地**：图片按原分辨率落盘、年历两个小几何、日历无障碍；引用回车也已按“一律跟随 iOS”改回续引用（P1-4）。P1-7 / P1-9 / P1-11 / P1-13 / P1-14 / P1-15 / P1-16 同轮完成，详见 `docs/migration-progress.md` R36。
7. **P2 数据/性能小问题已收口（R38）**：P2-6b 全部、P2-5 孤儿图片/存储用量、编辑 vs 阅读首条像素基线已完成。
8. **后续只剩两件大改**：P2-3 morph 性能与 P2-6 大文件拆分（各自需要单独一轮测量/重构）；P2-11/12/13 无障碍与真机走查暂不做。发布链路已在 R37 准备好。
9. **发布链路已准备（R37）**：版本号走 `version.properties`、release 签名可注入、R8/资源压缩已开、CI 与 `androidTest` 已落地；签名 release APK 4.1MB / AAB 6.0MB 已冒烟。剩下的是真实签名 secrets、Play Console 上架与可选的 baseline profile / macrobenchmark。

> 用户可见行为的现状稿见 [`docs/app-current-state.md`](docs/app-current-state.md)（随每次行为变更更新）。

---

## 0.1 已确认的产品口径（2026-11-12）

| 决策 | 结论 |
| --- | --- |
| Auto Backup | **关掉**，只用设置页导出的 `.jdiary`；**不加**备份时间/超期提醒 |
| 引用回车 | **一律跟随 iOS**：当前 iOS 是非空引用行回车续引用、空引用行再回车结束；Android 按此改，以后 iOS 改也继续跟 |
| 图片存储 | **跟 iOS：原分辨率 + q0.95，只留方向**；不保留 PNG/透明，不额外设分辨率上限 |
| 月历无障碍 | **仅 TalkBack 开启时**挂逐日语义节点；正常模式保持 Canvas 性能 |
| 通知权限 | **首次启动申请**通知权限并说明用途；拒绝后设置页保留入口，不反复弹 |
| 删除撤销 | **不做** 5 秒撤销；保留删除前确认框 |
| 编辑器撤销/重做 | **不做**；删掉 `EditorHistory` 及相关死代码 |
| 导入换日时刻 | **提示用户确认是否重算**现有日记，不静默采用/丢弃 |
| 可选功能 | 本轮只做**应用快捷方式**；分享入口、相机、多图、小组件、搜索历史暂不做 |
| 恶意备份 | 非法图片路径**整包拒绝并明确报错**，不做清洗后继续导入 |
| 语言/主题切换 | **主题不重建；语言保留官方重建但消除黑帧**，两者都不丢草稿 |
| 首页“写日记” | **先进入阅读态**，再由阅读态顶栏铅笔 / 空态按钮进入编辑；与 iOS `openDiary` 一致 |

> **P0-0 … P0-5 已在 2026-11-12 的 R34 全部修复；P1 可执行项已在 2026-11-13 的 R36 全部落地；P2-1/2/4/7/8/9/10 已在 R37 收口**（P1-8 / P1-10 按已确认口径不做）。当前 **636 条 JVM 测试全绿**（61 个测试类），并在 Pixel_10_Pro 模拟器上验证了草稿恢复、原图落盘、月历无障碍与通知/快捷方式入口。

---

## 1. P0 · 数据安全与正确性

### [x] P0-0 备份导入的图片路径穿越：可覆盖 / 删除数据库与设置

**状态：已修复（2026-11-12，R34）** —— `ImagePathUtil` 只保留安全 basename；`BackupService` 校验 manifest 的 `src`/`file`；`DiaryRepository` 导入前扫描备份库里的全部 `content_json.src`；非法路径整包拒绝。新增 `BackupSecurityRobolectricTest`（3 条）与 `ImagePathUtilTest`（5 条）。

> **这是本轮最严重的一条，建议先于所有功能项处理。**

**问题**：导入 `.jdiary` 时，`manifest.json` 里的 `images[].file` 被当成“文件后缀”拼进新文件名，`extractTo` 对目标路径没有做 canonical containment；同时 `content_json.src` 的 `images/...` 前缀会被 `normalizeSrcKey` 原样保留，后续 `resolveImagePath(src).delete()` 可以落到 `images/` 之外。

**证据**：

```kotlin
// app/src/main/java/com/cov/justdiary/service/BackupService.kt:187-197
val ext = entryName?.substringAfterLast('.', "")
val newName = "imp_${stamp}_${importedImages + 1}.$ext"
zip.extractTo(entryName, File(imagesDir, newName))

// app/src/main/java/com/cov/justdiary/service/ZipArchive.kt:111-115
fun extractTo(name: String, target: File): Boolean {
    val entry = zip.getEntry(name) ?: return false
    target.parentFile?.mkdirs()
    FileOutputStream(target).buffered(BUFFER_SIZE).use { out -> copyVerified(entry, out) }
    return true
}

// app/src/main/java/com/cov/justdiary/model/ContentPart.kt:112-115
fun normalizeSrcKey(src: String): String {
    if (src.startsWith(IMAGE_DIR_PREFIX)) return src   // "images/../../databases/..." 原样返回
    ...
}

// app/src/main/java/com/cov/justdiary/data/DiaryRepository.kt:583-584 / :602
fun resolveImagePath(src: String): File =
    File(imagesDir, ImagePathUtil.normalizeSrcKey(src).removePrefix(IMAGE_DIR_PREFIX))
...
resolveImagePath(src).delete()
```

**攻击面**：
- `ext` 里带 `/` 时（例如 manifest 的 `file = "x.jpg/../../databases/just_diary"`），`newName` 会变成 `imp_xxx_1.jpg/../../databases/just_diary`，`File(imagesDir, newName)` 直接写出 `images/`；
- 即使不写文件，只把 `src = "images/../../databases/just_diary.db"` 留在 `content_json`，之后编辑/删除该片段，`resolveImagePath(...).delete()` 就会删到数据库；
- `collectImageRefs` 导出时也会 `source.copyTo(target)`，恶意 `src` 可把应用私有文件（如 `shared_prefs`）复制进备份包，造成信息泄露；
- 这类 `.jdiary` 可以来自任何来源（聊天、网盘、邮件），用户只要点一次“导入”。

**已确认（2026-11-12）**：发现任何非法图片路径（`../`、绝对路径、非法后缀、NUL 等）时**整包拒绝并明确报错**，不做“清洗后继续导入”。

**建议动作**：
1. 导入时**只信自增序号**：`newName = "imp_<stamp>_<n>.<ext>"`，`ext` 只接受白名单 `jpg|jpeg|png|webp|heic`；`entryName` 只用于 `zip.getEntry`，不参与目标路径。
2. 目标路径必须 `canonicalPath.startsWith(imagesDir.canonicalPath + File.separator)`；否则拒绝整个包并报 `InvalidManifest`。
3. 校验 manifest 里的每个 `src`：必须以 `images/` 开头、basename 不含 `/`、`\`、`..`、NUL；不符合的包拒绝（不要“修一修继续导”）。
4. `normalizeSrcKey` 对任何输入一律 `substringAfterLast('/')` 后再拼 `images/`；`resolveImagePath` 再做一次 containment 断言。
5. `ZipArchive.extractTo` 本身也加 containment（防御性，不只依赖 BackupService）。
6. 补恶意 zip 测试：`file` 带 `../`、`src` 带 `../`、绝对路径、NUL、大小写绕过；断言导入失败或只落在 `images/`。

**验收**：构造包含 `images[].file = "a.jpg/../../databases/just_diary"` 与 `src = "images/../../shared_prefs/just_diary_settings.xml"` 的 `.jdiary`，导入后数据库/设置文件字节不变，`filesDir/images` 之外没有新文件；`ImagePathUtil` 单测覆盖 `../` 归一。

---

### [x] P0-1 编辑器草稿持久化：重建 / 进程回收不丢未保存内容

**状态：已修复（2026-11-12，R34）** —— 新增 `EditorDraftStore`；`DiaryPageViewModel` 按 `dayKey#blockId` 保存/恢复/清理，`EditorScreen` 恢复内容与选区、250ms 防抖上报、`ON_STOP` 同步落盘；Activity 重建时不再重复 `open()`。新增 `EditorDraftStoreTest`（5 条）、`DiaryPageStateTest` 草稿用例（3 条）；已在 Pixel_10_Pro 模拟器上验证“切深色”和“force-stop 后重进”都能恢复 `draft-p0-1`。

**问题**：编辑中的 `EditorTextState` 只活在组合期：

```kotlin
// app/src/main/java/com/cov/justdiary/ui/diary/EditorScreen.kt:136-139
val initialLines = remember(loadToken) { EditorModel.partsToLines(initialParts) }
val state = remember(loadToken) { EditorTextState(initialLines) }
val history = remember(loadToken) { EditorHistory(initial = initialLines) }
```

没有 `rememberSaveable`、没有 `SavedStateHandle`、没有草稿文件。以下场景都会把正在写的内容清空：

- 系统深色/浅色切换、系统字号变化、分屏/窗口尺寸变化；
- 应用内语言切换（`AppCompatDelegate.setApplicationLocales` 会重建 Activity）；
- 后台被系统回收后重新进入。

**证据**：
- `EditorScreen.kt:136-139`（状态只在 `remember`）
- `docs/android-migration-plan.md:1006` 风险表已点名：`Activity 重建导致草稿丢失 | SavedStateHandle + 编辑器草稿持久化；旋转与语言切换专项测试`
- `docs/android-design/README.md:120` 也把“语言/主题切换不重建整棵树”列为硬约束

**建议动作**（按成本从低到高，至少做前两项）：

1. 把编辑器“当前快照”（`content_json` + 光标/选区 + `startTimeUtc` + 是否新建）提到 `DiaryPageViewModel`，配置变更时 ViewModel 存活，内容不丢。
2. 再把快照以 `dayKey + editingBlockId` 为键写入 `SavedStateHandle` 或私有草稿文件（`filesDir/drafts/`），进程死亡后可恢复；恢复时显示“恢复未保存的草稿？”。
3. 保存 / 放弃 / 正常退出时清掉草稿；草稿与当前库内容不一致时不要静默覆盖。
4. 为“转屏 / 深色 / 字号 / 语言 / 进程死亡”补 Compose + Robolectric 用例。

**验收**：
- 编辑态下切换系统深色、改字号、切语言、进入分屏，文字、光标、列表/待办/图片都还在；
- `adb shell am kill` 后重进同一天，弹出草稿恢复且内容一致；
- 放弃修改后草稿被清除，不会下次又冒出来。

---

### [x] P0-2 收口 Android Auto Backup：不要让系统恢复“半套数据”

**状态：已修复（2026-11-12，R34）** —— `AndroidManifest.xml` 改 `android:allowBackup="false"`；构建产物已用 `aapt2 dump xmltree` 确认。

**问题**：Manifest 只写了 `android:allowBackup="true"`，没有 `android:dataExtractionRules`、没有 `android:fullBackupContent`：

```xml
<!-- app/src/main/AndroidManifest.xml:14 -->
android:allowBackup="true"
```

Android Auto Backup 的配额约 25MB，而本应用的数据库 + `filesDir/images` 里的照片很容易超过。现在的状态可能是：

- 数据库被备份了、图片没备份 → 恢复后有日记但图片全裂；
- 或整体备份失败，用户以为“系统会帮我备份”；
- 设备到设备迁移时同样存在这个半套问题。

**已确认（2026-11-12）**：**关掉 Auto Backup，只用 `.jdiary`**。

**动作**：
- Manifest 改 `android:allowBackup="false"`（如果担心设备到设备迁移，可同时声明空的 `dataExtractionRules` 并明确注释“只走应用内导出”）；
- **不加“上次备份时间 / 超期提醒”**（2026-11-12 用户确认）；只保留设置页导出入口；
- 文档明确：换机/恢复只支持设置页导出的 `.jdiary`，不要依赖系统云备份。

**验收**：
- `adb shell bmgr backupnow com.cov.justdiary` 不再产出本应用数据（或明确被排除）；
- 卸载重装后，用户按设置页提示导入 `.jdiary` 能完整恢复（含图片）；
- 设置页**没有**备份时间/提醒入口（按本次决策）。

---

### [x] P0-3 导入备份后的设置恢复不完整，且旧备份会重置新增设置

**状态：已修复（2026-11-12，R34）** —— `AppSettingsJson.toSettings(current, presentKeys)` 保留旧备份缺失键；`BackupService` 只返回 `restoredSettings`、不再直接写设置；`AppSettingsApplier.applyImported` 应用主题/语言/提醒；换日时刻变化时 `SettingsViewModel` 先弹确认再重算。新增 `BackupModelsTest` 缺键用例、`SettingsImportRobolectricTest`。

**问题 A：设置没有走副作用执行器**。`BackupService.importBackup` 直接写 `SettingsStore`：

```kotlin
// app/src/main/java/com/cov/justdiary/service/BackupService.kt:204-210
if (manifest.settingsJson.isNotEmpty()) {
    val parsed = json.decodeFromString(AppSettingsJson.serializer(), manifest.settingsJson)
    if (parsed != null) {
        settings.save(parsed.toSettings(settings.load()))
        stats = stats.copy(settingsRestored = true)
    }
}
```

`SettingsViewModel.runImport` 只做了 `settings.load()` + `reminderScheduler.rearm()`（`SettingsViewModel.kt:355-362`），**没有**执行 `AppSettingsApplier` 里的：

- `applyLanguage`（应用内语言要重启才生效）；
- `applyNightMode`（窗口/系统栏仍是旧主题）；
- `RecomputeDayKeys`（换日时刻变了，现有未覆盖日记的归属日没有按新规则处理）；
- `BackfillRegions` / 权限副作用。

**问题 B：旧备份会把新增的两个精度设置重置**。`AppSettingsJson` 的 12 个字段都有默认值；一份只含 10 个键的旧备份反序列化后，`share_loc_precision` 会变成 `district`、`default_loc_precision` 会变成 `auto`，覆盖用户当前值：

```kotlin
// app/src/main/java/com/cov/justdiary/model/AppSettingsJson.kt:16-25
@SerialName("share_loc_precision") val shareLocPrecision: String = LocPrecision.district,
@SerialName("default_loc_precision") val defaultLocPrecision: String = LocPrecision.auto,
```

**已确认（2026-11-12）**：换日时刻与当前不同时，**导入后提示并让用户确认是否重算现有日记**；不要静默采用、也不要静默丢弃。

**建议动作**：
1. 让 `BackupService` 返回“解析到的设置”而不是自己 `save`；由 `SettingsViewModel` 调 `applier.commit(old, restored)`（或新增 `adoptRestored`），至少执行语言 / 夜间模式 / 提醒重排；换日时刻变化时按现有规则弹“会移动 N 篇，是否继续”。
2. `AppSettingsJson` 改成能区分“键缺失”和“显式默认值”（`String?` / `JsonObject` 读取），缺失键保留当前值，不重置。
3. 补测试：只含 10 键的旧 JSON → 两个精度键保持当前值；导入含不同 `app_language` / `theme_mode` / `day_start_hour` 的备份 → 界面立刻生效且提醒重排；`day_start_hour` 变化时出现“会移动 N 篇”的确认框。

**验收**：
- 导入一份 iOS 旧备份（10 键），当前 `share_loc_precision=province` 不被改成 `district`；
- 导入后语言、主题立即变化，不需要杀进程；
- 换日时刻变化时出现确认框，确认后 `day_key` 与首页/搜索一致。

---

### [x] P0-4 首页“选中日”加载存在竞态：可能显示上一天的片段

**状态：已修复（2026-11-12，R34）** —— `HomeViewModel` 增加 `dayLoadGeneration`，`select`/`reload` 每次挂起返回后校验代际与 key；新增 `HomeViewModelTest` 乱序返回用例（A 晚于 B 返回不覆盖 B）。

**问题**：`HomeViewModel.select()` 与 `reloadDay()` 是多个独立协程，没有代际号 / 取消：

```kotlin
// app/src/main/java/com/cov/justdiary/ui/home/HomeViewModel.kt:86-90
fun select(dayKey: String) {
    if (dayKey == _state.value.selectedKey) return
    _state.value = _state.value.copy(selectedKey = dayKey)
    viewModelScope.launch { reloadDay() }
}

// app/src/main/java/com/cov/justdiary/ui/home/HomeViewModel.kt:106-127
private suspend fun reloadDay() {
    val key = _state.value.selectedKey
    val diary = ...getDiaryByDay(key)...
    val blocks = if (diary == null) null else ...getBlocks(diary.id)...
    _state.value = _state.value.copy(dayBlocks = blocks)
}
```

快速连点两天（或 `refreshSettings` 与 `select` 交错）时，旧请求可能在两次 `suspend` 之间晚回来，把 `dayBlocks` 写成前一天的内容，而 `selectedKey` 已经是新的一天。

**建议动作**：给 `HomeViewModel` 加 `loadGeneration: Int`（或 `selectedJob?.cancel()` + 代际检查），在每次 suspend 返回后确认仍是当前代际才写回；`FootprintViewModel.reload()` 与 `DiaryPageViewModel.reload()` 也建议同模式。

**验收**：用 `StandardTestDispatcher` + 可控延迟的 fake repository 写一条竞态测试：先 `select(A)` 再 `select(B)`，让 A 的 `getBlocks` 晚于 B 完成，最终 `dayBlocks` 必须是 B 的。

---

### [x] P0-5 首页“今天”忽略 `day_start_hour`，与日记页口径不一致

**状态：已修复（2026-11-12，R34）** —— `HomeViewModel` 的 `today` 改用 `DateUtil.dayKeyForUtc(now, dayStartHour)`，回前台/设置变化重算，`HomeScreen` 只在“选中的是旧的今天”时跟随；新增 `HomeViewModelTest` 凌晨 01:00 用例。

**问题**：首页用 `LocalDate.now()` 取“今天”，日记页用 `dayKeyForUtc(now, dayStartHour)`：

```kotlin
// app/src/main/java/com/cov/justdiary/ui/home/HomeViewModel.kt:45-57
val today = LocalDate.now()
val monthKey = CalendarGrid.monthKeyOf(today)
_state.value = State(today = today, selectedKey = DateUtil.dayKeyOf(today), ...)

// app/src/main/java/com/cov/justdiary/ui/diary/DiaryPageViewModel.kt:209-213
val today = DateUtil.dayKeyForUtc(System.currentTimeMillis(), settings.dayStartHour)
```

`day_start_hour` 默认是 **4**。在 00:00–03:59 之间：

- 首页高亮/选中的是“日历今天”（例如 11-13），日记页认为“今天”是 11-12；
- 从首页点“写日记”可能命中 `canEditToday=false`，弹“无法新增编辑块”；
- 即使能写，内容也会落到 11-12，而首页看起来是 11-13 的空白日；
- `State.today` 只在 VM 构造时取一次，跨午夜/回前台不刷新（`AppForeground` 只被日记页消费）。

**建议动作**：
1. `HomeViewModel` 的 `today` 改为 `DateUtil.dayKeyForUtc(now, settings.dayStartHour)` 派生；`refresh()` / `refreshSettings()` / 回前台时重算 `today`、`monthKey`、`selectedKey`（用户没有手动选过日期时）。
2. 给 `HomeViewModel` 注入 `Clock` 便于测试；纯函数 `DateUtil` 已有测试，补“01:00 + dayStartHour=4”的用例。
3. 首页与日记页共用同一个“今天”函数，禁止再出现第二套 `LocalDate.now()`。

**验收**：固定时钟 `2026-11-13T01:00 + dayStartHour=4` → 首页 `todayKey="2026-11-12"`，点“写日记”能进入 11-12 的编辑态；跨午夜后回前台，首页自动切到新的一天；`HomeViewModel` 单测覆盖。

---

## 2. P1 · 与 iOS 最新版同步（2026-10-01 之后）

> iOS 基线：`/Users/cov/JustADiary` @ `bf2ef0a`。`9dca20a..HEAD` 共 17 个提交，逐条读代码后只有下面 3 件必须跟；引用回车是**反向分歧**，见 §2.4。

### [x] P1-1 图片按原分辨率保存（iOS `5594a73`）

**状态：已实现（2026-11-13，R36）** —— `MediaStore` 去掉 2048 降采样、JPEG q0.95；仅 OOM 时回退 2048；`MediaStoreRobolectricTest` 覆盖原尺寸 / 不放大 / 方向 / GPS / 失败不留文件。

**已确认（2026-11-12）**：**跟 iOS：原分辨率 + q0.95，只留方向**；不单独保留 PNG/透明，也不额外设分辨率上限。备份体积变大是接受的代价。

**现状**：Android 插图时先降到 2048、再按 q85 重编码，原图分辨率**永久丢失**：

```kotlin
// app/src/main/java/com/cov/justdiary/service/MediaStore.kt:96-102
if (longest > INSERT_MAX_PIXEL && longest > 0) {
    val scale = INSERT_MAX_PIXEL.toFloat() / longest
    decoder.setTargetSize(...)
}
// :111
bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
// :138-139
const val INSERT_MAX_PIXEL: Int = 2048
const val JPEG_QUALITY: Int = 85
```

iOS 已改成：**按原始像素尺寸**落盘、JPEG **q0.95**、只保留方向（不带 GPS/机型/时间）；显示链路仍然降采样（编辑器/阅读/预览/分享都从落盘字节解到目标尺寸），所以排版和常驻内存不变。

**建议动作**：
1. `importImage` 去掉 `setTargetSize`（保留 `ImageDecoder` 自动应用 EXIF 方向），`JPEG_QUALITY = 95`；删除/改写 `INSERT_MAX_PIXEL`。
2. 大图内存要兜底：全分辨率解码约 `4 bytes/px`（16MP ≈ 64MB，48MP ≈ 192MB）。建议先试全尺寸，`OutOfMemoryError` 时回退 2048 并记 `Log.editor.w`；不要因此把上限重新加回“存储侧”。
3. 显示侧不改：`image(src, maxPixel=1600)` 继续服务编辑/阅读/预览/分享。
4. **不要把原图尺寸写进 `content_json` 的 `w/h`**：Android 现在写的是回读显示图（≤1600）的宽高，`ImageDisplaySize` 只把它当比例；改成原图 px 会与 iOS 的 pt 形状越走越远。
5. 补 `MediaStore` 测试（当前**一条都没有**）：
   - 3000×2000 导入后落盘仍是 3000×2000（旧实现会是 2048×1365）；
   - 800×600 不被放大；
   - 方向被烘进像素（无 orientation 标签也不倒转）+ JPEG 里没有 GPS；
   - 失败路径不留下半张文件。
6. 备份体积会变大（16MP 0.35MB → ~1.8MB），在备份界面/文档里说明，不做静默压缩。
7. **已确认不单独保留 PNG/透明**：跟 iOS 一样统一存 JPEG，截图/贴纸的 alpha 会变黑底，这是已知取舍；不要在本项里偷偷按 MIME 改格式，否则与 iOS 的存储形状分叉。

**验收**：真机插入一张 3000×2000 照片，用 `exiftool`/`ImageDecoder` 验证落盘尺寸与元数据；编辑/阅读/分享三处显示与改动前一致；636 条测试 + 新用例全绿。

---

### [x] P1-2 年历的两个小几何（iOS `64cbcc9` + `87e06f5`，必须同轮）

**状态：已实现（2026-11-13，R36）** —— 圆半径 `0.42 → 0.46`（`CalendarLayout.yearDayCircleRadius` 两处同源）；标记改挂日号行盒底（`yearDiaryMarkTop`）；`CalendarYearGeometryTest` 覆盖。

**现状**：Android 年历的圆和标记**不走 `DayMetrics`**，而是另一套比例：

```kotlin
// app/src/main/java/com/cov/justdiary/ui/home/CalendarCanvas.kt:209
val todayRadius = minOf(cellW, cellH) * 0.42f
// :243-250 选中/今日圆都用它
// :259-267 标记条 y = cy + todayRadius * 0.86f
```

`YearMonthMorph.kt:643` 的迷你端点也是 `0.42`，所以 morph 端点自洽，但年历圆**恒占格宽 84%**，月/周/流约 90.5% —— iOS 用户报的“年历圆偏小”在 Android 同样存在。

标记条则**已经贴着日号**（`0.86r ≈ dayFont/2`），iOS 的“掉在下面”在 Android 看不到；但它的 y 挂在圆半径上，**一旦把 0.42 放大，标记会被一起带远**。

**建议动作**：
1. 把年历圆半径比例从 `0.42` 提到 **0.45~0.465**（0.45 约与 Android 月视图 90.5% 看齐，0.465 约与 iOS 93% 看齐）；**两处同改**：`CalendarCanvas.kt:209`、`YearMonthMorph.kt:643`。
2. 把年历标记的定位**从“圆心 + 0.86r”改成“日号行盒底 + gap”**（年历 gap=0），即 `markTop = cy + dayFont/2 + flagGap`；`CalendarCanvas.kt:261` 与 `YearMonthMorph.kt:722` 同源。这样放大圆不会把标记带远。
3. **不要**照抄 iOS 在 `DayMetrics` 里加 `circleMargin` 字段——Android 年历不消费 `DayMetrics`，加了就是死字段（除非同轮把年历重构成走 `DayMetrics`，不建议夹带）。
4. 补断言（当前年历这两项完全没有测试）：
   - 迷你月圆直径 / 格宽 ≥ 0.9，相邻圆仍有缝；
   - 年历标记顶端 − 日号盒底 ≤ 1dp；
   - 改圆半径不动标记 y；
   - 年↔月 morph 两端与中点连续（无“啪”跳）。

**验收**：402×744 / 412×851 / 360×700 三档截图量直径与标记间距；年↔月 morph 录屏逐帧看收尾不跳；`CalendarLayoutTest` / 新 `CalendarCanvas` 测试全绿。

---

### [x] P1-3 月历无障碍：逐日节点缺失（阻断）+ “前一天 / 后一天”动作（iOS `af82014`）

**状态：已实现（2026-11-13，R36）** —— 无障碍开启时叠 35–42 个透明逐日节点（复用 `CalendarDayCell`），正常模式仍走 Canvas；日历容器补「前一天 / 后一天」；`MonthA11yTest` 覆盖节点数 / 文案 / 点击 / 关闭时 0 节点。

> 这一条比“补两个动作”更严重：当前**月态（默认首页）没有任何逐日无障碍节点**，TalkBack 用户无法选择任何日期。先修节点，再加动作。
>
> **已确认（2026-11-12）**：采用“**仅 TalkBack 开启时挂逐日语义节点**”，正常模式保持 Canvas 性能。

**现状 A：月态没有逐日节点**。`MonthFlow` 为了性能把整月压成了 `Canvas` + 每行一个命中节点：

```kotlin
// app/src/main/java/com/cov/justdiary/ui/home/MonthFlow.kt:1019-1032
// 所以这里把整块压成：一层 Canvas（画全部格子 + 分割线）+ 每行一个命中节点，
// 节点数从 ~300 降到个位数。
// ## 无障碍（已知取舍）
// 这一版不再为每一格建语义节点（那正是慢的来源）。整块仍有一个语义节点、
// 日期与状态仍在无障碍树里，但逐格导航（TalkBack 左右滑动一格一格读）没有了。
```

而 `CalendarDayGrid.kt:383-393` 的**周视图**仍是每格一个 `Role.Button` + `clearAndSetSemantics`，所以周态可读、月态不可读。README 还写着“31 个日格节点”（`README.md:144`），与当前实现不符。

**现状 B：日历容器只有月/年动作**：

```kotlin
// app/src/main/java/com/cov/justdiary/ui/home/HomeScreen.kt:747-752
customActions = listOf(
    CustomAccessibilityAction(a11yPrevMonth) { stepMonth(-1); true },
    CustomAccessibilityAction(a11yNextMonth) { stepMonth(1); true },
    CustomAccessibilityAction(a11yPrevYear) { jumpYears(-1); true },
    CustomAccessibilityAction(a11yNextYear) { jumpYears(1); true },
)
```

没有 `a11y_prev_day` / `a11y_next_day` 文案，也没有“选中日 ±1”的动作。

**建议动作**：
1. **先修 A**：按代码注释里已经给出的方向，只在无障碍开启时挂语义节点（`LocalAccessibilityManager.current?.isEnabled == true`）：
   - 35–42 个透明节点（按当月周数，5 行或 6 行 × 7 格）`clearAndSetSemantics { contentDescription = 日期 + 状态; onClick { ... } }`，`traversalIndex` 按行/列排序；
   - 正常模式仍走 Canvas，避免重新引入 300 个布局节点的卡顿；
   - 节点点击走与 `detectTapGestures` 相同的“只允许当月日期”校验。
2. 再加 B：补 `a11y_prev_day` / `a11y_next_day`（中英），在 `a11yModifier` 里加两条 `CustomAccessibilityAction`，跨月时同步 `monthKey`（`carrySelectionInto` 是现成脚手架，`HomeScreen.kt:433-439`）。
3. **不要**做“整条流合成一个元素”；也**不建议**做 `ProgressBarRangeInfo` 式 adjustable action —— 与“每格一个按钮”的模型打架，收益低。
4. 更新 `MonthFlow.kt:113` 的过期注释（还写着“每一个日期都是真实的 Compose 组件”）和 README 的节点数。
5. 补测试：Compose 语义树里月态有 35–42 个日期节点（无障碍开启时）、每个节点可点击；关闭无障碍时不额外建节点；`a11y_prev_day/next_day` 中英资源存在且方向没反。

**验收**：TalkBack 打开首页月态，能逐格聚焦并朗读“2026年11月13日 · 今天 · 有日记”，双击选中；操作转子出现“前一天/后一天”；跨月时月份标题跟着变；普通模式滚动帧率不回退（对比 `-morph-log`）。

---

### [x] P1-4 引用里回车：**改为跟随 iOS**（新行继续引用）

**状态：已实现（2026-11-13，R36）** —— 非空引用行回车续引用、空引用行结束引用；`EditorEnterKeyTest` / `EditorModelTest` 钉住 `quote,quote,body`；R33 的反向口径已作废。

**已确认（2026-11-12）**：**编辑器逻辑一律跟随 iOS；当前 iOS 是“非空引用行回车续引用，空引用行再回车才结束”，Android 按此改。以后 iOS 改，Android 继续跟。**

| | 现状（改之前） |
| --- | --- |
| **Android** | 非空引用行回车 → **新行回正文**；空引用行回车 → 吃掉按键并结束引用。见 `EditorModel.kt:546-549`、`EditorEnterKeyTest.kt:191` |
| **iOS HEAD** | 非空引用行回车 → **新行继续引用**；空引用行再回车才结束。见 `c019251` / `RichTextEngine.swift` 的 `handleReturn`，UI 测试断言落库 `quote,quote,body` |

**动作**：
1. 改 `EditorModel.repairAfterEnter` 的 quote 分支：上一行是引用且有内容 → 新行继续引用；空引用行 → 保持“吃掉按键 + 本行回正文”。
2. 改写/新增 `EditorEnterKeyTest`：非空引用行回车后两行都是引用；连续两次回车（第二行空）结束引用；落库 `quote,quote,body` 形态。
3. 更新 `docs/android-design/04-diary.md` §3.4 的“回车 · 引用”行、`docs/ios-sync-2026-10-01.md` §1.2、`docs/migration-progress.md` R33 的“iOS 随后按同一口径改”表述。
4. 补“空引用行回车结束引用后光标回到列首 x=0”的断言（iOS `EditorEmptyLineTests` 有，Android 目前只断言空引用行光标在内缩处）。

**验收**：真机/模拟器：引用行输入 `abc` → 回车 → 新行仍是引用（按钮亮、底在、缩进对）→ 直接回车（空行）→ 结束引用、光标回列首；落库解析为 `quote,quote,body`；`EditorEnterKeyTest` / `EditorCursorLayoutTest` 全绿。

> 注意：这是**两端统一**的一步，不要只改 Android 不同步 iOS 的文档；Android 侧以后仍以 iOS 为源。

---

### [x] P1-5 引用底：Android 机制不同，只需补一条等价断言

**状态：已实现（2026-11-13，R36）** —— `EditorQuoteRenderTest` 新增“空引用行 vs 打一个字”的底 top/bottom 逐像素相等。

iOS `B30–B35`（`30aca19` / `7aac4d9` / `902556f` / `de3f27b` / `2a028b7`）修的是 CALayer 装饰层被拉伸、重画晚一拍、隐式动画等；Android 的引用底是**每帧在 `super.onDraw` 之前按 Layout 现画**（`EditorTextArea.kt:357-383`），文本层没有 `LineBackgroundSpan`，结构上不会出现那些闪烁，**不需要照搬代码**。

唯一值得补的：iOS 用两条用例守“空着时的底”与“打一个字之后的底”在同一位置；Android 只断言了“顶部有底、下面没有”（`EditorQuoteRenderTest.kt:274-285`）。补一条像素用例：同一行空着 vs 打一个字，底的 `top/bottom` 逐项相等。

---

## 3. P1 · Android 平台功能补齐

### [x] P1-6 编辑器撤销 / 重做：已确认不做，删掉死代码

**状态：已完成（2026-11-13，R36）** —— 已删除 `EditorHistory.kt` / `EditorHistoryTest.kt`，全仓无引用；脏标记只看 `lines != initialLines`。

**已确认（2026-11-12）**：**不做撤销/重做**。保留“放弃修改”作为唯一回滚路径；把 `EditorHistory.kt`、`EditorHistoryTest.kt` 以及 `EditorScreen.kt:138/610` 里只为它存在的构造与 `canUndo` 判断一起删掉，避免“有实现但没入口”的误导。

**验收**：全仓无 `EditorHistory` 引用；编辑器脏标记仍由 `lines != initialLines` 正确计算；放弃修改/返回拦截测试全绿。

---

### [x] P1-7 提醒通知：直达当天编辑页、专属小图标、动作与文案

**状态：已实现（2026-11-13，R36）** —— 通知/动作直达 `justdiary://editor`（打开时算今天）、白色剪影 `ic_notification`、时区/时间广播重排、首启先说明再申请权限；`LaunchIntentTest` 覆盖深链。

**已确认（2026-11-12）**：**首次启动就申请通知权限并说明用途**；拒绝/永久拒绝时设置页保留入口，不反复弹。

**现状**：点通知只打开应用首页，小图标用的是系统图标：

```kotlin
// app/src/main/java/com/cov/justdiary/service/ReminderScheduler.kt:159-170
val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
...
.setSmallIcon(android.R.drawable.ic_dialog_info)
```

`UNUSED_INTENT_ACTION = "com.cov.justdiary.REMIND"` 是死常量（`:184`）；`notifyReminder` 里也写着“M9 可以带 dayKey 直达当天编辑页”。

**建议动作**：
1. 用 `PendingIntent` 打开 `justdiary://editor`（不写死 `dayKey`，让打开时按当天重新算，跨午夜也正确）；增加“写日记”动作按钮（同一个 PendingIntent）。
2. 新增白色剪影 `ic_notification.xml`（`setSmallIcon(R.drawable.ic_notification)`）。
3. 增加 `TIMEZONE_CHANGED` / `TIME_SET` 广播接收器（`goAsync` 调 `rearm()`）：现在时区变了，WorkManager 的延迟仍是按旧时区算的绝对时间。
4. 首次启动 `remindEnabled` 默认 true 但未授权时，提醒实际不会排（`ReminderScheduler.kt:83-86` 直接返回）：按已确认口径，首启就申请通知权限并说明用途；拒绝后设置页保留入口，不反复弹。

**验收**：通知点击进当天编辑页；动作“写日记”同样；锁屏/免打扰下通知仍可见（权限允许时）；改时区后重新排期到正确的本地时间；通知小图标在白色状态栏下不是方块。

---

### [—] P1-8 从其他应用分享文字 / 图片进日记：本轮未选

**已确认（2026-11-12）**：可选功能只选了“应用快捷方式”，**分享入口本轮不做**。保留原因备查：Manifest 只有 `MAIN` 与 `VIEW justdiary://editor`，没有 `ACTION_SEND`；以后要做时，文字 → 预填当天编辑器、图片 → 先 IO 导入再插行，注意与现有深链共用 `singleTop`。

---

### [x] P1-9 应用快捷方式（长按图标）

**状态：已实现（2026-11-13，R36）** —— `shortcuts.xml` 两个静态快捷方式（写今天 / 搜索日记）+ `justdiary://search`；`AppShortcutsRobolectricTest` 覆盖。

**已确认（2026-11-12）**：可选功能选中的唯一一项，**要做**（两个快捷方式）。

**现状**：没有 `res/xml/shortcuts.xml`，Manifest 也没有 `meta-data android.app.shortcuts`。

**建议动作**：加 2 个静态快捷方式：
- “写今天的日记” → `justdiary://editor`（不写 `dayKey`，打开时算今天）；
- “搜索日记” → 打开应用并切到搜索页（需要一个 `justdiary://search` 深链或 extra）。

**验收**：长按桌面图标出现两项；点击进入正确页面；冷启动/热启动都不产生第二个 Activity 实例。

---

### [—] P1-10 删除撤销：已确认不做

**已确认（2026-11-12）**：保持“删除前确认框”，**不新增 5 秒撤销**。误删仍靠 `.jdiary` 备份恢复；后续如果用户反悔，再单开一项，不要在删除链路里预留半成品 undo 状态。

---

### [x] P1-11 插图失败/耗时要有反馈（相机与多图本轮不做）

**状态：已实现（2026-11-13，R36）** —— 导入中轻量进度（不抢焦点）、失败 Snackbar；回读尺寸移到 IO。

**现状**：`Photo Picker` 选完图后，`onImportImage` 返回 null 就静默 `return`，用户不知道失败；导入大图期间也没有进度：

```kotlin
// app/src/main/java/com/cov/justdiary/ui/diary/EditorScreen.kt:156-160
scope.launch {
    val imported = onImportImage(uri) ?: return@launch
```

**建议动作**：
1. 失败时 Snackbar“图片读取失败，请换一张”；导入中显示轻量进度（不要阻塞键盘）。
2. 相机 / 多图本轮**未选**，见 §9“暂不做”；不要顺手加。
3. 图片导入后 `onImportImage` 里 `container.mediaStore.image(src)` 是主线程解码（`DiaryPageScreen.kt:361`），与 P2-1 一起改成 IO。

**验收**：选损坏图片/不支持格式 → 有明确提示；大图导入不 ANR；不引入相机/多图入口。

---

### [x] P1-12 语言 / 主题切换的黑帧与重建（R5 遗留）

**状态：已改（2026-11-13，R36）** —— 主题不再走 `AppCompatDelegate.setDefaultNightMode`（运行期只更新 Compose + 窗口底/系统栏），冷启动在 `Application` 对齐一次；语言仍走官方 `setApplicationLocales`，起始窗口背景跟随用户主题。黑帧需模拟器录屏复核。

**现状**：语言切换走 `AppCompatDelegate.setApplicationLocales`（`AppSettingsApplier.kt:158-165`），API 33+ 经 `LocaleManager` 会重建 Activity；R5 实测切换瞬间有**一帧纯黑**（`migration-progress.md:1376-1383`）。主题切换走 `AppCompatDelegate.setDefaultNightMode`（`:144-149`），同样可能重建。

**已确认（2026-11-12）**：**主题不重建；语言保留官方重建，但消除黑帧**；两者都不能丢草稿。

**建议动作**：
1. 主题：Compose 已经由 `JustDiaryTheme(themeMode=...)` 驱动，去掉/绕开 `AppCompatDelegate.setDefaultNightMode` 的 Activity 重建，只更新窗口背景与系统栏图标（`enableEdgeToEdge` / `WindowCompat`）。
2. 语言：继续走 `AppCompatDelegate.setApplicationLocales`（官方 per-app language），但把窗口背景/转场做成同色淡入，消掉纯黑帧；**不要**改成 `configChanges` 自行接管语言（成本高、容易与系统机制打架）。
3. 与 P0-1 一起做：重建时草稿、光标、dirty 标记必须恢复；搜索条件/滚动位置尽量靠 `rememberSaveable`/ViewModel 保住。

**验收**：录屏逐帧没有纯黑帧；切换语言后搜索条件、足迹展开态、滚动位置、编辑草稿都还在；系统栏图标颜色正确。

---

### [x] P1-13 读页卡片“删除”无障碍动作是错的

**状态：已实现（2026-11-13，R36）** —— 删除动作改为 `confirmDeleteBlock(id)`：只删一条、弹确认框、不进多选态；`DiaryReadScreenTest` / `DiaryPageStateTest` 覆盖。

**现状**：卡片给了“编辑 / 删除”两个自定义动作，但“删除”实际只调用 `onToggleSelect()`，而 `onToggleSelect` 在非多选态只是 `viewModel.toggleSelection(id)`，**不会进入多选、也不会删除**：

```kotlin
// app/src/main/java/com/cov/justdiary/ui/diary/DiaryReadScreen.kt:375-378
CustomAccessibilityAction(editLabel) { onEdit(); true },
CustomAccessibilityAction(deleteLabel) { onToggleSelect(); true },
```

TalkBack 用户执行“删除”后看不到任何变化（或状态里多了一个不可见的选中项）。

**建议动作**：改成真正删除单条：调用 `onLongPress()` 进入多选并选中该条，或新增 `onDeleteBlock()` 直接弹“删除这一条？”确认。两个动作的标签与行为必须一致；补 Compose 语义测试。

**验收**：TalkBack 执行“删除” → 出现删除确认 → 确认后条目消失；不进入“已选择 1 项”的中间态（除非这是产品想要的行为）。

---

### [x] P1-14 待办勾选写错段落下标：同一片段有多个列表/待办时会勾到第一个

**状态：已实现（2026-11-13，R36）** —— `onToggleTodo(blockIndex, partIndex, itemIndex)` 一路透传，删掉 `partIndexOfFirstItems`；`DiaryReadScreenTest` 覆盖正文 + 待办的 partIndex。

**问题**：`DiaryReadScreen` 只向上回传“这一天里的第几个片段 + 待办项下标”，`DiaryPageScreen` 用 `partIndexOfFirstItems` 猜“第一个 items 段”：

```kotlin
// app/src/main/java/com/cov/justdiary/ui/diary/DiaryPageScreen.kt:302-310
onToggleTodo = { blockIndex, itemIndex ->
    state.blocks.getOrNull(blockIndex)?.let { block ->
        viewModel.toggleTodo(block.id, partIndexOfFirstItems(block.contentJson), itemIndex)
    }
}
// :444-449
private fun partIndexOfFirstItems(contentJson: String): Int = ...
```

一个片段里如果有“正文 + 列表 + 待办”或两个待办块，勾第二个会静默改第一个的 `done`，并立即落库。

**建议动作**：把 `partIndex` 从 `ReadPart` 一路传上来：`onToggleTodo(blockIndex, partIndex, itemIndex)`；删掉 `partIndexOfFirstItems`。`ReadPart` 已经有 `partIndex` 参数（`DiaryReadScreen.kt:462`），只是没传出去。

**验收**：构造同一片段含两个 todo part，勾第二个，断言 `content_json` 只有第二个的 `done` 变化；勾第一个同理；列表与待办混排也正确。

---

### [x] P1-15 导入备份不是原子操作：失败会留下“半天数据 + 半套图片 + 未恢复设置”

**状态：已实现（2026-11-13，R36）** —— DB 合并单事务；OVERWRITE 旧图提交后清理；图片先登记、失败整批删除；`BackupServiceRobolectricTest` 用“第 2 天图片损坏”验证回滚。

**问题**：`mergeBackupDatabase` 是**逐天事务**，图片在 DB 合并前就落盘，设置最后写；中途失败只报“导入失败”，但前面已提交的天和图片不会回滚：

- `DiaryRepository.kt:689-758`：每天 `beginTransaction…setTransactionSuccessful`；
- `DiaryRepository.kt:763-765`：全部提交后才重建 flags/索引；
- `BackupService.kt:190-212`：`copyImage` 先写 `images/`，DB 合并后才写设置。

**影响**：进程被杀、磁盘满、索引重建异常时，用户重试会二次复制图片（新 `imp_` 名字），OVERWRITE 模式被替换的旧图也不会删，目录膨胀；用户看到“导入失败”但数据已经变了一半。

**建议动作**：
1. 把整个 DB 合并放进**单个事务**；或先解到临时库，成功后一次性 `ATTACH`/替换。
2. 图片先写临时目录，事务成功后再移入 `images/`；失败路径清理已复制图片。
3. 导入完成后在同一轮里清理被覆盖片段的旧图（与 P2-5 的 `cleanupOrphanImages` 合并）。
4. 统计里区分“已提交 / 未提交”。

**验收**：注入“第 2 天写入后抛异常”的失败点，断言 DB 与 `images/` 都回到导入前；重试不产生重复 `imp_` 文件；OVERWRITE 后旧图被清理。

---

### [x] P1-16 启动迁移/开库失败被静默吞掉，界面表现为“所有日记消失”

**状态：已实现（2026-11-13，R36）** —— `repository.prepare()` 失败向上抛，`SettingsViewModel.startupError` 驱动全屏错误态 + 重试；`StartupErrorRobolectricTest` 覆盖。

**问题**：

```kotlin
// app/src/main/java/com/cov/justdiary/app/AppSettingsApplier.kt:66-77
runCatching { repository.prepare() }.onFailure { Log.app.e("prepare 失败：${it.message}") }
runCatching { repository.ensureSearchIndex() }.onFailure { Log.app.e("搜索索引准备失败：${it.message}") }

// app/src/main/java/com/cov/justdiary/app/MainActivity.kt:60-63
runCatching { settingsViewModel.bootstrap() }.onFailure { Log.app.e("启动序列失败：${it.message}") }
```

DB 迁移失败（磁盘满、库损坏、降级安装）时 App 正常进入，但所有读取返回空/异常，用户看到空日记本，可能误操作导入覆盖或卸载。

**建议动作**：启动失败进入显式错误态（可重试 / 导出诊断 / 提示先备份），不要在 `prepare()` 失败后继续静默读写；至少给一次对话框或 Snackbar。补 Robolectric 用例：注入抛异常的 helper，断言 UI 显示错误态而不是空日记本。

**验收**：模拟迁移抛异常，App 不展示“空日记本”，而是“数据读取失败 + 重试”；杀掉重进仍能恢复（如果库实际没坏）。

---

## 4. P2 · 性能与内存

### [x] P2-1 图片解码搬到 IO，并按显示尺寸取缩略图

**状态：已实现（2026-11-13，R37）** —— 新增 `MediaStore.imageAsync`；首页当日面板缩略图 480px、正文/编辑 1600px；分享面板预览 IO + inSampleSize；编辑器图片先异步预载再回写 `imageRevision`；缓存 150MB→96MB，`onTrimMemory` 清空。

**问题**：`MediaStore.image()` 是同步解码（默认 1600px），但多个调用点在**组合期/主线程**直接调用：

- 首页当日面板：`HomeScreen.kt:1956` `val bitmap = imageLoader(part.src)` → `RootScreen.kt:229` 的 `container.mediaStore.image(src)`；
- 分享面板预览：`ShareSheet.kt:99-100` 在 `remember` 里 `loadBitmap(file)`；
- 编辑器 `SpannableBridge.toSpannable` 的 `imageProvider`（`SpannableBridge.kt:388`）；
- 插图后 `DiaryPageScreen.kt:361` 在主线程再解一次；
- 缓存上限 150MB（`MediaStore.kt:143`），且所有尺寸都用同一个 1600px 解码，40dp 缩略图也按 1600px 占内存。

**建议动作**：
1. 图片加载统一走 suspend/`produceState`（已有 `DiaryReadScreen.InlineImage` 的正确做法可复用），禁止在组合期同步解码。
2. `image(src, maxPixel)` 的 `maxPixel` 按槽位给：缩略图 ~320–480、正文/编辑 ~1600、全屏预览 ~2048；缓存 key 已含 `maxPixel`，可直接复用。
3. 缓存上限从 150MB 降到 64–96MB，或改成“按当前页面可见图片预算”；配合 `LruCache` 的 `evictAll` 在低内存回调（`onTrimMemory`）。
4. 分享面板预览解码也放 IO；如果分享图很大，先解到预览宽度。
5. 用 StrictMode（debug）把主线程 `decodeFile` 标出来，或加 macrobenchmark。

**验收**：冷启动/切日/打开有图日记时无主线程 `BitmapFactory`/`ImageDecoder` 调用；大图日记滚动掉帧明显减少；内存 profiler 中图片缓存不超过设定上限。

---

### [x] P2-2 日记页 ViewModel 按天累积，关闭后不清

**状态：已实现（2026-11-13，R37）** —— `DiaryPageScreen` 使用页面级 `ViewModelStoreOwner`，`DisposableEffect` 在关闭/换日时 `viewModelStore.clear()`，不再把每天一个 VM 留在 Activity 里。

**问题**：

```kotlin
// app/src/main/java/com/cov/justdiary/ui/diary/DiaryPageScreen.kt:82-85
val viewModel: DiaryPageViewModel = viewModel(
    key = "diary:${dayKey.orEmpty()}",
    factory = DiaryPageViewModel.factory(container),
)
```

默认 `LocalViewModelStoreOwner` 是 Activity；`key` 每天一个，但关闭页面只把 `openDayKey=null`，没有 `ViewModelStore.clear()`。用户翻看 30 天，Activity 的 `ViewModelStore` 里就留下 30 个 `DiaryPageViewModel`（各自持有 blocks、搜索词、位置快照）。

**建议动作**：给日记页一个**页面级** `ViewModelStoreOwner`（`remember(dayKey) { ViewModelStore() }` + `ViewModelStoreOwner` + `DisposableEffect { clear() }`），或改成单个 `DiaryPageViewModel` 在 `open(dayKey)` 时 reset。注意 `onCleared` 里清理分享临时文件、取消 job。

**验收**：连续打开 30 天再返回，内存不随天数线性增长；`dumpsys meminfo` / LeakCanary 无 `DiaryPageViewModel` 泄漏。

---

### [ ] P2-3 月→周仍是 ~80fps；新月份首块录制 17–19ms

> **状态（2026-11-13，R38）**：**本轮不做**，留给下一轮单独排。原因：需要真机逐帧测量，
> 且改动会碰 morph / 挂载窗口的交班条件，不适合和功能/数据改动同轮。后续接手时按下面的
> “建议动作”先跑基线，再只改“新挂载块只画可能可见行”这一处。
>
> 入口：`MonthFlow.kt` 的 `BlockCanvas` / `BlockCanvas` 的 `weeks.forEachIndexed`、
> `MonthBlockWindow`；测量：`-morph-log` + `ScrollProf.blockDraw`，目标新块首帧 < 10ms。

**证据**：`migration-progress.md:2845`（“周 → 月 是最慢的一条，约 80 fps，单帧 12.5ms，瓶颈在 CPU 绘制指令录制”）；`:3021`（“剩下 17–19ms 是新月份块第一次录制，块是 5–6 行整块录的，屏幕上通常只有 1–2 行”）。

**建议动作**：
1. 新挂载的月份块只画“可能可见的行”（`MonthFlow` 已有 `range`/`windowAround` 概念，可把绘制范围再收一层），目标 ~5ms。
2. 跨年 `flags` 换实例导致的整窗重录是内容真变化，保留；但可以靠上一条变便宜。
3. 用 `compose-reports` 看 `MonthFlow` 的 skippable 情况，避免每次重组新建 `List`/lambda（代码里已大量 `remember`，新增改动不要破坏）。
4. 真机（MEIZU 21 / Pixel）用 `-morph-log` 复测，记录中位 fps。

**验收**：周→月中位 ≥ 90fps；新块首帧 < 10ms；无长帧（>32ms）。

---

### [x] P2-4 读页每段 `ReadLayout.renderModel` 未 remember

**状态：已实现（2026-11-13，R37）** —— `ReadPart` 用 `remember(part) { ReadLayout.renderModel(...) }`，滚动/重组不再重复解析。

**问题**：`ReadPart` 每次重组都跑 `ReadLayout.renderModel(listOf(part))`（`DiaryReadScreen.kt:462-470`），长日记/滚动时是重复计算；`DiaryBlockCard` 的 `matches` 已经 remember，这个没有。

**建议动作**：`val render = remember(part) { ReadLayout.renderModel(listOf(part)).firstOrNull() }`；`ContentFlatten.flattenPart` 也按 part 缓存。补一条 Compose 测试或基准。

---

### [x] P2-5 孤儿图片清理函数是死代码；存储用量不可见

**状态：已实现（2026-11-13，R38）** —— `cleanupOrphanImages(protectedSrcs)` 在 `bootstrap` 末尾调用，草稿引用的图片受保护；设置页新增“存储占用 / 清理孤儿图片”与结果提示；`StorageUsage` 统计 DB（含 WAL/SHM）/ 图片 / `.jdiary` 备份。新增 `StorageUsageTest`、草稿保护与删除旧图测试。

**问题**：

```kotlin
// app/src/main/java/com/cov/justdiary/data/DiaryRepository.kt:560-585
suspend fun cleanupOrphanImages() { ... }
```

全仓没有调用点。插图后“放弃修改”、导入中断、外部删库都会在 `filesDir/images` 留下孤儿文件；设置页也看不到“图片占用多少”。

**建议动作**：
1. 启动 `bootstrap` 末尾（或设置页“检查存储”）调用 `cleanupOrphanImages()`；注意不要与正在编辑的草稿里引用的图片冲突。
2. 设置页数据卡加“存储占用（日记/图片/备份）”与“清理孤儿图片”入口。
3. 补 Robolectric 测试：写两个 block 引用两张图 → 删一个 block → 只删对应图片；被草稿引用的图片不删。

**验收**：孤儿图片数量降为 0；清理后日记图片仍能打开；设置页数字与实际文件大小一致。

---

### [ ] P2-6 大文件拆分（可维护性）

> **状态（2026-11-13，R38）**：**本轮不做**，留给后续专门的重构轮。它是纯搬家、不改行为，
> 但 5 个文件都处在 UI/编辑器核心链路上，建议单独提交、按文件逐个拆，每一步都跑
> `testDebugUnitTest` + `connectedDebugAndroidTest`，不要和功能/性能改动混在一起。
>
> 建议顺序：`SpannableBridge` → `EditorModel` → `MonthFlow` → `SettingsScreen` → `HomeScreen`。
> 每个文件先抽“纯函数 / 数据模型”，最后才动 Compose 组合结构；`HomeScreen` 可以按
> “顶栏 / 周层 / 年层 / 当日面板”切，`MonthFlow` 按“几何 / 绘制 / 手势 / 标签”切。

当前最大的几个源文件：

| 文件 | 行数 |
| --- | --- |
| `ui/home/HomeScreen.kt` | 2044 |
| `ui/home/MonthFlow.kt` | 1787 |
| `ui/diary/EditorModel.kt` | 1124 |
| `ui/settings/SettingsScreen.kt` | 1007 |
| `ui/diary/SpannableBridge.kt` | 987 |

**建议动作**：纯重构、不改行为，按“顶栏/周层/年层/当日面板”“月流绘制/手势/标签”“行模型/格式操作/回车规则”“设置卡片/对话框/行组件”拆文件；每一步用现有 636 条测试兜底。优先级低于功能项，但会显著降低后续返工成本。

---

### [x] P2-6b 已确认但尚未单列的小问题（按触达模块顺手收）

**状态：已全部收口（2026-11-13，R38）** —— R37 收掉设置写入、地点筛选、页内搜索 3 条；
R38 收掉读页解析缓存、足迹后台聚合、分享临时文件生命周期、DB 事务后删图、死 API、
未使用依赖 6 条；`DebugFlags` 由 P2-9 收掉。

- [x] **设置写入是读-改-写，快速连点两个开关会丢一个**：**已修（R37）** —— `SettingsViewModel.update` 用 `Mutex` 串行化“读 old → transform → commit”；补连续 setter 的回归测试。原问题：`SettingsViewModel.update` 每次 `old = settings.load()`，`AppSettingsApplier.commit` 整体 `settings.save(new)`（`SettingsViewModel.kt:384-404`、`AppSettingsApplier.kt:94-101`、`SettingsStore.kt:47-66`）。改成一个串行 actor/Mutex，transform 在已提交状态上做；补“连续 setX/setY 都生效”的测试。
- [x] **地点筛选防抖的取消无效**：**已修（R37）** —— `setLocSearch` / `loadLocationOptions` 共用同一个 `locSearchJob`，查库改为同一协程内的 suspend 函数。原问题：`locSearchJob?.cancel()` 只取消 `delay` 那一层，`loadLocationOptions` 内部又 `viewModelScope.launch` 新协程（`SearchViewModel.kt:180-205`），旧结果可能覆盖新结果。改成同一协程体内查库或用 `flatMapLatest`；补乱序返回测试。
- [x] **读页在组合期解析 `content_json`**：**已修（R38）** —— `DiaryPageViewModel.reload` 在 `Dispatchers.Default` 解析一次 `partsByBlock`，阅读页/搜索/编辑共用。原问题：`DiaryPageScreen.kt:283-293` 每次重组 `state.blocks.map { ContentFlatten.parseContent(...) }`，页内搜索每敲一个字都会全量重解析。在 VM 加载时解析一次并缓存 `List<ReadBlock>`（与 P2-4 的 `ReadLayout.renderModel` 一起做）。
- [x] **页内搜索命中计算在主线程**：**已修（R37）** —— `recomputeHits` 在 `Dispatchers.Default` 解析 + `findMatches`，回写前校验关键词未变。原问题：`DiaryPageViewModel.kt:643-659` 在 `viewModelScope`（Main）里对每个 block `parseContent` + `findMatches`；长日记每次防抖后卡 1–2 帧。放 `Dispatchers.Default`，按 `(keyword, blocksVersion)` 缓存。
- [x] **足迹页全量聚合在主线程**：**已修（R38）** —— `FootprintViewModel.rebuildAsync` 在 `Dispatchers.Default` 聚合，保留旧 `viewState` + 顶部细进度条，代际号防旧结果覆盖。原问题：`FootprintViewModel.kt:127-146` 的 `rebuild` 对全部 rows 做统计 + 建树；上千条后切年份/时间档会掉帧。放 `Dispatchers.Default`，保留旧值 + loading 反馈。
- [x] **分享临时文件生命周期**：**已修（R38）** —— 关闭面板后延迟 30s 清理（新分享会取消），`ACTION_SEND` 无接收方时 Snackbar「没有可用的分享应用」。原问题：`dismissSharePanel` 立即 `clearShareDir()`（`DiaryPageViewModel.kt:779-786`），接收方可能还在读 `content://`；分享失败也被 `runCatching` 吞掉（`DiaryPageScreen.kt:159-164`）。延迟清理并给“没有可用的分享应用”提示。
- [x] **在 DB 事务内删图片**：**已修（R38）** —— `write` 增加 `afterCommit`，`updateBlockContent` / `deleteBlocks` 在事务提交后再删文件。原问题：`updateBlockContent` 事务里调 `cleanupImagesRemovedBetween`，事务回滚后 JSON 仍引用已删文件（`DiaryRepository.kt:351-353`、`:587-604`）。把文件删除移到事务提交之后（收集待删列表）。
- [x] **DebugFlags 在 release 可被任意 Intent 打开**：**已修（R37，P2-9）** —— `consume` 在 release 直接关闭。原问题：`MainActivity.kt:56-57` 会消费 `-morph-log` / `SLOW_MORPH`，第三方可用 extra 打开高频日志。给 `DebugFlags.resolve` 加 `BuildConfig.DEBUG` 门；`Log` 同样（P2-9）。
- [x] **死 API 清理**：**已修（R38）** —— 删除 `DiaryRepository.changes` / `MediaStore.invalidateAll` / `MediaStore.renderMaxPixel` / `ShareIntents.shareBackup` / `filteredLocOptions`；`UNUSED_INTENT_ACTION` 已在 R36 删除。原问题：`DiaryRepository.changes`（无收集点，`DiaryRepository.kt:68-69`）、`MediaStore.invalidateAll`（`MediaStore.kt:135`）、`MediaStore.renderMaxPixel`（`:146-147`）、`ShareIntents.shareBackup`（`file_paths.xml` 只暴露 `cache/share/`，调用会抛 `IllegalArgumentException`，`ShareIntents.kt:32-40`）、`filteredLocOptions`（`SearchViewModel.kt:50`）、`UNUSED_INTENT_ACTION`（`ReminderScheduler.kt:184`）。删除或接上，不要留“看起来可用”的入口。
- [x] **未使用的 `datastore-preferences` 依赖**：**已修（R38）** —— 从 `build.gradle.kts` 与版本目录删除。原问题：`app/build.gradle.kts:107` 引入但全仓无 `DataStore`；删除或真用起来，减少包体与攻击面。

---

## 5. P2 · 工程化与发布

### [x] P2-7 发布配置：versionCode / 签名 / R8 / 资源压缩

**状态：已实现（2026-11-13，R37）** —— 新增 `version.properties`（构建读取）、`keystore.properties` / 环境变量签名、release `isMinifyEnabled=true` + `isShrinkResources=true`、R8 keep 规则与 `docs/release.md`。实测签名 release APK 4.1MB、AAB 6.0MB，冷启动/备份导出正常。

**现状**：

```kotlin
// app/build.gradle.kts:17-18
versionCode = 1
versionName = "1.0"
// :32-40
debug { isMinifyEnabled = false }
release {
    isMinifyEnabled = false
    proguardFiles(...)
}
```

没有 `signingConfig`、没有版本号自增策略、没有 R8/资源压缩、debug APK 已 37MB。

**建议动作**：
1. 版本号从 `version.properties` / CI 注入；`versionCode` 单调递增，`versionName` 语义化。
2. release 开 `isMinifyEnabled=true` + `isShrinkResources=true`，补 keep 规则（`kotlinx.serialization`、WorkManager、Room 无、反射点逐个确认）；用 `assembleRelease` + 真机回归。
3. 签名走环境变量/`keystore.properties`（不入库）；CI 出 AAB。
4. 加 baseline profile（启动 + 首屏）与 macrobenchmark（可选但推荐）。
5. 目标 SDK 36 / compile 37 与 Play 政策已对齐；确认 16KB page size（当前无自有 native lib，风险低）。

**验收**：`./gradlew assembleRelease` 产出已签名 APK/AAB；R8 后 636 测试 + 手工冒烟全过；体积明显下降；启动无 `ClassNotFoundException`。

---

### [x] P2-8 CI 与 instrumented tests（当前为零）

**状态：已实现（2026-11-13，R37）** —— 新增 `.github/workflows/android.yml`（单测 + schema/docs 校验 + API 35 模拟器 connected tests）与 7 条 `androidTest`（DB 往返/搜索、草稿、三种深链、启动首页）。本机 Pixel_10_Pro 上 `connectedDebugAndroidTest` 7/7 通过。

**现状**：仓库没有 `.github/workflows`，没有 `app/src/androidTest`；`testInstrumentationRunner` 已配但依赖里没有 `androidx.test:runner` / `ext:junit` / espresso。所有验证靠本机 Gradle + uiautomator 脚本。

**建议动作**：
1. GitHub Actions：`assembleDebug` + `testDebugUnitTest` + `tools/verify_sqlite_schema.py` + `tools/verify_backup_interop.sh`；缓存 Gradle/Robolectric。
2. 加 `androidTest` 依赖与 4–6 条关键用例：
   - 深链冷启动/热启动；
   - 编辑器保存 → 阅读 → 再次编辑；
   - 备份导入两种模式（SAF 可用 `Intents`/临时文件）；
   - 通知点击直达；
   - 分享 ACTION_SEND（若做）；
   - 配置变更草稿保留（若做 P0-1）。
3. 可选：`lint` + `detekt`/`ktlint`，先只对新增文件卡规则，避免一次性几百条。

**验收**：PR 上 CI 全绿；instrumented 在 API 33/36 模拟器上跑通；失败能定位到用例。

---

### [x] P2-9 日志在 release 没有开关

**状态：已实现（2026-11-13，R37）** —— `Log` 按 `BuildConfig.DEBUG` 抑制 debug/info；`DebugFlags.consume` 在 release 直接关闭所有 Intent/环境变量开关。release 启动 logcat 已看不到 `JustDiaryApplication created` 这类 info。

**现状**：`Log.kt` 直接调 `android.util.Log`，没有 `BuildConfig.DEBUG` 判断；release 也会输出 `day_key`、地点、备份统计等。

**建议动作**：`Log` 按 `BuildConfig.DEBUG` 降级/静默，或 R8 规则移除；错误级别保留但不要带坐标/正文。补一条“release 不输出 debug 日志”的静态检查（可选）。

---

### [x] P2-10 文档收口（当前有互相矛盾的“事实”）

**状态：已实现（2026-11-13，R37）** —— 更新 `docs/android-design/README.md` 实施状态、`07-ios-map-and-plan.md` 历史差距说明、`android-ui-inventory.md` 的 `EditorHistory` 记录；新增 `tools/verify_docs.py` 校验测试计数 / P1 收口 / release 配置，并接入 CI。

| 位置 | 问题 |
| --- | --- |
| `docs/android-design/README.md:9-12` | 还写“**日记页尚未开工**”，实际 R27–R33 已完成 |
| `docs/android-design/07-ios-map-and-plan.md:55-64` | P0-1…P0-6 多数已修，仍按“未修”列表呈现 |
| `docs/android-design/04-diary.md` | P1-6 已确认不做；删除 `EditorHistory` 后“无 undo 栈”仍然成立，不用改 |
| `docs/android-design/04-diary.md` §3.4 / `migration-progress.md` R33 | 引用回车写“iOS 随后按同一口径改”；P1-4 已确认**跟 iOS 续引用**，实现时一起同步 |

**建议动作**：P1-4 实现时一次性更新；测试计数、设置键数、AGP 版本用脚本或 CI 校验（例如 `tools` 里加一个 `verify_docs.py`）。

---

## 6. P2 · 可访问性与真机验证

### [ ] P2-11 TalkBack 真人走查仍未做

README 已列“仍未验证：TalkBack 真人走查（语义已存在，朗读顺序与提示语尚未逐条听）”。当前语义节点不少（日格、卡片、开关行、格式栏），但**朗读顺序/重复停靠/自定义动作**只有零散断言。

**建议动作**：按 `docs/android-design/09-test-checklist.md` + 新增 P1-3/P1-13 做一次完整走查；重点：
- 月态 35–42 个日期节点（无障碍开启时）是否逐格可读、`content-desc` 是否带状态；
- 卡片合并后是否一次读完、删除动作是否正确；
- 设置开关行是否只停一次；
- 格式栏 9 键是否都有名称；
- 大字号 2.0× 是否裁切。

**验收**：走查表逐条打勾，问题回填本清单或新建 issue。

### [ ] P2-12 48dp 触控目标仍是“定义了但少用”

`Spacing.hitTarget = 48dp` 只在两处使用（`HomeScreen.kt:1779`、`Components.kt:333`）；日格在横屏/分栏下可能低于 48dp。设计规范把 48dp 列为硬约束。

**建议动作**：对日格、格式栏按钮、设置行、搜索胶囊做一次 `uiautomator` 尺寸量测；日格在紧凑行高下用 `minimumInteractiveComponentSize` 或透明扩展命中区（不改变视觉行高）。权衡：6 行月历不能因此被撑破。

### [ ] P2-13 尚未真机验证的三项（分享深色 / 平板分享面板 / 图片行光标）

来自 R29 的“仍未做”：
- 分享长图深色版只在单测覆盖，真机没走查；
- 分享面板在平板/横屏的预览宽度（`SharePanelLayout.previewWidthDp`）没验证；
- 图片行中间点击时 `ImageSpan` 的 caret 位置没走查。

**建议动作**：各补一张真机截图进 `docs/ui-audit/`，有问题再改代码。

---

## 7. P2 · 测试缺口（不是“没测”，是“这几条没测”）

- [x] **备份路径穿越**：恶意 manifest/zip 的 `../`、绝对路径、后缀白名单；`BackupSecurityRobolectricTest` + `ImagePathUtilTest` 已覆盖。
- [x] **图片导入**：`MediaStoreRobolectricTest` 覆盖 P1-1 的四条不变量（原尺寸 / 不放大 / 方向与 GPS / 失败不留文件）。
- [x] **年历几何**：`CalendarYearGeometryTest` 覆盖 P1-2 的圆比例与标记 y。
- [x] **月历逐日语义**：`MonthA11yTest` 覆盖无障碍开启时的可点节点与关闭时 0 节点；见 P1-3。
- [x] **日历 a11y 文案**：中英资源均已补齐；`MonthA11yTest` 覆盖节点状态文案，方向由 `stepDay(±1)` 用例兜住。
- [x] **待办勾选 partIndex**：`DiaryReadScreenTest` 用“正文 + 待办”覆盖 `partIndex` 透传；见 P1-14。
- [x] **导入原子性**：`BackupServiceRobolectricTest` 用第 2 天图片损坏验证 DB/图片回滚；见 P1-15。
- [x] **编辑 vs 阅读逐像素**：**已落地首条基线（2026-11-13，R38）** —— `EditReadPixelParityTest` 把正文单段分别画进 `DiaryEditText` 与 `DiaryReadScreen`，比行盒高度和像素差异（当前允许 8% 抗锯齿/letterSpacing 差异，基线后会继续收紧）。原建议：`ios-sync-2026-10-01.md` §5.5 提议的“同一 `content_json` 分别走 `SpannableBridge` 与阅读渲染，比像素”仍未落地；可作为 R32“三处统一”的验收尺子（当前基线可能先红，正好当靶子）。
- [x] **空引用行回车后光标回列首**：`EditorEnterKeyTest` 的空引用行用例覆盖行数与样式；光标 x 由既有 `EditorCursorLayoutTest` 兜住。
- [x] **空引用行底与打字后同位置**：`EditorQuoteRenderTest` 覆盖。
- [x] **首页选中日竞态**：`HomeViewModelTest` 的乱序返回用例已覆盖。
- [x] **草稿恢复**：`EditorDraftStoreTest` / `DiaryPageStateTest` 已覆盖，模拟器也验证过。

---

## 8. P3 · 可选增强（本轮未选，后置）

> 2026-11-12 用户确认：可选功能本轮只做**应用快捷方式**（P1-9）。下表其余项**暂不排期**，保留评估。

| 项 | 价值 | 成本/风险 | 本轮状态 |
| --- | --- | --- | --- |
| 桌面小组件（Glance）：今日是否已写 + 一键写 | 高 | 中；新增依赖与尺寸适配 | 未选 |
| 阅读页“复制正文 / 分享文字” | 中 | 低；卡片长按目前是多选，需加自定义动作 | 未选 |
| 搜索历史 / 最近关键词 | 中 | 低；持久化 + 清除入口 | 未选 |
| 备份提醒（上次备份超过 N 天） | 中 | 低 | **不做**（2026-11-12 用户确认） |
| 相机直拍插图 | 中 | 中；FileProvider 临时文件 + 权限/失败路径 | 未选 |
| 多图一次插入 | 中 | 中；Photo Picker `PickMultipleVisualMedia` + 顺序/光标 | 未选 |
| 存储用量卡片 | 中 | 低；与 P2-5 一起 | 未选（P2-5 先做清理） |
| 应用锁（生物识别） | 中 | 中；隐私政策、失败降级、通知预览遮挡 | 未选 |
| 平板拖放图片 / 硬件键盘快捷键 | 低 | 中；Android 专属体验 | 未选 |

---

## 9. 已明确不做 / 不要回退

| 项 | 结论 | 出处 |
| --- | --- | --- |
| 预测性返回（`PredictiveBackHandler` 的跟手位移/淡出） | **用户明确说不做**，R29 做完后整段回退，不要重做 | `migration-progress.md:3308-3309` |
| 日历捏合缩放 | 不做；会与上下滑翻月抢手势、绕过 morph 锚点 | `android-design/README.md` 一·有意分歧 |
| iPad / Mac / 桌面三栏、iPhone Duo 折痕适配 | 本轮不做；Android 折痕是独立需求，别夹带 | `android-migration-plan.md:69`、iOS `f03e8d6` |
| 设置页“清空全部日记” | Q1 暂不补；若补必须二次确认 + 输入“删除” + 先自动导出 | `android-design/README.md:173` |
| 第二套导航 / 玻璃模糊 / `material-icons-extended` | 不做；保持 M3 + 零第三方 UI | `android-design/README.md` 硬约束 |
| 照抄 iOS 的已知缺陷（`auto_time` 死设置、未来 toast 死代码等） | 不复制 | `android-design/README.md` 六 |
| 把年历改成走 `DayMetrics` + `circleMargin` 字段 | 不建议夹带；Android 年历不消费 `DayMetrics`，按比例修即可（P1-2） | 本轮审计 |
| 删除片段 5 秒撤销 | **已确认不做**；保留删除前确认框 | 2026-11-12 用户确认 |
| 编辑器撤销/重做 | **已确认不做**；删掉 `EditorHistory` 与相关死代码 | 2026-11-12 用户确认 |
| 分享入口 / 相机 / 多图 / 桌面小组件 / 搜索历史 | **本轮不做**；可选功能只选了应用快捷方式 | 2026-11-12 用户确认 |
| Android Auto Backup | **关闭**，只走 `.jdiary` | 2026-11-12 用户确认（P0-2） |
| 引用回车“新行回正文” | **不再作为 Android 口径**；跟随 iOS 的“续引用” | 2026-11-12 用户确认（P1-4） |
| 备份时间/超期提醒 | **不做**；Auto Backup 关闭后仍只靠手动导出，不主动提醒 | 2026-11-12 用户确认（P0-2） |

---

## 10. 建议排期（按依赖关系）

| 轮次 | 内容 | 产出 |
| --- | --- | --- |
| **S1 · 数据安全** | P0-0 路径穿越、P0-1 草稿、P0-2 关闭 Auto Backup、P0-3 导入设置、P0-4 首页竞态、P0-5 今天口径、P1-15 导入原子性 | 不丢内容；导入/恢复可预期 |
| **S2 · iOS 同步** | P1-1 原图、P1-2 年历两项（同轮）、P1-3 月历 a11y、P1-4 改为跟随 iOS 的续引用 | 与 iOS 最新版对齐；引用口径统一 |
| **S3 · 平台体验** | P1-7 通知、P1-9 快捷方式、P1-12 语言/主题、P1-13 删除动作、P1-14 待办下标、P1-16 启动错误态 | Android 用户可感知的增强 |
| **S4 · 性能与发布** | P2-1 图片、P2-2 VM 泄漏、P2-3 morph、P2-7 发布、P2-8 CI | 可发版；长期可维护 |

> **2026-11-13 更新**：S4 里的 P2-1 / P2-2 / P2-7 / P2-8 / P2-9 / P2-10 已在 R37 完成；P2-3（morph 性能）与 P2-6 大文件拆分仍保留。每轮结束仍按项目惯例跑 `./gradlew clean assembleDebug testDebugUnitTest` + 校验脚本，并把真机截图放进 `docs/ui-audit/`。新增 UI 逻辑优先下沉成纯函数并补 JVM 断言。

---

## 附：本轮审计的边界

- 本清单**没有修改任何代码**，只新增了这份 `TODO.md`。
- 没有提交、没有推送、没有改变工作树里已有的 `gradle/libs.versions.toml` 改动。
- iOS 对照基于 `/Users/cov/JustADiary` @ `bf2ef0a`；Android 基于 `c3dea83`。
- 优先级是工程判断，不是排期承诺；P0-2 与 P1-4 的口径已确认，建议先直接修 P0-0 / P0-1，再按 S1–S4 推进。

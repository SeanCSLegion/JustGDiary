# 04 · 服务层与平台相关源码分析（iOS/SwiftUI → Android）

> 目标读者：Android 实现者。本文只写**可直接照着实现的事实**：确切常量、存储 key 与默认值、字符串 key、方法签名、API 名称。
> 每一条关键结论都带 `文件:行号` 证据（路径相对 `/Users/cov/JustADiary`）。
>
> 已锁定的目标技术栈（本文所有判定与之对齐）：
> Material 3（**不模仿 Liquid Glass，不引入任何玻璃模糊库**）、`minSdk 33` / `compileSdk 37` / `targetSdk 36`、Kotlin + Jetpack Compose、
> `SQLiteOpenHelper` + 裸 SQL（不用 Room）、备份与 iOS **双向严格互导**、定位运行时探测 GMS（有则 `FusedLocationProviderClient`，无则 `LocationManager` + `Geocoder`，反地理编码抽接口）、
> 提醒用 `WorkManager`、足迹排序用 `android.icu.text.Collator` + `setNumericCollation(true)`（平台 API，不算第三方库）、图表自绘 Compose Canvas（只有一张柱状图）、
> 应用内语言用 `AppCompatDelegate.setApplicationLocales` + `android:localeConfig`（appcompat 1.8.0）。本轮验收＝编译通过 + JVM 单测通过。

---

## 1. 设置项全量清单

### 1.1 持久化容器

| 事实 | 值 | 证据 |
|---|---|---|
| 容器类型 | `UserDefaults(suiteName:)`（App Group 共享容器） | `JustDiary/Models/AppSettings.swift:31` |
| suite 名 | `group.com.cov.justdiary` | `AppSettings.swift:30` |
| 取不到时的回退 | `.standard` | `AppSettings.swift:31` |
| 写入方式 | **每次 `save()` 都把 10 个 key 全量重写**（没有局部更新） | `AppSettings.swift:56-69` |
| 读取缓存 | 进程内 `LockedBox<AppSettings?>` 缓存，`load()` 命中缓存直接返回；`save()` 同时刷新缓存 | `AppSettings.swift:33-38,57` |
| 载入白名单校验 | `theme_mode ∈ {light,dark,system}`、`app_language ∈ {system,zh,en}`、`week_start ∈ {monday,sunday}`，不合法即回落默认 | `AppSettings.swift:49-51` |
| 版本号来源 | `Bundle.main.infoDictionary["CFBundleShortVersionString"] ?? "1.0"` | `AppSettings.swift:84-86` |

> Android 对应：`SharedPreferences`（或多进程 `DataStore`）。**不存在 App Group 概念**；备份互导要求 key 名、类型、默认值逐字对齐（见 §8）。

### 1.2 UI 行 → 设置项

| UI 行名称（字符串 key） | 存储 key | 类型 | 默认值 | 可选值 | 是否即时生效 | 运行时副作用（调用到哪个方法） |
|---|---|---|---|---|---|---|
| 语言 `settings_language` | `app_language` | String | `"system"` | `"system"` / `"zh"` / `"en"` | 即时（重建视图树 + 重排提醒） | `SettingsViewModel.setLanguage(_:)`（`:46-52`）：`save()` → `SettingsStore.load()` → `DiaryRepository.shared.bumpUiTick()` → `Task { await ReminderService.rearm() }` |
| 主题 `settings_theme` | `theme_mode` | String | `"system"` | `"system"` / `"light"` / `"dark"` | 即时 | `setTheme(_:)`（`:40-44`）：`save()` → `AppConfigService.applyAll()`（`AppConfigService.swift:16-22`）＝`DayDraw.clearCache()` + `bumpUiTick()` |
| 新一天的开始时间 `settings_day_start` | `day_start_hour` | Int | `4` | `0…23`（单选轮，标签用应用时间文案） | 即时，但归属日重算异步 | `applyDayStart()`（`:110-121`）：`save()` → `DiaryRepository.recomputeDayKeys(dayStartHour:)` → `bumpDiaryVersion()`；冲突数 > 0 时弹 `settings_day_recalc_title` / `settings_day_recalc_msg` |
| 一周从哪一天开始 `settings_week_start` | `week_start` | String | `"monday"` | `"monday"` / `"sunday"` | 即时 | `setWeekStart(_:)`（`:54-58`）：`save()` → `bumpUiTick()` |
| 自动插入时间 `settings_auto_time` | `auto_time` | Bool | `true` | `true` / `false` | **无任何效果** | `setAutoTime(_:)`（`:60-63`）：只 `save()`。**全仓无读取点**：`startUtc` 恒为 `Int64(Date().timeIntervalSince1970 * 1000)`（`DiaryViewModel.swift:25,50`），所以"自动记录开始时间"永远是开着的（实现缺口，见 §8 备注） |
| 自动插入地点 `settings_auto_loc` | `auto_loc` | Bool | `true` | `true` / `false` | 即时（开启会请求权限） | `setAutoLoc(_:)`（`:65-72`）：`save()`；`value == true` → `LocationService.shared.requestPermission()` + `Task { await DiaryRepository.shared.backfillBlockRegions() }`；`false` → 无动作 |
| 位置权限 `settings_loc_permission` | *不持久化*（派生自 `CLAuthorizationStatus`） | — | — | — | 返回前台时刷新 | `handleLocPermission()`（`:95-108`）：`.notDetermined` → `requestPermission()`；否则 `UIApplication.openSettingsURLString`；随后 `sleep(800ms)` 再 `refreshStatus()` |
| 允许修改 / 删除历史日记 `settings_history_edit` | `allow_history_edit` | Bool | `false` | `true` / `false` | 即时 | `setAllowHistoryEdit(_:)`（`:74-77`）：只 `save()`。读取点：`DiaryViewModel.swift:136,158` |
| 写日记提醒 `settings_remind_enabled` | `remind_enabled` | Bool | `true` | `true` / `false` | 即时（异步授权） | `setRemindEnabled(_:)`（`:79-93`）：`save()`；`true` → `await ReminderService.requestEnable()`，成功再 `ensureDailyReminder()`，然后 `refreshStatus()`；`false` → `ReminderService.cancelAll()` |
| 提醒时间 `settings_remind_time` | `remind_hour` / `remind_minute` | Int / Int | `21` / `0` | `0…23` / `0…59`（`DatePicker(.hourAndMinute)`） | 即时 | `applyRemindTime()`（`:123-126`）：`save()` → `Task { await ReminderService.rearm() }` |
| 通知权限 `settings_notif_permission` | *不持久化*（派生自 `notificationSettings()`） | — | — | — | 返回前台时刷新 | `requestNotificationPermission()`（`:128-133`）：`await ReminderService.requestEnable()` → `refreshStatus()`。仅当 `remindEnabled && notifStatusText == L10n.str("settings_notif_off")` 才显示该行（`SettingsView.swift:336-343`） |
| 导出全部日记 `settings_export` | *不持久化*（生成临时文件） | — | — | `settings_export_data_only` / `settings_export_data_settings` | — | `runExport(includeSettings:)`（`:135-150`）→ `BackupService.exportBackup(includeSettings:)` |
| 导入备份 `settings_import` | *不持久化*；**导入会整体覆盖设置** | — | — | `settings_import_skip` / `settings_import_overwrite` | 导入后即时 | `runImport(mode:)`（`:152-177`）→ `BackupService.importBackup` → `SettingsStore.load()` + `bumpDiaryVersion()` + `bumpUiTick()` |
| 版本 `settings_version` | *不持久化*（`SettingsStore.appVersion`） | — | — | — | — | 只读展示（`SettingsView.swift:374-375`） |

设置页结构：`通用 / 日记规则 / 提醒 / 数据 / 关于`（`SettingsView.swift:231-377`），section key 分别为 `settings_section_general`、`settings_section_rules`、`settings_section_remind`、`settings_section_data`、`settings_section_about`。

### 1.3 非 UI 的持久化 key（必须原样迁移）

| key | 类型 | 默认值 | 语义 | 证据 |
|---|---|---|---|---|
| `search_index_version` | Int | `0`（`integer(forKey:)`） | FTS 索引版本，用于判断是否需要重建。**当前要求值 = `3`**（`DiaryRepository.searchIndexVersion`） | `AppSettings.swift:71-74`；`DiaryRepository.swift:14`（`= 3`）、`:32,304`（比较 `>= 3`）、`:284`（重建索引后写入 3） |
| `content_format_version` | Int | `0` | `edit_block.content_json` 的**存储格式**版本（不是用户偏好，恢复备份不得重置）。**当前要求值 = `ContentDocument.currentVersion` = `2`** | `AppSettings.swift:79-82`；`DiaryRepository.swift:229`（`< 2` 才迁移）、`:246`（迁移后写 2）；`ContentPart.swift:155`（`currentVersion = 2`） |
| `region_geocode_skip_v2` | `[Int64]`（UserDefaults array） | `[]` | 反地理编码失败过一次的 block id 黑名单，最多保留 2000 条（`suffix(2000)`） | `DiaryRepository.swift:834-845` |

只在内存/系统里、不落库的相关 key：`CFBundleShortVersionString`（版本）、`Locale.preferredLanguages`（跟随系统语言时的取值来源，`L10n.swift:9`）、iOS 系统偏好 `AppleLanguages`（**本应用从不写它**，见 §6）。

---

## 2. 定位完整流程

### 2.1 授权

| 项 | 事实 | 证据 |
|---|---|---|
| 权限请求 API | `CLLocationManager.requestWhenInUseAuthorization()` | `Services/LocationService.swift:30-32` |
| 授权状态读取 | 复用同一个 `private let statusManager = CLLocationManager()`，读 `statusManager.authorizationStatus` | `LocationService.swift:8,11-13` |
| 视为已授权 | `.authorizedWhenInUse` 或 `.authorizedAlways` | `LocationService.swift:15-18` |
| 精度授权 | `statusManager.accuracyAuthorization == .fullAccuracy`，且必须先已授权 | `LocationService.swift:20-22` |
| **只请求 WhenInUse** | 无 `NSLocationAlwaysAndWhenInUseUsageDescription`，Info.plist 无 `UIBackgroundModes`（只含 `CFBundleLocalizations` / `CFBundleURLTypes` / `NSPhotoLibraryAddUsageDescription` / `NSUserNotificationsUsageDescription`） | `JustDiary/Info.plist:1-26` |
| 请求时机 ①（新片段进编辑器且未授权） | `DiaryViewModel.beginLocate()`：`guard LocStatus.isAuthorized else { requestPermission(); sleep 600ms; 再查一次，仍未授权则 `locating = false` 返回 }` | `ViewModels/DiaryViewModel.swift:387-406` |
| 请求时机 ②（打开"自动插入地点"开关） | `SettingsViewModel.setAutoLoc(true)` | `SettingsViewModel.swift:65-72` |
| 请求时机 ③（点"位置权限"行且 `.notDetermined`） | `SettingsViewModel.handleLocPermission()`；非 `.notDetermined` 时跳系统设置，再等 800ms 刷新状态 | `SettingsViewModel.swift:95-108` |
| **不在启动时请求** | `AppDelegate.didFinishLaunching` / `applicationDidBecomeActive` 都没有定位调用 | `App/JustDiaryApp.swift:30-54` |

### 2.2 权限文案原文（中英，逐字）

| key | zh | en | 证据 |
|---|---|---|---|
| `NSLocationWhenInUseUsageDescription` | `用于在写日记时自动记录所在位置` | `Used to record your location automatically when writing` | zh 来自 **build setting**：`generate_project.py:170`；en 来自 `JustDiary/Resources/en.lproj/InfoPlist.strings:1` |
| `NSPhotoLibraryAddUsageDescription` | `用于把你导出的日记图片保存到相册` | `Used to save the diary image you export to your photo library` | `Info.plist:21-22`；`InfoPlist.strings:2` |
| `NSUserNotificationsUsageDescription` | `用于在未写日记时提醒你` | `Used to remind you when you haven't written yet` | `Info.plist:23-24`；`InfoPlist.strings:3` |

> ⚠️ 坑：`NSLocationWhenInUseUsageDescription` **不在 `Info.plist` 文件里**，只由 `INFOPLIST_KEY_NSLocationWhenInUseUsageDescription` 注入（`generate_project.py:164-170`）。源码注释明确写着：漏掉它，"请求定位授权时真机会终止 App"（`generate_project.py:168-169`）。仓库里也没有 `zh-Hans.lproj/InfoPlist.strings`（只有 `en.lproj`），中文靠 base 值。
> Android 对应：manifest 里声明 `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`；无运行时"授权文案"概念，只有系统对话框。

### 2.3 取点：API 与超时

```
func currentLocation() async -> CLLocation?
```
（`LocationService.swift:34`）

- 测试钩子：启动参数含 `-ui-test-no-location` → 直接 `return nil`（`:38-40`）。
- 未授权 → `Log.location.warning("currentLocation denied")` + `nil`（`:41-44`）。
- 取点 API：**`CLLocationUpdate.liveUpdates()`**（iOS 17+ 的异步序列，`:48`），取第一个 `update.location != nil` 的更新即返回并 `group.cancelAll()`（`:49-54`）。
- **超时＝ 8 秒**：`Task.sleep(for: .seconds(8))` 的竞速任务（`:56-59`）。
- 失败/超时 → `nil`，catch 分支 `Log.location.error("liveUpdates failed: …")`（`:64-67`）。
- 注意：`liveUpdates()` 默认是**连续更新**，代码只取首点；没有设置 `desiredAccuracy`/`distanceFilter`，也没有 `allowsBackgroundLocationUpdates`。

### 2.4 降级策略汇总

| 场景 | 结果 | 证据 |
|---|---|---|
| 未授权 | `nil` | `LocationService.swift:41-44` |
| 8 秒无首点 | `nil` | `LocationService.swift:56-62` |
| `liveUpdates` 抛错 | `nil` | `LocationService.swift:64-67` |
| 反向地理编码失败/为空 | `nil` placemark；`text(for:precision:)` 返回 `""` | `LocationService.swift:70-82,123` |
| **拿到坐标但地址为空** | 视为"没有位置"：`location = snapshot.locText.isEmpty ? nil : snapshot` | `DiaryViewModel.swift:413-417` |
| 内存精度不足（非 fullAccuracy） | 精度锁死 `province`，可选档位只剩省/市/区 | `LocationService.swift:104-105,193-198` |
| 保存时仍无位置（`autoLoc == true`） | 二次确认：`editor_location_retry` / `editor_location_missing_msg` / 确认按钮 `editor_save_anyway` | `DiaryViewModel.swift:251-264` |
| UI 测试 | `-ui-test-no-location` → `nil`；`-ui-test-no-autoloc` → `auto_loc = false` | `LocationService.swift:38`；`JustDiaryApp.swift:36-43` |

### 2.5 反向地理编码

| 项 | 事实 | 证据 |
|---|---|---|
| API | `MKReverseGeocodingRequest(location:)`（iOS 26+ MapKit 新 API），失败返回 `nil` | `LocationService.swift:70-71` |
| 语言固定 | `request.preferredLocale = AppLanguage.locale`（**不跟随设备 locale，跟随应用内语言**） | `LocationService.swift:72-74` |
| 结果 | `try await request.mapItems` → `mapItems.first?.diaryPlacemark` | `LocationService.swift:75-78` |
| CLPlacemark 获取方式 | `extension MKMapItem { var diaryPlacemark: CLPlacemark? { placemark } }`，用 `@diagnose(DeprecatedDeclaration, as: ignored, reason: …)` 精确豁免弃用告警 | `Services/MKMapItem+Placemark.swift:22-28` |
| 为什么不用 `MKAddressRepresentations` | 它只给展示性字符串（full/short address、cityName、regionName、regionCode），**没有 country / administrativeArea / subAdministrativeArea / subLocality / thoroughfare，也没有国家代码字段** | `MKMapItem+Placemark.swift:3-21` |
| 后台回填用的另一条路径 | `ReverseGeocoder.reverse(_:)` 直接用 `MKReverseGeocodingRequest` + `.diaryPlacemark`（**没有设 preferredLocale**） | `Data/DiaryRepository.swift:1011-1031` |

### 2.6 地区字段映射规则（给出的三元表达式必须逐字对齐）

**主路径**（新建片段、实时解析）`LocationResolver.resolve(location:)`，`LocationService.swift:106-112`：

```swift
let region = LocRegion(
    country:     placemark?.country ?? "",
    countryCode: placemark?.isoCountryCode ?? "",
    region1:     placemark?.administrativeArea ?? "",
    region2:     placemark?.subAdministrativeArea ?? placemark?.locality ?? "",
    region3:     (placemark?.subLocality ?? "").isEmpty ? "" : (placemark?.locality != nil ? placemark?.subLocality ?? "" : ""),
    locQuality:  precise ? LocQuality.precise : LocQuality.coarse)
```

| 落库列 | 取值 | 语义 |
|---|---|---|
| `country` | `CLPlacemark.country ?? ""` | 国家名（本地语言） |
| `country_code` | `CLPlacemark.isoCountryCode ?? ""` | ISO 国家码 |
| `region1` | `administrativeArea ?? ""` | 省 / 一级行政区 |
| `region2` | `subAdministrativeArea ?? locality ?? ""` | 市 / 二级 |
| `region3` | `subLocality`，但**仅当** `subLocality` 非空**且** `locality != nil`，否则 `""` | 区县 / 三级 |
| `loc_quality` | `precise ? "precise" : "coarse"` | 精度质量 |

**回填路径**（`ReverseGeocoder.reverse`）**规则不同，这是实现者必须知道的既有不一致**（`DiaryRepository.swift:1020-1029`）：

```swift
let region1 = pm.administrativeArea ?? pm.subAdministrativeArea ?? ""
let region2 = pm.subAdministrativeArea ?? pm.locality ?? ""
let region3 = pm.locality != nil && pm.subAdministrativeArea != nil ? pm.subLocality ?? "" : ""
```

差异点：`region1` 多一层 `subAdministrativeArea` 兜底；`region3` 要求 `locality != nil && subAdministrativeArea != nil`（主路径只要求 `locality != nil`）。**Android 抽 `ReverseGeocoder` 接口时应把两者统一成一条规则**（建议取主路径规则，因为足迹聚合按 `country/region1/region2` 建树）。

Android 字段对应（`android.location.Address`）：`country ← getCountryName()`、`country_code ← getCountryCode()`、`region1 ← getAdminArea()`、`region2 ← getSubAdminArea() ?: getLocality()`、`region3 ← getSubLocality()`（按上面条件判空）、`占位名 ← getFeatureName()`（≈ `CLPlacemark.name`）、`thoroughfare ← getThoroughfare()`。

### 2.7 精度档位全表

定义在 `Models/DiaryModels.swift:84-93`：

| 常量 | 字符串值 | 顺序表 `LocPrecision.all` | 地址文案来源 |
|---|---|---|---|
| `none` | `"none"` | 不在 `all` 内 | 无 |
| `exact` | `"exact"` | `all[0]` | `name` + `thoroughfare` + `locality` + `administrativeArea` + `country` |
| `street` | `"street"` | `all[1]` | `thoroughfare ?? subLocality` + `locality` + `administrativeArea` + `country` |
| `district` | `"district"` | `all[2]` | `subAdministrativeArea` + `locality` + `administrativeArea` + `country` |
| `city` | `"city"` | `all[3]` | `locality` + `administrativeArea` + `country` |
| `province` | `"province"` | `all[4]` | `administrativeArea` + `country`（`default` 分支） |

`all = [exact, street, district, city, province]`（`DiaryModels.swift:92`）。

**精度 ↔ 授权精度的对应关系**（`LocationService.swift:104-105, 193-198`）：

| 授权情况 | 新建片段的初始精度 | 精度菜单可选项 |
|---|---|---|
| 已授权 且 `fullAccuracy` | `exact` | `[exact, street, district, city, province]` |
| 已授权但只有模糊位置 | `province` | `[province, city, district]` |
| 未授权 | 不会取点 | `[province, city, district]`（`availablePrecisions()` 的 else 分支） |
| 编辑已有片段 | 用库里 `loc_precision` | `availablePrecisions(for:region:recorded:)`：记录值若是 `exact`/`street` 则**只**把该值加进来（其完整文本只存在于 `loc_text`）＋`region3` 非空则加 `district`＋`region2` 非空则加 `city`＋恒有 `province`（`LocationService.swift:182-191`） |

`LocQuality`：`none` / `coarse` / `precise`（`DiaryModels.swift:95-99`）。

### 2.8 地址文案拼接规则

- `LocationResolver.text(for:placemark:precision:)`（`LocationService.swift:122-157`）：
  - `exact`：先对"地点名 vs 街道"去重——`name` 为空取 `street`；`street` 为空或 `name.contains(street)` 取 `name`；`street.contains(name)` 取 `street`；否则 `"\(name), \(street)"`（`:126-139`），再与 `locality`/`administrativeArea`/`country` 用 `" · "` 连接（`:140-143`）。
  - 其余档位按上表字段用 `" · "` 连接，过滤空串（`:144-156`）。
- `LocationResolver.text(for:region:precision:)`（**编辑态从库列重建**，`LocationService.swift:165-177`）：
  - `city` → `[region2, region1, country]`
  - `province` → `[region1, country]`
  - `default`（含 `exact`/`street`/`district` 缺失时）→ `[region3, region2, region1, country]`
  - 注意 `exact`/`street` 无法从列重建，所以调用方要用 `recordedText`（见下）。
- 回到"记录时的档位"时**原样恢复** `recordedText`，不走重建（`DiaryViewModel.swift:421-437`）。
- `LocationSnapshot` 结构（`LocationService.swift:85-99`）：`latitude/longitude/locText/locPrecision/region/placemark` + `recordedPrecision: String?` + `recordedText: String?`（非 `nil` ＝ 从库里重建的，不是实时查询）。

### 2.9 落库路径（新建 vs 编辑）

| 场景 | 方法签名 | 写入的列 | 证据 |
|---|---|---|---|
| 新建（该天还没有 diary 行） | `addBlock(startTimeUtc: Int64, locText: String, latitude: Double, longitude: Double, locPrecision: String, region: LocRegion, contentJson: String, dayStartHour: Int) async throws -> Int64` | `loc_text, latitude, longitude, loc_precision, loc_quality, country, country_code, region1, region2, region3`（+ `content_json, search_text, created_utc, updated_utc`） | `Data/DiaryRepository.swift:421-447`（INSERT `:433-441`） |
| 新建（当天已有 diary 行） | `addBlockToDiary(diaryId: Int64, startTimeUtc: Int64, locText: String, latitude: Double, longitude: Double, locPrecision: String, region: LocRegion, contentJson: String) async throws -> Int64` | 同上 | `DiaryRepository.swift:449-468` |
| 编辑已有片段 | `updateBlockContent(blockId: Int64, contentJson: String, locText: String? = nil, locPrecision: String? = nil) async throws` | **只写 `loc_text` + `loc_precision`**（两者都非 `nil` 时）；传 `nil` 则只写 `content_json/search_text/updated_utc`。**永不写经纬度与地区列** | `DiaryRepository.swift:477-501`（UPDATE `:489-495`） |
| 回填（补地区列） | `updateBlockRegions(id:country:countryCode:region1:region2:region3:locQuality:)`，谓词带 `AND region1 = ''`（幂等保护） | `country, country_code, region1, region2, region3, loc_quality` | `DiaryRepository.swift:823-832` |
| 回填批次 | `blocksNeedingRegionBackfill(limit:)` 取 `(latitude <> 0 OR longitude <> 0) AND region1 = ''` 的块，`ORDER BY id ASC`；调用方一次 `limit: 30` | — | `DiaryRepository.swift:804-821,847-867` |

新建落库的字段来源（`DiaryViewModel.swift:283-306`）：`location` 为 `nil` 时 `locText=""`、`latitude=0`、`longitude=0`、`locPrecision="none"`、`region = LocRegion(country:"", countryCode:"", region1:"", region2:"", region3:"", locQuality:"none")`。

数据库列定义与默认值（迁移后）：`loc_text TEXT NOT NULL DEFAULT ''`、`latitude/longitude`（REAL）、`loc_precision TEXT NOT NULL DEFAULT 'none'`、`loc_quality TEXT NOT NULL DEFAULT 'none'`、`country/country_code/region1/region2/region3 TEXT NOT NULL DEFAULT ''`（`DiaryRepository.swift:112-123,152-163`）。旧库迁移时的推导规则：`region3<>''→district`、`region2<>''→city`、`region1<>''||loc_text<>''→province`；`loc_quality = latitude<>0||longitude<>0 ? 'precise' : 'coarse'`；`UPDATE edit_block SET region1 = region2 WHERE region1 = '' AND region2 <> ''`（`DiaryRepository.swift:192-206`）。

### 2.10 何时不再获取位置（规则全集，必须照搬）

1. **编辑已有片段永不重新取点**：`beginLocate()` 第一行 `guard editingIndex == nil else { return }`（`DiaryViewModel.swift:390`）；`refreshLocation()` 同样 `guard editingIndex == nil`（`:441-445`）；对外暴露 `canRelocate = (editingIndex == nil)`（`:451`）。
2. **编辑态的位置来自片段自身列**：`enterEditBlock` 调 `DiaryViewModel.locationSnapshot(from:)` 从 `locText/locPrecision/country/.../latitude/longitude` 重建（`:150,470-484`），并把 `recordedPrecision = precision`、`recordedText = block.locText`。
3. **保存过且没有位置的片段，从此无法再补**：保存前二次确认（`:251-263`），确认后写空位置；编辑时 UI 只显示只读的 `search_loc_no_loc`（`Views/Diary/DiaryPageView.swift:431-435`）。
4. **UI 上不再提供"重新获取位置"入口**（编辑态）：胶囊菜单只有精度选项（`DiaryPageView.swift:431-448`；`editor_location_retry_action` 只出现在新建分支）。
5. **新建时清空上一条的位置**：`enterWrite()` 里 `location = nil`（`DiaryViewModel.swift:122`），避免把上一个片段的位置带到新片段。
6. **自动定位只在打开新建编辑器时触发一次**：`enterWrite()` → `beginLocateIfNeeded()`（`:130,382-385`，`guard settings.autoLoc, location == nil`）。
7. 相关设计文档：`docs/location-recording.md:9-52`（规则表、为什么、实现要点、测试用例）。

---

## 3. 提醒功能

### 3.1 权限

| 项 | 事实 | 证据 |
|---|---|---|
| 是否已开启（判定） | `settings.authorizationStatus == .authorized \|\| == .provisional`（**不含 `.ephemeral`、`.denied`、`.notDetermined`**） | `Services/ReminderService.swift:6-9` |
| 请求 API | `UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])` | `ReminderService.swift:11-18`（options 在 `:13`） |
| 返回值 | `Bool`（granted）；抛错返回 `false` | `ReminderService.swift:12-17` |
| 请求时机 ① | 打开"写日记提醒"开关：`setRemindEnabled(true)` → `requestEnable()`，成功才 `ensureDailyReminder()` | `SettingsViewModel.swift:79-93` |
| 请求时机 ② | 提醒已开但通知权限为关时，设置页多出一行"通知权限"，点击 → `requestNotificationPermission()` | `SettingsView.swift:336-343`；`SettingsViewModel.swift:128-133` |
| 不在启动时请求 | 启动只做 `rearm()`，不请求授权 | `JustDiaryApp.swift:49-54`；`Views/RootView.swift:104-106` |

### 3.2 调度

```swift
static func ensureDailyReminder() async           // ReminderService.swift:20
static func markTodayWritten() async              // ReminderService.swift:29
static func cancelAll()                           // ReminderService.swift:36
static func rearm() async                         // ReminderService.swift:41
private static func nextTriggerDate(hour: Int, minute: Int, skipIfTodayWritten: Bool) -> Date?  // :50
private static func schedule(id: String, at date: Date)                                          // :64
```

| 项 | 值 | 证据 |
|---|---|---|
| **identifier** | 字面量字符串 `"daily_reminder"`（**不是命名常量**，三处硬编码：`ensureDailyReminder`、`markTodayWritten`、`cancelAll`） | `ReminderService.swift:26,33,37,38` |
| 通知标题 key | `reminder_title` | `ReminderService.swift:66` |
| 通知正文 key | `reminder_content` | `ReminderService.swift:67` |
| 声音 | `content.sound = .default` | `ReminderService.swift:68` |
| 触发器 | `UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)`，`comps = [.year,.month,.day,.hour,.minute]` | `ReminderService.swift:69-70` |
| **repeat** | **`false`**（非重复，每次只响一次，必须重新调度） | `ReminderService.swift:70` |
| 提交 | `UNUserNotificationCenter.current().add(request) { error in … }`，失败 `Log.app.error("schedule reminder failed: …")` | `ReminderService.swift:72-76` |
| 取消 | `removePendingNotificationRequests` + `removeDeliveredNotifications`（同一个 id） | `ReminderService.swift:36-39` |

中英文案（`Resources/Localizable.xcstrings`）：

| key | zh-Hans | en |
|---|---|---|
| `reminder_title` | `写日记提醒` | `Journal reminder` |
| `reminder_content` | `今天还没有写日记，花几分钟记录一下吧` | `You haven't written today — take a few minutes to record it.` |

### 3.3 触发日期计算规则（逐字）

`nextTriggerDate(hour:minute:skipIfTodayWritten:)`（`ReminderService.swift:50-62`）：

1. 取"今天"的 `[.year,.month,.day]`，把 `hour`/`minute` 设为设置值、`second = 0`，用 **`Calendar.current`**（不是 `DateUtil.calendar`）构造出 `target`。
2. `if skipIfTodayWritten || Date() >= target` → `target = Calendar.current.date(byAdding: .day, value: 1, to: target)`。
3. 结论：**有 `skipIfTodayWritten` 时一定排到明天**（即使现在还没到今天的提醒点）；否则"今天该点已过 → 明天，否则 → 今天"。

调用参数：

| 调用点 | `skipIfTodayWritten` | 说明 | 证据 |
|---|---|---|---|
| `ensureDailyReminder()` | `hasWrittenToday = await DiaryRepository.shared.getDiaryByDay(DateUtil.dayKeyOf(Date())) != nil` | 注意用的是**自然日** `dayKeyOf(Date())`，不是按 `dayStartHour` 归并后的 day key | `ReminderService.swift:23-26` |
| `markTodayWritten()` | `true`（恒定） | 保存成功后调用，直接排到明天 | `ReminderService.swift:29-34` |
| `rearm()` | 先看 `remindEnabled`：`false` → `cancelAll()` 并返回；`true` → `ensureDailyReminder()` | — | `ReminderService.swift:41-48` |

### 3.4 所有 rearm / 调度调用点（全量）

| # | 调用点 | 触发条件 | 证据 |
|---|---|---|---|
| 1 | `AppDelegate.applicationDidBecomeActive` | 每次回到前台：`await DiaryRepository.shared.prepare(); await ReminderService.rearm()` | `App/JustDiaryApp.swift:49-54` |
| 2 | `RootView.task` | 首次出现 | `Views/RootView.swift:104-106` |
| 3 | `RootView.onReceive(UIApplication.didBecomeActiveNotification)` | 回到前台（与 ① 重复） | `RootView.swift:135-138` |
| 4 | `RootView.onChange(scenePhase)` | `phase == .active`（与 ①③ 重复，实际会连跑三次） | `RootView.swift:142-147` |
| 5 | `SettingsViewModel.setLanguage(_:)` | 切换应用语言后 | `SettingsViewModel.swift:46-52` |
| 6 | `SettingsViewModel.applyRemindTime()` | 改提醒时间 | `SettingsViewModel.swift:123-126` |
| 7 | `SettingsViewModel.setRemindEnabled(true)` | 直接 `ensureDailyReminder()`（不经 `rearm`） | `SettingsViewModel.swift:79-93` |
| 8 | `DiaryViewModel.saveByDayKey(...)` 成功路径 | `await ReminderService.markTodayWritten()` | `ViewModels/DiaryViewModel.swift:309` |
| 9 | `SettingsViewModel.setWeekStart` / `setTheme` / `applyDayStart` | **不**调用 rearm（只 `bumpUiTick`/`bumpDiaryVersion`） | `SettingsViewModel.swift:40-58,110-121` |

> Android 落地：用 `WorkManager` 唯一任务名 `"daily_reminder"` + `ExistingWorkPolicy.REPLACE`，`initialDelay = 下一个触发时刻 − now`。因为 iOS 是 `repeats: false`，Android 也必须在 Worker 执行完后**重新入队**。同时保留上表 1–8 全部 rearm 点（去重后可只在 `Application.onCreate` + `ProcessLifecycleOwner` ON_START + 语言/提醒时间/开关变更/保存成功后调用）。

---

## 4. 分享长图渲染

实现：`Services/ImageShareService.swift`（`enum ShareRenderer`，`nonisolated`）。输入 `ShareBlock { time: Int64, loc: String, parts: [ContentPart] }`（`:3-7`）。

### 4.1 渲染器与位图尺寸/缩放

| 项 | 值 | 证据 |
|---|---|---|
| 渲染器 | `UIGraphicsImageRenderer(size:format:)` | `ImageShareService.swift:221` |
| `format.scale` | **`2`** | `:219` |
| `format.opaque` | `true` | `:220` |
| 逻辑尺寸 | `width = 720.0` × `layout.height` | `:10,221` |
| 实际像素 | **1440 × (2 × height)** | 由 scale=2 推出 |
| 入口 | `static func render(dayKey: String, blocks: [ShareBlock], isDark: Bool) -> UIImage?` | `:212` |

### 4.2 版式常量（全部数值）

| 常量 | 值 | 含义 | 证据 |
|---|---|---|---|
| `width` | `720.0` | 画布宽（pt） | `:10` |
| `blockLeft` | `108.0` | 正文左边距（时间轴右侧） | `:11` |
| `side` | `48.0` | 右侧留白 | `:12` |
| `contentWidth` | `width - blockLeft - side` = **`564.0`** | 正文可用宽 | `:17` |
| `tlLineX` | `61.0` | 时间轴竖线中心 x | `:13` |
| `tlLineW` | `3.0` | 时间轴竖线宽 | `:14` |
| `dateY` | `64.0` | 日期首行 y | `:15` |
| `footerH` | `34.0` | 页脚高（从底部预留） | `:16` |

`layout(dateText:startLine:blocks:)` 的纵向游标（`:54-78`）：

```
y = 64                                   // dateY
y += 70                                  // 日期行高
y += startLineHeight(startLine)          // 起步行（按 28pt 逐字量宽算行数 × 40）
y += 2 + 24                              // 细分隔线 + 间距
每个 block:
    blockTop = y
    y += 36                              // 时间行
    y += 20                              // 间距
    y += 34                              // 地点行（固定 34，不按换行累加）
    每个 part: y += rect.height
    y += 36                              // 段间
y += 34                                  // footerH
```

### 4.3 结构顺序（日期 → 起始行 → 时间轴 → 各片段 → 署名）

`render` 的绘制顺序（`:222-250`）：

1. `drawBackground`（渐变 + 3 个光斑）
2. **日期**：`L10n.dateOnly(DateUtil.parseDayKey(dayKey) ?? Date())`（key `date_day_only`），`systemFont(ofSize: 62, weight: .bold)`，`palette.text`，x=`blockLeft`
3. **起始行**：`L10n.startLine(blocks.first?.time ?? 0, locText: blocks.first?.loc ?? "")`（key `read_start_time` / `read_start_time_loc`，含 `timeOf`），`systemFont(ofSize: 28)`，`lineHeight 40`，`palette.sub`
4. `drawHairline`（横向渐变细分隔线，`blockLeft → width-side`）
5. **时间轴**：`drawTimeline(from: y, to: layout.height - footerH - 20)`，3pt 竖条 + 渐变
6. **每个片段**：`drawNode`（节点圆）+ 时间 `L10n.timeOf(block.time)`（26pt bold, `accentA`）+ 地点 `block.loc`（24pt, lineHeight 34, `palette.sub`）+ 各 part
7. **页脚署名**：`"· \(L10n.str("EntryAbility_label")) ·"`，24pt，`palette.faint`，字间距 6pt，近似居中（`:305-316`）

节点画法（`:292-303`）：`accentA` 实心圆 r=15（带 `blur 18` 阴影）＋ r=18 描边环（lineWidth 6）＋ 白色内点 r=5。

### 4.4 每种段落样式的字号 / 行高 / 高度

字体：`fontForPart`（`:182-190`）；行高：`lineHeightForPart`（`:192-200`）；尺寸：`partRect`（`:93-116`）。

| style（`ContentPartStyle`） | UI 名 | 字号/字重 | 行高 | 预留高度 | 是否换行 |
|---|---|---|---|---|---|
| `title` | 大标题 | **36 bold** | 52 | `52 + 12 = 64` | ❌ 单行 `drawText` |
| `heading` | 小标题 | **32 semibold** | 46 | `46 + 10 = 56` | ❌ 单行 |
| `quote` | 引用 | **30 regular** | 40 | `wrappedHeight(text, width: 564-22, lineHeight: 40, inkRatio: 0.87)` | ✅ 换行；内缩 22；圆角 `Radius.concentric(outer: 20, inset: 4)` = **16**；文字 x 再 +22、y +20 |
| `list` | 列表 | **30 regular** | 55 | `items.count * 55` | 逐项换行；圆点 r=5.5（11pt 直径）`accentA` 带 `blur 8`，x+12；文本 x+24，宽 `564-24` |
| `todo` | 待办 | **30 regular** | 55 | `items.count * 55` | 逐项；勾选框 28×28，圆角 `concentric(outer: Radius.badge=12, inset: 4)` = **8**；已勾选＝`accentA→accentB` 线性渐变 + 白色对勾（线宽 3.2，点 `(minX+7, midY+1) → (minX+12.5, maxY-7) → (maxX-6, minY+7)`，round cap/join）；已完成文本 alpha **0.45**；文本 x+42 |
| `image` | 图片 | — | — | `max(120, contentWidth * ratio)`，`ratio = h/w`（优先 `part.w/h`，否则解码图片尺寸，再否则 `0.75`） | 圆角 `Radius.card` = 20；图缺失时画占位渐变 + `share_image_placeholder`（26pt） |
| 其他（`body`） | 正文 | **31 regular** | 50 | `行数 * 50 + 10` | 换行；绘制时 `setAlpha(0.9)` |

`wrappedHeight` 的"墨水高度"（`:118-133`）：`max(1, lineCount-1) * lineHeight + ink + 40`，其中 `ink = max over lines( max over chars( charRatio ) * fontSize )`，字符比例：**CJK（U+4E00–U+9FFF、U+3400–U+4DBF）0.87，ASCII(<128) 0.75，其他 0.98**（`:450-459`）。

`text(for:region:precision:)`、`Radius.*` 等共用常量来自 `Views/Components/DesignSystem.swift:13-36`（`card 20 / panel 26 / field 14 / image 14 / badge 12 / bar 4 / hairline 1`，`concentric(outer:inset:) = max(0, outer - inset)`）。

### 4.5 调色板两套色值

`ShareRenderer.Palette`（`:19-46`），`isDark` 由调用方传（`DiaryViewModel.shareDiary()` 用 `UITraitCollection.current.userInterfaceStyle == .dark`，`DiaryViewModel.swift:512`）。

| 字段 | Light | Dark |
|---|---|---|
| `text` | `#191C20` | `#E1E2E8` |
| `sub` | `#44474F` | `#C5C6D0` |
| `faint` | `rgba(25,28,32,0.38)` | `rgba(225,226,232,0.38)` |
| `bgTop` | `#FBFBFE` | `#101318` |
| `bgMid` | `#F3F6FD` | `#131826` |
| `bgBottom` | `#F7F2FF` | `#131826` |
| `accentA` | `#6E8BFF` | `#4A7DFF` |
| `accentB` | `#9B6BFF` | `#A56BFF` |
| `accentC` | `#3FB6FF` | `#38BDF8` |
| `quoteBg` | `#E4EAF9` | `#2B3344` |

背景/装饰的 alpha（`:253-290`）：
- 底色：竖直线性渐变 `bgTop → bgMid(0.5) → bgBottom`（`:253-257`）。
- 光斑（radial，`0.45 → 0`）：`(620, -80, r=460, accentA)`、`(-60, 300, r=380, accentB)`、`(450, height+60, r=500, accentC)`（`:258-260`）。
- 细分隔线：横向 `clear → accentA@0.6 → clear`，从 `blockLeft` 到 `width-side`（`:274-278`）。
- 时间轴：竖直 `accentA@0.9 → accentA@0.35(0.6) → accentA@0`，矩形 `(tlLineX - 1.5, from, 3, max(1, to-from))`（`:280-290`）。
- 占位图渐变：`bgMid → accentA@0.08`（`:411-419`，经 `Palette.surface2(_:)` / `Palette.quoteBg(_:)` 这两个"忽略 self 的参数化方法"，`:440-448`）。

### 4.6 文字换行算法要点

`wrappedLines(_ text:font:width:maxLines:)`（`:149-173`）：

- **按 `Character` 逐个贪心**（不是按词）；对拉丁文会在单词中间断开。
- 单字宽度用 `NSString.boundingRect(with: CGSize(width: .greatestFiniteMagnitude, height: font.pointSize), options: .usesLineFragmentOrigin, attributes: [.font: font])` 现算（`measure`，`:175-180`）——**每个字符一次 Core Text 布局**，长日记是 O(n) 次昂贵调用。
- 断行条件：`currentW + w > width`（严格大于）**且**当前行非空；断行后新行以该字符开头，`currentW = w`——**不吞行首空格、不做标点避头尾**。
- 单字符宽于整行时仍会独占一行（不会死循环）。
- `maxLines` 分支存在但**所有调用点都传 `Int.max`**，实际上不截断（`:113,119,432`）。
- 标题/小标题/日期/时间走 `drawText`（`NSString.draw(at:)`），**完全不换行**。
- 起始行单独用 `startLineHeight(_:)`（`:80-91`）：字体 28pt，`lines` 从 1 起，越界则 `lines += 1` 且**把当前宽度清零后不再累加该字符**（与 `wrappedLines` 的实现略有出入），返回 `lines * 40`。

### 4.7 分享入口

| 入口 | 用法 | 证据 |
|---|---|---|
| 日记阅读页分享长图 | `vm.shareDiary()`：把 `blocks` 映射成 `ShareBlock(time: block.startTimeUtc, loc: block.locText, parts: ContentPartCache.parseContentCached(block.contentJson))`，在 `Task.detached(priority: .userInitiated)` 里渲染，回主线程设 `shareImage` + `showShareSheet`；失败弹 `read_share_failed_title` / `read_share_failed_msg` | `DiaryViewModel.swift:507-526` |
| 呈现 | `ShareLink(item: Image(uiImage: image), preview: SharePreview(L10n.formatDayKey(vm.actualDayKey), image: Image(uiImage: image)))` + `.buttonStyle(.glassProminent)` + `.presentationDetents([.height(170)])` | `Views/Diary/DiaryPageView.swift:52-60` |
| 备份文件分享 | `ShareLink(item: url, preview: SharePreview(L10n.str("EntryAbility_label")))` | `Views/Settings/SettingsView.swift:29-35` |

### 4.8 我在渲染器中发现的 bug / 风险（迁移时一并修掉）

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| B1 | **署名水印在英文下仍是中文**：`EntryAbility_label` 的 en 值就是 `一页时光`（与 zh 完全相同），且这个 key 名是鸿蒙遗留（应为 app name 语义） | `Services/ImageShareService.swift:306-308`；`Resources/Localizable.xcstrings` 中 `EntryAbility_label`：zh `一页时光` / en `一页时光` | 英文用户导出的长图底部印中文；Android 应改用 `app_name` 资源并给英文名 |
| B2 | 未勾选的待办框**没有设置描边颜色**（`path.lineWidth = 2; path.stroke()` 前无 `setStrokeColor`），颜色继承 CGContext 当前状态：上一个已勾选框把它设成了白色、时间轴节点把它设成 `accentA` | `:404-408`（对比 `:298,402`） | 未勾选框可能变白不可见或染成品牌色；Android 必须显式指定颜色 |
| B3 | 地点行/列表项/待办项**行高固定**（34 / 55 / 55），但绘制用的是可换行文本 | `:64,108,240-242,336-353` | 长地点或长列表项换行后与下一段重叠 |
| B4 | 标题/小标题不换行且预留高度只有单行 | `:99-102,322-324` | 长标题直接画出画布右边界被位图裁掉 |
| B5 | `imageHeight` 无上限：`max(120, 564 * ratio)`；竖构图 ratio=3 ⇒ 1692pt ⇒ 3384px 高 | `:135-147` | 巨大位图有 OOM 风险；Android 应设最大高度并等比缩放 |
| B6 | 渲染在 `Task.detached`（非主线程）执行，全程使用 `UIFont` 度量与 `NSString.draw` | `DiaryViewModel.swift:514`；`:222-250` | UIKit 文本绘制非线程安全；Android 侧在 `Dispatchers.Default` 上用 `Paint`/`StaticLayout` 是安全的，但需要显式 `Bitmap.recycle()` 管理 |
| B7 | `layout()` 返回的 `blockRects` 从未被使用（只用了 `partRects`） | `:50,74,245` | 死数据，可去掉 |
| B8 | `Palette.surface2(_:)` / `Palette.quoteBg(_:)` 是实例方法却接收一个和自己同类型的参数、忽略 `self`，且与 `quoteBg` 属性同名 | `:440-448` | 迁移时直接内联为常量函数 |
| B9 | 页脚逐字符绘制并额外加 6pt 字距，`totalWidth` 按 `字符数*6 + 整串宽度` 估算，末字符后仍多出 6pt | `:309-315` | 居中约偏左 3pt（可接受，但 Android 用 `Paint.letterSpacing` + `measureText` 可直接算准） |

---

## 5. 主题系统

### 5.1 品牌色与代码计算色

`Services/Theme.swift`：

| 令牌 | 值 / 计算 | 证据 |
|---|---|---|
| `Theme.seed` | **`Color(hex: 0x2563EB)`**（品牌蓝） | `:4` |
| `Theme.primary()` | `seed` | `:6` |
| `Theme.onPrimary()` | 动态：dark → `UIColor(hex: 0x191C20)`，light → `.white` | `:8-10` |
| `Theme.primaryContainer()` | `Color("primaryContainer")`（Asset Catalog 动态色） | `:12` |
| `Theme.glowColor()` | `seed.opacity(0.15)` | `:14` |
| `Theme.flowLightColor()` | `seed.opacity(0.40)` | `:16` |
| `Theme.flowMaskColor()` | `seed.opacity(0.05)` | `:18` |
| `Theme.primaryUIColor()` | `UIColor { trait in seed.resolved(rgb: trait) }` | `:20-22` |
| `Theme.primaryContainerUIColor()` | `UIColor(named: "primaryContainer") ?? .systemFill` | `:24-26` |

品牌色是**刻意固定的**，不跟随系统着色滑块（`docs/design-system.md:206`）。全局 tint：`RootView` 的 `.tint(Theme.primary())`（`Views/RootView.swift:60`）。

### 5.2 Asset Catalog 动态色（明暗配对全表）

来源：`JustDiary/Resources/Assets.xcassets/<name>.colorset/Contents.json`。`引用次数`＝全仓 Swift 中 `Theme.<name>()` / `Theme.<name>UIColor()` / `Color("<name>")` 的出现次数（含定义处 1 次）。

| colorset | Light | Dark | 用途 | 引用次数（文件分布） |
|---|---|---|---|---|
| `bg` | `#FAF9FD` (alpha 1) | `#101318` (alpha 1) | 页面底色（`BlobBackground` 的底层） | 2（`Theme.swift`、`Components.swift`） |
| `onSurface` | `#191C20` | `#E1E2E8` | 主文本色 | 34（Components 8、RichTextEngine 4、CalendarGrids 4、Search 3、Settings 3、Home 3、Footprint 2、Theme 2、DiaryPage 1、CalendarPane 1、RichTextView 3 …） |
| `onSurfaceVariant` | `#44474F` | `#C5C6D0` | 次要文本/图标 | 50（Search 10、Footprint 9、DiaryPage 8、Components 6、Settings 5、Home 1、DayContent 3、CalendarGrids 2、Theme 2、CalendarPane 1、RichTextEngine 1、RichTextView 1） |
| `outlineVariant` | `#C5C6D0` | `#44474F` | 分隔线/描边（常乘 0.35–0.9 透明度） | 8（Components 2、RichTextView 2、CalendarGrids 2、HomeView 1、Theme 1） |
| `primaryContainer` | `#DBEAFE` | `#1E3A8A` | 品牌容器（胶囊、徽章底） | 11（Components 3、Search 3、DiaryPage 2、Theme 2、ReadTextView 1） |
| `quoteBg` | `#E4EAF9` | `#2B3344` | 引用段背景（编辑器/阅读器 `NSAttributedString.backgroundColor`） | 4（RichTextEngine 3、Theme 1）；注意分享图里用的是 Palette 自己的 `quoteBg` 常量，不读这个 colorset |
| `glassDim` | `rgba(255,255,255,0.451)` | `rgba(38,43,54,0.502)` | 玻璃控件底色（`GlassSecondaryButton` 的 `Capsule().fill`） | 2（Components 1、Theme 1） |
| `glassBorder` | `rgba(255,255,255,0.549)` | `rgba(255,255,255,0.200)` | 玻璃控件 1pt 描边（`GlassIconBadge`） | 2（Components 1、Theme 1） |
| `flowLight` | `rgba(255,255,255,0.800)` | `rgba(255,255,255,0.349)` | `FlowLightOverlay` 流光高光（**当前无实例化点**，仅主题令牌保留） | 2（Components 1、Theme 1） |
| `blobA` | `#93C5FD` @0.502 | `#1E40AF` @0.502 | 背景光斑 A（左下） | 3（Components 2、Theme 1） |
| `blobB` | `#BFDBFE` @0.600 | `#1E3A8A` @0.549 | 背景光斑 B（右下） | 3（Components 2、Theme 1） |
| `blobC` | `#E0E7FF` @0.431 | `#312E81` @0.431 | 背景光斑 C（右上） | 3（Components 2、Theme 1） |
| `shadowColor` | `#000000` @0.078 | `#000000` @0.149 | 卡片阴影（实际再乘 0.14–0.22） | 4（Components 1、RichTextView 1、HomeView 1、Theme 1） |
| `AccentColor` | `srgb(0.145, 0.388, 0.922)` ≈ `#2563EB`，**单一外观，无暗色版** | 同左 | 全局 accent（`ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME`，`generate_project.py:159`） | 0（无 Swift 引用） |

光斑具体画法（`Views/Components/Components.swift:60-97`）：`BlobBackground` 在 `Theme.bg()` 上叠 3 个 `Circle` + `blur`（普通模式 280/240/200pt，blur 60/60/55；`dense` 模式 300/250/210pt，blur 70/65/60），位置依赖 `geo.size`。

### 5.3 明暗切换机制

1. 设置存 `theme_mode ∈ {system, light, dark}`，`load()` 时白名单校验（`AppSettings.swift:8,49`）。
2. `AppConfigService.colorScheme: ColorScheme?`——`light`→`.light`、`dark`→`.dark`、其他→`nil`（`Services/AppConfigService.swift:4-10`）。
3. `RootView` 上 `.preferredColorScheme(AppConfigService.colorScheme)`（`Views/RootView.swift:61`）——`nil` 就是跟随系统。
4. `AppConfigService.applyThemeMode()` 是**空实现**（`// Colors resolve against UIKit traits; nothing to apply statically.`，`AppConfigService.swift:12-14`）；真正的"立即生效"靠 `applyAll()`：`DayDraw.clearCache()`（日历 Canvas 把解析后的颜色缓存了，不清就还是旧配色）＋ `DiaryRepository.shared.bumpUiTick()`（`AppConfigService.swift:16-22`；`DayDraw.clearCache` 定义在 `Views/Home/CalendarGrids.swift:44-48`）。
5. `bumpUiTick()` 发 `Notification.Name("uiTickChanged")`（`Data/DiaryRepository.swift:84-91`；名字定义 `:8`）；`RootView` 收到后 `appState.uiTick += 1`（`RootView.swift:139-141`），而 `body` 里读了 `appState.uiTick`（`:30`），并 `.id("\(AppLanguage.current)#\(settingsTick)")`（`:73`）**整棵树重建**，所以 `preferredColorScheme` 会重新求值。
6. UIKit 侧（编辑器/阅读器的 AttributedString）读 `UIColor(named:)` 的 trait 版本：`Theme.onSurfaceUIColor()` / `onSurfaceVariantUIColor()` / `quoteBgUIColor()` / `primaryContainerUIColor()`（`Theme.swift:42-44,24-26`）。

> Android：`AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_YES|NO|FOLLOW_SYSTEM)` + `values-night/colors.xml`；不需要清缓存，但要确保 Compose 颜色从 `MaterialTheme.colorScheme` 读取（而不是顶层 `val` 常量），否则同样会遇到"主题切了颜色没变"。

### 5.4 `Color(hex:)` / `UIColor(hex:)` 的边界行为

```swift
init(hex: UInt32) {            // Theme.swift:48-54 (Color)
    let r = Double((hex >> 16) & 0xFF) / 255.0
    let g = Double((hex >> 8)  & 0xFF) / 255.0
    let b = Double(hex & 0xFF) / 255.0
    let a = Double((hex >> 24) & 0xFF) / 255.0
    self.init(.sRGB, red: r, green: g, blue: b, opacity: a == 0 ? 1 : a)
}
```
`UIColor(hex:)` 同形（`:61-68`）。

- `alpha` 只取最高 8 位。**传 6 位 hex（如 `0x2563EB`）时 `hex >> 24 == 0` ⇒ 透明度被强制成 `1`**——这就是"6 位写法即不透明"的隐式约定。
- 传 8 位且 alpha 恰好为 `0x00` 时，也会被强制成 **不透明**；也就是说**无法用这个构造器表达"完全透明"**，必须写成 `Color(hex:).opacity(0)`。
- 参数类型是 `UInt32`：`0xFF2563EB` 合法（`>= 2^31` 但仍是有效 `UInt32` 字面量）；`& 0xFF` 位运算保证不越界。
- 颜色空间固定 `.sRGB`；Asset Catalog 用的是 `"color-space": "srgb"`，两者一致。

---

## 6. 本地化机制

### 6.1 应用内即时切语言的实现原理

| 环节 | 实现 | 证据 |
|---|---|---|
| 语言解析 | `AppLanguage.current`：`appLanguage=="zh"`→`"zh-Hans"`；`=="en"`→`"en"`；否则 `Locale.preferredLanguages.first ?? "zh-Hans"` | `Services/L10n.swift:4-10` |
| `AppLanguage.locale` | `Locale(identifier: current)` | `L10n.swift:12-14` |
| `AppLanguage.isZh` | `current.lowercased().hasPrefix("zh")` | `L10n.swift:16-18` |
| 取字符串 | `L10n.str(key)` → `lookup(key, locale: AppLanguage.current)`；`L10n.fmt(key, args...)` → 先查表再 `String(format:)` | `L10n.swift:28-35` |
| **为什么不用 `String(localized:locale:)`** | 该初始化器按 App 的**活动语言**（`AppleLanguages` 偏好）解析，忽略传入 locale ⇒ 界面会停留在启动语言。本应用改为**直接读目标 `.lproj` 的编译后 `Localizable.strings` 表** | `L10n.swift:22-27`（源码注释） |
| 表读取 | `Bundle.main.url(forResource: "Localizable", withExtension: "strings", localization: locale)` → `NSDictionary(contentsOf:)`，按 locale 缓存于 `tables`（`NSLock` 保护） | `L10n.swift:45-60` |
| 回退链 | 目标语言表 → `en` 表 → **返回 key 本身**（不崩、不空） | `L10n.swift:37-43` |
| locale 归一化 | 精确命中 `Bundle.main.localizations` 即用；否则按 `-` 拆开取语言前缀匹配，若第二段（script）能匹配则优先（`zh-Hans-CN` → `zh-Hans`） | `L10n.swift:65-81` |
| 即时生效 | ① 所有字符串都是**运行时查表**（没有编译进视图的静态文案）；② `DateFormatter`/`Calendar` 按 `locale` id 缓存（切语言不会复用旧 locale 的实例）；③ `RootView` 用 `.environment(\.locale, AppLanguage.locale)`（`:67,99`）＋ `.id("\(AppLanguage.current)#\(tick)")` 重建视图树（`:73`） | `L10n.swift:83-114`；`RootView.swift:61-73` |
| 切换入口 | `SettingsViewModel.setLanguage(_:)`：`save()` → `SettingsStore.load()` → `bumpUiTick()` → `ReminderService.rearm()`（通知文案要跟着换语言） | `SettingsViewModel.swift:46-52` |
| 语言选择标签 | `system` → `settings_lang_system`；`zh` → 硬编码 `"中文"`；`en` → 硬编码 `"English"`（语言名不翻译） | `Views/Settings/SettingsView.swift:266-272` |

> Android：`AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(...))` + `android:localeConfig="@xml/locales_config"`（appcompat 1.8.0）。系统会**重建 Activity**，因此不需要 `.id()` 那种整树重建技巧；但所有字符串必须走 `stringResource`/`getString`，且**语言名（中文/English）依旧硬编码**才是对等的。

### 6.2 String Catalog 事实

| 项 | 值 | 证据 |
|---|---|---|
| 文件 | `JustDiary/Resources/Localizable.xcstrings` | — |
| `sourceLanguage` | `en` | JSON 顶层 |
| `version` | `"1.0"` | JSON 顶层 |
| **key 总数** | **196**（含 `""`、`"%lld"` 两条 Xcode 自动提取的占位条目，它们**没有任何 `localizations`**） | JSON |
| 语言 | **2**：`en`、`zh-Hans` | JSON |
| `extractionState` | **194 条 `stale`**，2 条无状态 | JSON |
| 为何大量 stale | 项目 `SWIFT_EMIT_LOC_STRINGS = YES`（`generate_project.py:181`；两个测试 target 为 `NO`，`:198,221`）靠 `Text(...)`/`LocalizedStringKey` 提取，而本项目**全部文案走 `L10n.str("…")` 动态查表**，Xcode 静态扫描看不到 ⇒ 被标 stale。**不要按 stale 清理这些键** | `docs/design-system.md:244-254`（曾按"零引用"清掉 39 条，是人工核实而非按 stale） |
| 命名风格 | 全小写 `snake_case`，`<功能域>_<用途>`；域前缀：`settings_` / `editor_` / `search_` / `footprint_` / `index_` / `read_` / `date_` / `time_label_` / `loc_precision_` / `reminder_` / `a11y_`；少量无前缀通用词：`save` / `cancel` / `delete` / `discard` | 统计自 JSON |
| 占位符风格 | 位置化 `%1$ld`/`%@`（zh/en 可换序）与 `%d` 混用 | 见下表 |
| 直接在 Swift 中被引用的 key | **168 个**（`L10n.str(` / `L10n.fmt(` 字面量），**全部存在，0 缺失** | 全仓扫描 |
| 无法被静态扫描到的 key | 28 个（`L10n.swift` 内部用裸 `str(`/`fmt(`；以及三元表达式传参），例如 `date_day_only`、`read_start_time`、`time_label_dawn`、`loc_precision_exact`、`index_week_head`、`editor_placeholder_edit/new`（`DiaryPageView.swift:413` 的三元）、`editor_font_body/heading/sub`（`RichTextEngine.swift:83-85` 由 `localizationKey` 返回） | — |

### 6.3 缺失 / 有问题的 key

| 类型 | 明细 | 证据 |
|---|---|---|
| 真正缺失（`L10n.str`/`fmt` 打到不存在的 key） | **0 个**。旧 bug（长图页脚印 `module_desc`）已修 | 全仓扫描；`ImageShareService.swift:306-307` 注释 |
| 无本地化的条目 | `""`、`"%lld"`（Xcode 从 `Picker("")` 等自动提取） | JSON |
| **值错误（本地化 bug）** | `EntryAbility_label`：zh `一页时光` / en `一页时光`（英文值未翻译），且 key 名是鸿蒙遗留 | JSON；用于长图水印 `ImageShareService.swift:308` 与备份分享标题 `SettingsView.swift:31` |
| 有意不翻译 | `settings_lang_zh` = `中文` / `中文`（语言自称名，正确做法） | JSON |
| InfoPlist 侧 | `NSLocationWhenInUseUsageDescription` **不在 `Info.plist` 里**，只在 build setting（`generate_project.py:170`）；en 由 `en.lproj/InfoPlist.strings:1` 提供；无 `zh-Hans.lproj`，中文用 base 值。`CFBundleLocalizations = [zh-Hans, en]`（`Info.plist:5-9`）；`CFBundleDisplayName` build setting 固定为 `"一页时光"`（`generate_project.py:166`，两语言相同） | — |
| 未接的键（历史记录） | `a11y_next_day` / `a11y_prev_day` 曾被删（UI 上可调节无障碍动作没标签）；`month_jan…month_dec`、`week_monday…week_sunday` 19 条硬编码日期名也已删，**不要再用硬编码月/星期名** | `docs/design-system.md:224-235` |

### 6.4 12 / 24 小时制的判定方法

```swift
private static func timeFormatter() -> DateFormatter {           // L10n.swift:125-139
    let id = "time|\(AppLanguage.current)"
    …
    let appLocale = Locale(identifier: AppLanguage.current)
    let deviceFormat = DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current) ?? "h"
    let uses24Hour = deviceFormat.contains("H") || deviceFormat.contains("k")
    let f = DateFormatter()
    f.locale = appLocale                                              // AM/PM 符号取应用语言
    f.dateFormat = DateFormatter.dateFormat(fromTemplate: uses24Hour ? "Hmm" : "hmm",
                                            options: 0, locale: appLocale)
    …
}
```

- **小时制取设备 locale**（`locale: .current`），尊重"设置 › 通用 › 日期与时间 › 24 小时制"；**AM/PM 符号取应用语言**（`locale: appLocale`）。
- 骨架模板 `"j"` ＝ 该 locale 的首选小时循环；结果里出现 `H` 或 `k` 即判定 24 小时制。
- 缓存 key 是 `"time|\(AppLanguage.current)"`（**不含设备设置**，切系统 24 小时制需重启进程才刷新——已知小瑕疵，Android 应每次读 `DateFormat.is24HourFormat(context)`）。
- 使用点：`L10n.timeOf(_ utcMs: Int64)`（毫秒时间戳，`:199-201`）、`L10n.timeLabel(hour:minute:)`（裸时分，`:204-206`，内部用 `DateUtil.referenceTime`）。
- 历史上的 bug：曾硬编码 `dateFormat = "HH:mm"`，英文用户看到 "Started at 21:00" 而同屏设置项是 "4:00 AM"（`L10n.swift:116-124` 注释）。

Android 对应：`android.text.format.DateFormat.is24HourFormat(context)` 决定 `HH:mm` / `h:mm a`，AM/PM 与月份/星期名由 `Locale`（应用语言）驱动。

### 6.5 日期/时间语义槽位格式表（中英对照）

`docs/design-system.md:158-172` 是设计规范，下表是 catalog 里的**逐字值**：

| 槽位 | 函数 / key | zh-Hans | en |
|---|---|---|---|
| 年 | `date_year`（`fmt`，`%1$ld`） | `%1$ld年` | `%1$ld` |
| 月（大标题） | `monthFull`（`DateFormatter` 模板 `MMMM`） | `九月` | `September` |
| 月（紧凑） | `monthName`（`Calendar.shortMonthSymbols`） | `9月` | `Sep` |
| 月 + 年 | `date_month_title`（`%1$ld年 %2$@` / `%2$@ %1$ld`，用于日历无障碍标签，`CalendarGrids.swift:229`） | `2026年 九月` | `September 2026` |
| 月+日 | `date_day_only`（`%1$@%2$ld日` / `%1$@ %2$ld`） | `9月15日` | `Sep 15` |
| 月+日+星期 | `date_day_title`（`%1$@%2$ld日 %3$@` / `%3$@, %1$@ %2$ld`） | `9月15日 周二` | `Tue, Sep 15` |
| 年+月+日+星期 | `index_week_head`（`%1$ld年%2$@%3$ld日 %4$@` / `%4$@, %2$@ %3$ld, %1$ld`） | `2026年9月15日 周二` | `Tue, Sep 15, 2026` |
| 区间 | `date_range`（`%1$@ – %2$@`，U+2013 en dash，两侧各一空格） | `9月1日 – 9月15日` | `Sep 1 – Sep 15` |
| 起始行（无地点） | `read_start_time` | `%@ 开始写` | `Started at %@` |
| 起始行（有地点） | `read_start_time_loc` | `%@ · 于%@开始写` | `Started at %@ in %@` |
| 相对日 0 / -1 / +1 | `index_today` / `index_yesterday` / `index_tomorrow` | `今天` / `昨天` / `明天` | `Today` / `Yesterday` / `Tomorrow` |
| 相对日 N | `index_days_ago` / `index_days_later`（`%1$ld`） | `%1$ld天前` / `%1$ld天后` | `%1$ld days ago` / `in %1$ld days` |
| 时刻 | `timeOf`（`"Hmm"`/`"hmm"` 模板） | `21:00` | `9:00 PM` |
| 日段标签 | `time_label_midnight` / `_dawn` / `_morning` / `_noon` / `_afternoon` / `_evening` | `凌晨 0:00` / `凌晨 %1$ld:00` / `上午 %1$ld:00` / `中午 12:00` / `下午 %1$ld:00` / `晚上 %1$ld:00` | `12:00 AM` / `%1$ld:00 AM` / `%1$ld:00 AM` / `12:00 PM` / `%1$ld:00 PM` / `%1$ld:00 PM` |
| 位置精度 | `loc_precision_exact` / `_street` / `_district` / `_city` / `_province` | `精确地点` / `街道` / `区县` / `城市` / `省份` | `Exact place` / `Street` / `District` / `City` / `Province` |

`L10n.dayStartLabel(_ hour: Int)` 的区间划分（`L10n.swift:228-237`）：`0`→midnight；`1..<6`→dawn；`6..<12`→morning；`12`→noon；`13..<18`→afternoon；`else`→evening。

### 6.6 10 个代表性 key（全量对照）

| # | key | zh-Hans | en | 用途 |
|---|---|---|---|---|
| 1 | `reminder_title` | 写日记提醒 | Journal reminder | 通知标题 |
| 2 | `reminder_content` | 今天还没有写日记，花几分钟记录一下吧 | You haven't written today — take a few minutes to record it. | 通知正文 |
| 3 | `loc_precision_exact` | 精确地点 | Exact place | 精度菜单 `exact` |
| 4 | `loc_precision_province` | 省份 | Province | 精度菜单 `province` |
| 5 | `date_day_title` | `%1$@%2$ld日 %3$@` | `%3$@, %1$@ %2$ld` | 日期标题 |
| 6 | `date_range` | `%1$@ – %2$@` | `%1$@ – %2$@` | 时间段胶囊 |
| 7 | `time_label_morning` | 上午 %1$ld:00 | %1$ld:00 AM | 日起始时间取值 |
| 8 | `settings_loc_fuzzy` | 模糊 | Approximate | 位置权限状态 |
| 9 | `search_loc_no_loc` | 未记录地点 | No place recorded | 无位置占位 |
| 10 | `footprint_summary` | 已标注 %1$ld 处 · 未记录 %2$ld 篇 | Located %1$ld · Unlocated %2$ld | 足迹页标题右侧统计 |

---

## 7. 日期 / 农历 / 入口生命周期

### 7.1 `DateUtil`（`Models/DateUtil.swift`）逐函数语义

| 函数 | 语义 | 证据 |
|---|---|---|
| `static let calendar: Calendar` | `Calendar(identifier: .gregorian)`，`locale = Locale.current`，`timeZone = .current`。**`static let`：进程内只求值一次，不随应用内切语言变化** | `:4-9` |
| `dayKeyOf(_ date: Date) -> String` | `String(format: "%04d-%02d-%02d", year, month, day)` | `:11-14` |
| `referenceTime(hour: Int, minute: Int) -> Date` | **2000-01-01** + 给定时分（给 `DatePicker` 用的锚点日） | `:16-28` |
| `hour(of:) / minute(of:) -> Int` | 取 `calendar` 的 `.hour` / `.minute` | `:30-36` |
| `parseDayKey(_ key: String) -> Date?` | 按 `-` 拆分 `compactMap(Int.init)`，**必须恰好 3 段**；时分秒归 0；解析不出返回 `nil` | `:38-49` |
| `dayKeyForUtc(_ utc: Int64, dayStartHour: Int) -> String` | 毫秒时间戳 → `Date`；若 `hour < dayStartHour` 则回退 1 天再取 dayKey（**日记归属日的唯一规则**） | `:51-59` |
| `addDays(_:_:) -> Date` | `calendar.date(byAdding: .day, value:)`，失败回原值 | `:61-63` |
| `startOfDay(_:) -> Date` | `calendar.startOfDay(for:)` | `:65-67` |
| `monthFirst(_:) -> Date` | 只保留 `[.year,.month]` | `:69-72` |
| `weekFirst(_:) -> Date` | `startOfDay(date) - weekdayIndex(date, weekStart: 设置值)` | `:74-77` |
| `weekdayIndex(_:weekStart:) -> Int` | `weekStart=="sunday"` → `wd-1`；否则 `(wd+5) % 7`（周一=0） | `:79-85` |
| `relativeDays(from:to:) -> Int` | `startOfDay` 之差的天数（`to` 减 `from`） | `:87-92` |

### 7.2 `Lunar`（`Models/Lunar.swift`）

| 项 | 值 | 证据 |
|---|---|---|
| Calendar identifier | **`.chinese`**（`Calendar(identifier: .chinese)`），`timeZone = .current` | `:4-8` |
| 输出字段 | `components(_:) -> (year: Int, month: Int, day: Int, leap: Bool)`；`leap` 来自 `c.isLeapMonth ?? false`，缺省 `year/month/day` 分别为 1 | `:19-22` |
| `dayNames`（30） | `初一 初二 初三 初四 初五 初六 初七 初八 初九 初十 十一 十二 十三 十四 十五 十六 十七 十八 十九 二十 廿一 廿二 廿三 廿四 廿五 廿六 廿七 廿八 廿九 三十` | `:10-12` |
| `monthNames`（12） | `正月 二月 三月 四月 五月 六月 七月 八月 九月 十月 冬月 腊月` | `:13-14` |
| `stems`（10） | `甲乙丙丁戊己庚辛壬癸` | `:15` |
| `branches`（12） | `子丑寅卯辰巳午未申酉戌亥` | `:16` |
| `animals`（12） | `鼠牛虎兔龙蛇马羊猴鸡狗猪` | `:17` |
| `sexagenary(_ cycleYear: Int) -> String` | `stems[(y-1)%10] + branches[(y-1)%12]` | `:24-26` |
| `dayLabel(_ date:) -> String` | `day == 1` → `("闰" if leap) + monthNames[(month-1)%12]`；否则 `dayNames[(day-1)%30]` | `:28-34` |
| `fullLabel(_ date:) -> String` | `sexagenary(year) + "年" + ("闰") + monthName + dayName` | `:36-39` |
| `yearZodiacLabel(_ date:) -> String` | `sexagenary(year) + animals[(year-1)%12] + "年"` | `:41-44` |

> 这些名称**全部硬编码中文**，不随应用语言变化（`Lunar` 没有本地化分支）——**这是刻意的**：农历名称本身是中文术语（catalog 里也没有对应 key）。Android 对应 `android.icu.util.ChineseCalendar`（API 24+），字段语义需核对：`get(ChineseCalendar.IS_LEAP_MONTH)`、月/日索引从 0/1 起算的差异，以及 `ChineseCalendar` 的"年"是干支循环年还是公历年（iOS 的 `.chinese` calendar 的 `year` 即干支循环年，`sexagenary` 直接拿它取模）。

### 7.3 `JustDiaryApp` / AppDelegate 职责

```
@main struct JustDiaryApp: App                       // App/JustDiaryApp.swift:4-28
@UIApplicationDelegateAdaptor(AppDelegate.self)      // :6
```

| 职责 | 内容 | 证据 |
|---|---|---|
| Scene 结构 | `WindowGroup { RootView().onOpenURL { handleURL($0) } }` | `:9-16` |
| URL 解析 | `guard url.scheme == "justdiary"`；`url.host == "editor"` → 读 `dayKey` query item → `LaunchIntent.openEditor(dayKey:)`；**其他 host（含空 host）→ `LaunchIntent.openEditor()`（打开今天）** | `:18-27` |
| URL scheme 声明 | `CFBundleURLTypes` → role `Editor`，scheme **`justdiary`** | `Info.plist:10-20` |
| AppDelegate：`didFinishLaunchingWithOptions` | ① `-ui-test-reset-settings` → `SettingsStore.save(AppSettings())`（把设置恢复默认）② `-ui-test-no-autoloc` → `auto_loc = false` ③ `AppConfigService.applyThemeMode()`（空实现）④ `Log.app.info("app launched v\(SettingsStore.appVersion)")` ⑤ `return true` | `:30-47` |
| AppDelegate：`applicationDidBecomeActive` | `Task { await DiaryRepository.shared.prepare(); await ReminderService.rearm() }` | `:49-54` |
| **不做的事** | 不请求定位、不请求通知权限、不应用主题（空实现）、不写 `AppleLanguages`、不注册通知分类 | 全文件 |

### 7.4 启动流程（时序）

1. `AppDelegate.didFinishLaunchingWithOptions`：测试钩子 → 主题（no-op）→ 启动日志（`JustDiaryApp.swift:31-46`）。
2. `WindowGroup` 构建 `RootView`；`.onOpenURL` 交给 `handleURL`（`:10-15,18-27`）。
3. `RootView.body`：读 `appState.uiTick`（`:30`）→ `TabView(.sidebarAdaptable)` 四个 Tab（首页/足迹/搜索/设置，标题取 `index_title`/`footprint_title`/`search_title`/`settings_title`，`systemImage` 分别 `house.fill`/`figure.walk`/`magnifyingglass`/`gearshape.fill`，`:31-54`）→ `.tabBarMinimizeBehavior(.onScrollDown)`（`:55`）→ `.tint(Theme.primary())`（`:60`）→ `.preferredColorScheme(...)`（`:61`）→ `.environment(\.diaryDynamicTypeSize, …)`（`:66`）→ `.environment(\.locale, AppLanguage.locale)`（`:67`）→ `.adaptiveLayoutReader()`（`:69`）→ `.id("\(AppLanguage.current)#\(settingsTick)")`（`:73`）→ 测试探针 overlay（`:74-86`）→ `fullScreenCover` 编辑器（`:87-103`）。
4. `RootView.task`（`:104-134`）：`DiaryRepository.prepare()` → `ReminderService.rearm()` → 解析 `-ui-test-tab <home|footprint|search|settings>`（`:107-116`）→ `-ui-test-open-editor`（`:117-119`）→ `-ui-test-open-day <key>`（`-ui-test-reset-data` 时先删该天，`:120-131`）→ `consumeLaunchIntent()`。
5. `consumeLaunchIntent()`（`:150-158`）：`LaunchIntent.target == "editor"` 时，**延迟 0.15s** 后设 `appState.editorDayKey` + `presentEditor = true`，并立即 `LaunchIntent.clear()`。
6. 前台回来：`applicationDidBecomeActive`（`JustDiaryApp.swift:49`）＋ `didBecomeActiveNotification`（`RootView.swift:135-138`）＋ `scenePhase == .active`（`:142-147`）**三处都会 `rearm()` 并 `consumeLaunchIntent()`**。
7. 编辑器关闭时 `DiaryRepository.bumpDiaryVersion()`（`:87-91`）。

### 7.5 `LaunchIntent`（`Services/LaunchIntent.swift`）

| 成员 | 默认值 | 语义 | 证据 |
|---|---|---|---|
| `static var target: String` | `"home"` | 目标页；只有 `"editor"` 会被消费 | `:4` |
| `static var dayKey: String` | `""` | 目标日记的 day key；空串＝今天 | `:5` |
| `openEditor(dayKey: String? = nil)` | — | `target = "editor"`；`dayKey = dayKey ?? ""` | `:7-10` |
| `clear()` | — | 复位成 `"home"` / `""` | `:12-15` |

URL 解析规则汇总（`JustDiaryApp.swift:18-27`）：

| URL | 结果 |
|---|---|
| `justdiary://editor` | `openEditor(nil)` → 今天 |
| `justdiary://editor?dayKey=2026-09-15` | `openEditor("2026-09-15")` |
| `justdiary://anything-else` | `openEditor(nil)`（**注意：任何非 `editor` 的 host 都打开编辑器**） |
| `justdiary://editor?dayKey=`（空值） | `openEditor("")` → 消费端把空串当 `nil`（今天） |

> Android：`<intent-filter>` 声明 `android:scheme="justdiary"` + `android:host="editor"`，`launchMode="singleTask"` 处理 `onNewIntent`；`dayKey` 从 `intent.data.getQueryParameter("dayKey")` 取。若要严格对齐"其它 host 也打开编辑器"，需在 `data` 上**不写 host** 的 filter 里兜底（或按同样规则判 host != editor 时打开今天）——建议与 iOS 保持一致以免行为分叉。

### 7.6 `MorphProgressLog`（`Services/MorphProgressLog.swift`）

| 项 | 值 | 证据 |
|---|---|---|
| 开关 | **编译期 `#if DEBUG`**（整个类型只在 Debug 存在）；调用点再叠加**运行期开关**：启动参数含 `-morph-log` | `:3,32`；`HomeView.swift:169-175`、`HomeViewModel.swift:36`、`MorphViews.swift:20,112`、`CalendarGrids.swift:158-161` |
| 单例 | `MorphProgressLog.shared`，`NSLock` 保护 | `:5-7` |
| 时间基准 | `ProcessInfo.processInfo.systemUptime`（进程启动时间，单调钟） | `:9,19` |
| `append(_ progress: Double, tag: String = "")` | 转成 `"\(tag) \(String(format: "%.5f", progress))"` | `:12-14` |
| `append(_ value: String)` | 记录 `(uptime - start, value)`；判定是否为 marker：`value.range(of: "^[-0-9.]+$", options: .regularExpression) == nil`（**不像纯数字就是 marker**）；marker 或累计满 25 条触发 flush | `:16-31` |
| 输出路径 | **`NSHomeDirectory() + "/Documents/morph.log"`**，UTF-8，`atomically: true`，**每次 flush 覆盖写全量缓冲**（不是追加） | `:29` |
| 行格式 | `"\(uptime)\t\(value)"`，`\n` 连接 | `:24` |
| 调用点 / 标签 | `HomeViewModel:37` `"reload"`；`MorphViews:21` `tag: "ym"`；`MorphViews:113` `tag: "mw"`；`HomeView:173` 任意 marker（`ym-open-start`/`ym-open-end`/`mw-open-start`/`mw-open-end` 等，`:194-218`）；`CalendarGrids:159` `"textcache-evict-\(count)"` | — |
| UI 测试驱动 | `JustDiaryUITests/MorphPerfUITests.swift:15`：`app.launchArguments = ["-morph-log"]`，测试后从模拟器容器取 `Documents/morph.log` | — |

> Android 迁移：这是纯调试设施。建议对应 `BuildConfig.DEBUG` 门控，输出到 `filesDir/morph.log`（不是 Documents），并沿用 `uptimeMillis\tvalue` 的 TSV 格式以便沿用同一套解析脚本。`-morph-log` 相当于"启动参数开关"，Android 可用 `intent` extra 或 `BuildConfig` 字段。

### 7.7 `AppState`（`ViewModels/AppState.swift`，9 行）

```swift
@Observable final class AppState {
    var activeTab: AppTab = .home     // AppTab: home / footprint / search / settings (:5)
    var presentEditor = false         // (:6)
    var editorDayKey: String?         // (:7)
    var uiTick = 0                    // (:8) 语言/主题/周起始等变更时自增，驱动 RootView 重建
}
```
`AppTab` 定义在 `Views/RootView.swift:8-13`（`home` / `footprint` / `search` / `settings`）。

### 7.8 `Log`（`Services/Log.swift`，10 行）

| 项 | 值 |
|---|---|
| subsystem | **`com.cov.justdiary`**（所有 category 相同） |
| categories | `app`、`db`、`backup`、`location`、`editor`（`:5-9`） |
| 类型 | `os.Logger`（`import os`） |

各 category 的实际使用点（便于 Android 映射 `Log.tag`）：`app`＝启动日志与提醒调度失败（`JustDiaryApp.swift:45`、`ReminderService.swift:74`）；`db`＝数据库（`DiaryRepository`）；`backup`＝导入导出（`BackupService.swift:71,109,183`、`SettingsViewModel.swift:144,171`）；`location`＝取点与反地理编码（`LocationService.swift:42,65,79`、`DiaryRepository.swift:1017`）；`editor`＝编辑器保存/插图（`DiaryViewModel.swift:243,312,499`）。

### 7.9 触觉反馈 `Haptics`（`Services/Haptics.swift`，10 行）

```swift
enum Haptics {
    private static let tapGenerator = UIImpactFeedbackGenerator(style: .light)
    static func tap() { tapGenerator.prepare(); tapGenerator.impactOccurred() }
}
```
全仓 34 处 `Haptics.tap()` 调用。Android：`View.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)`（自动尊重系统触感开关）或 `VibratorManager.defaultVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))`。

### 7.10 搜索 / 足迹页面的平台相关点（本轮要求精读的两页）

| 项 | 事实 | 证据 |
|---|---|---|
| 搜索关键词防抖 | **250 ms**：`Task.sleep(nanoseconds: 250_000_000)`，每次输入取消上一个 Task，并用自增 `currentSearchId` 丢弃过期结果 | `ViewModels/SearchViewModel.swift:66-67,153-160,263-307` |
| 时间范围 → dayKey | `TimeRangeKind.dayKeyRange(customFrom:customTo:)`：`all → ("0000-01-01","9999-12-31")`；`thisWeek → [weekFirst, today]`；`thisMonth → [monthFirst, today]`；`thisYear → [1月1日, today]`；`custom → 排序后的两端` | `SearchViewModel.swift:3-29` |
| 搜索无条件的短路 | 关键词与筛选都为空时直接清空结果，不查库 | `SearchViewModel.swift:264-272` |
| 索引状态 | `indexing = !DiaryRepository.shared.searchIndexReady()`，UI 显示 `search_preparing` / `search_loading` | `SearchViewModel.swift:274`；`Views/Search/SearchView.swift:282-292` |
| 结果分页 | `offset` 累加；有更多时显示 `search_load_more` 按钮 | `SearchViewModel.swift:294-304`；`SearchView.swift:247-252` |
| 地点选项过滤 | 大小写归一后对 `country`/`region1` 做 `contains` 过滤（客户端） | `SearchViewModel.swift:147-151` |
| 地点筛选标签 | `locFilterLabel`：`noLoc → search_loc_no_loc`；空国家 → `search_loc_picker`；只有国家 → `"\(country) · \(search_loc_country_only)"`；否则 `"\(country) · \(region1)"` | `SearchViewModel.swift:71-76` |
| 高亮渲染 | `HighlightedText` 优先解析 snippet 里的 `<hl>` 标记，否则用 `SearchUtil.highlightSegments`；命中的 run 用 `Theme.primary()` 前景 + `Theme.primaryContainer()` 背景 | `Views/Search/SearchView.swift:483-514` |
| 顶部控件滚动隐藏阈值 | `topControlsHeight`（实测，兜底 170）＋ `y > 24 && contentSize.height > containerSize.height + extra + 24` | `SearchView.swift:268-279` |
| 足迹排序 | **`(names[a] ?? a).localizedStandardCompare(names[b] ?? b) == .orderedAscending`**，先按 `count` 降序，再按名称 | `Services/FootprintDataService.swift:97-103` |
| 足迹树 | country → region1 → region2 三级；`path(of:)` 丢空段，全空时退化为 `locText` 单节点；节点 id 用 `"\u{1}"` 连接路径 | `FootprintDataService.swift:41,47-60,78-114` |
| "有地点"判定 | `!country.isEmpty \|\| !region1.isEmpty \|\| !region2.isEmpty \|\| !region3.isEmpty \|\| !locText.isEmpty` | `FootprintDataService.swift:47-50` |
| 统计 | 省＝`country\u{1}region1` 去重；市＝`country\u{1}region1\u{1}region2` 去重；国家＝`country` 去重；`blocks` 计数；`days`＝dayKey 去重 | `FootprintDataService.swift:62-76` |
| 年度统计 | 只保留 `dayKey.count >= 4` 的行，按年前 4 位分组，`days`/`places`（路径集合）去重；**年份升序** | `FootprintDataService.swift:116-136` |
| 年份胶囊 | 从已定位行的 dayKey 前 4 位取集合，**降序**（`sorted(by: >)`） | `ViewModels/FootprintViewModel.swift:86-90` |
| 竖屏/横屏版面 | 竖屏：统计 → 趋势 → 清单单栏；横屏：`blockH = max(140, min(190, layout.size.height - 262))`，趋势图 `max(72, blockH - 58)`，清单栏 `min(320, max(240, contentWidth * 0.4))` | `Views/Footprint/FootprintView.swift:36-62,125-162` |
| 唯一图表 | `Chart(vm.yearly) { BarMark(x: .value(footprint_trend_year, stat.year), y: .value(footprint_trend_days, stat.days)) }` + `Radius.bar`(4) 圆角 + 左轴 `AxisMarks { AxisGridLine(); AxisValueLabel() }` + 高 `chartHeight ?? 132` | `FootprintView.swift:125-155` |
| 展开状态清理 | `pruneExpanded()`：重建树后 `expanded.formIntersection(alive)`，防止状态无界增长 | `FootprintViewModel.swift:97-108` |
| 页面刷新触发 | 足迹页监听 `.diaryVersionChanged` → `reload()`；筛选变更直接 `Task { await vm.reload() }` | `FootprintView.swift:71-74,84-88` |

---

## 8. iOS API → Android 映射表 + 迁移判定表

### 8.1 逐条 API 映射

| # | Apple API（用到的地方） | Android 对应方案 |
|---|---|---|
| 1 | `CLLocationManager.requestWhenInUseAuthorization()`、`authorizationStatus`、`accuracyAuthorization == .fullAccuracy`（`LocationService.swift:8,11-32`） | `ActivityResultContracts.RequestMultiplePermissions`（`ACCESS_FINE_LOCATION`+`ACCESS_COARSE_LOCATION`）；`ContextCompat.checkSelfPermission`；"精确 vs 模糊"＝`FINE` 是否授予（Android 12+ 用户可选"大致位置"＝只给 COARSE） |
| 2 | `CLLocationUpdate.liveUpdates()` + 8s 超时（`LocationService.swift:46-63`） | 运行时探测 GMS：有 GMS → `FusedLocationProviderClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationToken)`；无 GMS → `LocationManager.getCurrentLocation(provider, cancellationToken, executor, consumer)`（API 30+，minSdk 33 可用）；超时用 `withTimeoutOrNull(8_000)` 包一层，两个实现都收敛到同一个 `suspend fun currentLocation(): Location?` |
| 3 | `MKReverseGeocodingRequest(location:)` + `preferredLocale` + `mapItems`（`LocationService.swift:70-82`）；`ReverseGeocoder.reverse`（`DiaryRepository.swift:1011-1031`） | `android.location.Geocoder(Locale)` + `getFromLocation(lat, lon, max, GeocodeListener)`（**API 33 的异步回调版**，正好是 minSdk）；抽成接口 `interface ReverseGeocoder { suspend fun reverse(lat: Double, lon: Double): ReversePlacemark? }`，默认实现走平台 `Geocoder`，可选 GMS 实现；`Geocoder.isPresent()` 决定是否可用 |
| 4 | `CLPlacemark`：`country`/`isoCountryCode`/`administrativeArea`/`subAdministrativeArea`/`locality`/`subLocality`/`thoroughfare`/`name`（`LocationService.swift:106-156`） | `android.location.Address`：`getCountryName()`/`getCountryCode()`/`getAdminArea()`/`getSubAdminArea()`/`getLocality()`/`getSubLocality()`/`getThoroughfare()`/`getFeatureName()`；**映射表达式按 §2.6 逐字实现，两条 iOS 路径规则统一** |
| 5 | `UIGraphicsImageRenderer` + `CGContext` + `UIBezierPath` + 渐变（整个 `ImageShareService.swift`） | `Bitmap.createBitmap(1440, h*2, ARGB_8888)` + `android.graphics.Canvas` + `Paint` + `LinearGradient`/`RadialGradient` + `Path.addRoundRect` + `StaticLayout`/`Canvas.drawText`；或 Compose `Canvas` 离屏绘制到 `ImageBitmap`。**注意 `Bitmap.recycle()` 与 OOM 兜底** |
| 6 | `UIFont.systemFont(ofSize:weight:)`（`:182-190,226-241`） | `Paint.typeface = Typeface.create(Typeface.DEFAULT, weight)` + `Paint.textSize`；无法得到 SF Pro 的字面等价（Android 默认 Roboto / 厂商字体） |
| 7 | `UNUserNotificationCenter.requestAuthorization([.alert,.sound,.badge])`、`notificationSettings()`（`ReminderService.swift:6-18`） | API 33+ 运行时权限 `POST_NOTIFICATIONS`（`ActivityResultContracts.RequestPermission`）；`NotificationManagerCompat.areNotificationsEnabled()`；**新增 `NotificationChannel`**（id/name/importance 都需要新决策） |
| 8 | `UNCalendarNotificationTrigger(dateMatching: [.year,.month,.day,.hour,.minute], repeats: false)` + identifier `"daily_reminder"`（`ReminderService.swift:69-76`） | `WorkManager`：`OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay).build()`，`enqueueUniqueWork("daily_reminder", ExistingWorkPolicy.REPLACE, request)`；Worker 里发通知并**再次入队**；`cancelUniqueWork("daily_reminder")` 对应 `cancelAll()` |
| 9 | `ShareLink(item: Image)` / `ShareLink(item: URL)` + `SharePreview`（`DiaryPageView.swift:55`、`SettingsView.swift:31`） | `Intent(Intent.ACTION_SEND).setType("image/png")` + `EXTRA_STREAM` + `FileProvider.getUriForFile` + `FLAG_GRANT_READ_URI_PERMISSION`，`Intent.createChooser`；"保存到相册"另走 `MediaStore.Images` 插入（对应 `NSPhotoLibraryAddUsageDescription`） |
| 10 | `UIDocumentPickerViewController(forOpeningContentTypes: [.data])`（`SettingsView.swift:433-462`） | `ActivityResultContracts.OpenDocument`（mime `application/octet-stream` 或 `*/*`）+ `ContentResolver.openInputStream`；导出用 `ActivityResultContracts.CreateDocument` |
| 11 | `UIImpactFeedbackGenerator(style:.light)`（`Haptics.swift:4-9`） | `View.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)` 或 `VibratorManager` + `VibrationEffect.createPredefined(EFFECT_TICK)` |
| 12 | `os.Logger(subsystem:category:)`（`Log.swift:5-9`） | `android.util.Log`，tag 固定 `app`/`db`/`backup`/`location`/`editor`（或 Timber，非必需） |
| 13 | `UserDefaults(suiteName: "group.com.cov.justdiary")`（`AppSettings.swift:30-31`） | `SharedPreferences`（Android 无 App Group）；**key 名/类型/默认值必须逐字对齐**以支持备份互导；`region_geocode_skip_v2` 用 `Set<String>` 或 JSON 字符串存长整型数组 |
| 14 | `Bundle.main.localizations` + 读 `.lproj/Localizable.strings`（`L10n.swift:53-81`） | `res/values/strings.xml`（默认 en）+ `res/values-zh-rCN/strings.xml`；运行时 `context.getString(R.string.x)`；应用内切换用 `AppCompatDelegate.setApplicationLocales` + `android:localeConfig` |
| 15 | `.preferredColorScheme` + Asset Catalog 动态色（`RootView.swift:61`、`Assets.xcassets/*.colorset`） | `AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_*)` + `res/values-night/colors.xml`；把 §5.2 的 13 组色值搬进 `colors.xml` |
| 16 | `Charts` 的 `Chart`/`BarMark`/`AxisMarks`（`FootprintView.swift:131-149`） | **自绘 Compose `Canvas`**（单张柱状图：柱宽按可用宽均分、`Radius.bar`=4 圆角、左侧轴线+刻度标签、柱色 `Theme.primary()`） |
| 17 | `String.localizedStandardCompare`（`FootprintDataService.swift:101`） | **`android.icu.text.Collator.getInstance(locale)` + `setNumericCollation(true)`**（API 24+，平台 API，不算第三方库）；先按 `count` 降序、`count` 相等再比名称 |
| 18 | `SQLite3` C API（+ FTS5 `unicode61`，`DiaryRepository.swift:210-216`） | `SQLiteOpenHelper` + 裸 SQL（不用 Room）；FTS5 需**运行时探测可编译性**（`sqlite_version()` / 建表 try-catch），失败降级为 `LIKE` 扫描（对齐 iOS 的 `ftsSupported` 双路径） |
| 19 | ZIP（raw deflate method 8 / store method 0）+ `CRC32`（`Services/ZipArchive.swift`、`BackupService.swift:90-103`） | `java.util.zip.ZipOutputStream`（默认 DEFLATED＝raw deflate）/ `ZipInputStream`（**两种 method 都要能读**）；`.jdiary` 文件名格式 `JustDiary_yyyyMMdd_HHmmss.jdiary`；包内路径 `manifest.json`、`db/just_diary.db`、`images/<file>`（读取时还要接受 `jdiary_export/` 前缀） |
| 20 | `CGImageSourceCreateThumbnailAtIndex`（降采样，`MediaStore.swift:105-118`） | `ImageDecoder.setTargetSampleSize` + `setTargetSize`（API 28+）或 `BitmapFactory.Options.inSampleSize` |
| 21 | `PhotosUI` 的 `PHPickerViewController`（`PhotoPicker: UIViewControllerRepresentable`，`Views/Diary/MediaViews.swift:104-133`） | `ActivityResultContracts.PickVisualMedia`（系统 Photo Picker）/ `TakePicture` |
| 22 | `UIApplication.openSettingsURLString` + `UIApplication.shared.open`（`SettingsViewModel.swift:100-102`） | `startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))` |
| 23 | `UIApplication.didBecomeActiveNotification` / `scenePhase == .active` / `AppDelegate.didBecomeActive`（`JustDiaryApp.swift:49`、`RootView.swift:135,142`） | `Application.onCreate` + `ProcessLifecycleOwner.get().lifecycle` 的 `ON_START` 观察者（或 `MainActivity.onResume`）；三处 rearm 合并为一处避免重复 |
| 24 | `@Observable` + `NotificationCenter`（`AppState.swift`、`uiTickChanged`/`diaryVersionChanged`） | Compose `mutableStateOf`/`StateFlow` + 事件用 `SharedFlow`；`uiTick` 用 `State<Int>`，不需要 `.id()` 重建技巧 |
| 25 | `TabView(.sidebarAdaptable)` + `Tab` + `.tabBarMinimizeBehavior(.onScrollDown)`（`RootView.swift:31-59`） | Material 3 `NavigationBar`（紧凑）/ `NavigationRail`（宽屏，宽 ≥ 600dp 时切换）+ `NavigationBarItem`；"滚动收起"用 `NestedScrollConnection` 自行实现或省略 |
| 26 | Liquid Glass 组件族：`.glassEffect`、`glassDim`、`glassBorder`、`GlassPrimaryButton`/`GlassSecondaryButton`/`GlassIconButton`/`GlassChip`/`GlassSearchField`/`GlassSheetHeader`/`GlassIconBadge`/`GlassCountBadge`/`GlassActionChip`/`GlassEmptyState`（`Components.swift:177-702`） | **Material 3 重新设计**：卡片/按钮 → `Surface` + `tonalElevation`/`Card`/`FilledTonalButton`/`Button`/`AssistChip`/`SearchBar`；**不引入 haze 等模糊库**；品牌色实底按钮用 `Button` + `containerColor = primary` |
| 27 | `BlobBackground`（3 个 `Circle` + `blur`，`Components.swift:60-97`） | Compose 用 `Brush.radialGradient` 或预渲染一张背景图（避免 `Modifier.blur` 的性能问题）；保留 `bg`/`blobA`/`blobB`/`blobC` 四组明暗色值即可 |
| 28 | `DateFormatter.dateFormat(fromTemplate: "j"/"MMMM"/"Hmm"/"hmm")`、`Calendar(.gregorian/.chinese)`、`Locale.preferredLanguages`（`L10n.swift:97-139`、`DateUtil.swift:4-9`） | `android.text.format.DateFormat.getBestDateTimePattern(locale, "j"/"Hmm"/"hmm")`、`DateFormat.is24HourFormat(context)`、`java.time` + `LocaleList.getDefault()[0]`、农历用 `android.icu.util.ChineseCalendar` |
| 29 | `ProcessInfo.processInfo.arguments` 里的 `-ui-test-*` / `-morph-log` 钩子（`JustDiaryApp.swift:33-43`、`RootView.swift:78,107-131`、`LocationService.swift:38`） | 不需要 1:1 迁移；若要保留测试钩子，用 `InstrumentationRegistry.getArguments()` 或 `BuildConfig` 字段 |
| 30 | `NSLock` / `LockedBox`（`AppSettings.swift:89-109`、`L10n.swift:45,88`） | `kotlin.synchronized` / `ReentrantLock` / `Mutex` |
| 31 | `Bundle.main.infoDictionary["CFBundleShortVersionString"]`（`AppSettings.swift:85`） | `BuildConfig.VERSION_NAME` 或 `PackageManager.getPackageInfo().versionName` |
| 32 | `NSHomeDirectory() + "/Documents"`（`MorphProgressLog.swift:29`、`ImagePathUtil.resolveImagePath`，`ContentPart.swift:233-237`） | `context.filesDir`（图片用 `filesDir/images/`，morph 日志用 `filesDir/morph.log`）；**不要用 `getExternalFilesDir`**，因为备份包路径与图片 `src` 前缀 `images/` 必须与 iOS 一致 |
| 33 | `CFBundleURLTypes` + `.onOpenURL`（`Info.plist:10-20`、`JustDiaryApp.swift:18-27`） | manifest `<intent-filter>`：`android:scheme="justdiary"`；`launchMode="singleTask"` + `onNewIntent` |
| 34 | `Task`/`Task.sleep`/`withThrowingTaskGroup`/`Task.detached`（`LocationService.swift:46`、`SearchViewModel.swift:156`、`DiaryViewModel.swift:514`） | Kotlin 协程：`withTimeoutOrNull`、`delay`、`coroutineScope` + `async`、`Dispatchers.Default`/`IO` |
| 35 | `UIApplication.shared.open`、`UIDocumentPicker`、`ShareLink` 等 UI 桥接的 UIKit 类型（`UIViewControllerRepresentable`） | Android 无对应"包装"概念，直接用 `ActivityResultLauncher` + `rememberLauncherForActivityResult` |

### 8.2 迁移判定表

判定口径：**自然迁移**＝平台有等价 API、行为可直接对齐；**需第三方库**＝必须引入依赖（给候选与理由）；**需重新设计**＝平台没有等价物或目标栈要求换实现，需明确取舍。

| 能力 | iOS 实现 | Android 方案 | 判定 | 取舍 / 备注 |
|---|---|---|---|---|
| 定位取点 | `CLLocationUpdate.liveUpdates()`，8s 超时（`LocationService.swift:34-68`） | GMS `FusedLocationProviderClient.getCurrentLocation` ↔ 原生 `LocationManager.getCurrentLocation` | **自然迁移**（含运行时 GMS 探测） | 若引入 GMS 则**需第三方库 `com.google.android.gms:play-services-location`**（FusedLocationProviderClient 只在该库中；这是本迁移唯一"必须可缺省"的运行时依赖，必须保证无 GMS 设备走原生分支）。两分支行为差异：原生 `getCurrentLocation` 在无缓存时可能久等，8s 超时要有 |
| 定位权限 | `requestWhenInUseAuthorization` + `authorizationStatus` + `accuracyAuthorization` | 运行时权限 `FINE`/`COARSE` + `checkSelfPermission` | **自然迁移** | Android 12+ 的"大致位置"＝FINE 被拒/COARSE 授予，正好对应 iOS 的 `fullAccuracy == false`；新增"仅本次允许"语义（iOS 无） |
| 反向地理编码 | `MKReverseGeocodingRequest` + `CLPlacemark` | `android.location.Geocoder`（API 33 异步 `GeocodeListener`） | **自然迁移（但要抽接口 + 重写字段映射）** | `Geocoder` 结果质量与字段完整度因设备/网络而异，且**离线时可能只有坐标**；`Address` 的 `adminArea`/`subAdminArea` 与 `CLPlacemark.administrativeArea`/`subAdministrativeArea` 不是一一对应（尤其中国直辖市、港澳）。必须保留"拿到坐标但地址为空 ⇒ 视为无位置"的规则（`DiaryViewModel.swift:417`）。GMS 的 `android.location.Geocoder` 同名前缀类已废弃，**建议直接用平台 `Geocoder`**，不要引 `play-services-location` 的 Geocoder |
| 地区三级聚合（country/region1/region2/region3） | 三条 iOS 路径（实时解析 / 回填 / DB 迁移推导） | 平台 `Address` 映射 + 统一规则 | **需重新设计** | iOS 自身两条路径规则就不一致（§2.6）。Android 应**只实现一套映射函数**并在迁移测试里对同一坐标断言与 iOS 的输出一致（互导/聚合一致性的关键） |
| 提醒调度 | `UNCalendarNotificationTrigger(repeats: false)`，identifier `daily_reminder` | `WorkManager` 唯一一次性任务 + 执行后重新入队 | **需重新设计** | WorkManager **不保证墙钟精确**（受 Doze/省电策略影响，可能延后数分钟到数十分钟）。取舍：① 接受不精确（与"每天提醒一次"的产品语义兼容，推荐）；② 若必须准点，需改用 `AlarmManager.setExactAndAllowWhileIdle` + `SCHEDULE_EXACT_ALARM`，但**与已定决策（WorkManager）冲突**。必须实现"执行后重新入队"，因为 iOS 的触发器 `repeats: false` 同样是一次性的 |
| 通知权限 | `requestAuthorization([.alert,.sound,.badge])` | `POST_NOTIFICATIONS` 运行时权限 + `NotificationChannel` | **需重新设计** | Android 必须**先建通知渠道**（渠道 id/名称/importance 是新的持久化决策，用户在系统里可单独关渠道）；"权限已开"的判定改为 `areNotificationsEnabled() && 渠道未关闭`，不能只查权限位 |
| 分享长图渲染 | `UIGraphicsImageRenderer` + CoreGraphics | `Bitmap`+`Canvas` 或 Compose 离屏绘制 | **需重新设计** | 版式常量、调色板（§4.2/4.5）可 1:1 搬；但**文字度量与换行必须重写**：`Paint.measureText`/`breakText` 替代逐字符 `boundingRect`（顺带修掉性能问题），并修 §4.8 的 B1–B5。字体无法等价（Roboto vs SF Pro），换行位置必然不同 ⇒ 长图与 iOS 不会像素一致，只要求结构与信息一致 |
| 长图/备份分享 | `ShareLink` + `SharePreview` | `ACTION_SEND` + `FileProvider` | **自然迁移** | 需要新增 `provider_paths.xml`（FileProvider 配置）；图片分享需先把 Bitmap 落到 `cacheDir` 再给 URI |
| 文件导入/导出 | `UIDocumentPickerViewController` | `ActivityResultContracts.OpenDocument` / `CreateDocument` | **自然迁移** | mime 用 `application/octet-stream`；iOS 用 `UTType.data` 等价 |
| 备份格式互导 | ZIP（raw deflate/store）+ `manifest.json` + `db/just_diary.db` + `images/*` | `java.util.zip` + 同构 SQLite 文件 | **需重新设计（必须重新实现同一份 schema 与容器）** | 严格双向互导的硬约束：Android 产出的 `.jdiary` 必须能被 iOS `ZipArchive.extract` 解开（两种 compression method 都要支持），且 `db/just_diary.db` 的表结构与列默认值必须与 iOS 完全一致（`DiaryRepository.swift:100-210`）；iOS 侧还接受 `jdiary_export/` 前缀（`BackupService.swift:127-141`），Android 两种都要读 |
| 主题（明暗） | `.preferredColorScheme` + Asset Catalog 动态色 | `AppCompatDelegate.setDefaultNightMode` + `values-night` | **自然迁移** | 13 组色值（§5.2）直接搬进 `values/colors.xml` + `values-night/colors.xml`；`Color(hex:)` 的"alpha 0 视为不透明"语义**不要照搬**（Kotlin 里直接写 `Color(0xFF2563EB)` 更清晰） |
| 玻璃/液态玻璃控件 | `.glassEffect` + 11 个 `Glass*` 组件 | Material 3 `Surface`/`Card`/`Button`/`Chip`/`SearchBar` | **需重新设计** | 按既定决策**不模仿 Liquid Glass、不引任何模糊库**。只对齐功能与交互：主行动按钮＝`Button`（primary 实底）、次要＝`FilledTonalButton`/`OutlinedButton`、图标按钮＝`IconButton`、筛选＝`FilterChip`、搜索框＝`SearchBar`、底部弹层＝`ModalBottomSheet`、空态＝自绘空态 |
| 背景光斑 | `BlobBackground`（3 Circle + blur） | Compose 径向渐变或预渲染图 | **需重新设计** | 保留四组明暗色值；不要用大半径 `Modifier.blur`（性能差），用 `Brush.radialGradient` |
| 应用内语言切换 | `L10n` 运行时读 `.lproj` 表 + `.id()` 重建 | `AppCompatDelegate.setApplicationLocales` + `localeConfig` | **需重新设计** | 机制完全不同：Android 由系统重建 Activity，不需要查表也不该自己读资源文件。取舍：① 用 `strings.xml`（迁移期最大工作量是把 196 条 key 转成资源条目）；② 语言名"中文/English"仍硬编码 |
| 日期/时间/月周名 | `DateFormatter` 模板 + `Calendar.shortMonthSymbols`/`shortWeekdaySymbols`/`veryShortWeekdaySymbols` | `DateFormat.getBestDateTimePattern` + `java.time` + `Locale` | **自然迁移** | `L10n.swift:141-206` 的槽位语义（§6.5）要保留；12/24 小时制改为每次读 `is24HourFormat(context)`（顺手修掉 iOS 的缓存瑕疵） |
| 农历 | `Calendar(identifier: .chinese)` + 自建名称表 | `android.icu.util.ChineseCalendar` + 同一份名称表 | **自然迁移（字段语义需重新核对）** | 名称表（干支/生肖/月/日）是硬编码中文常量，直接搬；`IS_LEAP_MONTH`、月日索引基准要写单测对齐 iOS 输出（可把 `Lunar` 的 5 个函数做成纯 Kotlin 工具并配 JVM 单测——正好落在本轮"JVM 单测通过"的验收范围内） |
| 足迹排序 | `localizedStandardCompare` | `android.icu.text.Collator` + `setNumericCollation(true)` | **自然迁移** | 平台 API 24+，**不算第三方库**；必须开 numeric collation 才能得到 `2 < 10` 这类数字顺序。稳定排序语义：主键 `count` 降序，次键名称升序（`FootprintDataService.swift:97-103`） |
| 图表 | Swift Charts（1 张柱状图） | 自绘 Compose `Canvas` | **需重新设计** | 只有一张柱状图（年度记录天数），自绘成本低；`Radius.bar = 4` 圆角、左轴网格线 + 标签、柱色 primary 即可；**不要引图表库** |
| 搜索全文索引 | SQLite FTS5 + `tokenize='unicode61'`，失败降级 | `SQLiteOpenHelper` FTS5 + 运行时探测 + LIKE 降级 | **需重新设计（探测与降级是必须的）** | Android 系统 SQLite 的编译选项随 API/ROM 变化，FTS5 不保证存在；必须 try-catch 建表并缓存 `ftsSupported`（对齐 `DiaryRepository.swift:210-216` 的 `ftsSupported` 模式），降级走 `LIKE`；中文分词依赖 `unicode61` 的按字切分（与 iOS 表现一致，无需 ICU 分词器） |
| 数据层 | `sqlite3` C API + 手写 SQL | `SQLiteOpenHelper` + 裸 SQL | **自然迁移** | 表结构/列默认值必须逐字一致（备份互导）；注意 Android 无 `sqlite3_backup` API，导出时"生成一个一致的 DB 快照"要用**文件复制 + 事务/`VACUUM INTO`** 或先关连接再复制（iOS 用的是 `sqlite3_backup_step`，`BackupService.swift:200-218`） |
| ZIP 容器 | `Compression` 框架 raw deflate + 自写 CRC32 | `java.util.zip`（DEFLATED/STORED，自带 CRC32） | **自然迁移** | 需同时**读** method 0 与 method 8（`ZipArchive.swift:115-123`）；大备份注意 ZIP64（`java.util.zip` 自动处理，自写解析要注意） |
| 图片降采样 | `CGImageSourceCreateThumbnailAtIndex` | `ImageDecoder.setTargetSampleSize`/`inSampleSize` | **自然迁移** | 插入图片时的上限是 `maxPixel = 2048`、JPEG 质量 `0.85`（`DiaryViewModel.swift:489-490`）；文件名格式 `img_<毫秒时间戳>.jpg`，`src` 存 `images/<文件名>`（`ImagePathUtil.normalizeSrcKey`，`ContentPart.swift:209-215`）——**必须与 iOS 一致，否则备份里的图片路径对不上** |
| 触觉反馈 | `UIImpactFeedbackGenerator(.light)` | `performHapticFeedback` / `VibrationEffect.EFFECT_TICK` | **自然迁移** | Android 无"prepare"概念 |
| 日志 | `os.Logger` | `android.util.Log`（固定 tag 五个） | **自然迁移** | 可选 Timber，但非必需 |
| 调试用帧时日志 | `MorphProgressLog`（`#if DEBUG` + `-morph-log`） | `BuildConfig.DEBUG` 门控 + `filesDir/morph.log` | **自然迁移** | 输出格式 `uptimeMillis\tvalue`，marker 规则（非纯数字立即 flush、每 25 条 flush）可照搬 |
| UI 测试钩子 | `ProcessInfo.arguments` 的 `-ui-test-*` | Instrumentation 参数 / `BuildConfig` | **需重新设计（可选）** | 本轮不做安装运行验证（只编译 + JVM 单测），这些钩子可先不迁；但**纯逻辑部分（`Lunar`、`DateUtil`、`FootprintDataService` 的排序与建树、地区映射、`nextTriggerDate`）应做成可单测的纯函数**，正好覆盖验收要求 |
| 状态与事件 | `@Observable` + `NotificationCenter` | `StateFlow`/`SharedFlow` + Compose State | **需重新设计** | `uiTick`/`diaryVersionChanged` 这类"全局失效信号"在 Compose 里用 `StateFlow<Int>` 更直接；不要强行复制 `.id()` 整树重建 |
| 导航/自适应 | `TabView(.sidebarAdaptable)` + 自适应环境值 | Material 3 `NavigationBar`/`NavigationRail` + `WindowSizeClass` | **需重新设计** | 功能对齐四个 Tab；版式按 Material 3 规范重画（横屏的"趋势图 + 清单并排"这类结构可以保留，但尺寸常量需按 dp/WindowSizeClass 重算） |

### 8.3 三条会影响判定结论的补充事实（实现者必读）

1. **`auto_time` 设置是死的**：`SettingsView` 有这一行、`AppSettings` 存了这个 key，但全仓没有任何读取点（`grep autoTime` 只命中定义、读写与 UI），`startUtc` 恒为当前时间（`DiaryViewModel.swift:25,50`）。Android 迁移要么补齐开关语义，要么明确删掉这一行——不要"迁移一个不生效的设置"。
2. **`rearm()` 在回到前台时被触发三次**（`JustDiaryApp.applicationDidBecomeActive`、`RootView.didBecomeActiveNotification`、`RootView.scenePhase == .active`），语义上幂等（同一个 id 覆盖），但 Android 侧应合并为一次，避免 `WorkManager` 反复 `REPLACE` 造成延迟窗口抖动。
3. **提醒的"今天已写"判定用自然日**（`DateUtil.dayKeyOf(Date())`，`ReminderService.swift:23`），而日记归属用 `dayKeyForUtc(_, dayStartHour:)`。当 `dayStartHour = 4` 且用户在凌晨 1 点写日记时，两者会给出不同的"今天"。这是既有行为，迁移时**保持逐字一致**（否则跨设备提醒时机不同），但要意识到它是个语义瑕疵。

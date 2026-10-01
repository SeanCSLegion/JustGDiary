package com.cov.justdiary.service

import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.model.LocQuality
import com.cov.justdiary.model.LocRegion

/**
 * 位置的记录规则。移植自 `docs/location-recording.md` 与
 * iOS `ViewModels/DiaryViewModel.swift` 的位置相关分支。
 *
 * ## 为什么单独成一层
 *
 * 这些规则是**数据正确性**规则，不是 UI 细节（方案 §8.1）：
 *
 * 1. 位置是"写这条日记时人在哪"，属于当时的事实。事后重新获取会把它悄悄改成"现在在哪"，
 *    而日记的时间戳还是过去 —— 足迹页的三级聚合也会跟着漂移；
 * 2. 因此只有**新建片段**时获取一次并落库，之后只允许调整**显示精度**
 *    （精度只改 `loc_text` / `loc_precision`，不动经纬度与地区列）。
 *
 * 全部是纯函数，因此可以在 JVM 上把 5 条规则逐条断言。
 */
object LocationRules {

    /** 一个片段已记录的位置快照（含"记录时的档位与原文"，用于回到记录档位时原样恢复）。 */
    data class LocationSnapshot(
        val latitude: Double,
        val longitude: Double,
        val locText: String,
        val locPrecision: String,
        val region: LocRegion,
        /** 记录时的档位；`exact`/`street` 的完整文本只存在于 [locText] 里。 */
        val recordedPrecision: String = locPrecision,
        /** 记录时的原文；回到记录档位时原样恢复，避免一次来回丢掉地点名。 */
        val recordedText: String = locText,
    ) {
        /** 是否真的有位置（坐标与地址都算：地址为空视为没有位置）。 */
        val hasLocation: Boolean get() = locText.isNotEmpty() || region.hasAnyRegion()
    }

    /** 编辑器里位置行的状态。 */
    sealed interface EditorLocationState {
        /** 自动定位关闭：位置行整体不显示。 */
        data object Hidden : EditorLocationState

        /** 新建片段：正在获取（胶囊显示"位置获取中…"）。 */
        data object Locating : EditorLocationState

        /** 新建片段获取失败：胶囊变成"重新获取位置"。 */
        data object LocateFailed : EditorLocationState

        /** 编辑一个没有位置的片段：只读的"未记录地点"，**不提供任何获取入口**。 */
        data object NotRecorded : EditorLocationState

        /** 已有位置：显示该片段记录的位置，只允许调整精度。 */
        data class Recorded(val snapshot: LocationSnapshot) : EditorLocationState
    }

    /**
     * 打开编辑器时是否要获取位置。
     *
     * **只有新建片段**会获取一次；编辑已有片段永不重新获取（`DiaryViewModel.swift:390` 的
     * `guard editingIndex == nil`）。
     */
    fun shouldLocateOnOpen(isNewBlock: Boolean, autoLoc: Boolean, existingLocation: Boolean): Boolean =
        isNewBlock && autoLoc && !existingLocation

    /** 打开编辑器时的初始状态。 */
    fun initialState(
        isNewBlock: Boolean,
        autoLoc: Boolean,
        snapshot: LocationSnapshot?,
    ): EditorLocationState = when {
        snapshot != null && snapshot.hasLocation -> EditorLocationState.Recorded(snapshot)
        isNewBlock && autoLoc -> EditorLocationState.Locating
        // 编辑态（无论新建失败还是编辑无位置的片段）：只读，不给获取入口
        isNewBlock -> EditorLocationState.LocateFailed
        else -> EditorLocationState.NotRecorded
    }

    /**
     * 是否仍提供"重新获取位置"入口。
     *
     * 只有**新建片段**可以重试；编辑已有片段（含今天新写的）一律不提供。
     */
    fun canRefetchLocation(isNewBlock: Boolean, state: EditorLocationState): Boolean =
        isNewBlock && state !is EditorLocationState.Recorded

    /**
     * 保存时是否需要二次确认。
     *
     * 触发条件：新建片段 + 自动定位开启 + 还没有位置。确认后按无位置保存，
     * 此后该片段**不再支持添加位置**。
     */
    fun needsSaveConfirmation(isNewBlock: Boolean, autoLoc: Boolean, hasLocation: Boolean): Boolean =
        isNewBlock && autoLoc && !hasLocation

    /**
     * 保存之后还能不能再补位置。答案永远是"不能" —— 这是规则 1 的直接后果。
     * 保留成函数是为了让调用点读起来有意图，而不是散落的 `false`。
     */
    fun canAddLocationAfterSave(): Boolean = false

    // ------------------------------------------------------------------ 缓存点的可用性

    /**
     * "新鲜"缓存点的年龄上限：2 分钟。
     *
     * iOS 的 `CLLocationUpdate.liveUpdates()` 首帧给的就是系统缓存点，这是它"总是立刻有
     * 位置"的原因。取点频率很低（写日记时才取一次），2 分钟内的点足以代表"此刻在哪"。
     */
    const val FRESH_FIX_MAX_AGE_MS: Long = 2 * 60 * 1000

    /**
     * 兜底缓存点的年龄上限：30 分钟。
     *
     * 实时定位拿不到时（室内没有卫星信号）宁可用半小时内的点，也不要让整条日记没有位置；
     * 但**绝不用更旧的点** —— 那已经不是"我写这条日记时在哪"，而是另一个地方了
     * （真机上见过系统里存着好几天前、甚至另一个城市的缓存点）。
     */
    const val STALE_FIX_MAX_AGE_MS: Long = 30 * 60 * 1000

    /** 缓存点年龄（毫秒）。`elapsedRealtimeNanos` 是开机后的单调时钟，正好用来算年龄。 */
    fun ageMillisOf(elapsedRealtimeNanos: Long, nowNanos: Long): Long =
        ((nowNanos - elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)

    /** 这个年龄的缓存点能不能直接当作"当前位置"。 */
    fun isFreshFix(ageMillis: Long): Boolean = ageMillis in 0..FRESH_FIX_MAX_AGE_MS

    /** 实时定位失败时，这个年龄的缓存点还能不能兜底。 */
    fun isUsableStaleFix(ageMillis: Long): Boolean = ageMillis in 0..STALE_FIX_MAX_AGE_MS

    /**
     * 回到前台时要不要补一次定位。
     *
     * 判据全部来自编辑器当下的状态：**正在新建片段**、`auto_loc` 开着、**还没有位置**、
     * 而且此刻没有正在跑的请求。任一不满足都不补 —— 尤其是"已经有位置"这条：
     * 补一次会把用户刚看到的地点悄悄换掉。
     */
    fun shouldRetryOnForeground(
        isRead: Boolean,
        isEditingExistingBlock: Boolean,
        autoLoc: Boolean,
        hasLocation: Boolean,
        locating: Boolean,
    ): Boolean = !isRead && !isEditingExistingBlock && autoLoc && !hasLocation && !locating

    /**
     * 坐标拿到了但反地理编码为空：**按没有位置处理**。
     *
     * 否则会存下一条"有坐标、没有地名、且再也补不上"的记录。
     */
    fun acceptsGeocodeResult(locText: String, region: LocRegion): Boolean =
        locText.isNotEmpty() || region.hasAnyRegion()

    /** 编辑态可选的位置精度档（记录档 + 地区列能表达的级别）。 */
    fun precisionOptions(snapshot: LocationSnapshot): List<String> =
        LocationResolver.availablePrecisionsForRecorded(snapshot.region, snapshot.recordedPrecision)

    /**
     * 给定精度档下位置胶囊应显示的文本。
     *
     * 回到**记录时的档位**时原样恢复原文（`recordedText`），其余档位由地区列重建 ——
     * 这样一次来回不会丢掉地点名。
     */
    fun textForPrecision(snapshot: LocationSnapshot, precision: String): String =
        if (precision == snapshot.recordedPrecision) {
            snapshot.recordedText
        } else {
            LocationResolver.textFromRegion(snapshot.region, precision)
        }

    /**
     * 新建片段时的默认精度：**精确授权 → `exact`，模糊授权 → `province`**
     * （`LocationService.swift:104-105`）。
     */
    fun defaultPrecisionForNewLocation(precise: Boolean): String =
        if (precise) LocPrecision.exact else LocPrecision.province

    /** 新建片段时的位置质量档：精确授权 → `precise`，否则 `coarse`。 */
    fun defaultQualityForNewLocation(precise: Boolean): String =
        if (precise) LocQuality.precise else LocQuality.coarse

    /** 地区列是否表达了任何地点信息。 */
    private fun LocRegion.hasAnyRegion(): Boolean =
        country.isNotEmpty() || region1.isNotEmpty() || region2.isNotEmpty() || region3.isNotEmpty()

    /**
     * **记录新位置**时的地区列（`LocationService.swift:99-107`）。
     *
     * 注意 `region1` 只取 `administrativeArea`、**不退到 `subAdministrativeArea`**；
     * 回填历史片段时用的却是另一套规则（[RegionBackfiller.reversePlacemark] 会退）。
     * 这两条差异都是 iOS 的既有行为，刻意各自保留，因此两个映射都做成纯函数以便对比断言。
     */
    fun regionFromLivePlacemark(placemark: PlacemarkText?, locQuality: String): LocRegion {
        val hasBoth =
            placemark != null && placemark.locality.isNotEmpty() && placemark.subAdministrativeArea.isNotEmpty()
        return LocRegion(
            country = placemark?.country.orEmpty(),
            countryCode = placemark?.isoCountryCode.orEmpty(),
            region1 = placemark?.administrativeArea.orEmpty(),
            // region2 = subAdministrativeArea ?? locality
            region2 = placemark?.subAdministrativeArea.orEmpty().ifEmpty { placemark?.locality.orEmpty() },
            // region3 只在 locality 与 subAdministrativeArea 都存在时才取 subLocality
            region3 = if (hasBoth) placemark.subLocality else "",
            locQuality = locQuality,
        )
    }
}

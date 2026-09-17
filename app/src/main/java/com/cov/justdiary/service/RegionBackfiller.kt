package com.cov.justdiary.service

import com.cov.justdiary.data.DiaryRepository
import com.cov.justdiary.model.LocQuality

/**
 * iOS `ReverseGeocoder.reverse(_:)` 的返回结构。
 *
 * 与 [PlacemarkText] 的区别：这个只保留**能落库的列**（`country/country_code/region1..3`），
 * 因为回填路径不需要拼地址文本。
 */
data class ReversePlacemark(
    val country: String,
    val countryCode: String,
    val region1: String,
    val region2: String,
    val region3: String,
)

/**
 * 历史片段的地区列回填。移植自 iOS `DiaryRepository.backfillBlockRegions()` +
 * `ReverseGeocoder`（`DiaryRepository.swift:804-869`、`1011-1028`）。
 *
 * 用途：`auto_loc` 由关变开时，把历史片段里已经存了经纬度、但 `region1` 为空的记录补上
 * 国家 / 省 / 市（这样搜索的"地区筛选"与足迹的层级才能覆盖老数据）。
 *
 * ## ⚠️ 一条容易漏掉的差异
 *
 * iOS 的**回填**路径与**记录新位置**路径对 `region1` 的定义**并不相同**：
 *
 * | 路径 | region1 |
 * | --- | --- |
 * | 记录新位置（`LocationResolver.resolve`） | `administrativeArea ?? ""` |
 * | 回填（`ReverseGeocoder.reverse`） | `administrativeArea ?? subAdministrativeArea ?? ""` |
 *
 * 这里按"逐路径复刻"处理，以保证同一份数据在两端回填出同样的列值
 * （属于 [RegionBackfiller] 的既有行为，不是这里新引入的偏差）。
 *
 * ## 幂等与放弃策略
 *
 * - 更新语句带 `AND region1 = ''`，重复执行不会覆盖已有值；
 * - 反地理编码失败的 id 记入跳过名单（[SettingsStore.markRegionGeocodeSkipped]，上限 2000），
 *   避免每次开屏都重试同一批失败项；
 * - 每轮只处理 [BATCH_LIMIT] 条（与 iOS 的 `limit: 30` 一致）。
 */
object RegionBackfiller {

    /** 每轮回填上限（iOS 为 `limit: 30`）。 */
    const val BATCH_LIMIT: Int = 30

    /**
     * 反地理编码结果 → 落库列（iOS `ReverseGeocoder.reverse` 的字段映射）。
     *
     * `region3` 要求 `locality` 与 `subAdministrativeArea` **都存在**才取 `subLocality`；
     * iOS 判的是 `!= nil`，Android 的 [PlacemarkText] 把 null 与空串都归一成 `""`，
     * 因此这里判空串 —— 除"字段存在但为空串"这一种情形外结果一致。
     */
    fun reversePlacemark(placemark: PlacemarkText?): ReversePlacemark? {
        if (placemark == null) return null
        return ReversePlacemark(
            country = placemark.country,
            countryCode = placemark.isoCountryCode,
            region1 = placemark.administrativeArea.ifEmpty { placemark.subAdministrativeArea },
            region2 = placemark.subAdministrativeArea.ifEmpty { placemark.locality },
            region3 = if (placemark.locality.isNotEmpty() && placemark.subAdministrativeArea.isNotEmpty()) {
                placemark.subLocality
            } else {
                ""
            },
        )
    }

    /**
     * 执行一轮回填。
     *
     * @param reverseGeocode 经纬度 → 反地理编码结果；返回 null 视为失败
     * @return 成功回填的条数
     */
    suspend fun run(
        repository: DiaryRepository,
        reverseGeocode: suspend (latitude: Double, longitude: Double) -> PlacemarkText?,
    ): Int {
        val pending = repository.blocksNeedingRegionBackfill(BATCH_LIMIT)
        if (pending.isEmpty()) return 0

        var updated = 0
        val failed = mutableListOf<Long>()
        for ((id, latitude, longitude) in pending) {
            val place = reversePlacemark(reverseGeocode(latitude, longitude))
            if (place == null) {
                failed.add(id)
                continue
            }
            repository.updateBlockRegions(
                id = id,
                country = place.country,
                countryCode = place.countryCode,
                region1 = place.region1,
                region2 = place.region2,
                region3 = place.region3,
                // iOS 回填时固定写 precise（不区分当前授权精度）
                locQuality = LocQuality.precise,
            )
            updated++
        }
        if (failed.isNotEmpty()) repository.markRegionGeocodeSkipped(failed)
        Log.location.i("地区回填：成功 $updated 条，放弃 ${failed.size} 条")
        return updated
    }
}

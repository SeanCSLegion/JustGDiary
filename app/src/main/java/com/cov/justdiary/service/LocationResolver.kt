package com.cov.justdiary.service

import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.model.LocRegion

/**
 * 位置文案的拼装。移植自 iOS `Services/LocationService.swift` 的 `LocationResolver`。
 *
 * 分两个入口：
 * - [textFromPlacemark]：刚拿到反地理编码结果时用（能用上地点名与街道）；
 * - [textFromRegion]：**从库里存的地区列重建**地址（编辑态调整精度时用）。
 *
 * 后者是纯函数，因此可以在 JVM 上精确断言 —— 它是"精度调整不能丢地点名"这条规则的实现。
 */
object LocationResolver {

    const val SEPARATOR = " · "

    /**
     * 由反地理编码结果生成地址文本（`LocationService.swift:122-157`）。
     *
     * `exact` 档会把地点名与街道去重：MapKit 常把两者报成同一个字符串
     * （"深南大道, 深南大道"），只保留一个。
     */
    fun textFromPlacemark(
        placemark: PlacemarkText?,
        precision: String,
    ): String {
        if (placemark == null) return ""
        return when (precision) {
            LocPrecision.exact -> {
                val street = placemark.thoroughfare
                val name = placemark.name
                val place = when {
                    name.isEmpty() -> street
                    street.isEmpty() || name.contains(street) -> name
                    street.contains(name) -> street
                    else -> "$name, $street"
                }
                join(place, placemark.locality, placemark.administrativeArea, placemark.country)
            }

            LocPrecision.street -> join(
                placemark.thoroughfare.ifEmpty { placemark.subLocality },
                placemark.locality,
                placemark.administrativeArea,
                placemark.country,
            )

            LocPrecision.district -> join(
                // 区县名在 `subLocality`（中国的「南山区」就在这里），`subAdministrativeArea`
                // 是县级（美国是 county）—— 两者都没有时才退到城市。此前**只**看
                // `subAdministrativeArea`，于是「区县」级在中国常常只显示到市，
                // 与地区列重建的文案（region3 · region2 · …）对不上（iOS 同处已修）。
                placemark.subLocality.ifEmpty { placemark.subAdministrativeArea },
                placemark.locality,
                placemark.administrativeArea,
                placemark.country,
            )

            LocPrecision.city -> join(
                placemark.locality,
                placemark.administrativeArea,
                placemark.country,
            )

            else -> join(placemark.administrativeArea, placemark.country)
        }
    }

    /**
     * 由库里的地区列重建地址文本（`LocationService.swift:165-177`）。
     *
     * `exact` / `street` 需要地点名与街道，而库里只存 `region1..3`，
     * 因此它们回落到列能表达的最细一级；调用方在这种情况下应当使用
     * 快照里记录的原文（`LocationSnapshot.recordedText`）。
     */
    fun textFromRegion(region: LocRegion, precision: String): String = when (precision) {
        LocPrecision.city -> join(region.region2, region.region1, region.country)
        LocPrecision.province -> join(region.region1, region.country)
        else -> join(region.region3, region.region2, region.region1, region.country)
    }

    /**
     * 已记录位置的片段还能选哪些精度档（`LocationService.swift:182-191`）：
     * 库里的列能表达的级别，**加上它记录时的那一档**（只有那一档的文本里可能有地点名/街道）。
     */
    fun availablePrecisionsForRecorded(region: LocRegion, recorded: String): List<String> {
        val levels = mutableListOf<String>()
        if (recorded == LocPrecision.exact || recorded == LocPrecision.street) levels.add(recorded)
        if (region.region3.isNotEmpty()) levels.add(LocPrecision.district)
        if (region.region2.isNotEmpty()) levels.add(LocPrecision.city)
        levels.add(LocPrecision.province)
        return levels
    }

    /**
     * 这次定位能支撑的**最细**级别（对应 iOS `LocationResolver.maxPrecision`）。
     *
     * | 系统给的 | 上限 | 依据 |
     * | --- | --- | --- |
     * | 只有模糊授权（`ACCESS_COARSE_LOCATION`） | 城市 | 坐标被吸附到"所在区域"的代表点，区县不再可信 |
     * | 精确授权 + 精度 ≤ 10m | 精确地点 | 十米级才指得到某一栋楼 |
     * | ≤ 100m | 街道 | |
     * | ≤ 1000m | 区县 | |
     * | ≤ 5000m | 城市 | |
     * | 更粗 | 省份 | 只有省界还站得住 |
     *
     * 精度未知（Android 用 `0` 表示"没有这个信息"）时按**城市**算：既不冒进到
     * "精确地点"，也不像 iOS 的负值那样一路退到省份 —— iOS 的负值意思是"坐标无效"，
     * 那种点在本项目的 `currentLocation` 里已经被直接丢掉，不会走到这里。
     */
    fun maxPrecision(precise: Boolean, horizontalAccuracy: Float): String = when {
        !precise -> LocPrecision.city
        horizontalAccuracy <= 0f -> LocPrecision.city
        horizontalAccuracy <= 10f -> LocPrecision.exact
        horizontalAccuracy <= 100f -> LocPrecision.street
        horizontalAccuracy <= 1000f -> LocPrecision.district
        horizontalAccuracy <= 5000f -> LocPrecision.city
        else -> LocPrecision.province
    }

    /**
     * 这次记录实际用的级别。
     *
     * `preferred` 是用户上一次手动选过的级别（`AppSettings.defaultLocPrecision`，
     * `auto` = 跟随权限）。手动选的级别只会让结果**更粗**：权限算出来的上限永远是
     * 天花板，用户的选择不能把它顶穿。
     */
    fun effective(preferred: String, cap: String): String =
        if (!LocPrecision.all.contains(preferred)) cap else LocPrecision.coarser(preferred, cap)

    /**
     * 新建片段时菜单能给的级别：不粗于 [maxPrecision] —— 系统给不出更细的位置，
     * 菜单里就不该有更细的级别（iOS `availablePrecisions(maxPrecision:)`）。
     */
    fun availablePrecisions(maxPrecision: String, placemark: PlacemarkText? = null): List<String> {
        if (placemark != null && placemark.thoroughfare.isEmpty()) {
            if (maxPrecision == LocPrecision.exact && placemark.name.isNotEmpty()) {
                return listOf(LocPrecision.exact) + LocPrecision.levels(LocPrecision.district)
            }
            if (maxPrecision == LocPrecision.exact || maxPrecision == LocPrecision.street) {
                return LocPrecision.levels(LocPrecision.district)
            }
        }
        return LocPrecision.levels(cappedAt = maxPrecision)
    }

    /**
     * 分享长图上的地点文案：**只用地区列重建**，`locText` 根本不是它的输入。
     *
     * 这是"分享不会泄露地点名与街道"的硬保证。此前分享直接把库里的 `loc_text` 抄进长图，
     * 而早期版本（以及地区列还没回填的记录）会把地点名 / 街道写在 `loc_text` 里、
     * `loc_precision` 却只标到 `province` —— 只要分享走原文，就总有一条路径会漏。
     * 改成重建之后：
     *  - 上限只取到区县（[LocPrecision.shareable]），精确地点与街道不在其中；
     *  - 记录精度比上限更细时按上限降级，比上限更粗时按记录的来 —— **只能更粗**；
     *  - 重建为空（地区列缺失、或这行本来就没有地点）时**不显示地点**，而不是退回原文。
     */
    fun shareText(recorded: String, region: LocRegion, cap: String): String {
        // 上限先夹到"可分享"的范围：比区县更细、或不在梯子上的值，一律按最细的可分享
        // 级别（区县）处理。设置层 `AppSettings.from` 已经挡过一次，这里是最后一道。
        val capLevel = when {
            LocPrecision.shareable.contains(cap) -> cap
            cap == LocPrecision.none -> LocPrecision.none
            else -> LocPrecision.district
        }
        if (capLevel == LocPrecision.none || !LocPrecision.all.contains(recorded)) return ""
        val level = LocPrecision.coarser(recorded, capLevel)
        return if (LocPrecision.all.contains(level)) textFromRegion(region, level) else ""
    }

    private fun join(vararg parts: String): String =
        parts.filter { it.isNotEmpty() }.joinToString(SEPARATOR)
}

/**
 * 反地理编码结果的文本侧视图（只有拼地址用到的字段）。
 *
 * 与平台解耦：Android 侧由 `Geocoder` 的 `Address` 映射过来，测试里直接构造。
 */
data class PlacemarkText(
    val name: String = "",
    val thoroughfare: String = "",
    val subLocality: String = "",
    val locality: String = "",
    val subAdministrativeArea: String = "",
    val administrativeArea: String = "",
    val country: String = "",
    val isoCountryCode: String = "",
)

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
     * 新建片段时可选的全部精度档：授权且精确 → 全部 5 档；
     * 否则只有省 / 市 / 区（`LocationService.swift:193-198`）。
     */
    fun availablePrecisionsForNewLocation(authorized: Boolean, precise: Boolean): List<String> =
        if (authorized && precise) {
            LocPrecision.all
        } else {
            listOf(LocPrecision.province, LocPrecision.city, LocPrecision.district)
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

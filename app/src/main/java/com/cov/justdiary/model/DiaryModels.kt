package com.cov.justdiary.model

/** 一天一条日记。对应 iOS `DiaryModels.swift` 的 `DiaryRecord`。 */
data class DiaryRecord(
    val id: Long,
    val dayKey: String,
    val summary: String,
    val searchText: String,
    val createdUtc: Long,
    val updatedUtc: Long,
)

/** 一条日记里的一个片段。对应 iOS 的 `EditBlock`。字段与列一一对应。 */
data class EditBlock(
    val id: Long,
    val diaryId: Long,
    val startTimeUtc: Long,
    val locText: String,
    val latitude: Double,
    val longitude: Double,
    val contentJson: String,
    val searchText: String,
    val locPrecision: String,
    val locQuality: String,
    val country: String,
    val countryCode: String,
    val region1: String,
    val region2: String,
    val region3: String,
    val createdUtc: Long,
    val updatedUtc: Long,
)

/** 反地理编码得到的地区列集合。对应 iOS 的 `LocRegion`。 */
data class LocRegion(
    val country: String,
    val countryCode: String,
    val region1: String,
    val region2: String,
    val region3: String,
    val locQuality: String,
)

/** 搜索的地点筛选。对应 iOS 的 `LocFilter`。 */
data class LocFilter(
    val country: String = "",
    val region1: String = "",
    val noLoc: Boolean = false,
)

/** 地点筛选面板里的一个候选项。对应 iOS 的 `LocOption`。 */
data class LocOption(
    val country: String,
    val region1: String,
    val count: Int,
)

/** 足迹页的明细行。对应 iOS 的 `FootprintRow`。 */
data class FootprintRow(
    val id: Long,
    val dayKey: String,
    val startTimeUtc: Long,
    val latitude: Double,
    val longitude: Double,
    val locText: String,
    val locQuality: String,
    val locPrecision: String,
    val country: String,
    val countryCode: String,
    val region1: String,
    val region2: String,
    val region3: String,
    val summary: String,
)

/** 搜索结果里的一条。对应 iOS 的 `SearchResultItem`。 */
data class SearchResultItem(
    val id: Long,
    val dayKey: String,
    val summary: String,
    val snippet: String,
    val updatedUtc: Long,
)

/** 搜索结果一页。`total = -1` 表示库还没准备好（与 iOS 一致）。 */
data class SearchPageResult(
    val items: List<SearchResultItem>,
    val hasMore: Boolean,
    val total: Int,
)

/** 位置精度档位。这些字符串会落库，因此是稳定值。对应 iOS `LocPrecision`。 */
object LocPrecision {
    const val none = "none"
    const val province = "province"
    const val city = "city"
    const val district = "district"
    const val street = "street"
    const val exact = "exact"

    /** 可选顺序（与 iOS `LocPrecision.all` 一致）。 */
    val all: List<String> = listOf(exact, street, district, city, province)
}

/** 位置质量档位。对应 iOS `LocQuality`。 */
object LocQuality {
    const val none = "none"
    const val coarse = "coarse"
    const val precise = "precise"
}

/** 足迹树节点的路径分隔符（与 iOS 一致，用它拼接节点 id）。 */
const val FOOTPRINT_PATH_SEPARATOR: Char = '\u0001'

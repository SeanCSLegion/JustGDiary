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

    /**
     * 梯子上唯一一个**不是级别**的成员：它只用于「上一次手动选的精度」这个偏好，
     * 意思是"跟随定位权限算出来的上限"（iOS `LocPrecision.auto`）。
     */
    const val auto = "auto"

    /** 可选顺序（与 iOS `LocPrecision.all` 一致）：由细到粗，**索引越大越粗**。 */
    val all: List<String> = listOf(exact, street, district, city, province)

    /**
     * 分享长图允许出现的级别：**精确地点与街道不在其中**。
     *
     * 这不是"默认值"而是硬约束：`LocationResolver.shareText` 只按这三档重建地址，
     * 用户在任何设置下都无法把地点名 / 街道分享出去（`docs/location-recording.md` §五）。
     */
    val shareable: List<String> = listOf(district, city, province)

    /** 在梯子上的位置：0 = 最细。`none` 与任何未知值按"最粗"处理，坏数据因此往安全一侧倒。 */
    fun rank(precision: String): Int {
        val index = all.indexOf(precision)
        return if (index >= 0) index else all.size
    }

    /** 两个级别里**更粗**的那个。 */
    fun coarser(a: String, b: String): String = if (rank(a) >= rank(b)) a else b

    /**
     * 梯子上**不细于** `cap` 的级别（由细到粗）。
     *
     * 上限只砍掉更细的那几级：`levels(cappedAt = city)` = [city, province] ——
     * 于是"系统只给了模糊位置"时，菜单里不会出现区县、街道与精确地点。
     */
    fun levels(cappedAt: String): List<String> {
        val levels = all.filter { rank(it) >= rank(cappedAt) }
        return levels.ifEmpty { listOf(province) }
    }
}

/** 位置质量档位。对应 iOS `LocQuality`。 */
object LocQuality {
    const val none = "none"
    const val coarse = "coarse"
    const val precise = "precise"
}

/** 足迹树节点的路径分隔符（与 iOS 一致，用它拼接节点 id）。 */
const val FOOTPRINT_PATH_SEPARATOR: Char = '\u0001'

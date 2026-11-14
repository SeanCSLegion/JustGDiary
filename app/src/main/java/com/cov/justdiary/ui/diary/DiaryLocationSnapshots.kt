package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.EditBlock
import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.model.LocRegion
import com.cov.justdiary.service.LocationRules

/** 片段自己的地区列（编辑态重建快照、分享重建地址都用它）。 */
internal fun regionOf(block: EditBlock): LocRegion = LocRegion(
    country = block.country,
    countryCode = block.countryCode,
    region1 = block.region1,
    region2 = block.region2,
    region3 = block.region3,
    locQuality = block.locQuality,
)

/** 从库里的片段重建一份只读位置快照；没有位置时返回 null。 */
internal fun locationSnapshotOf(block: EditBlock): LocationRules.LocationSnapshot? {
    val precision = block.locPrecision.ifEmpty { LocPrecision.none }
    if (block.locText.isEmpty() && precision == LocPrecision.none) return null
    return LocationRules.LocationSnapshot(
        latitude = block.latitude,
        longitude = block.longitude,
        locText = block.locText,
        locPrecision = precision,
        region = regionOf(block),
        maxPrecision = LocPrecision.province,
        recordedPrecision = precision,
        recordedText = block.locText,
    )
}

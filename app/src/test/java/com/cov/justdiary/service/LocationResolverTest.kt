package com.cov.justdiary.service

import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.model.LocRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 位置文案与精度档位测试。
 *
 * 这两个函数是"精度调整不能丢地点名"与"编辑已有片段只能选它能表达的档位"两条规则的实现，
 * 全部是纯函数（`LocationService.swift:165-198`），因此逐条断言。
 */
class LocationResolverTest {

    private val shenzhen = LocRegion(
        country = "中国",
        countryCode = "CN",
        region1 = "广东省",
        region2 = "深圳市",
        region3 = "南山区",
        locQuality = "precise",
    )

    private val cityOnly = shenzhen.copy(region3 = "")
    private val countryOnly = shenzhen.copy(region1 = "", region2 = "", region3 = "")

    @Test
    fun `address is rebuilt from the stored region columns`() {
        // default（district / exact / street 的回落）：区 · 市 · 省 · 国家
        assertEquals(
            "南山区 · 深圳市 · 广东省 · 中国",
            LocationResolver.textFromRegion(shenzhen, LocPrecision.exact),
        )
        assertEquals(
            "南山区 · 深圳市 · 广东省 · 中国",
            LocationResolver.textFromRegion(shenzhen, LocPrecision.street),
        )
        assertEquals(
            "南山区 · 深圳市 · 广东省 · 中国",
            LocationResolver.textFromRegion(shenzhen, LocPrecision.district),
        )
        // city：市 · 省 · 国家
        assertEquals("深圳市 · 广东省 · 中国", LocationResolver.textFromRegion(shenzhen, LocPrecision.city))
        // province：省 · 国家
        assertEquals("广东省 · 中国", LocationResolver.textFromRegion(shenzhen, LocPrecision.province))
    }

    @Test
    fun `missing levels are skipped rather than leaving empty separators`() {
        assertEquals("深圳市 · 广东省 · 中国", LocationResolver.textFromRegion(cityOnly, LocPrecision.district))
        assertEquals("中国", LocationResolver.textFromRegion(countryOnly, LocPrecision.province))
        assertEquals("中国", LocationResolver.textFromRegion(countryOnly, LocPrecision.city))
    }

    @Test
    fun `recorded precisions keep the level they were recorded at`() {
        // 记录在 exact：可以保留 exact，还能降级到列能表达的级别
        assertEquals(
            listOf(LocPrecision.exact, LocPrecision.district, LocPrecision.city, LocPrecision.province),
            LocationResolver.availablePrecisionsForRecorded(shenzhen, LocPrecision.exact),
        )
        // 记录在 street：同理
        assertTrue(
            LocationResolver.availablePrecisionsForRecorded(shenzhen, LocPrecision.street)
                .contains(LocPrecision.street),
        )
        // 记录在 city：没有 region3，所以给不出 district
        assertEquals(
            listOf(LocPrecision.city, LocPrecision.province),
            LocationResolver.availablePrecisionsForRecorded(cityOnly, LocPrecision.city),
        )
        // 只有国家：只剩省这一档
        assertEquals(
            listOf(LocPrecision.province),
            LocationResolver.availablePrecisionsForRecorded(countryOnly, LocPrecision.province),
        )
    }

    @Test
    fun `new location offers all levels only when the permission is precise`() {
        assertEquals(
            LocPrecision.all,
            LocationResolver.availablePrecisionsForNewLocation(authorized = true, precise = true),
        )
        assertEquals(
            listOf(LocPrecision.province, LocPrecision.city, LocPrecision.district),
            LocationResolver.availablePrecisionsForNewLocation(authorized = true, precise = false),
        )
        assertEquals(
            listOf(LocPrecision.province, LocPrecision.city, LocPrecision.district),
            LocationResolver.availablePrecisionsForNewLocation(authorized = false, precise = true),
        )
    }

    @Test
    fun `exact text deduplicates a place name that repeats as the street`() {
        // MapKit 常见形态：地点名与街道是同一个字符串
        val duplicated = PlacemarkText(
            name = "深南大道",
            thoroughfare = "深南大道",
            locality = "深圳市",
            administrativeArea = "广东省",
            country = "中国",
        )
        assertEquals(
            "深南大道 · 深圳市 · 广东省 · 中国",
            LocationResolver.textFromPlacemark(duplicated, LocPrecision.exact),
        )

        // 地点名包含街道 → 保留地点名
        val containing = duplicated.copy(name = "腾讯滨海大厦", thoroughfare = "滨海大道")
        assertEquals(
            "腾讯滨海大厦, 滨海大道 · 深圳市 · 广东省 · 中国",
            LocationResolver.textFromPlacemark(containing, LocPrecision.exact),
        )

        // 街道包含地点名 → 保留街道
        val streetContains = duplicated.copy(name = "滨海", thoroughfare = "滨海大道")
        assertEquals(
            "滨海大道 · 深圳市 · 广东省 · 中国",
            LocationResolver.textFromPlacemark(streetContains, LocPrecision.exact),
        )

        // 没有地点名 → 用街道
        val noName = duplicated.copy(name = "")
        assertEquals(
            "深南大道 · 深圳市 · 广东省 · 中国",
            LocationResolver.textFromPlacemark(noName, LocPrecision.exact),
        )
    }

    @Test
    fun `other precisions pick the documented fields`() {
        val pm = PlacemarkText(
            name = "深圳湾公园",
            thoroughfare = "滨海大道",
            subLocality = "南山区",
            locality = "深圳市",
            subAdministrativeArea = "南山区",
            administrativeArea = "广东省",
            country = "中国",
        )
        assertEquals("滨海大道 · 深圳市 · 广东省 · 中国", LocationResolver.textFromPlacemark(pm, LocPrecision.street))
        assertEquals("南山区 · 深圳市 · 广东省 · 中国", LocationResolver.textFromPlacemark(pm, LocPrecision.district))
        assertEquals("深圳市 · 广东省 · 中国", LocationResolver.textFromPlacemark(pm, LocPrecision.city))
        assertEquals("广东省 · 中国", LocationResolver.textFromPlacemark(pm, LocPrecision.province))
        assertEquals("", LocationResolver.textFromPlacemark(null, LocPrecision.exact))
    }

    @Test
    fun `street falls back to sub locality when there is no thoroughfare`() {
        val pm = PlacemarkText(subLocality = "南山区", locality = "深圳市", country = "中国")
        assertEquals("南山区 · 深圳市 · 中国", LocationResolver.textFromPlacemark(pm, LocPrecision.street))
    }
}

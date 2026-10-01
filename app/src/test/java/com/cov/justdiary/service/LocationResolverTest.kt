package com.cov.justdiary.service

import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.model.LocQuality
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

    /**
     * 权限 × 精度 → 这次定位能支撑的**最细**级别（iOS `maxPrecision`）。
     *
     * 模糊授权一律城市：坐标被吸附到"所在区域"的代表点，区县不再可信。
     * 精确授权按这次的 `horizontalAccuracy` 分档；精度未知（Android 用 0）按城市算。
     */
    @Test
    fun `the permission and the fix accuracy set the finest level`() {
        // 模糊授权：无论精度报多少，最多到城市
        assertEquals(LocPrecision.city, LocationResolver.maxPrecision(precise = false, horizontalAccuracy = 5f))
        assertEquals(LocPrecision.city, LocationResolver.maxPrecision(precise = false, horizontalAccuracy = 0f))

        // 精确授权：按水平精度分档
        assertEquals(LocPrecision.exact, LocationResolver.maxPrecision(precise = true, horizontalAccuracy = 8f))
        assertEquals(LocPrecision.street, LocationResolver.maxPrecision(precise = true, horizontalAccuracy = 80f))
        assertEquals(LocPrecision.district, LocationResolver.maxPrecision(precise = true, horizontalAccuracy = 900f))
        assertEquals(LocPrecision.city, LocationResolver.maxPrecision(precise = true, horizontalAccuracy = 4_000f))
        assertEquals(LocPrecision.province, LocationResolver.maxPrecision(precise = true, horizontalAccuracy = 9_000f))
        // 精度未知（0）不冒进：按城市算
        assertEquals(LocPrecision.city, LocationResolver.maxPrecision(precise = true, horizontalAccuracy = 0f))
    }

    @Test
    fun `a manually picked precision can only make the record coarser`() {
        assertEquals(LocPrecision.exact, LocationResolver.effective(LocPrecision.auto, LocPrecision.exact))
        assertEquals(LocPrecision.city, LocationResolver.effective(LocPrecision.city, LocPrecision.exact))
        assertEquals("记住的比上限更细时按上限", LocPrecision.district, LocationResolver.effective(LocPrecision.exact, LocPrecision.district))
        assertEquals("不在梯子上的值按上限", LocPrecision.province, LocationResolver.effective("坏值", LocPrecision.province))
    }

    @Test
    fun `new location offers exactly the levels the cap allows`() {
        assertEquals(LocPrecision.all, LocationResolver.availablePrecisions(LocPrecision.exact))
        assertEquals(listOf(LocPrecision.district, LocPrecision.city, LocPrecision.province), LocationResolver.availablePrecisions(LocPrecision.district))
        assertEquals(listOf(LocPrecision.city, LocPrecision.province), LocationResolver.availablePrecisions(LocPrecision.city))
        assertEquals(listOf(LocPrecision.province), LocationResolver.availablePrecisions(LocPrecision.province))
        // 坏上限 → 只剩省份，不会凭空多出更细的级别
        assertEquals(listOf(LocPrecision.province), LocationResolver.availablePrecisions(LocPrecision.none))
    }

    /**
     * 分享文案：**只用地区列重建**，`loc_text` 不是它的输入。
     *
     * 这条是"分享不会泄露地点名与街道"的硬保证 —— 早期记录会把地点名写在 `loc_text` 里，
     * 而 `loc_precision` 只标到省份，只看标签迟早会漏。
     */
    @Test
    fun `share text is rebuilt from the region columns and never leaks the place name`() {
        val region = LocRegion("中国", "CN", "广东省", "深圳市", "南山区", LocQuality.precise)

        // 记录在 exact（loc_text 里有地点名与街道）→ 分享按上限降到区县
        assertEquals("南山区 · 深圳市 · 广东省 · 中国", LocationResolver.shareText(LocPrecision.exact, region, LocPrecision.district))
        // 上限更粗 → 只能更粗
        assertEquals("深圳市 · 广东省 · 中国", LocationResolver.shareText(LocPrecision.exact, region, LocPrecision.city))
        assertEquals("广东省 · 中国", LocationResolver.shareText(LocPrecision.exact, region, LocPrecision.province))
        // 记录本来就更粗 → 按记录的来，不会凭空更细
        assertEquals("广东省 · 中国", LocationResolver.shareText(LocPrecision.province, region, LocPrecision.district))
        // 隐藏
        assertEquals("", LocationResolver.shareText(LocPrecision.exact, region, LocPrecision.none))
        // 上限被误写成 exact / street（坏备份）→ 收到最细的可分享级别，而不是原样放行
        assertEquals("南山区 · 深圳市 · 广东省 · 中国", LocationResolver.shareText(LocPrecision.exact, region, LocPrecision.exact))
        assertEquals("南山区 · 深圳市 · 广东省 · 中国", LocationResolver.shareText(LocPrecision.exact, region, "坏值"))
        // 没有地点的记录 → 空（不退回原文）
        assertEquals("", LocationResolver.shareText(LocPrecision.none, LocRegion("", "", "", "", "", LocQuality.none), LocPrecision.district))
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

    /**
     * 「区县」级优先 `subLocality`（中国的「南山区」就在这里），没有才退到
     * `subAdministrativeArea`（美国是 county）。
     *
     * 此前**只**看 `subAdministrativeArea`，而中国的反地理编码常常把市级填进那一栏，
     * 于是「区县」级只显示到市，与地区列重建出来的文案（region3 · region2 · …）对不上。
     */
    @Test
    fun `district prefers sub locality and falls back to the sub administrative area`() {
        val china = PlacemarkText(
            subLocality = "南山区",
            locality = "深圳市",
            subAdministrativeArea = "深圳市",
            administrativeArea = "广东省",
            country = "中国",
        )
        assertEquals(
            "南山区 · 深圳市 · 广东省 · 中国",
            LocationResolver.textFromPlacemark(china, LocPrecision.district),
        )

        val us = PlacemarkText(
            subAdministrativeArea = "Santa Clara County",
            locality = "Cupertino",
            administrativeArea = "California",
            country = "United States",
        )
        assertEquals(
            "Santa Clara County · Cupertino · California · United States",
            LocationResolver.textFromPlacemark(us, LocPrecision.district),
        )
    }
}

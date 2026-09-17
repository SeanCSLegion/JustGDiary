package com.cov.justdiary.service

import com.cov.justdiary.model.LocQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 历史片段地区回填的字段映射测试。
 *
 * 断言的是 iOS `ReverseGeocoder.reverse(_:)`（`DiaryRepository.swift:1011-1028`）的**逐字规则**，
 * 特别是它与"记录新位置"路径（`LocationResolver.resolve`）在 `region1` 上的差异 ——
 * 这条差异很容易在移植时被"顺手统一"掉，从而让两端回填出不同的列值，
 * 破坏备份互通时"同一份数据两端一致"的前提。
 */
class RegionBackfillerTest {

    private val shenzhen = PlacemarkText(
        name = "深圳湾公园",
        thoroughfare = "望海路",
        subLocality = "南山区",
        locality = "深圳市",
        subAdministrativeArea = "南山区",
        administrativeArea = "广东省",
        country = "中国",
        isoCountryCode = "CN",
    )

    @Test
    fun `null placemark yields null so the id is retried later`() {
        assertNull(RegionBackfiller.reversePlacemark(null))
    }

    @Test
    fun `region1 falls back to subAdministrativeArea in the backfill path`() {
        // 回填路径：administrativeArea 为空时退到 subAdministrativeArea（记录新位置时不退）
        val place = RegionBackfiller.reversePlacemark(shenzhen.copy(administrativeArea = ""))
        assertEquals("南山区", place?.region1)
    }

    @Test
    fun `region1 prefers administrativeArea when both are present`() {
        val place = RegionBackfiller.reversePlacemark(shenzhen)
        assertEquals("广东省", place?.region1)
    }

    @Test
    fun `the live path does not fall back for region1 - divergence is intentional`() {
        // 同一条 placemark（administrativeArea 为空）：回填给「南山区」，记录新位置给「」
        val withoutProvince = shenzhen.copy(administrativeArea = "")
        assertEquals("南山区", RegionBackfiller.reversePlacemark(withoutProvince)?.region1)
        assertEquals(
            "",
            LocationRules.regionFromLivePlacemark(withoutProvince, LocQuality.precise).region1,
        )
    }

    @Test
    fun `both paths agree on country, code, region2 and region3`() {
        val backfill = RegionBackfiller.reversePlacemark(shenzhen)!!
        val live = LocationRules.regionFromLivePlacemark(shenzhen, LocQuality.precise)
        assertEquals(live.country, backfill.country)
        assertEquals(live.countryCode, backfill.countryCode)
        assertEquals(live.region1, backfill.region1)
        assertEquals(live.region2, backfill.region2)
        assertEquals(live.region3, backfill.region3)
    }

    @Test
    fun `region2 falls back from subAdministrativeArea to locality`() {
        assertEquals("南山区", RegionBackfiller.reversePlacemark(shenzhen)?.region2)
        assertEquals(
            "深圳市",
            RegionBackfiller.reversePlacemark(shenzhen.copy(subAdministrativeArea = ""))?.region2,
        )
    }

    @Test
    fun `region3 needs both locality and subAdministrativeArea`() {
        assertEquals("南山区", RegionBackfiller.reversePlacemark(shenzhen)?.region3)

        // 缺 subAdministrativeArea → region3 为空（哪怕 subLocality 有值）
        assertEquals(
            "",
            RegionBackfiller.reversePlacemark(shenzhen.copy(subAdministrativeArea = ""))?.region3,
        )
        // 缺 locality → 同样为空
        assertEquals("", RegionBackfiller.reversePlacemark(shenzhen.copy(locality = ""))?.region3)
    }

    @Test
    fun `country and code are copied verbatim and may be empty`() {
        val place = RegionBackfiller.reversePlacemark(
            shenzhen.copy(country = "", isoCountryCode = ""),
        )
        assertEquals("", place?.country)
        assertEquals("", place?.countryCode)
    }

    @Test
    fun `an all-empty placemark still produces a placemark - the caller decides`() {
        // iOS 只在 reverse 失败时返回 nil；字段全空但拿到 mapItem 时仍会写库（region1 保持空）
        val place = RegionBackfiller.reversePlacemark(PlacemarkText())
        assertEquals(ReversePlacemark("", "", "", "", ""), place)
    }

    @Test
    fun `batch limit matches iOS`() {
        assertEquals(30, RegionBackfiller.BATCH_LIMIT)
    }
}

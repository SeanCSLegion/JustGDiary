package com.cov.justdiary.service

import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.model.LocQuality
import com.cov.justdiary.model.LocRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 位置记录规则测试。
 *
 * 这些规则是**数据正确性**规则（方案 §8.1）：位置属于"写日记那一刻的事实"，
 * 事后重新获取会把它悄悄改成"现在在哪"，足迹聚合也会跟着漂移。
 * 五条规则逐条断言 —— 它们全部是纯函数，因此不需要设备。
 */
class LocationRulesTest {

    private val shenzhen = LocRegion(
        country = "中国",
        countryCode = "CN",
        region1 = "广东省",
        region2 = "深圳市",
        region3 = "南山区",
        locQuality = LocQuality.precise,
    )

    private val recorded = LocationRules.LocationSnapshot(
        latitude = 22.54,
        longitude = 113.95,
        locText = "深圳湾公园, 滨海大道 · 深圳市 · 广东省 · 中国",
        locPrecision = LocPrecision.exact,
        region = shenzhen,
        recordedPrecision = LocPrecision.exact,
        recordedText = "深圳湾公园, 滨海大道 · 深圳市 · 广东省 · 中国",
    )

    // ------------------------------------------------------------------ 规则 1：只在新建时获取一次

    @Test
    fun `location is fetched only for a new block with auto location on`() {
        assertTrue(LocationRules.shouldLocateOnOpen(isNewBlock = true, autoLoc = true, existingLocation = false))
        // 编辑已有片段永不重新获取
        assertFalse(LocationRules.shouldLocateOnOpen(isNewBlock = false, autoLoc = true, existingLocation = false))
        // 自动定位关闭
        assertFalse(LocationRules.shouldLocateOnOpen(isNewBlock = true, autoLoc = false, existingLocation = false))
        // 新建时已有位置（理论上不会发生，但不应重复获取）
        assertFalse(LocationRules.shouldLocateOnOpen(isNewBlock = true, autoLoc = true, existingLocation = true))
    }

    @Test
    fun `initial state covers the five documented scenarios`() {
        // 新建 + 自动定位 → 获取中
        assertEquals(
            LocationRules.EditorLocationState.Locating,
            LocationRules.initialState(isNewBlock = true, autoLoc = true, snapshot = null),
        )
        // 新建 + 自动定位关闭 → 失败态（胶囊显示"重新获取位置"）
        assertEquals(
            LocationRules.EditorLocationState.LocateFailed,
            LocationRules.initialState(isNewBlock = true, autoLoc = false, snapshot = null),
        )
        // 编辑无位置的片段 → 只读的"未记录地点"
        assertEquals(
            LocationRules.EditorLocationState.NotRecorded,
            LocationRules.initialState(isNewBlock = false, autoLoc = true, snapshot = null),
        )
        // 已有位置（新建或编辑都一样）→ 显示记录的位置
        assertTrue(
            LocationRules.initialState(isNewBlock = false, autoLoc = true, snapshot = recorded)
                is LocationRules.EditorLocationState.Recorded,
        )
    }

    @Test
    fun `refetch entry exists only for a new block without a recorded location`() {
        assertTrue(
            LocationRules.canRefetchLocation(isNewBlock = true, state = LocationRules.EditorLocationState.LocateFailed),
        )
        assertFalse(
            LocationRules.canRefetchLocation(
                isNewBlock = true,
                state = LocationRules.EditorLocationState.Recorded(recorded),
            ),
        )
        // 编辑态永不提供
        assertFalse(
            LocationRules.canRefetchLocation(isNewBlock = false, state = LocationRules.EditorLocationState.NotRecorded),
        )
    }

    // ------------------------------------------------------------------ 规则 2：保存确认

    @Test
    fun `saving without a location asks for confirmation only when we tried to record one`() {
        assertTrue(LocationRules.needsSaveConfirmation(isNewBlock = true, autoLoc = true, hasLocation = false))
        assertFalse(LocationRules.needsSaveConfirmation(isNewBlock = true, autoLoc = true, hasLocation = true))
        assertFalse(LocationRules.needsSaveConfirmation(isNewBlock = true, autoLoc = false, hasLocation = false))
        // 编辑已有片段不需要确认（它本来就不获取）
        assertFalse(LocationRules.needsSaveConfirmation(isNewBlock = false, autoLoc = true, hasLocation = false))
    }

    @Test
    fun `a saved block can never gain a location afterwards`() {
        assertFalse(LocationRules.canAddLocationAfterSave())
    }

    @Test
    fun `coordinates without an address count as no location`() {
        val empty = LocRegion("", "", "", "", "", LocQuality.coarse)
        // 坐标有、地址空 → 按没有位置处理，否则会存一条永远补不上的记录
        assertFalse(LocationRules.acceptsGeocodeResult(locText = "", region = empty))
        // 只要有任意一级地区就算有位置
        assertTrue(LocationRules.acceptsGeocodeResult(locText = "", region = shenzhen))
        assertTrue(LocationRules.acceptsGeocodeResult(locText = "深圳市", region = empty))
    }

    // ------------------------------------------------------------------ 规则 3：编辑态只能调精度

    @Test
    fun `recorded precision and text survive a round trip through precision changes`() {
        val options = LocationRules.precisionOptions(recorded)
        assertEquals(
            listOf(LocPrecision.exact, LocPrecision.district, LocPrecision.city, LocPrecision.province),
            options,
        )

        // 回到记录档位 → 原样恢复原文（不会丢掉地点名）
        assertEquals(
            "深圳湾公园, 滨海大道 · 深圳市 · 广东省 · 中国",
            LocationRules.textForPrecision(recorded, LocPrecision.exact),
        )
        // 降级到省 → 由地区列重建
        assertEquals("广东省 · 中国", LocationRules.textForPrecision(recorded, LocPrecision.province))
        assertEquals("深圳市 · 广东省 · 中国", LocationRules.textForPrecision(recorded, LocPrecision.city))
        assertEquals(
            "南山区 · 深圳市 · 广东省 · 中国",
            LocationRules.textForPrecision(recorded, LocPrecision.district),
        )

        // 来回一次之后，记录档位仍然能恢复到原文
        assertEquals(
            recorded.recordedText,
            LocationRules.textForPrecision(recorded, recorded.recordedPrecision),
        )
    }

    @Test
    fun `a block recorded without a district offers fewer levels`() {
        val cityOnly = recorded.copy(region = shenzhen.copy(region3 = ""), recordedPrecision = LocPrecision.city)
        assertEquals(listOf(LocPrecision.city, LocPrecision.province), LocationRules.precisionOptions(cityOnly))
    }

    // ------------------------------------------------------------------ 精度与质量档位

    @Test
    fun `precise authorization yields exact and precise quality`() {
        assertEquals(LocPrecision.exact, LocationRules.defaultPrecisionForNewLocation(precise = true))
        assertEquals(LocQuality.precise, LocationRules.defaultQualityForNewLocation(precise = true))
    }

    @Test
    fun `coarse authorization yields province and coarse quality`() {
        assertEquals(LocPrecision.province, LocationRules.defaultPrecisionForNewLocation(precise = false))
        assertEquals(LocQuality.coarse, LocationRules.defaultQualityForNewLocation(precise = false))
    }

    @Test
    fun `a snapshot with empty text and empty region reports no location`() {
        val empty = LocationRules.LocationSnapshot(
            latitude = 0.0,
            longitude = 0.0,
            locText = "",
            locPrecision = LocPrecision.none,
            region = LocRegion("", "", "", "", "", LocQuality.none),
        )
        assertFalse(empty.hasLocation)
        assertTrue(recorded.hasLocation)
    }
}

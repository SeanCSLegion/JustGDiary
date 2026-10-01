package com.cov.justdiary.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.cov.justdiary.model.LocPrecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 一次定位结果。
 *
 * [accuracy] 是这次定位的水平精度（米），对应 iOS 的 `horizontalAccuracy` ——
 * "这次定位能标到多细"由它和授权档一起算（见 [LocationResolver.maxPrecision]）。
 * 未知时是 `0`（`Location.getAccuracy()` 的平台约定）。
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float = 0f,
)

/** 带"什么时候测到的"的定位结果（缓存点要按年龄判断能不能用）。 */
data class AgedLocationFix(val fix: LocationFix, val ageMillis: Long)

/**
 * 定位与反向地理编码。对应 iOS `Services/LocationService.swift`。
 *
 * ## 已确认的落地方式（方案 §8.1、决策 Q6）
 *
 * **运行时探测 GMS**：有 Google Play services 时走 `FusedLocationProviderClient`
 * （融合 GPS/WiFi/基站，行为最接近 iOS 的 `CLLocationUpdate`）；
 * 没有 GMS 的设备（含国内多数机型）退回原生 `LocationManager.getCurrentLocation`
 * （API 30+，minSdk 33 满足）。两者都受 **8 秒超时**约束 —— 与 iOS 的
 * `Task.sleep(for: .seconds(8))` 竞速一致。
 *
 * ## 为什么是"两条来源赛跑"而不是"二选一"（2026-09-28 真机返工）
 *
 * 原来的判据是"装了 GMS 就只用 GMS"，在国行 ROM 上会**静默失效**：真机（MEIZU 21 /
 * Android 16）实测 `FusedLocationProviderClient.getCurrentLocation(PRIORITY_HIGH_ACCURACY)`
 * 每次都卡满 8 秒返回 null —— Google 的网络定位后端在国内不可达，而高精度请求又非要等
 * 一个 GPS 定位（该机 GNSS 的 TTFF 均值 5.8s，室内根本等不到），于是"明明给了权限却
 * 经常拿不到位置"。
 *
 * 现在改成：**两套来源同时开跑，谁先给出点就用谁**（`firstFix`）。GMS 能用的设备上
 * 融合定位仍然最快最准；国行 ROM 上厂商的 `LocationManager`（背后是高德/百度）先回来。
 * 再加一条**新鲜缓存点**的快路径 —— iOS 的 `CLLocationUpdate.liveUpdates()` 首帧给的
 * 就是系统的缓存点，这也是它"总是立刻有位置"的原因。
 *
 * 反向地理编码用平台 `Geocoder`（API 33+ 有异步回调版本），字段映射到
 * [PlacemarkText] 的语义与 iOS 的 `CLPlacemark` 基本一一对应。
 *
 * > 若目标市场含国内且要求稳定的中文地址，`Geocoder` 的后端依赖厂商实现，
 * > 可能需要换高德/百度 Web 服务（方案 §8.1 已列为可选方案）。
 */
class LocationService(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 设备是否有可用的 Google Play services。 */
    val hasPlayServices: Boolean by lazy {
        runCatching {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
    }

    /** 是否已获得定位授权（精确或模糊都算）。 */
    fun isAuthorized(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
        granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * 是否拿到**精确**授权。
     *
     * 与 iOS 的 `accuracyAuthorization == .fullAccuracy` 对应：
     * 只有精确授权才能记录 `exact` 档，模糊授权一律按 `province`（`LocationService.swift:104-105`）。
     */
    fun isPrecise(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    /** 需要向系统申请的权限列表（模糊 + 精确一起要，让用户能在弹窗里选"大致位置"）。 */
    fun requiredPermissions(): Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /**
     * 取当前位置，超时 [timeoutMillis]（默认 8 秒，与 iOS 一致）返回 null。
     *
     * 顺序（每一步都是"能拿到就立刻返回"）：
     *  1. **新鲜缓存点**：`LocationRules.isFreshFix` 判定的年龄内直接用（千分之一秒级）；
     *  2. **两条来源赛跑**：GMS 融合定位 ∥ 平台 `LocationManager`（每个可用 provider 一个请求）；
     *  3. 全都没回来 → **过期缓存兜底**（`LocationRules.isUsableStaleFix`，避免把几天前的
     *     坐标当成"此刻的位置"写进日记）。
     */
    suspend fun currentLocation(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): LocationFix? {
        if (!isAuthorized()) {
            Log.location.w("未授权定位，currentLocation 返回 null")
            return null
        }
        // ① 新鲜缓存：室内没有卫星信号时，这是唯一能在 1 秒内拿到的点
        cachedLocation(LocationRules::isFreshFix)?.let {
            Log.location.i("用新鲜缓存点（${it.ageMillis}ms 前）")
            return it.fix
        }
        // ② 赛跑
        val fix = withTimeoutOrNull(timeoutMillis) { firstFix() }
        if (fix != null) return fix
        // ③ 兜底：稍旧但仍在可用窗口内的缓存点
        val stale = cachedLocation(LocationRules::isUsableStaleFix)
        if (stale == null) {
            Log.location.w("定位超时（${timeoutMillis}ms）或失败")
        } else {
            Log.location.w("实时定位失败，回退到 ${stale.ageMillis}ms 前的缓存点")
        }
        return stale?.fix
    }

    /**
     * 两条来源赛跑，取**第一个非空**结果，然后撤掉另一条（不让它继续耗电）。
     *
     * 没有 GMS 的设备只发原生那一条，行为与从前一致。
     */
    private suspend fun firstFix(): LocationFix? = coroutineScope {
        val results = Channel<LocationFix?>(2)
        val jobs = mutableListOf<Job>()
        if (hasPlayServices) {
            jobs += launch { results.send(runCatching { fusedLocation() }.getOrNull()) }
        }
        jobs += launch { results.send(runCatching { nativeLocation() }.getOrNull()) }

        var received = 0
        var fix: LocationFix? = null
        while (received < jobs.size && fix == null) {
            fix = results.receive()
            received++
        }
        // 拿到点就把另一条撤掉（它还在等卫星 / 网络）
        if (fix != null) jobs.forEach { it.cancel() }
        jobs.forEach { if (it.isActive) it.join() }
        fix
    }

    /**
     * 系统里最近一次定位（GMS 可用时问融合定位，也问平台），按 [usable] 判定年龄够不够新。
     *
     * 两个来源都问、取**最新**的那个：GMS 与厂商定位服务的缓存不是同一份
     * （国行 ROM 上厂商那份更新）。
     */
    private suspend fun cachedLocation(usable: (Long) -> Boolean): AgedLocationFix? {
        val candidates = mutableListOf<AgedLocationFix>()
        if (hasPlayServices) {
            runCatching { fusedLastLocation() }.getOrNull()?.let { candidates.add(it) }
        }
        runCatching { nativeLastLocation() }.getOrNull()?.let { candidates.add(it) }
        return candidates
            .filter { usable(it.ageMillis) }
            // 越新越好
            .minByOrNull { it.ageMillis }
    }

    private suspend fun fusedLastLocation(): AgedLocationFix? = suspendCancellableCoroutine { continuation ->
        val client = LocationServices.getFusedLocationProviderClient(appContext)
        try {
            client.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (continuation.isActive) continuation.resume(location?.toAgedFix())
                }
                .addOnFailureListener { error: Exception ->
                    Log.location.w("Fused 缓存点读取失败：${error.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
        } catch (t: Throwable) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private fun nativeLastLocation(): AgedLocationFix? {
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return enabledProviders(manager)
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.toAgedFix()
            }
            .minByOrNull { it.ageMillis }
    }

    /**
     * 可用的 provider 列表。
     *
     * **模糊授权下跳过 GPS**：Android 12 起只有 `ACCESS_COARSE_LOCATION` 时向 GPS provider
     * 取点会抛 `SecurityException`（原先的实现会因此整条链路返回 null）。
     * 网络 provider 在前 —— 它先回来的概率高得多。
     */
    private fun enabledProviders(manager: LocationManager): List<String> = buildList {
        if (runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
            add(LocationManager.NETWORK_PROVIDER)
        }
        if (isPrecise() &&
            runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        ) {
            add(LocationManager.GPS_PROVIDER)
        }
        if (isEmpty() &&
            runCatching { manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) }.getOrDefault(false)
        ) {
            add(LocationManager.PASSIVE_PROVIDER)
        }
    }

    private suspend fun fusedLocation(): LocationFix? = suspendCancellableCoroutine { continuation ->
        val client = LocationServices.getFusedLocationProviderClient(appContext)
        // GMS 用 CancellationTokenSource（不是平台 CancellationSignal），取消时一并撤单
        val tokenSource = CancellationTokenSource()
        continuation.invokeOnCancellation { tokenSource.cancel() }
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(DEFAULT_TIMEOUT_MS)
            .build()
        try {
            client.getCurrentLocation(request, tokenSource.token)
                .addOnSuccessListener { location: Location? ->
                    if (continuation.isActive) continuation.resume(location?.toFix())
                }
                .addOnFailureListener { error: Exception ->
                    Log.location.w("Fused 定位失败：${error.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
        } catch (t: Throwable) {
            // 例如 GMS 被卸载 / 服务不可用
            Log.location.w("Fused 调用异常：${t.message}")
            if (continuation.isActive) continuation.resume(null)
        }
    }

    /**
     * 平台定位：**每个可用 provider 各发一个请求，谁先回来用谁**
     * （网络 provider 通常几百毫秒就回来，GPS 要等卫星，没必要串行等它）。
     */
    private suspend fun nativeLocation(): LocationFix? {
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            Log.location.w("拿不到 LocationManager")
            return null
        }
        val providers = enabledProviders(manager)
        if (providers.isEmpty()) {
            Log.location.w("没有可用的定位 provider")
            return null
        }
        return coroutineScope {
            val results = Channel<LocationFix?>(providers.size)
            val jobs = providers.map { provider ->
                launch { results.send(nativeLocationFrom(manager, provider)) }
            }
            var received = 0
            var fix: LocationFix? = null
            while (received < providers.size && fix == null) {
                fix = results.receive()
                received++
            }
            if (fix != null) jobs.forEach { it.cancel() }
            jobs.forEach { if (it.isActive) it.join() }
            fix
        }
    }

    private suspend fun nativeLocationFrom(manager: LocationManager, provider: String): LocationFix? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            runCatching {
                manager.getCurrentLocation(provider, signal, appContext.mainExecutor) { location ->
                    if (continuation.isActive) continuation.resume(location?.toFix())
                }
            }.onFailure {
                Log.location.w("原生定位（$provider）异常：${it.message}")
                if (continuation.isActive) continuation.resume(null)
            }
        }

    /**
     * 反向地理编码；失败或结果为空时返回 null。
     *
     * **失败会重试**（3 次，间隔 400ms / 900ms）：真机（MEIZU 21 / Android 16）实测，
     * 系统会**在定位过程中重启厂商的定位服务**（`LocationManagerService: network restart
     * com.meizu.location` → 进程被 kill → 我们正在跑的 `getFromLocation` 拿到
     * `android.os.DeadObjectException`）。服务重启大约需要 500ms，那时再问一次就好了。
     *
     * 原先的实现把这一次失败当成"没有地址"，而"没有地址"按规则等于"没有位置"
     * （[LocationRules.acceptsGeocodeResult]），于是坐标明明拿到了，界面上却是「重新获取位置」。
     *
     * @param appLanguage 地址语言跟随**应用内语言**（对应 iOS 设 `request.preferredLocale`）
     */
    suspend fun reverseGeocode(
        fix: LocationFix,
        appLanguage: String,
        systemLocale: Locale = Locale.getDefault(),
    ): PlacemarkText? = withContext(Dispatchers.IO) {
        val locale = DateFormats.localeFor(appLanguage, systemLocale)
        var placemark: PlacemarkText? = null
        repeat(GEOCODE_ATTEMPTS) { attempt ->
            placemark = runCatching { geocodeOnce(fix, locale) }
                .onFailure { Log.location.w("反地理编码异常（第 ${attempt + 1} 次）：${it.message}") }
                .getOrNull()
            if (placemark != null) return@withContext placemark
            GEOCODE_RETRY_DELAYS_MS.getOrNull(attempt)?.let { delay(it) }
        }
        Log.location.w("反地理编码 $GEOCODE_ATTEMPTS 次都没拿到地址（坐标保留，但按规则视为没有位置）")
        placemark
    }

    /** 一次反地理编码（瞬时故障由 [reverseGeocode] 重试）。 */
    private suspend fun geocodeOnce(fix: LocationFix, locale: Locale): PlacemarkText? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                val geocoder = Geocoder(appContext, locale)
                geocoder.getFromLocation(fix.latitude, fix.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        if (continuation.isActive) continuation.resume(addresses.firstOrNull()?.toPlacemarkText())
                    }

                    override fun onError(errorMessage: String?) {
                        Log.location.w("反地理编码失败：$errorMessage")
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            }
        } else {
            @Suppress("DEPRECATION")
            Geocoder(appContext, locale)
                .getFromLocation(fix.latitude, fix.longitude, 1)
                ?.firstOrNull()
                ?.toPlacemarkText()
        }

    /**
     * 由一次定位结果构造位置快照（把"权限上限 × 记忆偏好 → 档位/质量"的规则集中在这里）。
     *
     * 规则与 iOS 一致（`docs/location-recording.md` §六 / §七）：
     *  - 上限 [LocationResolver.maxPrecision]：模糊授权最多到城市，精确授权按这次的
     *    `horizontalAccuracy` 分档；
     *  - 实际档位 = `min(用户上次手动选的, 上限)` —— 手动选的只会让结果更粗；
     *  - **地址为空时按没有位置处理**（`LocationRules.acceptsGeocodeResult`）。
     *
     * `preferred` 由调用方从设置里读（与 `appLanguage` 同一条约定：这个类不读设置）。
     */
    fun snapshotFor(
        fix: LocationFix,
        placemark: PlacemarkText?,
        appLanguage: String,
        preferred: String = LocPrecision.auto,
    ): LocationRules.LocationSnapshot? {
        val precise = isPrecise()
        val cap = LocationResolver.maxPrecision(precise, fix.accuracy)
        val precision = LocationResolver.effective(preferred, cap)
        val quality = LocationRules.defaultQualityForNewLocation(precise)
        val locText = LocationResolver.textFromPlacemark(placemark, precision)
        val region = LocationRules.regionFromLivePlacemark(placemark, quality)
        if (!LocationRules.acceptsGeocodeResult(locText, region)) return null
        return LocationRules.LocationSnapshot(
            latitude = fix.latitude,
            longitude = fix.longitude,
            locText = locText,
            locPrecision = precision,
            region = region,
            maxPrecision = cap,
            placemark = placemark,
            recordedPrecision = precision,
            recordedText = locText,
        ).also { Log.location.i("已记录位置：$locText（$precision，上限 $cap，精度 ${fix.accuracy}m）") }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun Location.toFix() = LocationFix(latitude = latitude, longitude = longitude, accuracy = accuracy)

    /** 平台给的 `Location` 自带时间戳（`elapsedRealtimeNanos`），年龄由它算。 */
    private fun Location.toAgedFix(): AgedLocationFix = AgedLocationFix(
        fix = toFix(),
        ageMillis = LocationRules.ageMillisOf(
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            nowNanos = android.os.SystemClock.elapsedRealtimeNanos(),
        ),
    )

    companion object {
        /** 与 iOS 的 8 秒超时一致。 */
        const val DEFAULT_TIMEOUT_MS: Long = 8_000

        /** 反地理编码的尝试次数（真机上厂商定位服务会在定位过程中被系统重启）。 */
        const val GEOCODE_ATTEMPTS: Int = 4

        /**
         * 每次失败后的等待：400ms → 900ms → 1800ms。
         *
         * 服务重启（`chose new implementation` → `connected`）实测约 500ms，因此第一次退避
         * 给 400ms；后面两次覆盖"重启又赶上一次服务冷启动（刚起来的 geocoder 头几秒还会
         * 返回空地址）"的情况 —— 真机上"刚授完权限、整个进程重启"那一次就是这样：
         * 前两次撞在服务重启窗口里，第三次才连接上但结果为空。
         * 全部走完约 3.1s，相比 8s 的定位预算可以接受（这期间用户在打字）。
         */
        val GEOCODE_RETRY_DELAYS_MS: List<Long> = listOf(400L, 900L, 1_800L)
    }
}

/** `android.location.Address` → 与 iOS `CLPlacemark` 语义对应的文本视图。 */
fun android.location.Address.toPlacemarkText(): PlacemarkText = PlacemarkText(
    name = featureName.orEmpty(),
    thoroughfare = thoroughfare.orEmpty(),
    subLocality = subLocality.orEmpty(),
    locality = locality.orEmpty(),
    subAdministrativeArea = subAdminArea.orEmpty(),
    administrativeArea = adminArea.orEmpty(),
    country = countryName.orEmpty(),
    isoCountryCode = countryCode.orEmpty(),
)

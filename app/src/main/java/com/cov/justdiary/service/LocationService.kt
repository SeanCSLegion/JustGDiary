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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** 一次定位结果。 */
data class LocationFix(val latitude: Double, val longitude: Double)

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
     */
    suspend fun currentLocation(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): LocationFix? {
        if (!isAuthorized()) {
            Log.location.w("未授权定位，currentLocation 返回 null")
            return null
        }
        val fix = withTimeoutOrNull(timeoutMillis) {
            if (hasPlayServices) fusedLocation() else nativeLocation()
        }
        if (fix == null) Log.location.w("定位超时（${timeoutMillis}ms）或失败")
        return fix
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

    private suspend fun nativeLocation(): LocationFix? = suspendCancellableCoroutine { continuation ->
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            Log.location.w("没有可用的定位 provider")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        runCatching {
            manager.getCurrentLocation(provider, signal, appContext.mainExecutor) { location ->
                if (continuation.isActive) continuation.resume(location?.toFix())
            }
        }.onFailure {
            Log.location.w("原生定位异常：${it.message}")
            if (continuation.isActive) continuation.resume(null)
        }
    }

    /**
     * 反向地理编码；失败或结果为空时返回 null。
     *
     * @param appLanguage 地址语言跟随**应用内语言**（对应 iOS 设 `request.preferredLocale`）
     */
    suspend fun reverseGeocode(
        fix: LocationFix,
        appLanguage: String,
        systemLocale: Locale = Locale.getDefault(),
    ): PlacemarkText? = withContext(Dispatchers.IO) {
        val locale = DateFormats.localeFor(appLanguage, systemLocale)
        runCatching {
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
        }.onFailure { Log.location.w("反地理编码异常：${it.message}") }.getOrNull()
    }

    /**
     * 由一次定位结果构造位置快照（把"精度 → 档位/质量"的规则集中在这里）。
     *
     * 规则与 iOS 一致：精确授权 → `exact`/`precise`，模糊授权 → `province`/`coarse`；
     * **地址为空时按没有位置处理**（`LocationRules.acceptsGeocodeResult`）。
     */
    fun snapshotFor(fix: LocationFix, placemark: PlacemarkText?, appLanguage: String): LocationRules.LocationSnapshot? {
        val precise = isPrecise()
        val precision = LocationRules.defaultPrecisionForNewLocation(precise)
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
            recordedPrecision = precision,
            recordedText = locText,
        ).also { Log.location.i("已记录位置：$locText（$precision）") }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun Location.toFix() = LocationFix(latitude = latitude, longitude = longitude)

    companion object {
        /** 与 iOS 的 8 秒超时一致。 */
        const val DEFAULT_TIMEOUT_MS: Long = 8_000
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

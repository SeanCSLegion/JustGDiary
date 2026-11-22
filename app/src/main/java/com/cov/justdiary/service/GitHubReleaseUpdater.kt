package com.cov.justdiary.service

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** GitHub Releases based installer for the public Android repository. */
class GitHubReleaseUpdater(private val context: Context) {
    data class Release(
        val tag: String,
        val versionName: String,
        val notes: String,
        val downloadUrl: String,
        val expectedSize: Long,
        val sha256: String?,
    )

    sealed interface CheckResult {
        data object UpToDate : CheckResult
        data class Available(val release: Release) : CheckResult
    }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "JustGDiary-Android-Updater")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("GitHub release check failed (${connection.responseCode})")
            }
            val json = Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
            require(json["draft"]?.jsonPrimitive?.content != "true") { "Latest release is a draft" }
            require(json["prerelease"]?.jsonPrimitive?.content != "true") { "Latest release is a prerelease" }
            val tag = json["tag_name"]?.jsonPrimitive?.content ?: error("Release tag is missing")
            val versionName = tag.removePrefix("v").removePrefix("V")
            require(versionName.matches(Regex("\\d+(?:\\.\\d+){0,2}"))) { "Unsupported release version: $tag" }
            val assetNames = setOf("JustGDiary-$versionName.apk", APK_ASSET_NAME).map { it.lowercase(Locale.ROOT) }
            val asset = json["assets"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.lowercase(Locale.ROOT) in assetNames }
                ?: error("Release APK asset 'JustGDiary-$versionName.apk' is missing")
            val url = asset["browser_download_url"]?.jsonPrimitive?.content ?: error("Release APK URL is missing")
            require(url.startsWith("https://github.com/") || url.startsWith("https://objects.githubusercontent.com/")) {
                "Release APK URL is not a trusted GitHub URL"
            }
            Release(
                tag = tag,
                versionName = versionName,
                notes = json["body"]?.jsonPrimitive?.content.orEmpty(),
                downloadUrl = url,
                expectedSize = asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                sha256 = asset["digest"]?.jsonPrimitive?.content?.removePrefix("sha256:")?.lowercase(Locale.ROOT),
            ).let { release ->
                val installed = installedPackageInfo()
                if (compareVersions(release.versionName, installed.versionName.orEmpty()) <= 0) CheckResult.UpToDate
                else CheckResult.Available(release)
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadAndVerify(
        release: Release,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val temporary = File(directory, "${release.tag}.apk.part")
        val apk = File(directory, "${release.tag}.apk")
        val connection = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "JustGDiary-Android-Updater")
        }
        try {
            if (connection.responseCode !in 200..299) error("APK download failed (${connection.responseCode})")
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_APK_BYTES || (release.expectedSize > 0 && release.expectedSize > MAX_APK_BYTES)) {
                error("APK is larger than the allowed download limit")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_APK_BYTES) error("APK is larger than the allowed download limit")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onProgress(total, release.expectedSize.takeIf { it > 0 } ?: contentLength)
                    }
                }
            }
            if (release.expectedSize > 0 && total != release.expectedSize) error("Downloaded APK size does not match the release asset")
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && sha256 != release.sha256) error("Downloaded APK SHA-256 does not match GitHub")
            requireValidApk(temporary, release)
            if (apk.exists()) apk.delete()
            check(temporary.renameTo(apk)) { "Could not finalize downloaded APK" }
            apk
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun installerUri(apk: File) = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)

    private fun requireValidApk(file: File, release: Release) {
        require(file.length() >= 4 && file.inputStream().use { input ->
            input.read() == 0x50 && input.read() == 0x4b
        }) { "Downloaded file is not an APK archive" }
        ZipFile(file).use { zip ->
            require(zip.getEntry("AndroidManifest.xml") != null) { "APK manifest is missing" }
        }
        val archiveInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } ?: error("Android could not read the downloaded APK")
        require(archiveInfo.packageName == context.packageName) { "APK belongs to a different application" }
        val installed = installedPackageInfo()
        require(archiveInfo.versionName == release.versionName) { "APK version name does not match the release tag" }
        require(archiveInfo.longVersionCode > installed.longVersionCode) { "APK is not newer than the installed application" }
        require(archiveInfo.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ==
            installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()) {
            "APK signing certificate does not match the installed application"
        }
    }

    private fun installedPackageInfo(): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.split('.').map { it.toLongOrNull() ?: 0L }
        val rightParts = right.split('.').map { it.toLongOrNull() ?: 0L }
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val comparison = leftParts.getOrElse(index) { 0L }.compareTo(rightParts.getOrElse(index) { 0L })
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        const val APK_ASSET_NAME = "JustGDiary.apk"
        private const val RELEASE_API = "https://api.github.com/repos/SeanCSLegion/JustGDiary/releases/latest"
        private const val MAX_APK_BYTES = 100L * 1024 * 1024
    }
}

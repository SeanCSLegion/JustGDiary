package com.cov.justdiary.service

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 分享与"另存为"的 Intent 构造。
 *
 * 对应 iOS 的 `ShareLink`（日记分享长图、备份导出）。Android 侧必须经由
 * `FileProvider` 把私有目录里的文件暴露成可授权的 `content://` URI ——
 * 直接传 `file://` 会抛 `FileUriExposedException`。
 *
 * 与 iOS 的差异：iOS 的 `ShareLink` 直接吃 `Image`/`URL`；Android 需要
 * 文件 + provider authority + `FLAG_GRANT_READ_URI_PERMISSION`。
 */
object ShareIntents {

    /** 分享长图（`ACTION_SEND` + `EXTRA_STREAM`）。 */
    fun shareImage(context: Context, imageFile: File, chooserTitle: String): Intent {
        val uri = uriFor(context, imageFile)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_PNG
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle)
    }

    /** 分享备份包（`.jdiary`，MIME 用 `application/octet-stream`）。 */
    fun shareBackup(context: Context, backupFile: File, chooserTitle: String): Intent {
        val uri = uriFor(context, backupFile)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_OCTET_STREAM
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle)
    }

    /** 取得可对外授权的 `content://` URI。 */
    fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, authorityOf(context), file)

    /** 与 `AndroidManifest.xml` 里的 `${applicationId}.fileprovider` 对应。 */
    fun authorityOf(context: Context): String = "${context.packageName}$AUTHORITY_SUFFIX"

    const val AUTHORITY_SUFFIX = ".fileprovider"
    const val MIME_PNG = "image/png"
    const val MIME_OCTET_STREAM = "application/octet-stream"
}

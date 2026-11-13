package com.cov.justdiary.service

import android.util.Log as AndroidLog
import com.cov.justdiary.BuildConfig

/**
 * 日志门面，对应 iOS `Services/Log.swift`：subsystem `com.cov.justdiary`，
 * 分类固定为 `app` / `db` / `backup` / `location` / `editor`。
 *
 * Android 侧直接用平台 `android.util.Log`（不引入 Timber），tag 形如 `JustDiary.db`，
 * 便于用 `adb logcat -s JustDiary.db` 过滤。
 */
object Log {

    private const val TAG_PREFIX = "JustDiary"

    val app = Category("app")
    val db = Category("db")
    val backup = Category("backup")
    val location = Category("location")
    val editor = Category("editor")

    class Category internal constructor(name: String) {

        private val tag: String = "$TAG_PREFIX.$name"

        // P2-9：release 不输出 debug/info（day_key、地点、备份统计都在这一层）。
        // warning / error 保留，方便线上定位；R8 仍会按 shrink 规则裁掉未被引用的字符串。
        private fun debugEnabled(): Boolean = BuildConfig.DEBUG

        fun d(message: String) {
            if (debugEnabled()) AndroidLog.d(tag, message)
        }

        fun i(message: String) {
            if (debugEnabled()) AndroidLog.i(tag, message)
        }

        fun w(message: String) = AndroidLog.w(tag, message)

        fun e(message: String, throwable: Throwable? = null) {
            if (throwable == null) {
                AndroidLog.e(tag, message)
            } else {
                AndroidLog.e(tag, message, throwable)
            }
        }
    }
}

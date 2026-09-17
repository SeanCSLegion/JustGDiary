package com.cov.justdiary.service

import android.util.Log as AndroidLog

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

        fun d(message: String) = AndroidLog.d(tag, message)

        fun i(message: String) = AndroidLog.i(tag, message)

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

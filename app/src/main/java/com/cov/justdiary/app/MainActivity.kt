package com.cov.justdiary.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.cov.justdiary.ui.root.RootScreen
import com.cov.justdiary.ui.theme.JustDiaryTheme

/**
 * 唯一的 Activity。
 *
 * 用 [AppCompatActivity] 作为宿主，是为了让应用内的深色模式与应用内语言切换
 * （`AppCompatDelegate.setDefaultNightMode` / `setApplicationLocales`）走官方机制；
 * Material 3 的配色由 Compose 的 [JustDiaryTheme] 负责，XML 主题只提供合法的窗口底。
 *
 * 深链 `justdiary://editor?dayKey=…` 在 M7 阶段接入（对应 iOS `LaunchIntent`）。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            JustDiaryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RootScreen()
                }
            }
        }
    }
}

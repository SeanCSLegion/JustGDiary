package com.cov.justdiary.app

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.cov.justdiary.R
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** P2-8：冷启动能到首页，且首启通知弹窗不会挡住主界面。 */
@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {

    private val permission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(permission).around(composeRule)

    @Test
    fun `app launches to the journal home`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        composeRule.onNodeWithText(context.getString(R.string.tab_home)).assertIsDisplayed()
    }
}

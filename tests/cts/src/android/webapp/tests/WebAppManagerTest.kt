/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.webapp.tests

import android.content.Context
import android.content.pm.webapp.WebAppManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.ApiTest
import com.android.compatibility.common.util.SystemUtil
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebAppManagerTest : WebAppTestBase() {

    /**
     * Test that WebAppManager is available on the target system. The availability of WebAppManager
     * isn't affected by whether the developer option is enabled or not.
     */
    @Test
    fun testWebAppManagerExists() {
        assertThat(context.getSystemService(Context.WEB_APP_SERVICE)).isNotNull()
    }

    /** Test that WebAppManager can be turned on and off by developer option. */
    @Test
    @ApiTest(apis = ["android.content.pm.webapp.WebAppManager#isAvailable"])
    fun testWebAppManager_DeveloperOption() {
        val webAppService = context.getSystemService(WebAppManager::class.java)
        assertThat(webAppService).isNotNull()

        // Enable developer option
        SystemUtil.runShellCommand("settings put global enable_webapp_minter 1")
        assertThat(webAppService.isAvailable).isTrue()

        // Disable developer option
        SystemUtil.runShellCommand("settings put global enable_webapp_minter 0")
        assertThat(webAppService.isAvailable).isFalse()
    }
}

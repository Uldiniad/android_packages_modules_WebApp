/*
 * Copyright (C) 2025 The Android Open Source Project
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.SystemUtil
import com.android.webapp.flags.Flags
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MtsWebAppPrerequisiteTest {
    private var originalMinterState: String? = null

    @Before
    fun setUp() {
        // Skip test if feature flag is disabled
        assumeTrue(Flags.enableWebAppServiceV2())

        // Enable developer option
        try {
            originalMinterState =
                SystemUtil.runShellCommand("settings get global enable_webapp_minter").trim()
        } catch (e: Exception) {
            // Ignore
        }
        SystemUtil.runShellCommand("settings put global enable_webapp_minter 1")
    }

    @After
    fun tearDown() {
        // Restore developer option
        if (originalMinterState == "null" || originalMinterState == null) {
            SystemUtil.runShellCommand("settings delete global enable_webapp_minter")
        } else {
            SystemUtil.runShellCommand(
                "settings put global enable_webapp_minter $originalMinterState"
            )
        }
    }

    /** Test that WebApp is installed on the target system; */
    @Test
    fun testWebAppServicesExistence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Validate services needed for the module to function.
        assertThat(context.getSystemService(Context.WEB_APP_SERVICE)).isNotNull()
    }
}

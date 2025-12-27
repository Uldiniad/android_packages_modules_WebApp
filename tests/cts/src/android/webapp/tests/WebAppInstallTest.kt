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
import android.content.pm.webapp.WebAppInstallRequest
import android.content.pm.webapp.WebAppManager
import android.util.Log
import android.webkit.cts.CtsTestServer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.android.compatibility.common.util.SystemUtil
import com.android.webapp.flags.Flags
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebAppInstallTest {

    private lateinit var context: Context
    private lateinit var webAppManager: WebAppManager
    private lateinit var testServer: CtsTestServer
    private lateinit var uiDevice: UiDevice
    private var installedPackageName: String? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().context

        // Skip test if feature flag is disabled
        assumeTrue(Flags.enableWebAppServiceV2())

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        webAppManager = targetContext.getSystemService(WebAppManager::class.java)!!

        testServer = CtsTestServer(context)
        uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        prepareDevice()
    }

    private fun prepareDevice() {
        uiDevice.apply {
            wakeUp()
            executeShellCommand("wm dismiss-keyguard")
            // Wait for the keyguard to disappear
            wait(Until.gone(By.res("com.android.systemui", "keyguard_bottom_area")), uiTimeout)
            pressHome()
            waitForIdle(initialIdleTimeout)
        }
    }

    @After
    fun tearDown() {
        testServer.shutdown()
        installedPackageName?.let { uiDevice.executeShellCommand("pm uninstall $it") }
    }

    @Test
    fun testInstallWebApp() {
        assertThat(webAppManager).isNotNull()

        val manifestUrl = testServer.getAssetUrl(MANIFEST_PATH)

        // Debug: Show manifestUrl
        Log.i(TAG, "kv6 Manifest URL: $manifestUrl")
        Log.i(TAG, "Timeout to install: $installTimeout")

        assertThat(manifestUrl).isNotNull()
        val request = WebAppInstallRequest.Builder("Test PWA", manifestUrl).build()

        val latch = CountDownLatch(1)
        val installedPackage = AtomicReference<String>()
        val installResult = AtomicInteger(-1)

        // Initiate install
        webAppManager.install(request, context.mainExecutor) { packageName, resultCode ->
            installedPackage.set(packageName)
            installResult.set(resultCode)
            latch.countDown()
        }

        // Note: Assuming English device or "Install" string resource.
        val installButton = uiDevice.wait(Until.findObject(By.text("Install")), uiTimeout)
        if (installButton == null) {
            fail("Install button not found in UI dialog")
        }

        // Use type-safe click if possible, or just click
        (installButton as androidx.test.uiautomator.UiObject2).click()

        // Wait for callback
        val callbackReceived = latch.await(installTimeout, TimeUnit.MILLISECONDS)
        assertThat(callbackReceived).isTrue()

        // Verify success
        assertThat(installResult.get()).isEqualTo(WebAppInstallRequest.RESULT_SUCCESS)
        assertThat(installedPackage.get()).isNotNull()
        installedPackageName = installedPackage.get()

        uiDevice.apply {
            // Wait for the system to quiesce
            pressHome()
            waitForIdle(postInstallIdleTimeout)

            // Launch the app
            executeShellCommand(
                "monkey -p $installedPackageName -c android.intent.category.LAUNCHER 1"
            )

            // Verify content
            val helloText =
                wait(Until.findObject(By.text("Hello, I can run!")), uiBrowserRenderTimeout)
            assertThat(helloText).isNotNull()
        }
    }

    companion object {
        private const val TAG = "WebAppInstallTest"
        private const val MANIFEST_PATH = "manifest.json"

        private val timeoutMultiplier: Long by lazy {
            try {
                val multiplier =
                    SystemUtil.runShellCommand("getprop ro.hw_timeout_multiplier")
                        .trim()
                        .toLongOrNull() ?: 1L
                Log.i(TAG, "ro.hw_timeout_multiplier: $multiplier")
                multiplier
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get timeout multiplier, using 1L", e)
                1L
            }
        }

        private val initialIdleTimeout: Long by lazy { 25000 * timeoutMultiplier }

        private val uiTimeout: Long by lazy { 12000 * timeoutMultiplier }

        private val installTimeout: Long by lazy { 40000 * timeoutMultiplier }

        private val postInstallIdleTimeout: Long by lazy { 15000 * timeoutMultiplier }

        private val uiBrowserRenderTimeout: Long by lazy { 25000 * timeoutMultiplier }
    }
}

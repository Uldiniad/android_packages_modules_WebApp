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
import android.content.pm.webapp.WebAppInstallRequest
import android.content.pm.webapp.WebAppManager
import android.util.Log
import android.webkit.cts.CtsTestServer
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
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

abstract class WebAppTestBase {

    protected lateinit var context: Context
    protected lateinit var webAppManager: WebAppManager
    protected lateinit var testServer: CtsTestServer
    protected lateinit var uiDevice: UiDevice
    protected var installedPackageName: String? = null
    protected var originalMinterState: String? = null

    @Before
    fun setUp() {
        // Skip test if feature flag is disabled
        assumeTrue(Flags.enableWebAppServiceV2())

        context = InstrumentationRegistry.getInstrumentation().context

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        webAppManager = targetContext.getSystemService(WebAppManager::class.java)

        // Enable developer option
        try {
            originalMinterState =
                SystemUtil.runShellCommand("settings get global enable_webapp_minter").trim()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get original minter state", e)
        }
        SystemUtil.runShellCommand("settings put global enable_webapp_minter 1")

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
        // Also skip teardown if the feature flag is disabled
        if (!Flags.enableWebAppServiceV2()) {
            return
        }

        testServer.shutdown()
        installedPackageName?.let { uiDevice.executeShellCommand("pm uninstall $it") }

        // Restore developer option
        if (originalMinterState == "null" || originalMinterState == null) {
            SystemUtil.runShellCommand("settings delete global enable_webapp_minter")
        } else {
            SystemUtil.runShellCommand(
                "settings put global enable_webapp_minter $originalMinterState"
            )
        }

        // Force stop the service to ensure clean state for next test
        // Handle both AOSP and Google package names
        uiDevice.executeShellCommand("am force-stop com.android.webapp.service")
        uiDevice.executeShellCommand("am force-stop com.google.android.webapp.service")
    }

    protected fun installWebApp(
        appName: String,
        manifestPath: String,
        expectedResult: Int,
        expectedTextOnPage: String,
        expectSuccessUi: Boolean,
    ) {
        assertThat(webAppManager).isNotNull()

        val manifestUrl = testServer.getAssetUrl(manifestPath)
        val request = WebAppInstallRequest.Builder(appName, manifestUrl).build()

        val latch = CountDownLatch(1)
        val installedPackage = AtomicReference<String>()
        val installResult = AtomicInteger(-1)

        webAppManager.install(request, context.mainExecutor) { packageName, resultCode ->
            installedPackage.set(packageName)
            installResult.set(resultCode)
            latch.countDown()
        }

        if (expectSuccessUi) {
            val installButton = uiDevice.wait(Until.findObject(By.text("Install")), uiTimeout)
            if (installButton != null) {
                installButton.click()
            } else {
                if (expectedResult == WebAppInstallRequest.RESULT_SUCCESS) {
                    fail("Install button not found in UI dialog")
                }
            }
        }

        val callbackReceived = latch.await(installTimeout, TimeUnit.MILLISECONDS)
        assertThat(callbackReceived).isTrue()
        assertThat(installResult.get()).isEqualTo(expectedResult)

        if (expectedResult == WebAppInstallRequest.RESULT_SUCCESS) {
            assertThat(installedPackage.get()).isNotNull()
            installedPackageName = installedPackage.get()

            uiDevice.apply {
                pressHome()
                waitForIdle(postInstallIdleTimeout)
                executeShellCommand(
                    "monkey -p $installedPackageName -c android.intent.category.LAUNCHER 1"
                )
                // Chrome's First Run Experience (OOBE) might show up on 'user' builds.
                // We try to get past it if it appears.
                var helloText: UiObject2? = null
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < uiBrowserRenderTimeout) {
                    helloText = wait(Until.findObject(By.text(expectedTextOnPage)), 1000)
                    if (helloText != null) break

                    findObject(By.text(CHROME_OOBE_TEXT))?.click()
                }
                assertThat(helloText).isNotNull()
            }
        }
    }

    companion object {
        const val TAG = "WebAppTestBase"
        const val TEST_PWA_NAME = "Test PWA"
        const val TEST_PWA_TEXT = "Hello, I can run!"
        const val CHROME_OOBE_TEXT = "Use without an account"
        const val MANIFEST_PATH = "manifest.json"
        // A path that doesn't exist on the server to trigger 404
        const val INVALID_MANIFEST_PATH = "invalid.json"
        // A manifest without icons to trigger INVALID_ARGUMENTS
        const val NO_ICON_MANIFEST_PATH = "manifest_no_icon.json"
        // A test app that is not eligible of installing web apps
        const val INELIGIBLE_TEST_APP = "android.webapp.tests.ineligible"

        val timeoutMultiplier: Long by lazy {
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

        val initialIdleTimeout: Long by lazy { 25000 * timeoutMultiplier }

        val uiTimeout: Long by lazy { 12000 * timeoutMultiplier }

        val installTimeout: Long by lazy { 15000 * timeoutMultiplier }
        val queryTimeout: Long by lazy { 3000 * timeoutMultiplier }

        val postInstallIdleTimeout: Long by lazy { 15000 * timeoutMultiplier }

        val uiBrowserRenderTimeout: Long by lazy { 25000 * timeoutMultiplier }
    }
}

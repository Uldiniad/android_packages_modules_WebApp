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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.webapp.WebAppInstallRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.android.compatibility.common.util.ApiTest
import com.android.compatibility.common.util.SystemUtil
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebAppInstallTest : WebAppTestBase() {

    @Test
    @ApiTest(apis = ["android.content.pm.webapp.WebAppManager#install"])
    fun testInstallWebApp_Success() {
        installWebApp(
            TEST_PWA_NAME,
            MANIFEST_PATH,
            WebAppInstallRequest.RESULT_SUCCESS,
            TEST_PWA_TEXT,
            true,
        )
    }

    @Test
    @ApiTest(apis = ["android.content.pm.webapp.WebAppManager#install"])
    fun testInstallWebApp_NetworkError() {
        installWebApp(
            TEST_PWA_NAME,
            INVALID_MANIFEST_PATH,
            WebAppInstallRequest.RESULT_NETWORK_ERROR,
            TEST_PWA_TEXT,
            false,
        )
    }

    @Test
    @ApiTest(apis = ["android.content.pm.webapp.WebAppManager#install"])
    fun testInstallWebApp_InvalidArguments() {
        installWebApp(
            TEST_PWA_NAME,
            NO_ICON_MANIFEST_PATH,
            WebAppInstallRequest.RESULT_INVALID_ARGUMENTS,
            TEST_PWA_TEXT,
            false,
        )
    }

    @Test
    @ApiTest(apis = ["android.content.pm.webapp.WebAppManager#install"])
    fun testInstallWebApp_IneligibleCaller_PermissionDenied() {
        val manifestUrl = testServer.getAssetUrl(MANIFEST_PATH)
        val latch = CountDownLatch(1)
        val result = AtomicInteger(-1)

        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == "android.webapp.tests.ineligible.ACTION_INSTALL_RESULT") {
                        result.set(intent.getIntExtra("resultCode", -1))
                        latch.countDown()
                    }
                }
            }

        context.registerReceiver(
            receiver,
            IntentFilter("android.webapp.tests.ineligible.ACTION_INSTALL_RESULT"),
            Context.RECEIVER_EXPORTED,
        )

        try {
            val intent = Intent("android.webapp.tests.ineligible.ACTION_INSTALL")
            intent.setClassName(
                INELIGIBLE_TEST_APP,
                "android.webapp.tests.ineligible.IneligibleActivity",
            )
            intent.putExtra("url", manifestUrl)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            val received = latch.await(installTimeout, TimeUnit.MILLISECONDS)
            assertThat(received).isTrue()
            assertThat(result.get()).isEqualTo(WebAppInstallRequest.RESULT_PERMISSION_DENIED)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    @ApiTest(apis = ["android.content.pm.webapp.WebAppManager#install"])
    fun testInstallWebApp_DuplicateRequest() {
        val manifestUrl = testServer.getAssetUrl(MANIFEST_PATH)
        val request = WebAppInstallRequest.Builder(TEST_PWA_NAME, manifestUrl).build()
        val latch = CountDownLatch(1)
        val results = AtomicInteger(0)

        // Initiate two identical requests
        val callback = { _: String?, resultCode: Int ->
            if (resultCode == WebAppInstallRequest.RESULT_DUPLICATED_REQUEST) {
                results.incrementAndGet()
                latch.countDown()
            }
        }

        webAppManager.install(request, context.mainExecutor, callback)
        webAppManager.install(request, context.mainExecutor, callback)

        val received = latch.await(installTimeout, TimeUnit.MILLISECONDS)
        assertThat(received).isTrue()
        assertThat(results.get()).isAtLeast(1)

        // Clean up UI for the first request
        val cancelButton = uiDevice.wait(Until.findObject(By.text("Cancel")), uiTimeout)
        assertThat(cancelButton).isNotNull()
        cancelButton.click()
    }

    @Test
    @ApiTest(apis = ["android.content.pm.webapp.WebAppManager#install"])
    fun testInstallWebApp_Cancelled() {
        val manifestUrl = testServer.getAssetUrl(MANIFEST_PATH)
        val request = WebAppInstallRequest.Builder(TEST_PWA_NAME, manifestUrl).build()
        val latch = CountDownLatch(1)
        val installResult = AtomicInteger(-1)

        webAppManager.install(request, context.mainExecutor) { _, resultCode ->
            installResult.set(resultCode)
            latch.countDown()
        }

        val cancelButton = uiDevice.wait(Until.findObject(By.text("Cancel")), uiTimeout)
        assertThat(cancelButton).isNotNull()
        cancelButton.click()

        latch.await(installTimeout, TimeUnit.MILLISECONDS)
        assertThat(installResult.get()).isEqualTo(WebAppInstallRequest.RESULT_CANCELLED_BY_USER)
    }

    @Test
    @ApiTest(apis = ["android.content.pm.webapp.WebAppManager#install"])
    fun testInstallWebApp_Unavailable() {
        SystemUtil.runShellCommand("settings put global enable_webapp_minter 0")
        try {
            installWebApp(
                TEST_PWA_NAME,
                MANIFEST_PATH,
                WebAppInstallRequest.RESULT_UNAVAILABLE,
                TEST_PWA_TEXT,
                false,
            )
        } finally {
            SystemUtil.runShellCommand("settings put global enable_webapp_minter 1")
        }
    }

    @Test
    @ApiTest(
        apis =
            [
                "android.content.pm.webapp.WebAppInstallRequest.Builder#build",
                "android.content.pm.webapp.WebAppInstallRequest#getManifestUrl",
                "android.content.pm.webapp.WebAppInstallRequest#getTitle",
            ]
    )
    fun testInstallRequest() {
        val manifestUrl = testServer.getAssetUrl(MANIFEST_PATH)
        val request = WebAppInstallRequest.Builder(TEST_PWA_NAME, manifestUrl).build()
        assertThat(request.manifestUrl).isEqualTo(manifestUrl)
        assertThat(request.title).isEqualTo(TEST_PWA_NAME)
    }
}

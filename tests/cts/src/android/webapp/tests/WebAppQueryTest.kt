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

import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.content.pm.webapp.WebAppInstallRequest
import android.content.pm.webapp.WebAppQueryRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.ApiTest
import com.android.compatibility.common.util.SystemUtil
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebAppQueryTest : WebAppTestBase() {

    @Test
    @ApiTest(apis = ["android.content.pm.webapp.WebAppManager#query"])
    fun testQuery_PackageInstalledByCaller_WithoutQueryAllPackages_ReturnsInstalled() {
        installWebApp(
            TEST_PWA_NAME,
            MANIFEST_PATH,
            WebAppInstallRequest.RESULT_SUCCESS,
            TEST_PWA_TEXT,
            true,
        )
        assertThat(installedPackageName).isNotNull()

        assertThat(queryWebApp(installedPackageName!!))
            .isEqualTo(WebAppQueryRequest.RESULT_INSTALLED)
    }

    @Test
    @ApiTest(apis = ["android.content.pm.webapp.WebAppManager#query"])
    fun testQuery_PackageInstalledByCaller_WithQueryAllPackages_ReturnsInstalled() {
        installWebApp(
            TEST_PWA_NAME,
            MANIFEST_PATH,
            WebAppInstallRequest.RESULT_SUCCESS,
            TEST_PWA_TEXT,
            true,
        )
        assertThat(installedPackageName).isNotNull()

        assertThat(queryWebAppFromHelper(installedPackageName!!))
            .isEqualTo(WebAppQueryRequest.RESULT_INSTALLED)
    }

    @Test
    @ApiTest(apis = ["android.content.pm.webapp.WebAppManager#query"])
    fun testQuery_PackageNotInstalledByCaller_WithoutQueryAllPackages_ReturnsPermissionDenied() {
        assertTargetAppInstalled()
        assertThat(queryWebApp(INELIGIBLE_TEST_APP))
            .isEqualTo(WebAppQueryRequest.RESULT_PERMISSION_DENIED)
    }

    @Test
    @ApiTest(apis = ["android.content.pm.webapp.WebAppManager#query"])
    fun testQuery_PackageNotInstalledByWebApp_WithQueryAllPackages_ReturnsNotInstalled() {
        assertTargetAppInstalled()
        assertThat(queryWebAppFromHelper(INELIGIBLE_TEST_APP))
            .isEqualTo(WebAppQueryRequest.RESULT_NOT_INSTALLED)
    }

    @Test
    @ApiTest(apis = ["android.content.pm.webapp.WebAppManager#query"])
    fun testQuery_NonExistentPackage_WithoutQueryAllPackages_ReturnsPermissionDenied() {
        assertThat(queryWebApp(NONEXISTING_PACKAGE))
            .isEqualTo(WebAppQueryRequest.RESULT_PERMISSION_DENIED)
    }

    @Test
    @ApiTest(apis = ["android.content.pm.webapp.WebAppManager#query"])
    fun testQuery_NonExistentPackage_WithQueryAllPackages_ReturnsNotInstalled() {
        assertThat(queryWebAppFromHelper(NONEXISTING_PACKAGE))
            .isEqualTo(WebAppQueryRequest.RESULT_NOT_INSTALLED)
    }

    @Test
    @ApiTest(apis = ["android.content.pm.webapp.WebAppManager#query"])
    fun testQuery_ServiceUnavailable_ReturnsUnavailable() {
        SystemUtil.runShellCommand("settings put global enable_webapp_minter 0")
        try {
            assertThat(queryWebApp(INELIGIBLE_TEST_APP))
                .isEqualTo(WebAppQueryRequest.RESULT_UNAVAILABLE)
            assertThat(queryWebAppFromHelper(INELIGIBLE_TEST_APP))
                .isEqualTo(WebAppQueryRequest.RESULT_UNAVAILABLE)
        } finally {
            SystemUtil.runShellCommand("settings put global enable_webapp_minter 1")
        }
    }

    @Test
    @ApiTest(
        apis =
            [
                "android.content.pm.webapp.WebAppQueryRequest#WebAppQueryRequest",
                "android.content.pm.webapp.WebAppQueryRequest#getPackageName",
            ]
    )
    fun testQueryRequest() {
        val packageName = "com.android.webapp.testapp"
        val request = WebAppQueryRequest(packageName)
        assertThat(request.packageName).isEqualTo(packageName)
    }

    private fun assertTargetAppInstalled() {
        try {
            assertThat(
                    context.packageManager.getPackageInfo(
                        INELIGIBLE_TEST_APP,
                        PackageManager.PackageInfoFlags.of(0),
                    )
                )
                .isNotNull()
        } catch (e: Exception) {
            assertThat(e).isNull()
        }
    }

    private fun queryWebApp(packageName: String): Int {
        val queryRequest = WebAppQueryRequest(packageName)
        val latch = CountDownLatch(1)
        val queryResult = AtomicInteger(-1)

        webAppManager.query(queryRequest, context.mainExecutor) { resultCode ->
            queryResult.set(resultCode)
            latch.countDown()
        }

        latch.await(queryTimeout, TimeUnit.MILLISECONDS)
        return queryResult.get()
    }

    private fun queryWebAppFromHelper(targetPackageName: String): Int {
        val intent = android.content.Intent("android.webapp.tests.query.ACTION_QUERY")
        intent.setPackage("android.webapp.tests.query")
        intent.putExtra("android.webapp.tests.query.extra.PACKAGE_NAME", targetPackageName)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

        val latch = CountDownLatch(1)
        val queryResult = AtomicInteger(-1)

        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: android.content.Context,
                    intent: android.content.Intent,
                ) {
                    if (intent.action == "android.webapp.tests.query.ACTION_QUERY_RESULT") {
                        val result =
                            intent.getIntExtra("android.webapp.tests.query.extra.RESULT_CODE", -1)
                        queryResult.set(result)
                        latch.countDown()
                    }
                }
            }

        context.registerReceiver(
            receiver,
            android.content.IntentFilter("android.webapp.tests.query.ACTION_QUERY_RESULT"),
            android.content.Context.RECEIVER_EXPORTED,
        )

        try {
            context.startActivity(intent)
            latch.await(queryTimeout, TimeUnit.MILLISECONDS)
        } finally {
            context.unregisterReceiver(receiver)
        }

        return queryResult.get()
    }

    companion object {
        const val NONEXISTING_PACKAGE = "android.webapp.tests.nonexist"
    }
}

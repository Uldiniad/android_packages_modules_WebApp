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

package com.android.webapp.service

import android.annotation.PermissionManuallyEnforced
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.android.webapp.IWebAppInstallCallback
import com.android.webapp.IWebAppService
import com.android.webapp.IWebAppVerifyCallback
import com.android.webapp.service.model.InstallRequest
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Entry point of the Web App Service. */
class WebAppService : LifecycleService() {

    private val mBinder =
        object : IWebAppService.Stub() {
            @PermissionManuallyEnforced
            override fun install(
                title: String,
                manifestUrl: String,
                callback: IWebAppInstallCallback,
            ) {
                val callingUid = getCallingUid()
                val pm = applicationContext.packageManager

                val callingPackages = pm.getPackagesForUid(callingUid)
                val callingPackage =
                    callingPackages?.singleOrNull()
                        ?: run {
                            Log.e(
                                TAG,
                                "Calling UID $callingUid does not map to a single package: $callingPackages",
                            )
                            // TODO: Replace with actual error code after SDK interface is available
                            callback.onInstallResult(1)
                            return
                        }
                Log.d(TAG, "Calling UID: $callingUid Package: $callingPackage")

                // TODO: Caller verification

                val request =
                    InstallRequest(callingUid, callingPackage, manifestUrl, title, callback)
                lifecycleScope.launch {
                    val result =
                        withTimeoutOrNull(INSTALL_ENQUEUE_TIMEOUT_SECONDS.seconds) {
                            installer.enqueueRequest(request)
                        }
                            ?: run {
                                Log.e(TAG, "Timed out enqueuing install request $request")
                                // TODO: Replace with actual error code after SDK interface is
                                // available
                                callback.onInstallResult(1)
                                return@launch
                            }

                    if (!result) {
                        // TODO: Replace with actual error code after SDK interface is available
                        callback.onInstallResult(1)
                    }
                }
            }

            @PermissionManuallyEnforced
            override fun verifyPackage(packageName: String, callback: IWebAppVerifyCallback) {
                // TODO: Caller verification
                lifecycleScope.launch {
                    val result =
                        withTimeoutOrNull(PACKAGE_VERIFY_TIMEOUT_SECONDS.seconds) {
                            installer.verifyPackage(packageName)
                        }
                            ?: run {
                                Log.e(TAG, "Timed out verifying package $packageName")
                                // TODO: Replace with actual error code after SDK interface is
                                // available
                                callback.onVerifyResult(1)
                                return@launch
                            }
                    callback.onVerifyResult(result)
                }
            }
        }

    private val installerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val installer: WebAppInstaller by lazy {
        WebAppInstaller(applicationContext, installerScope)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        Log.d(TAG, "Service onBind")
        return mBinder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        installerScope.cancel("Cancelled due to service being shut down")
    }

    companion object {
        private const val TAG = "WebAppService"
        private const val INSTALL_ENQUEUE_TIMEOUT_SECONDS = 10
        private const val PACKAGE_VERIFY_TIMEOUT_SECONDS = 10
    }
}

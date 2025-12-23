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
import android.content.pm.PackageManager
import android.content.pm.webapp.WebAppInstallRequest
import android.content.pm.webapp.WebAppQueryRequest
import android.os.IBinder
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.android.webapp.IWebAppInstallCallback
import com.android.webapp.IWebAppQueryCallback
import com.android.webapp.IWebAppService
import com.android.webapp.service.model.InstallRequest
import kotlin.system.exitProcess
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
            /**
             * Process the installation request. See [WebAppInstallRequest.RESULT_PERMISSION_DENIED]
             * for what apps are eligible of calling this method.
             */
            @PermissionManuallyEnforced
            override fun install(
                title: String,
                manifestUrl: String,
                callback: IWebAppInstallCallback,
            ) {
                val callingUid = getCallingUid()
                val pm = applicationContext.packageManager

                // Clear the calling identity. This ensures the PackageManager and RoleManager
                // queries run with the permissions of the current process (system app), not the
                // calling app.
                val token = clearCallingIdentity()

                val callingPackage: String
                val callingAppName: CharSequence
                try {
                    val callingPackages = pm.getPackagesForUid(callingUid)
                    callingPackage =
                        callingPackages?.singleOrNull()
                            ?: run {
                                Log.e(
                                    TAG,
                                    "Calling UID $callingUid does not map to a single package: $callingPackages",
                                )
                                callback.onInstallResult(
                                    WebAppInstallRequest.RESULT_INVALID_ARGUMENTS,
                                    null,
                                )
                                return
                            }
                    val callingAppInfo =
                        pm.getApplicationInfo(
                            callingPackage,
                            PackageManager.ApplicationInfoFlags.of(0),
                        )
                    callingAppName = pm.getApplicationLabel(callingAppInfo)
                    Log.d(
                        TAG,
                        "Calling UID: $callingUid Package: $callingPackage Name: $callingAppName",
                    )

                    // Check if the calling app is eligible of installing web apps.
                    val intent =
                        Intent(Intent.ACTION_VIEW).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            setData("http:".toUri())
                        }

                    val resolveInfo =
                        pm.queryIntentActivities(
                            intent,
                            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
                        )

                    if (resolveInfo.none { it.activityInfo.packageName == callingPackage }) {
                        Log.e(
                            TAG,
                            "The calling app $callingPackage is not eligible to be a browser.",
                        )
                        callback.onInstallResult(
                            WebAppInstallRequest.RESULT_PERMISSION_DENIED,
                            null,
                        )
                        return
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.e(TAG, "Failed to get calling app info for UID $callingUid", e)
                    callback.onInstallResult(WebAppInstallRequest.RESULT_INVALID_ARGUMENTS, null)
                    return
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to check browser eligibility for UID $callingUid.", e)
                    callback.onInstallResult(WebAppInstallRequest.RESULT_INTERNAL_ERROR, null)
                    return
                } finally {
                    // Always restore the identity
                    restoreCallingIdentity(token)
                }

                val request =
                    InstallRequest(
                        callingUid,
                        callingPackage,
                        callingAppName,
                        manifestUrl,
                        title,
                        callback,
                    )
                lifecycleScope.launch {
                    val result =
                        withTimeoutOrNull(INSTALL_ENQUEUE_TIMEOUT_SECONDS.seconds) {
                            installer.enqueueRequest(request)
                        }
                            ?: run {
                                Log.e(TAG, "Timed out enqueuing install request $request")
                                callback.onInstallResult(
                                    WebAppInstallRequest.RESULT_INTERNAL_ERROR,
                                    null,
                                )
                                return@launch
                            }

                    if (!result) {
                        callback.onInstallResult(
                            WebAppInstallRequest.RESULT_DUPLICATED_REQUEST,
                            null,
                        )
                    }
                }
            }

            @PermissionManuallyEnforced
            override fun queryPackage(packageName: String, callback: IWebAppQueryCallback) {
                val callingUid = getCallingUid()

                lifecycleScope.launch {
                    val result =
                        withTimeoutOrNull(PACKAGE_QUERY_TIMEOUT_SECONDS.seconds) {
                            // Clear the calling identity. This ensures that PackageManager queries
                            // run with the permissions of the current process (system app), not the
                            // calling app.
                            val token = clearCallingIdentity()
                            try {
                                installer.queryPackage(packageName, callingUid)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error querying $packageName", e)
                                WebAppQueryRequest.RESULT_INTERNAL_ERROR
                            } finally {
                                // Always restore the identity
                                restoreCallingIdentity(token)
                            }
                        }
                            ?: run {
                                Log.e(TAG, "Timed out querying package $packageName")
                                callback.onQueryResult(WebAppQueryRequest.RESULT_INTERNAL_ERROR)
                                return@launch
                            }
                    callback.onQueryResult(result)
                }
            }
        }

    private val installerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val installer: WebAppInstaller by lazy { WebAppInstaller(this, installerScope) }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        Log.d(TAG, "Service onBind")
        return mBinder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        installerScope.cancel("Cancelled due to service being shut down")
        exitProcess(0)
    }

    companion object {
        private const val TAG = "WebAppService"
        private const val INSTALL_ENQUEUE_TIMEOUT_SECONDS = 10
        private const val PACKAGE_QUERY_TIMEOUT_SECONDS = 10
    }
}

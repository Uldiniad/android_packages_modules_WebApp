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

package com.android.webapp.service.view

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.android.webapp.service.InstallContinuationManager
import com.android.webapp.service.model.InstallRequest
import kotlin.coroutines.resume

class InstallConfirmActivity() : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestBundle =
            intent.getBundleExtra(EXTRA_INSTALL_REQUEST)
                ?: run {
                    Log.e(TAG, "No install request in intent")
                    finish()
                    return
                }
        val request =
            InstallRequest.fromBundle(requestBundle)
                ?: run {
                    Log.e(TAG, "Failed to get InstallRequest from Bundle")
                    finish()
                    return
                }

        val appName =
            intent.getStringExtra(EXTRA_APP_NAME)
                ?: run {
                    Log.e(TAG, "$EXTRA_APP_NAME is null in intent")
                    resumeInstall(request, false)
                    return
                }
        val startUrl =
            intent.getStringExtra(EXTRA_APP_START_URL)
                ?: run {
                    Log.e(TAG, "$EXTRA_APP_START_URL is null in intent")
                    resumeInstall(request, false)
                    return
                }

        setContent {
            ColorScheme {
                InstallConfirmDialog(
                    appName = appName,
                    startUrl = startUrl,
                    invokingAppName = request.callingAppName.toString(),
                    onInstall = { resumeInstall(request, true) },
                    onCancel = { resumeInstall(request, false) },
                )
            }
        }
    }

    fun resumeInstall(request: InstallRequest, result: Boolean) {
        Log.i(TAG, "Resuming installation for $request with user confirm: $result")
        InstallContinuationManager.userContinuations.remove(request)?.resume(result)
        finish()
    }

    companion object {
        private const val TAG = "InstallConfirmDialog"
        const val EXTRA_INSTALL_REQUEST = "install_request"
        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_APP_START_URL = "app_start_url"
    }
}

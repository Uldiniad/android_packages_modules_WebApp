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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlin.coroutines.resume

/**
 * A [BroadcastReceiver] that receives package manager app install result and resumes installation
 * in [WebAppInstaller].
 */
class PackageManagerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val sessionId = intent?.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (sessionId == null || sessionId == -1) {
            Log.e(TAG, "Failed to get session ID")
            return
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "Install result for session ID $sessionId: $status $statusMessage")
        InstallContinuationManager.pmContinuations
            .remove(sessionId)
            ?.resume(Pair(status, statusMessage))
    }

    companion object {
        private const val TAG = "PackageManagerReceiver"
    }
}

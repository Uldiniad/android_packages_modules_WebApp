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

package com.android.webapp.service.model

import android.os.Bundle
import android.util.Log
import com.android.webapp.IWebAppInstallCallback
import java.util.Objects

/**
 * A Web App install request. If two requests have the same [callingUid] and [manifestUrl], they are
 * considered the same request.
 *
 * **NOTE: Make sure [toBundle] and [fromBundle] methods are updated whenever fields are changed on
 * this data class.**
 */
data class InstallRequest(
    val callingUid: Int,
    val callingPackage: String,
    val callingAppName: CharSequence,
    val manifestUrl: String,
    val appName: String,
    val callback: IWebAppInstallCallback,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InstallRequest) return false
        if (callingUid != other.callingUid) return false
        if (manifestUrl != other.manifestUrl) return false
        return true
    }

    override fun hashCode(): Int {
        return Objects.hash(callingUid, manifestUrl)
    }

    fun toBundle(): Bundle {
        return Bundle().apply {
            putInt(KEY_CALLING_UID, callingUid)
            putString(KEY_CALLING_PACKAGE, callingPackage)
            putCharSequence(KEY_CALLING_APP_NAME, callingAppName)
            putString(KEY_MANIFEST_URL, manifestUrl)
            putString(KEY_APP_NAME, appName)
            putBinder(KEY_CALLBACK, callback.asBinder())
        }
    }

    companion object {
        private const val TAG = "InstallRequest"
        private const val KEY_CALLING_UID = "calling_uid"
        private const val KEY_CALLING_PACKAGE = "calling_package"
        private const val KEY_CALLING_APP_NAME = "calling_app_name"
        private const val KEY_MANIFEST_URL = "manifest_url"
        private const val KEY_APP_NAME = "app_name"
        private const val KEY_CALLBACK = "callback"

        fun fromBundle(bundle: Bundle): InstallRequest? {
            val callingUid =
                bundle.getInt(KEY_CALLING_UID, -1).also {
                    if (it == -1) {
                        Log.e(TAG, "Bundle missing required key: $KEY_CALLING_UID")
                        return null
                    }
                }
            val callingPackage =
                bundle.getString(KEY_CALLING_PACKAGE)
                    ?: run {
                        Log.e(TAG, "Bundle missing required key: $KEY_CALLING_PACKAGE")
                        return null
                    }
            val callingAppName =
                bundle.getCharSequence(KEY_CALLING_APP_NAME)
                    ?: run {
                        Log.e(TAG, "Bundle missing required key: $KEY_CALLING_APP_NAME")
                        return null
                    }
            val manifestUrl =
                bundle.getString(KEY_MANIFEST_URL)
                    ?: run {
                        Log.e(TAG, "Bundle missing required key: $KEY_MANIFEST_URL")
                        return null
                    }
            val appName =
                bundle.getString(KEY_APP_NAME)
                    ?: run {
                        Log.e(TAG, "Bundle missing required key: $KEY_APP_NAME")
                        return null
                    }
            val binder =
                bundle.getBinder(KEY_CALLBACK)
                    ?: run {
                        Log.e(TAG, "Bundle missing required key: $KEY_CALLBACK")
                        return null
                    }
            return InstallRequest(
                callingUid = callingUid,
                callingPackage = callingPackage,
                callingAppName = callingAppName,
                manifestUrl = manifestUrl,
                appName = appName,
                callback = IWebAppInstallCallback.Stub.asInterface(binder),
            )
        }
    }
}

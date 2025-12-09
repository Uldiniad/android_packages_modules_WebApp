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

import com.android.webapp.IWebAppInstallCallback
import java.util.Objects

/**
 * A Web App install request. If two requests have the same [callingUid] and [manifestUrl], they are
 * considered the same request.
 */
data class InstallRequest(
    val callingUid: Int,
    val callingPackage: String,
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
}

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

package com.android.webapp;

import com.android.webapp.IWebAppInstallCallback;
import com.android.webapp.IWebAppVerifyCallback;

/**
 * Interface to interact with the Web App service.
 * @hide
 */
interface IWebAppService {
    /**
     * Installs the web app using a manifest URL. The request will be processed by the Web App
     * Service in the background. The installation result is delivered via the provided callback.
     *
     * @param title The title of the app to install. This is used to show the install progress to
     *     the user before the installer can read the manifest. This may not be the name of the
     *     minted app as the names defined in the manifest will be prioritized.
     * @param manifestUrl The URL of the PWA manifest.
     * @param callback The callback to receive the installation result.
     */
    @PermissionManuallyEnforced
    void install(
        String title,
        String manifestUrl,
        in IWebAppInstallCallback callback
    );

    /**
     * Verifies if an Android app is installed by the Web App Service.
     *
     * @param packageName Package name of the app to verify.
     * @param callback The callback to receive the verification result.
     */
    @PermissionManuallyEnforced
    void verifyPackage(String packageName, in IWebAppVerifyCallback callback);
}

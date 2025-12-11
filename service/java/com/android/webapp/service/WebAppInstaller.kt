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

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.webapp.WebAppInstallRequest
import android.util.Log
import android.webkit.URLUtil
import com.android.webapp.service.minter.ApkInfo
import com.android.webapp.service.minter.ApkKeyStore
import com.google.common.xml.XmlEscapers
import com.android.webapp.service.minter.ApkMinter
import com.android.webapp.service.model.InstallRequest
import com.android.webapp.service.model.PwaManifest
import com.android.webapp.service.view.InstallConfirmActivity
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** Processes Web App installation requests in a queue. */
class WebAppInstaller(val service: WebAppService, scope: CoroutineScope) {

    private val context = service.applicationContext

    private var installRequests = mutableSetOf<InstallRequest>()
    private val mutex = Mutex()

    private val processingChannel = Channel<InstallRequest>(MAX_QUEUE_LENGTH)

    private val keyStore: ApkKeyStore by lazy { ApkKeyStore() }

    init {
        scope.launch { processQueue() }
    }

    /**
     * Inserts an [InstallRequest] into the installer's queue.
     *
     * @param[request] The request to insert.
     * @return True if the request is successfully enqueued. False if the same request is already in
     *   the queue.
     */
    suspend fun enqueueRequest(request: InstallRequest): Boolean {
        mutex.withLock {
            if (installRequests.contains(request)) {
                Log.e(TAG, "The install request $request is already being processed")
                return false
            }
            installRequests.add(request)

            // This will suspend until a slot is available in the processing channel.
            processingChannel.send(request)
            return true
        }
    }

    private suspend fun processQueue() {
        for (request in processingChannel) {
            try {
                performInstall(request)
            } finally {
                mutex.withLock { installRequests.remove(request) }
            }
        }
    }

    private suspend fun performInstall(request: InstallRequest) {
        Log.i(TAG, "Start installing $request")

        // TODO(b/448503528): Parse manifest properly
        val manifest =
            fetchAndParseManifest(request.manifestUrl)
                ?: run {
                    Log.e(TAG, "Failed to fetch and parse manifest")
                    request.callback.onInstallResult(
                        WebAppInstallRequest.RESULT_NETWORK_ERROR,
                        null,
                    )
                    return
                }
        val apkInfo = generateApkInfo(request, manifest)
        val packageName = apkInfo.packageName

        if (!waitForUserConfirm(request, apkInfo)) {
            Log.w(TAG, "User cancelled installation")
            request.callback.onInstallResult(WebAppInstallRequest.RESULT_CANCELLED_BY_USER, null)
            return
        }

        val workingDir = File(context.cacheDir, packageName)
        try {
            if (workingDir.exists() && !workingDir.deleteRecursively()) {
                Log.e(TAG, "Failed to delete existing working directory")
                request.callback.onInstallResult(WebAppInstallRequest.RESULT_INTERNAL_ERROR, null)
                return
            }
            if (!workingDir.mkdirs()) {
                Log.e(TAG, "Failed to create working directory")
                request.callback.onInstallResult(WebAppInstallRequest.RESULT_INTERNAL_ERROR, null)
                return
            }

            val signedApk =
                ApkMinter(keyStore, workingDir, apkInfo).mintApk()
                    ?: run {
                        Log.e(TAG, "Failed to mint APK")
                        request.callback.onInstallResult(
                            WebAppInstallRequest.RESULT_INTERNAL_ERROR,
                            null,
                        )
                        return
                    }

            Log.i(TAG, "Start installing minted APK for $packageName")
            val installResult =
                withTimeoutOrNull(PACKAGE_MANAGER_TIMEOUT_SECONDS.seconds) {
                    installApk(signedApk, packageName, request)
                }
                    ?: run {
                        Log.e(TAG, "Timed out waiting for package manager to install $packageName")
                        request.callback.onInstallResult(
                            WebAppInstallRequest.RESULT_INTERNAL_ERROR,
                            null,
                        )
                        return
                    }

            if (installResult != PackageInstaller.STATUS_SUCCESS) {
                Log.e(TAG, "Failed to install APK: $installResult")
                request.callback.onInstallResult(WebAppInstallRequest.RESULT_INTERNAL_ERROR, null)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mint and install APK", e)
            request.callback.onInstallResult(WebAppInstallRequest.RESULT_INTERNAL_ERROR, null)
            return
        } finally {
            if (!workingDir.deleteRecursively()) {
                Log.e(TAG, "Failed to delete working directory: $workingDir")
            }
        }

        Log.i(TAG, "Successfully installed $packageName for $request")
        request.callback.onInstallResult(WebAppInstallRequest.RESULT_SUCCESS, packageName)
        return
    }

    /**
     * Wait for user confirmation in [InstallConfirmActivity].
     *
     * @return `true` if the user confirmed the installation, `false` if the user cancelled the
     *   installation, dismissed the dialog, or there was a problem processing the request in the
     *   dialog.
     */
    private suspend fun waitForUserConfirm(request: InstallRequest, apkInfo: ApkInfo): Boolean =
        suspendCancellableCoroutine { continuation ->
            InstallContinuationManager.userContinuations[request] = continuation

            val userConfirmIntent =
                Intent(context, InstallConfirmActivity::class.java).apply {
                    // TODO(b/447493504O): Provide app icon to the confirmation dialog
                    putExtra(InstallConfirmActivity.EXTRA_INSTALL_REQUEST, request.toBundle())
                    putExtra(InstallConfirmActivity.EXTRA_APP_NAME, apkInfo.appName)
                    putExtra(InstallConfirmActivity.EXTRA_APP_START_URL, apkInfo.startUrl)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            service.startActivity(userConfirmIntent)

            continuation.invokeOnCancellation {
                Log.e(TAG, "Installation for $request cancelled while awaiting user confirmation")
                InstallContinuationManager.userContinuations.remove(request)
            }
        }

    private fun fetchAndParseManifest(manifestUrl: String): PwaManifest? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(manifestUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP response code of $manifestUrl is $responseCode")
                return null
            }

            val response = connection.getInputStream().bufferedReader().use { it.readText() }
            val json = Json { ignoreUnknownKeys = true }
            return json.decodeFromString(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching or parsing $manifestUrl", e)
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun installApk(
        apkFile: File,
        packageName: String,
        request: InstallRequest,
    ): Int = suspendCancellableCoroutine { continuation ->
        val packageInstaller = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(packageName)
                setSize(apkFile.length())
                setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                // Attribute the installation to the calling app.
                setOriginatingUid(request.callingUid)
            }

        val sessionId = packageInstaller.createSession(params)
        InstallContinuationManager.pmContinuations[sessionId] = continuation
        packageInstaller.openSession(sessionId).use { session ->
            session.openWrite("WebAppService", 0, -1).use { output ->
                FileInputStream(apkFile).use { input -> input.copyTo(output) }
                session.fsync(output)
            }

            val pendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, PackageManagerReceiver::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            session.commit(pendingIntent.intentSender)
        }

        continuation.invokeOnCancellation {
            Log.e(TAG, "Installation for $packageName cancelled")
            packageInstaller.abandonSession(sessionId)
            InstallContinuationManager.pmContinuations.remove(sessionId)
        }
    }

    private fun generateApkInfo(request: InstallRequest, manifest: PwaManifest): ApkInfo {
        val appName =
            manifest.shortName
                ?: manifest.name
                ?: run {
                    Log.i(TAG, "Using fallback appName: ${request.appName}")
                    request.appName
                }
        val manifestUri = URI(request.manifestUrl)
        var startUrl =
            manifest.startUrl
                ?: run {
                    Log.i(TAG, "Using fallback startUrl: $DEFAULT_START_URL")
                    DEFAULT_START_URL
                }
        if (!URLUtil.isNetworkUrl(startUrl)) {
            startUrl = "${manifestUri.scheme}://${manifestUri.host}${startUrl}"
        }
        val displayMode =
            manifest.display
                ?: run {
                    Log.i(TAG, "Using fallback displayMode: $DEFAULT_DISPLAY_MODE")
                    DEFAULT_DISPLAY_MODE
                }
        val backgroundColor =
            manifest.backgroundColor
                ?: run {
                    Log.i(TAG, "Using fallback backgroundColor: $DEFAULT_BACKGROUND_COLOR")
                    DEFAULT_BACKGROUND_COLOR
                }
        val themeColor =
            manifest.themeColor
                ?: run {
                    Log.i(TAG, "Using fallback themeColor: $DEFAULT_THEME_COLOR")
                    DEFAULT_THEME_COLOR
                }
        val themeColorDark =
            manifest.themeColorDark
                ?: run {
                    Log.i(TAG, "Using fallback themeColorDark: $DEFAULT_THEME_COLOR_DARK")
                    DEFAULT_THEME_COLOR_DARK
                }
        val navigationColor =
            manifest.navigationColor
                ?: run {
                    Log.i(TAG, "Using fallback navigationColor: $DEFAULT_NAVIGATION_COLOR")
                    DEFAULT_NAVIGATION_COLOR
                }
        val navigationColorDark =
            manifest.navigationColorDark
                ?: run {
                    Log.i(TAG, "Using fallback navigationColorDark: $DEFAULT_NAVIGATION_COLOR_DARK")
                    DEFAULT_NAVIGATION_COLOR_DARK
                }
        val orientation =
            manifest.orientation
                ?: run {
                    Log.i(TAG, "Using fallback orientation: $DEFAULT_ORIENTATION")
                    DEFAULT_ORIENTATION
                }

        val xmlAttributeEscaper = XmlEscapers.xmlAttributeEscaper()
        // All string fields must be escaped to be safely written to the AndroidManifest.xml.
        return ApkInfo(
            appName = xmlAttributeEscaper.escape(appName),
            packageName =
                xmlAttributeEscaper.escape(
                    generatePackageName(request.callingPackage, request.manifestUrl)
                ),
            manifestUrl = xmlAttributeEscaper.escape(request.manifestUrl),
            startUrl = xmlAttributeEscaper.escape(startUrl),
            hostName = xmlAttributeEscaper.escape(manifestUri.host),
            sdkVersion = xmlAttributeEscaper.escape(APK_SDK_VERSION),
            displayModeFullscreen = displayMode == "fullscreen",
            displayModeFullscreenSticky = displayMode == "fullscreen-sticky",
            displayModeMinimalUi = displayMode == "minimal-ui",
            displayModeBrowser = displayMode == "browser",
            backgroundColor = xmlAttributeEscaper.escape(backgroundColor),
            themeColor = xmlAttributeEscaper.escape(themeColor),
            themeColorDark = xmlAttributeEscaper.escape(themeColorDark),
            navigationColor = xmlAttributeEscaper.escape(navigationColor),
            navigationColorDark = xmlAttributeEscaper.escape(navigationColorDark),
            orientation = xmlAttributeEscaper.escape(orientation),
        )
    }

    // TODO(b/448503528): Use origin and manifest ID after parsing the manifest
    private fun generatePackageName(callingPackage: String, manifestUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes =
            digest.digest((callingPackage + manifestUrl).toByteArray(StandardCharsets.UTF_8))
        val hexString = hashBytes.joinToString("") { "%02x".format(it) }
        val prefixedString = PACKAGE_NAME_PREFIX + hexString
        return prefixedString.substring(0, min(prefixedString.length, PACKAGE_NAME_MAX_LENGTH))
    }

    suspend fun queryPackage(packageName: String, callingUid: Int): Int {
        return keyStore.queryPackage(context.packageManager, packageName, callingUid)
    }

    companion object {
        private const val APK_SDK_VERSION = "36"
        private const val DEFAULT_BACKGROUND_COLOR = "#bababa"
        private const val DEFAULT_DISPLAY_MODE = "standalone"
        private const val DEFAULT_NAVIGATION_COLOR = "#ff303f9f"
        private const val DEFAULT_NAVIGATION_COLOR_DARK = "#ffff0000"
        private const val DEFAULT_ORIENTATION = "any"
        private const val DEFAULT_START_URL = "/"
        private const val DEFAULT_THEME_COLOR = "#ff303f9f"
        private const val DEFAULT_THEME_COLOR_DARK = "#ff00ff00"
        private const val PACKAGE_NAME_PREFIX = "com.android.webapp"
        private const val PACKAGE_NAME_MAX_LENGTH = 150
        private const val TAG = "WebAppInstaller"
        private const val MAX_QUEUE_LENGTH = 1000
        private const val PACKAGE_MANAGER_TIMEOUT_SECONDS = 600L
    }
}

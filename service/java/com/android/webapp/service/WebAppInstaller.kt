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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import com.android.webapp.service.minter.ApkInfo
import com.android.webapp.service.minter.ApkKeyStore
import com.android.webapp.service.minter.ApkMinter
import com.android.webapp.service.model.InstallRequest
import com.android.webapp.service.model.PwaManifest
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random
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
class WebAppInstaller(val context: Context, scope: CoroutineScope) {

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

        val manifest =
            fetchAndParseManifest(request.manifestUrl)
                ?: run {
                    Log.e(TAG, "Failed to fetch and parse manifest")
                    // TODO: Replace with actual error code after SDK interface is available
                    request.callback.onInstallResult(1)
                    return
                }
        val appName = manifest.shortName ?: manifest.name ?: request.appName

        // TODO(b/448503528): Parse manifest
        // TODO: Await user confirmation

        val packageName = generatePackageName()
        val apkInfo = ApkInfo(appName, packageName)
        val workingDir = File(context.cacheDir, packageName)
        try {
            if (workingDir.exists() && !workingDir.deleteRecursively()) {
                Log.e(TAG, "Failed to delete existing working directory")
                // TODO: Replace with actual error code after SDK interface is available
                request.callback.onInstallResult(1)
                return
            }
            if (!workingDir.mkdirs()) {
                Log.e(TAG, "Failed to create working directory")
                // TODO: Replace with actual error code after SDK interface is available
                request.callback.onInstallResult(1)
                return
            }

            val signedApk =
                ApkMinter(keyStore, workingDir, apkInfo).mintApk()
                    ?: run {
                        Log.e(TAG, "Failed to mint APK")
                        // TODO: Replace with actual error code after SDK interface is available
                        request.callback.onInstallResult(1)
                        return
                    }

            Log.i(TAG, "Start installing minted APK for $packageName")
            val installResult =
                withTimeoutOrNull(PACKAGE_MANAGER_TIMEOUT_SECONDS.seconds) {
                    installApk(signedApk, packageName, request)
                }
                    ?: run {
                        Log.e(TAG, "Timed out waiting for package manager to install $packageName")
                        // TODO: Replace with actual error code after SDK interface is available
                        request.callback.onInstallResult(1)
                        return
                    }

            if (installResult != PackageInstaller.STATUS_SUCCESS) {
                Log.e(TAG, "Failed to install APK: $installResult")
                // TODO: Replace with actual error code after SDK interface is available
                request.callback.onInstallResult(1)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mint and install APK", e)
            // TODO: Replace with actual error code after SDK interface is available
            request.callback.onInstallResult(1)
            return
        } finally {
            if (!workingDir.deleteRecursively()) {
                Log.e(TAG, "Failed to delete working directory: $workingDir")
            }
        }

        Log.i(TAG, "Successfully installed $packageName for $request")
        // TODO: Replace with actual error code after SDK interface is available
        request.callback.onInstallResult(0)
        return
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
                // Attribute the installation to the calling app.
                setPackageSource(request.callingUid)
            }

        val sessionId = packageInstaller.createSession(params)
        InstallContinuationManager.continuations[sessionId] = continuation
        packageInstaller.openSession(sessionId).use { session ->
            session.openWrite("WebAppService", 0, -1).use { output ->
                FileInputStream(apkFile).use { input -> input.copyTo(output) }
                session.fsync(output)
            }

            val pendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, InstallStatusReceiver::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            session.commit(pendingIntent.intentSender)
        }

        continuation.invokeOnCancellation {
            Log.e(TAG, "Installation for $packageName cancelled")
            packageInstaller.abandonSession(sessionId)
            InstallContinuationManager.continuations.remove(sessionId)
        }
    }

    private fun generatePackageName(): String {
        // TODO(b/430234914): Generate predictable app ID
        return "webapp.id${Random.nextLong(Long.MAX_VALUE)}"
    }

    suspend fun verifyPackage(packageName: String): Int {
        return keyStore.verifyPackage(context.packageManager, packageName)
    }

    companion object {
        private const val TAG = "WebAppInstaller"
        private const val MAX_QUEUE_LENGTH = 1000
        private const val PACKAGE_MANAGER_TIMEOUT_SECONDS = 600L
    }
}

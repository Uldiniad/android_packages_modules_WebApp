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

import android.annotation.StringRes
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.webapp.WebAppInstallRequest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.TrafficStats
import android.os.Bundle
import android.os.Trace
import android.util.Log
import android.webkit.URLUtil
import androidx.core.graphics.scale
import com.android.webapp.service.minter.ApkInfo
import com.android.webapp.service.minter.ApkKeyStore
import com.android.webapp.service.minter.ApkMinter
import com.android.webapp.service.minter.ManifestIcon
import com.android.webapp.service.model.InstallRequest
import com.android.webapp.service.model.PwaManifest
import com.android.webapp.service.view.InstallConfirmActivity
import com.android.webapp.service.view.InstallErrorActivity
import com.google.common.xml.XmlEscapers
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
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
class WebAppInstaller(val service: WebAppService, scope: CoroutineScope) {

    private val context = service.applicationContext

    private var installRequests = mutableSetOf<InstallRequest>()
    private val mutex = Mutex()

    private val processingChannel = Channel<InstallRequest>(MAX_QUEUE_LENGTH)

    private val keyStore: ApkKeyStore by lazy { ApkKeyStore() }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
        scope.launch { processQueue() }
    }

    enum class NotificationType {
        IN_PROGRESS,
        SUCCESS,
        FAILURE,
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        notificationManager.createNotificationChannel(channel)
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
            // Generate a random integer as cookie for performance trace.
            val cookie = Random.nextInt()
            Trace.beginAsyncSection(INSTALL_TRACE_NAME, cookie)
            try {
                performInstall(request)
            } finally {
                mutex.withLock {
                    Trace.endAsyncSection(INSTALL_TRACE_NAME, cookie)
                    installRequests.remove(request)
                    // If the requests queue is empty, shut down the service.
                    if (installRequests.isEmpty()) {
                        Log.i(TAG, "Shutting down the service due to empty install queue")
                        service.stopSelf()
                    }
                }
            }
        }
    }

    private fun logInstallResultWithReason(
        status: Int,
        failureReason: Int,
        startTime: Long,
        apkSizeKb: Long,
        request: InstallRequest,
    ) {
        val installationTimeMs = System.currentTimeMillis() - startTime
        WebappStatsLog.write(
            WebappStatsLog.WEBAPP_INSTALLER_APP_INSTALLED,
            status,
            failureReason,
            installationTimeMs,
            apkSizeKb,
            request.callingUid,
        )
    }

    private fun logInstallResult(
        status: Int,
        startTime: Long,
        apkSizeKb: Long,
        request: InstallRequest,
    ) {
        logInstallResultWithReason(
            status,
            WebappStatsLog.WEBAPP_INSTALLER_APP_INSTALLED__FAILURE_REASON__UNKNOWN,
            startTime,
            apkSizeKb,
            request,
        )
    }

    private suspend fun performInstall(request: InstallRequest) {
        val startTime = System.currentTimeMillis()
        var apkSizeKb = 0L
        Log.i(TAG, "Start installing $request")
        val notificationId = request.hashCode()
        updateNotification(
            type = NotificationType.IN_PROGRESS,
            notificationId = notificationId,
            callingAppName = request.callingAppName,
            appName = request.appName,
            messageId = R.string.notification_getting_info,
        )

        // TODO(b/448503528): Parse manifest properly
        val manifest =
            fetchAndParseManifest(request.manifestUrl, request.callingUid)
                ?: run {
                    Log.e(TAG, "Failed to fetch and parse manifest")
                    logInstallResultWithReason(
                        WebappStatsLog
                            .WEBAPP_INSTALLER_APP_INSTALLED__STATUS__INSTALL_STATUS_FAILURE,
                        WebappStatsLog
                            .WEBAPP_INSTALLER_APP_INSTALLED__FAILURE_REASON__MANIFEST_FETCH_FAILURE,
                        startTime,
                        apkSizeKb,
                        request,
                    )
                    request.callback.onInstallResult(
                        WebAppInstallRequest.RESULT_NETWORK_ERROR,
                        null,
                    )
                    updateNotification(
                        type = NotificationType.FAILURE,
                        notificationId = notificationId,
                        callingAppName = request.callingAppName,
                        appName = request.appName,
                        messageId = R.string.notification_install_failed,
                        errorMessage = "Failed to fetch and parse manifest",
                    )
                    return
                }
        val apkInfo = generateApkInfo(request, manifest)
        val packageName = apkInfo.packageName
        val appName = apkInfo.appName

        // TODO: Add fallback to handle the case where icons are not provided in the manifest.
        if (apkInfo.icons.isEmpty()) {
            Log.e(TAG, "No icons found in the manifest")
            logInstallResultWithReason(
                WebappStatsLog.WEBAPP_INSTALLER_APP_INSTALLED__STATUS__INSTALL_STATUS_FAILURE,
                WebappStatsLog
                    .WEBAPP_INSTALLER_APP_INSTALLED__FAILURE_REASON__MANIFEST_PARSE_FAILURE,
                startTime,
                apkSizeKb,
                request,
            )
            request.callback.onInstallResult(WebAppInstallRequest.RESULT_INVALID_ARGUMENTS, null)
            updateNotification(
                type = NotificationType.FAILURE,
                notificationId = notificationId,
                callingAppName = request.callingAppName,
                appName = appName,
                messageId = R.string.notification_install_failed,
                errorMessage = "No icons found in the manifest",
            )
            return
        }

        val workingDir = File(context.cacheDir, packageName)
        var appIcon: Bitmap? = null
        try {
            if (workingDir.exists() && !workingDir.deleteRecursively()) {
                Log.e(TAG, "Failed to delete existing working directory")
                logInstallResultWithReason(
                    WebappStatsLog.WEBAPP_INSTALLER_APP_INSTALLED__STATUS__INSTALL_STATUS_FAILURE,
                    WebappStatsLog
                        .WEBAPP_INSTALLER_APP_INSTALLED__FAILURE_REASON__WORKING_DIR_DELETION_FAILURE,
                    startTime,
                    apkSizeKb,
                    request,
                )
                request.callback.onInstallResult(WebAppInstallRequest.RESULT_INTERNAL_ERROR, null)
                updateNotification(
                    type = NotificationType.FAILURE,
                    notificationId = notificationId,
                    callingAppName = request.callingAppName,
                    appName = appName,
                    messageId = R.string.notification_install_failed,
                    errorMessage = "Failed to delete existing working directory",
                )
                return
            }
            if (!workingDir.mkdirs()) {
                Log.e(TAG, "Failed to create working directory")
                logInstallResultWithReason(
                    WebappStatsLog.WEBAPP_INSTALLER_APP_INSTALLED__STATUS__INSTALL_STATUS_FAILURE,
                    WebappStatsLog
                        .WEBAPP_INSTALLER_APP_INSTALLED__FAILURE_REASON__WORKING_DIR_CREATION_FAILURE,
                    startTime,
                    apkSizeKb,
                    request,
                )
                request.callback.onInstallResult(WebAppInstallRequest.RESULT_INTERNAL_ERROR, null)
                updateNotification(
                    type = NotificationType.FAILURE,
                    notificationId = notificationId,
                    callingAppName = request.callingAppName,
                    appName = appName,
                    messageId = R.string.notification_install_failed,
                    errorMessage = "Failed to create working directory",
                )
                return
            }

            val downloadIconsResult = downloadIcons(apkInfo.icons, workingDir, request.callingUid)
            appIcon = downloadIconsResult.second
            if (!downloadIconsResult.first || appIcon == null) {
                // TODO: Add fallback to handle the case where icons are not downloaded
                // successfully.
                Log.e(TAG, "Failed to download icons")
                logInstallResultWithReason(
                    WebappStatsLog.WEBAPP_INSTALLER_APP_INSTALLED__STATUS__INSTALL_STATUS_FAILURE,
                    WebappStatsLog
                        .WEBAPP_INSTALLER_APP_INSTALLED__FAILURE_REASON__ICON_DOWNLOAD_FAILURE,
                    startTime,
                    apkSizeKb,
                    request,
                )
                request.callback.onInstallResult(WebAppInstallRequest.RESULT_NETWORK_ERROR, null)
                updateNotification(
                    type = NotificationType.FAILURE,
                    notificationId = notificationId,
                    callingAppName = request.callingAppName,
                    appName = appName,
                    messageId = R.string.notification_install_failed,
                    errorMessage = "Failed to download icons",
                )
                return
            }

            updateNotification(
                type = NotificationType.IN_PROGRESS,
                notificationId = notificationId,
                callingAppName = request.callingAppName,
                appName = appName,
                messageId = R.string.notification_installing,
                largeIcon = appIcon,
            )

            if (!waitForUserConfirm(request, apkInfo, appIcon)) {
                Log.w(TAG, "User cancelled installation")
                logInstallResult(
                    WebappStatsLog.WEBAPP_INSTALLER_APP_INSTALLED__STATUS__INSTALL_STATUS_CANCELLED,
                    startTime,
                    apkSizeKb,
                    request,
                )
                request.callback.onInstallResult(
                    WebAppInstallRequest.RESULT_CANCELLED_BY_USER,
                    null,
                )
                updateNotification(
                    type = NotificationType.FAILURE,
                    notificationId = notificationId,
                    callingAppName = request.callingAppName,
                    appName = appName,
                    messageId = R.string.notification_cancelled,
                    largeIcon = appIcon,
                )
                return
            }

            val signedApk =
                ApkMinter(keyStore, workingDir, apkInfo).mintApk()
                    ?: run {
                        Log.e(TAG, "Failed to mint APK")
                        logInstallResultWithReason(
                            WebappStatsLog
                                .WEBAPP_INSTALLER_APP_INSTALLED__STATUS__INSTALL_STATUS_FAILURE,
                            WebappStatsLog
                                .WEBAPP_INSTALLER_APP_INSTALLED__FAILURE_REASON__APK_MINTING_FAILURE,
                            startTime,
                            apkSizeKb,
                            request,
                        )
                        request.callback.onInstallResult(
                            WebAppInstallRequest.RESULT_INTERNAL_ERROR,
                            null,
                        )
                        updateNotification(
                            type = NotificationType.FAILURE,
                            notificationId = notificationId,
                            callingAppName = request.callingAppName,
                            appName = appName,
                            messageId = R.string.notification_install_failed,
                            largeIcon = appIcon,
                            errorMessage = "Failed to mint APK",
                        )
                        return
                    }
            apkSizeKb = signedApk.length() / 1024

            Log.i(TAG, "Start installing minted APK for $packageName")
            val installResult =
                withTimeoutOrNull(PACKAGE_MANAGER_TIMEOUT_SECONDS.seconds) {
                    installApk(signedApk, packageName, request)
                }
                    ?: run {
                        Log.e(TAG, "Timed out waiting for package manager to install $packageName")
                        logInstallResultWithReason(
                            WebappStatsLog
                                .WEBAPP_INSTALLER_APP_INSTALLED__STATUS__INSTALL_STATUS_FAILURE,
                            WebappStatsLog
                                .WEBAPP_INSTALLER_APP_INSTALLED__FAILURE_REASON__PACKAGE_MANAGER_FAILURE,
                            startTime,
                            apkSizeKb,
                            request,
                        )
                        request.callback.onInstallResult(
                            WebAppInstallRequest.RESULT_INTERNAL_ERROR,
                            null,
                        )
                        updateNotification(
                            type = NotificationType.FAILURE,
                            notificationId = notificationId,
                            callingAppName = request.callingAppName,
                            appName = appName,
                            messageId = R.string.notification_install_failed,
                            largeIcon = appIcon,
                            errorMessage = "Timed out waiting for package manager",
                        )
                        return
                    }
            val statusCode = installResult.first
            val errorMessage = installResult.second

            if (statusCode != PackageInstaller.STATUS_SUCCESS) {
                Log.e(TAG, "Failed to install APK: $statusCode\nError message: $errorMessage")
                logInstallResultWithReason(
                    WebappStatsLog.WEBAPP_INSTALLER_APP_INSTALLED__STATUS__INSTALL_STATUS_FAILURE,
                    WebappStatsLog
                        .WEBAPP_INSTALLER_APP_INSTALLED__FAILURE_REASON__APK_INSTALL_FAILURE,
                    startTime,
                    apkSizeKb,
                    request,
                )
                request.callback.onInstallResult(
                    if (statusCode == PackageInstaller.STATUS_FAILURE_BLOCKED)
                        WebAppInstallRequest.RESULT_SECURITY_ERROR
                    else WebAppInstallRequest.RESULT_INTERNAL_ERROR,
                    null,
                )
                updateNotification(
                    type = NotificationType.FAILURE,
                    notificationId = notificationId,
                    callingAppName = request.callingAppName,
                    appName = appName,
                    messageId = R.string.notification_install_failed,
                    largeIcon = appIcon,
                    errorMessage =
                        "Failed to install APK: $statusCode\nError message: $errorMessage",
                )
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mint and install APK", e)
            logInstallResultWithReason(
                WebappStatsLog.WEBAPP_INSTALLER_APP_INSTALLED__STATUS__INSTALL_STATUS_FAILURE,
                WebappStatsLog.WEBAPP_INSTALLER_APP_INSTALLED__FAILURE_REASON__APK_MINTING_FAILURE,
                startTime,
                apkSizeKb,
                request,
            )
            request.callback.onInstallResult(WebAppInstallRequest.RESULT_INTERNAL_ERROR, null)
            updateNotification(
                type = NotificationType.FAILURE,
                notificationId = notificationId,
                callingAppName = request.callingAppName,
                appName = appName,
                messageId = R.string.notification_install_failed,
                largeIcon = appIcon,
                errorMessage = "Failed to mint and install APK",
            )
            return
        } finally {
            if (!workingDir.deleteRecursively()) {
                Log.e(TAG, "Failed to delete working directory: $workingDir")
            }
        }

        Log.i(TAG, "Successfully installed $packageName for $request")
        logInstallResult(
            WebappStatsLog.WEBAPP_INSTALLER_APP_INSTALLED__STATUS__INSTALL_STATUS_SUCCESS,
            startTime,
            apkSizeKb,
            request,
        )
        request.callback.onInstallResult(WebAppInstallRequest.RESULT_SUCCESS, packageName)
        updateNotification(
            type = NotificationType.SUCCESS,
            notificationId = notificationId,
            callingAppName = request.callingAppName,
            appName = appName,
            messageId = R.string.notification_installed,
            largeIcon = appIcon,
            packageName = packageName,
        )
        return
    }

    private fun updateNotification(
        type: NotificationType,
        notificationId: Int,
        callingAppName: CharSequence,
        appName: String,
        @StringRes messageId: Int,
        largeIcon: Bitmap? = null,
        packageName: String? = null,
        errorMessage: String? = null,
    ) {
        val builder =
            Notification.Builder(context, NOTIFICATION_CHANNEL_ID).apply {
                when (type) {
                    NotificationType.IN_PROGRESS -> {
                        setOngoing(true)
                        setSmallIcon(android.R.drawable.stat_sys_download)
                    }
                    NotificationType.SUCCESS ->
                        let {
                            setOngoing(false)
                            setSmallIcon(android.R.drawable.stat_sys_download_done)

                            // Add Launch button for successful cases
                            val packageName =
                                packageName
                                    ?: run {
                                        Log.wtf(
                                            TAG,
                                            "Package name is null for successful installations",
                                        )
                                        return@let
                                    }
                            val launchIntent =
                                context.packageManager.getLaunchIntentForPackage(packageName)
                            if (launchIntent != null) {
                                val pendingIntent =
                                    PendingIntent.getActivity(
                                        context,
                                        0,
                                        launchIntent,
                                        PendingIntent.FLAG_IMMUTABLE,
                                    )
                                setContentIntent(pendingIntent)
                                val action =
                                    Notification.Action.Builder(
                                            null,
                                            context.getString(R.string.notification_launch),
                                            pendingIntent,
                                        )
                                        .build()
                                addAction(action)
                            }
                        }
                    NotificationType.FAILURE -> {
                        setOngoing(false)
                        setSmallIcon(android.R.drawable.stat_notify_error)

                        // Add Details and Got it buttons for failure cases
                        if (errorMessage != null) {
                            val detailsIntent =
                                InstallErrorActivity.createIntent(context, errorMessage).apply {
                                    setFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    )
                                }
                            val detailsPendingIntent =
                                PendingIntent.getActivity(
                                    context,
                                    0,
                                    detailsIntent,
                                    PendingIntent.FLAG_IMMUTABLE or
                                        PendingIntent.FLAG_UPDATE_CURRENT,
                                )
                            val detailsAction =
                                Notification.Action.Builder(
                                        null,
                                        context.getString(R.string.notification_action_details),
                                        detailsPendingIntent,
                                    )
                                    .build()
                            addAction(detailsAction)
                        }

                        val dismissIntent =
                            Intent(context, NotificationReceiver::class.java).apply {
                                action = NotificationReceiver.ACTION_DISMISS_NOTIFICATION
                                putExtra(NotificationReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                            }
                        val dismissPendingIntent =
                            PendingIntent.getBroadcast(
                                context,
                                0,
                                dismissIntent,
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                            )
                        val dismissAction =
                            Notification.Action.Builder(
                                    null,
                                    context.getString(R.string.notification_action_got_it),
                                    dismissPendingIntent,
                                )
                                .build()
                        addAction(dismissAction)
                    }
                }

                if (largeIcon != null) {
                    setLargeIcon(largeIcon)
                }

                // Prevent grouping of notifications so that substitute app name still works when
                // there are multiple notifications.
                setGroup("group_$notificationId")
                setContentTitle(context.getString(messageId, appName))

                addExtras(
                    Bundle().apply {
                        // Substitute notification app name with the calling app name.
                        putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, callingAppName.toString())
                    }
                )
            }

        notificationManager.notify(notificationId, builder.build())
    }

    /**
     * Wait for user confirmation in [InstallConfirmActivity].
     *
     * @return `true` if the user confirmed the installation, `false` if the user cancelled the
     *   installation, dismissed the dialog, or there was a problem processing the request in the
     *   dialog.
     */
    private suspend fun waitForUserConfirm(
        request: InstallRequest,
        apkInfo: ApkInfo,
        appIcon: Bitmap,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        InstallContinuationManager.userContinuations[request] = continuation

        val userConfirmIntent =
            Intent(context, InstallConfirmActivity::class.java).apply {
                // TODO(b/447493504O): Provide app icon to the confirmation dialog
                putExtra(InstallConfirmActivity.EXTRA_INSTALL_REQUEST, request.toBundle())
                putExtra(InstallConfirmActivity.EXTRA_APP_NAME, apkInfo.appName)
                putExtra(InstallConfirmActivity.EXTRA_APP_START_URL, apkInfo.startUrl)
                putExtra(InstallConfirmActivity.EXTRA_APP_ICON, appIcon)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        service.startActivity(userConfirmIntent)

        continuation.invokeOnCancellation {
            Log.e(TAG, "Installation for $request cancelled while awaiting user confirmation")
            InstallContinuationManager.userContinuations.remove(request)
        }
    }

    // Counted as the requesting app's traffic, so its network rules and data usage apply. The
    // socket takes the tag when connect() creates it.
    private fun connectAs(uid: Int, connection: HttpURLConnection) {
        TrafficStats.setThreadStatsUid(uid)
        try {
            connection.connect()
        } finally {
            TrafficStats.clearThreadStatsUid()
        }
    }

    private fun fetchAndParseManifest(manifestUrl: String, callingUid: Int): PwaManifest? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(manifestUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connectAs(callingUid, connection)

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

    /**
     * Installs the APK specified in [apkFile].
     *
     * @param[apkFile] The APK file to install.
     * @param[packageName] Package name of the APK.
     * @param[request] The web app installation request.
     * @return A [Pair] of [Int] and optional [String]. The [Int] is the status code
     *   [PackageInstaller.EXTRA_STATUS] from [PackageInstaller]. The optional String is the error
     *   message [PackageInstaller.EXTRA_STATUS_MESSAGE] from [PackageInstaller].
     */
    private suspend fun installApk(
        apkFile: File,
        packageName: String,
        request: InstallRequest,
    ): Pair<Int, String?> = suspendCancellableCoroutine { continuation ->
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
            startUrl = manifestUri.resolve(startUrl).toString()
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

        val icons = parseIcons(manifest, manifestUri)

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
            icons = icons,
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

    private fun downloadIcons(
        icons: List<ManifestIcon>,
        workingDir: File,
        callingUid: Int,
    ): Pair<Boolean, Bitmap?> {
        val iconsDir = File(workingDir, "icons")
        if (!iconsDir.exists() && !iconsDir.mkdirs()) {
            Log.e(TAG, "Failed to create icons directory")
            return Pair(false, null)
        }

        // The icon to display in confirmation dialog and install notifications
        var appIcon: Bitmap? = null

        var anySuccess = false
        for (icon in icons) {
            if (icon.src.isNullOrEmpty()) {
                continue
            }

            for (size in icon.sizes.sortedByDescending { it.first * it.second }) {
                val density = getDensityForSize(size.first)
                val destDir = File(iconsDir, "res/mipmap-$density")
                if (!destDir.exists() && !destDir.mkdirs()) continue

                if (icon.purpose.any { it.equals("monochrome", ignoreCase = true) }) {
                    if (downloadIcon(icon.src, File(destDir, "ic_notification.png"), callingUid)) {
                        anySuccess = true
                    }
                }

                if (icon.purpose.any { it.equals("maskable", ignoreCase = true) }) {
                    if (
                        downloadIcon(
                            icon.src,
                            File(destDir, "ic_launcher_foreground.png"),
                            callingUid,
                        )
                    ) {
                        anySuccess = true
                    }
                }

                if (
                    icon.purpose.isEmpty() ||
                        icon.purpose.any { it.equals("any", ignoreCase = true) }
                ) {
                    val destFile = File(destDir, "ic_launcher.png")
                    if (downloadIcon(icon.src, destFile, callingUid)) {
                        anySuccess = true
                        if (appIcon == null) {
                            appIcon =
                                decodeSampledBitmapFromFile(destFile, MAX_DISPLAY_APP_ICON_SIZE)
                        }
                    }
                }
            }
        }
        return Pair(anySuccess, appIcon)
    }

    private fun getDensityForSize(size: Int): String {
        return when {
            size >= 192 -> "xxxhdpi"
            size >= 144 -> "xxhdpi"
            size >= 96 -> "xhdpi"
            size >= 72 -> "hdpi"
            else -> "mdpi"
        }
    }

    private fun downloadIcon(urlStr: String, destFile: File, callingUid: Int): Boolean {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connectAs(callingUid, connection)

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return false
            }

            connection.inputStream.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading icon", e)
            return false
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseIcons(manifest: PwaManifest, manifestUri: URI): List<ManifestIcon> {
        return manifest.icons?.mapNotNull { icon ->
            val src = icon.src
            val resolvedSrc =
                if (URLUtil.isNetworkUrl(src)) {
                    src
                } else {
                    manifestUri.resolve(src).toString()
                }

            val sizes =
                icon.sizes?.split(" ")?.mapNotNull { size ->
                    val parts = size.split("x")
                    if (parts.size == 2) {
                        try {
                            parts[0].toInt() to parts[1].toInt()
                        } catch (e: NumberFormatException) {
                            null
                        }
                    } else {
                        null
                    }
                } ?: emptyList()

            val purpose = icon.purpose?.split(" ")?.toSet() ?: emptySet()

            ManifestIcon(src = resolvedSrc, type = icon.type, sizes = sizes, purpose = purpose)
        } ?: emptyList()
    }

    suspend fun queryPackage(packageName: String, callingUid: Int): Int {
        // Generate a random integer as cookie for performance trace.
        val cookie = Random.nextInt()
        Trace.beginAsyncSection(QUERY_TRACE_NAME, cookie)
        try {
            return keyStore.queryPackage(context.packageManager, packageName, callingUid)
        } finally {
            Trace.endAsyncSection(QUERY_TRACE_NAME, cookie)
        }
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
        private const val MAX_DISPLAY_APP_ICON_SIZE = 256
        private const val MAX_QUEUE_LENGTH = 1000
        private const val PACKAGE_MANAGER_TIMEOUT_SECONDS = 600L
        private const val NOTIFICATION_CHANNEL_ID = "web_app_installation"
        private const val INSTALL_TRACE_NAME = "PerformWebAppInstall"
        private const val QUERY_TRACE_NAME = "PerformWebAppQuery"

        /**
         * Loads an image from a [File] while capping its maximum size at [maxDim]. It's used to
         * prevent excessive memory usage while loading image files downloaded from the Internet.
         */
        private fun decodeSampledBitmapFromFile(file: File, maxDim: Int): Bitmap? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)

            var w = options.outWidth
            var h = options.outHeight
            var inSampleSize = 1
            // Keep dimensions >= maxDim to avoid upscaling later, but reduce memory usage.
            // We stop when dividing by 2 would make it smaller than maxDim.
            while (w / 2 >= maxDim && h / 2 >= maxDim) {
                w /= 2
                h /= 2
                inSampleSize *= 2
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize

            val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null

            // If the bitmap is still larger than maxDim (due to power of 2 steps), scale it down
            // exactly.
            if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val scale = maxDim.toFloat() / max(bitmap.width, bitmap.height)
                val scaledWidth = (bitmap.width * scale).toInt()
                val scaledHeight = (bitmap.height * scale).toInt()
                val scaled = bitmap.scale(scaledWidth, scaledHeight)
                if (scaled != bitmap) {
                    bitmap.recycle()
                }
                return scaled
            }
            return bitmap
        }
    }
}

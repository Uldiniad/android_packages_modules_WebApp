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

package android.content.pm.webapp;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.webapp.IWebAppService;
import com.android.webapp.flags.Flags;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * System service that manages the installation and querying of Web Apps.
 *
 * <p>This class acts as the entry point for managing Web Apps installed as Android apps.
 *
 * <p>To obtain an instance of this manager, use {@link Context#getSystemService(Class)}:
 *
 * <pre>{@code
 * WebAppManager webAppManager = context.getSystemService(WebAppManager.class);
 * }</pre>
 *
 * @see WebAppInstallRequest
 * @see WebAppQueryRequest
 */
@FlaggedApi(Flags.FLAG_ENABLE_WEB_APP_SERVICE)
public final class WebAppManager {
    private static final String TAG = "WebAppManager";
    private static final String WEBAPP_PACKAGE = "com.android.webapp.service";
    private static final String WEBAPP_SERVICE_CLASS = "com.android.webapp.service.WebAppService";

    /**
     * Name of the system service that manages Web Apps.
     *
     * <p>This string can be used in {@link Context#getSystemService(String)} to obtain a {@link
     * WebAppManager} instance.
     */
    public static final String SERVICE_NAME = "webapp_service";

    private static final Intent BIND_INTENT =
            new Intent(IWebAppService.class.getName())
                    .setComponent(new ComponentName(WEBAPP_PACKAGE, WEBAPP_SERVICE_CLASS));

    private final Context mContext;

    /** {@link ExecutorService} to run blocking (e.g. AIDL) methods. */
    private final ExecutorService mExecutorService = Executors.newSingleThreadExecutor();

    /**
     * Creates a {@link WebAppManager} object.
     *
     * @hide
     * @param context The context of the calling app.
     */
    WebAppManager(@NonNull Context context) {
        this.mContext = context;
    }

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private IWebAppService mWebAppService = null;

    @GuardedBy("mLock")
    private boolean mIsBinding = false;

    @GuardedBy("mLock")
    private final List<Runnable> mPendingRequests = new ArrayList<>();

    private final ServiceConnection mConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    synchronized (mLock) {
                        mWebAppService = IWebAppService.Stub.asInterface(service);
                        mIsBinding = false;
                        // Drain the queue of pending requests
                        for (Runnable request : mPendingRequests) {
                            var ignored = mExecutorService.submit(request);
                        }
                        mPendingRequests.clear();
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    synchronized (mLock) {
                        mWebAppService = null;
                    }
                }
            };

    /**
     * Internal helper to handle connection logic and queuing.
     *
     * @param serviceTask The consumer to execute when the system is ready. It will always run on a
     *     background thread.
     * @param errorCallback The runnable to execute immediately if the system is unavailable.
     * @throws SecurityException If the caller doesn't have permission to call the service or the
     *     the service doesn't exist.
     */
    private void enqueueRequest(
            @NonNull Consumer<IWebAppService> serviceTask, @NonNull Runnable errorCallback) {
        Runnable taskToRun =
                () -> {
                    IWebAppService localWebAppService;
                    synchronized (mLock) {
                        localWebAppService = mWebAppService;
                    }
                    if (localWebAppService != null) {
                        serviceTask.accept(localWebAppService);
                    } else {
                        Log.e(TAG, "mWebAppService is null while executing request");
                        errorCallback.run();
                    }
                };
        synchronized (mLock) {
            if (mWebAppService != null) {
                var ignored = mExecutorService.submit(taskToRun);
                return;
            }

            // If service is not ready, add to queue
            mPendingRequests.add(taskToRun);

            // If already connecting, just wait (request is now in queue)
            if (mIsBinding) {
                return;
            }

            try {
                mIsBinding =
                        mContext.bindService(BIND_INTENT, mConnection, Context.BIND_AUTO_CREATE);
                if (!mIsBinding) {
                    Log.e(TAG, "The connection to WebAppService was not successful.");
                    // Binding failed immediately; remove the request we just added and fail.
                    mPendingRequests.remove(taskToRun);
                    var ignored = mExecutorService.submit(errorCallback);
                }
            } catch (SecurityException e) {
                mPendingRequests.remove(taskToRun);
                throw e;
            }
        }
    }

    /**
     * Requests the installation of a Web App.
     *
     * <p>This operation is asynchronous. The request is processed by the system in the background,
     * and the result is delivered via the callback provided in the {@code request} if set.
     *
     * @param request The {@link WebAppInstallRequest} containing the app details (such as title and
     *     manifest URL) and the result callback.
     */
    public void install(@NonNull WebAppInstallRequest request) {
        try {
            enqueueRequest(
                    request::start,
                    () ->
                            request.maybeCallCallback(
                                    WebAppInstallRequest.RESULT_INTERNAL_ERROR, null));
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException while connecting to the service", e);
            request.maybeCallCallback(WebAppInstallRequest.RESULT_PERMISSION_DENIED, null);
        }
    }

    /**
     * Queries the status of a specific Web App package.
     *
     * <p>This operation is asynchronous. The system checks if the package is installed by the Web
     * App service and visible to the caller, delivering the result via the callback provided in the
     * {@code request}.
     *
     * @param request The {@link WebAppQueryRequest} containing the package name and the result
     *     callback.
     */
    public void query(@NonNull WebAppQueryRequest request) {
        try {
            enqueueRequest(
                    request::start,
                    () -> request.callCallback(WebAppQueryRequest.RESULT_INTERNAL_ERROR));
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException while connecting to the service", e);
            request.callCallback(WebAppQueryRequest.RESULT_PERMISSION_DENIED);
        }
    }
}

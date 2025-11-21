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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.os.RemoteException;
import android.util.Log;

import com.android.webapp.IWebAppInstallCallback;
import com.android.webapp.IWebAppService;
import com.android.webapp.flags.Flags;

import java.lang.annotation.Retention;
import java.util.concurrent.Executor;

/**
 * Encapsulates the parameters and callback for installing a Web App.
 *
 * <p>Instances of this class are created using the {@link Builder} and passed to {@link
 * WebAppManager#install(WebAppInstallRequest)}.
 *
 * @see WebAppManager
 */
@FlaggedApi(Flags.FLAG_ENABLE_WEB_APP_SERVICE)
public class WebAppInstallRequest {
    private static final String TAG = "WebAppInstallRequest";

    /**
     * Result codes for the Web App installation.
     *
     * @hide
     */
    @IntDef({
        RESULT_SUCCESS,
        RESULT_NETWORK_ERROR,
        RESULT_INTERNAL_ERROR,
        RESULT_PERMISSION_DENIED,
        RESULT_DUPLICATED_REQUEST,
        RESULT_INVALID_ARGUMENTS,
        RESULT_CANCELLED_BY_USER,
    })
    @Retention(SOURCE)
    public @interface ResultCode {}

    /** The installation completed successfully. */
    public static final int RESULT_SUCCESS = 0;

    /** The installation failed due to network connectivity issues or timeout. */
    public static final int RESULT_NETWORK_ERROR = 1;

    /** The installation failed due to an internal system error. */
    public static final int RESULT_INTERNAL_ERROR = 2;

    /** The calling app does not have the necessary permission to install Web Apps. */
    public static final int RESULT_PERMISSION_DENIED = 3;

    /** An installation request for the same Web App is already in progress. */
    public static final int RESULT_DUPLICATED_REQUEST = 4;

    /** The provided arguments (e.g., manifest URL) are invalid. */
    public static final int RESULT_INVALID_ARGUMENTS = 5;

    /** The installation was cancelled by the user. */
    public static final int RESULT_CANCELLED_BY_USER = 6;

    /** Listener for the result of a Web App installation request. */
    @FlaggedApi(Flags.FLAG_ENABLE_WEB_APP_SERVICE)
    public interface Callback {
        /**
         * Invoked when the installation process has completed or failed.
         *
         * @param resultCode The result code indicating success or the specific error.
         * @param packageName The package name of the installed Web App if {@code resultCode} is
         *     {@link #RESULT_SUCCESS}; otherwise {@code null}.
         */
        void onInstallResult(int resultCode, @Nullable String packageName);
    }

    private final CharSequence mTitle;
    private final String mManifestUrl;

    final Executor mExecutor;
    final Callback mCallback;

    WebAppInstallRequest(
            @NonNull CharSequence title,
            @NonNull String manifestUrl,
            @Nullable Executor executor,
            @Nullable Callback callback) {
        this.mTitle = title;
        this.mManifestUrl = manifestUrl;
        this.mExecutor = executor;
        this.mCallback = callback;
    }

    /** Builder for creating {@link WebAppInstallRequest} instances. */
    @FlaggedApi(Flags.FLAG_ENABLE_WEB_APP_SERVICE)
    public static final class Builder {
        private final CharSequence mTitle;
        private final String mManifestUrl;
        private Executor mExecutor;
        private Callback mCallback;

        /**
         * Creates a new Builder with the required PWA manifest URL.
         *
         * @param title The initial title of the app to display during the installation process.
         *     <p>Note: The final app name may differ as it is prioritized from the content of the
         *     web manifest.
         * @param manifestUrl The URL of the PWA manifest.
         */
        public Builder(@NonNull CharSequence title, @NonNull String manifestUrl) {
            this.mTitle = title;
            this.mManifestUrl = manifestUrl;
        }

        /**
         * Sets the callback to receive the installation result.
         *
         * @param executor The {@link Executor} on which the callback will be invoked.
         * @param callback The {@link Callback} to receive the result.
         * @return This Builder instance.
         */
        public @NonNull Builder setCallback(
                @NonNull Executor executor, @NonNull Callback callback) {
            this.mExecutor = executor;
            this.mCallback = callback;
            return this;
        }

        /** Builds the WebAppInstallRequest object. */
        public @NonNull WebAppInstallRequest build() {
            return new WebAppInstallRequest(mTitle, mManifestUrl, mExecutor, mCallback);
        }
    }

    /** Returns the title provided for the installation request. */
    @NonNull
    public CharSequence getTitle() {
        return mTitle;
    }

    /** Returns the PWA manifest URL. */
    @NonNull
    public String getManifestUrl() {
        return mManifestUrl;
    }

    /** Returns the {@link Executor} used for the callback, or {@code null} if not set. */
    @Nullable
    public Executor getExecutor() {
        return mExecutor;
    }

    /** Returns the {@link Callback} used for the result, or {@code null} if not set. */
    @Nullable
    public Callback getCallback() {
        return mCallback;
    }

    void start(@NonNull IWebAppService service) {
        try {
            service.install(
                    mTitle.toString(),
                    mManifestUrl,
                    new IWebAppInstallCallback.Stub() {
                        @Override
                        @RequiresNoPermission
                        public void onInstallResult(int resultCode, @Nullable String packageName) {
                            maybeCallCallback(resultCode, packageName);
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while calling install() on service.", e);
            maybeCallCallback(RESULT_INTERNAL_ERROR, null);
        }
    }

    void maybeCallCallback(@ResultCode int result, @Nullable String packageName) {
        if (mExecutor != null && mCallback != null) {
            mExecutor.execute(() -> mCallback.onInstallResult(result, packageName));
        }
    }
}

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
import android.annotation.RequiresNoPermission;
import android.os.RemoteException;
import android.util.Log;

import com.android.webapp.IWebAppQueryCallback;
import com.android.webapp.IWebAppService;
import com.android.webapp.flags.Flags;

import java.lang.annotation.Retention;
import java.util.concurrent.Executor;

/**
 * Encapsulates the parameters and callback for querying the status of a Web App package.
 *
 * <p>Instances of this class are created using the {@link Builder} and passed to {@link
 * WebAppManager#query(WebAppQueryRequest)}.
 *
 * @see WebAppManager
 */
@FlaggedApi(Flags.FLAG_ENABLE_WEB_APP_SERVICE)
public class WebAppQueryRequest {
    private static final String TAG = "WebAppQueryRequest";

    /**
     * Result codes for the Web App query.
     *
     * @hide
     */
    @IntDef({
        RESULT_EXISTS,
        RESULT_NOT_EXISTS,
        RESULT_INTERNAL_ERROR,
        RESULT_PERMISSION_DENIED,
    })
    @Retention(SOURCE)
    public @interface ResultCode {}

    /** The queried package exists and is visible to the calling app. */
    public static final int RESULT_EXISTS = 0;

    /** The queried package does not exist or is not visible to the calling app. */
    public static final int RESULT_NOT_EXISTS = 1;

    /** The query failed due to an internal system error. */
    public static final int RESULT_INTERNAL_ERROR = 2;

    /** The calling app does not have the necessary permission to query Web Apps. */
    public static final int RESULT_PERMISSION_DENIED = 3;

    /** Listener for the result of a Web App query. */
    @FlaggedApi(Flags.FLAG_ENABLE_WEB_APP_SERVICE)
    public interface Callback {
        /**
         * Invoked when the query result is available.
         *
         * @param resultCode The result code indicating whether the package exists or an error
         *     occurred.
         */
        void onQueryResult(int resultCode);
    }

    private final String mPackageName;
    final Executor mExecutor;
    final Callback mCallback;

    public WebAppQueryRequest(
            @NonNull String packageName, @NonNull Executor executor, @NonNull Callback callback) {
        this.mPackageName = packageName;
        this.mExecutor = executor;
        this.mCallback = callback;
    }

    /** Builder for creating {@link WebAppQueryRequest} instances. */
    @FlaggedApi(Flags.FLAG_ENABLE_WEB_APP_SERVICE)
    public static final class Builder {
        private final String mPackageName;
        private final Executor mExecutor;
        private final Callback mCallback;

        /**
         * Creates a new Builder with the target package name.
         *
         * @param packageName The name of the package to query.
         * @param executor The {@link Executor} on which the callback will be invoked.
         * @param callback The {@link Callback} to receive the result.
         */
        public Builder(
                @NonNull String packageName,
                @NonNull Executor executor,
                @NonNull Callback callback) {
            this.mPackageName = packageName;
            this.mExecutor = executor;
            this.mCallback = callback;
        }

        /** Builds and returns a new {@link WebAppQueryRequest}. */
        public @NonNull WebAppQueryRequest build() {
            return new WebAppQueryRequest(mPackageName, mExecutor, mCallback);
        }
    }

    /** Returns the package name being queried. */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /** Returns the {@link Executor} used for the callback. */
    @NonNull
    public Executor getExecutor() {
        return mExecutor;
    }

    /** Returns the {@link Callback} used for the result. */
    @NonNull
    public Callback getCallback() {
        return mCallback;
    }

    void start(@NonNull IWebAppService service) {
        try {
            service.queryPackage(
                    mPackageName,
                    new IWebAppQueryCallback.Stub() {
                        @Override
                        @RequiresNoPermission
                        public void onQueryResult(int resultCode) {
                            callCallback(resultCode);
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while calling queryPackage() on service.", e);
            callCallback(RESULT_INTERNAL_ERROR);
        }
    }

    void callCallback(@ResultCode int result) {
        mExecutor.execute(() -> mCallback.onQueryResult(result));
    }
}

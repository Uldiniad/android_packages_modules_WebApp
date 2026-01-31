/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.webapp.tests.query

import android.app.Activity
import android.content.Intent
import android.content.pm.webapp.WebAppManager
import android.content.pm.webapp.WebAppQueryRequest
import android.os.Bundle
import android.util.Log
import java.util.concurrent.Executors

class QueryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action != ACTION_QUERY) {
            return
        }

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        val manager = getSystemService(WebAppManager::class.java)

        Log.i(TAG, "Querying package $packageName")
        val request = WebAppQueryRequest(packageName)

        manager.query(request, Executors.newSingleThreadExecutor()) { resultCode ->
            Log.i(TAG, "Query result: $resultCode")
            val resultIntent = Intent(ACTION_QUERY_RESULT)
            resultIntent.setPackage(CALLING_PACKAGE)
            resultIntent.putExtra(EXTRA_RESULT_CODE, resultCode)
            sendBroadcast(resultIntent)
            finish()
        }
    }

    companion object {
        const val TAG = "QueryActivity"
        const val ACTION_QUERY = "android.webapp.tests.query.ACTION_QUERY"
        const val ACTION_QUERY_RESULT = "android.webapp.tests.query.ACTION_QUERY_RESULT"
        const val EXTRA_PACKAGE_NAME = "android.webapp.tests.query.extra.PACKAGE_NAME"
        const val EXTRA_RESULT_CODE = "android.webapp.tests.query.extra.RESULT_CODE"
        const val CALLING_PACKAGE = "com.android.webapp.tests"
    }
}

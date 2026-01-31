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

package android.webapp.tests.ineligible

import android.app.Activity
import android.content.Intent
import android.content.pm.webapp.WebAppInstallRequest
import android.content.pm.webapp.WebAppManager
import android.os.Bundle
import android.util.Log
import java.util.concurrent.Executors

class IneligibleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action != "android.webapp.tests.ineligible.ACTION_INSTALL") {
            return
        }

        val url = intent.getStringExtra("url") ?: return
        val manager = getSystemService(WebAppManager::class.java)

        Log.i(TAG, "Requesting install for $url")
        val request = WebAppInstallRequest.Builder("Ineligible PWA", url).build()

        manager.install(request, Executors.newSingleThreadExecutor()) { packageName, resultCode ->
            Log.i(TAG, "Install result: $resultCode")
            val resultIntent = Intent("android.webapp.tests.ineligible.ACTION_INSTALL_RESULT")
            resultIntent.setPackage("com.android.webapp.tests")
            resultIntent.putExtra("resultCode", resultCode)
            sendBroadcast(resultIntent)
            finish()
        }
    }

    companion object {
        const val TAG = "IneligibleActivity"
    }
}

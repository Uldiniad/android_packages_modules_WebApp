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

package com.android.webapp.service.view

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Activity that displays an error dialog for failed web app installations. */
class InstallErrorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val errorMessage = intent.getStringExtra(EXTRA_ERROR_MESSAGE) ?: "Unknown error"
        setContent {
            ColorScheme {
                InstallErrorDialog(errorMessage = errorMessage, onDismiss = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_ERROR_MESSAGE = "com.android.webapp.service.extra.ERROR_MESSAGE"

        fun createIntent(context: Context, errorMessage: String): Intent {
            return Intent(context, InstallErrorActivity::class.java).apply {
                putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}

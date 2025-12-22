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

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.webapp.service.R

@Composable
fun InstallConfirmDialog(
    appName: String,
    startUrl: String,
    appIcon: Bitmap,
    invokingAppName: String,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        icon = {
            Image(
                bitmap = appIcon.asImageBitmap(),
                // Add description for accessibility purpose
                contentDescription =
                    stringResource(R.string.install_confirm_icon_description, appName),
                modifier = Modifier.size(64.dp),
            )
        },
        title = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.install_confirm_title, appName),
                    textAlign = TextAlign.Center,
                )
            }
        },
        text = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text =
                        stringResource(R.string.install_confirm_message, invokingAppName, startUrl),
                    textAlign = TextAlign.Start,
                )
            }
        },
        onDismissRequest = onCancel,
        confirmButton = {
            Button(onClick = onInstall) {
                Text(stringResource(R.string.install_confirm_button_install))
            }
        },
        dismissButton = {
            Button(onClick = onCancel) {
                Text(stringResource(R.string.install_confirm_button_cancel))
            }
        },
    )
}

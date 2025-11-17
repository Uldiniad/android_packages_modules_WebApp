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

package com.android.webapp.service.model

import androidx.annotation.Keep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A class that is mapped to a PWA manifest. All fields should be kept optional since PWA manifest
 * format is not strictly applied in browser implementations.
 *
 * PWA specifications: https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/Manifest
 */
@Keep // Prevent this class from being optimized out by R8.
@Serializable
data class PwaManifest(
    val name: String? = null,
    @SerialName("short_name") val shortName: String? = null,
)

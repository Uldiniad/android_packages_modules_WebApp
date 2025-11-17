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

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation

/**
 * A singleton that manages the continuation used to suspend web app installation and make it wait
 * until package manager returns the installation result.
 */
object InstallContinuationManager {
    // The key is the unique session ID provided by the package manager.
    val continuations = ConcurrentHashMap<Int, Continuation<Int>>()
}

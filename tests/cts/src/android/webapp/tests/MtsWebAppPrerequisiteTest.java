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

package android.webapp.tests;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MtsWebAppPrerequisiteTest {

    // TODO: find the correct string for the internal service
    // static final String WebAppInternalServiceName = "com.android.webapp.service.WebAppService";

    @Before
    public void setUp() throws Exception {}

    /** Test that WebApp is installed on the target system; */
    @Test
    public void testWebAppServicesExistence() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Validate services needed for the module to function.
        assertThat(context.getSystemService(Context.WEB_APP_SERVICE)).isNotNull();

        // TODO: also check the internal service
        // assertThat(context.getSystemService(WebAppInternalServiceName)).isNotNull();
    }
}

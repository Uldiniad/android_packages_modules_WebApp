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

import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.pm.webapp.WebAppInstallRequest;
import android.content.pm.webapp.WebAppManager;
import android.util.Log;
import android.webkit.cts.CtsTestServer;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.android.compatibility.common.util.SystemUtil;
import com.android.webapp.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class WebAppInstallTest {

    private static final String MANIFEST_PATH = "manifest.json";

    // Functions rather than constants, so that isEmulator() can be evaluated/cached lazily
    private static long getInitialIdleTimeout() {
        return isEmulator() ? 50000 : 25000;
    }

    private static long getUiTimeout() {
        return isEmulator() ? 20000 : 12000;
    }

    private static long getInstallTimeout() {
        return isEmulator() ? 80000 : 40000;
    }

    private static long getPostInstallIdleTimeout() {
        return isEmulator() ? 30000 : 15000;
    }

    private static long getUiBrowserRenderTimeout() {
        return isEmulator() ? 50000 : 25000;
    }

    private static Boolean sIsEmulator;

    private Context mContext;
    private WebAppManager mWebAppManager;
    private CtsTestServer mTestServer;
    private UiDevice mUiDevice;
    private String mInstalledPackageName;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();

        // Skip test if feature flag is disabled
        assumeTrue(Flags.enableWebAppServiceV2());

        // Use target context for WebAppManager as it is a system service?
        // Actually usually getSystemService works on context from getTargetContext() for system
        // services,
        // but for CtsTestServer we need the context that has assets, which is the test context
        // (getInstrumentation().getContext()).
        // Wait, WebAppManager should be available on target context (the device's system).

        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mWebAppManager = targetContext.getSystemService(WebAppManager.class);

        mTestServer = new CtsTestServer(mContext);
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        prepareDevice();
    }

    private void prepareDevice() throws Exception {
        mUiDevice.wakeUp();
        mUiDevice.executeShellCommand("wm dismiss-keyguard");
        // Wait for the keyguard to disappear
        mUiDevice.wait(
                Until.gone(By.res("com.android.systemui", "keyguard_bottom_area")), getUiTimeout());
        mUiDevice.pressHome();
        mUiDevice.waitForIdle(getInitialIdleTimeout());
    }

    @After
    public void tearDown() throws Exception {
        if (mTestServer != null) {
            mTestServer.shutdown();
        }
        if (mInstalledPackageName != null) {
            mUiDevice.executeShellCommand("pm uninstall " + mInstalledPackageName);
        }
    }

    @Test
    public void testInstallWebApp() throws Exception {
        assertThat(mWebAppManager).isNotNull();

        String manifestUrl = mTestServer.getAssetUrl(MANIFEST_PATH);

        // Debug: Show manifestUrl
        Log.i("WebAppInstallTest", "Manifest URL: " + manifestUrl);
        System.out.println("Manifest URL: " + manifestUrl);
        Log.i("WebAppInstallTest", "Timeout to install: " + getInstallTimeout());
        System.out.println("Timeout to install: " + getInstallTimeout());

        WebAppInstallRequest request =
                new WebAppInstallRequest.Builder("Test PWA", manifestUrl).build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> installedPackage = new AtomicReference<>();
        AtomicInteger installResult = new AtomicInteger(-1);

        // Initiate install
        mWebAppManager.install(
                request,
                mContext.getMainExecutor(),
                (packageName, resultCode) -> {
                    installedPackage.set(packageName);
                    installResult.set(resultCode);
                    latch.countDown();
                });

        // Note: Assuming English device or "Install" string resource.
        UiObject2 installButton =
                mUiDevice.wait(Until.findObject(By.text("Install")), getUiTimeout());
        if (installButton == null) {
            fail("Install button not found in UI dialog");
        }
        installButton.click();

        // Wait for callback
        boolean callbackReceived = latch.await(getInstallTimeout(), TimeUnit.MILLISECONDS);
        assertThat(callbackReceived).isTrue();

        // Verify success
        assertThat(installResult.get()).isEqualTo(WebAppInstallRequest.RESULT_SUCCESS);
        assertThat(installedPackage.get()).isNotNull();
        mInstalledPackageName = installedPackage.get();

        // Wait for the system to quiesce
        mUiDevice.pressHome();
        mUiDevice.waitForIdle(getPostInstallIdleTimeout());

        // Launch the app
        mUiDevice.executeShellCommand(
                "monkey -p " + mInstalledPackageName + " -c android.intent.category.LAUNCHER 1");

        // Verify content
        UiObject2 helloText =
                mUiDevice.wait(
                        Until.findObject(By.text("Hello, I can run!")),
                        getUiBrowserRenderTimeout());
        assertThat(helloText).isNotNull();
    }

    private static boolean isEmulator() {
        if (sIsEmulator != null) {
            return sIsEmulator;
        }
        try {
            sIsEmulator =
                    SystemUtil.runShellCommand("getprop ro.boot.qemu").trim().equals("1")
                            || SystemUtil.runShellCommand("getprop ro.kernel.qemu")
                                    .trim()
                                    .equals("1")
                            || SystemUtil.runShellCommand("getprop ro.hardware.virtual_device")
                                    .trim()
                                    .equals("1");
        } catch (Exception e) {
            Log.e("WebAppInstallTest", "Failed to check if emulator", e);
            sIsEmulator = false;
        }
        return sIsEmulator;
    }
}

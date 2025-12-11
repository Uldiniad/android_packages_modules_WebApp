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

package com.android.webapp.service.minter

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.webapp.WebAppQueryRequest
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ApkKeyStore {

    // Use Android Key Store
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    private val mutex = Mutex()

    private suspend fun getKeyEntry(): KeyStore.PrivateKeyEntry? {
        return mutex.withLock {
            try {
                keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                    ?: run {
                        generateKeyAndCertificate()
                        keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get or create keystore", e)
                null
            }
        }
    }

    /** Generates a new self-signed RSA key pair and saves it in AndroidKeyStore. */
    private fun generateKeyAndCertificate() {
        val generator =
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")

        val now = System.currentTimeMillis()
        val notBefore = Date(now)
        // 50 years validity
        val notAfter = Date(now + 1000L * 60 * 60 * 24 * 365 * 50)

        val parameterSpec =
            KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                .setKeySize(2048)
                .setCertificateSubject(X500Principal("CN=$CERT_COMMON_NAME"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setKeyValidityStart(notBefore)
                .setKeyValidityEnd(notAfter)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build()

        generator.initialize(parameterSpec)
        generator.generateKeyPair() // This generates and saves the key in the hardware/OS

        Log.i(TAG, "Successfully generated new hardware-backed key for signing.")
    }

    suspend fun signApk(workingDir: File, unsignedApkFile: File): File? {
        val privateKeyEntry =
            getKeyEntry()
                ?: run {
                    return null
                }
        return try {
            val signedApk = File(workingDir, "signed.apk")
            val signerConfig =
                ApkSigner.SignerConfig.Builder(
                        KEY_ALIAS,
                        KeyConfig.Jca(privateKeyEntry.privateKey),
                        privateKeyEntry.certificateChain.asList().map { it as X509Certificate },
                    )
                    .build()

            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(unsignedApkFile)
                .setOutputApk(signedApk)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .build()
                .sign()

            signedApk
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign APK", e)
            null
        }
    }

    suspend fun queryPackage(
        packageManager: PackageManager,
        packageName: String,
        callingUid: Int,
    ): Int {
        val expectedEntry =
            getKeyEntry()
                ?: run {
                    Log.e(TAG, "Failed to get expected PrivateKeyEntry from keystore.")
                    return WebAppQueryRequest.RESULT_INTERNAL_ERROR
                }
        val expectedCert =
            expectedEntry.certificateChain.firstOrNull()
                ?: run {
                    Log.e(TAG, "Expected certificate chain is empty.")
                    return WebAppQueryRequest.RESULT_INTERNAL_ERROR
                }

        // Get the info about whether the calling app has QUERY_ALL_PACKAGES permission.
        val callingAppCanQueryAllPackages =
            try {
                packageManager.getPackagesForUid(callingUid).let {
                    it != null &&
                        it.any { callingPackageName ->
                            packageManager.checkPermission(
                                Manifest.permission.QUERY_ALL_PACKAGES,
                                callingPackageName,
                            ) == PackageManager.PERMISSION_GRANTED
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking the permission of calling UID $callingUid", e)
                // It's safe to return RESULT_INTERNAL_ERROR here because we are querying the
                // calling package, not the target package.
                return WebAppQueryRequest.RESULT_INTERNAL_ERROR
            }

        return try {
            // Get the info about whether the target package was installed by the calling app.
            val sourceInfo = packageManager.getInstallSourceInfo(packageName)
            val originatingPackageName =
                sourceInfo.originatingPackageName
                    ?: run {
                        Log.e(TAG, "Failed to get originating package name for $packageName")
                        // Return RESULT_PERMISSION_DENIED here if the calling app cannot query all
                        // packages to avoid leaking installed status of a random package.
                        return if (callingAppCanQueryAllPackages)
                            WebAppQueryRequest.RESULT_INTERNAL_ERROR
                        else WebAppQueryRequest.RESULT_PERMISSION_DENIED
                    }
            val originatingUid = packageManager.getPackageUid(originatingPackageName, 0)
            val isInstalledByCallingApp = originatingUid == callingUid

            // Get the info about whether the target package is signed by us.
            val packageInfo =
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo =
                packageInfo.signingInfo
                    ?: run {
                        Log.e(TAG, "Failed to get singing info for $packageName")
                        // Return RESULT_PERMISSION_DENIED here if the calling app cannot query all
                        // packages to avoid leaking installed status of a random package.
                        return if (callingAppCanQueryAllPackages)
                            WebAppQueryRequest.RESULT_INTERNAL_ERROR
                        else WebAppQueryRequest.RESULT_PERMISSION_DENIED
                    }
            val signers =
                signingInfo.apkContentsSigners
                    ?: signingInfo.signingCertificateHistory
                    ?: run {
                        Log.e(TAG, "Failed to get signers for $packageName")
                        // Return RESULT_PERMISSION_DENIED here if the calling app cannot query all
                        // packages to avoid leaking installed status of a random package.
                        return if (callingAppCanQueryAllPackages)
                            WebAppQueryRequest.RESULT_INTERNAL_ERROR
                        else WebAppQueryRequest.RESULT_PERMISSION_DENIED
                    }
            val isInstalledByWebAppService =
                signers.any { signature ->
                    signature.toByteArray().contentEquals(expectedCert.encoded)
                }

            Log.i(
                TAG,
                """Querying $packageName for UID $callingUid with conditions:
                |callingAppCanQueryAllPackages: $callingAppCanQueryAllPackages
                |isInstalledByWebAppService: $isInstalledByWebAppService
                |isInstalledByCallingApp: $isInstalledByCallingApp"""
                    .trimMargin(),
            )
            // Check permission according to `WebAppQueryRequest` result code definitions.
            if (
                isInstalledByWebAppService &&
                    (callingAppCanQueryAllPackages || isInstalledByCallingApp)
            )
                WebAppQueryRequest.RESULT_INSTALLED
            else if (callingAppCanQueryAllPackages) WebAppQueryRequest.RESULT_NOT_INSTALLED
            else WebAppQueryRequest.RESULT_PERMISSION_DENIED
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Target package $packageName not found or not visible.", e)
            // Return RESULT_PERMISSION_DENIED if calling app doesn't have QUERY_ALL_PACKAGES
            // permission to avoid leaking installed status of a random package.
            if (callingAppCanQueryAllPackages) WebAppQueryRequest.RESULT_NOT_INSTALLED
            else WebAppQueryRequest.RESULT_PERMISSION_DENIED
        } catch (e: Exception) {
            Log.e(TAG, "Error querying package for $packageName", e)
            WebAppQueryRequest.RESULT_INTERNAL_ERROR
        }
    }

    companion object {
        private const val TAG = "ApkKeyStore"
        private const val KEY_ALIAS = "webapp-key"
        private const val CERT_COMMON_NAME = "WebApp-SelfSigned"
    }
}

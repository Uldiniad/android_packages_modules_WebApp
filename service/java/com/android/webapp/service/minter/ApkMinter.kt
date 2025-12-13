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

import android.util.Log
import com.samskivert.mustache.Mustache
import com.samskivert.mustache.MustacheException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkMinter(val keyStore: ApkKeyStore, val workingDir: File, val apkInfo: ApkInfo) {

    suspend fun mintApk(): File? {
        val unpackedTemplateDir =
            unpackTemplate()
                ?: run {
                    return null
                }
        val manifestFile =
            modifyTemplate(unpackedTemplateDir)
                ?: run {
                    return null
                }
        val flatResDir =
            compileResources(unpackedTemplateDir)
                ?: run {
                    return null
                }
        val unsignedApkFile =
            linkApk(unpackedTemplateDir, flatResDir, manifestFile)
                ?: run {
                    return null
                }
        return keyStore.signApk(workingDir, unsignedApkFile)
    }

    private fun unpackTemplate(): File? {
        val outputDir = File(workingDir, "unpacked_template")
        val zipFile = File(TEMPLATE_FILE)

        return try {
            if (!outputDir.mkdirs()) {
                Log.e(TAG, "Failed to create $outputDir")
                return null
            }

            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val dest = File(outputDir, entry.name)
                    if (entry.isDirectory) {
                        if (!dest.exists() && !dest.mkdirs()) {
                            Log.e(TAG, "Failed to create $dest")
                            return null
                        }
                    } else {
                        val parent =
                            dest.parentFile
                                ?: run {
                                    Log.wtf(TAG, "Parent file doesn't exist for $dest")
                                    return null
                                }
                        if (!parent.exists() && !parent.mkdirs()) {
                            Log.e(TAG, "Failed to create ${dest.parentFile}")
                            return null
                        }
                        zip.getInputStream(entry).use { input ->
                            Files.copy(input, dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
            }
            outputDir
        } catch (e: IOException) {
            Log.e(TAG, "Failed to unpack template", e)
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied while unpacking template", e)
            null
        }
    }

    private fun modifyTemplate(unpackedTemplateDir: File): File? {
        val manifestTemplateFile = File(unpackedTemplateDir, "AndroidManifest.xml.mustache")
        val manifestFile = File(unpackedTemplateDir, "AndroidManifest.xml")
        try {
            val template =
                manifestTemplateFile.reader().use { reader -> Mustache.compiler().compile(reader) }
            manifestFile.writeText(template.execute(apkInfo))
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read AndroidManifest.xml", e)
            return null
        } catch (e: MustacheException) {
            Log.e(TAG, "Failed to execute template for AndroidManifest.xml", e)
            return null
        }

        val stableIdsFile = File(unpackedTemplateDir, "res-ids.txt")
        try {
            val resIdsContent =
                stableIdsFile.readText().replace("com.android.webapp.template", apkInfo.packageName)
            stableIdsFile.writeText(resIdsContent)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to modify res-ids.txt", e)
            return null
        }

        val valuesDir = File(unpackedTemplateDir, "res/values")
        if (!valuesDir.exists() && !valuesDir.mkdirs()) {
            Log.e(TAG, "Failed to create $valuesDir")
            return null
        }

        val colorsTemplateFile = File(unpackedTemplateDir, "colors.xml.mustache")
        val colorsFile = File(valuesDir, "colors.xml")
        try {
            val colorTemplate =
                colorsTemplateFile.reader().use { reader -> Mustache.compiler().compile(reader) }
            colorsFile.writeText(colorTemplate.execute(apkInfo))
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read colors.xml", e)
            return null
        } catch (e: MustacheException) {
            Log.e(TAG, "Failed to execute template for colors.xml", e)
            return null
        }

        if (!processIcons(unpackedTemplateDir)) {
            Log.e(TAG, "Failed to process icons")
            return null
        }

        return manifestFile
    }

    private fun processIcons(unpackedTemplateDir: File): Boolean {
        val iconsDir = File(workingDir, "icons")
        var hasMaskableIcon = false
        if (iconsDir.exists()) {
            iconsDir.walkTopDown().forEach { source ->
                if (source.isFile) {
                    if (source.name == "ic_launcher_foreground.png") {
                        hasMaskableIcon = true
                    }
                    val relativePath = source.relativeTo(iconsDir)
                    val dest = File(unpackedTemplateDir, relativePath.path)
                    if (!dest.parentFile.exists()) {
                        dest.parentFile.mkdirs()
                    }
                    try {
                        Files.copy(
                            source.toPath(),
                            dest.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to copy icon resource $source", e)
                        return false
                    }
                }
            }
        }

        // Maskable icons are used for the adaptive app icon.
        if (hasMaskableIcon) {
            val adaptiveIconXml = File(unpackedTemplateDir, "ic_launcher.xml")
            if (adaptiveIconXml.exists()) {
                val destIconXml = File(unpackedTemplateDir, "res/mipmap-anydpi/ic_launcher.xml")
                if (!destIconXml.parentFile.exists()) {
                    destIconXml.parentFile.mkdirs()
                }
                try {
                    Files.copy(
                        adaptiveIconXml.toPath(),
                        destIconXml.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to copy ic_launcher.xml", e)
                    return false
                }
            }
        }
        return true
    }

    private fun compileResources(unpackedTemplateDir: File): File? {
        val inputResDir = File(unpackedTemplateDir, "res")
        val flatResDir = File(workingDir, "flat_res")
        if (!flatResDir.mkdirs()) {
            Log.e(TAG, "Failed to create $flatResDir")
            return null
        }
        val aapt2Args = buildList {
            add(AAPT2_EXEC)
            add("compile")
            add("--dir")
            add(inputResDir.absolutePath)
            add("-o")
            add(flatResDir.absolutePath)
        }

        return try {
            val ret = execAapt2(aapt2Args)
            if (ret != 0) {
                Log.e(TAG, "Return code from aapt2 is not 0: $ret")
                null
            } else {
                flatResDir
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run aapt2 compile", e)
            null
        }
    }

    private fun linkApk(unpackedTemplateDir: File, flatResDir: File, manifestFile: File): File? {
        val classesDexFile = File(unpackedTemplateDir, "classes.dex")
        val stableIdsFile = File(unpackedTemplateDir, "res-ids.txt")
        val frameworkResFile = File("/system/framework/framework-res.apk")
        val linkedResApkFile = File(workingDir, "res-linked.apk")
        val outputApkFile = File(workingDir, "unsigned.apk")

        return try {
            // Link resources
            val flatResFiles =
                flatResDir.listFiles()
                    ?: run {
                        Log.e(TAG, "Failed to list flat res files")
                        return null
                    }
            val aapt2Args = buildList {
                add(AAPT2_EXEC)
                add("link")
                flatResFiles.forEach { add(it.absolutePath) }
                add("-o")
                add(linkedResApkFile.absolutePath)
                add("-I")
                add(frameworkResFile.absolutePath)
                add("--manifest")
                add(manifestFile.absolutePath)
                add("--stable-ids")
                add(stableIdsFile.absolutePath)
            }

            val ret = execAapt2(aapt2Args)
            if (ret != 0) {
                Log.e(TAG, "Return code from aapt2 is not 0: $ret")
                return null
            }

            // Add classes.dex to the output APK
            ZipFile(linkedResApkFile).use { zip ->
                ZipOutputStream(outputApkFile.outputStream()).use { output ->
                    zip.entries().asSequence().forEach { entry ->
                        output.putNextEntry(entry)
                        zip.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                    output.putNextEntry(ZipEntry("classes.dex"))
                    classesDexFile.inputStream().use { it.copyTo(output) }
                    output.closeEntry()
                }
            }

            outputApkFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run link resources", e)
            null
        }
    }

    private fun execAapt2(args: List<String>): Int {
        Log.d(TAG, "Invoking aapt2 with args: ${args.joinToString(" ")}")
        val pb = ProcessBuilder(args)
        val p = pb.start()
        val ret = p.waitFor()

        val stdOutBytes = p.inputStream.readAllBytes()
        if (stdOutBytes.isNotEmpty()) {
            Log.i(TAG, "aapt2 stdout:\n${String(stdOutBytes)}")
        }
        val stdErrBytes = p.errorStream.readAllBytes()
        if (stdErrBytes.isNotEmpty()) {
            Log.e(TAG, "aapt2 stderr:\n${String(stdErrBytes)}")
        }
        return ret
    }

    companion object {
        private const val TAG = "ApkMinter"
        private const val TEMPLATE_FILE = "/apex/com.android.webapp/res/webapp-template.zip"
        private const val AAPT2_EXEC = "/apex/com.android.webapp/bin/aapt2"
    }
}

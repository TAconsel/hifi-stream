package com.hifistream.sender

import android.content.Context
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Turns this app into a privileged system app with a Magisk module:
 *   /system/priv-app/HiFiStream/HiFiStream.apk          (copy of the installed APK)
 *   /system/etc/permissions/privapp-permissions-hifistream.xml
 * The allowlist grants exactly the two privileged permissions the app declares
 * (CAPTURE_AUDIO_OUTPUT, MODIFY_AUDIO_ROUTING); nothing else on the device is
 * changed. A reboot is needed afterwards. Undo: this app's "Remove" button, or
 * `magisk --remove-modules` from adb / Magisk safe mode (hold Volume Down at boot).
 */
object RootInstaller {
    const val MODULE_ID = "hifistream"

    private const val PRIVAPP_XML = """<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="com.hifistream.sender">
        <permission name="android.permission.CAPTURE_AUDIO_OUTPUT"/>
        <permission name="android.permission.MODIFY_AUDIO_ROUTING"/>
    </privapp-permissions>
</permissions>
"""

    /** Standard Magisk module installer stub: hands over to Magisk's own installer. */
    private const val UPDATE_BINARY = """#!/sbin/sh
umask 022
ui_print() { echo "${'$'}1"; }
OUTFD=${'$'}2
ZIPFILE=${'$'}3
mount /data 2>/dev/null
[ -f /data/adb/magisk/util_functions.sh ] || { ui_print "! Please install Magisk v20.4+"; exit 1; }
. /data/adb/magisk/util_functions.sh
install_module
exit 0
"""

    data class Result(val ok: Boolean, val log: String)

    fun hasRoot(): Boolean = runAsRoot("id").log.contains("uid=0")

    fun isModuleInstalled(): Boolean =
        runAsRoot("[ -d /data/adb/modules/$MODULE_ID ] && [ ! -f /data/adb/modules/$MODULE_ID/remove ] && echo yes").log.contains("yes")

    fun install(ctx: Context): Result {
        val apk = File(ctx.applicationInfo.sourceDir)
        val zip = File(ctx.cacheDir, "hifistream-magisk.zip")
        ZipOutputStream(FileOutputStream(zip)).use { z ->
            fun put(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
            val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            put("module.prop", """id=$MODULE_ID
name=HiFi Stream (system app)
version=${version.versionName}
versionCode=${version.longVersionCode}
author=hifistream
description=Installs HiFi Stream as a privileged system app so it can route audio to the PC like an external audio device.
""".toByteArray())
            put("META-INF/com/google/android/update-binary", UPDATE_BINARY.toByteArray())
            put("META-INF/com/google/android/updater-script", "#MAGISK\n".toByteArray())
            put("system/priv-app/HiFiStream/HiFiStream.apk", apk.readBytes())
            put("system/etc/permissions/privapp-permissions-hifistream.xml", PRIVAPP_XML.toByteArray())
        }
        val r = runAsRoot("magisk --install-module '${zip.absolutePath}' 2>&1 && echo INSTALL_OK")
        zip.delete()
        return Result(r.log.contains("INSTALL_OK"), r.log)
    }

    fun uninstall(): Result {
        val r = runAsRoot("touch /data/adb/modules/$MODULE_ID/remove && echo REMOVE_OK")
        return Result(r.log.contains("REMOVE_OK"), r.log)
    }

    fun reboot() {
        runAsRoot("svc power reboot || reboot")
    }

    fun runAsRoot(cmd: String): Result {
        return try {
            val p = Runtime.getRuntime().exec("su")
            DataOutputStream(p.outputStream).use { it.writeBytes("$cmd\nexit\n"); it.flush() }
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText() +
                BufferedReader(InputStreamReader(p.errorStream)).readText()
            val code = p.waitFor()
            Result(code == 0, out.trim())
        } catch (e: Exception) {
            Result(false, "su failed: ${e.message}")
        }
    }
}

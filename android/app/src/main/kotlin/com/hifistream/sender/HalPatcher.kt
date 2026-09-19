package com.hifistream.sender

import java.io.File

/**
 * Optional, user-initiated patch of AOSP's remote-submix audio HAL
 * (audio.r_submix.default.so) so that the loop-back pipe carries 32-bit float
 * at the requested sample rate instead of being forced to 16-bit / 48 kHz.
 *
 * In adev_open_output_stream() and adev_open_input_stream() the stock library
 * "sanitises" the requested config:
 *     config->sample_rate = supported ? rate : 48000;
 *     config->format      = AUDIO_FORMAT_PCM_16_BIT;   // = 1
 * The pipe itself (libnbaio MonoPipe, Format_from_SR_C) is format agnostic, so
 * the patch only changes the constant 1 to 5 (AUDIO_FORMAT_PCM_FLOAT) at both
 * sites and turns the 48000 fall-backs into NOPs. Both the Thumb-2 (32-bit) and
 * AArch64 builds are recognised by instruction pattern, and the patch refuses to
 * run unless exactly two sites are found.
 *
 * The patched copy is placed in the Magisk module (under system/vendor/lib.../hw), so
 * the real /vendor is never written and "Remove system app" restores everything.
 */
object HalPatcher {
    private const val LIB32 = "/vendor/lib/hw/audio.r_submix.default.so"
    private const val LIB64 = "/vendor/lib64/hw/audio.r_submix.default.so"
    private const val MODULE = "/data/adb/modules/${RootInstaller.MODULE_ID}"

    data class Result(val ok: Boolean, val message: String)

    fun isInstalled(): Boolean =
        RootInstaller.runAsRoot("[ -f $MODULE/system$LIB32 ] || [ -f $MODULE/system$LIB64 ] && echo yes").log.contains("yes")

    /** Patches whichever of the two libraries exist. */
    fun install(cacheDir: File): Result {
        if (!RootInstaller.isModuleInstalled())
            return Result(false, "Install the system app module first")
        val log = StringBuilder()
        var patchedAny = false
        for (lib in listOf(LIB32, LIB64)) {
            val tmp = File(cacheDir, "rsubmix_" + File(lib).parentFile!!.parentFile!!.name)
            val cp = RootInstaller.runAsRoot("[ -f $lib ] && cp $lib ${tmp.absolutePath} && chmod 644 ${tmp.absolutePath} && echo COPIED")
            if (!cp.log.contains("COPIED")) { log.append("$lib: not present\n"); continue }
            val data = tmp.readBytes()
            val r = try {
                if (data.size > 4 && data[4] == 2.toByte()) patchAArch64(data) else patchThumb(data)
            } catch (e: Exception) {
                tmp.delete()
                return Result(false, "$lib: ${e.message}")
            }
            tmp.writeBytes(data)
            val dest = "$MODULE/system$lib"
            val inst = RootInstaller.runAsRoot(
                "mkdir -p $(dirname $dest) && cp ${tmp.absolutePath} $dest && chmod 644 $dest && chown root:root $dest && echo INSTALLED")
            tmp.delete()
            if (!inst.log.contains("INSTALLED")) return Result(false, "$lib: could not write into the module: ${inst.log}")
            log.append("$lib: $r\n")
            patchedAny = true
        }
        return Result(patchedAny, log.toString().trim())
    }

    fun uninstall(): Result {
        val r = RootInstaller.runAsRoot("rm -rf $MODULE/system/vendor && echo REMOVED")
        return Result(r.log.contains("REMOVED"), if (r.log.contains("REMOVED")) "Stock HAL restored after reboot" else r.log)
    }

    // ---- Thumb-2 (32-bit HAL service) --------------------------------------------

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun patchThumb(b: ByteArray): String {
        // "movs r1, #1" immediately followed by "str r1, [rN, #8]" (16- or 32-bit encoding)
        val sites = ArrayList<Int>()
        var i = 0
        while (i + 6 <= b.size) {
            if (u16(b, i) == 0x2101) {
                val n1 = u16(b, i + 2)
                val isStr16 = (n1 and 0xFFC7) == 0x6081
                val isStr32 = (n1 and 0xFFF0) == 0xF8C0 && u16(b, i + 4) == 0x1008
                if ((isStr16 || isStr32) && hasWithin(b, i - 0x100, i, MOVW_R0_48000)) sites.add(i)
            }
            i += 2
        }
        if (sites.size != 2) throw IllegalStateException("expected 2 sanitise sites, found ${sites.size} — unknown HAL build, not patched")
        var nops = 0
        for (s in sites) {
            b[s] = 0x05                                   // movs r1, #5  (PCM_FLOAT)
            var p = maxOf(0, s - 0x100)
            while (p + 4 <= s) {
                if (matches(b, p, MOVW_R0_48000)) {
                    NOP_W.copyInto(b, p)                  // keep the requested sample rate
                    if (p >= 2 && u16(b, p - 2) == 0xBF18) { b[p - 2] = 0x00; b[p - 1] = 0xBF.toByte() }  // it ne -> nop
                    nops++
                }
                p += 2
            }
        }
        return "Thumb-2: 2 format sites, $nops rate fall-backs patched"
    }

    private val MOVW_R0_48000 = byteArrayOf(0x4B, 0xF6.toByte(), 0x80.toByte(), 0x30)
    private val NOP_W = byteArrayOf(0xAF.toByte(), 0xF3.toByte(), 0x00, 0x80.toByte())

    // ---- AArch64 (64-bit HAL service) ----------------------------------------------

    private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

    private fun patchAArch64(b: ByteArray): String {
        val sites = ArrayList<Int>()
        var i = 0
        while (i + 16 <= b.size) {
            if (u32(b, i) == 0x52800028L) {               // mov w8, #1
                var strFound = false
                for (k in 1..4) if ((u32(b, i + 4 * k) and 0xFFFFFC1FL) == 0xB9000A08L) strFound = true  // str w8,[xN,#8]
                if (strFound && hasWithin(b, i - 0x100, i, MOV_W8_48000)) sites.add(i)
            }
            i += 4
        }
        if (sites.size != 2) throw IllegalStateException("expected 2 sanitise sites, found ${sites.size} — unknown HAL build, not patched")
        var nops = 0
        for (s in sites) {
            b[s] = 0xA8.toByte()                           // mov w8, #5
            var p = maxOf(0, s - 0x100)
            while (p + 4 <= s) {
                if (matches(b, p, MOV_W8_48000)) { NOP_A64.copyInto(b, p); nops++ }
                p += 4
            }
        }
        return "AArch64: 2 format sites, $nops rate fall-backs patched"
    }

    private val MOV_W8_48000 = byteArrayOf(0x08, 0x70, 0x97.toByte(), 0x52)
    private val NOP_A64 = byteArrayOf(0x1F, 0x20, 0x03, 0xD5.toByte())

    private fun matches(b: ByteArray, o: Int, pat: ByteArray): Boolean {
        if (o < 0 || o + pat.size > b.size) return false
        for (j in pat.indices) if (b[o + j] != pat[j]) return false
        return true
    }

    private fun hasWithin(b: ByteArray, from: Int, to: Int, pat: ByteArray): Boolean {
        var p = maxOf(0, from)
        while (p + pat.size <= to) { if (matches(b, p, pat)) return true; p += 2 }
        return false
    }
}

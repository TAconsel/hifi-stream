package com.hifistream.sender

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Looper
import android.util.Log

/**
 * System-level capture for a privileged (rooted / Magisk-installed) build.
 *
 * Uses the @SystemApi AudioPolicy / AudioMix API through reflection: a mix rule
 * matching media, game and unknown usages with ROUTE_FLAG_LOOP_BACK routes those
 * players *exclusively* into a remote-submix pipe that we read back with an
 * AudioRecord. The phone itself goes silent, exactly as if an external audio
 * device were connected, and no screen-share consent is needed.
 *
 * Needs CAPTURE_AUDIO_OUTPUT and MODIFY_AUDIO_ROUTING, which Android grants
 * only to privileged system apps (see RootInstaller), and the manifest's
 * usesNonSdkApi flag so a system app may call these @SystemApi classes.
 */
object SystemCapture {
    private const val TAG = "HiFiStream"
    private const val RULE_MATCH_ATTRIBUTE_USAGE = 0x1
    private const val ROUTE_FLAG_LOOP_BACK = 0x2

    const val PERM_CAPTURE = "android.permission.CAPTURE_AUDIO_OUTPUT"
    const val PERM_ROUTING = "android.permission.MODIFY_AUDIO_ROUTING"

    private val policyClass: Class<*> by lazy { Class.forName("android.media.audiopolicy.AudioPolicy") }

    class Session(val record: AudioRecord, private val am: AudioManager, private val policy: Any) {
        fun close() {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            try {
                am.javaClass.getMethod("unregisterAudioPolicyAsync", policyClass).invoke(am, policy)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterAudioPolicy failed: $e")
            }
        }
    }

    /** True when the app is installed as a privileged system app. */
    fun isPrivileged(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERM_CAPTURE) == PackageManager.PERMISSION_GRANTED &&
        ctx.checkSelfPermission(PERM_ROUTING) == PackageManager.PERMISSION_GRANTED &&
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Registers the loop-back policy and returns a recording sink in the given
     * format. Throws with a readable message on failure.
     */
    fun open(ctx: Context, rate: Int, encoding: Int): Session {
        val am = ctx.getSystemService(AudioManager::class.java)

        val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
        val addMixRule = ruleBuilderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
        for (usage in intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)) {
            addMixRule.invoke(ruleBuilder, RULE_MATCH_ATTRIBUTE_USAGE, AudioAttributes.Builder().setUsage(usage).build())
        }
        val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val mixBuilder = mixBuilderClass.getConstructor(rule.javaClass).newInstance(rule)
        mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK)
        val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

        val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(ctx)
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
        policyBuilderClass.getMethod("setLooper", Looper::class.java).invoke(policyBuilder, Looper.getMainLooper())
        val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)

        val status = am.javaClass.getMethod("registerAudioPolicy", policyClass).invoke(am, policy) as Int
        if (status != 0) throw IllegalStateException("registerAudioPolicy failed ($status) — is the app a privileged system app?")

        val record = try {
            policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix) as AudioRecord?
        } catch (e: Exception) {
            am.javaClass.getMethod("unregisterAudioPolicyAsync", policyClass).invoke(am, policy)
            throw IllegalStateException("createAudioRecordSink failed: ${e.cause?.message ?: e.message}")
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            am.javaClass.getMethod("unregisterAudioPolicyAsync", policyClass).invoke(am, policy)
            throw IllegalStateException("Could not open the loop-back recorder at $rate Hz " +
                "(the remote submix HAL may not support this format)")
        }
        Log.i(TAG, "system capture: ${record.sampleRate} Hz, encoding ${record.audioFormat}")
        return Session(record, am, policy)
    }
}

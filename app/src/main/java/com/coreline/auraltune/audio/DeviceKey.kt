// DeviceKey.kt
// Phase 5 device-key policy. Stable, privacy-respecting identifier for an
// audio output device. Used as the lookup key for per-device AutoEQ selections
// in SettingsStore.
package com.coreline.auraltune.audio

import android.media.AudioDeviceInfo
import android.os.Build
import java.security.MessageDigest

/**
 * Stable identifier for an audio output device.
 *
 * Composition rules (priority order, dev-plan Phase 5):
 *   1. USB headset/device → `usb|<productName>|<vendorId>|<productId>`
 *   2. BT (A2DP / BLE / SCO) with stable address → `bt|<productName>|<address>`
 *   3. BT without address (random RPA, permission denied) → `bt|<productName>`
 *   4. Wired headphones/headset → `wired|<productName>` (productName usually "headphones")
 *   5. Anything else (speaker, HDMI, line-digital) → returns null (AutoEQ N/A)
 *
 * The raw fields are NEVER logged or transmitted — see [stableHash] for a
 * redacted form suitable for analytics / Crashlytics.
 */
data class DeviceKey(
    val raw: String,
    val supportsAutoEq: Boolean,
    val displayName: String,
) {
    /** SHA-256 first 12 hex chars — telemetry-safe one-way identifier. */
    fun stableHash(): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(24)
        for (i in 0 until 6) {
            sb.append("%02x".format(bytes[i]))
        }
        return sb.toString()
    }

    companion object {
        /**
         * Build a [DeviceKey] from an [AudioDeviceInfo]. Returns null when the
         * device is not a headphone-class output (speaker, HDMI, etc.) — AutoEQ
         * should not be applied for those.
         *
         * @param btAddressAvailable Whether the caller has BLUETOOTH_CONNECT
         *        permission. When false, BT keys fall back to product-name-only.
         */
        fun fromAudioDevice(
            info: AudioDeviceInfo,
            btAddressAvailable: Boolean,
        ): DeviceKey? {
            val productName = info.productName.toString().ifBlank { "unknown" }
            return when (info.type) {
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> {
                    // USB doesn't gate behind BLUETOOTH_CONNECT, but defensively
                    // wrap the access — some OEM HALs throw on `info.address`.
                    val addr = safeAddress(info, allowed = true) ?: ""
                    DeviceKey(
                        raw = "usb|$productName|$addr",
                        supportsAutoEq = true,
                        displayName = productName,
                    )
                }
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                    // P1-5: BLUETOOTH_CONNECT may not be granted on Android 12+.
                    // Read address through safeAddress() which short-circuits when
                    // the permission is missing AND wraps the access in runCatching
                    // so OEM HAL throws don't crash the audio device callback.
                    val addr = safeAddress(info, allowed = btAddressAvailable)
                    DeviceKey(
                        raw = if (addr != null) "bt|$productName|$addr" else "bt|$productName",
                        supportsAutoEq = true,
                        displayName = productName,
                    )
                }
                AudioDeviceInfo.TYPE_BLE_HEADSET -> if (Build.VERSION.SDK_INT >= 31) {
                    val addr = safeAddress(info, allowed = btAddressAvailable)
                    DeviceKey(
                        raw = if (addr != null) "ble|$productName|$addr" else "ble|$productName",
                        supportsAutoEq = true,
                        displayName = productName,
                    )
                } else null
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                    DeviceKey(
                        raw = "wired|$productName",
                        supportsAutoEq = true,
                        displayName = productName,
                    )
                }
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC,
                AudioDeviceInfo.TYPE_HDMI_EARC,
                AudioDeviceInfo.TYPE_LINE_DIGITAL,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_TELEPHONY -> {
                    // AutoEQ is N/A — speakers/HDMI/telephony shouldn't get headphone correction.
                    DeviceKey(
                        raw = "speaker|$productName|${info.type}",
                        supportsAutoEq = false,
                        displayName = productName,
                    )
                }
                else -> null
            }
        }

        /**
         * P1-5 + lint NewApi fix: defensive accessor for
         * [AudioDeviceInfo.address]. Returns null when:
         *   - the running OS is below API 28 (the public getter was added in
         *     Android 9 — pre-9 we silently fall back to product-name keys),
         *   - permission is not allowed (BLUETOOTH_CONNECT denied on Android 12+),
         *   - the OEM HAL throws on access (rare, but observed on some BT stacks),
         *   - the value is blank.
         */
        @androidx.annotation.RequiresApi(Build.VERSION_CODES.P) // dead branch on <28
        private fun addressOnPie(info: AudioDeviceInfo): String? =
            runCatching { info.address }.getOrNull()?.takeIf { it.isNotBlank() }

        private fun safeAddress(info: AudioDeviceInfo, allowed: Boolean): String? {
            if (!allowed) return null
            // AudioDeviceInfo.getAddress() is API 28+. minSdk is 26 (Android 8),
            // so we MUST gate this call. On older devices, address keys collapse
            // to product-name only — same fallback as when the BT permission
            // is denied.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            return addressOnPie(info)
        }
    }
}

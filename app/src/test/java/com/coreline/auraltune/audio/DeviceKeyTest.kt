package com.coreline.auraltune.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceKeyTest {
    @Test
    fun `headphone class routes map to a device key`() {
        val headphoneTypes = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )

        headphoneTypes.forEach { type ->
            val key = DeviceKey.fromAudioDevice(device(type), btAddressAvailable = false)
            assertNotNull("type $type should produce a key", key)
        }
    }

    @Test
    fun `non headphone output routes still map to a key (correction applies on every route)`() {
        // Route type is NOT a gate — speaker / HDMI / line / telephony still get a
        // stable key so the active profile keeps being applied (no route clearing).
        val nonHeadphoneTypes = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_TELEPHONY,
        )

        nonHeadphoneTypes.forEach { type ->
            val key = DeviceKey.fromAudioDevice(device(type), btAddressAvailable = true)
            assertNotNull("type $type should produce a key", key)
            assertTrue(key.raw.startsWith("non_headphone|"))
        }
    }

    @Test
    fun `unknown routes still map to a generic key`() {
        val key = DeviceKey.fromAudioDevice(
            device(AudioDeviceInfo.TYPE_BUILTIN_MIC),
            btAddressAvailable = true,
        )

        assertNotNull(key)
        assertTrue(key.raw.startsWith("other|"))
    }

    @Test
    fun `bluetooth without address permission falls back to name based key`() {
        val key = DeviceKey.fromAudioDevice(
            device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            btAddressAvailable = false,
        )

        assertEquals("bt|robolectric", key.raw)
    }

    private fun device(type: Int): AudioDeviceInfo =
        AudioDeviceInfoBuilder.newBuilder()
            .setType(type)
            .build()
}

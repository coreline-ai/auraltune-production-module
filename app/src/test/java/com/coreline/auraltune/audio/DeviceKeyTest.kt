package com.coreline.auraltune.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `headphone class routes support AutoEQ`() {
        val eligibleTypes = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )

        eligibleTypes.forEach { type ->
            val key = DeviceKey.fromAudioDevice(device(type), btAddressAvailable = false)

            assertNotNull("type $type should produce a key", key)
            assertTrue("type $type should support AutoEQ", key!!.supportsAutoEq)
        }
    }

    @Test
    fun `non headphone output routes force AutoEQ clear`() {
        val ineligibleTypes = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_TELEPHONY,
        )

        ineligibleTypes.forEach { type ->
            val key = DeviceKey.fromAudioDevice(device(type), btAddressAvailable = true)

            assertNotNull("type $type should produce a clear-sentinel key", key)
            assertFalse("type $type should not support AutoEQ", key!!.supportsAutoEq)
            assertTrue(key.raw.startsWith("non_headphone|"))
        }
    }

    @Test
    fun `unknown routes do not create a device key`() {
        val key = DeviceKey.fromAudioDevice(
            device(AudioDeviceInfo.TYPE_BUILTIN_MIC),
            btAddressAvailable = true,
        )

        assertNull(key)
    }

    @Test
    fun `bluetooth without address permission falls back to name based key`() {
        val key = DeviceKey.fromAudioDevice(
            device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            btAddressAvailable = false,
        )

        assertNotNull(key)
        assertEquals("bt|robolectric", key!!.raw)
        assertTrue(key.supportsAutoEq)
    }

    private fun device(type: Int): AudioDeviceInfo =
        AudioDeviceInfoBuilder.newBuilder()
            .setType(type)
            .build()
}

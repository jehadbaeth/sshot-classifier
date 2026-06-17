package com.okapiorbits.sshotclassifier.pipeline.vlm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityTest {

    private val arm64 = listOf("arm64-v8a", "armeabi-v7a")
    private val gb8 = 7_600_000_000L // ~8 GB device's reported totalMem
    private val gb6 = 5_900_000_000L // ~6 GB device's reported totalMem

    @Test
    fun highEndArm64Device_isCapable() {
        val r = DeviceCapability.assess(gb8, arm64, isLowRam = false, isEmulator = false)
        assertTrue(r.isCapable)
        assertEquals(DeviceCapability.Result.Capable, r)
    }

    @Test
    fun sixGbDevice_isNotCapable() {
        // A 6 GB device must be excluded: the model alone peaks at ~5.9 GB.
        val r = DeviceCapability.assess(gb6, arm64, isLowRam = false, isEmulator = false)
        assertFalse(r.isCapable)
        assertTrue((r as DeviceCapability.Result.NotCapable).reason.contains("8 GB"))
    }

    @Test
    fun emulator_isNotCapable_evenWithLotsOfRam() {
        val r = DeviceCapability.assess(16_000_000_000L, arm64, isLowRam = false, isEmulator = true)
        assertFalse(r.isCapable)
        assertTrue((r as DeviceCapability.Result.NotCapable).reason.contains("emulator", ignoreCase = true))
    }

    @Test
    fun non_arm64_isNotCapable() {
        val r = DeviceCapability.assess(gb8, listOf("armeabi-v7a"), isLowRam = false, isEmulator = false)
        assertFalse(r.isCapable)
        assertTrue((r as DeviceCapability.Result.NotCapable).reason.contains("arm64"))
    }

    @Test
    fun lowRamDevice_isNotCapable() {
        val r = DeviceCapability.assess(gb8, arm64, isLowRam = true, isEmulator = false)
        assertFalse(r.isCapable)
        assertTrue((r as DeviceCapability.Result.NotCapable).reason.contains("low-RAM"))
    }

    @Test
    fun ramThresholdBoundary() {
        assertFalse(DeviceCapability.assess(DeviceCapability.MIN_TOTAL_RAM_BYTES - 1, arm64, false, false).isCapable)
        assertTrue(DeviceCapability.assess(DeviceCapability.MIN_TOTAL_RAM_BYTES, arm64, false, false).isCapable)
    }
}

package com.okapiorbits.sshotclassifier.pipeline.vlm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether a device can run the experimental on-device VLM caption flow (Gemma 3n E2B
 * via MediaPipe; see docs/spikes/vlm-device-research.md). The model peaks at ~5.9 GB and the
 * runtime is documented as "optimized for high-end Android devices (Pixel 8 / Samsung S23 or
 * later)" and not reliable on emulators, so this gate is intentionally strict: a non-qualifying
 * device keeps the offline structured describer and the Generative option stays disabled.
 *
 * The decision is a pure function ([assess]) so it is unit-tested with injected values;
 * [DeviceCapabilityChecker] is the thin wrapper that reads the real device.
 */
object DeviceCapability {

    /**
     * Total-RAM floor (~7 GB). `ActivityManager.totalMem` reports below the nominal spec, so an
     * "8 GB" device (Pixel 8 / S23 class) reads ~7.3–7.9 GB and passes, while a 6 GB device reads
     * ~5.5–5.9 GB and is excluded — which it must be, since the model alone peaks at ~5.9 GB and
     * leaves no headroom for the OS.
     */
    const val MIN_TOTAL_RAM_BYTES = 7_000_000_000L

    sealed interface Result {
        data object Capable : Result
        /** Why the device can't run it, shown in Settings. */
        data class NotCapable(val reason: String) : Result

        val isCapable: Boolean get() = this is Capable
    }

    fun assess(
        totalRamBytes: Long,
        abis: List<String>,
        isLowRam: Boolean,
        isEmulator: Boolean,
    ): Result = when {
        isEmulator -> Result.NotCapable("Not supported on emulators")
        "arm64-v8a" !in abis -> Result.NotCapable("Needs a 64-bit (arm64) device")
        isLowRam -> Result.NotCapable("Device is marked low-RAM")
        totalRamBytes < MIN_TOTAL_RAM_BYTES -> Result.NotCapable("Needs a high-end device (~8 GB RAM)")
        else -> Result.Capable
    }
}

/** Reads the real device into a [DeviceCapability.Result]. */
@Singleton
class DeviceCapabilityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun assess(): DeviceCapability.Result {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return DeviceCapability.Result.NotCapable("Cannot read device memory")
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return DeviceCapability.assess(
            totalRamBytes = mem.totalMem,
            abis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            isLowRam = am.isLowRamDevice,
            isEmulator = isEmulator(),
        )
    }

    /** Best-effort emulator detection (the VLM runtime is not reliable on emulators). */
    private fun isEmulator(): Boolean {
        val fp = Build.FINGERPRINT.orEmpty()
        val model = Build.MODEL.orEmpty()
        val hw = Build.HARDWARE.orEmpty()
        return fp.contains("generic") || fp.startsWith("unknown") ||
            model.contains("Emulator") || model.contains("Android SDK built for") ||
            Build.PRODUCT.orEmpty().contains("sdk") || hw.contains("goldfish") || hw.contains("ranchu")
    }
}

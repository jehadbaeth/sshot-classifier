package com.okapiorbits.sshotclassifier.pipeline.vlm

import com.okapiorbits.sshotclassifier.data.prefs.DescriptionSource
import com.okapiorbits.sshotclassifier.pipeline.vlm.CaptureDescriberRouter.Companion.shouldUseGenerative
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generative path is attempted only when opted in, a model is present, there is an image,
 * and the device qualifies — or Developer mode force-allows an under-spec device.
 */
class CaptureDescriberRouterTest {

    private fun gen(
        source: DescriptionSource = DescriptionSource.GENERATIVE,
        hasImage: Boolean = true,
        deviceCapable: Boolean = true,
        devModeForced: Boolean = false,
        modelInstalled: Boolean = true,
    ) = shouldUseGenerative(source, hasImage, deviceCapable, devModeForced, modelInstalled)

    @Test
    fun capable_device_opted_in_with_model_uses_generative() {
        assertTrue(gen())
    }

    @Test
    fun structured_source_never_generative() {
        assertFalse(gen(source = DescriptionSource.STRUCTURED))
    }

    @Test
    fun no_image_never_generative() {
        assertFalse(gen(hasImage = false))
    }

    @Test
    fun missing_model_never_generative_even_when_forced() {
        assertFalse(gen(deviceCapable = false, devModeForced = true, modelInstalled = false))
    }

    @Test
    fun incapable_device_blocked_without_dev_mode() {
        assertFalse(gen(deviceCapable = false, devModeForced = false))
    }

    @Test
    fun incapable_device_allowed_when_dev_mode_forces_it() {
        assertTrue(gen(deviceCapable = false, devModeForced = true))
    }
}

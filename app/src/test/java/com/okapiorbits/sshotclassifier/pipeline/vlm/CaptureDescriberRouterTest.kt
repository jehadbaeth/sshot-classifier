package com.okapiorbits.sshotclassifier.pipeline.vlm

import com.okapiorbits.sshotclassifier.data.prefs.DescriptionSource
import com.okapiorbits.sshotclassifier.pipeline.vlm.CaptureDescriberRouter.Companion.shouldUseGenerative
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generative path is attempted only when every gate passes. */
class CaptureDescriberRouterTest {

    @Test
    fun all_conditions_met_uses_generative() {
        assertTrue(
            shouldUseGenerative(
                source = DescriptionSource.GENERATIVE,
                hasImage = true,
                deviceCapable = true,
                modelInstalled = true,
            )
        )
    }

    @Test
    fun structured_source_never_generative() {
        assertFalse(
            shouldUseGenerative(
                source = DescriptionSource.STRUCTURED,
                hasImage = true,
                deviceCapable = true,
                modelInstalled = true,
            )
        )
    }

    @Test
    fun no_image_never_generative() {
        assertFalse(
            shouldUseGenerative(
                source = DescriptionSource.GENERATIVE,
                hasImage = false,
                deviceCapable = true,
                modelInstalled = true,
            )
        )
    }

    @Test
    fun incapable_device_never_generative() {
        assertFalse(
            shouldUseGenerative(
                source = DescriptionSource.GENERATIVE,
                hasImage = true,
                deviceCapable = false,
                modelInstalled = true,
            )
        )
    }

    @Test
    fun missing_model_never_generative() {
        assertFalse(
            shouldUseGenerative(
                source = DescriptionSource.GENERATIVE,
                hasImage = true,
                deviceCapable = true,
                modelInstalled = false,
            )
        )
    }
}

package com.okapiorbits.sshotclassifier.pipeline.vlm

import com.okapiorbits.sshotclassifier.data.prefs.DescriptionSource
import com.okapiorbits.sshotclassifier.pipeline.CaptureContext
import com.okapiorbits.sshotclassifier.pipeline.StructuredCaptureDescriber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the describer for a capture: the experimental generative VLM only when the user
 * opted into it AND the device qualifies AND a model is imported AND there is an image;
 * otherwise the deterministic structured describer. The generative path also falls back to
 * structured on any runtime failure (it returns null), so the structured describer is the
 * guaranteed floor — a capture always gets a description.
 *
 * The gating decision is the pure [shouldUseGenerative] so it is unit-tested without a
 * device or the multi-GB model.
 */
@Singleton
class CaptureDescriberRouter @Inject constructor(
    private val structured: StructuredCaptureDescriber,
    private val generative: GenerativeCaptureDescriber,
    private val deviceCapabilityChecker: DeviceCapabilityChecker,
    private val modelManager: VlmModelManager,
) {
    suspend fun describe(ctx: CaptureContext, source: DescriptionSource): String {
        val canGenerate = shouldUseGenerative(
            source = source,
            hasImage = ctx.imageUri != null,
            deviceCapable = deviceCapabilityChecker.assess().isCapable,
            modelInstalled = modelManager.isInstalled(),
        )
        if (canGenerate) {
            generative.generate(ctx)?.let { return it }
        }
        return structured.describe(ctx)
    }

    companion object {
        /** All conditions must hold for the experimental generative path to even be attempted. */
        fun shouldUseGenerative(
            source: DescriptionSource,
            hasImage: Boolean,
            deviceCapable: Boolean,
            modelInstalled: Boolean,
        ): Boolean = source == DescriptionSource.GENERATIVE &&
            hasImage && deviceCapable && modelInstalled
    }
}

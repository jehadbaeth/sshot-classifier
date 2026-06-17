package com.okapiorbits.sshotclassifier.pipeline.vlm

import com.okapiorbits.sshotclassifier.data.prefs.CapturePreferencesStore
import com.okapiorbits.sshotclassifier.data.prefs.DescriptionSource
import com.okapiorbits.sshotclassifier.pipeline.CaptureContext
import com.okapiorbits.sshotclassifier.pipeline.CaptureDescriber
import com.okapiorbits.sshotclassifier.pipeline.StructuredCaptureDescriber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [CaptureDescriber] bound into the pipeline. Picks the experimental generative VLM only
 * when the user opted into it AND the device qualifies AND a model is imported AND there is an
 * image; otherwise the deterministic structured describer. The generative path also falls back
 * to structured on any runtime failure (it returns null), so structured is the guaranteed floor
 * — a capture always gets a description.
 *
 * The preference is read here rather than threaded through the pipeline because only camera
 * captures are described (the screenshot throughput path never calls [describe]), so this stays
 * off the hot path. The gate is the pure, unit-tested [shouldUseGenerative].
 */
@Singleton
class CaptureDescriberRouter @Inject constructor(
    private val structured: StructuredCaptureDescriber,
    private val generative: GenerativeCaptureDescriber,
    private val deviceCapabilityChecker: DeviceCapabilityChecker,
    private val modelManager: VlmModelManager,
    private val capturePrefsStore: CapturePreferencesStore,
) : CaptureDescriber {

    override suspend fun describe(ctx: CaptureContext): String {
        val canGenerate = shouldUseGenerative(
            source = capturePrefsStore.current().descriptionSource,
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

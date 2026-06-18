package com.okapiorbits.sshotclassifier.pipeline.vlm

import com.okapiorbits.sshotclassifier.data.prefs.CapturePreferencesStore
import com.okapiorbits.sshotclassifier.data.prefs.DescriptionSource
import com.okapiorbits.sshotclassifier.data.prefs.UiPreferencesStore
import com.okapiorbits.sshotclassifier.pipeline.CaptureContext
import com.okapiorbits.sshotclassifier.pipeline.CaptureDescriber
import com.okapiorbits.sshotclassifier.pipeline.StructuredCaptureDescriber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [CaptureDescriber] bound into the pipeline. Picks the experimental generative VLM only
 * when the user opted into it AND a model is imported AND there is an image AND the device either
 * qualifies or the user force-allowed it via Developer mode; otherwise the deterministic
 * structured describer. The generative path also falls back to structured on any runtime failure
 * (it returns null), so structured is the guaranteed floor — a capture always gets a description.
 *
 * Preferences are read here rather than threaded through the pipeline because only camera captures
 * are described (the screenshot throughput path never calls [describe]), so this stays off the hot
 * path. The gate is the pure, unit-tested [shouldUseGenerative].
 */
@Singleton
class CaptureDescriberRouter @Inject constructor(
    private val structured: StructuredCaptureDescriber,
    private val generative: GenerativeCaptureDescriber,
    private val deviceCapabilityChecker: DeviceCapabilityChecker,
    private val modelManager: VlmModelManager,
    private val capturePrefsStore: CapturePreferencesStore,
    private val uiPrefsStore: UiPreferencesStore,
) : CaptureDescriber {

    override suspend fun describe(ctx: CaptureContext): String {
        val canGenerate = shouldUseGenerative(
            source = capturePrefsStore.current().descriptionSource,
            hasImage = ctx.imageUri != null,
            deviceCapable = deviceCapabilityChecker.assess().isCapable,
            devModeForced = uiPrefsStore.devModeNow(),
            modelInstalled = modelManager.isInstalled(),
        )
        if (canGenerate) {
            generative.generate(ctx)?.let { return it }
        }
        return structured.describe(ctx)
    }

    companion object {
        /**
         * The generative path is attempted only when opted in, a model is present, there is an
         * image, and the device qualifies — OR Developer mode has force-allowed an under-spec
         * device. Even when forced, generation falls back to structured on failure.
         */
        fun shouldUseGenerative(
            source: DescriptionSource,
            hasImage: Boolean,
            deviceCapable: Boolean,
            devModeForced: Boolean,
            modelInstalled: Boolean,
        ): Boolean = source == DescriptionSource.GENERATIVE &&
            hasImage && modelInstalled && (deviceCapable || devModeForced)
    }
}

package com.okapiorbits.sshotclassifier.data.prefs

/** When a QR link is resolved into a preview, if at all. */
enum class ResolveTrigger {
    /** Only when the user taps "Resolve link" on a capture. */
    MANUAL,

    /** Automatically while a capture is processed (still gated by [CapturePreferences.resolveQrLinks]). */
    AUTOMATIC,
}

/** Source of a capture's description. */
enum class DescriptionSource {
    /** Deterministic text composed on-device from OCR + tags + QR payload. Offline. */
    STRUCTURED,

    /** A generative vision-language model. Requires a model that is not bundled yet (gated). */
    GENERATIVE,
}

/**
 * User-controllable behavior for the camera-capture feature. Every field is a stored
 * preference (see [CapturePreferencesStore]); the defaults are offline and private, so
 * nothing reaches the network unless the user opts in.
 */
data class CapturePreferences(
    /** Master switch for QR link resolution. Off by default: no network use at all. */
    val resolveQrLinks: Boolean = false,
    /** Whether resolution is manual (per tap) or automatic during processing. */
    val resolveTrigger: ResolveTrigger = ResolveTrigger.MANUAL,
    /** Restrict resolution to an unmetered (Wi-Fi-like) connection. */
    val resolveOnWifiOnly: Boolean = true,
    /**
     * Whether the preview's og:image is rendered. The URL is always stored once resolved;
     * this gate controls only whether the image is actually fetched/displayed, since
     * loading it contacts a (possibly different) image host.
     */
    val downloadPreviewImages: Boolean = false,
    /** Where a capture's description comes from. Generative is gated until a model exists. */
    val descriptionSource: DescriptionSource = DescriptionSource.STRUCTURED,
    /** Whether captures are scanned for QR/barcodes on-device (offline). */
    val decodeQrCodes: Boolean = true,
    /** Folder name under Pictures/ that captures are written into (then a /Captures subfolder). */
    val captureAlbumRoot: String = DEFAULT_CAPTURE_ROOT,
) {
    companion object {
        const val DEFAULT_CAPTURE_ROOT = "ScreenshotClassifier"
    }
}

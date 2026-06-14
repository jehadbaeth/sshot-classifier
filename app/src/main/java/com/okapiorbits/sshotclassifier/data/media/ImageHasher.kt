package com.okapiorbits.sshotclassifier.data.media

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Computes a stable content hash for an image so the same screenshot is never
 * processed twice, even if MediaStore reassigns ids or the file is moved.
 */
class ImageHasher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun sha256(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var read = stream.read(buffer)
                while (read >= 0) {
                    digest.update(buffer, 0, read)
                    read = stream.read(buffer)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            null
        }
    }
}

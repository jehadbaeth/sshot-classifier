package com.okapiorbits.sshotclassifier.diagnostics

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects the app's own logcat for the Developer-mode "Export debug logs" action. Since Android
 * 4.1 an app's `logcat -d` only returns entries from its own process (READ_LOGS is a privileged
 * permission we neither hold nor want), which is exactly what we need to diagnose the experimental
 * VLM path on a tester's device. Best-effort: returns whatever the buffer holds, prefixed with
 * device/build info, or an error note if the command can't run.
 */
@Singleton
class DebugLogExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun collect(): String = withContext(Dispatchers.IO) {
        val header = buildString {
            appendLine("# Screenshot Classifier debug log")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("abis: ${Build.SUPPORTED_ABIS?.joinToString().orEmpty()}")
            appendLine("app: ${versionName()}")
            appendLine()
        }
        header + runCatching { readLogcat() }
            .getOrElse { "Could not read logcat: ${it.message}" }
    }

    private fun readLogcat(): String {
        // -d dumps and exits; -v time gives readable timestamps. Own-process only on API 16+.
        val process = ProcessBuilder("logcat", "-d", "-v", "time")
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return text.ifBlank { "(logcat returned no entries for this process)" }
    }

    private fun versionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
}

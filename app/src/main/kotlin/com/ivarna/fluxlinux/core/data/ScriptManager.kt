package com.ivarna.fluxlinux.core.data

import android.content.Context
import android.util.Log
import java.io.InputStream

class ScriptManager(private val context: Context) {

    /**
     * Reads a script file from the assets folder and returns it as a single String.
     * Useful for passing small scripts directly to 'bash -c'.
     *
     * A missing or unreadable asset yields a stub that prints an error to stderr and
     * exits 127, so callers that run (or concatenate) the result fail instead of
     * reporting success.
     */
    fun getScriptContent(fileName: String): String =
        readScript(fileName) { context.assets.open(it) }

    companion object {
        private const val TAG = "ScriptManager"

        internal fun readScript(fileName: String, open: (String) -> InputStream): String =
            try {
                open("scripts/$fileName").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read script asset $fileName", e)
                missingScript(fileName)
            }

        internal fun missingScript(fileName: String): String {
            val quoted = "'" + fileName.replace("'", "'\\''") + "'"
            return "echo \"FluxLinux: script asset missing or unreadable: \"$quoted >&2\nexit 127\n"
        }
    }
}

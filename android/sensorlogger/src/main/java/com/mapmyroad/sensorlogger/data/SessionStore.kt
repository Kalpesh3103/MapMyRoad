package com.mapmyroad.sensorlogger.data

import android.content.Context
import android.util.Log
import com.mapmyroad.sensorlogger.core.SessionNaming
import com.mapmyroad.sensorlogger.core.SessionZipper
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One session directory as shown on the sessions screen. */
data class SessionInfo(
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val isComplete: Boolean?,
)

/**
 * File access for recorded sessions. All functions block: call them from a background dispatcher.
 * Threading: stateless apart from the Context; safe to call from any thread.
 */
class SessionStore(
    context: Context,
) {
    private val appContext: Context = context.applicationContext

    /** Root `<external files>/sessions`, or null if external storage is unavailable. */
    fun sessionsRoot(): File? = appContext.getExternalFilesDir(null)?.let { File(it, SessionNaming.SESSIONS_DIR) }

    fun list(): List<SessionInfo> {
        val root = sessionsRoot() ?: return emptyList()
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        return dirs.sortedByDescending { it.name }.map { dir -> describe(dir) }
    }

    /** Zips the session into the cache `share/` directory (see res/xml/file_paths.xml). */
    @Throws(IOException::class)
    fun zipForSharing(name: String): File {
        val dir = sessionDir(name) ?: throw IOException("Session not found")
        val shareDir = File(appContext.cacheDir, SHARE_DIR)
        // Keep at most one zip around; old ones are only temporary share copies.
        shareDir.listFiles()?.forEach { it.delete() }
        return SessionZipper.zip(dir, shareDir)
    }

    fun delete(name: String): Boolean = sessionDir(name)?.deleteRecursively() ?: false

    private fun sessionDir(name: String): File? {
        val root = sessionsRoot() ?: return null
        val dir = File(root, name)
        // Guard against path tricks: the session must be a direct child of the root.
        return if (dir.isDirectory && dir.parentFile == root && !name.contains(File.separatorChar)) dir else null
    }

    private fun describe(dir: File): SessionInfo {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        var durationMs: Long? = null
        var isComplete: Boolean? = null
        val meta = File(dir, SessionNaming.META_FILE)
        if (meta.isFile) {
            try {
                val json = JSONObject(meta.readText())
                if (json.has("durationMs")) durationMs = json.getLong("durationMs")
                if (json.has("isComplete")) isComplete = json.getBoolean("isComplete")
            } catch (e: IOException) {
                Log.w(TAG, "Cannot read meta.json", e)
            } catch (e: JSONException) {
                Log.w(TAG, "Invalid meta.json", e)
            }
        }
        return SessionInfo(
            name = dir.name,
            sizeBytes = files.sumOf { it.length() },
            durationMs = durationMs,
            isComplete = isComplete,
        )
    }

    companion object {
        const val SHARE_DIR = "share"
        private const val TAG = "MmrSessionStore"
    }
}

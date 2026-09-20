package com.meracast.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.meracast.common.AppConstants
import com.meracast.common.ControlMessage
import com.meracast.common.MediaMimeTypes
import com.meracast.ui.DebugLogStore

/**
 * Lists media files (video + audio) from the device's external storage.
 *
 * Uses MediaStore to query without needing READ_EXTERNAL_STORAGE
 * on Android 13+ (uses the grant via MediaProjection or the
 * READ_MEDIA_VIDEO / READ_MEDIA_AUDIO granular permissions).
 */
class MediaFileProvider(private val context: Context) {

    companion object {
        private const val TAG = "${AppConstants.TAG}.MediaProv"
    }

    /**
     * Scan the device for available video and audio files.
     *
     * @param maxFiles Maximum files to return (default 100).
     */
    fun listMediaFiles(maxFiles: Int = 100): List<ControlMessage.FileEntry> {
        val files = mutableListOf<ControlMessage.FileEntry>()

        // Query video files
        try {
            val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.MIME_TYPE
            )
            val cursor = context.contentResolver.query(
                videoUri, projection, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.Video.Media._ID)
                val titleCol = c.getColumnIndex(MediaStore.Video.Media.TITLE)
                val dataCol = c.getColumnIndex(MediaStore.Video.Media.DATA)
                val sizeCol = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                val durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                val mimeCol = c.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)

                while (c.moveToNext() && files.size < maxFiles) {
                    val id = c.getLong(idCol).toString()
                    val title = c.getString(titleCol) ?: "Unknown"
                    val path = c.getString(dataCol) ?: continue
                    val size = c.getLong(sizeCol)
                    val durationMs = if (durCol >= 0) c.getLong(durCol) else 0L
                    val mime = if (mimeCol >= 0) c.getString(mimeCol)
                        ?: MediaMimeTypes.forFile(path) else MediaMimeTypes.forFile(path)

                    files.add(ControlMessage.FileEntry(
                        id = id,
                        name = title,
                        path = path,
                        size = size,
                        mimeType = mime,
                        duration = durationMs / 1000.0
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying video files", e)
            DebugLogStore.log("MEDIA: Video query error — ${e.message}")
        }

        // Query audio files if we still have room
        if (files.size < maxFiles) {
            try {
                val audioUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.MIME_TYPE
                )
                val cursor = context.contentResolver.query(
                    audioUri, projection, null, null,
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                )
                cursor?.use { c ->
                    val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
                    val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                    val sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                    val durCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)

                    while (c.moveToNext() && files.size < maxFiles) {
                        val id = c.getLong(idCol).toString()
                        val title = c.getString(titleCol) ?: "Unknown"
                        val path = c.getString(dataCol) ?: continue
                        val size = c.getLong(sizeCol)
                        val durationMs = if (durCol >= 0) c.getLong(durCol) else 0L
                        val mime = if (mimeCol >= 0) c.getString(mimeCol)
                            ?: MediaMimeTypes.forFile(path) else MediaMimeTypes.forFile(path)

                        files.add(ControlMessage.FileEntry(
                            id = "a_$id",
                            name = title,
                            path = path,
                            size = size,
                            mimeType = mime,
                            duration = durationMs / 1000.0
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying audio files", e)
            }
        }

        DebugLogStore.log("MEDIA: Found ${files.size} media file(s) on device")
        return files
    }
}

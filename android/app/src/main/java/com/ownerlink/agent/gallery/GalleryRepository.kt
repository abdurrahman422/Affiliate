package com.ownerlink.agent.gallery

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.ownerlink.agent.files.FileIds
import com.ownerlink.agent.util.Permissions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

class GalleryRepository(private val context: Context) {
    fun listVideos(cursor: String?, limit: Int): JsonObject {
        if (!Permissions.hasMedia(context)) return buildJsonObject {
            put("ok", false); put("code", "PERMISSION_MISSING")
            put("message", "Video permission is not granted on the phone.")
        }
        val items = ArrayList<JsonObject>()
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION,
        )
        val after = cursor?.toLongOrNull()
        val selection = after?.let { "${MediaStore.Video.Media._ID} < ?" }
        val args = after?.let { arrayOf(it.toString()) }
        context.contentResolver.query(uri, projection, selection, args, "${MediaStore.Video.Media._ID} DESC")?.use { c ->
            while (c.moveToNext() && items.size < limit.coerceIn(1, 100)) {
                val id = c.getLong(0)
                items += buildJsonObject {
                    put("id", FileIds.encodeGallery(id)); put("name", c.getString(1) ?: "video")
                    put("mime", c.getString(2) ?: "video/*"); put("size", c.getLong(3))
                    put("width", c.getInt(4)); put("height", c.getInt(5))
                    put("taken_at", c.getLong(6)); put("duration_ms", c.getLong(7)); put("is_video", true)
                }
            }
        }
        val next = items.lastOrNull()?.get("id")?.jsonPrimitive?.content?.let {
            (FileIds.decode(it) as com.ownerlink.agent.files.FileRef.Gallery).mediaId.toString()
        }
        return buildJsonObject {
            put("items", JsonArray(items)); put("has_more", items.size >= limit.coerceIn(1, 100))
            if (items.size >= limit.coerceIn(1, 100) && next != null) put("cursor", next)
        }
    }

    fun list(cursor: String?, limit: Int): JsonObject {
        if (!Permissions.hasMedia(context)) {
            return buildJsonObject {
                put("ok", false)
                put("code", "PERMISSION_MISSING")
                put("message", "Photos and videos permission is not granted on the phone.")
            }
        }
        val items = ArrayList<JsonObject>()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val selection = StringBuilder(
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?)",
        )
        val args = mutableListOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val after = cursor?.toLongOrNull()
        if (after != null) {
            selection.append(" AND ${MediaStore.Files.FileColumns._ID} < ?")
            args.add(after.toString())
        }
        val sort = "${MediaStore.Files.FileColumns._ID} DESC"
        context.contentResolver.query(uri, projection, selection.toString(), args.toTypedArray(), sort)?.use { c ->
            val idIx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeIx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeIx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val wIx = c.getColumnIndex(MediaStore.Files.FileColumns.WIDTH)
            val hIx = c.getColumnIndex(MediaStore.Files.FileColumns.HEIGHT)
            val dateIx = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
            val typeIx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            var n = 0
            while (c.moveToNext() && n < limit) {
                val id = c.getLong(idIx)
                val mediaType = c.getInt(typeIx)
                items.add(
                    buildJsonObject {
                        put("id", FileIds.encodeGallery(id))
                        put("name", c.getString(nameIx) ?: "media")
                        put("mime", c.getString(mimeIx) ?: "")
                        put("size", c.getLong(sizeIx))
                        if (wIx >= 0) put("width", c.getInt(wIx))
                        if (hIx >= 0) put("height", c.getInt(hIx))
                        if (dateIx >= 0) put("taken_at", c.getLong(dateIx))
                        put("is_video", mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                    },
                )
                n++
            }
        }
        val hasMore = items.size >= limit
        val cursorOut = if (hasMore && items.isNotEmpty()) {
            val encoded = items.last()["id"]!!.jsonPrimitive.content
            val ref = FileIds.decode(encoded) as com.ownerlink.agent.files.FileRef.Gallery
            ref.mediaId.toString()
        } else null
        return buildJsonObject {
            put("items", JsonArray(items))
            put("has_more", hasMore)
            if (cursorOut != null) put("cursor", cursorOut)
        }
    }

    fun thumbnailJpeg(mediaId: Long): ByteArray {
        val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), mediaId)
        val bitmap = if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver,
                mediaId,
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
        } ?: error("thumbnail unavailable")
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return out.toByteArray()
    }

    fun openOriginal(mediaId: Long) =
        context.contentResolver.openInputStream(
            ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), mediaId),
        ) ?: error("media not found")

    fun displayName(mediaId: Long): Pair<String, String?> {
        val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), mediaId)
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.SIZE),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                return (c.getString(0) ?: "media") to c.getString(1)
            }
        }
        return "media" to null
    }

    fun sizeOf(mediaId: Long): Long? {
        val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), mediaId)
        context.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }
}


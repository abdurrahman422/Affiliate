package com.ownerlink.agent.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

sealed class FileRef {
    data class Gallery(val mediaId: Long) : FileRef()
    data class Path(val canonical: String) : FileRef()
    data class Saf(val treeUri: String, val documentId: String) : FileRef()
    data class AppPrivate(val logicalName: String) : FileRef()
}

object FileIds {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeGallery(mediaId: Long): String = encode(buildJsonObject {
        put("k", "g")
        put("i", mediaId)
    })

    fun encodePath(canonical: String): String = encode(buildJsonObject {
        put("k", "p")
        put("p", canonical)
    })

    fun encodeSaf(treeUri: String, documentId: String): String = encode(buildJsonObject {
        put("k", "s")
        put("t", treeUri)
        put("d", documentId)
    })

    fun encodeAppPrivate(logicalName: String): String = encode(buildJsonObject {
        put("k", "a")
        put("n", logicalName)
    })

    fun decode(id: String): FileRef {
        val obj = decodeObject(id)
        return when (obj["k"]?.jsonPrimitive?.contentOrNull) {
            "g" -> FileRef.Gallery(obj["i"]?.jsonPrimitive?.longOrNull ?: error("bad gallery id"))
            "p" -> FileRef.Path(obj["p"]?.jsonPrimitive?.contentOrNull ?: error("bad path id"))
            "s" -> FileRef.Saf(
                obj["t"]?.jsonPrimitive?.contentOrNull ?: error("bad saf tree"),
                obj["d"]?.jsonPrimitive?.contentOrNull ?: error("bad saf doc"),
            )
            "a" -> FileRef.AppPrivate(obj["n"]?.jsonPrimitive?.contentOrNull ?: error("bad app file"))
            else -> error("unknown file id")
        }
    }

    fun assertAllowedPath(path: String, roots: List<File>) {
        val canonical = File(path).canonicalFile
        val ok = roots.any { root ->
            val r = root.canonicalFile
            canonical == r || canonical.path.startsWith(r.path.trimEnd('/', '\\') + File.separator)
        }
        if (!ok) error("path not allowed")
    }

    private fun encode(obj: JsonObject): String {
        val raw = obj.toString().toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    private fun decodeObject(id: String): JsonObject {
        val bytes = Base64.getUrlDecoder().decode(id)
        return json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
    }
}

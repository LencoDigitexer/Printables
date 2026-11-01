package com.lencodigitexer.printables.data

import org.json.JSONArray
import org.json.JSONObject

enum class SearchOrdering(val apiValue: String, val displayName: String) {
    BEST_MATCH("best_match", "Best Match"),
    POPULAR("popular", "Popular"),
    LATEST("latest", "Latest"),
    RATING("rating", "Top Rated"),
    MAKES_COUNT("makes_count", "Most Makes");

    companion object {
        fun fromApiValue(value: String): SearchOrdering =
            entries.find { it.apiValue == value } ?: BEST_MATCH
    }
}

data class ModelFile(
    val id: String,
    val name: String,
    val fileType: String, // "stl", "gcode", "sla", "other"
    val sizeBytes: Long? = null,
    val printer: String? = null,
    val material: String? = null,
    val printDuration: String? = null,
    val layerHeight: Double? = null,
    val nozzleDiameter: Double? = null,
    val weight: Double? = null,
    val created: String? = null,
    var downloadUrl: String? = null,
    val filePreviewPath: String? = null
) {
    val formattedSize: String
        get() {
            val bytes = sizeBytes ?: return "Unknown size"
            return when {
                bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
                bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
        }

    val subtitle: String
        get() {
            val parts = mutableListOf<String>()
            parts.add(formattedSize)
            if (fileType.equals("gcode", ignoreCase = true)) {
                if (!printer.isNullOrBlank()) parts.add(printer)
                if (!material.isNullOrBlank()) parts.add(material)
                if (!printDuration.isNullOrBlank()) parts.add(printDuration)
                if (layerHeight != null && layerHeight > 0) parts.add(String.format("%.2fmm", layerHeight))
            } else {
                parts.add(fileType.uppercase())
            }
            return parts.joinToString(" • ")
        }

    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("file_type", fileType)
        obj.put("size_bytes", sizeBytes ?: JSONObject.NULL)
        obj.put("printer", printer ?: JSONObject.NULL)
        obj.put("material", material ?: JSONObject.NULL)
        obj.put("printDuration", printDuration ?: JSONObject.NULL)
        obj.put("layerHeight", layerHeight ?: JSONObject.NULL)
        obj.put("download_url", downloadUrl ?: JSONObject.NULL)
        return obj
    }
}

data class PrintableModel(
    val id: String,
    val name: String,
    val slug: String? = null,
    val ratingAvg: Double? = null,
    val likesCount: Int? = null,
    val downloadCount: Int? = null,
    val datePublished: String? = null,
    val author: String? = null,
    val authorHandle: String? = null,
    val mainImageUrl: String? = null,
    val imageUrls: List<String> = emptyList(),
    val description: String? = null,
    var files: List<ModelFile> = emptyList(),
    var isBookmarked: Boolean = false,
    val isCuratedFallback: Boolean = false
) {
    val modelUrl: String
        get() {
            val slugPart = if (!slug.isNullOrBlank()) "-$slug" else ""
            return "https://www.printables.com/model/$id$slugPart"
        }

    val formattedRating: String
        get() = ratingAvg?.let { String.format("%.1f", it) } ?: "5.0"

    val formattedLikes: String
        get() = formatMetric(likesCount ?: 0)

    val formattedDownloads: String
        get() = formatMetric(downloadCount ?: 0)

    private fun formatMetric(count: Int): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fk", count / 1_000.0)
            else -> count.toString()
        }
    }

    /**
     * Converts to JSON matching printables_api.py CLI output format:
     * all_models_data[model_id_str] = {
     *   "name": ...,
     *   "url": ...,
     *   "main_image_url": ...,
     *   "image_urls": [...],
     *   "author": ...,
     *   "stats": { likes, downloads, rating, published_date },
     *   "description": ...,
     *   "files": [...]
     * }
     */
    fun toPrintablesApiJson(): JSONObject {
        val root = JSONObject()
        root.put("name", name)
        root.put("url", modelUrl)
        root.put("main_image_url", mainImageUrl ?: JSONObject.NULL)
        
        val imagesArr = JSONArray()
        val allImages = if (imageUrls.isNotEmpty()) imageUrls else listOfNotNull(mainImageUrl)
        allImages.forEach { imagesArr.put(it) }
        root.put("image_urls", imagesArr)

        root.put("author", author ?: "Creator")

        val statsObj = JSONObject()
        statsObj.put("likes", likesCount ?: 0)
        statsObj.put("downloads", downloadCount ?: 0)
        statsObj.put("rating", ratingAvg ?: 0.0)
        statsObj.put("published_date", datePublished ?: JSONObject.NULL)
        root.put("stats", statsObj)

        root.put("description", description ?: "3D model from Printables.")

        val filesArr = JSONArray()
        files.forEach { file ->
            val f = JSONObject()
            f.put("name", file.name)
            f.put("download_url", file.downloadUrl ?: JSONObject.NULL)
            f.put("size_bytes", file.sizeBytes ?: JSONObject.NULL)
            f.put("file_type", file.fileType)
            filesArr.put(f)
        }
        root.put("files", filesArr)

        return root
    }
}

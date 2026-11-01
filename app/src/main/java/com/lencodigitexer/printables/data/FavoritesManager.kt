package com.lencodigitexer.printables.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class FavoritesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("printables_favorites_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SAVED_MODELS_JSON = "key_saved_models_json"
    }

    fun isBookmarked(modelId: String): Boolean {
        val map = loadModelsMap()
        return map.containsKey(modelId)
    }

    fun toggleBookmark(model: PrintableModel): Boolean {
        val map = loadModelsMap().toMutableMap()
        val willBeBookmarked = !map.containsKey(model.id)
        if (willBeBookmarked) {
            model.isBookmarked = true
            map[model.id] = model
        } else {
            model.isBookmarked = false
            map.remove(model.id)
        }
        saveModelsMap(map)
        return willBeBookmarked
    }

    fun getBookmarkedModels(): List<PrintableModel> {
        val map = loadModelsMap()
        return map.values.toList().reversed()
    }

    private fun loadModelsMap(): Map<String, PrintableModel> {
        val jsonStr = prefs.getString(KEY_SAVED_MODELS_JSON, null) ?: return emptyMap()
        val map = mutableMapOf<String, PrintableModel>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                val name = obj.optString("name")
                val slug = obj.optString("slug")
                val rating = if (obj.has("ratingAvg") && !obj.isNull("ratingAvg")) obj.getDouble("ratingAvg") else null
                val likes = obj.optInt("likesCount", 0)
                val downloads = obj.optInt("downloadCount", 0)
                val datePublished = obj.optString("datePublished")
                val author = obj.optString("author")
                val authorHandle = obj.optString("authorHandle")
                val mainImageUrl = obj.optString("mainImageUrl").takeIf { it.isNotBlank() }
                val description = obj.optString("description")

                map[id] = PrintableModel(
                    id = id,
                    name = name,
                    slug = slug,
                    ratingAvg = rating,
                    likesCount = likes,
                    downloadCount = downloads,
                    datePublished = datePublished,
                    author = author,
                    authorHandle = authorHandle,
                    mainImageUrl = mainImageUrl,
                    description = description,
                    isBookmarked = true
                )
            }
        } catch (_: Exception) {}
        return map
    }

    private fun saveModelsMap(map: Map<String, PrintableModel>) {
        val arr = JSONArray()
        for (m in map.values) {
            val obj = JSONObject()
            obj.put("id", m.id)
            obj.put("name", m.name)
            obj.put("slug", m.slug ?: JSONObject.NULL)
            obj.put("ratingAvg", m.ratingAvg ?: JSONObject.NULL)
            obj.put("likesCount", m.likesCount ?: 0)
            obj.put("downloadCount", m.downloadCount ?: 0)
            obj.put("datePublished", m.datePublished ?: JSONObject.NULL)
            obj.put("author", m.author ?: JSONObject.NULL)
            obj.put("authorHandle", m.authorHandle ?: JSONObject.NULL)
            obj.put("mainImageUrl", m.mainImageUrl ?: JSONObject.NULL)
            obj.put("description", m.description ?: JSONObject.NULL)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_SAVED_MODELS_JSON, arr.toString()).apply()
    }
}

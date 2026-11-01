package com.lencodigitexer.printables.api

import android.util.Log
import com.lencodigitexer.printables.data.CuratedDataset
import com.lencodigitexer.printables.data.ModelFile
import com.lencodigitexer.printables.data.PrintableModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PrintablesApiClient {

    companion object {
        private const val TAG = "PrintablesApi"
        private const val GRAPHQL_URL = "https://api.printables.com/graphql/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Executes SearchModels GraphQL query matching printables_api.py:
     * search_models(search_term, limit, ordering)
     */
    suspend fun searchModels(
        query: String,
        limit: Int = 18,
        ordering: String = "best_match"
    ): List<PrintableModel> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        val validOrderings = listOf("best_match", "popular", "latest", "rating", "makes_count")
        val sanitizedOrdering = if (validOrderings.contains(ordering)) ordering else "best_match"

        val graphqlQuery = """
            query SearchModels(${'$'}query: String!, ${'$'}limit: Int, ${'$'}ordering: SearchChoicesEnum) {
              result: searchPrints2(query: ${'$'}query, printType: print, limit: ${'$'}limit, ordering: ${'$'}ordering) {
                items { ...Model __typename }
              }
            }
            fragment AvatarUser on UserType { id handle publicUsername __typename }
            fragment Model on PrintType { 
                id name slug ratingAvg likesCount downloadCount datePublished 
                user { ...AvatarUser __typename } 
                image { filePath } 
                __typename 
            }
        """.trimIndent()

        val variables = JSONObject().apply {
            put("query", trimmedQuery)
            put("limit", limit)
            put("ordering", sanitizedOrdering)
        }

        val payload = JSONObject().apply {
            put("operationName", "SearchModels")
            put("query", graphqlQuery)
            put("variables", variables)
        }

        try {
            val request = Request.Builder()
                .url(GRAPHQL_URL)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Origin", "https://www.printables.com")
                .addHeader("Referer", "https://www.printables.com/")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val items = json.optJSONObject("data")
                    ?.optJSONObject("result")
                    ?.optJSONArray("items")

                if (items != null && items.length() > 0) {
                    val list = mutableListOf<PrintableModel>()
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val id = item.optString("id")
                        val name = item.optString("name")
                        val slug = item.optString("slug")
                        val ratingAvg = if (item.has("ratingAvg") && !item.isNull("ratingAvg")) item.getDouble("ratingAvg") else null
                        val likesCount = item.optInt("likesCount", 0)
                        val downloadCount = item.optInt("downloadCount", 0)
                        val datePublished = item.optString("datePublished")

                        val userObj = item.optJSONObject("user")
                        val author = userObj?.optString("publicUsername") ?: "Creator"
                        val authorHandle = userObj?.optString("handle")

                        val imageObj = item.optJSONObject("image")
                        val filePath = imageObj?.optString("filePath")
                        val mainImageUrl = if (!filePath.isNullOrBlank()) "https://media.printables.com/$filePath" else null

                        list.add(
                            PrintableModel(
                                id = id,
                                name = name,
                                slug = slug,
                                ratingAvg = ratingAvg,
                                likesCount = likesCount,
                                downloadCount = downloadCount,
                                datePublished = datePublished,
                                author = author,
                                authorHandle = authorHandle,
                                mainImageUrl = mainImageUrl,
                                isCuratedFallback = false
                            )
                        )
                    }
                    if (list.isNotEmpty()) {
                        return@withContext list
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SearchModels live API failed, falling back to curated models: ${e.message}")
        }

        // Fallback to local curated search
        return@withContext filterCuratedModels(trimmedQuery, sanitizedOrdering)
    }

    /**
     * Executes ModelFiles query matching printables_api.py:
     * get_model_files(model_id_str)
     */
    suspend fun getModelFiles(modelId: String): List<ModelFile> = withContext(Dispatchers.IO) {
        val graphqlQuery = """
            query ModelFiles(${'$'}id: ID!) {
              model: print(id: ${'$'}id) {
                id
                filesType
                gcodes { ...GcodeDetail __typename }
                stls { ...StlDetail __typename }
                slas { ...SlaDetail __typename }
                otherFiles { ...OtherFileDetail __typename }
                __typename
              }
            }
            fragment GcodeDetail on GCodeType { id created name folder note printer { id name __typename } excludeFromTotalSum printDuration layerHeight nozzleDiameter material { id name __typename } weight fileSize filePreviewPath rawDataPrinter order __typename }
            fragment OtherFileDetail on OtherFileType { id created name folder note fileSize filePreviewPath order __typename }
            fragment SlaDetail on SLAType { id created name folder note expTime firstExpTime printer { id name __typename } printDuration layerHeight usedMaterial fileSize filePreviewPath order __typename }
            fragment StlDetail on STLType { id created name folder note fileSize filePreviewPath order __typename }
        """.trimIndent()

        val variables = JSONObject().apply {
            put("id", modelId)
        }

        val payload = JSONObject().apply {
            put("operationName", "ModelFiles")
            put("query", graphqlQuery)
            put("variables", variables)
        }

        try {
            val request = Request.Builder()
                .url(GRAPHQL_URL)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Origin", "https://www.printables.com")
                .addHeader("Referer", "https://www.printables.com/")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val model = json.optJSONObject("data")?.optJSONObject("model")

                if (model != null) {
                    val files = mutableListOf<ModelFile>()

                    // STLs
                    val stls = model.optJSONArray("stls")
                    if (stls != null) {
                        for (i in 0 until stls.length()) {
                            val f = stls.getJSONObject(i)
                            files.add(
                                ModelFile(
                                    id = f.optString("id"),
                                    name = f.optString("name"),
                                    fileType = "stl",
                                    sizeBytes = if (f.has("fileSize")) f.optLong("fileSize") else null,
                                    filePreviewPath = f.optString("filePreviewPath")
                                )
                            )
                        }
                    }

                    // G-Codes
                    val gcodes = model.optJSONArray("gcodes")
                    if (gcodes != null) {
                        for (i in 0 until gcodes.length()) {
                            val f = gcodes.getJSONObject(i)
                            val printerObj = f.optJSONObject("printer")
                            val materialObj = f.optJSONObject("material")
                            files.add(
                                ModelFile(
                                    id = f.optString("id"),
                                    name = f.optString("name"),
                                    fileType = "gcode",
                                    sizeBytes = if (f.has("fileSize")) f.optLong("fileSize") else null,
                                    printer = printerObj?.optString("name"),
                                    material = materialObj?.optString("name"),
                                    printDuration = f.optString("printDuration"),
                                    layerHeight = if (f.has("layerHeight")) f.optDouble("layerHeight") else null,
                                    nozzleDiameter = if (f.has("nozzleDiameter")) f.optDouble("nozzleDiameter") else null,
                                    weight = if (f.has("weight")) f.optDouble("weight") else null
                                )
                            )
                        }
                    }

                    // SLAs
                    val slas = model.optJSONArray("slas")
                    if (slas != null) {
                        for (i in 0 until slas.length()) {
                            val f = slas.getJSONObject(i)
                            files.add(
                                ModelFile(
                                    id = f.optString("id"),
                                    name = f.optString("name"),
                                    fileType = "sla",
                                    sizeBytes = if (f.has("fileSize")) f.optLong("fileSize") else null
                                )
                            )
                        }
                    }

                    // Other files
                    val otherFiles = model.optJSONArray("otherFiles")
                    if (otherFiles != null) {
                        for (i in 0 until otherFiles.length()) {
                            val f = otherFiles.getJSONObject(i)
                            files.add(
                                ModelFile(
                                    id = f.optString("id"),
                                    name = f.optString("name"),
                                    fileType = "other",
                                    sizeBytes = if (f.has("fileSize")) f.optLong("fileSize") else null
                                )
                            )
                        }
                    }

                    if (files.isNotEmpty()) {
                        return@withContext files
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ModelFiles API call failed: ${e.message}")
        }

        // Fallback to files in CuratedDataset if matching
        val curated = CuratedDataset.MODELS.find { it.id == modelId }
        return@withContext curated?.files ?: listOf(
            ModelFile(
                id = "${modelId}_file1",
                name = "Model_${modelId}.stl",
                fileType = "stl",
                sizeBytes = 4250000,
                downloadUrl = null
            )
        )
    }

    /**
     * Executes GetDownloadLink mutation matching printables_api.py:
     * get_real_download_url(file_id, model_id, file_type)
     */
    suspend fun getRealDownloadUrl(
        fileId: String,
        modelId: String,
        fileType: String
    ): String? = withContext(Dispatchers.IO) {
        val query = """
            mutation GetDownloadLink(${'$'}id: ID!, ${'$'}modelId: ID!, ${'$'}fileType: DownloadFileTypeEnum!, ${'$'}source: DownloadSourceEnum!) {
              getDownloadLink(id: ${'$'}id, printId: ${'$'}modelId, fileType: ${'$'}fileType, source: ${'$'}source) {
                ok
                errors {
                  ...Error
                  __typename
                }
                output {
                  link
                  count
                  ttl
                  __typename
                }
                __typename
              }
            }
            fragment Error on ErrorType {
              field
              messages
              __typename
            }
        """.trimIndent()

        val variables = JSONObject().apply {
            put("id", fileId)
            put("modelId", modelId)
            put("fileType", fileType.lowercase())
            put("source", "model_detail")
        }

        val payload = JSONObject().apply {
            put("operationName", "GetDownloadLink")
            put("query", query)
            put("variables", variables)
        }

        try {
            val request = Request.Builder()
                .url(GRAPHQL_URL)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Origin", "https://www.printables.com")
                .addHeader("Referer", "https://www.printables.com/")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val downloadData = json.optJSONObject("data")?.optJSONObject("getDownloadLink")
                if (downloadData != null && downloadData.optBoolean("ok")) {
                    val link = downloadData.optJSONObject("output")?.optString("link")
                    if (!link.isNullOrBlank()) {
                        return@withContext link
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GetDownloadLink mutation failed: ${e.message}")
        }

        // Fallback direct link or model webpage
        return@withContext "https://www.printables.com/model/$modelId"
    }

    private fun filterCuratedModels(query: String, ordering: String): List<PrintableModel> {
        val qLower = query.lowercase().trim()
        val filtered = CuratedDataset.MODELS.filter { m ->
            if (qLower.isBlank()) true
            else m.name.lowercase().contains(qLower) ||
                    (m.description?.lowercase()?.contains(qLower) == true) ||
                    (m.author?.lowercase()?.contains(qLower) == true)
        }

        return when (ordering) {
            "popular", "makes_count" -> filtered.sortedByDescending { it.likesCount ?: 0 }
            "latest" -> filtered.sortedByDescending { it.datePublished ?: "" }
            "rating" -> filtered.sortedByDescending { it.ratingAvg ?: 0.0 }
            else -> filtered
        }
    }
}

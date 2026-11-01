package com.lencodigitexer.printables.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.TransitionDrawable
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class PrintablesImageLoader private constructor(context: Context) {

    companion object {
        private const val TAG = "PrintablesImageLoader"

        @Volatile
        private var instance: PrintablesImageLoader? = null

        fun getInstance(context: Context): PrintablesImageLoader {
            return instance ?: synchronized(this) {
                instance ?: PrintablesImageLoader(context.applicationContext).also { instance = it }
            }
        }
    }

    private val memoryCache: LruCache<String, Bitmap>
    private val httpClient: OkHttpClient

    init {
        // Allocate 1/8th of max available memory for bitmap cache
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8
        memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }

        // 50MB disk cache
        val cacheDir = File(context.cacheDir, "printables_http_cache")
        val cache = Cache(cacheDir, 50L * 1024L * 1024L)

        httpClient = OkHttpClient.Builder()
            .cache(cache)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun load(
        url: String?,
        imageView: ImageView,
        placeholderRes: Int = 0,
        scope: CoroutineScope
    ): Job {
        if (url.isNullOrBlank()) {
            if (placeholderRes != 0) {
                imageView.setImageResource(placeholderRes)
            } else {
                imageView.setImageDrawable(null)
            }
            imageView.tag = null
            return Job()
        }

        imageView.tag = url

        // Check memory cache first
        val cached = memoryCache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return Job()
        }

        // Set placeholder while loading
        if (placeholderRes != 0) {
            imageView.setImageResource(placeholderRes)
        }

        return scope.launch(Dispatchers.IO) {
            val bitmap = fetchAndDecodeBitmap(url)
            if (bitmap != null) {
                memoryCache.put(url, bitmap)
                withContext(Dispatchers.Main) {
                    if (imageView.tag == url) {
                        val currentDrawable = imageView.drawable ?: ColorDrawable(0x00000000)
                        val targetDrawable = BitmapDrawable(imageView.resources, bitmap)
                        val transition = TransitionDrawable(arrayOf(currentDrawable, targetDrawable))
                        transition.isCrossFadeEnabled = true
                        imageView.setImageDrawable(transition)
                        transition.startTransition(200)
                    }
                }
            }
        }
    }

    private fun fetchAndDecodeBitmap(url: String): Bitmap? {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null

            val bytes = response.body?.bytes() ?: return null

            // First decode with inJustDecodeBounds=true to check dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            // Target max dimension ~800px to save RAM
            options.inSampleSize = calculateInSampleSize(options, 800, 800)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            Log.w(TAG, "Error downloading bitmap from $url: ${e.message}")
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}

package com.example.llama.handler

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class RAGHandler constructor(private val context: Context) {
    private val embeddingProvider = SentenceEmbeddingProvider.getInstance(context)
    private val tag = "RAGHandler"
    private val embeddingCache = mutableMapOf<String, FloatArray>()
    private val embeddingSize = 384 // Matches all-MiniLM-L6-v2
    private var isInitialized = false

    companion object {
        @Volatile
        private var instance: RAGHandler? = null
        private var dbFile: File? = null

        fun getInstance(context: Context): RAGHandler {
            return instance ?: synchronized(this) {
                instance ?: RAGHandler(context.applicationContext).also {
                    instance = it
                    it.initialize()
                }
            }
        }

        private fun copyToLocalStorage(context: Context, filename: String): File {
            val storageFile = File(context.filesDir, filename)
            if (!storageFile.exists()) {
                try {
                    storageFile.parentFile?.mkdirs()
                    context.assets.open(filename).use { inputStream ->
                        FileOutputStream(storageFile).use { outputStream ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                    Log.d("RAGHandler", "Copied $filename to ${storageFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e("RAGHandler", "Failed to copy $filename: ${e.stackTraceToString()}")
                }
            }
            return storageFile
        }
    }

    private fun initialize() {
        if (!isInitialized) {
            synchronized(this) {
                if (!isInitialized) {
                    dbFile = copyToLocalStorage(context, "QAonnx.db")
                    Log.d(tag, "Initialized with dbFile at: ${dbFile?.absolutePath}")
                    isInitialized = true
                }
            }
        }
    }

    private suspend fun computeEmbedding(text: String): FloatArray {
        Log.d(tag, "Computing embedding for text: $text")
        embeddingCache[text]?.let {
            Log.d(tag, "Cache hit for text: $text")
            return it
        }
        return withContext(Dispatchers.Default) {
            try {
                // Retry embedding generation
                val embedding = retryWithBackoff(3, 1000L) {
                    embeddingProvider.getEmbedding(text)?.takeIf {
                        it.size == embeddingSize && it.any { value -> value != 0.0f }
                    }
                }
                if (embedding == null || embedding.size != embeddingSize || embedding.all { it == 0.0f }) {
                    Log.w(tag, "Invalid embedding: null=${embedding == null}, size=${embedding?.size ?: 0}, allZero=${embedding?.all { it == 0.0f } ?: false}")
                    FloatArray(embeddingSize) { 0f }
                } else {
                    Log.d(tag, "Extracted embedding (first 10): ${embedding.take(10).joinToString()}")
                    embeddingCache[text] = embedding
                    embedding
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to compute embedding: ${e.stackTraceToString()}")
                FloatArray(embeddingSize) { 0f }
            }
        }
    }

    suspend fun retrieveRelevantContext(prompt: String, topK: Int = 3): List<String> {
        if (!isInitialized) {
            initialize()
            if (!isInitialized || dbFile == null) {
                Log.e(tag, "Failed to initialize, cannot access database file")
                return emptyList()
            }
        }

        var db: SQLiteDatabase? = null
        try {
            // Open database
            db = SQLiteDatabase.openDatabase(dbFile!!.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            Log.d(tag, "Database opened at: ${dbFile!!.absolutePath}")

            val promptEmbedding = computeEmbedding(prompt)
            if (promptEmbedding.all { it == 0f }) {
                Log.e(tag, "Zero prompt embedding for: $prompt}")
                return emptyList()
            }

            val cursor = db.rawQuery("SELECT id, text, embedding FROM qa", null)
            val results = mutableListOf<Pair<Float, String>>()

            cursor.use {
                while (it.moveToNext()) {
                    val text = it.getString(it.getColumnIndexOrThrow("text"))
                    val embeddingBlob = it.getBlob(it.getColumnIndexOrThrow("embedding"))
                    val embedding = FloatArray(embeddingSize)
                    try {
                        ByteBuffer.wrap(embeddingBlob).order(ByteOrder.LITTLE_ENDIAN)
                            .asFloatBuffer().get(embedding)
                        if (embedding.all { it == 0f }) {
                            Log.w(tag, "Zero embedding in database for text: $text}")
                            continue
                        }
                        val similarity = cosineSimilarity(promptEmbedding, embedding)
                        results.add(similarity to text)
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to process embedding for text: $text, error: ${e.message}")
                        continue
                    }
                }
            }

            val topResults = results.sortedByDescending { it.first }.take(topK)
            Log.d(tag, "Top $topK results: ${topResults.map { "${it.second} (score: ${it.first})" }}")
            return topResults.map { it.second }
        } catch (e: Exception) {
            Log.e(tag, "Error retrieving context: ${e.stackTraceToString()}")
            return emptyList()
        } finally {
            // Close database
            db?.let {
                if (it.isOpen) {
                    it.close()
                    Log.d(tag, "Database closed")
                }
            }
        }
    }

    private suspend fun <T> retryWithBackoff(
        maxAttempts: Int,
        delayMs: Long,
        block: suspend () -> T
    ): T? {
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                Log.w(tag, "Attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < maxAttempts - 1) delay(delayMs)
            }
        }
        return null
    }

    private fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        if (vec1.size != vec2.size || vec1.size != embeddingSize) {
            Log.w(tag, "Mismatched embedding sizes: ${vec1.size} vs ${vec2.size}")
            return 0.0f
        }
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            normA += vec1[i] * vec1[i]
            normB += vec2[i] * vec2[i]
        }
        val similarity = if (normA > 0 && normB > 0) dotProduct / (sqrt(normA) * sqrt(normB)) else 0.0f
        return similarity
    }

    fun close() {
        embeddingProvider.close()
        embeddingCache.clear()
        isInitialized = false
        instance = null
        Log.d(tag, "Resources closed")
    }
}

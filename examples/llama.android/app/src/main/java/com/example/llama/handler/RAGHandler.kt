package com.example.llama.handler

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class RAGHandler(private val context: Context) {
    private lateinit var db: SQLiteDatabase
    private val embeddingProvider = SentenceEmbeddingProvider(context)
    private val tag = "RAGHandler"
    private val embeddingCache = mutableMapOf<String, FloatArray>()
    private val embeddingSize = 384 // Matches all-MiniLM-L6-v2

    init {
        initializeDatabase()
    }

    private fun initializeDatabase() {
        val dbFile = copyToLocalStorage("QAonnx.db")
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            Log.d(tag, "Database opened at: ${dbFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to open database: ${e.stackTraceToString()}")
        }
    }

    private fun copyToLocalStorage(filename: String): File {
        val storageFile = File(context.filesDir, filename)
        if (!storageFile.exists()) {
            try {
                storageFile.parentFile?.mkdirs()
                context.assets.open(filename).use { inputStream ->
                    FileOutputStream(storageFile).use { outputStream ->
                        val buffer = ByteArray(8192) // 8KB buffer
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                    }
                }
                Log.d(tag, "Copied $filename to ${storageFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(tag, "Failed to copy $filename: ${e.stackTraceToString()}")
            }
        }
        return storageFile
    }

    private suspend fun computeEmbedding(text: String): FloatArray {
        Log.d(tag, "Computing embedding for text: $text")
        embeddingCache[text]?.let { return it }
        return withContext(Dispatchers.Default) {
            try {
                val embedding = embeddingProvider.getEmbedding(text)
                if (embedding == null || embedding.size != embeddingSize) {
                    Log.w(tag, "Invalid embedding size: ${embedding?.size ?: 0}, expected $embeddingSize")
                    FloatArray(embeddingSize) { 0.0f }
                } else {
                    embeddingCache[text] = embedding
                    embedding
                }
            } catch (e: Exception) {
                Log.e(tag, "Error computing embedding: ${e.stackTraceToString()}")
                FloatArray(embeddingSize) { 0.0f }
            }
        }
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
        return if (normA > 0 && normB > 0) dotProduct / (sqrt(normA) * sqrt(normB)) else 0.0f
    }

    suspend fun retrieveRelevantContext(prompt: String, topK: Int = 3): List<String> {
        if (!this::db.isInitialized || !db.isOpen) {
            initializeDatabase()
            if (!this::db.isInitialized || !db.isOpen) {
                Log.e(tag, "Database not initialized, cannot retrieve context")
                return emptyList()
            }
        }

        return try {
            val promptEmbedding = computeEmbedding(prompt)
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
                        val similarity = cosineSimilarity(promptEmbedding, embedding)
                        results.add(similarity to text)
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to process embedding for text: $text, error: ${e.message}")
                        continue
                    }
                }
            }

            results.sortedByDescending { it.first }
                .take(topK)
                .map { it.second }
                .also { Log.d(tag, "Retrieved ${it.size} relevant contexts") }
        } catch (e: Exception) {
            Log.e(tag, "Error retrieving context: ${e.stackTraceToString()}")
            emptyList()
        }
    }

    fun close() {
        if (this::db.isInitialized && db.isOpen) {
            db.close()
        }
        embeddingProvider.close()
        Log.d(tag, "Resources closed")
    }
}

package com.example.llama.handler.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

class SentenceEmbeddingProvider constructor(private val context: Context) {
    private val ortEnv = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var tokenizer: SimpleTokenizer? = null
    private val isInitialized = AtomicBoolean(false)
    private val tag = "SentenceEmbeddingProvider"

    companion object {
        @Volatile
        private var instance: SentenceEmbeddingProvider? = null

        fun getInstance(context: Context): SentenceEmbeddingProvider {
            return instance ?: synchronized(this) {
                instance ?: SentenceEmbeddingProvider(context.applicationContext).also {
                    instance = it
                    it.initialize()
                }
            }
        }
    }

    private fun initialize() {
        if (!isInitialized.get()) {
            synchronized(this) {
                if (!isInitialized.get()) {
                    try {
                        // Load the ONNX model from assets
                        val modelPath = "all-MiniLM-L6-v2.onnx"
                        context.assets.open(modelPath).use { inputStream ->
                            val modelBytes = inputStream.readBytes()
                            session = ortEnv.createSession(modelBytes)
                        }
                        // Load the tokenizer
                        context.assets.open("tokenizer.json").use { stream ->
                            tokenizer = SimpleTokenizer(stream)
                        }
                        isInitialized.set(true)
                        Log.d(tag, "SentenceEmbeddingProvider initialized successfully")
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to initialize SentenceEmbeddingProvider: ${e.stackTraceToString()}")
                        throw IllegalStateException("Failed to initialize SentenceEmbeddingProvider: ${e.message}", e)
                    }
                }
            }
        }
    }

    // EmbeddingsHelper.kt (getEmbedding only)
    suspend fun getEmbedding(sentence: String): FloatArray? {
        if (!isInitialized.get()) {
            Log.e(tag, "SentenceEmbeddingProvider not initialized")
            throw IllegalStateException("SentenceEmbeddingProvider not initialized")
        }

        if (sentence.isBlank()) {
            Log.w(tag, "Empty input sentence")
            return null
        }

        return try {
            val tokenizer = tokenizer ?: throw IllegalStateException("Tokenizer not initialized")
            val session = session ?: throw IllegalStateException("Session not initialized")
            // Tokenize the input sentence
            val (inputIds, attentionMask) = tokenizer.encode(sentence)
            Log.d(tag, "Tokenized input: inputIds=${inputIds.take(10).joinToString()}, attentionMask=${attentionMask.take(10).joinToString()}")

            // Retry tensor creation and inference
            retryWithBackoff(3, 1000L) {
                OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(inputIds), longArrayOf(1, inputIds.size.toLong())).use { inputIdsTensor ->
                    OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(attentionMask), longArrayOf(1, attentionMask.size.toLong())).use { attentionMaskTensor ->
                        val inputs = mapOf(
                            "input_ids" to inputIdsTensor,
                            "attention_mask" to attentionMaskTensor
                        )
                        session.run(inputs).use { result ->
                            val outputTensor = result.get(0).value as Array<Array<FloatArray>>
                            val sequenceLength = outputTensor[0].size
                            val embeddingDim = 384
                            Log.d(tag, "Output tensor shape: [1, $sequenceLength, $embeddingDim]")

                            if (sequenceLength == 0) {
                                Log.w(tag, "Empty sequence length in output tensor")
                                return@use null
                            }

                            val embeddings = FloatArray(embeddingDim) { 0f }
                            for (j in 0 until embeddingDim) {
                                var sum = 0f
                                for (i in 0 until sequenceLength) {
                                    sum += outputTensor[0][i][j]
                                }
                                embeddings[j] = sum / sequenceLength
                            }
                            if (embeddings.all { it == 0.0f }) {
                                Log.w(tag, "Zero embedding generated for '$sentence'")
                                return@use null
                            }
                            Log.d(tag, "Generated embedding (first 10): ${embeddings.take(10).joinToString()}")
                            embeddings
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error generating embedding for '$sentence': ${e.stackTraceToString()}")
            null
        }
    }

    // Add retry utility at class level
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

    fun close() {
        if (isInitialized.get()) {
            try {
                session?.close()
                ortEnv.close()
                isInitialized.set(false)
                instance = null
                Log.d(tag, "SentenceEmbeddingProvider closed")
            } catch (e: Exception) {
                Log.e(tag, "Error closing SentenceEmbeddingProvider: ${e.stackTraceToString()}")
            }
        }
    }

    private class SimpleTokenizer(stream: InputStream) {
        private val vocab: Map<String, Int>
        private val maxLength = 128

        init {
            stream.use {
                val json = JSONObject(InputStreamReader(it).readText())
                vocab = json.getJSONObject("model").getJSONObject("vocab")
                    .keys().asSequence()
                    .associateWith { json.getJSONObject("model").getJSONObject("vocab").getInt(it) }
            }
        }

        fun encode(text: String): Pair<LongArray, LongArray> {
            val tokens = IntArray(maxLength) { vocab["[PAD]"] ?: 0 }
            val attentionMask = IntArray(maxLength) { 0 }
            var index = 0

            // Add [CLS] token
            tokens[index] = vocab["[CLS]"] ?: 101
            attentionMask[index] = 1
            index++

            // Tokenize words
            val words = text.lowercase().split("\\s+".toRegex())
            for (word in words) {
                if (index >= maxLength - 1) break
                tokens[index] = vocab[word] ?: vocab["[UNK]"] ?: 100
                attentionMask[index] = 1
                index++
            }

            // Add [SEP] token
            if (index < maxLength) {
                tokens[index] = vocab["[SEP]"] ?: 102
                attentionMask[index] = 1
            }

            return tokens.map { it.toLong() }.toLongArray() to attentionMask.map { it.toLong() }.toLongArray()
        }
    }
}

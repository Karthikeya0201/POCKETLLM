package com.example.llama.handler

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

class SentenceEmbeddingProvider(private val context: Context) {
    private val ortEnv = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer: SimpleTokenizer
    private val isInitialized = AtomicBoolean(false)

    init {
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
        } catch (e: Exception) {
            throw IllegalStateException("Failed to initialize SentenceEmbeddingProvider: ${e.message}", e)
        }
    }

    fun getEmbedding(sentence: String): FloatArray? {
        if (!isInitialized.get()) {
            throw IllegalStateException("SentenceEmbeddingProvider not initialized")
        }

        return try {
            // Tokenize the input sentence
            val (inputIds, attentionMask) = tokenizer.encode(sentence)
            // Create input tensors
            OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(inputIds), longArrayOf(1, inputIds.size.toLong())).use { inputIdsTensor ->
                OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(attentionMask), longArrayOf(1, attentionMask.size.toLong())).use { attentionMaskTensor ->
                    // Run the model
                    val inputs = mapOf(
                        "input_ids" to inputIdsTensor,
                        "attention_mask" to attentionMaskTensor
                    )
                    session.run(inputs).use { result ->
                        // Extract embeddings (shape: [1, sequence_length, 384])
                        val outputTensor = result.get(0).value as Array<Array<FloatArray>> // Shape: [1, sequence_length, 384]
                        val sequenceLength = outputTensor[0].size
                        val embeddingDim = 384
                        val embeddings = FloatArray(embeddingDim) { 0f }
                        // Mean pooling
                        for (j in 0 until embeddingDim) {
                            var sum = 0f
                            for (i in 0 until sequenceLength) {
                                sum += outputTensor[0][i][j]
                            }
                            embeddings[j] = sum / sequenceLength
                        }
                        embeddings
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun close() {
        if (isInitialized.get()) {
            session.close()
            ortEnv.close()
            isInitialized.set(false)
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

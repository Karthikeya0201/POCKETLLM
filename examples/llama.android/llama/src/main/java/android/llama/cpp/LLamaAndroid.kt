package android.llama.cpp

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class LLamaAndroid {
    private val tag: String? = this::class.simpleName
    private var userName: String = "User"
    private var assistantName: String = "Assistant"
    private var modelPath: String? = null

    private val threadLocalState: ThreadLocal<State> = ThreadLocal.withInitial { State.Idle }

    private val runLoop: CoroutineDispatcher = Executors.newSingleThreadExecutor {
        thread(start = false, name = "Llm-RunLoop") {
            Log.d(tag, "Dedicated thread for native code: ${Thread.currentThread().name}")

            System.loadLibrary("llama-android")

            log_to_android()
            backend_init(false)

            Log.d(tag, system_info())

            it.run()
        }.apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, exception: Throwable ->
                Log.e(tag, "Unhandled exception", exception)
            }
        }
    }.asCoroutineDispatcher()

    private val nlen: Int = 4096 // 4K response size
    private var contextSize: Int = 2048

    private external fun log_to_android()
    private external fun load_model(filename: String): Long
    private external fun free_model(model: Long)
    private external fun new_context(model: Long): Long
    private external fun new_context_with_size(model: Long, ctxSize: Int): Long
    private external fun free_context(context: Long)
    private external fun backend_init(numa: Boolean)
    private external fun backend_free()
    private external fun new_batch(nTokens: Int, embd: Int, nSeqMax: Int): Long
    private external fun free_batch(batch: Long)
    private external fun new_sampler(): Long
    private external fun free_sampler(sampler: Long)
    private external fun bench_model(
        context: Long,
        model: Long,
        batch: Long,
        pp: Int,
        tg: Int,
        pl: Int,
        nr: Int
    ): String

    private external fun system_info(): String

    private external fun completion_init(
        context: Long,
        batch: Long,
        text: String,
        formatChat: Boolean,
        nLen: Int
    ): Int

    private external fun completion_loop(
        context: Long,
        batch: Long,
        sampler: Long,
        nLen: Int,
        ncur: IntVar
    ): String?

    private external fun kv_cache_clear(context: Long)

    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String {
        return withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    Log.d(tag, "bench(): $state")
                    bench_model(state.context, state.model, state.batch, pp, tg, pl, nr)
                }
                else -> throw IllegalStateException("No model loaded")
            }
        }
    }

    suspend fun load(pathToModel: String) {
        withContext(runLoop) {
            // Check if a model is already loaded
            if (isModelLoaded()) {
                Log.d(tag, "A model is already loaded ($modelPath), unloading it first")
                try {
                    unload()
                    Log.d(tag, "Successfully unloaded the existing model")
                } catch (e: Exception) {
                    Log.e(tag, "Error unloading existing model: ${e.stackTraceToString()}")
                    throw IllegalStateException("Failed to unload existing model: ${e.message}")
                }
            }

            // Verify state is Idle before proceeding
            if (threadLocalState.get() !is State.Idle) {
                Log.e(tag, "State inconsistency: State is not Idle after unloading")
                throw IllegalStateException("State is not Idle after unloading")
            }

            // Load the new model
            val model = load_model(pathToModel)
            if (model == 0L) throw IllegalStateException("load_model failed")
            val context = new_context_with_size(model, contextSize)
            if (context == 0L) throw IllegalStateException("new_context failed")
            val batch = new_batch(2048, 0, 1)
            if (batch == 0L) throw IllegalStateException("new_batch failed")
            val sampler = new_sampler()
            if (sampler == 0L) throw IllegalStateException("new_sampler failed")
            Log.i(tag, "Loaded model $pathToModel with context size $contextSize")
            threadLocalState.set(State.Loaded(model, context, batch, sampler))
            modelPath = pathToModel
        }
    }

    fun setContextSize(size: Int) {
        require(size in 512..32768) { "Context size must be between 512 and 32768" }
        contextSize = size
    }

    fun send(message: String, formatChat: Boolean = false): Flow<String> = flow {
        when (val state = threadLocalState.get()) {
            is State.Loaded -> {
                val formattedMessage = if (formatChat) {
                    message
                } else {
                    message
                }
                Log.d("LLamaAndroid", "Sending message: $formattedMessage, formatChat=$formatChat")

                val ncur = IntVar(completion_init(state.context, state.batch, formattedMessage, formatChat, nlen))
                while (ncur.value <= nlen) {
                    val str = completion_loop(state.context, state.batch, state.sampler, nlen, ncur)
                    if (str == null) break
                    emit(str)
                }
                kv_cache_clear(state.context)
            }
            else -> {
                Log.d("LLamaAndroid", "Model not loaded, cannot send message")
            }
        }
    }.flowOn(runLoop)

    suspend fun unload() {
        withContext(runLoop) {
            when (val state = threadLocalState.get()) {
                is State.Loaded -> {
                    try {
                        free_context(state.context)
                        free_model(state.model)
                        free_batch(state.batch)
                        free_sampler(state.sampler)
                        Log.d(tag, "Successfully unloaded model: $modelPath")
                    } catch (e: Exception) {
                        Log.e(tag, "Error during unload: ${e.stackTraceToString()}")
                    } finally {
                        threadLocalState.set(State.Idle)
                        modelPath = null
                    }
                }
                else -> {
                    Log.d(tag, "No model to unload, state is already Idle")
                    threadLocalState.set(State.Idle)
                    modelPath = null
                }
            }
        }
    }

    fun isModelLoaded(): Boolean {
        return threadLocalState.get() is State.Loaded
    }

    fun initialize(userName: String, assistantName: String) {
        if (threadLocalState.get() !is State.Idle) {
            throw IllegalStateException("Cannot initialize after loading model")
        }
        this.userName = userName
        this.assistantName = assistantName
    }

    companion object {
        private class IntVar(value: Int) {
            @Volatile
            var value: Int = value
                private set

            fun inc() {
                synchronized(this) {
                    value += 1
                }
            }
        }

        private sealed interface State {
            data object Idle : State
            data class Loaded(
                val model: Long,
                val context: Long,
                val batch: Long,
                val sampler: Long
            ) : State
        }

        private val _instance: LLamaAndroid = LLamaAndroid()

        fun instance(): LLamaAndroid = _instance
    }
}

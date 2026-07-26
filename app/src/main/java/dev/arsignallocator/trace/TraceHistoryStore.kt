package dev.arsignallocator.trace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class TraceHistoryStore(
    internal val recorder: TraceRecorder,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutex = Mutex()
    private val _summaries = MutableStateFlow<List<TraceSummary>>(emptyList())
    val summaries: StateFlow<List<TraceSummary>> = _summaries.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private var initialized = false

    init {
        viewModelScope.launch { withRecorder {} }
    }

    internal suspend fun <T> withRecorder(
        refreshHistory: Boolean = false,
        block: () -> T,
    ): T = mutex.withLock {
        withContext(ioDispatcher) {
            initialize()
            block().also {
                if (refreshHistory) publish(recorder.completedTraces())
            }
        }
    }

    fun deleteTrace(file: File) {
        viewModelScope.launch {
            withRecorder {
                val deleted = recorder.deleteTrace(file)
                publish(
                    recorder.completedTraces(),
                    if (deleted) null else TRACE_DELETE_ERROR,
                )
            }
        }
    }

    fun deleteAllTraces() {
        viewModelScope.launch {
            withRecorder {
                recorder.deleteAllTraces()
                publish(
                    recorder.completedTraces(),
                    if (recorder.storageError == null) null else TRACE_DELETE_ALL_ERROR,
                )
            }
        }
    }

    private fun initialize() {
        if (initialized) return
        initialized = true
        val files = runCatching { recorder.initialize() }
        publish(
            files.getOrDefault(emptyList()),
            if (files.isFailure || recorder.storageError != null) HISTORY_READ_ERROR else null,
        )
    }

    private fun publish(files: List<File>, operationError: String? = null) {
        var invalid = false
        _summaries.value = files.take(10).mapNotNull { file ->
            runCatching { TraceSummaryReader.read(file) }.getOrElse {
                invalid = true
                null
            }
        }
        _error.value = operationError ?: HISTORY_READ_ERROR.takeIf { invalid }
    }

    override fun onCleared() {
        recorder.close()
    }

    private companion object {
        const val HISTORY_READ_ERROR = "Session history could not be read"
        const val TRACE_DELETE_ERROR = "The session could not be deleted"
        const val TRACE_DELETE_ALL_ERROR = "Some sessions could not be deleted"
    }
}

package com.example.sentriai.models

import android.content.Context
import android.util.Log
import com.example.sentriai.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Brings the on-device model set up to date and reports how far along that is.
 *
 * Required models gate the app: without Whisper there is no transcript, so there is nothing
 * for detection to read. The two Gemma models are fetched on the same pass but a failure to
 * get them is not fatal — detection degrades to the safe-word trigger rather than stopping,
 * though that leaves unprompted speech unmonitored.
 */
object ModelRepository {

    private const val TAG = "ModelRepository"

    sealed interface State {
        /** Working out what is already on disk. */
        data object Checking : State

        data class Downloading(
            val asset: ModelAsset,
            val fileIndex: Int,
            val fileCount: Int,
            val bytesDone: Long,
            val bytesTotal: Long,
        ) : State {
            /** 0f..1f for the file in flight, or null when the size is not yet known. */
            val fileProgress: Float?
                get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else null
        }

        /** Every required model is present. [missingOptional] lists any Gemma model that isn't. */
        data class Ready(val missingOptional: List<ModelAsset>) : State

        /** A required model could not be fetched. Retrying resumes rather than restarting. */
        data class Failed(val asset: ModelAsset, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = _state.asStateFlow()

    /** True once the required set is on disk, so callers can skip the setup screen entirely. */
    fun requiredModelsPresent(context: Context): Boolean =
        ModelCatalog.required.all { ModelStore.isAvailable(context, it) }

    /**
     * Downloads whatever is missing, in catalog order — smallest first, so an unreachable or
     * misconfigured URL fails in seconds rather than after a partial 280 MB transfer.
     *
     * Safe to call repeatedly; present files are skipped.
     */
    suspend fun ensureModels(context: Context) {
        _state.value = State.Checking

        val missing = ModelCatalog.all.filterNot { ModelStore.isAvailable(context, it) }
        if (missing.isEmpty()) {
            _state.value = State.Ready(emptyList())
            return
        }

        Log.i(TAG, "Missing models: ${missing.joinToString { it.fileName }}")
        val missingOptional = mutableListOf<ModelAsset>()

        missing.forEachIndexed { index, asset ->
            _state.value = State.Downloading(asset, index + 1, missing.size, 0, asset.sizeBytes ?: 0)

            val result = downloadFromBestSource(context, asset) { done, total ->
                _state.value = State.Downloading(asset, index + 1, missing.size, done, total)
            }

            if (result.isFailure) {
                val message = result.exceptionOrNull()?.message ?: "Download failed"
                if (asset.required) {
                    Log.e(TAG, "Required model ${asset.fileName} failed: $message")
                    _state.value = State.Failed(asset, message)
                    return
                }
                Log.w(TAG, "Optional model ${asset.fileName} failed: $message")
                missingOptional += asset
            }
        }

        _state.value = State.Ready(missingOptional)
    }

    /**
     * Fetches [asset] from whichever source can serve it.
     *
     * Hugging Face is tried first when the catalog knows a repo for the file, because that
     * needs no hosting of your own — `tokenizer.json` comes straight from `openai/whisper-tiny.en`
     * with no token at all. Configured storage is the fallback, which is the only route for the
     * files that exist nowhere public: the Whisper `.pte` exports.
     */
    private suspend fun downloadFromBestSource(
        context: Context,
        asset: ModelAsset,
        onProgress: (Long, Long) -> Unit,
    ): Result<File> {
        val token = BuildConfig.HF_TOKEN.ifBlank { null }
        val hubUrl = asset.huggingFaceUrl

        if (hubUrl != null) {
            val viaHub = ModelDownloader.downloadFrom(context, asset, hubUrl, token, onProgress)
            if (viaHub.isSuccess) return viaHub

            if (BuildConfig.MODEL_BASE_URL.isBlank()) return viaHub
            Log.w(TAG, "Hub download of ${asset.fileName} failed; trying configured storage")
        }

        if (BuildConfig.MODEL_BASE_URL.isBlank()) {
            return Result.failure(IllegalStateException(noSourceMessage(asset)))
        }
        return ModelDownloader.download(
            context = context,
            asset = asset,
            baseUrl = BuildConfig.MODEL_BASE_URL,
            authToken = token,
            onProgress = onProgress,
        )
    }

    /** Says which knob is missing, rather than naming a property the reader has to go look up. */
    private fun noSourceMessage(asset: ModelAsset): String =
        "No download source for ${asset.displayName}. Set modelBaseUrl in gradle.properties to " +
            "storage holding ${asset.fileName}, or serve model-hosting/ locally — see MODELS.md."
}

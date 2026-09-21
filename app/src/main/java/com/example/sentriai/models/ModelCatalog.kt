package com.example.sentriai.models

import com.example.sentriai.BuildConfig

/**
 * One model file the app needs on disk.
 *
 * @param fileName name under `filesDir`, and the name the loaders ask for.
 * @param displayName shown on the setup screen while this file downloads.
 * @param sizeBytes expected final size. Used for the progress bar before the server sends a
 *   Content-Length, and as a cheap "already complete" check. Null when unknown.
 * @param sha256 lowercase hex digest verified after download. Null skips verification, which
 *   is worth avoiding: a truncated or HTML-error-page download otherwise reaches the model
 *   loader and fails as an unreadable-model crash far from its real cause.
 * @param remotePath path appended to the download base URL.
 * @param required whether the app is unusable without it.
 * @param hfRepo Hugging Face repo id, e.g. `litert-community/Gemma3-1B-IT`. Set only for models
 *   the debug-only download button can pull directly from the hub.
 * @param hfFile file name within [hfRepo], which is often not the same as [fileName].
 * @param hfRevision branch or commit to resolve against.
 */
data class ModelAsset(
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val remotePath: String = fileName,
    val required: Boolean = true,
    val hfRepo: String? = null,
    val hfFile: String? = null,
    val hfRevision: String = "main",
) {
    /**
     * Direct hub download URL, or null when this model has no Hugging Face source.
     *
     * A gated repo answers this anonymously with `401 GatedRepo`, then redirects to a signed
     * CDN host once authenticated — which is why [ModelDownloader] follows redirects by hand
     * and drops the token on a host change.
     */
    val huggingFaceUrl: String?
        get() = if (hfRepo != null && hfFile != null) {
            "https://huggingface.co/$hfRepo/resolve/$hfRevision/$hfFile"
        } else {
            null
        }
}

/**
 * Everything the app downloads on first launch.
 *
 * Split by where each file comes from:
 *
 * - **Whisper ships in `assets/`.** No public repo has a `whisper-tiny.en` ExecuTorch export
 *   with the method signatures [com.example.sentriai.model_inference.speech_to_text.WhisperModel]
 *   expects, so these are local exports and bundling is the only route that needs no hosting.
 * - **Gemma downloads.** Both bundles are far too large to add to the APK on top of Whisper.
 *
 * Sizes and digests describe the files this app was developed against. They are only enforced
 * on a *download*; a bundled asset is trusted. Re-export Whisper or re-convert a Gemma bundle
 * and these must be updated too.
 */
object ModelCatalog {

    val WHISPER_MODEL = ModelAsset(
        fileName = "model.pte",
        displayName = "Speech recognition",
        sizeBytes = 198_506_112L,
        sha256 = "41edc13ef7c31dcb54cb532eb665840cb2efad36e52970fd5c84f5f2618bfb6b",
    )

    val WHISPER_PREPROCESSOR = ModelAsset(
        fileName = "whisper_preprocessor.pte",
        displayName = "Audio front-end",
        sizeBytes = 78_720L,
        sha256 = "2f21fa575483d07a2374bd521759ecdf67554e5423bb96b7b281dcb16fb13e47",
    )

    /**
     * Bundled like the rest of Whisper, but this one also has a working public URL, so it stays
     * recoverable from `openai/whisper-tiny.en` if the asset is ever stripped.
     */
    val WHISPER_TOKENIZER = ModelAsset(
        fileName = "tokenizer.json",
        displayName = "Tokenizer",
        sizeBytes = 2_405_679L,
        sha256 = "5eb60cec1e77aeeb6869a2bb5a8e01a84c3fe5d072d75369343021fe6f5310d0",
        hfRepo = "openai/whisper-tiny.en",
        hfFile = "tokenizer.json",
    )

    /**
     * Emergency classifier — the model that decides whether an utterance is an emergency.
     *
     * Optional only in the sense that the app still runs without it: detection falls back to
     * the safe word. Unprompted speech is not monitored without it, so ship it.
     */
    val CLASSIFIER = ModelAsset(
        fileName = "gemma3-1b-it-q4.task",
        displayName = "Emergency classifier",
        // Smallest q4 build in the repo. ekv2048 is the KV-cache size — ample for the ~300
        // token classification prompt, and cheaper in RAM than the ekv4096 variants.
        sizeBytes = 554_661_246L,
        sha256 = "ddfaf1210d8b4d1b812b5fadb6652999e852c8be6dd9abe353b9213a25262c10",
        required = false,
        hfRepo = "litert-community/Gemma3-1B-IT",
        hfFile = "Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task",
    )

    val all: List<ModelAsset> = listOf(
        WHISPER_PREPROCESSOR,
        WHISPER_TOKENIZER,
        WHISPER_MODEL,
        CLASSIFIER,
    )

    val required: List<ModelAsset> = all.filter { it.required }
}

package com.example.sentriai.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelCatalogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `file names are unique`() {
        val names = ModelCatalog.all.map { it.fileName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `required models can be integrity checked`() {
        // A required model with no digest would let a truncated download or an HTML error page
        // reach the ExecuTorch loader and fail there instead, with a useless message.
        ModelCatalog.required.forEach { asset ->
            assertNotNull("${asset.fileName} has no sha256", asset.sha256)
            assertNotNull("${asset.fileName} has no expected size", asset.sizeBytes)
        }
    }

    @Test
    fun `declared digests are well formed`() {
        ModelCatalog.all.mapNotNull { it.sha256 }.forEach { digest ->
            assertEquals(64, digest.length)
            assertTrue(digest.all { it in "0123456789abcdef" })
        }
    }

    @Test
    fun `whisper files are all required and gemma files are not`() {
        assertTrue(ModelCatalog.WHISPER_MODEL.required)
        assertTrue(ModelCatalog.WHISPER_PREPROCESSOR.required)
        assertTrue(ModelCatalog.WHISPER_TOKENIZER.required)
        // Detection degrades to keywords without these rather than stopping.
        assertTrue(!ModelCatalog.CLASSIFIER.required)
    }

    @Test
    fun `smallest required files download first so a bad URL fails fast`() {
        val requiredSizes = ModelCatalog.all
            .filter { it.required }
            .map { it.sizeBytes ?: Long.MAX_VALUE }
        assertEquals(requiredSizes.sorted(), requiredSizes)
    }

    @Test
    fun `hugging face url resolves to the hub download endpoint`() {
        assertEquals(
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/" +
                "Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task",
            ModelCatalog.CLASSIFIER.huggingFaceUrl,
        )
    }

    @Test
    fun `an asset with no repo configured has no url`() {
        // Drives the null branch directly rather than through a catalog entry, whose repo now
        // depends on whether hfModelRepo is set at build time.
        val local = ModelAsset(fileName = "model.pte", displayName = "Speech recognition")
        assertEquals(null, local.huggingFaceUrl)
    }

    @Test
    fun `whisper executorch exports are never fetched from the hub`() {
        // No public repo has a whisper-tiny.en ExecuTorch export with the method signatures
        // WhisperModel expects, so these must come from assets/ and never from a URL.
        assertEquals(null, ModelCatalog.WHISPER_MODEL.huggingFaceUrl)
        assertEquals(null, ModelCatalog.WHISPER_PREPROCESSOR.huggingFaceUrl)
    }

    @Test
    fun `hub file name may differ from the local file name`() {
        // ModelStore looks for CLASSIFIER.fileName on disk, so a mismatch here would mean the
        // download lands and is then never found.
        val asset = ModelCatalog.CLASSIFIER
        assertEquals("gemma3-1b-it-q4.task", asset.fileName)
        assertTrue(asset.huggingFaceUrl!!.endsWith(asset.hfFile!!))
    }

    @Test
    fun `sha256 matches a known digest`() {
        val file = tempFolder.newFile("probe.bin")
        file.writeText("sentriai")
        // printf 'sentriai' | shasum -a 256
        assertEquals(
            "c17584da990d0db20b146fbeb5291cdf663f55f9940d59a932b35ed903e8ac06",
            ModelStore.sha256(file),
        )
    }

    @Test
    fun `sha256 streams correctly across buffer boundaries`() {
        // The digest is computed in 64 KB chunks; a file larger than one chunk proves the
        // update loop is fed every byte rather than only the first read.
        val file = tempFolder.newFile("large.bin")
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        file.writeBytes(payload)

        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, ModelStore.sha256(file))
    }

    @Test
    fun `sha256 of an empty file is the known empty digest`() {
        val file = tempFolder.newFile("empty.bin")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ModelStore.sha256(file),
        )
    }
}

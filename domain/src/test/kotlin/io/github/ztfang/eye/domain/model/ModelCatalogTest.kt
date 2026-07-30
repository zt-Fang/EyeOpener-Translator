package io.github.ztfang.eye.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ModelCatalog 模型目录与命名函数测试。 */
class ModelCatalogTest {

    @Test
    fun `voskModelName generates uppercase id`() {
        assertEquals("VOSK_ASR_ZH", ModelCatalog.voskModelName("zh"))
        assertEquals("VOSK_ASR_EN", ModelCatalog.voskModelName("en"))
        assertEquals("VOSK_ASR_EN-IN", ModelCatalog.voskModelName("en-in"))
        assertEquals("VOSK_ASR_JA", ModelCatalog.voskModelName("ja"))
    }

    @Test
    fun `sherpaOnnxModelName generates prefixed id`() {
        val modelId = SherpaOnnxModel.X_ASR_ZH_EN_960MS.modelId
        assertEquals("SHERPA_ONNX_ASR_$modelId", ModelCatalog.sherpaOnnxModelName(modelId))
    }

    @Test
    fun `VOSK_MODELS map covers all 33 Vosk languages`() {
        assertEquals(33, ModelCatalog.VOSK_MODELS.size)
        assertNotNull(ModelCatalog.VOSK_MODELS["zh"])
        assertNotNull(ModelCatalog.VOSK_MODELS["en"])
        assertNotNull(ModelCatalog.VOSK_MODELS["en-in"])
        assertNotNull(ModelCatalog.VOSK_MODELS["ja"])
    }

    @Test
    fun `SHERPA_ONNX_MODELS map covers all 3 Sherpa models`() {
        assertEquals(3, ModelCatalog.SHERPA_ONNX_MODELS.size)
    }

    @Test
    fun `voskZipSpec returns spec by lowercase code`() {
        val spec = ModelCatalog.voskZipSpec("zh")
        assertNotNull(spec)
        assertEquals("vosk/vosk-model-small-cn-0.22.zip", spec!!.relativePath)
        assertTrue(spec.url.startsWith("https://"))
    }

    @Test
    fun `sherpaOnnxTarSpec returns spec for tar bz2 models`() {
        val bnSpec = ModelCatalog.sherpaOnnxTarSpec(SherpaOnnxModel.BN_VOSK_2026_02_09.modelId)
        assertNotNull(bnSpec)
        assertTrue(bnSpec!!.url.contains("tar.bz2"))
    }

    @Test
    fun `sherpaOnnxFileSpecs returns multi-file list for X-ASR`() {
        val files = ModelCatalog.sherpaOnnxFileSpecs(SherpaOnnxModel.X_ASR_ZH_EN_960MS.modelId)
        assertNotNull(files)
        assertEquals(4, files!!.size)
    }

    @Test
    fun `sherpaOnnxFileSpecs returns null for BN Vosk tar model`() {
        // BN Vosk 走 tar.bz2，files 为 null
        val files = ModelCatalog.sherpaOnnxFileSpecs(SherpaOnnxModel.BN_VOSK_2026_02_09.modelId)
        assertNull(files)
    }

    @Test
    fun `totalSizeBytes returns expected for VOSK prefix`() {
        val size = ModelCatalog.totalSizeBytes("VOSK_ASR_ZH")
        assertTrue("Vosk zh size 应 > 0", size > 0)
    }

    @Test
    fun `totalSizeBytes returns expected for SHERPA_ONNX prefix`() {
        val size = ModelCatalog.totalSizeBytes(
            "SHERPA_ONNX_ASR_${SherpaOnnxModel.X_ASR_ZH_EN_960MS.modelId}"
        )
        assertTrue("X-ASR size 应 > 0", size > 0)
    }

    @Test
    fun `totalSizeBytes returns 0 for unknown prefix`() {
        assertEquals(0L, ModelCatalog.totalSizeBytes("UNKNOWN_PREFIX"))
    }

    @Test
    fun `filesFor returns list for VOSK prefix`() {
        val files = ModelCatalog.filesFor("VOSK_ASR_ZH")
        assertEquals(1, files.size)
    }

    @Test
    fun `filesFor returns empty for unknown name`() {
        val files = ModelCatalog.filesFor("UNKNOWN_NAME")
        assertTrue(files.isEmpty())
    }

    @Test
    fun `MODEL_MLKIT and MODEL_VAD constants are stable`() {
        assertEquals("MLKIT_TRANSLATION", ModelCatalog.MODEL_MLKIT)
        assertEquals("WEBRTC_VAD", ModelCatalog.MODEL_VAD)
    }
}

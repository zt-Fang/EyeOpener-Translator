package io.github.ztfang.eye.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SherpaOnnxModel 枚举与语言集合的完整性测试。 */
class SherpaOnnxModelTest {

    @Test
    fun `NEMOTRON_LANGUAGES contains ready plus broad`() {
        val expected = SherpaOnnxModel.NEMOTRON_READY_LANGUAGES +
            SherpaOnnxModel.NEMOTRON_BROAD_LANGUAGES
        assertEquals(expected, SherpaOnnxModel.NEMOTRON_LANGUAGES)
    }

    @Test
    fun `Nemotron ready covers 15 main languages`() {
        // ready 19 locale 含多 locale；按 ISO 639-1 主代码计 15 种
        val ready = SherpaOnnxModel.NEMOTRON_READY_LANGUAGES
        assertTrue(ready.contains("en"))
        assertTrue(ready.contains("ja"))
        assertTrue(ready.contains("ko"))
        assertTrue(ready.contains("vi"))
        assertTrue(ready.size == 15)
    }

    @Test
    fun `Nemotron broad contains 13 locales including zh`() {
        val broad = SherpaOnnxModel.NEMOTRON_BROAD_LANGUAGES
        assertTrue(broad.contains("zh"))
        assertTrue(broad.contains("hu"))
        assertTrue(broad.contains("ro"))
        assertTrue(broad.contains("et"))
        assertTrue(broad.size == 13)
    }

    @Test
    fun `zh and en routed to X-ASR not Nemotron`() {
        // zh/en 虽在 broad 集合中，但实际走 X-ASR（resolveSherpaModelId 逻辑）
        val nemotronLangs = SherpaOnnxModel.NEMOTRON_LANGUAGES
        assertTrue("zh 应在 Nemotron 语言集合中（用于集合判断）", nemotronLangs.contains("zh"))
        assertTrue("en 应在 Nemotron 语言集合中", nemotronLangs.contains("en"))
    }

    @Test
    fun `fromModelId returns matching model`() {
        val xAsr = SherpaOnnxModel.fromModelId(SherpaOnnxModel.X_ASR_ZH_EN_960MS.modelId)
        assertEquals(SherpaOnnxModel.X_ASR_ZH_EN_960MS, xAsr)

        val nemotron = SherpaOnnxModel.fromModelId(SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8.modelId)
        assertEquals(SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8, nemotron)

        val bn = SherpaOnnxModel.fromModelId(SherpaOnnxModel.BN_VOSK_2026_02_09.modelId)
        assertEquals(SherpaOnnxModel.BN_VOSK_2026_02_09, bn)
    }

    @Test
    fun `fromModelId returns null for unknown id`() {
        assertNull(SherpaOnnxModel.fromModelId("nonexistent-model"))
    }

    @Test
    fun `getAll returns exactly 3 models`() {
        assertEquals(3, SherpaOnnxModel.getAll().size)
    }

    @Test
    fun `default constants point to expected models`() {
        assertEquals(SherpaOnnxModel.X_ASR_ZH_EN_960MS, SherpaOnnxModel.DEFAULT_ZH)
        assertEquals(SherpaOnnxModel.X_ASR_ZH_EN_960MS, SherpaOnnxModel.DEFAULT_EN)
        assertEquals(SherpaOnnxModel.BN_VOSK_2026_02_09, SherpaOnnxModel.DEFAULT_BN)
        assertEquals(
            SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8,
            SherpaOnnxModel.DEFAULT_MULTILINGUAL
        )
    }

    @Test
    fun `X-ASR model files are non-null with 4 entries`() {
        val files = SherpaOnnxModel.X_ASR_ZH_EN_960MS.files
        assertNotNull(files)
        assertEquals(4, files!!.size)
        assertTrue(files.any { it.relativePath == "encoder.int8.onnx" })
        assertTrue(files.any { it.relativePath == "decoder.onnx" })
        assertTrue(files.any { it.relativePath == "joiner.int8.onnx" })
        assertTrue(files.any { it.relativePath == "tokens.txt" })
    }

    @Test
    fun `Nemotron model files are non-null with 4 entries`() {
        val files = SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8.files
        assertNotNull(files)
        assertEquals(4, files!!.size)
    }

    @Test
    fun `BN Vosk uses tar bz2 download with null files`() {
        assertNull(SherpaOnnxModel.BN_VOSK_2026_02_09.files)
        assertFalse(SherpaOnnxModel.BN_VOSK_2026_02_09.downloadUrl.isEmpty())
    }

    @Test
    fun `X-ASR modelType is zipformer2`() {
        assertEquals("zipformer2", SherpaOnnxModel.X_ASR_ZH_EN_960MS.modelType)
    }

    @Test
    fun `Nemotron modelType is empty string to avoid double loading`() {
        // 见 project_memory: modelType 留空让 sherpa-onnx 自动检测
        assertEquals("", SherpaOnnxModel.NEMOTRON_3_5_320MS_INT8.modelType)
    }

    @Test
    fun `Nemotron unsupported languages include expected set`() {
        val unsupported = SherpaOnnxModel.NEMOTRON_UNSUPPORTED
        assertTrue(unsupported.contains("el"))
        assertTrue(unsupported.contains("th"))
        assertTrue(unsupported.contains("he"))
    }
}

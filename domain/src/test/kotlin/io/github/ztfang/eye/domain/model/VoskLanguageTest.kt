package io.github.ztfang.eye.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** VoskLanguage 枚举完整性测试。 */
class VoskLanguageTest {

    @Test
    fun `getAll returns 33 languages including en-in variant`() {
        val all = VoskLanguage.getAll()
        assertEquals(33, all.size)
        assertTrue(all.any { it.code == "en-in" })
    }

    @Test
    fun `fromCode finds language case insensitive`() {
        assertEquals(VoskLanguage.CHINESE, VoskLanguage.fromCode("zh"))
        assertEquals(VoskLanguage.CHINESE, VoskLanguage.fromCode("ZH"))
        assertEquals(VoskLanguage.CHINESE, VoskLanguage.fromCode("Zh"))
        assertEquals(VoskLanguage.ENGLISH, VoskLanguage.fromCode("en"))
        assertEquals(VoskLanguage.ENGLISH_INDIA, VoskLanguage.fromCode("en-in"))
        assertEquals(VoskLanguage.JAPANESE, VoskLanguage.fromCode("ja"))
        assertEquals(VoskLanguage.KOREAN, VoskLanguage.fromCode("ko"))
    }

    @Test
    fun `fromCode returns null for unknown language`() {
        assertNull(VoskLanguage.fromCode("xx"))
        assertNull(VoskLanguage.fromCode("nonexistent"))
    }

    @Test
    fun `all language codes are unique`() {
        val codes = VoskLanguage.getAll().map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `all model names are unique`() {
        val names = VoskLanguage.getAll().map { it.modelName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `all model URLs start with https`() {
        VoskLanguage.getAll().forEach { lang ->
            assertTrue(
                "${lang.name} URL 应为 https 开头: ${lang.modelUrl}",
                lang.modelUrl.startsWith("https://")
            )
        }
    }

    @Test
    fun `all sizes are positive`() {
        VoskLanguage.getAll().forEach { lang ->
            assertTrue(
                "${lang.name} sizeBytes 应 > 0",
                lang.sizeBytes > 0
            )
        }
    }

    @Test
    fun `Chinese and English have mlkit support`() {
        assertTrue(VoskLanguage.CHINESE.mlkitSupported)
        assertTrue(VoskLanguage.ENGLISH.mlkitSupported)
    }

    @Test
    fun `Esperanto and Tajik do not have mlkit support`() {
        assertFalse(VoskLanguage.ESPERANTO.mlkitSupported)
        assertFalse(VoskLanguage.TAJIK.mlkitSupported)
        assertFalse(VoskLanguage.KYRGYZ.mlkitSupported)
        assertFalse(VoskLanguage.GEORGIAN.mlkitSupported)
        assertFalse(VoskLanguage.BRETON.mlkitSupported)
    }

    @Test
    fun `Bengali model is not in VoskLanguage enum`() {
        // 孟加拉语走 SherpaOnnxModel.BN_VOSK_2026_02_09，不在 VoskLanguage 中
        assertNull(VoskLanguage.fromCode("bn"))
    }
}

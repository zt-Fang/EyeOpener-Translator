package io.github.ztfang.eye.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 解压路径校验（防 Zip Slip / Tar Slip）单元测试。
 *
 * 对应审查报告 P1-21：压缩包内条目名可含 `../`，直接 `File(destDir, entryName)`
 * 会把文件写到 destDir 之外。这里验证 [resolveEntryFile] 的放行与拦截行为。
 */
class SafePathTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var destDir: File

    private fun setUpDest(): File {
        destDir = File(tmp.root, "models").apply { mkdirs() }
        return destDir
    }

    @Test
    fun `普通相对路径解析到 destDir 内`() {
        val dest = setUpDest()
        val out = resolveEntryFile(dest, "am/final.mdl")
        assertEquals(File(dest, "am/final.mdl").canonicalPath, out.path)
        assertTrue(out.path.startsWith(dest.canonicalPath + File.separator))
    }

    @Test
    fun `单层上跳被拒绝`() {
        val dest = setUpDest()
        val ex = runCatching { resolveEntryFile(dest, "../evil.txt") }.exceptionOrNull()
        assertTrue("应抛出 IOException，实际: $ex", ex is java.io.IOException)
    }

    @Test
    fun `深层上跳被拒绝`() {
        val dest = setUpDest()
        val ex = runCatching { resolveEntryFile(dest, "a/b/../../../../outside.txt") }.exceptionOrNull()
        assertTrue("应抛出 IOException，实际: $ex", ex is java.io.IOException)
    }

    @Test
    fun `绝对路径被约束在 destDir 内`() {
        val dest = setUpDest()
        // File(parent, "/etc/passwd") 在 JVM 上会拼接为 parent + "/etc/passwd"，
        // 因此这里断言结果仍位于 destDir 之下（而非落到真实 /etc）。
        val out = resolveEntryFile(dest, "/etc/passwd")
        assertTrue(out.path.startsWith(dest.canonicalPath + File.separator))
    }

    @Test
    fun `destDir 自身条目被允许`() {
        val dest = setUpDest()
        // 目录条目为 "." 或空串时，归一化结果就是 destDir 本身
        val out = resolveEntryFile(dest, ".")
        assertEquals(dest.canonicalPath, out.path)
    }

    @Test
    fun `名字里含点点但未越界的文件被允许`() {
        val dest = setUpDest()
        val out = resolveEntryFile(dest, "model..v2/weights.bin")
        assertTrue(out.path.startsWith(dest.canonicalPath + File.separator))
    }
}

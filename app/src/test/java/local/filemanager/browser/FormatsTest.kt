package local.filemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsTest {
    @Test
    fun 載せた拡張子はどれも実際に開ける種類として扱われる() {
        val bad = SupportedFormats.extensions.filter { ext ->
            val name = "sample.$ext"
            PreviewKind.of(name) == null &&
                FileOps.kindOf(name) !in setOf(FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO) &&
                Archives.typeOf(name) == null
        }
        assertTrue("開けない形式が載っている: $bad", bad.isEmpty())
    }

    @Test
    fun 同じ拡張子を二重に載せない() {
        val all = SupportedFormats.groups.flatMap { g -> g.rows.flatMap { it.exts } }
        assertEquals("重複: ${all.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}", all.size, all.toSet().size)
    }

    @Test
    fun 主な形式が載っている() {
        for (e in listOf("pdf", "docx", "xlsx", "pptx", "zip", "7z", "rar", "lzh", "mp4", "mp3", "jpg", "png", "stl", "dxf", "apk", "sqlite", "txt")) {
            assertTrue("$e が載っていない", e in SupportedFormats.extensions)
        }
    }
}
package org.filemat.server.module.file.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class FilePathTest {

    @Test
    fun `filename is the last segment`() {
        assertEquals("report.txt", FilePath.of("/data/report.txt").filename)
        assertEquals("data", FilePath.of("/data/").filename)
    }

    @Test
    fun `filename of the root is the root itself`() {
        val root = FilePath.of("/")

        assertEquals(root.pathString, root.filename)
    }

    @Test
    fun `filename keeps characters that are special in URLs`() {
        assertEquals("a#b%c&d+e", FilePath.of("/x/a#b%c&d+e").filename)
    }

    @Test
    fun `paths are normalized before comparing`() {
        assertEquals(FilePath.of("/a/b"), FilePath.of("/a/./c/../b/"))
        assertEquals(FilePath.of("/a/b").hashCode(), FilePath.of("a//b").hashCode())
        assertNotEquals(FilePath.of("/a/b"), FilePath.of("/a/bb"))
    }

    @Test
    fun `ofAlreadyNormalized keeps the path as given`() {
        val path = Paths.get("/a/b").toAbsolutePath()

        assertEquals(path, FilePath.ofAlreadyNormalized(path).path)
    }

    @Test
    fun `startsWith compares whole segments`() {
        val path = FilePath.of("/a/bc")

        assertEquals(true, path.startsWith(FilePath.of("/a")))
        assertEquals(false, path.startsWith(FilePath.of("/a/b")))
    }
}

package org.filemat.server.config.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class PlatformTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `Windows values`() {
        assertEquals(true, Platform.isWindows)
        assertEquals("windows", Platform.pathStyle)
        assertEquals("\\", Platform.separator)
        assertEquals(";", Platform.listSeparator)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `POSIX values`() {
        assertEquals(false, Platform.isWindows)
        assertEquals("posix", Platform.pathStyle)
        assertEquals("/", Platform.separator)
        assertEquals(":", Platform.listSeparator)
    }
}

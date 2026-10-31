package org.filemat.server.config.platform

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.diagnostics.FailureAnalyzer
import org.springframework.core.io.support.SpringFactoriesLoader

class WindowsPortFailureAnalyzerTest {

    @Test
    fun `the hint names the reserved port ranges`() {
        val analysis = WindowsPortFailureAnalyzer.analysis(8080)

        assertTrue(analysis.description.contains("port 8080"))
        assertTrue(analysis.action.contains("excludedportrange"))
        assertTrue(analysis.action.contains("SERVER_PORT"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `Spring Boot finds the analyzer`() {
        val names = SpringFactoriesLoader.loadFactoryNames(FailureAnalyzer::class.java, javaClass.classLoader)

        assertTrue(WindowsPortFailureAnalyzer::class.java.name in names)
    }
}

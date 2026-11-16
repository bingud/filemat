package org.filemat.server

import org.filemat.server.config.platform.Platform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StopWithLauncherTest {

    private val launcherEnv = mapOf("FM_STOP_WITH_LAUNCHER" to "true")

    /**
     * A process that waits for a line of input, like the `cmd.exe` that runs `filemat.cmd` waits for Java
     */
    private fun startLauncher(): Process {
        val command = if (Platform.isWindows) listOf("cmd", "/c", "set /p line=") else listOf("sh", "-c", "read line")
        return ProcessBuilder(command).start()
    }

    @Test
    fun `Filemat stops when the process that started it ends`() {
        val launcher = startLauncher()
        val stopped = CountDownLatch(1)
        try {
            stopWithLauncher(launcherEnv, launcher.toHandle()) { stopped.countDown() }
            assertEquals(1, stopped.count, "Stopped while the launcher was still running")

            // What ending the scheduled task does
            launcher.destroyForcibly()

            assertTrue(stopped.await(10, TimeUnit.SECONDS))
        } finally {
            launcher.destroyForcibly()
        }
    }

    @Test
    fun `a start without filemat cmd is left alone`() {
        val launcher = startLauncher()
        val stopped = CountDownLatch(1)

        stopWithLauncher(emptyMap(), launcher.toHandle()) { stopped.countDown() }
        stopWithLauncher(launcherEnv, launcher = null) { stopped.countDown() }
        launcher.destroyForcibly().waitFor()

        assertEquals(false, stopped.await(500, TimeUnit.MILLISECONDS))
    }
}

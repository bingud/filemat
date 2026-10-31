package org.filemat.server.module.file.service.file.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

/**
 * The FFmpeg slots (R12). Needs no ffmpeg: the slot is taken before a process starts.
 */
class ExternalFfmpegBackendSlotTest {

    @TempDir
    lateinit var tempDir: Path

    private val file get() = tempDir.resolve("clip.mp4")

    private fun elapsed(action: () -> Unit): Duration {
        val start = System.nanoTime()
        action()
        return Duration.ofNanos(System.nanoTime() - start)
    }

    @Test
    fun `with every slot taken, a frame and a probe get the busy error after the wait`() {
        val backend = ExternalFfmpegBackend(tempDir.resolve("ffmpeg"), maxProcesses = 1, slotWait = Duration.ofMillis(100))
        backend.slots.acquire()

        val time = elapsed {
            assertThrows(FfmpegBusyException::class.java) { backend.firstFrame(file) }
            // Not taken for "no metadata", which would be cached
            assertThrows(FfmpegBusyException::class.java) { backend.probe(file) }
        }

        assertTrue(time < Duration.ofSeconds(2), "took $time")
    }

    @Test
    fun `the default wait for a slot is about two seconds`() {
        val backend = ExternalFfmpegBackend(tempDir.resolve("ffmpeg"), maxProcesses = 1)
        backend.slots.acquire()

        val time = elapsed { assertThrows(FfmpegBusyException::class.java) { backend.firstFrame(file) } }

        assertTrue(time >= Duration.ofMillis(1900) && time < Duration.ofSeconds(5), "took $time")
    }

    @Test
    fun `a run that fails gives its slot back`() {
        val backend = ExternalFfmpegBackend(tempDir.resolve("missing-ffmpeg"), maxProcesses = 1, slotWait = Duration.ofMillis(100))

        assertNull(backend.probe(file))

        assertEquals(1, backend.slots.availablePermits())
    }
}

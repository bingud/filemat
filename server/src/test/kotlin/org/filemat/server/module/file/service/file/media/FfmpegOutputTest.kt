package org.filemat.server.module.file.service.file.media

import org.filemat.server.module.file.model.FileContentMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FfmpegOutputTest {

    @Test
    fun `reads the input's size and duration, not the output's`() {
        val log = """
            Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'file:C:\Videos\clip.mp4':
              Metadata:
                major_brand     : isom
              Duration: 00:01:02.50, start: 0.000000, bitrate: 1234 kb/s
              Stream #0:0[0x1](und): Video: h264 (High) (avc1 / 0x31637661), yuv420p(tv, bt709, progressive), 1920x1080 [SAR 1:1 DAR 16:9], 1000 kb/s, 30 fps
              Stream #0:1[0x2](und): Audio: aac (LC) (mp4a / 0x6134706D), 48000 Hz, stereo, fltp, 128 kb/s
            Stream mapping:
              Stream #0:0 -> #0:0 (h264 (native) -> bmp (native))
            Output #0, image2pipe, to 'pipe:1':
              Stream #0:0: Video: bmp, bgr24(progressive), 64x48 [SAR 1:1 DAR 4:3], q=2-31
        """.trimIndent()

        assertEquals(FileContentMetadata(width = 1920, height = 1080, durationMs = 62_500), FfmpegOutput.metadata(log))
    }

    @Test
    fun `a codec tag is not a size`() {
        val log = """
            Input #0, avi, from 'file:/videos/raw.avi':
              Duration: 00:00:01.00, start: 0.000000, bitrate: 1 kb/s
              Stream #0:0: Video: rawvideo ([0][0][0][0] / 0x0000), bgr24, 320x240, 10 fps
        """.trimIndent()

        assertEquals(FileContentMetadata(width = 320, height = 240, durationMs = 1000), FfmpegOutput.metadata(log))
    }

    @Test
    fun `audio has a duration only`() {
        val log = """
            Input #0, wav, from 'file:/music/song.wav':
              Duration: 00:00:03.20, bitrate: 1411 kb/s
              Stream #0:0: Audio: pcm_s16le ([1][0][0][0] / 0x0001), 44100 Hz, 2 channels, s16, 1411 kb/s
            At least one output file must be specified
        """.trimIndent()

        assertEquals(FileContentMetadata(durationMs = 3200), FfmpegOutput.metadata(log))
    }

    @Test
    fun `nothing readable is null`() {
        val log = """
            Input #0, png_pipe, from 'file:/x.png':
              Duration: N/A, bitrate: N/A
            file:/x.png: Invalid data found when processing input
        """.trimIndent()

        assertNull(FfmpegOutput.metadata(log))
        assertNull(FfmpegOutput.metadata(""))
    }
}

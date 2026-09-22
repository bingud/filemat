package org.filemat.server.config.auth

import org.filemat.server.module.file.controller.FileController
import org.filemat.server.module.file.controller.FileUtilController
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Keeps `@Cors` on the public file reads the filter treats as open. */
class CorsAnnotationTest {

    @Test
    fun `public file reads opt into anonymous CORS`() {
        assertCors(FileController::class.java, "streamFileContentMapping")
        assertCors(FileController::class.java, "streamMultipleContentZipMapping")
        assertCors(FileUtilController::class.java, "imageThumbnailMapping")
        assertCors(FileUtilController::class.java, "videoPreviewMapping")
    }

    private fun assertCors(type: Class<*>, method: String) {
        val annotated = type.declaredMethods.any { candidate ->
            candidate.name == method && candidate.getAnnotation(Cors::class.java) != null
        }
        assertTrue(annotated, "$method is missing @Cors")
    }
}

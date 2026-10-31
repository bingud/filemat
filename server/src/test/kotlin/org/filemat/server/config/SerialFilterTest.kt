package org.filemat.server.config

import me.desair.tus.server.upload.UploadId
import me.desair.tus.server.upload.UploadInfo
import me.desair.tus.server.upload.UploadType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.net.URI
import java.util.UUID

class SerialFilterTest {

    private fun serialize(value: Serializable): ByteArray {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(value) }
        return bytes.toByteArray()
    }

    private fun deserialize(bytes: ByteArray): Any? {
        return ObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.setObjectInputFilter(SerialFilter.filter)
            it.readObject()
        }
    }

    @Test
    fun `an upload's info is read`() {
        val uuid = UUID.randomUUID()
        val info = UploadInfo().apply {
            id = UploadId(uuid)
            offset = 5L
            length = 10L
            encodedMetadata = "filename dGVzdA=="
            ownerKey = "owner"
            uploadType = UploadType.PARTIAL
            concatenationPartIds = arrayListOf("a", "b")
        }

        val read = deserialize(serialize(info)) as UploadInfo

        assertEquals(uuid.toString(), read.id.originalObject.toString())
        assertEquals(5L, read.offset)
        assertEquals(listOf("a", "b"), read.concatenationPartIds)
        assertEquals(UploadType.PARTIAL, read.uploadType)
    }

    @Test
    fun `other classes are refused`() {
        assertThrows<InvalidClassException> { deserialize(serialize(URI.create("https://example.com").toURL())) }
        assertThrows<InvalidClassException> { deserialize(serialize(File("x"))) }
        // Also when they are inside an allowed class
        assertThrows<InvalidClassException> { deserialize(serialize(hashMapOf("a" to File("x")))) }
    }
}

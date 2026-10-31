package org.filemat.server.config

import java.io.ObjectInputFilter

/**
 * The JVM-wide filter for Java deserialization.
 *
 * The only Java deserialization is tus reading `uploads/<id>/info` from the upload folder, and users may be able to write there
 * (an upload folder inside an exposed folder, or `/tmp/filemat` created by another local user).
 * So only the classes of an upload's info are accepted, and everything else is refused before it is created.
 */
object SerialFilter {
    const val PATTERN = "maxdepth=20;maxrefs=1000;maxarray=10000;maxbytes=1048576;" +
            "me.desair.tus.server.upload.*;java.lang.*;java.util.*;!*"

    val filter: ObjectInputFilter = ObjectInputFilter.Config.createFilter(PATTERN)

    /**
     * Sets the filter for the whole JVM, once. A filter set before (`-Djdk.serialFilter`) is kept.
     */
    @Synchronized
    fun install() {
        if (ObjectInputFilter.Config.getSerialFilter() != null) return
        ObjectInputFilter.Config.setSerialFilter(filter)
    }
}

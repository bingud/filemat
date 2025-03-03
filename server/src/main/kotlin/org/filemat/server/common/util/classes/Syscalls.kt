package org.filemat.server.common.util.classes

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Structure
import com.sun.jna.ptr.LongByReference

object Syscalls {

    /**
     * JNA interface to C library.
     */
    private interface CLib : Library {
        fun stat(path: String, buf: Stat): Int
    }

    /**
     * Data structure matching Linux's `stat`. This layout is valid
     * on many 64-bit Linux systems, but may require adjustments for
     * other OSes or architectures.
     */
    @Structure.FieldOrder(
        "st_dev",
        "st_ino",
        "st_nlink",
        "st_mode",
        "st_uid",
        "st_gid",
        "st_rdev",
        "pad1",
        "st_size",
        "st_blksize",
        "st_blocks",
        "st_atime",
        "st_atimensec",
        "st_mtime",
        "st_mtimensec",
        "st_ctime",
        "st_ctimensec",
        "unused4",
        "unused5"
    )
    class Stat : Structure() {
        @JvmField var st_dev:      NativeLong? = null
        @JvmField var st_ino:      NativeLong? = null
        @JvmField var st_nlink:    NativeLong? = null
        @JvmField var st_mode:     Int         = 0
        @JvmField var st_uid:      Int         = 0
        @JvmField var st_gid:      Int         = 0
        @JvmField var st_rdev:     NativeLong? = null
        @JvmField var pad1:        NativeLong? = null
        @JvmField var st_size:     NativeLong? = null
        @JvmField var st_blksize:  NativeLong? = null
        @JvmField var st_blocks:   NativeLong? = null
        @JvmField var st_atime:    NativeLong? = null
        @JvmField var st_atimensec:NativeLong? = null
        @JvmField var st_mtime:    NativeLong? = null
        @JvmField var st_mtimensec:NativeLong? = null
        @JvmField var st_ctime:    NativeLong? = null
        @JvmField var st_ctimensec:NativeLong? = null
        @JvmField var unused4:     NativeLong? = null
        @JvmField var unused5:     NativeLong? = null
    }

    /**
     * Loads the C library via JNA. On Linux, this loads the default "c" library.
     */
    private val cLib: CLib by lazy {
        Native.load("c", CLib::class.java) as CLib
    }

    /**
     * Get the inode number of the file at `path`. Throws an exception
     * if the underlying stat call fails.
     */
    fun getInode(path: String): Result<Long> {
        val statBuf = Stat()
        val result = cLib.stat(path, statBuf)
        return if (result == 0) {
            statBuf.st_ino?.toLong()?.let { Result.success(it) }
                ?: Result.failure(RuntimeException("st_ino was null"))
        } else {
            Result.failure(RuntimeException("stat failed with error code: $result"))
        }
    }

}
package org.filemat.server.config.platform

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.platform.win32.AccCtrl
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Kernel32Util
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import java.nio.file.Path

/**
 * Windows API calls through JNA. Only call these when `Platform.isWindows` is true.
 *
 * Every function returns null when the call fails, also when JNA can't load at all. Callers treat null as "can't verify".
 */
object WindowsNative {
    const val systemSid = "S-1-5-18"
    const val administratorsSid = "S-1-5-32-544"

    private const val highIntegritySid = "S-1-16-12288"
    private const val systemIntegritySid = "S-1-16-16384"
    private const val driveRemote = 4
    private const val belowNormalPriorityClass = 0x4000

    /**
     * Win32 refuses a folder path of 248 characters or more without the long path prefix, and a file path of 260 or more
     */
    const val folderPathLimit = 248
    const val filePathLimit = 260
    private const val noneMapped = "NONE_MAPPED"
    private const val fileCaseSensitiveInfo = 23
    private const val caseSensitiveDirFlag = 0x1
    private const val fileIdInfo = 18
    private const val supportsOpenByFileId = 0x01000000
    private const val maxLongPath = 32_768
    private const val shareAll = WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE
    private const val reparsePointAttribute = 0x400
    private const val dosDevicePrefix = "\\??\\"

    /**
     * Why Windows calls can't be made at all, or null when they work. JNA unpacks its DLL to `jna.tmpdir`, where
     * AppLocker or WDAC may block it.
     */
    val unavailableReason: String? by lazy {
        val error = runCatching { Kernel32.INSTANCE.GetCurrentProcessId() }.exceptionOrNull() ?: return@lazy null
        "Windows system calls don't work (${error.message ?: error.javaClass.simpleName}). JNA unpacks its DLL to " +
            "${System.getProperty("jna.tmpdir") ?: "the temp folder"}. Allow DLLs to load from there, or set -Djna.tmpdir " +
            "to a folder where they may (for example in JAVA_TOOL_OPTIONS)."
    }

    /**
     * Returns whether this process runs elevated (high integrity) or as LocalSystem
     */
    fun isElevatedOrSystem(): Boolean? = withProcessToken { token ->
        if (Advapi32Util.getTokenAccount(token).sidString == systemSid) return@withProcessToken true
        Advapi32Util.getTokenGroups(token).any { it.sidString == highIntegritySid || it.sidString == systemIntegritySid }
    }

    /**
     * Returns the `DOMAIN\name` of the account this process runs as
     */
    fun currentAccountName(): String? = withProcessToken { token ->
        Advapi32Util.getTokenAccount(token).fqn
    }

    /**
     * Runs the process [pid] at below normal priority, so a helper such as FFmpeg doesn't slow down the user's programs.
     * Returns whether it worked.
     */
    fun lowerPriority(pid: Long): Boolean? = runCatching {
        val handle = Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_SET_INFORMATION or WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt())
        if (handle == null || handle == WinBase.INVALID_HANDLE_VALUE) return false
        try {
            Kernel32.INSTANCE.SetPriorityClass(handle, WinDef.DWORD(belowNormalPriorityClass.toLong()))
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }.getOrNull()

    /**
     * Returns the `DOMAIN\name` of a well-known SID. Group names are localized, so they are never hardcoded.
     */
    fun accountNameOfSid(sid: String): String? = runCatching {
        val account = Advapi32Util.getAccountBySid(sid)
        // JNA returns this name instead of throwing when no account has the SID
        if (account.name == noneMapped) null else account.fqn
    }.getOrNull()

    /**
     * Returns whether the drive of a path is a network drive
     */
    fun isOnNetworkDrive(path: Path): Boolean? = runCatching {
        val root = path.root?.toString() ?: return null
        Kernel32.INSTANCE.GetDriveType(root) == driveRemote
    }.getOrNull()

    /**
     * Returns whether a folder has the per-directory case-sensitive flag (`fsutil file setCaseSensitiveInfo`).
     * Null when the folder can't be opened or its filesystem has no such flag (FAT, exFAT, older Windows).
     */
    fun isCaseSensitiveDirectory(folder: Path): Boolean? = withHandle(folder, WinNT.FILE_FLAG_BACKUP_SEMANTICS) { handle ->
        val info = Memory(4)
        if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, fileCaseSensitiveInfo, info, WinDef.DWORD(4))) return null
        (info.getInt(0) and caseSensitiveDirFlag) != 0
    }

    /**
     * Makes an empty folder case-sensitive, like `fsutil file setCaseSensitiveInfo <folder> enable`.
     * Null when it fails: the folder isn't empty, its filesystem has no such flag, or Windows only allows it with WSL installed.
     */
    fun setCaseSensitiveDirectory(folder: Path): Boolean? = withHandle(folder, WinNT.FILE_FLAG_BACKUP_SEMANTICS, WinNT.FILE_WRITE_ATTRIBUTES) { handle ->
        val info = Memory(4)
        info.setInt(0, caseSensitiveDirFlag)
        if (!Kernel32.INSTANCE.SetFileInformationByHandle(handle, fileCaseSensitiveInfo, info, WinDef.DWORD(4))) return null
        true
    }

    /**
     * Returns `<volumeSerial>:<fileId>` in hex, the NTFS and ReFS file ID that `fsutil file queryFileID` prints.
     * Links and junctions are not followed.
     */
    fun fileId(path: Path): String? = withHandle(path, WinNT.FILE_FLAG_BACKUP_SEMANTICS or WinNT.FILE_FLAG_OPEN_REPARSE_POINT) { handle ->
        // FILE_ID_INFO: a 64-bit volume serial, then a 128-bit file ID
        val info = Memory(24)
        if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, fileIdInfo, info, WinDef.DWORD(24))) return null
        val serial = "%016x".format(info.getLong(0))
        // The ID is little-endian, printed as one number like fsutil does
        val id = (23 downTo 8).joinToString("") { "%02x".format(info.getByte(it.toLong())) }
        "$serial:$id"
    }

    /**
     * Returns the reparse tag of a file or folder, or 0 when it is not a reparse point. Links are not followed.
     * Reads the folder entry, so OneDrive placeholders are not downloaded.
     */
    fun reparseTag(path: Path): Int? = runCatching {
        val data = WinBase.WIN32_FIND_DATA()
        val handle = Kernel32.INSTANCE.FindFirstFile(longPath(path), data.pointer)
        if (handle == null || handle == WinBase.INVALID_HANDLE_VALUE) return null
        try {
            data.read()
            if ((data.dwFileAttributes and reparsePointAttribute) == 0) 0 else data.dwReserved0
        } finally {
            Kernel32.INSTANCE.FindClose(handle)
        }
    }.getOrNull()

    /**
     * Returns the folder that a `subst` drive points to (`C:\dir`, or `UNC\server\share` for a network folder).
     * Null for other drives. [drive] is a drive name like `X:`.
     */
    fun substTarget(drive: String): String? = runCatching {
        val buffer = CharArray(maxLongPath)
        if (Kernel32.INSTANCE.QueryDosDevice(drive, buffer, buffer.size) == 0) return null
        // Ordinary drives point to a device (`\Device\HarddiskVolume3`), subst drives to a path
        val target = Native.toString(buffer)
        if (target.startsWith(dosDevicePrefix)) target.removePrefix(dosDevicePrefix) else null
    }.getOrNull()

    /**
     * Returns the system message of a Win32 error code, the text the JDK puts in `FileSystemException.reason`
     */
    fun errorMessage(code: Int): String? = runCatching { Kernel32Util.formatMessage(code) }.getOrNull()

    /**
     * Returns the root of the volume that a path is on: `C:\`, a folder a volume is mounted at, or `\\server\share\`
     */
    fun volumeRoot(path: Path): String? = runCatching {
        val buffer = CharArray(maxLongPath)
        if (!Kernel32.INSTANCE.GetVolumePathName(longPath(path), buffer, buffer.size)) return null
        // Long paths come back with the `\\?\` prefix, which other calls don't all accept
        val root = Native.toString(buffer)
        when {
            root.startsWith("\\\\?\\UNC\\") -> "\\\\" + root.removePrefix("\\\\?\\UNC\\")
            else -> root.removePrefix("\\\\?\\")
        }
    }.getOrNull()

    /**
     * Returns whether a local volume keeps file IDs across renames (NTFS, ReFS). False for FAT, exFAT and network volumes.
     */
    fun hasStableFileIds(volumeRoot: String): Boolean? = runCatching {
        if (Kernel32.INSTANCE.GetDriveType(volumeRoot) == driveRemote) return false
        val flags = IntByReference()
        if (!Kernel32.INSTANCE.GetVolumeInformation(volumeRoot, null, 0, null, null, flags, null, 0)) return null
        (flags.value and supportsOpenByFileId) != 0
    }.getOrNull()

    /**
     * Replaces the DACL of a file or folder with full control for the current user, SYSTEM and Administrators,
     * protected from the parent's. A folder passes the entries on to its children.
     *
     * Written with `SetNamedSecurityInfo`, which also replaces the inherited entries of everything already inside a folder.
     * Java's ACL view writes with `SetFileSecurity`, which leaves them.
     */
    fun setOwnerOnlyDacl(path: Path, isFolder: Boolean): Unit? = runCatching {
        val user = withProcessToken { Advapi32Util.getTokenAccount(it).sidString } ?: return null
        val sids = listOf(user, systemSid, administratorsSid).distinct().map { WinNT.PSID(Advapi32Util.convertStringSidToSid(it)) }
        // The ACL header, then per entry the entry header, the access mask and the SID
        val size = 8 + sids.sumOf { 8 + Advapi32.INSTANCE.GetLengthSid(it) }
        val acl = WinNT.ACL(size)
        if (!Advapi32.INSTANCE.InitializeAcl(acl, size, WinNT.ACL_REVISION)) return null
        val inheritance = if (isFolder) WinNT.OBJECT_INHERIT_ACE.toInt() or WinNT.CONTAINER_INHERIT_ACE.toInt() else 0
        for (sid in sids) {
            if (!Advapi32.INSTANCE.AddAccessAllowedAceEx(acl, WinNT.ACL_REVISION, inheritance, WinNT.FILE_ALL_ACCESS, sid)) return null
        }
        val information = WinNT.DACL_SECURITY_INFORMATION or WinNT.PROTECTED_DACL_SECURITY_INFORMATION
        val error = Advapi32.INSTANCE.SetNamedSecurityInfo(longPath(path), AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT, information, null, null, acl.pointer, null)
        if (error != 0) return null
    }.getOrNull()

    /**
     * Adds the `\\?\` prefix to a path of [limit] characters or more, which lifts the 260 character limit of Win32 calls.
     * Network paths (`\\server\share`) are left as they are. Folders have a lower limit than files: [folderPathLimit] and [filePathLimit].
     */
    fun withLongPathPrefix(path: String, limit: Int): String =
        if (path.length < limit || path.startsWith("\\\\")) path else "\\\\?\\$path"

    private fun longPath(path: Path): String = withLongPathPrefix(path.toString(), folderPathLimit)

    /**
     * Opens a file or folder with no access rights and full sharing, so files that other programs hold open still answer
     */
    private inline fun <T> withHandle(path: Path, flags: Int, access: Int = 0, block: (WinNT.HANDLE) -> T): T? = runCatching {
        val handle = Kernel32.INSTANCE.CreateFile(longPath(path), access, shareAll, null, WinNT.OPEN_EXISTING, flags, null)
        if (handle == null || handle == WinBase.INVALID_HANDLE_VALUE) return null
        try {
            block(handle)
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }.getOrNull()

    private inline fun <T> withProcessToken(block: (WinNT.HANDLE) -> T): T? = runCatching {
        val tokenRef = WinNT.HANDLEByReference()
        if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, tokenRef)) return null
        val token = tokenRef.value
        try {
            block(token)
        } finally {
            Kernel32.INSTANCE.CloseHandle(token)
        }
    }.getOrNull()
}

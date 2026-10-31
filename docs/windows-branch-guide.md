# The `windows-support` branch, explained

What the branch changes, for someone who hasn't read any of it, in the order that makes it easiest to follow. It describes the branch as of fixes 27 (about 230 files), which includes the clean-ups of the code review, fixes 26 for its M1-M4 and fixes 27 for its L1-L10 and I1. Known problems are in [windows-code-review.md](windows-code-review.md), and what is left to do is in [windows-status.md](windows-status.md).

## TL;DR

- **One JAR for both systems.** At startup the server checks the OS (`Platform.isWindows`) and picks the Windows or the Linux version of: path handling, file identity, permissions on its own files, the lists of protected folders and FFmpeg.
- **Windows ships as a portable zip** (x64 and arm64): a trimmed Java 21, the JAR, `ffmpeg.exe` and `filemat.cmd`. No installer, service, registry entries or firewall rules. Data goes to `%LOCALAPPDATA%\Filemat`.
- **Paths:** on the wire they look like `C:/Users/x`. The UI shows `C:\Users\x`. `/` is a virtual folder that lists the drives.
- **Case:** Windows names ignore case, unless a folder has been made case-sensitive. Each stored path gets a `path_key` (the path with case folded where Windows folds it), and every lookup goes through the key.
- **Identity:** permissions follow a file through renames by a `file_key`, which replaces the `inode` column. On Linux it is `posix:<dev>:<ino>`, on Windows `win:<volume serial>:<NTFS file ID>`.
- **One database migration (V9)** adds both keys and drops `inode`. The database is backed up before any migration.
- **Windows-only dangers are handled:** junctions, network shares, `subst` drives, 8.3 short names, files in use, the read-only attribute, OneDrive online-only files, reserved names such as `CON`, Windows' sensitive folders, running as administrator.
- **FFmpeg:** Linux keeps the FFmpeg built into the JAR (javacv). Windows runs `ffmpeg.exe` as a separate process, with several safety limits.
- **The web app** got a path module (`path.ts`) that mirrors the server's rules, so it never assumes `/`.
- **Linux gets bug fixes too:** `_` and `%` in folder names, `/foo` matching `/foobar`, names with `#` or `?` in URLs, a thumbnail that loaded a whole huge image into memory, and more (see "What Linux users notice").

## 1. Windows concepts that Linux doesn't have

The rest of this guide assumes these terms.

| Term | What it is | Why Filemat cares |
| --- | --- | --- |
| **Drive letter** | Each disk or partition is a separate root: `C:\`, `D:\`. There is no single `/` above them. | Filemat invents a virtual `/` that lists the drives. It isn't a real folder, so it can't be written to, searched or sized. |
| **Backslash** | Windows separates names with `\`. Most APIs accept `/` too. | The API uses `/` everywhere (`C:/Users`). Only the UI shows `\`. |
| **Drive-relative path** | `C:foo` means "foo in the *current* folder of drive C", and `\foo` means "foo at the root of the *current* drive". | Both are refused, or only allowed as share-relative paths, so nothing depends on the server's working folder. |
| **Case-insensitive names** | `Report.txt` and `report.txt` are the same file. Java's `Path.equals` ignores case on Windows. | Lookups that compared strings would miss or duplicate entries, so everything compares keys. |
| **Per-folder case sensitivity** | Since Windows 10, a folder can be made case-sensitive (`fsutil file setCaseSensitiveInfo <folder> enable`). WSL does this to folders it creates. New subfolders inherit the flag. | Then `a.txt` and `A.txt` really are two files. The keys keep case for names in such folders. |
| **8.3 short names** | Old DOS aliases: `C:\PROGRA~1` is `C:\Program Files`, and `.ssh` may also be reachable as `SSH~1`. | The block lists compare names, so a short name could slip past them. Paths are turned into their real names before every check. |
| **Reserved names** | `CON`, `PRN`, `AUX`, `NUL`, `COM1`-`COM9`, `LPT1`-`LPT9` (and `¹²³` variants) are devices, also with an extension (`NUL.txt`). Names can't end in a dot or a space (Windows silently drops them). `<>:"/\|?*` and control characters aren't allowed. | Such paths are refused by the server, and the web checks the same rules first (`FilenameRules`). |
| **Alternate data streams (ADS)** | `file.txt:secret` is a hidden second content stream of `file.txt`. | `:` after the drive is refused, so nobody can read or write hidden streams. |
| **UNC path** | `\\server\share\file`: a network share. | Refused everywhere. Opening one makes Windows connect and **send the account's credentials** to that server. SQLite also corrupts databases over the network. |
| **Mapped network drive** | A drive letter (`Z:`) that points to a network share. | Refused like UNC paths (the drive type is checked with `GetDriveType`). |
| **`subst` drive** | `subst S: C:\Some\Folder` creates a fake drive `S:` that is just another name for that folder. No admin rights needed. | The same file has two paths (`S:\a` and `C:\Some\Folder\a`), but the block lists and permissions are per path. Filemat turns an `S:` path into the real folder before every check, and the page shows the real path. A `subst` drive on a network folder is refused. |
| **Symbolic link** | Like on Linux. Creating one needs admin rights or Developer Mode. | Same rules as on Linux: only followed when `FM_FOLLOW_SYMBOLIC_LINKS=true`. A link to the network is never followed. |
| **Junction** | An older kind of folder link, which any user can create (`mklink /J`). **Java reports it as a normal folder** even when told not to follow links. | The dangerous one: a recursive delete through a junction would empty its *target*. Junctions are detected with a native call and treated as links everywhere. |
| **Volume mount point** | A whole disk mounted into a folder (`C:\Data` showing drive E). | Treated like a junction. |
| **Reparse point** | The general Windows mechanism behind symlinks, junctions, mount points, and also OneDrive placeholders and deduplicated files. Each carries a *tag*. | The tag's "name surrogate" bit tells links (point elsewhere) from data (a real file stored differently). |
| **OneDrive online-only file** | A placeholder that looks like a normal file. Reading its content makes OneDrive **download it**. | Filemat never reads one just to make a thumbnail or read its media metadata (see section 9). |
| **File ID** | NTFS and ReFS give each file a 128-bit ID per volume, which survives renames. Java can't read it (`fileKey()` is null). | Used as the file's identity instead of the inode, via a native call. |
| **FAT32 / exFAT** | USB sticks and SD cards. No file IDs that survive renames, no permissions (ACLs), no case flag. | Permissions there follow the path only, and the UI says so. |
| **ACL** | Windows permissions: a list of "this account may do X". There are no `rwx` bits. Folders pass entries down to what's inside ("inheritance"). | Filemat's own files (data folder, database, codes) get an owner-only ACL instead of `chmod 700`. |
| **Files in use** | A program that opens a file can forbid others from deleting, renaming or writing it ("sharing mode"). Antivirus and the search indexer do this briefly all the time. Java's own `java.io` opens files that way. | Filemat retries briefly, then answers 409 "File is in use". It opens files through NIO, which allows deletes while reading. |
| **Read-only attribute** | A flag on a file (not a permission) that blocks writes and deletes. | Kept: Filemat refuses to edit, overwrite or delete such files, and copies keep the flag. |
| **Elevation** | An administrator's programs normally run with limited rights. "Run as administrator" gives them full rights. | A Filemat running elevated can read every user's files, so startup warns loudly. |
| **Console code page** | The console uses an old character set (437, 850...) unless switched to UTF-8 (65001). Java finds its own files through the "ANSI" code page. | `filemat.cmd` switches to UTF-8, and warns when the zip sits in a folder whose name the ANSI code page can't show (Java won't start there). |
| **AppLocker / WDAC** | Corporate rules that block DLLs from loading in some folders, often `%TEMP%`. | The DLLs of JNA and SQLite are unpacked to `<data>\native` instead. |
| **Reserved ports** | Hyper-V, WSL and Docker reserve port ranges, often including 8080. | A clear startup message instead of "port in use". |
| **Storage Sense** | Windows empties temp folders automatically. | Uploads in progress and the thumbnail cache live in the data folder on Windows, not in `%TEMP%`. |
| **Controlled folder access** | Defender's ransomware protection blocks unknown programs from changing Documents, Pictures and similar. | Appears as access denied. The web adds a hint to such errors. |
| **Recycle Bin** | Deleted files normally go there. | Filemat deletes permanently, as on Linux. The delete dialog says so on Windows. |

## 2. Where the code is

| Area | Files |
| --- | --- |
| Platform layer (new) | `server/.../config/platform/`: `Platform`, `PathPolicy` (with `PosixPathPolicy`, `WindowsPathPolicy`, `FilenameRules`), `WindowsNative`, `CaseSensitivity`, `FileIdentity`, `LinkInspector`, `AclPolicy`, `DatabaseLocationPolicy`, `ConsoleLogFile`, `WindowsSystemFolders`, `WindowsPortFailureAnalyzer` |
| Path resolution | `common/util/utilFunctions.kt` (`resolvePath`, `localDrivePath`, `withRealNames`, `reachesNetwork`), `module/file/model/FilePath.kt` |
| File errors | `common/util/FileErrors.kt` (in use, read-only, retries), `FileUtils.kt` (walks, `createFoldersLike`, `isCaseOnlyRename`) |
| Database | `db/migration/V9__add_files_path_and_file_keys.kt`, `config/database/` (`PathKeyRebuild`, `FileKeyBackfill`, `Transactions`, backups in `DatabaseSetup`) |
| FFmpeg | `module/file/service/file/media/` (`MediaPreviewService`, `JavacvBackend`, `ExternalFfmpegBackend`) |
| Startup | `ServerApplication.kt`, `config/Props.kt`, `common/State.kt` |
| Web | `web/src/lib/code/util/path.ts`, `appState.svelte.ts` (`platform`, `paths`), `validation.ts`, `folderDownload.ts`, `accessDenied.ts` |
| Packaging | `server/build.gradle.kts` (`windowsDist`), `server/src/packaging/windows/` (`filemat.cmd`, `THIRD-PARTY.txt`) |
| Docs | `docs/windows*.md`, `configuration.md`, `database.md` |

## 3. The platform layer

Everything OS-specific goes through a few objects, so the rest of the code calls `PathPolicy.parse(...)` without asking which OS it is on.

- **`Platform`**: `isWindows`, the path style sent to the web (`windows` or `posix`), the separator, and the list separator for environment variables (`;` on Windows, `:` on Linux).
- **`PathPolicy`**: an interface with a Linux and a Windows version. It parses API paths into native paths and back, checks names, builds path keys, tells whether a path is a root or the drive list, splits and joins segments. The companion object delegates to the right version, so callers write `PathPolicy.pathKey(path)`.
- **`WindowsNative`**: every Windows API call, through **JNA** (Java Native Access, a library that calls DLL functions from Java; `jna-platform` 5.14 is a new dependency of about 3 MB). The calls: is the process elevated, account names of SIDs, drive type, case flag of a folder (read and set), file ID, reparse tag, `subst` target, volume root and whether it keeps file IDs, an owner-only ACL, and lowering FFmpeg's priority. **Every call returns null on failure, and callers treat null as "can't verify" and refuse.** If JNA can't load at all (AppLocker), Filemat stops at startup, because it can't tell local from network drives without it.
- **`LinkInspector`**: tells links from data reparse points (section 9).
- **`FileIdentity`**, **`CaseSensitivity`**: sections 5 and 6.

## 4. Paths, end to end

### On the wire

- Linux: unchanged, `/home/x/file`.
- Windows: `C:/Users/x/file`, drive letter upper case. `/` alone is the drive list. A path without a drive (`/sub/file`) is only valid inside a share, where it is relative to the shared folder.
- `FilePath.pathString` is this API form. It is what the database stores, what tries and caches use, and what the web gets.

### Parsing (`WindowsPathPolicy.parse`)

Accepts `/` or `\`, then refuses: control characters, drive-relative paths (`C:foo`), `:` anywhere after the drive (ADS), names ending in a dot or space, reserved device names and the characters `<>:"|?*`. That also refuses `\\?\` and `\\.\` device paths. A refused path throws `InvalidPathException`, which the new `ControllerAdvice` handler answers with 400.

A consequence: a file that exists on disk with a name Windows normally forbids (made by WSL, say `aux.txt` or `name.`) is listed, but can't be opened through Filemat.

### Resolving (`resolvePath`)

Every request path goes through this before any check. In order:

1. The drive list is returned as is (Java would otherwise turn `\` into the current drive).
2. A path without a drive is refused (only shares use them, and they resolve their own way).
3. **Windows: `localDrivePath`.** A path on a network drive is refused. A path on a `subst` drive is rewritten to the folder the drive points to, and that folder's drive is checked the same way. So a `subst` drive on a network folder, or on a folder of a mapped drive, is refused.
4. **`reachesNetwork`**: each segment is checked for a symbolic link that leads to `\\server\...` or to another drive that is a network drive (`Z:\data`), reading the link *without following it*, because following it would already send credentials.
5. `toRealPath()`: the real path, with real names (this also turns 8.3 short names into long ones).
6. A result that is a UNC path or on a network drive is refused. This is the safety net behind step 4: by now the connection has already been made.
7. With links off, the real path must be the same as the requested one (`PathPolicy.isSamePath`: same path, and on Windows the same key, so a junction `a` → `A` in a case-sensitive folder isn't mistaken for itself).

`withRealNames` is the variant for paths that must *not* be resolved because the link itself is the target (deleting, moving or copying a link): it fixes the names (case, 8.3) without following links. Delete and move first resolve the *folder* the link is in with `resolvePath`, then add the link's name, so a link reached through a linked folder is checked where it really is. Before, delete checked a link's path as typed, so with links off `exposed/junction/link` was checked as if it were inside `exposed` (also on Linux and `main`).

### In the web (`path.ts`)

Mirrors the server: `pathSegments`, `joinSegments`, `parentPath`, `childPath`, `filenameOf`, `isChildOf` (whole segments, so `/ab` is not inside `/a`), `isSamePath` (case-insensitive on Windows), `displayPath` (`C:\Users`). It reads `appState.platform.pathStyle`, which the server sends. About 15 files moved from hand-written `split("/")` code to it.

- **Page URLs are encoded per segment** (`filePageUrl`): `/files/C:/Users/a%23b`. Before, a name with `#`, `?`, `%` or `&` broke the URL on Linux too. `:` stays readable.
- A path typed in the wrong case, or on a `subst` drive, loads the real path and rewrites the URL to it.
- Copy-path dialogs and the breadcrumb copy show native paths (`C:\...`).

## 5. Case: path keys

**Problem:** the database, the permission tree, the visibility rules and the locks all looked paths up as strings. On Windows `C:/Docs` and `c:/docs` are the same folder, except inside case-sensitive folders, where they aren't.

**Solution:** every path has a **path key** (`PathPolicy.pathKey`, `FilePath.pathKey`):

- Linux: the key is the path itself.
- Windows: the drive letter is upper case, and each name is upper-cased (simple per-character mapping, like NTFS: `ß` stays `ß`) **unless its parent folder is case-sensitive**, in which case it is kept. `CaseSensitivity` reads the flag with a native call and caches it for 30 s. A folder on a filesystem without the flag (FAT, exFAT) is cached as not case-sensitive; only a folder that doesn't exist yet is read again each time.

The key is used for: entity lookups (`files.path_key`, unique index), the in-memory entity map, the permission tree, the visibility trie (exposed and hidden folders), file locks, the entity verify locks, "is this inside that" checks, and the move-into-itself checks. The real-case `path` is still stored and shown.

Related behaviour:

- **Case-only rename** (`photo.jpg` → `Photo.jpg`): Java's `Files.move` silently does nothing when both names are the same file, and the "already exists" and "into itself" checks would refuse it. It has its own path (`isCaseOnlyRename`, `renameCaseOnly`) that renames with an atomic move.
- **Copying or moving across drives** recreates folders one by one. A copy of a case-sensitive folder gets the flag too (`createFoldersLike`), or a folder holding `a.txt` and `A.txt` would lose one. If the flag can't be set (FAT), the operation fails instead.
- **Folder upload** checks for duplicate names by key, depending on whether the destination folder is case-sensitive.
- **Web:** listings carry `caseSensitive` for the open folder, and "keep both" names (`a (1).txt`) ignore case where the folder does.
- **Every Windows start rebuilds the keys** (`PathKeyRebuild`), because a folder can be made case-sensitive while Filemat is stopped. If two entities end up with the same key, the older keeps it and the newer loses its path. Rows whose folder is missing (an unplugged drive) keep their stored key, because a missing folder would read as case-insensitive and fold `a` and `A` into one.

## 6. File identity: `file_key` replaces `inode`

Filemat ties permissions and shares to an **entity** (a row in `files`) so that they follow a file when it's renamed or moved outside Filemat. Before, the entity remembered the inode number.

- Windows has no inode. The new **`file_key`** is a string:
  - Linux: `posix:<device>:<inode>`. The device number is new, and fixes a real bug: two mounted disks can have files with the same inode number.
  - Windows: `win:<volume serial>:<128-bit file ID>`, read with one native call that opens the file with no access rights and full sharing, so it works on files other programs have open. Links aren't followed, so a link has its own key.
- **Unsupported volumes:** FAT, exFAT and network volumes don't keep file IDs, so their entities are marked `is_filesystem_supported = false` and follow the path only. The permissions panel shows a note ("Permissions stay with this path...") for such files (`isPathOnly`).
- **Hard links** share one file key. The first path keeps it, the others follow their path only.
- **Repair** (`FileSecurityService.repairEntityIdentity`, run when a file is opened): if the file at a path has another key than its entity, the entity with that key is moved to this path (the file was moved outside Filemat), or the stale entity is detached. A path in a new case (renamed outside Filemat) is stored again. A move through Filemat reads the key again, because a move to another drive changes it.
- **Drive letters:** if a USB drive comes back as `F:` instead of `E:`, identity reconnects by key, but exposure is by path, so its folders become un-exposed. That fails safe.
- **Docker Desktop and WSL bind mounts** change inode numbers between restarts. Linux startup warns when an exposed folder is on such a mount (`9p`, `drvfs`, `virtiofs`, ...).

## 7. Database

- **Migration V9** (Kotlin, `db/migration`): adds `files.path_key` (filled with the path on Linux, computed on Windows), adds `files.file_key` and fills it by reading each indexed file, adds unique indexes on both, drops `inode` and its index. Guarded with `hasColumn`, so it can run on a database that already has part of it.
- **Backup before migrations:** when migrations are pending, the database is copied with SQLite's `VACUUM INTO` to `filemat-server.db.v<version>-<time>.bak` next to it. The backup file is created private first. The three newest are kept.
- **Every start:** Windows rebuilds the path keys (section 5), and both OSes fill in missing file keys for files that came back while the server was stopped.
- **SQL prefix queries** ("everything under this folder") used `LIKE 'prefix/%'`, where `_` and `%` in folder names were wildcards and ASCII case was ignored. They now compare exact substrings of the key, with the trailing `/` of a root (`/`, `C:/`) trimmed first. This fixes a Linux bug too.
- `SavedFileRepository.exists` used `&&`, which SQLite doesn't support; now `AND`.
- `sqlite-schema.sql` lost the `inode` index line, which broke every second start (B1).
- The datasource URL is no longer in `application.yml`: it is set in `main()` from the data folder.

## 8. Security and permissions

### Filemat's own data folder

- **Where:** `FM_DATA_DIR`, else `%LOCALAPPDATA%\Filemat` on Windows and `/var/lib/filemat` on Linux. It holds the database, setup and auth codes, logs, the `native` DLL folder, and on Windows the uploads in progress and the thumbnail cache.
- **Not on a network:** startup exits if the data folder is a UNC path or on a network drive, or if the drive type can't be read.
- **Private when Filemat creates it:** a data folder that Filemat creates is restricted. On Windows its ACL is replaced with full control for the current user, SYSTEM and Administrators, protected from the parent and passed down to everything inside. On Linux it gets `rwxr-x---`, as on `main`. A data folder that already existed keeps its permissions, because on Windows the rewrite would also reach every file inside, and `FM_DATA_DIR` can name a shared folder or a whole drive. If other accounts can access it, startup prints a warning. An ACL that the JDK reads as empty (a NULL DACL, which gives everyone full access) counts as not private.
- **Always private:** the database is restricted on every start (`rw-------` on Linux), because it is always Filemat's own file. The `logs` and `native` folders are restricted when Filemat creates them, so other accounts can't swap the DLLs. The setup code, auth code and database backups are created private.
- **Blocked for users**, as before, now compared by real path. Filemat's own work in it is allowed only through **internal folders** (`InternalFolder` in `State.kt`: the upload folder and the thumbnail cache): finished uploads may be moved out of the upload folder and temp folders deleted without the user checks, but only for paths really inside those folders, and those folders can't be a drive root, the data folder or a folder that contains it.
- **Upload folder change** now only moves the `uploads` and `locks` folders of tus (uploads in progress), instead of moving the whole old folder as the user. Both move or neither does. **Thumbnail cache change** only moves or deletes cache-named files, so pointing the cache at a folder with other files can't delete them. Both folders are stored as their real path, so a link or a name in another case compares the same everywhere.

### Sensitive and protected folders

- **Sensitive list** (blocked when `FM_HIDE_SENSITIVE_FOLDERS=true`, the default): Linux list unchanged. Windows has its own: registry hives, DPAPI keys, credential stores, sign-in tokens, browser and mail profiles, SSH and cloud keys, `.git-credentials`, PowerShell history, crash dumps, page files, `$Recycle.Bin`, `System Volume Information`, shadow copies, and more (full list in `docs/configuration.md`). The Windows, ProgramData, Program Files and Users folders are read from the system (Known Folders), not assumed to be on `C:`. Matching ignores case.
- **Non-deletable list:** every drive root, the drive list, `C:\Windows`, `ProgramData`, `Program Files`, `Users` and every profile in it, `Recovery`, `Boot`, `EFI`, `PerfLogs`.
- **Always hidden** on Windows, even with the sensitive list off: `$Recycle.Bin`, `System Volume Information` and the page files at each drive's root. Checked by name, so a drive plugged in after the start is covered too.
- `*` is not a wildcard in `FM_HIDDEN_FOLDER_PATHS` and `FM_FORCE_DELETABLE_FOLDERS` (it never was, silently). Startup now warns per entry with `*`.

### The drive list

Every signed-in user can open `/` and sees every local drive (not network drives or empty card readers), even when `/` isn't exposed. Opening a drive is checked like any folder. The drive list can't be searched, sized, shared, zipped or written to.

### Links

- Junctions and mount points are treated as links everywhere: delete, move and copy never go into them, walks (search, folder size, zip) skip them unless links are followed, and copying one is refused ("Links to folders were not copied"). Moving one to another drive is refused (it would turn into an empty folder).
- A folder upload resolves every folder it writes into and refuses links unless links are followed, so an upload can't write into the data folder through a junction such as `Local Settings`.
- Links to the network, a UNC path or a mapped drive, are never followed (section 4).

### Other hardening (both OSes)

- **Search** used to work without a session when no share token was given. It now needs a signed-in user, checks access to the searched folder first, and is rate limited (30, then 1 every 2 s per user).
- **Java deserialization filter** (`SerialFilter`): tus reads an `info` file per upload with Java deserialization, and users may be able to write to the upload folder. Only tus' own classes are accepted.
- **Running as administrator or SYSTEM** prints a large warning (Windows).

## 9. OneDrive and other reparse points with data

`LinkInspector.kindOf` reads a path's reparse tag *from the folder entry* (`FindFirstFile`), which doesn't make OneDrive download anything, and sorts it into:

- **NONE**: not a reparse point.
- **LINK**: symlink, junction or mount point (the tag's "name surrogate" bit). Treated as a link.
- **DATA**: a reparse point that holds file data, like an OneDrive online-only file or a deduplicated file. Java calls these "other", not regular files.

DATA files:

- are listed as normal files (Java alone would call them "other");
- get **no thumbnail** ("No preview for this file.") and **no media metadata**, because reading them downloads them;
- can still be opened and downloaded (`LinkInspector.isRegularFile` counts them as files), which makes OneDrive download them, as Explorer would;
- are found by search by name only.

A folder whose tag can't be read counts as a link, so walks never go into an unknown target.

## 10. File operations on Windows

- **Files in use** (`FileErrors`): sharing and lock violations are recognised by comparing the error text with the system's (localized) messages for those two error codes. Delete, move, copy, replace and edit retry 3 times, 100 ms apart, with at most 3 s of waiting for a whole folder operation. Then the answer is **409 "File is in use."** (the web shows it). Replacing an open file gives "access denied" instead, which only counts as "in use" at the actual swap step.
- **Read-only attribute:** edit, overwrite and delete refuse ("File is read-only."), copies keep it, a move to another drive (a copy and a delete) refuses it before copying.
- **Folder operations report why:** a partial failure lists the reasons ("Some files are in use by another program." / "Read-only files were left in place.").
- **Delete of several files** now returns what was deleted and why the others weren't, and the web shows it. Before, failures were silent.
- **Replace (upload over a file):** the temp file is hidden while it's written (Windows shows dot files), then swapped in atomically.
- **Edit:** writes over the file through NIO, then truncates, so a file locked by another program isn't emptied first, and the file keeps its file ID, ACL and hard links. It locks the whole file first, so another program's lock on part of it stops the save before anything is written (409), and it writes the bytes past the old end first, so a full disk fails before the old content is touched. A crash in the middle of the write can still mix old and new.
- **Move to another drive** falls back to copy plus delete, and removes the copy if the original can't be deleted, so no duplicate is left.
- **New file reads and writes** (image headers, the editor) use NIO, which lets other programs delete or rename the file meanwhile. `java.io` would block them.

## 11. Video previews and FFmpeg

- The FFmpeg built into the JAR (javacv natives) only exists for Linux. **opencv** (also javacv, never used) was removed, which shrinks the JAR.
- `MediaPreviewService` picks a backend once: javacv if it loads (Linux), otherwise `ffmpeg` from `FM_FFMPEG_PATH`, otherwise from `PATH`. No FFmpeg means video previews are off and everything else works. **Admin → System → Video previews** shows which one is used, or why there is none.
- **External FFmpeg** (`ExternalFfmpegBackend`), hardened:
  - no shell, arguments as a list, input as `file:<absolute path>`;
  - only local files (`-protocol_whitelist file`) and only video containers and single images (`-format_whitelist`), not playlists (HLS, concat, ...) or image sequences, which could make FFmpeg read other files;
  - never a `.cmd` or `.bat` (cmd.exe would parse the arguments again) or a relative path;
  - killed after 30 s, with its child processes; runs at below-normal priority;
  - at most half the CPU cores (1-4) at once. A request waits 2 s for a free slot, then gets **503 "Video previews are busy"**, and nothing is cached for it.
- The javacv path got the same protocol and format limits.
- **Not Windows-specific:** a thumbnail of a large image decoded the whole image first (a 12000 × 12000 TIFF took about 1.3 GB). The size now comes from the header, and the image is read reduced. A failed thumbnail used to answer 200 `image/jpeg` with error text and long cache headers; it now answers 500.

## 12. Startup and running

`main()` now does, in order:

1. Installs the deserialization filter, reads `Props` (data folder from `FM_DATA_DIR`).
2. Windows: points `jna.tmpdir` and `org.sqlite.tmpdir` at `<data>\native`. This must happen before anything loads JNA (B2: the first start on a new folder failed because of that).
3. Checks the data folder isn't on the network, creates it, the native folder and the database file. It restricts the database every time, and the data and native folders only when it creates them (section 8).
4. Windows: copies the console output to `<data>\logs\filemat.log` (the folder is restricted when created; rotated at 10 MB, 9 old files kept), because the portable app has no service log. Warns if elevated.
5. Starts Spring with the database URL from the data folder.

After startup: migrations (with backup), path key rebuild, file key repair. The FFmpeg status is logged once the app is ready (before, its warning was written before the log table existed).

A port that can't be opened explains Windows' reserved port ranges and how to pick another port (`WindowsPortFailureAnalyzer`).

## 13. Configuration

| Variable | What's new |
| --- | --- |
| `FM_DATA_DIR` | New, both OSes. Data folder. |
| `FM_FFMPEG_PATH` | New, both OSes. Full path of `ffmpeg`. The zip's `filemat.cmd` sets it. |
| List variables | `;` between values on Windows (`C:\One;D:\Two`), `:` on Linux. |
| `FM_HIDDEN_FOLDER_PATHS`, `FM_FORCE_DELETABLE_FOLDERS` | Warn at startup for `*`. |
| `SERVER_PORT` | A Spring setting, now documented. |

Windows defaults for the upload and thumbnail folders are inside the data folder (`uploads`, `cache\thumbnails`). The setup page shows the server's defaults. The upload and thumbnail folders can't be a drive, the data folder or a folder that contains it (both OSes).

## 14. The web app, other changes

- `appState.platform` (path style, separator, filename rules) and `appState.paths` (setup code, auth code and default folder paths). The paths contain the OS user name, so they're only sent before setup or to signed-in users.
- New folder, new file and rename check names with the server's rules first (`Validator.filename`).
- The drive list hides search, folder size and dates.
- **Folder download to a Windows PC** (also from a Linux server): names Windows can't store (`a:b`, `CON.txt`, `name.`) are made safe, and two names that become the same (`a:b` and `a?b`, or `A.txt` and `a.txt`) get ` (1)`.
- Delete dialogs on Windows say that files don't go to the Recycle Bin.
- Access-denied errors on Windows get a hint about `icacls` and Controlled folder access.
- Typed Windows paths are compared with the server's case folding (`foldCase` in `path.ts`: upper case per letter, unless that gives more than one letter), so `ı` and `i` match while `ß` and `ẞ` don't.
- A load that a newer navigation aborted no longer shows an error or jumps to the parent folder.
- The service worker message no longer waits forever when no worker is registered.
- Vitest was added (`npm test`, 76 tests).

## 15. Build and packaging

- **Java 17 → 21** (toolchain, with the foojay resolver to download it). Kotlin and Spring Boot are unchanged.
- **`gradlew bootJar` builds the web app too** (node-gradle plugin, uses the installed npm, `npm ci`). `-PskipWeb` leaves it out. `src/main/resources/static` is no longer used, and the build scripts lost their copy steps.
- **`gradlew.bat windowsDist -PreleaseVersion=1.2.3`** (Windows only) builds both zips:
  1. Downloads pinned Temurin JDK 21 zips and BtbN FFmpeg 7.1 GPL builds from GitHub, checks their SHA-256, caches them in `server/build/windows/downloads`.
  2. **jlink** from the x64 JDK builds a trimmed Java runtime (23 modules) from each target's modules. That's how an arm64 runtime is built on an x64 PC, and why the JDK version is pinned exactly.
  3. Zips `filemat.cmd` (converted to CRLF: cmd.exe misreads labels in LF files), `filemat.jar`, `jre\`, `ffmpeg\ffmpeg.exe` and `LICENSES\`.
- **The zip is GPL-3** because of FFmpeg. `THIRD-PARTY.txt` names the exact builds and their sources.
- **`filemat.cmd`:** sets `FM_FFMPEG_PATH` to the bundled FFmpeg, warns when its folder name can't be read in the ANSI code page, switches the console to UTF-8, runs Java with UTF-8 output, passes arguments on, and puts the console's code page back afterwards.
- `.gitattributes` keeps `*.cmd` in CRLF. A root `.editorconfig` was added.
- `utilities/build.ps1` builds the JAR, optionally the zips (`-Windows`) and the Docker image.
- Tests get their own data folder (`build/test-data-folder`). `-Dfilemat.requireWindowsFeatures=true` makes the Windows-only tests fail instead of skip when a feature (junctions, `subst`, 8.3 names, case-sensitive folders) is missing.

## 16. What Linux users notice

- Java 21 is required.
- A one-time database migration, with a backup first.
- Fixed: `_` and `%` in folder names acting as wildcards; `/foo` matching `/foobar`; `//name` at the root; names with `#`, `?`, `%` or `&` in page URLs; file keys colliding across two disks; large-image thumbnails using a lot of memory; failed thumbnails cached as images; the FFmpeg warning written before its log table existed; silent delete failures.
- Stricter: search needs a session; the database permissions are set on every start, not only when it is created; deleting a link inside a linked folder is checked where the link really is; an upload or cache folder of `/` or a parent of the data folder is refused; folder uploads don't go through symbolic links unless links are followed; FFmpeg may only open local files; Docker Desktop and WSL bind mounts get a startup warning.
- Unchanged: stored paths, URLs, default folders, media handling.

## 17. Tests and manual checks

- Server: 853 tests (55 new test files), run on Windows and on Linux (WSL). The Windows-only ones use real junctions, `subst` drives, case-sensitive folders, ACLs and locked files. Web: 76 Vitest tests. Fixes 26 (M1-M4) and fixes 27 (L1-L10, I1) bring the server to 879 and the web to 77.
- [windows-test-plan.md](windows-test-plan.md) has the manual checks (W1-W45 for Windows, L1-L18 for Linux). Every check an agent could run has run. The ones left need a person (admin rights, OneDrive, network shares, a second device) and are listed in [windows-status.md](windows-status.md).

## 18. Not done, on purpose

No installer (MSI), no Windows service, no code signing, no network shares, no Recycle Bin, no moving a database between Linux and Windows (it stores full paths), no server-side folder picker, no CI. ARM64 is built but untested. The release is a beta.

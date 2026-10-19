# Windows Support Plan

Status: planning draft (2026-10-19). Covers native Windows Server and Windows 10/11 hosts, x64 first and ARM64 second, plus Docker Desktop on Windows.

This plan is based on an audit of `main` (`aa7e2ab`) and the existing `windows` branch (`82376ad`, `86899a3`, 84 commits behind `main`). Each section lists **what breaks today**, **the decision**, and **the work items**. File references point to `main` unless they say otherwise.

---

## 0. Executive summary

Filemat assumes Linux throughout. On Windows the server **exits at startup**: `ServerApplication.initializeFileStructure()` calls `Files.setPosixFilePermissions`, which throws `UnsupportedOperationException` on NTFS, and the catch block calls `exitProcess(1)`. Even with that fixed, there is a chain of further failures:

- **Every folder listing fails.** `FilesystemService.kt:100,130-138` reads `PosixFileAttributes`, gets `null` on Windows, and `FileFolderEntriesService.kt:170-171` throws `IllegalStateException`.
- **The Spring context fails to start.** `ThumbnailService.kt:89-90` calls `av_log_set_level` in `init {}`, and the Linux-only ffmpeg natives throw `UnsatisfiedLinkError` (`ApplicationContextTest` mocks `ThumbnailService` for exactly this reason).
- **Uploads break after the first restart.** `DatabaseSetup.kt:102` turns `C:\x` into `/C:\x`, which leads to `InvalidPathException`. `TusService.initializeTusService` swallows it and the result is ignored (`Initialization.kt:273`).
- **Data folder protection is bypassed.** `Path.of("/var/lib/filemat")` is the drive-relative `\var\lib\filemat`, so every `startsWith(Props.dataFolderPath)` check is false and the SQLite DB, `setup-code.txt` and `auth-code.txt` become reachable through the app. `FileSecurityServiceTest:246-270` masks this because both sides become `\var\...`.
- **Recursive delete follows junctions.** `FilesystemDeletionService.kt:87-96` sees a junction as a directory and **deletes the target's contents**. Move (`FilesystemMoveService.kt:122-128`) and copy (`FilesystemCopyService.kt:125-131`) recurse into junctions too.

The existing test suite passes on Windows (last run 2026-10-17: 0 failures, 19 skipped symlink tests) only because the tests use `/foo` strings that become `\foo` on both sides. **A green Windows test run currently proves nothing.**

| # | Area | Severity | Summary |
|---|---|---|---|
| 0 | Startup and listing | **Blocker** | POSIX permissions at startup, `PosixFileAttributes` in metadata, eager ffmpeg native init, upload path mangling (see the list above). |
| 1 | File identity (`inode`) | **Blocker** | Permissions and shares are tied to entities identified by `unix:ino`, which doesn't exist on Windows. `isSupportedFilesystem()` returns `false`, so every entity loses move/rename tracking. |
| 2 | Path model | **Blocker** | Paths are POSIX strings rooted at `/`. `getNormalizedPath()` uses `Paths.get("/")`, which on Windows means "root of the current drive". `VisibilityTrie`, `EntityPermissionTree`, the frontend and the routes all split on `/`. |
| 3 | Hardcoded paths | **Blocker** | `/var/lib/filemat`, `/var/cache/filemat` and `/tmp/filemat` appear in `Props.kt`, `ServerApplication.kt`, the setup UI and `SensitiveAuthDialog`. |
| 4 | Security lists | **Security** | The sensitive and non-deletable folder lists are Linux-only. On Windows nothing would be protected (`C:\Windows`, SAM hives, DPAPI keys, other users' profiles). |
| 5 | Path aliasing | **Security** | Case-insensitivity, 8.3 short names, trailing dots/spaces, ADS (`file:stream`), `\\?\` and `\\.\` prefixes, UNC and drive-relative paths can each bypass string-based block lists and permission lookups. |
| 5b | Data folder bypass | **Security** | Relative `Props.dataFolderPath` on Windows: the DB and auth code files can be read, written and deleted through the app. |
| 6 | Links | **Security / data loss** | NTFS junctions and mount points are not reported as symlinks by `Files.isSymbolicLink`, so `FM_FOLLOW_SYMBOLIC_LINKS=false` can be bypassed with a junction. Recursive delete wipes the junction's target. |
| 7 | Media natives | High | `javacv` ffmpeg/opencv are bundled only for `linux-x86_64` and `linux-arm64`, so previews throw `UnsatisfiedLinkError`. |
| 8 | File locking | High | Windows refuses to delete or rename open files. Streams kept open while streaming video, by the thumbnailer, by antivirus or by the indexer cause sharing violations. |
| 9 | Config parsing | High | Multi-value env vars are split on `:`, which breaks `C:\...`. |
| 10 | Build/CI/packaging | Medium | Bash-only build, no Windows CI, no service wrapper, no Windows install docs, Java 17 vs 21 drift. |

**Recommended overall approach:** don't merge the `windows` branch as-is. It is large (114 files, +3.7k/−0.9k), stale, and its identity layer probably doesn't work on Windows (see §3.2). Port its good pieces (`PathPolicy`, `LinkInspector`, `Props`/`FM_DATA_DIR`, packaging, `.gitattributes`/`.editorconfig`, the `platform-parity` CI) onto current `main` in small, separately reviewable PRs, in the phase order in §13.

---

## 1. Scope and support targets

### 1.1 In scope
- A native Windows backend running as a Windows service or in the foreground (console).
- Windows 10 22H2+, Windows 11, Windows Server 2019/2022/2025.
- x64. ARM64 too if the media story allows it (§7).
- NTFS as the primary filesystem and ReFS as secondary. FAT32 and exFAT are browse-only, with degraded identity (§3).
- Local drives (`C:\`, `D:\`). UNC shares (`\\server\share`) only if an admin explicitly exposes them, and as a phase-2 feature.
- Docker Desktop on Windows (Linux containers) as a documented, supported path. This mostly needs docs plus an identity-stability check (§10.4).

### 1.2 Out of scope (for now)
- Windows containers (a `nanoserver` or `servercore` image).
- Exposing Windows ACL editing in the UI. Filemat permissions stay app-level, and the OS ACL is a hard lower bound.
- Recycle Bin integration. Delete stays permanent; documented, possible later option §8.6.
- macOS. It would benefit from the same abstraction, since APFS is case-insensitive by default, but isn't targeted.
- Moving an existing Linux database to a Windows host. All stored paths are absolute; see §12.4.

### 1.3 Decisions needed before implementation
1. **Path wire format** (§2.2): readable drive paths (`C:/Users/x`) vs an opaque base64 route codec (branch design). *Recommendation: readable canonical paths in the API, per-segment URL encoding in routes, no base64.*
2. **Virtual root `/` on Windows**: a synthetic directory that lists the exposed drives, or no root at all. *Recommendation: a synthetic root.*
3. **Media**: bundle Windows javacv natives, or switch to an external FFmpeg executable (branch design). *Recommendation: external FFmpeg on all platforms, keeping javacv as a fallback for one release.*
4. **Java baseline**: 17 or 21. *Recommendation: 21 everywhere (§8.3). CI and Docker already use 21, and the README and toolchain say 17.*
5. **Distribution**: plain JAR plus the user's own JRE, or a `jpackage`/`jlink` app image with a bundled JRE plus WinSW. *Recommendation: a zip app image with a bundled JRE plus WinSW. MSI later.*
6. **Does the Linux default upload folder move** from `/tmp/filemat` to `<dataDir>/uploads`? The branch changes it for Linux too. That breaks existing settings and needs a migration note.

---

## 2. Path model (core design)

### 2.1 What breaks today
- `utilFunctions.kt:219-222`: `String.getNormalizedPath()` = `Paths.get("/").resolve(trimStart('/'))`. On Windows `Paths.get("/")` is `\`, which is relative to the current drive. So `"/C:/Users"` → `\C:\Users` → `InvalidPathException`, and `"/Users"` → `C:\Users` silently, depending on the process's working drive.
- `FilePath.equals/hashCode` compare `pathString` case-sensitively, so `C:\Data` and `c:\data` are different keys for the same file.
- `VisibilityTrie.kt:17,36,69,83,109` and `EntityPermissionTree.kt:144,197,200` split and join on `/` and rebuild the path as `"/" + join("/")`. On Windows the stored `C:\x\y` produces a single segment, so exposure and inherited permissions stop working.
- Controllers: `substringAfterLast("/")` for download filenames (`FileController.kt:375`, `FileUtilController.kt:73,173`, `FileContentMetadataService.kt:55`), so the full `C:\a\b.txt` becomes the filename. `ResourceController.kt:29-33`.
- Root guards compare `pathString == "/"` (`FileCopyService:32`, `FileDeletionService:36`, `FileMoveService:35`, `ThumbCacheSettingService:73,150`). Drive roots `C:\` are not caught, so a user could try to delete or move `D:\`.
- Share-relative resolution uses `FilePath.of("/")` as the share root (`FileContentService:102`, `FileEntryListsService:57`, `FileMetadataService:77`, `sharePath.kt:71`).
- `DatabaseSetup.kt:102` forces a leading `/` onto the upload folder path.
- `FolderUploadService.kt:689,709,784,788` uses `/` segment logic. It already rejects a leading `\`, which is good.
- `EntityService.kt:260` builds `"$it/$suffix"` when re-pathing children after a move.
- Zip entry names (`FileContentService.kt:250`) must always use `/`. That is correct per the zip spec; keep it, but make sure names come from `Path` segments and not from `toString()`.

### 2.2 Decision: one canonical "API path" format with a platform adapter
Add a small platform layer, `org.filemat.server.common.platform` (the branch already has the skeleton):

```
Platform          isWindows / isPosix, pathStyle, separator, caseSensitivity
PathPolicy        parse(apiString) -> Path ; toApiString(Path) -> String ; toKey(Path) -> String
                  isRoot(Path) ; startsWith(child, parent) ; validateFileName(name)
                  resolveContained(base, relative)
FileIdentity      fileKey(Path) -> FileKey?   (see §3)
LinkInspector     isLinkLike(Path) (symlink OR any reparse point)
AtomicFiles       move/replace with cross-volume + sharing-violation handling
AclPolicy         restrictToOwner(Path) (POSIX chmod or Windows ACL)
```

**API/storage string format:**
- POSIX: unchanged (`/home/x/file`). This keeps Linux DB rows and URLs stable, so Linux users need no migration.
- Windows: forward-slash drive-absolute, `C:/Users/x/file`. The drive letter is upper-cased and the canonical on-disk case is used wherever the file exists. UNC is `//server/share/dir` (phase 2).
- Windows virtual root: `/`. A synthetic directory whose children are the exposed volume roots (`C:/`, `D:/`). It's never passed to `Paths.get`.
- Accept `\` on input on Windows only and normalize it to `/`. On Linux, `\` is a legal filename character and must stay literal.

**Rules (enforced in `PathPolicy.parse`):**
- Reject NUL and control characters 0x00–0x1F.
- Windows: accept only `X:/...`, `//server/share/...` (only if UNC is enabled) or `/`. Reject:
  - drive-relative `C:foo`
  - root-relative `\foo`
  - device namespaces `\\.\` and `\\?\`, `\\?\GLOBALROOT`, `\\?\Volume{...}` (the server can use these internally, but never accepts them from clients)
  - `:` anywhere after the drive letter (alternate data streams `file.txt:secret`, `file::$DATA`)
  - any segment ending in `.` or space (Win32 silently strips them, so `secret.txt.` would open `secret.txt`)
  - reserved device names in any segment (`CON`, `PRN`, `AUX`, `NUL`, `COM0-9`, `LPT0-9`, the superscript `COM¹²³`/`LPT¹²³` variants, `CONIN$`, `CONOUT$`), with or without an extension
- Normalize `.` and `..` lexically, then check containment. After that, call `toRealPath()` for existing paths (§2.4).

### 2.3 Case-insensitivity
- **Lookup key (`path_key`)**: for DB uniqueness and trie lookups on Windows, store a folded key next to the display path. The branch uses `lowercase(Locale.ROOT)`. NTFS actually compares using its per-volume `$UpCase` table (simple uppercase mapping), so use `uppercase` per code point with `Locale.ROOT` and **no** special casing; full case mapping turns `ß`→`SS`, which NTFS does not do. Document that rare Unicode edge cases can differ.
- **Display path**: always the canonical on-disk case, taken from `toRealPath(NOFOLLOW_LINKS)` for existing files. Never echo the client's casing back as canonical, because the frontend compares with `===`.
- **Per-directory case sensitivity** (WSL's `fsutil file setCaseSensitiveInfo`): folded keys would merge `a` and `A` in such folders. Detection is expensive, so for v1 document it as unsupported. Option: refuse to expose directories that have the case-sensitive flag set.
- Every place that compares paths with `startsWith`/`==` must go through `PathPolicy.startsWith` (segment-aware and case-aware). The current `String.startsWith` checks also have a Linux bug: `/foo` matches `/foobar`. The frontend audit found the same bug at `files.ts:1093-1097`.

### 2.4 8.3 short names and other aliases
- `C:\PROGRA~1` is `C:\Program Files`, and `C:\Users\ADMINI~1` is a profile. Block-list and permission checks on the lexical string miss these.
- **Fix**: always resolve existing paths with `toRealPath()` before authorization. It expands short names and fixes case. For paths that don't exist yet (create, upload target), resolve the deepest existing ancestor with `toRealPath()` and append the validated remaining segments.
- Also reject any input segment that matches `~[0-9]` in 8.3 form and doesn't round-trip through `toRealPath`. This is defence in depth.
- Other aliases to consider: hard links (the same file under two paths, so the same file key; §3.4), `subst` drives, and mapped network drives. Mapped drives are per-logon-session and invisible to services, so document "use UNC instead".

### 2.5 Length limits
- `MAX_PATH` = 260. The JDK prefixes `\\?\` internally for long paths in NIO, so Java file operations mostly work. External tools (FFmpeg, shell commands) and some Win32 APIs called through JNA do not. Either pass long paths to FFmpeg with the `\\?\` prefix or reject them for previews.
- A filename can be at most 255 UTF-16 code units on NTFS; on ext4 the limit is 255 bytes. Validate in UTF-16 units on Windows.
- Report `PathTooLong` cleanly instead of a generic 500.

### 2.6 Config values that hold paths
- `FM_NON_SENSITIVE_FOLDERS`, `FM_HIDDEN_FOLDER_PATHS` and `FM_FORCE_DELETABLE_FOLDERS` are split on `:` (README:65, `docs/configuration.md`). On Windows, use `;` (`File.pathSeparator`). Linux keeps `:`, so it stays backwards compatible. Accept both `\` and `/` on Windows, and `*` wildcards as in the Linux list (`/home/*/.ssh` → `C:/Users/*/AppData`).

---

## 3. File identity ("entities")

### 3.1 How it works now
`files.inode` (unique index) together with `is_filesystem_supported` lets `FileSecurityService.resolveEntityConflicts` (around lines 242-360) re-attach permissions and shares when a file moves, or orphan them when a different file appears at the same path. `FileUtils.getInode` reads `unix:ino`, and `isSupportedFilesystem` checks for the `unix` attribute view.

**There is an existing Linux bug in this design:** inode numbers are unique only per device. A unique `inode` index across two mounted filesystems can collide (for example `/` and `/mnt/data` both having inode 2 at the root, or any reused inode). The key should be `(st_dev, st_ino)`. Fix this in the same migration.

### 3.2 Problem with the `windows` branch approach
The branch builds the key from `BasicFileAttributes.fileKey()` plus `FileStore.name():type()`. **On the Windows NTFS provider in OpenJDK, `fileKey()` returns `null`** (the provider keeps the volume serial and file index internally for `isSameFile` but doesn't expose them). The branch would therefore fall through to `isStable = false` for every NTFS file. Also, `FileStore.name()` is the volume *label*, which isn't unique and which users can change. **Verify this with a test on a Windows runner before building on it** (`FileIdentityWindowsTest`: create a file, assert that the key is non-null and survives a rename).

### 3.3 Recommended Windows file key
- Use JNA (already a dependency on the branch through `LinkInspector`) to open a handle with `CreateFileW(path, 0 /*no access needed*/, FILE_SHARE_READ|WRITE|DELETE, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT)`.
- Call `GetFileInformationByHandleEx(FileIdInfo)`. It returns a 64-bit `VolumeSerialNumber` plus a 128-bit `FileId`; this is needed for ReFS, whose IDs are 128-bit.
- Fall back to `GetFileInformationByHandle` (32-bit serial plus a 64-bit index) on older systems or failure.
- Key format: `win:<volSerialHex>:<fileId128Hex>`. On POSIX: `posix:<dev>:<ino>`.
- Opening with access mask `0` and full share mode avoids sharing violations and doesn't trigger OneDrive hydration. Close the handle immediately, using `use {}`.
- Performance: this adds one syscall round-trip per file. Listing large folders would call it per child. Only compute keys for files that have entities, or lazily when needed. Today `getInode` is presumably called per entity check; keep the same call sites. Cache by `(path, lastModified)` in Caffeine (already a dependency).
- Alternative without JNA, on Java 22+: FFM API. Not available on 17/21 without preview flags, so use JNA (`net.java.dev.jna:jna-platform`, about 3 MB).

### 3.4 Identity stability facts to design around
| Case | NTFS | ReFS | FAT/exFAT | SMB share |
|---|---|---|---|---|
| Survives rename/move within volume | yes | yes | **no** (IDs are directory-entry positions) | depends on server |
| Survives copy | no (new file) | no | no | no |
| Safe-save editors (write temp + rename) | new ID | new ID | — | — |
| Hard links share ID | yes | n/a | n/a | — |
| Cross-volume move | new ID | new ID | new ID | — |

- Mark FAT, exFAT and network volumes `is_filesystem_supported = false`. That is the same fallback Linux already uses for unsupported filesystems. Decide based on `GetVolumeInformationW` (filesystem name plus the `FILE_SUPPORTS_OPEN_BY_FILE_ID` flag) and cache per volume root.
- Volume serial numbers can collide on cloned disks or VHDs. That's acceptable, but document it.
- Drive letters can change, for example on removable or USB drives. Because the key contains the volume serial and not the letter, a file with a permission entity that reappears under `E:` instead of `D:` is reconnected by key on first access. **But** visibility/exposure (`folder_visibility`) is path-based, so a letter change un-exposes the folder. That fails safe; document it.
- Hard links: two paths share one key and the unique index rejects the second entity. Linux has the same problem today. Decision: the key is unique, the first path wins, and the second path gets a path-only entity marked `is_filesystem_supported = false`. Or make `file_key` non-unique and resolve by `(file_key, path)`. Needs a decision; treat it as an existing bug on both platforms.

### 3.5 Database migration (next free number after V8 on `main`; the branch's V9/V10 must be renumbered)
- Add `files.file_key TEXT` (unique, nullable) and `files.path_key TEXT` (unique). Keep `inode` for one release, read-only, then drop it in a later migration.
- Add `folder_visibility.path_key` and `saved_files.path_key`. The `saved_files` primary key becomes `(user_id, path_key)`. SQLite can't alter a primary key, so this needs a table rebuild; follow the V8 pattern.
- Also check `shared_files`, `users.home_folder_path` (V6) and any other path column for the same treatment.
- Backfill on Linux: `path_key = path`, and `file_key = "posix:<dev>:<ino>"` computed in a Java migration that stats each path. Missing files get `null` and are orphaned exactly as they would be today.
- Migrations must be idempotent and run on both OSes. Test them in the Windows CI job against a DB fixture created on Linux; the paths won't resolve, so they must fail safe.
- `EntityRepository`: `getByInode` → `getByFileKey`, `updateInodeAndPath` → `updateFileKeyAndPath`. Path lookups query by `path_key`.

---

## 4. Platform security policy

### 4.1 Sensitive folders (Windows defaults for `SensitiveFolderPaths`)
Block these when `FM_HIDE_SENSITIVE_FOLDERS=true`. Resolve them through environment variables and Known Folders at startup, never by hardcoding `C:`:
- `%SystemRoot%\System32\config` (SAM, SYSTEM and SECURITY hives), `%SystemRoot%\System32\config\RegBack`
- `%SystemRoot%\NTDS` (domain controllers), `%SystemRoot%\System32\Microsoft\Protect`
- `%SystemRoot%\repair`, `%SystemRoot%\Panther` and `%SystemRoot%\System32\Sysprep` (unattend files can contain admin passwords)
- `%ProgramData%\Microsoft\Crypto`, `%ProgramData%\Microsoft\Windows\WER` (crash dumps hold memory contents)
- `C:\Users\*\NTUSER.DAT*`, `C:\Users\*\AppData` (credentials, DPAPI master keys, browser profiles and tokens), `C:\Users\*\.ssh`, `C:\Users\*\.gnupg`, `C:\Users\*\.aws`, `C:\Users\*\.azure`, `C:\Users\*\.kube`, `C:\Users\*\.docker`
- the service account's profile: `%SystemRoot%\System32\config\systemprofile`, `%SystemRoot%\ServiceProfiles`
- `<drive>:\System Volume Information`, `<drive>:\$Recycle.Bin` (other users' deleted files), `<drive>:\$Extend`, `<drive>:\pagefile.sys`, `hiberfil.sys`, `swapfile.sys`, `DumpStack.log*`, `MEMORY.DMP`
- `%ProgramData%\Docker`, `%ProgramData%\MySQL`, `C:\Program Files\PostgreSQL\*\data`, `%ProgramData%\ssh` (OpenSSH server host keys)
- IIS: `%SystemRoot%\System32\inetsrv\config`
- **the Filemat data dir** (`FM_DATA_DIR`), always, as on Linux.

On Linux, `/mnt/c/...` (WSL) should also get the Windows list applied when it detects a Windows drive. Optional.

### 4.2 Non-deletable paths (`NonDeletableSystemPaths`)
- every volume root (`X:/`) and the virtual `/`
- `%SystemRoot%`, `%ProgramFiles%`, `%ProgramFiles(x86)%`, `%ProgramW6432%`, `%ProgramData%`, `C:\Users`, `C:\Users\Default`, `C:\Users\Public`, each `C:\Users\<profile>` root, `C:\Recovery`, `C:\Boot`, `C:\EFI`, `C:\PerfLogs`
- Root guards in copy/move/delete must use `PathPolicy.isRoot(path)` (drive root, UNC share root or virtual root) instead of `== "/"`.

### 4.3 UNC paths and NTLM credential leakage
- Never let a client make the server *touch* an arbitrary UNC path. A request for `//attacker/share/x` makes the service authenticate with NTLM to the attacker (hash capture or relay).
- Reject UNC input unless its `//server/share` prefix is an admin-exposed root. Check this **before** any filesystem call, including `exists()` and `toRealPath()`.
- The same applies to WebDAV-style `\\host@SSL\...` and any path that resolves through a symlink to UNC. A symlink to a remote target requires the `R2R`/`L2R` symlink evaluation policy, which is off by default; document that.
- Settings inputs (upload folder, thumbnail cache, home folder) must reject UNC paths unless they're explicitly allowed.

### 4.4 Links and reparse points
- `Files.isSymbolicLink()` is true only for `IO_REPARSE_TAG_SYMLINK`. Junctions (`MOUNT_POINT`), volume mount points, AppExecLinks, OneDrive/cloud placeholders, dedup and WIM-backed files are all reparse points with other tags.
- Policy when `FM_FOLLOW_SYMBOLIC_LINKS=false`: don't traverse or follow **any name-surrogate reparse point** (symlink, junction, mount point). Detect `FILE_ATTRIBUTE_REPARSE_POINT` and read the tag through `FindFirstFileW` (`dwReserved0`) or `FSCTL_GET_REPARSE_POINT`. The branch's `LinkInspector` checks the attribute but not the tag, so it would also block OneDrive placeholder files and dedup files. Filter on `IsReparseTagNameSurrogate(tag)`.
- `safeWalk` (`FileUtils.kt`) and search must not recurse into junctions. Windows profiles contain legacy junction loops such as `C:\Users\x\AppData\Local\Application Data` pointing to itself. `walkIdentity`/`isAlreadyWalked` already exists for cycles; make sure it detects junction cycles too.
- When following is enabled: re-authorize the resolved target and enforce containment (`resolveContainedExisting` on the branch). That covers share roots and visibility.
- Creating symlinks needs `SeCreateSymbolicLinkPrivilege` (admin) or Developer Mode. If Filemat has any "create link" or copy-preserving-links behaviour, handle `FileSystemException: A required privilege is not held` by copying the target or rejecting with a clear message.
- `.lnk` shortcut files are ordinary files. Never resolve them.

### 4.5 Cloud files (OneDrive and similar)
- Placeholder files carry `FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS` and `RECALL_ON_OPEN`/`OFFLINE`. Reading content (thumbnails, previews, Tika MIME sniffing, zip download, search by content) **triggers a download**, possibly gigabytes, or fails when offline.
- Plan:
  - Skip thumbnail and preview generation and content sniffing for these files. Use extension-based MIME instead.
  - Show an "online-only" badge (optional).
  - Use `FILE_FLAG_OPEN_NO_RECALL` for metadata calls.
- Note: the `LocalSystem` service account has no OneDrive anyway. This matters for people who run Filemat as their own user.

### 4.6 Hidden and system attributes
- Linux has dotfiles; Windows has `dos:hidden` and `dos:system`. Add `isHidden`/`isSystem` to the file metadata DTO (`DosFileAttributes`; it's cheap because it comes from the same attribute read).
- UI: hide hidden/system files by default behind a toggle (a UX decision). Hidden doesn't mean protected; the security lists in §4.1 do the protecting.

### 4.7 ACLs and the "permission" flags the UI uses
- `isExecutable` and `isWritable` come from `Files.isExecutable`/`isWritable`. On Windows, `isWritable` on a *directory* checks only the read-only attribute plus an ACL check. `isExecutable` on a directory maps to `FILE_TRAVERSE`. The UI (`GridFileEntry.svelte:37`, `RowFileEntry.svelte:40`) treats `!isExecutable` as "can't open". Verify on Windows that ordinary folders report `true` and the folder isn't greyed out. Both calls do an access check and are slow on large listings; measure.
- The read-only attribute on folders is ignored by Windows for most purposes (Explorer uses it for customization). Don't map it to "not writable" for directories.
- An `AccessDeniedException` from the OS should become a Filemat "Missing filesystem permission" response and not a 500. That is a general improvement that also helps Linux.

### 4.8 The Filemat data folder's own permissions
- Replace `PosixFilePermissions.fromString("rwxr-x---")` and `rw-------` (`ServerApplication.kt`) with `AclPolicy.restrictToOwner(path)`:
  - Windows: `AclFileAttributeView` with protected DACL (no inheritance) and full control for the service account SID, `SYSTEM` and `BUILTIN\Administrators`.
  - POSIX: the current chmod.
- This matters for `setup-code.txt` and `auth-code.txt` (sensitive action auth) and for the DB.
- On failure: log a warning and continue? Or exit as Linux does? *Recommendation: exit only when running as a service account with an explicitly configured data dir. On an interactive dev run, warn.*

### 4.9 Running elevated
- Linux has a "current user" print and docs that recommend a dedicated user. On Windows, detect running as `LocalSystem` or an elevated Administrator (token elevation via JNA, or `whoami /groups` at startup) and log a prominent warning. Recommend a virtual service account (`NT SERVICE\Filemat`) or a dedicated local user.

---

## 5. Startup, data directory and configuration

### 5.1 Data dir
- `FM_DATA_DIR` env var, with defaults:
  - Windows: `%ProgramData%\Filemat`
  - Linux: `/var/lib/filemat`, unchanged
- Derive from it: DB, `setup-code.txt`, `auth-code.txt`, `logs\`, the default thumbnail cache (`%ProgramData%\Filemat\cache\thumbnails` vs `/var/cache/filemat`) and the default upload folder.
- Upload folder default on Windows: `%ProgramData%\Filemat\uploads`, *not* `%TEMP%`:
  - The service account's `%TEMP%` is `C:\Windows\Temp` or a service profile folder.
  - Storage Sense and Disk Cleanup purge temp folders.
  - The upload folder should be on the same volume as the common targets, so moves stay renames (§8.3).
- Replace every hardcoded literal: `Props.kt:13,18,19`, `ServerApplication.kt:38`, `SensitiveFolderPaths.kt` (`/var/lib/filemat`), and the frontend copies (§9).
- Expose the effective paths to the frontend through the setup/state endpoint, so the UI never hardcodes them.

### 5.2 SQLite
- `sqlite-jdbc` (xerial, through `spring-data-sqlite`) ships Windows x64/ARM64 natives, but extracts the DLL to `java.io.tmpdir` at startup. Under a locked-down service account or with AppLocker/WDAC, loading a DLL from temp can be blocked. Set `-Dorg.sqlite.tmpdir=<dataDir>\native` or ship the DLL alongside and use `-Dorg.sqlite.lib.path`.
- Reject a data dir on a network or mapped drive at startup: check `GetDriveTypeW` = `DRIVE_REMOTE` and UNC prefixes. SQLite locking over SMB corrupts databases. The branch already has `DatabaseLocationPolicy`; port it.
- WAL mode: `-wal`/`-shm` files are held open, and antivirus may scan them. Recommend a Defender exclusion for the data dir in the docs, not done automatically.
- Backups while the service is running must use the SQLite backup API or `VACUUM INTO`, not a file copy, which fails on Windows because the file is locked.

### 5.3 Charset and console
- Java 17 on Windows: the default charset is the ANSI code page (for example `windows-1252`). Java 18+ (JEP 400) defaults to UTF-8. The audit found that the server code uses Kotlin `readText`/`writeText`/`String(bytes)` (which default to UTF-8) or explicit UTF-8 (`utilFunctions.kt:249,260,541,547`, `TusService.kt:104`), so **nothing is affected today**. Java 21 is still recommended as a guard against future `java.io.FileReader`/`PrintWriter(File)` use, and for consistency with CI and Docker.
- Console output: set `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` in the start script, because the console code page is often 437/850 and box characters or non-ASCII filenames in logs are garbled.
- Filenames themselves are fine: Java uses the UTF-16 Win32 APIs.

### 5.4 Ports, firewall, binding
- Port 8080: Hyper-V/WSL/Docker reserve dynamic port ranges (`netsh int ipv4 show excludedportrange protocol=tcp`). Port 8080 sometimes lands in an excluded range and binding fails with "An attempt was made to access a socket in a way forbidden by its access permissions". Detect `BindException` and log this specific hint.
- On first run, Windows Firewall prompts for interactive runs. Services get no prompt, so the installer should add the rule (`New-NetFirewallRule`), opt-in.

### 5.5 Shutdown
- Windows has no SIGTERM. WinSW stops the service by sending Ctrl+C or Ctrl+Break, or by killing the process. Make sure Spring's graceful shutdown runs: `server.shutdown=graceful`, a WinSW `<stoptimeout>`, and `<stopparentprocessfirst>true`.
- Test that in-progress TUS uploads and the SQLite WAL checkpoint survive a service stop.

---

## 6. File operations on Windows

### 6.1 Sharing violations and locks
- Windows won't delete or rename a file that another handle opened without `FILE_SHARE_DELETE`. Java NIO opens files *with* share-delete. But other processes (antivirus, the Search Indexer, Explorer's thumbnail cache, an editor holding the file open, the FFmpeg child process) often don't.
- Possible sources inside Filemat: video range streaming (`FileContentService`), thumbnailing, zip creation, text editing, Tika detection, and the TUS upload store.
- Work items:
  1. Audit every `InputStream`/`FileChannel`/`RandomAccessFile` for `use {}`. Leaks are invisible on Linux and fatal on Windows. The server audit lists the call sites.
  2. Map `FileSystemException` messages "being used by another process" (`ERROR_SHARING_VIOLATION` 32, `ERROR_LOCK_VIOLATION` 33) and `AccessDeniedException` to a 409 "File is in use" response.
  3. Short retry with backoff (for example 3 × 100 ms) for delete and move. Antivirus locks are usually transient.
  4. **Delete-pending state**: after `DeleteFile` while another handle with share-delete is still open, the name remains until the last handle closes. Recreating the same name fails with `AccessDenied`, and directory deletes fail with "directory not empty". On Windows 10 1709+ this is mitigated by `FILE_DISPOSITION_POSIX_SEMANTICS`, which the JDK does not use. Handle it with a retry, or rename-then-delete: rename to `.filemat-deleting-<ulid>` in the same directory, then delete.
- `FileLockService`: the in-process locks key on `Path`, and `WindowsPath` equality ignores case, so they are consistent today. Switch them to `path_key` anyway, so correctness doesn't depend on the type of the key and lock keys match the DB keys.

### 6.2 Read-only attribute
- Deleting a file with the `READONLY` DOS attribute fails with `AccessDeniedException` on Windows, while the same delete works on Linux if the directory is writable. Decide whether to clear `dos:readonly` before delete. *Recommendation: yes, only when the user has Filemat `DELETE` permission, and log it.* Recursive deletes need the same handling.

### 6.3 Move, rename, copy
- `Files.move(..., ATOMIC_MOVE)` across volumes throws `AtomicMoveNotSupportedException`. Fall back to copy, then verify, then delete. Identity changes, so re-key the entity: after the copy, compute the new key and update the entity (§3) so permissions follow the file.
- Case-only rename (`photo.jpg` → `Photo.jpg`): on Windows the target "exists" because it's the same file. Existing "target exists" checks reject it. Detect `isSameFile(source, target)`, then rename directly; NTFS supports it. If that fails, two-step via a temp name.
- `REPLACE_EXISTING` onto a read-only or open target fails. Use the same error mapping as §6.1.
- Directory moves where the target directory is open in Explorer usually work. Moving a directory whose child is open fails with a sharing violation; the error message should name it.
- Name conflicts are case-insensitive: "a file named X already exists" checks must use `exists()` (the filesystem answers case-insensitively) and not compare strings in a listing.
- Copying preserves timestamps (`COPY_ATTRIBUTES`). On Windows that also copies DOS attributes but not ACLs. Files inherit the target folder ACL, which is desirable.

### 6.4 Timestamps and metadata
- Windows supports real creation time (`creationTime()`). Optionally show it.
- Timestamp precision is 100 ns on NTFS and 2 s on FAT, which matters for any "modified since" cache comparison (thumbnail cache invalidation). Use ≥2 s tolerance on FAT, or compare by size plus mtime.
- File size for sparse, compressed or dedup files: `size()` is the logical size. Fine.

### 6.5 Search and walk
- Walking `C:\` reaches 100k+ entries with many access-denied folders. Make sure `AccessDeniedException` per entry is swallowed, not fatal (the existing `safeWalk` probably does this; verify).
- Skip `System Volume Information`, `$Recycle.Bin` and `Windows\WinSxS` (huge, and hard links make it look bigger).
- Case-insensitive name matching is already done in the frontend type-ahead. Server search should use `equalsIgnoreCase`/`contains` with `Locale.ROOT`.

### 6.6 TUS uploads
- `tus-java-server` stores chunks plus `.info` files in the upload dir and uses file locks between requests. On Windows these locks are mandatory, so a stale lock from a crashed request blocks resume. Test concurrent PATCH, resume after a service restart, and cleanup of expired uploads when a chunk file is still open.
- Finalization moves the assembled upload into the target folder. Across volumes this is a copy (§6.3). For big files that's slow and needs a progress/timeout consideration.
- Validate client-supplied filenames and relative folder-upload paths with `PathPolicy.validateFileName` per segment (`FolderUploadService.kt:784-788`). A `\` in a client relative path must be treated as a separator on Windows and rejected as traversal if it produces `..`.

---

## 7. Media (thumbnails, video previews)

### 7.1 Current state
`javacv 1.5.11` plus `runtimeOnly` ffmpeg/opencv **`linux-x86_64` and `linux-arm64` classifiers only** (`build.gradle.kts:45-49`). The JAR is about 174 MB. On Windows, video thumbnails throw `UnsatisfiedLinkError`. Image thumbnails (Thumbnailator plus TwelveMonkeys) are pure Java and work.

### 7.2 Options
| Option | Pros | Cons |
|---|---|---|
| A. Add `windows-x86_64` classifiers | Least code | JAR grows to about 300 MB (or per-platform JARs); **no `windows-arm64` ffmpeg build from bytedeco**; natives extracted to temp (AV/AppLocker problems, as in §5.2) |
| B. Per-platform JARs (Gradle property `-Pplatform=windows-x86_64`) | Small JARs | More release artifacts; still no ARM64 |
| C. External FFmpeg executable (`FM_FFMPEG_PATH`, `ProcessBuilder`), the branch's `MediaPreviewBackend` | Small JAR (about 30 MB); any platform including ARM64; users can update FFmpeg for codec or security fixes | Process-spawn overhead; must harden argument passing; FFmpeg must be installed or bundled |

**Recommendation: C**, with opencv dropped if it's used only for thumbnails (confirm what opencv does). Bundle a pinned FFmpeg build in the Windows zip (gyan.dev or BtbN, LGPL build, for licensing). Keep javacv behind a feature flag for one release on Linux.

### 7.3 Hardening for the external process
- Never go through a shell. Use the `ProcessBuilder(list)` form only. On Windows the JDK still builds a command line string, and quoting rules for `"` and trailing `\` are fragile. Pass the input as `-i file:<path>` (forces the file protocol, so a filename like `http:...` or `concat:` can't change the protocol), or copy/hardlink to a temp name.
- Filenames starting with `-`: always use a full absolute path.
- Long paths (§2.5): prefix `\\?\`.
- Timeouts: kill the process tree on timeout (`ProcessHandle.descendants()`), cap concurrency, and use a low priority (`start /low` isn't available without a shell, so use JNA `SetPriorityClass` or accept normal priority).
- Locking: FFmpeg opens the input without share-delete, so a user deleting a video while its thumbnail is generated gets a sharing violation. Retry handles it (§6.1).

---

## 8. Packaging, service and distribution

### 8.1 Artifacts per release
- `filemat-<ver>.jar`: platform-neutral if media is external. Attach it to GitHub releases from CI; today JARs are uploaded by hand.
- `filemat-<ver>-windows-x64.zip`: app image containing:
  - a `jlink` JRE 21 (about 50 MB)
  - `filemat.jar`
  - `bin\filemat.cmd` (foreground start) and `bin\filemat-service.exe` (WinSW) plus `filemat-service.xml`
  - `ffmpeg\ffmpeg.exe` (pinned) and `LICENSES\` (FFmpeg LGPL notice, JRE GPLv2+CPE)
  - `install-service.ps1` / `uninstall-service.ps1`
- `filemat-<ver>-windows-arm64.zip` (phase 2).
- Later: an MSI (WiX or `jpackage --type msi`) with upgrade code, service install, firewall rule and data dir ACL.
- Code signing (Authenticode) for the service exe and scripts, or SmartScreen and Defender will warn. Budget for a certificate, or use Azure Trusted Signing.

### 8.2 Service wrapper
- **WinSW** (MIT; one exe plus XML; supports log rotation, stop timeouts, service account, failure restart). The branch already has `packaging/windows/filemat-x64.xml`. Alternatives: NSSM (unmaintained since 2017), Apache Procrun (`prunsrv`, more config but common), `sc create` with a Java wrapper (no).
- `install-service.ps1` must:
  1. require elevation
  2. create the data dir
  3. register the service with a virtual account `NT SERVICE\Filemat` (or a user-provided account)
  4. set the ACLs (`icacls` on the data dir: account full control, inheritance off, Administrators/SYSTEM full)
  5. set `FM_DATA_DIR` in the service XML
  6. optionally add the firewall rule
  7. start the service and print the setup code location
- Upgrades: stop the service → replace the app dir (the JAR is locked while running) → start. Never touch the data dir. Keep the previous app dir for rollback.
- Logs: `%ProgramData%\Filemat\logs` with rotation (WinSW `roll-by-size`). Also `FM_PRINT_LOGS` behaviour unchanged.

### 8.3 Gradle and build scripts
- Add a cross-platform build. Either:
  - a Gradle task chain: `npmCi` → `npmBuild` → copy `web/build` to `build/resources/main/static` → `bootJar`, using `npm.cmd` on Windows (`Os.isFamily(FAMILY_WINDOWS)`) or the `com.github.node-gradle.node` plugin, or
  - a single Node script `utilities/build.mjs` (branch).
- *Recommendation: the Gradle plugin, so `gradlew(.bat) bootJar` is the one command everywhere.*
- Keep `utilities/build.ps1` working, but make it non-interactive with switches, and use `Push-Location`/`Pop-Location`. Fix the usage text in `build-files.sh` and remove its dead variables.
- Add the foojay toolchain resolver to `settings.gradle.kts` and align on `jvmToolchain(21)`.
- A Gradle `windowsDist` task builds the zip (jlink plus WinSW plus FFmpeg download with a checksum pin).

### 8.4 Repo hygiene
- Root `.gitattributes` (from the branch): `* text=auto eol=lf`; `*.bat`/`*.cmd`/`*.ps1` as `eol=crlf`; `gradlew` and `*.sh` as `eol=lf`; binaries. Then run `git add --renormalize .` in a **dedicated commit**, because 323 files have CRLF in the working tree and 4 `.kt` files have mixed endings.
- Root `.editorconfig` (branch version). AGENTS.md already claims one exists.
- `web/package.json` `engines.node` (`>=20.19`), because `.npmrc` already has `engine-strict=true`.

### 8.5 Docker on Windows hosts
- The image stays Linux (Docker Desktop runs it under WSL2). Document `-v C:\Data:/data` and WSL paths, and that `user: 1111:1111` doesn't apply to Windows bind mounts.
- **Identity risk:** inode numbers on Docker Desktop bind mounts (grpcfuse/virtiofs/9p from NTFS) may not be stable across container or WSL restarts. If they aren't, permissions silently orphan. Test this, and document "use a Docker volume or WSL ext4 path for best results" if it fails.
- Inside WSL, `/mnt/c` (drvfs) has the same concern, and Windows files appear with fake POSIX permissions.

### 8.6 Later / optional
- Recycle Bin delete (`SHFileOperation` with `FOF_ALLOWUNDO` or `IFileOperation` via JNA). Only meaningful for an interactive user, not a service account. Skip.
- Windows Search integration for fast search. Skip.
- Event Log logging. WinSW can write service start/stop events.

---

## 9. Frontend (web/)

### 9.1 Path handling
The whole UI assumes POSIX. Centralize everything in one module, `$lib/code/util/path.ts`, driven by a `pathStyle: "posix" | "windows"` value that the server reports in the state/setup endpoint:
- `split(path) -> { root, segments }`. The root is `/`, `C:/` or `//server/share/`.
- `join(parent, name)` handles the root, so there's no more `//filename`. `pageLogic.ts:208-222` produces `//name` at the root today, which is a UNC path on Windows and a bug on Linux too.
- `parent(path)`, `filename(path)`, `isChildOf(child, parent)` (segment-aware, case-insensitive for the windows style), `equals(a, b)`, `isRoot(path)`.
- `display(path)` → `C:\Users\x` for the windows style, used in breadcrumbs, the details sidebar and "copy path" (`Breadcrumbs.svelte:46`).
- Replace these call sites (from the audit):
  - `codeUtil.svelte.ts`: `appendFilename` 138-150, `resolvePath` 361-365, `filenameFromPath` 427-429, `isChildOf` 434-442, `parentFromPath` 448-457, `appendTrailingSlash` 459-461, `isPathDirectChild` 769-774, `encodeUrlFilePath` 940-942
  - `filesState.svelte.ts:32-45, 509`
  - `breadcrumbState.svelte.ts:17-20`
  - `Breadcrumbs.svelte:41,46,59,84`
  - `fileUtilities.ts:42,80,100,113`
  - `FolderTreeSelector.svelte:42,67,153,185-201,242,248,257`
  - `files.ts:80,295,446,635,1088,1093-1097,1802,1859,1866`
  - `pageLogic.ts:189-193,208-222`
  - `appState.svelte.ts:96`
  - `+page.svelte:111`
- `uiUtil.ts:109-150` `prefixSlash` is used by the exposed-files input (`settings/system/exposed-files/+page.svelte:219-220`). It prevents entering `C:\Data`, so make it style-aware.

### 9.2 Routing and URLs
- Routes are `/files<rawPath>`. With windows-style paths: `/files/C:/Users/x`.
  - `:` in a non-first path segment is valid in URLs.
  - `\` is converted to `/` by the browser URL parser (WHATWG) in http(s) URLs. That's why the API format uses `/` only, and why any literal `\` in a Linux filename must be encoded as `%5C`.
- Route every navigation through one `filePageUrl(path)` that applies `encodeURIComponent` per segment. Unencoded sites to fix:
  - `files.ts:1918-1919`
  - `FileContextMenuPopover.svelte:76,89`
  - `pageLogic.ts:193`
  - `home/+page.svelte:7-8`
  - `Sidebar.svelte:42`
  - `stateUtils.ts:175-176` (`getZipContentUrl` default `encodeParam=false`)
- This fixes `#`, `?`, `%`, `&` and `+` in filenames on Linux too.
- The branch's opaque `v1.<base64url>` codec: it avoids all of these problems but makes URLs unreadable and un-editable, changes every existing bookmark and saved link, and adds a decode step to every request. *Recommendation: don't adopt it. Per-segment encoding plus a readable path is enough.* Keep it in reserve if a real case shows up that encoding can't handle.
- On the backend, make sure Spring/Tomcat accepts `%5C` and `%2F` in paths and query params (Tomcat rejects encoded slashes in the path by default; query params are fine). File paths are presumably query/body params today; confirm.

### 9.3 Filename validation
- Add `validateFilename(name, style)` to `$lib/code/util/validation.ts`, mirroring `PathPolicy.validateFileName`. Better: the server sends the rule set (invalid chars, reserved names, max length) in the state endpoint, so the two can't drift.
- Use it in: `handleNewFolder` (`fileActions.ts:116-169`), `handleNewFile` (`pageLogic.ts:208-222`), rename (`FileBrowser.svelte:261-275`), `FolderTreeSelector.svelte:42`, upload and folder upload (`folderUpload.ts:222,261,275,440-444`).
- The server stays authoritative; the UI only gives early feedback.

### 9.4 Case-insensitivity
- `getUniqueFilename` (`codeUtil.svelte.ts:508-537`; used in `files.ts:298-301` and `folderDownload.ts:148-151`) must fold case for the windows style. Otherwise uploading `photo.jpg` next to `Photo.jpg` overwrites it or fails.
- Strict `===` path comparisons stay correct only if the server always returns canonical case (§2.3). Keep `===` and rely on the server.

### 9.5 Setup and settings UI
- Remove hardcoded `/tmp/filemat`, `/var/cache/filemat`, `/var/lib/filemat/setup-code.txt` (`setup/+page.svelte:39,43,261,357`) and `/var/lib/filemat/auth-code.txt` (`SensitiveAuthDialog.svelte:17`). Render them from server-provided values.
- Path inputs (`HomeFolderPathSetting.svelte:61`, `UploadFolderPathSetting.svelte:80`, `ThumbnailCacheSetting.svelte:218-223`, exposed files): show a placeholder that matches the OS and validate the format client-side.
- Optional: a server-side folder picker that reuses `FolderTreeSelector` against the virtual root. It lists drives, which matters on Windows because typing paths is error-prone.
- Wording: "Symbolic links" → "Symbolic links and junctions" (`setup/+page.svelte:428-432`, `DetailsSidebar.svelte:373-374`). "Sensitive Linux folders" → "sensitive system folders".

### 9.6 Client-side download (independent of server OS)
- `folderDownload.ts` writes server filenames to the user's local disk through the File System Access API. On a **Windows client** downloading from a **Linux server**, names with `:`, `?`, `*`, `"`, `<`, `>`, `|`, a trailing dot or a reserved name fail today. Sanitize them on the client (replace with `_`) and make the reserved-name check case-insensitive. This bug exists now, independent of Windows server support. Good first PR.

### 9.7 Hidden files and sorting
- If §4.6 lands: a "Show hidden files" toggle.
- Optional: sort with `Intl.Collator(undefined, { numeric: true, sensitivity: "base" })` to match Explorer's natural sort.

---

## 10. Testing strategy

### 10.1 CI matrix
- New workflow, `platform-parity.yml` (port it from the branch), on PR and push:
  - `ubuntu-latest`, `windows-latest` (required), `ubuntu-24.04-arm` and `windows-11-arm` (optional or nightly)
  - JDK 21 (Temurin) via `actions/setup-java`, Node via `actions/setup-node` with an npm cache
  - backend: `./gradlew test` / `gradlew.bat test` (or `shell: bash` everywhere)
  - frontend: `npm ci && npm run check && npm test` (add a `test` script with Vitest for the path utilities)
  - a smoke job: build the JAR, start it with a temp `FM_DATA_DIR`, poll `/api/v1/...` health, run Playwright e2e (the branch has `platform-navigation.spec.ts`), stop.
- Release workflow: also build and attach the JAR and the Windows zip.

### 10.2 Unit tests to add (server)
- `PathPolicyWindowsTest`: drive-absolute, UNC, rejected forms (`C:foo`, `\foo`, `\\?\`, `\\.\`, `file:ads`, trailing dot/space, `CON`, `con.txt`, `COM¹`, `LPT0`), `..` containment, `\` normalization, case keys, `isRoot` for `C:/`, `//s/sh/`, `/`. Run it on all OSes where possible by testing pure-string logic separately from `Path` construction.
- `PathPolicyPosixTest`: `\` stays literal; existing behaviour unchanged.
- `FileIdentityTest` (per OS): key non-null on NTFS/ext4, stable across rename, changes on copy, `null` or unstable on FAT (use a VHD or skip in CI).
- `LinkInspectorTest` (Windows): symlink (needs Developer Mode on the runner; GitHub Windows runners run as admin, so it works), junction (`mklink /J`), mount point, junction loop, OneDrive placeholder (skip in CI).
- Entity reconnect tests: move a file with a permission, rename case-only, cross-volume move (CI runners have `C:` and `D:`; use both).
- Sharing violation: hold a `FileInputStream` without share-delete (via JNA `CreateFile` with share mode 0), attempt delete → expect a 409, not a 500.
- Security tests: `C:\PROGRA~1\...`, `c:\windows\system32\config\sam`, `C:\Windows\System32\config\SAM.` (trailing dot), `//127.0.0.1/c$/...` (UNC loopback must be rejected), `\\?\C:\Windows`, `C:\Users\x\AppData` through junction `Application Data`. All must be blocked.
- Migration tests: Linux fixture DB → migrate on Windows (paths don't resolve → orphaned safely); Linux DB → migrate on Linux → `file_key` backfilled.
- Existing tests that assume `/tmp` or POSIX paths: the server audit lists them (§14). Switch them to `@TempDir` and `Path` joins.

### 10.3 Manual release checklist
Port `docs/windows-beta-checklist.md` from the branch, and add:
- running as a virtual service account
- Defender real-time scanning on during the tests
- the OneDrive folder exposed to a user-run instance
- a service stop during an upload
- a drive-letter change on a USB drive
- a 300-char path
- a non-ASCII username profile (`C:\Users\Jürgen`)
- a CJK filename
- excluded-port-range port 8080

### 10.4 Docker Desktop check
- Run the Linux image with a Windows bind mount. Set a permission on a file, restart the container and WSL (`wsl --shutdown`), and confirm the permission is still attached (inode stability, §8.5).

---

## 11. Documentation updates
- README.md:33-35,51: requirements "Linux or Windows, Java 21 (or Docker)"; a Windows install section (zip plus service script); fix the Java version.
- README.md:65 and `docs/configuration.md`: the multi-value separator per OS; Windows examples; rename "sensitive Linux folders".
- `docs/configuration.md`: the Windows sensitive and non-deletable lists (§4.1, §4.2); a `FM_DATA_DIR` row; `FM_FFMPEG_PATH`; a UNC opt-in setting.
- `docs/technical-details.md`, `docs/settings.md`, `docs/database.md`: per-OS default paths; `file_key`/`path_key` columns instead of `inode`.
- `docs/docker-deployment.md`: a Docker Desktop on Windows section.
- New `docs/windows.md`: install, service account, ACLs, Defender exclusion, firewall, upgrade, backup (`VACUUM INTO`), known limitations (FAT identity, case-sensitive dirs, mapped drives, OneDrive placeholders, delete is permanent).
- README dev section and AGENTS.md: `gradlew.bat`, PowerShell env syntax (`$env:FM_DEV_MODE="true"`), `FM_DEV_BACKEND` on Windows.

---

## 12. Risks and open questions
1. **`fileKey()` returning null on Windows** (§3.2). If confirmed, JNA is required, which adds a native dependency and per-call overhead. Benchmark the listing of a 10k-file folder.
2. **Performance**: `toRealPath()` on every authorized request costs several syscalls per path segment on Windows and is much slower than Linux. Cache canonicalization results per request and per short TTL, keyed by `path_key`. Measure listing and thumbnail endpoints.
3. **Security surface grows**: Windows path aliasing is a known bug class. Plan an external review or fuzzing of `PathPolicy` (property-based tests generating random segment strings) before calling it stable.
4. **Linux regressions**: the path abstraction touches every file operation. Keep POSIX behaviour byte-identical (the same stored strings) and run the full Linux suite on every PR.
5. **Cross-OS DB portability**: not supported. If ever needed, add an admin "remap path prefix" tool (`/srv/data` → `D:/data`) that rewrites `path`/`path_key` and recomputes `file_key`.
6. **The `windows` branch**: commit `86899a3` is titled "concept change todo", so the author may already have changed direction. Confirm which of its design choices (opaque routes, external FFmpeg, `/tmp` → data dir on Linux) are still wanted before porting anything.
7. **ARM64**: depends on the media decision; JNA and sqlite-jdbc both ship win-arm64.
8. **Licensing**: bundling FFmpeg requires an LGPL build plus the notice; jlink JRE redistribution is fine (GPLv2+CPE).

---

## 13. Phased implementation order
Each phase is one or more small PRs, and Linux stays green throughout.

**Phase 0: prep (no behaviour change)**
1. Root `.gitattributes`, `.editorconfig`, renormalize commit.
2. Java 21 everywhere: toolchain, README, foojay resolver.
3. CI: a `platform-parity` workflow with Windows allowed to fail (`continue-on-error`) so the gap is visible.
4. Frontend: client-side download filename sanitization (§9.6) and route encoding fixes (§9.2). These are already bugs today.
5. Fix the `/foo` vs `/foobar` prefix bugs (server and `files.ts:1093`), and the `//name` at root bug.

**Phase 1: platform layer and startup**
0. Quick blockers that also harden Linux:
   - `BasicFileAttributes` in `FilesystemService.getMetadata`
   - lazy ffmpeg init in `ThumbnailService` that catches `Throwable` (degrade to "no video thumbnails")
   - surface the `initializeTusService()` failure
   - an `@ExceptionHandler(InvalidPathException)` that returns 400
   - `path.fileName` in place of `substringAfterLast("/")`
   - a `toRealPath()`'d absolute `Props.dataFolderPath`
   - a reparse-point check before recursing in delete, move and copy (it can't wait for Phase 4, because it causes data loss)
1. `Platform`, `PathPolicy` (POSIX implementation only, wired everywhere in place of the string ops; behaviour identical).
2. `FM_DATA_DIR`, `Props` derived paths, `AclPolicy` replacing `setPosixFilePermissions`, the env-var separator, `DatabaseLocationPolicy`.
3. Setup/state endpoint exposes `pathStyle`, the separator, default paths and filename rules; the frontend reads them.
→ *Milestone: the server starts on Windows.*

**Phase 2: Windows paths**
1. `PathPolicy` Windows implementation, the virtual root, the `VisibilityTrie`/`EntityPermissionTree` segment model, root guards, controller filename extraction.
2. `path_key` migration plus repositories plus `FileLockService` keys.
3. Frontend `path.ts` and call-site migration; breadcrumbs; display format.
→ *Milestone: browse, expose, upload and download on `C:` and `D:`.*

**Phase 3: identity**
1. `FileIdentity` (POSIX dev+ino, Windows JNA FileIdInfo), the `file_key` migration and backfill, the per-volume support detection.
2. Port `resolveEntityConflicts` to `file_key`; add the cross-volume move re-key.
→ *Milestone: permissions follow moves and renames on NTFS.*

**Phase 4: security hardening**
1. Windows sensitive and non-deletable lists, resolved via env/Known Folders.
2. Reparse-point policy (name-surrogate tags), junction-safe walk, UNC gating before any I/O, 8.3/trailing-dot/ADS rejection, `toRealPath` authorization.
3. Elevated-run warning.
4. A security test suite (§10.2).

**Phase 5: file operation robustness**
1. Stream-closing audit, sharing-violation mapping plus retry, delete-pending handling, read-only attribute, case-only rename, cross-volume move, TUS checks.

**Phase 6: media**
1. External FFmpeg backend with hardening; javacv behind a flag; Windows/ARM64 previews.

**Phase 7: packaging and docs**
1. Gradle web build integration, `windowsDist` (jlink plus WinSW plus FFmpeg), install/uninstall scripts, release workflow attaching the JAR and zip.
2. Docs (§11), the beta checklist, Docker Desktop notes.
3. Make Windows CI required. Ship as **beta**.

**Phase 8: post-beta**
UNC shares, ARM64 zip, MSI installer, code signing, hidden-file toggle, a server-side folder picker, a path remap tool.

---

## 14. Server code inventory
Paths are relative to `server/src/main/kotlin/org/filemat/server/`. Each line is a concrete target for the phase in brackets.

### 14.1 Startup and config [P1]
- `ServerApplication.kt:38,52,71`: hardcoded DB path; `setPosixFilePermissions` → exit.
- `config/Props.kt:13-19`; `src/main/resources/application.yml:6` (`jdbc:sqlite:/var/lib/filemat/...`). The DB location is duplicated, so derive both from `FM_DATA_DIR`.
- `module/service/AppService.kt:38-41`: `PosixFilePermissions.asFileAttribute` throws, so `setup-code.txt` is never written (the code is only printed to stdout).
- `module/auth/service/SensitiveAuthService.kt:69-70`: `auth-code.txt` written with no ACL restriction.
- `config/database/DatabaseSetup.kt:102`: forced `/` prefix on the upload path. `config/Initialization.kt:273` and `common/controller/SetupController.kt:279` ignore the `initializeTusService()` result.
- `common/State.kt:136`: `parseFileList` splits on `:`.
- `common/controller/ControllerAdvice.kt:13`: no `InvalidPathException` handler, so `<>:"|?*` and trailing spaces become 500s on every path-taking controller (`FileController`, `FolderController`, `FileUtilController`, `SavedFileController`, `PermissionController`, `AdminSettingsController`, `SetupController`, `TusService`).

### 14.2 Path model [P2]
- `common/util/utilFunctions.kt:219-224`: `getNormalizedPath`.
- `utilFunctions.kt:290-295`: `resolvePath` compares `rawPath == resolvedPath` as strings, so wrong case, 8.3 names and subst/mapped drives return **notFound**. Line 342 (`resolveNonExistent`) uses `Path !=`, which is case-insensitive on Windows. The two branches are inconsistent.
- `module/file/model/FilePath.kt:18,22,27,32`: `equals`/`hashCode` on case-sensitive strings vs case-insensitive `WindowsPath.startsWith`, which are mixed throughout the code. `FilePath.of` throws eagerly on invalid input; return a `Result`.
- Root guards: `FileDeletionService.kt:36`, `FileCopyService.kt:32`, `FileMoveService.kt:35`, `ThumbCacheSettingService.kt:73,150`.
- Separator logic:
  - `VisibilityTrie.kt:17,36,69,83,109`: fails closed, so nothing is exposed.
  - `EntityPermissionTree.kt:98-99,144-145,194-200`: `getClosestNode` returns the root, so non-admin users get nothing.
  - `EntityService.kt:256-260`.
- SQL prefix queries:
  - `EntityRepository.kt:43` and `SavedFileRepository.kt:34-35` use `path LIKE CONCAT(:prefix, '/%')`. Children are never found on Windows, so a folder move leaves child permissions and shares on stale paths.
  - SQLite `LIKE` is ASCII case-insensitive *and* treats `_` and `%` in paths as wildcards. That is an existing Linux bug: a folder named `a_b` matches `aXb`. Replace with `substr(path, 1, length(:prefix)+1) = :prefix || '/'` on `path_key`, or `LIKE ... ESCAPE '\'`.
  - `SavedFileService.kt:104` uses `startsWith("$oldPath/")`.
- Filenames: `FileController.kt:375,452`, `FileUtilController.kt:73,173`, `FileContentMetadataService.kt:55`, `FileShareService.kt:439` (a drive root's `fileName` is null, so the response shows "null").
- Wire format: `FilesystemService.kt:129` (`FileMetadata.path`) and `FileEntryListsService.kt:81-82` (search) emit `\`. Convert to the API format at the edge (§2.2).
- Settings stored as raw strings: `SetupController.kt:162,234`, `AdminSettingsController.kt:101-147`. Normalize to the canonical form and `path_key` before storing.
- Thumbnail cache key `md5(pathString)` (`ThumbnailService.kt:240,471,514-515`): use the canonical real path.

### 14.3 Attributes and identity [P1/P3]
- `FilesystemService.kt:100,130-138`: `PosixFileAttributes` → `BasicFileAttributes` + `DosFileAttributes`. Lines 134-135: `isExecutable` means almost nothing on Windows (true for most readable files). The UI greys out folders based on it, so on Windows report `true` for readable directories.
- `common/util/FileUtils.kt:23-40`: `unix:ino` / `supportsFileAttributeView("unix")`.
- `EntityService.kt:180-187` and `FileSecurityService.kt:313-317`: on Windows these always store `isFilesystemSupported=false`, so identity repair never runs.
- `src/main/resources/sqlite-schema.sql:48,54`: `inode` column plus unique index.

### 14.4 Security lists [P1/P4]
- `config/properties/SensitiveFolderPaths.kt:21-36,40-44`: Linux list; the regex uses `[^/]*` and `($|/.*)` and is case-sensitive. Build regexes from path segments with `IGNORE_CASE` on Windows.
- `config/properties/NonDeletableSystemPaths.kt:14-46`: exact, case-sensitive string match.
- `FileSecurityService.kt:218,230` (`forceDeletableFolders.contains`, case-sensitive).
- `FileVisibilityService.kt:37,68,70-75`: `hiddenFileTrie.insert(Props.authCodeFile)` splits on `/`, so the auth code file is not hidden. Blocked rules typed in a different case fail **open** when symlink following is on.
- Data folder checks to retest after the fix: `FileVisibilityService.kt:68`, `FileSecurityService.kt:218`, `FilesystemMoveService.kt:71,78,88`, `FilesystemCopyService.kt:86,93,103`, `FilesystemDeletionService.kt:56,68`, `FileContentService.kt:125,139`.

### 14.5 Links and junctions [P1 critical, P4 rest]
- `FilesystemDeletionService.kt:87-96`: **deletes junction target contents**.
- `FilesystemMoveService.kt:122-128`: moves the target's contents.
- `FilesystemCopyService.kt:125-131,148`: recurses with no `isAlreadyWalked`. Lines 248/255 copy symlinks as symlinks, which needs a privilege.
- `FileContentService.kt:194-210` (zip), `FileEntryListsService.kt:59-64` (search), `FileUtils.kt:106-107,182` (size): traverse junctions. The cycle guard prevents loops but not escaping the tree.
- `FilesystemService.kt:106-110`: junctions are listed as `FOLDER`, not `FOLDER_LINK`.
- `sharePath.kt:92` (`symlinkImmediateTargetsStayInside`) and `utilFunctions.kt:375` (`pathContainsSymlink`) ignore junctions.
- Java on Windows reports a junction as `isDirectory=true, isOther=true, isSymbolicLink=false` even with `NOFOLLOW_LINKS`. A cheap pure-Java detector is `attrs.isOther && attrs.isDirectory`, or `dos:attributes & 0x400`. Use JNA only to read the reparse tag (§4.4).
- `FileUtils.kt:237-248` `isFileStoreMatching`: a junction or folder-mount to another volume is judged by the link's store.

### 14.6 Locks and handles [P5]
- Handles opened without share-delete (java.io or native code): `ThumbnailService.kt:340,352,815-816,878-885`, `FileContentMetadataService.kt:113`, `FileContentService.kt:336`. Prefer NIO `Files.newInputStream` / `ImageIO.createImageInputStream(Files.newInputStream(...))`, which use `FILE_SHARE_DELETE`.
- `FileContentService.kt:70-90`: the read lock is released in `finally` while the returned stream stays open, which leads to the delete-pending state (§6.1).
- Read-only attribute: `FilesystemDeletionService.kt:435,443-445`, `FilesystemMoveService.kt:263`, `FilesystemService.kt:224-231`.
- Sharing violations map to a generic error: `FilesystemMoveService.kt:273-279`, `FilesystemDeletionService.kt:442-448`, `FilesystemCopyService.kt:263-269`.
- `FilesystemService.kt:216-242` `replaceFileContentsAtomically`: the temp file `.filemat-replace-*.tmp` isn't hidden on Windows. Set `dos:hidden`.
- Case-only rename is rejected: `FilesystemMoveService.kt:66-68` ("already exists"), `FilesystemCopyService.kt:82-83`, and `EntityService.kt:242` (`startsWith` gives "Cannot move folder into itself").
- `TusService.kt:50-55,276-277,295,350`: `FileChannel` locks under `<storage>/locks`, which are mandatory on Windows.

### 14.7 Filename validation [P2]
- No validation at: `FileContentService.kt:355-449` (create), `FileMoveService.kt` (rename/move), `TusService.kt:196-206,317-338` (the upload filename comes straight from metadata).
- `FolderUploadService.kt:55` **hardcodes `platform = FolderUploadPlatform.LINUX`**. So the Windows trailing dot/space rule (`:801-803`) never runs, and `duplicateKey` (`:807-812`) is case-sensitive. A validation framework already exists here: switch it to `Platform`, extend it with reserved names, invalid characters and UTF-16 length, and reuse it everywhere as the single `FilenameValidator`.
- A trailing dot is silently stripped by Win32 (`foo.` creates `foo`), and the entity path then no longer matches. Reading a reserved name such as `...\CON` can **block** the request thread.

### 14.8 Build and tests [P0/P6]
- `server/build.gradle.kts:45-49`: Linux-only bytedeco classifiers. `FileContentMetadataService.kt:135` catches `Exception`, not the `Error` thrown by a missing native.
- Tests that give false confidence on Windows: `FileSecurityServiceTest.kt:246-310`, `PathContainmentTest.kt:24-84`, `FileLockServiceTest.kt:16-54`, `FileSecurityServiceEntityRepairTest.kt:26,86`, `EntityPermissionTree*Test.kt`. Add cases built from real `Path.toString()` with `C:\`, case variants and drive roots.
- `SafeWalkTest.kt:242-258`: POSIX permission test; a Windows variant needs an ACL deny.
- `ApplicationContextTest.kt:35-45` never runs `initializeFileStructure`. Add a startup smoke test.
- Symlink tests: `testutil/TestLinks.kt:17-28` already falls back to `mklink /J`. Set `-Dfilemat.requireSymlinks=true` in Windows CI (runners are admin) so the 19 skipped tests actually run.
- Missing tests: case-only rename, junction delete, read-only delete, reserved names, `C:\` root operations.

### 14.9 Not affected (verified)
- No `WatchService`/inotify, no `ProcessBuilder`/`Runtime.exec` in main code, no `df`/`du`/mount logic, no `HOME`/`XDG`/`USER` lookups, no `Files.getOwner`.
- Charset: Kotlin and NIO defaults are UTF-8 (§5.3).
- Zip entry names use `invariantSeparatorsPathString` (`FileContentService.kt:149,279`), which is correct.
- `FileLockService` keys on `Path`, which is case-insensitive on Windows, so it is internally consistent. Still switch it to `path_key` so it doesn't depend on that.
- TwelveMonkeys, Thumbnailator, Tika and metadata-extractor are pure Java. sqlite-jdbc 3.47.2.0 bundles Windows natives (§5.2).

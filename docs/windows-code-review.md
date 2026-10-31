# Windows support: code review (2026-10-30)

A strict review of the whole `windows-support` branch against `main`, at `f345a6d` (a commit of the history before the squash of 2026-10-31). Open items found here are also listed in [windows-status.md](windows-status.md).

## How it was reviewed

- Read the status, overview, plan, test plan and changelog first, then ignored what they claim about the code.
- Reviewed the branch as one diff (`git diff main...HEAD`), not commit by commit: the 54 commits rework the same files many times, so only the end result matters. Server main code (~7.8k diff lines), web (~1.9k), build scripts and packaging (~0.9k) were read file by file. New files were read in full. Tests were skimmed for shared helpers and duplication, not re-verified case by case.
- Nothing was built or run during the review (the owner's rule: no builds or tests until asked). The clean-ups and the fixes were built and tested afterwards, on Windows and Linux (fixes 26 and 27, 2026-10-31).

Verdict: **CONFIRMED** means the code path was traced and the failure follows from it. **PLAUSIBLE** means it depends on Windows or JDK behaviour that wasn't checked on a machine.

## Findings

### Medium

**M1. Every start rewrites the ACLs of whatever folder `FM_DATA_DIR` names, with no guard.** CONFIRMED
[ServerApplication.kt:96](../server/src/main/kotlin/org/filemat/server/ServerApplication.kt#L96), [AclPolicy.kt:53](../server/src/main/kotlin/org/filemat/server/config/platform/AclPolicy.kt#L53), `WindowsNative.setOwnerOnlyDacl`.
`restrictOrExit(parentFolder, …)` runs on every start, and on Windows `SetNamedSecurityInfo` with a protected DACL replaces the inherited entries of *everything below* the folder. Nothing checks what the folder is. `FM_DATA_DIR=D:\Shared` (an existing folder with other accounts' files) or `D:\` makes every file below it private to the Filemat account on the next start. Run elevated with `FM_DATA_DIR=C:\`, it would rewrite the system drive. Linux only `chmod`s the folder itself, so the two OSes differ a lot here.
Fix: refuse a data folder that is a root (`PathPolicy.isRoot`), on the non-deletable list or a profile root, and only do the recursive rewrite when the folder is new or empty; otherwise warn and leave it.
**Fixed (2026-10-31), the simpler way the owner chose:** the data folder is restricted only when Filemat creates it on this start (`ServerApplication.initializeFileStructure`). An existing one keeps its permissions, and startup warns when other accounts can access it (`AclPolicy.isRestricted`). The database is still restricted on every start. The `native` and `logs` folders are restricted when Filemat creates them. Linux is back to `main`'s behaviour for the folder.

**M2. A NULL DACL counts as "already private".** PLAUSIBLE
[AclPolicy.kt:59](../server/src/main/kotlin/org/filemat/server/config/platform/AclPolicy.kt#L59).
The JDK's `AclFileAttributeView.getAcl()` returns an empty list for a NULL DACL, which grants everyone full access. `view.acl.all { … }` is `true` for an empty list, so such a folder (or database, setup code, auth code) is left open to everyone and nothing is printed.
Fix: `val acl = view.acl; if (acl.isNotEmpty() && acl.all { … }) return`.
**Fixed (2026-10-31):** `AclPolicy.isWindowsAclRestricted` returns false for an empty list, so `restrictToOwner` rewrites it.

**M3. Symbolic links to mapped network drives are followed.** PLAUSIBLE
[utilFunctions.kt:300](../server/src/main/kotlin/org/filemat/server/common/util/utilFunctions.kt#L300), [utilFunctions.kt:355](../server/src/main/kotlin/org/filemat/server/common/util/utilFunctions.kt#L355).
`reachesNetwork` only recognises UNC targets (`\\server\share`), and `resolvePath` only rejects a UNC result after `toRealPath`. A link whose target is a drive letter of a mapped network drive (`Z:\data`) is followed when "follow symbolic links" is on, so network files are served, against the decision that network shares and mapped drives are refused. `FilesystemService.getMetadata` also follows such a link to tell a folder from a file, which connects.
Fix: in `reachesNetwork`, also treat a link target whose drive is remote (`WindowsNative.isOnNetworkDrive(target) != false`) as the network; in `resolvePath`/`resolveNonExistent`, reject a resolved path on a network drive.
**Fixed (2026-10-31):** a new `isOnNetworkDrive(path)` in `utilFunctions.kt` is true when `localDrivePath` refuses the path: a network drive, a `subst` drive of a network folder, or a drive whose type can't be read. `reachesNetwork` checks it for a link target on another drive, and `resolvePath`/`resolveNonExistent` check the resolved path.

**M4. Deleting a symbolic link checks the path as typed, not the folder it is in.** CONFIRMED (also on `main`, on Linux too)
[FileDeletionService.kt:27](../server/src/main/kotlin/org/filemat/server/module/file/service/file/component/FileDeletionService.kt#L27).
For a link, `canonicalPath = withRealNames(rawPath)`: no part of the path is resolved. With links off, `C:/exposed/j/link`, where `j` is a junction (or `/exposed/j/link` with a symlink `j` on Linux) to an unexposed folder, is checked as if inside `C:/exposed`, and the link in the junction's target is deleted. On Windows a drive-less path (`/foo`) also gets through: `isSymbolicLink()` resolves `\foo` on the JVM's current drive, and `withRealNames` returns non-absolute paths unchanged. That second case needs an exposure rule on the drive list `/`.
Fix: do what `FileMoveService` does: resolve the parent with `resolvePath`, then append the name; reject non-absolute paths.
**Fixed (2026-10-31):** `FileDeletionService.deleteFile` resolves the parent folder, then checks whether the name in that real folder is a link. A path without a parent is a root, and a path without a drive is not found.

### Low

**L1. A `subst` drive that points to a mapped network drive isn't caught.** PLAUSIBLE
[utilFunctions.kt:318](../server/src/main/kotlin/org/filemat/server/common/util/utilFunctions.kt#L318).
`localDrivePath` checks the type of the requested drive, then maps `T:` to its target (`Z:\x`) without checking `Z:`. Whether `GetDriveType("T:\")` already reports the network for such a drive wasn't checked.
Fix: check `isOnNetworkDrive` again on the mapped path (or call `localDrivePath` on it, with a depth limit).
**Fixed (2026-10-31, fixes 27):** `localDrivePath` checks the folder a `subst` drive points to the same way, up to 26 drives deep, so a `subst` drive on a mapped network drive is refused before anything connects.

**L2. The prefix queries never match children of a root key.** CONFIRMED, not reachable today
[EntityRepository.kt:23](../server/src/main/kotlin/org/filemat/server/module/file/repository/EntityRepository.kt#L23), [SavedFileRepository.kt:35](../server/src/main/kotlin/org/filemat/server/module/savedFile/repository/SavedFileRepository.kt#L35).
`CONCAT(:prefixKey, '/')` gives `//` for `/` and `C://` for a drive root (`C:/`), so only the root row matches. Roots can't be moved or deleted now, but the orphaning `entityService.move(path, null)` in `repairEntityIdentity` and any later caller would silently leave the children.
Fix: compare against the prefix itself when it already ends with `/`.
**Fixed (2026-10-31, fixes 27):** both queries compare against `RTRIM(prefix, '/') || '/'`, and the saved-file update cuts the trimmed prefix, so `/` and `C:/` match their children and a moved child keeps one separator.

**L3. Case flags are never cached on FAT, exFAT or anything that can't report them.** CONFIRMED (performance)
[CaseSensitivity.kt:29](../server/src/main/kotlin/org/filemat/server/config/platform/CaseSensitivity.kt#L29).
A `null` from `isCaseSensitiveDirectory` isn't cached, so on a USB stick every `pathKey` opens a handle per path segment, every time. `pathKey` is computed for locks, visibility, entities and permissions, several times per request.
Fix: cache `false` when the folder exists and the flag can't be read; only skip the cache when the folder doesn't exist.
**Fixed (2026-10-31, fixes 27):** a null flag for a folder that exists is cached as `false`; a missing folder still isn't cached.

**L4. Starting with a drive unplugged can detach entities in case-sensitive folders on it.** CONFIRMED
[PathKeyRebuild.kt:34](../server/src/main/kotlin/org/filemat/server/config/database/PathKeyRebuild.kt#L34).
A missing folder counts as case-insensitive, so the start-up rebuild folds the keys of every entity on the missing drive. Two entities `E:/cs/a` and `E:/cs/A` then share a key and the newer one loses its path, with its permissions and shares. It reconnects through its file key only if the file is opened later.
Fix: leave the stored key of rows whose parent folder doesn't exist.
**Fixed (2026-10-31, fixes 27):** a row with a stored key whose parent folder doesn't exist keeps that key. Rows without a key are still computed.

**L5. Drive-root system files are hidden only on drives present at start-up.** CONFIRMED
[FileVisibilityService.kt:66](../server/src/main/kotlin/org/filemat/server/module/file/service/FileVisibilityService.kt#L66).
`hideWindowsSystemFiles` inserts `$Recycle.Bin`, `System Volume Information` and the page files per drive once. A drive plugged in later shows them when `FM_HIDE_SENSITIVE_FOLDERS=false` (the sensitive list covers them otherwise).
Fix: check the name of an entry directly below a drive root in `isPathAllowed` instead of inserting trie entries.
**Fixed (2026-10-31, fixes 27):** `isPathAllowed` blocks a path whose first name below a drive root is one of those names (ignoring case), and the trie entries are gone.

**L6. tus error responses are hand-built JSON.** CONFIRMED (also on `main`)
[TusService.kt:413](../server/src/main/kotlin/org/filemat/server/module/file/service/TusService.kt#L413).
`""" {"message":"$message",…} """` isn't escaped, so a message with `"` or `\` (a Windows path, a Linux file name with a quote in the folder upload errors) gives invalid JSON and the web shows a generic error.
Fix: serialize `ErrorResponse` like `AController` does.
**Fixed (2026-10-31, fixes 27):** `respond` writes `ErrorResponse(message, error).serialize()`.

**L7. The thumbnail cache folder is stored as typed, the upload folder as its real path.** CONFIRMED
[ThumbCacheSettingService.kt:73](../server/src/main/kotlin/org/filemat/server/module/setting/service/component/ThumbCacheSettingService.kt#L73), line 150.
`realInternalFolder` is only used as a check, and its resolved path is dropped. With a link or an 8.3 name in the setting, `folderPathChanged` (by path key) and `internalFolderPath` (by real path) compare different strings.
Fix: store the value `realInternalFolder` returns, as `SettingService.set_uploadFolderPath` does.
**Fixed (2026-10-31, fixes 27):** `saveForSetup` and `set_thumbnail` store and return the real path from `realInternalFolder`.

**L8. Moving uploads in progress can stop halfway.** CONFIRMED
[TusService.kt:88](../server/src/main/kotlin/org/filemat/server/module/file/service/TusService.kt#L88).
`uploads` is moved, then `locks`. If the second fails (the new folder already has `locks`), the uploads are in the new folder and their locks in the old one.
Fix: check that neither target exists before moving either.
**Fixed (2026-10-31, fixes 27):** both targets are checked before anything moves, and a failed second move moves the first one back.

**L9. Saving an edited file isn't atomic any more.** CONFIRMED
[FileContentService.kt:373](../server/src/main/kotlin/org/filemat/server/module/file/service/file/component/FileContentService.kt#L373).
`writeInPlace` writes over the file, then truncates. A failure in the middle (disk full, a lock on a later byte range) leaves the new start and the old end mixed together. Before, a failure left a short file, which was also bad but easier to notice.
Fix: write a temp file and swap it in, like `replaceFileContentsAtomically` (which already handles files in use and read-only files).
**Fixed differently (2026-10-31, fixes 27):** a temp file swapped in would be a new file, with a new file ID (permissions and shares follow it), the parent's ACL instead of its own, and no hard links. `writeInPlace` keeps writing in place, but locks the whole file first (another program's range lock gives 409 before anything is written) and writes the bytes past the old end first (a full disk fails before the old content is touched). A crash in the middle of the write can still mix the two.

**L10. The upload folder input can look unsaved after a save.** CONFIRMED (web)
[UploadFolderPathSetting.svelte:25](../web/src/routes/(app)/settings/system/_setting-components/UploadFolderPathSetting.svelte#L25).
`pathInput` only follows `savedPath` when it changes. When the server answers with the path already saved (typed in another case, or with a trailing `\`), the input keeps the typed text and Save stays enabled.
Fix: set `pathInput` to `savedPath` after a successful save.
**Fixed (2026-10-31, fixes 27):** the component sets `pathInput` to the path the server answers with.

### Info

- **I1.** The web folds case with `toLowerCase`, the server with per-code-point upper case ([path.ts:79](../web/src/lib/code/util/path.ts#L79)). They disagree on a few letters (`ı`, `ſ`). Only typed paths are affected; the server's answer wins. **Fixed (2026-10-31, fixes 27):** `foldCase` in `path.ts` folds like the server (upper case per letter, unless that gives more than one), and `isSamePath` uses it.
- **I2.** A `!` in the folder of `filemat.cmd` breaks the delayed-expansion echo ([filemat.cmd:19](../server/src/packaging/windows/filemat.cmd#L19)), so the "letters outside the code page" warning shows when it shouldn't. Harmless. **Wrong, checked on 2026-10-31:** the outer `cmd` runs without delayed expansion, so it passes `!FILEMAT_HOME!` on as it is, and the inner `cmd /v:on` doesn't expand the value again. The same lines run with `C:\Apps\a!b c\` and `C:\Apps\a!b!c\` give the same folder back. Nothing to fix.
- **I3.** `realInternalFolder` refuses the data folder and its parents, but accepts folders inside it such as `<data>\native` or `<data>\logs` ([State.kt:53](../server/src/main/kotlin/org/filemat/server/common/State.kt#L53)). The thumbnail clean-up only deletes cache-named files and tus only uses `uploads` and `locks`, so the risk is small.
- **I4.** `appendFilename`, `resolvePath`, `filenameFromPath` and `isChildOf` in `codeUtil.svelte.ts` now only forward to `path.ts`, and `filePageUrl` and `encodeUrlPathSegment` sit in `codeUtil` and split on `/` only. Folding them into `path.ts` touches about 16 files, so it's left for a pass that can run `npm run check`.

### Looked at and fine

`WindowsPathPolicy.parse` (drive-relative paths, ADS, device names, `\\?\` and UNC input are all refused), the FFmpeg runner (no shell, `file:` input, protocol and format whitelists, slot limit, both pipes drained, process tree killed), `SerialFilter`, the retry budget, `FileFailures`, the junction handling in delete, move, copy, walk and zip, the internal-folder checks for finished uploads, the V9 migration and its backup, the build's pinned downloads with SHA-256 checks, and the per-segment page URLs.

## Clean-ups made

Inconsequential changes only: no behaviour change. Built and tested with fixes 26.

- `FileUtils.kt`: removed `getPathRelationship` and `PathRelationship`, dead since the branch moved every caller to `State.app.isInDataFolder`; removed their tests in `PathContainmentTest`.
- Removed unused imports: `FilesystemService` (`Initialization.kt`), `UserPrincipal` and `UserPrincipalLookupService` (`AppService.kt`), `java.io.File` (`FileUtilController.kt`), `java.nio.file.Path` (`FileMetadataService.kt`), `AfterEach` and `assertEquals` (tests).
- Sorted the imports the branch had put out of order (`java.*` mixed into project imports, a project import after `kotlin.*`): `PathPolicy`, `WindowsNative`, `FileErrors`, `FileVisibilityService`, `FileContentMetadataService`, `FileContentService`, `FileMetadataService`, `EntityPermissionTree`, `FilesystemCopyService`, `FilesystemMoveService`, `FileContentServiceTest`.
- `WindowsPathPolicy.isSamePath`: one expression instead of two that both tested `a == b`.
- `ThumbnailService`: `?: throw` instead of `if (backend == null) throw`, like the video path next to it.
- `FolderUploadService.duplicateKey`: `if` instead of a `when` on a boolean.
- `TestLinks.kt`: `Platform.isWindows` instead of reading `os.name` again.
- `ServerApplication.kt`: removed a double blank line.
- `V9__add_files_path_and_file_keys.kt`: removed the comment about a development-only first V9 (never shipped), and cut the rebuild comment to one line.
- `fileActions.ts`: removed the leftover `console.log` of the new folder path.
- `VideoPreviewsSetting.svelte`: backticks instead of double quotes in script strings (AGENTS.md).
- `windows-overview.md`: two stale lines said W37 was still failing and that the plan's tracker lists open items; both now match windows-status.md.

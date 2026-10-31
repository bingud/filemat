# Windows Support Plan

Status: planning draft (2026-10-19), with the owner's decisions folded in (2026-10-24). Target: Windows 10 x64, the only tested OS (Windows 11 should work). Filemat ships as a **portable zip**: no installer and no Windows service in v1. It is always beta. Docker Desktop on Windows is best effort.

Decisions from §15 are folded into the sections. Where an answer changed the original recommendation, the section is marked **[decided Qn]**.

This plan is based on an audit of `main` (`aa7e2ab`). An older `windows` branch existed but is deprecated and deleted; nothing is ported from it, and everything here is built fresh on `main`. Each section lists **what breaks today**, **the decision**, and **the work items**. File references point to `main` unless they say otherwise.

Validation pass (2026-10-24, against `b201d62`, which changes only 2 server files since `aa7e2ab`, so the line references still hold): the JDK behaviour claims in §3.2 and §14.5 were confirmed by running a probe on Windows 10 with JDK 25. Corrections and newly found gaps are marked **[validated]**, **[corrected]** or **[gap]**. Every decision that needs the owner is collected in **§15**.

---

## Owner's brief (TL;DR of the real changes)

This section is the short version, for deciding. The sections below it are the detail. Open questions are in §15.

### Dependencies and toolchain
| Change | Detail |
|---|---|
| **Remove** `org.bytedeco:opencv` (both classifiers) | Not referenced anywhere in the code. Only `FFmpegFrameGrabber` and `Java2DFrameConverter` from javacv are used (`ThumbnailService`, `FileContentMetadataService`). Shrinks the JAR on Linux too (Q45). |
| **Keep** `javacv` + Linux `ffmpeg` natives, **add** an external `ffmpeg` backend | Two media code paths (Q44). javacv is used where its natives load (Linux x64/arm64, as today). Everywhere else the server runs `ffmpeg` through `ProcessBuilder`, found through `FM_FFMPEG_PATH`, then `PATH` (Q58). One JAR for every platform (Q57). |
| **Add** `net.java.dev.jna:jna-platform` | About 3 MB; ships win-x64/arm64 natives. Used only on Windows (Q8). |
| **Java** 17 → 21 | Toolchain and bundled JRE only. Kotlin `1.9.25` and Spring Boot `3.4.2` stay (Q7, Q9, Q56). |
| **Gradle** | Add the foojay toolchain resolver, the node-gradle plugin so `gradlew(.bat) bootJar` builds the web too (Q10), and a `windowsDist` task that builds the portable zip on your machine (Q65). |
| **Web devDeps** | None added. `npm run check` is the only frontend check (Q11, Q63). |
| **Unchanged** | sqlite-jdbc (already ships Windows natives), Thumbnailator, TwelveMonkeys, Tika, metadata-extractor, tus-java-server, Caffeine. |
| **In the zip only, not in the JAR** | jlink JRE 21, `ffmpeg.exe` (BtbN GPL, pinned with a checksum, Q46), `filemat.cmd`. No WinSW and no install scripts. |

### File identity ("entities") on Windows
- **Today**: an entity is identified by `unix:ino`. On Windows that attribute doesn't exist, so every entity is stored as "filesystem unsupported" and permissions and shares follow the path only. Moves and renames outside Filemat lose them.
- **New**: a string `file_key` column replaces `inode`.
  - Linux: `posix:<dev>:<ino>`. Adding the device number fixes an existing collision bug between two mounted filesystems.
  - Windows: `win:<volumeSerial>:<128-bit FileId>`, read with one native call per file. The call opens a handle with *no* access rights and full sharing, then calls `GetFileInformationByHandleEx(FileIdInfo)`. That is NTFS/ReFS's own file ID, the same thing Windows uses internally. Pure Java can't get it (`fileKey()` is `null`, verified).
  - The key survives renames, moves within a volume and case-only renames. It changes on copy, on cross-volume moves (Filemat re-keys its own moves) and when a "safe-save" editor writes a temp file and renames it.
  - FAT, exFAT and network volumes: path-only identity, the same as an unsupported filesystem on Linux today. The UI warns when a permission is set there (Q25).
  - Hard links: the key is unique and the first path wins (Q22). Permissions follow moves made through Filemat, including cross-volume moves, and never follow copies (Q23).
- **Path matching**: a new `path_key` column. On Linux it equals `path`, so nothing changes. On Windows each path segment is upper-case-folded **unless its parent directory has the per-directory case-sensitive flag** (`fsutil file setCaseSensitiveInfo`), in which case that segment is kept as it is (§2.3, Q36).
- **Drive letter changes** (USB drives): identity reconnects by volume serial, but exposure (`folder_visibility`) is path-based, so the folder becomes un-exposed. That fails safe and is documented (Q24).

### Media
- **Image thumbnails**: pure Java; they already work on Windows. No change.
- **Video thumbnails and video metadata**: javacv where its natives load (Linux, unchanged). Otherwise an external `ffmpeg` from `FM_FFMPEG_PATH`, then `PATH`.
  - The Windows zip bundles a pinned BtbN GPL `ffmpeg.exe`, and `filemat.cmd` points `FM_FFMPEG_PATH` at it. Nothing to install.
  - A plain JAR on Windows needs `ffmpeg` on `PATH` or in `FM_FFMPEG_PATH`.
- **No FFmpeg** means video previews are off and the server still starts. A warning shows in admin settings and in the startup log (Q47). The startup-safety part is already done (`FfmpegSupport`).

### Database (one new migration, V9)
- `files.file_key`, `files.path_key`, and `path_key` on `folder_visibility`, `saved_files` and other path tables. `saved_files` gets a table rebuild because its primary key changes. V9 drops `inode` (Q28).
- An automatic `VACUUM INTO` backup runs before V9. The Linux backfill stats every entity path once, so it may be slow; progress is logged (Q27).
- Prefix queries compare prefixes exactly (done for `path`; they move to `path_key` in V9).

### Configuration
- New env vars: `FM_DATA_DIR` and `FM_FFMPEG_PATH`. Env vars only; no config file (Q50).
- The multi-value separator is `;` on Windows; Linux keeps `:`.
- Windows default data dir: `%LOCALAPPDATA%\Filemat\` (DB, codes, `logs\`, `cache\thumbnails`, `uploads`) (Q59). The portable app folder holds no data.
- Linux defaults are unchanged, including uploads in `/tmp/filemat` (Q54).

### API and frontend
- The state/setup endpoint gains `pathStyle`, the separator, the effective default paths and the filename rules. Filenames are validated by the rules of the server's OS (Q17).
- Folder listings gain `caseSensitive`, so the UI folds names only where Windows does (§9.4).
- File metadata: `createdDate` already exists and is the real creation time on Windows. There is no `isHidden`/`isSystem` and no hidden-files toggle; Windows hidden and system files are shown like any other file (Q18, Q19).
- Windows paths go over the wire as `C:/Users/x` and are shown and copied as `C:\Users\x` (Q12, Q14). Linux strings are unchanged byte for byte.
- The virtual root `/` lists all available drives to every user. Exposure and permissions can be set on drive roots (Q15, Q61).
- Routes are encoded per segment, as in `/files/C:/Users/x`, and the SPA fallback needs a Tomcat encoded-slash setting (Q13). A URL with the wrong case is served and rewritten to the canonical case (Q16).
- Frontend: a new `$lib/code/util/path.ts` and about 15 files migrated to it. Sorting is unchanged (Q20). The folder picker comes later (Q21).

### New server code
- Package `config.platform` (`server/src/main/kotlin/org/filemat/server/config/platform/`): `Platform`, `PathPolicy` (POSIX and Windows, with `FilenameRules` and `validateFileName` for names, also used by `FolderUploadService`), `FileIdentity`, `CaseSensitivity` (per-directory flag, Windows), `LinkInspector` (symlinks plus junctions), `AclPolicy` (data-dir protection), `DatabaseLocationPolicy`, `WindowsNative` (JNA), `WindowsSystemFolders`, `ConsoleLogFile` and `WindowsPortFailureAnalyzer`. There is no `AtomicFiles` or `FilenameValidator`: in-use retries and read-only checks are `FileErrors` (`common/util`), and the move fallbacks stay in `FilesystemMoveService`.
- All Windows native calls (JNA): `CreateFileW`, `GetFileInformationByHandleEx` (`FileIdInfo` and `FileCaseSensitiveInfo`), `GetVolumeInformationW`, `GetDriveTypeW`, `FindFirstFileW` (reparse tag) and a token-elevation check. Nothing else.

### What Linux users will notice
- Java 21+ required.
- A one-time DB migration, with an automatic backup.
- Bug fixes:
  - `/foo` no longer matches `/foobar` in prefix queries and the frontend (done).
  - `_` and `%` in folder names are no longer wildcards (done).
  - Correct `//name` handling at the root (done).
  - Filenames with `#`, `?` or `%` work in URLs.
- Also changed (verification pass 3 found these missing here): the database is set to `rw-------` on every start, not only when created (the data folder too until code review M1 on 2026-10-31; it is again only set when created); an upload or cache folder setting of `/` or a parent of the data folder (`/var/lib`) is refused, so the upload service doesn't start with one until it's changed; folder uploads don't go through symbolic links unless links are followed; search needs a session or a share link; FFmpeg (javacv) may only open local files.
- Nothing else changes: media (javacv), stored paths, URLs, config and default folders.

### Release artifacts
- `filemat.jar`: one JAR for every platform. `bootJar` builds it as `server/build/libs/server-app.jar`; the docs, the README and the Windows zip call it `filemat.jar`.
- `filemat-<ver>-windows-x64.zip` and `-arm64.zip`, **portable**: unzip anywhere and run `filemat.cmd`. It contains the JRE, the JAR, `ffmpeg.exe` and licenses. No installer, service, registry entries or firewall rule (Q37).
- Both built on your machine (`gradlew bootJar windowsDist`) and uploaded by hand; there's no CI (Q55, Q65). Always beta (Q5), unsigned (Q49).
- An MSI and a Windows service come later (Q48). The ARM64 zip came in Phase 8 and is untested.

---

## Progress tracker

The history of the work: what each fix did and how it was checked. **What is still to do is in [windows-status.md](windows-status.md), not here.** A short write-up of each finished chunk goes in [windows-support-changelog.md](windows-support-changelog.md).

Legend: `[x]` done (not yet built or tested), `[v]` done and verified, `[ ]` to do (also listed in windows-status.md), `[~]` blocked on a §15 question.

**Fixes after the passes (2026-10-26)**
- Code: all phases, fixes 1-4 of the second verification pass, the third pass and fixes 7-23 are done:
  - [x] Test plan W37 (FAIL): a file opened through a `subst` drive showed the `subst` path (`S:/names/a#b.txt`) in the page and its metadata, not the real path. Fixes 20: `FileMetadataService.displayPath` moves a `subst` path to the folder the drive points to first, so it matches the canonical path (a followed link keeps its own name, on the real drive). Two tests; checked by API with a real `subst S:` (also `s:` and another case). The test plan's W37 is PASS.
  - [x] The thumbnail of a large image decoded the whole image first (`ThumbnailService.streamImageThumbnail`: `Thumbnails.of(it).scale(1.0).asBufferedImage()`, to learn the size): a 12000 × 12000 TIFF took about +1.3 GB, on Windows and Linux. Not Windows-specific. Fixes 19: the size comes from the header (`readImageSize`, shared with the content metadata), and Thumbnailator gets the target size. Its subsampling only runs with `thumbnailator.conserveMemoryWorkaround=true` (off by default, and then only when the image would take a good part of the free heap), so `ThumbnailService` sets that property. Measured on Windows with a 12000 × 12000 tiled Deflate TIFF: `java.exe` private memory 729 MB → 1717 MB (+988 MB) before, 691 MB → 707 MB peak (+16 MB) after.
  - [v] B1, the second start fails (`idx_files_inode` in `sqlite-schema.sql`). Fixed in "windows support 7". Every test plan chunk started twice.
  - [v] B2, the first start on a new data folder fails: `Props` loaded `NonDeletableSystemPaths`, which called JNA before `jna.tmpdir` was set (`ServerApplication.kt` `main()`). Fixed in "windows support fixes 7": `Props.nonDeletableFolders` is a getter, `prepareStartup()` sets the properties before any JNA class loads, and the `native` folder is created after the data folder check. `StartupNativeFolderTest`; a JAR started twice on a new data folder works. Test plan W5 passed on the rerun.
  - [v] R18: copying a folder with a junction made an empty real folder where the link was (`FilesystemCopyService.kt`). Fixes 8: junctions and mount points are skipped and reported ("Links to folders were not copied."), the rest is copied; symbolic links are still copied as links. Test plan §9 and W27.
  - [x] R10/S7-a: `*` entries in `FM_HIDDEN_FOLDER_PATHS` never match, and nothing warned (`State.kt`, `FileVisibilityService.kt`). Fixes 9: no wildcards, but a startup warning per entry with `*` in `FM_HIDDEN_FOLDER_PATHS` and `FM_FORCE_DELETABLE_FOLDERS` (`FM_NON_SENSITIVE_FOLDERS` does take `*`, as it names sensitive-list patterns). §4 and `docs/configuration.md` fixed.
  - [x] R12-a: an FFmpeg slot was waited for up to 30 s on the request thread, then a plain `IOException`, not a 503 (`ExternalFfmpegBackend.kt`). Fixes 10: 2 s at most, then `FfmpegBusyException`, which the thumbnail, video preview and content metadata endpoints answer with 503 "Video previews are busy. Try again in a moment." Nothing is cached for it; a metadata batch stops there and leaves the rest out, so the web asks again.
  - [x] R23 (decided by the owner, 2026-10-26: yes): add Brave, Chromium, Vivaldi, Opera, Thunderbird, `Microsoft/TokenBroker`, `IdentityCache`, `.git-credentials`, `_netrc`/`.netrc`, PSReadLine history and `Windows/CSC` to the Windows sensitive list. Done in fixes 11 (`SensitiveFolderPaths.kt`, `docs/configuration.md`).
  - Steps 5-10 of the second pass (clean-up, tests, packaging nits): about 60 smaller items. The ones still open are in [windows-status.md](windows-status.md).
    - [x] Fixes 23: a test for fixes 18 (`FileUtilControllerThumbnailTest`); a root `.gitattributes` with `*.cmd text eol=crlf`; test plan steps W18, W31, W33, W41 and L12 corrected (see their Notes).
    - [x] Fixes 22: 1.7-d, a missing create right in the folder was reported as "File is in use" when a file was replaced (`FilesystemService.replaceFileContentsAtomically`: `isTargetInUse` read any access denied on a writable target as in use). Only the swap step still reads it that way (Windows reports a replace of an open file as access denied); access denied while the temp file is made or filled is now "Missing permission to replace file." (rejected). Sharing and lock violations (32, 33) are in use at every step; the retry is unchanged. A test with an `icacls` deny on the folder (failed before the fix).
    - [x] Fixes 21: N4, with links off a junction that differs only in case (`a` -> `A` in a case-sensitive folder) was followed: `Path.equals` ignores case on Windows, so `isSamePath` called the link and its target the same path. `WindowsPathPolicy.isSamePath` now also compares path keys (case folded only where the folder allows it). Tests with a real junction in a case-sensitive folder; a wrong-case path in a normal folder still opens.
    - [x] Fixes 18: a failed image fallback or video preview answered 200 `image/jpeg` with the error text and the long cache header (`ThumbnailService.kt`; not Windows-specific). It throws now, so the controller answers 500 without cache headers. Tested in fixes 23 (`FileUtilControllerThumbnailTest`).
    - [x] Fixes 17: C17 (`ServerApplication.kt` indented with spaces) and C8 (the package stays `config.platform`; the plan says so).
    - [x] Fixes 16: C19 (web: `isPathDirectChild(parent, child)` is gone; callers use `isDirectChildOf(child, parent)` from `path.ts`, the same order as `isChildOf`).
    - [x] Fixes 15: C13 (the five data folder checks use `State.app.isInDataFolder`) and C2 (both path key rebuilds stay, with a comment why).
    - [x] Fixes 14: C4 (the dead Windows dot/space branch in `FolderUploadService`) and C5 (`toSubtreeRegexList` for the sensitive list, `toExactRegexList` for the non-deletable list).
    - [x] Fixes 13: Q2 (`mklink /J` once in `TestLinks.kt`) and Q5 (`FILEMAT_REQUIRE_WINDOWS_FEATURES`).
    - [x] Fixes 12: Q6 (a retry test depended on a 150 ms sleep), R21 (docs that disagreed with the code: this tracker's package list, the JAR names, the `deleteFiles` KDoc) and test plan W8 (non-ASCII checked through the data folder's name).
- Manual tests: [windows-test-plan.md](windows-test-plan.md) is the record. All `agent` checks (chunks A-E) ran on 2026-10-26, on Windows 10 Pro x64 and WSL2 Ubuntu: 38 PASS, 8 PART, 3 SKIP, 0 FAIL (W37 failed and was fixed in fixes 20). The checks left for a person are listed in windows-status.md. The open manual items below point at the test plan check that covers them.
- Automated tests: server 843, 0 failed (41 skipped) on Windows at 2dd389f; 830, 0 failed on Linux in WSL (149 Windows-only skipped) at 4204132; web 57, svelte-check 0 errors.

**Phase 0/1 quick fixes (decision-free, help Linux today)**
- [x] SQL prefix queries compare exactly, with no `LIKE` wildcards or ASCII case-folding (`EntityRepository.getAllByPathPrefix`, `SavedFileRepository.updatePath`).
- [x] `SavedFileRepository.exists` used `&&`, which SQLite doesn't support. It now uses `AND`. Only hit when the saved-file map is disabled.
- [x] Frontend whole-segment prefix checks after delete (`files.ts`, navigate-to-parent logic).
- [x] Root `//name` when creating a blank file (`pageLogic.ts handleNewFile`, now `resolvePath`).
- [x] Folder download: cleaned-up local filenames on Windows clients (`folderDownload.ts toLocalFilename`).
- [x] FFmpeg natives load once through `FfmpegSupport`. A load failure logs a warning and disables video previews, the FFmpeg image fallback and FFmpeg metadata, instead of failing startup.
- [x] `@ExceptionHandler(InvalidPathException)` returns 400.
- [x] `initializeTusService()` failures are logged, and printed at startup.
- [x] Junction-like directories (`isDirectory && isOther`) are never recursed into by delete, move or copy (`isJunctionLike` in `FileUtils.kt`).

**Verification still needed for the items above** (not run yet, at the owner's request)
- [v] `./gradlew build` and `./gradlew test` in `server/`, plus `npm run check` in `web/` (on Windows, 2026-10-24: 443 tests, 0 failed, 23 POSIX-only skipped; svelte-check 0 errors). Fixed on the way: `FileController` used `path.fileName` on a `FilePath` (now `path.filename`), and `FileSecurityServiceTest` hard-coded `/var/lib/filemat` (now `Props.dataFolderPath`).
  - Gradle 8.11.1 cannot run on JDK 25 (fails with just "25.0.3"). Run it with `JAVA_HOME` on JDK 21, or upgrade the wrapper.
- [v] Linux: move and rename a folder that has a permission, a share and a saved file, with children named `a_b` and `a%b` next to `aXb`. Test plan L6 (PASS).
- [v] Linux: start with the FFmpeg natives removed from the classpath. The server must start and only video previews should fail. Test plan L9 (PASS).
- [v] Windows: delete, move and copy a folder that contains a junction to a non-empty folder. The target's contents must be untouched. Test plan W27 (PASS).
- [ ] Windows client against a Linux server: folder-download files named `a:b`, `CON.txt` and `name.`. Test plan L17.

**Known gaps in the quick fixes (follow-ups)**
- [x] Folder download: two server names that clean up to the same local name (for example `a:b` and `a?b`, or `A.txt` and `a.txt` on a Windows client) wrote to one local file. Fixed after verification 3: `uniqueLocalFilename` gives the second one ` (1)` in the plan step.
- [x] Moving a junction across volumes falls back to copy plus delete, which leaves an empty folder in place of the link. Phase 5: such a move is refused ("Links to folders cannot be moved to another drive."). Recreating the link on the other drive is not done.
- [x] Other recursive walks (folder size, search, indexing) still descend into junctions. Fixed in Phase 4: `safeWalk` and `measureFolderContents` skip junctions and mount points unless links are followed.
- [x] `FFmpegFrameGrabber.start()` runs outside `try` in `ThumbnailService`, so a failed start skips `release()`. Fixed in Phase 6: `JavacvBackend` releases the grabber in `finally`.

**Next (every §15 question is answered; nothing is blocked)**
- [x] Phase 0: root `.editorconfig`; opencv natives removed (Q45); `jvmToolchain(21)`, foojay resolver and README "Java 21" (Q56); per-segment page URLs through `filePageUrl` (§9.2, Q13).
  - [v] Verify: `./gradlew build` downloads or finds JDK 21, and video thumbnails still work without opencv on Linux. Test plan L8 (PASS; `.heic` not tried).
  - [ ] Verify: open, bookmark and reload files named `a#b`, `a?b`, `50%`, `a&b`, `a+b` and `a b` on Linux. Test plan L7: PART, the API part passed; the page, bookmark and reload part needs a browser and a JAR with the web app.
- [x] Phase 1: platform layer, `FM_DATA_DIR` (`%LOCALAPPDATA%\Filemat`), `AclPolicy`, the elevated-run warning. The server starts on Windows.
  - [x] 1a startup: `Platform`, `WindowsNative` (JNA `jna-platform` 5.14.0), `FM_DATA_DIR` and derived `Props` paths (DB, setup/auth code, Windows upload and thumbnail defaults in the data folder), datasource URL set in `main`, `AclPolicy` (data folder, DB, setup-code, auth-code), `DatabaseLocationPolicy` (no UNC or network drive), elevated/SYSTEM warning, `org.sqlite.tmpdir` in `<dataDir>\native`, `;` list separator for `FM_*_FOLDERS`, upload path keeps `C:` (no forced `/`), `BasicFileAttributes` in `getMetadata`, `FilePath.filename` in place of `substringAfterLast("/")`, real-path `Props.dataFolderPath`.
  - [x] 1a frontend: `/v1/state/select` `app` sends `platform` (`pathStyle`, `separator`) and `paths` (only before setup or when logged in). Setup page and the auth-code dialog read them.
  - [x] 1b: `PathPolicy` (interface + `PosixPathPolicy`, in `config/platform/`) wired in place of the string path ops: `getNormalizedPath`, root guards (`isRoot`), `VisibilityTrie`/`EntityPermissionTree` segments, `EntityService` child re-pathing, share root. `FilenameRules` in the state endpoint (`app.platform.filenameRules`); `Validator.filename` in the web checks new folder, new file and rename (§9.3). `FolderUploadService` takes its OS switch from `Platform` and checks each segment with `PathPolicy.validateFileName`. `PathPolicyPosixTest`.
  - Not in 1b: `FolderTreeSelector` and folder upload in the web (folder upload is checked by the server; the selector moves to `path.ts` in Phase 2). Server-side name checks on create/rename/TUS stay in Phase 2 (§14.7).
  - [v] Verify: Linux starts with no `FM_DATA_DIR` and uses `/var/lib/filemat` as before. (`ApplicationContextTest` passes on Windows.) Test plan L1 (PASS, upgrade from `main`).
  - [v] Verified on Windows (2026-10-24, non-admin):
    - First start creates `%LOCALAPPDATA%\Filemat` (`native`, the DB, `setup-code.txt`) and starts in about 6 s.
    - ACL: user, SYSTEM and Administrators with full control. In `%LOCALAPPDATA%` icacls shows them as inherited (`ID`), because Windows marks entries that match the parent's. Under a broader parent (`C:\Users\Public`) the same code leaves exactly these three explicit entries and drops Users.
    - `/v1/state/select` sends `pathStyle: windows`, `separator: \` and the `C:\Users\...\Filemat\...` paths.
    - No elevated warning in a normal console.
    - `FM_DATA_DIR=\\host\share\...` exits with the network-path message.
  - [ ] Still to verify on Windows: the elevated warning in an admin console, and `FM_DATA_DIR` on a mapped drive (`GetDriveType`). Neither could be tested here. Test plan W10 and W38.
  - [v] Bugs fixed after verification (2026-10-24):
    - The FFmpeg warning is logged on `ApplicationReadyEvent`, after the migrations.
    - `PosixPathPolicy.isRoot` checks structure (`root != null && nameCount == 0`). On Windows the old string check never matched `\`, so the root guards (delete, move, copy, thumbnail folder) did nothing there.
    - `WindowsNative.accountNameOfSid` returns null for an unknown SID. JNA returned the name `NONE_MAPPED`.
    - `VisibilityTrie.getVisibility` no longer marks an inherited visibility as explicit (`isExplicit` is not read anywhere yet).
  - [v] Unit tests for the Windows work so far (server 550 tests, 0 failed; web 37):
    - New server tests: `FilenameRulesTest`, `AclPolicyTest`, `WindowsNativeTest`, `DatabaseLocationPolicyTest`, `PlatformTest`, `PropsTest` (`resolveDataFolder` is now `internal` and takes env, OS and home), `StateEnvTest`, `ControllerAdviceTest`, `VisibilityTrieTest`, `FilePathTest`, `FolderUploadServiceNameTest`, `EntityServiceMoveTest`, `PathPrefixQueryTest` (runs the real `@Query` SQL on in-memory SQLite), `ThumbnailServiceFfmpegLogTest`.
    - Extended server tests: `PathPolicyPosixTest` (`isRoot` on every OS), `StateControllerTest` (platform, filename rules, paths), `FilesystemServiceTest` (`getMetadata`), `EntityPermissionTreeManagementTest` (moves to the root and under new parents), `ThumbCacheSettingServiceTest` (root folder).
    - Web: Vitest added (`npm test`). Tests for `filePageUrl`/`encodeUrlPathSegment`, `isChildOf`/`parentFromPath`/`resolvePath`, `Validator.filename` (same cases as `FilenameRulesTest`), `toLocalFilename`, `fetchAppState`.
    - `EntityServiceMoveTest` is Linux-only until the Windows `PathPolicy`: on Windows the stored paths are native `\a\b` strings, so moves re-path wrongly there today.
    - Not unit-tested: `main()` startup (`initializeFileStructure`, elevated warning, native folder), setup and auth code file writes (they write to the real data folder), `DatabaseSetup` upload path, `TusService` start-failure log, the setup page.
  - Found at startup: FFmpeg fails to load on Windows (no `jniavutil` for windows-x86_64), and the warning is logged before Flyway creates the `log` table, so on a first start it prints "FAILED TO INSERT LOG TO DATABASE". The natives come with Phase 6. The log ordering is a small bug.
  - Not in 1a: the `BindException` port hint (§5.4) and the `-Dstdout.encoding` flags (they go in `filemat.cmd`, Phase 7). The ACL is not marked protected, so a later inheritance push from the parent folder can add entries back.
- [v] Phase 2: Windows `PathPolicy`, `C:/` wire paths, drive-list root, `path_key` with per-directory case sensitivity (§2.3), frontend `path.ts`. Code and tests done; the milestone (browse, expose, upload, download on `C:` and `D:`) is not checked in a browser yet.
  - [v] 2a server paths (built and tested on Windows, 2026-10-24: 581 tests, 0 failed):
    - `WindowsPathPolicy`: API paths `C:/a/b` (either separator in, drive letter upper-cased), `/` is the virtual drive list. `parse` rejects drive-relative `C:foo`, `:` after the drive (ADS), names ending in a dot or space, reserved device names and control characters (400 through the `InvalidPathException` handler).
    - Paths without a drive (`/sub/file`) parse to root-relative `\sub\file`. They are share-relative paths. `resolvePath` returns the drive list as itself and rejects every other root-relative path, so nothing resolves against the current drive.
    - `FilePath.of` parses through `PathPolicy`; `FilePath.pathString` is the API string, so the DB, tries, responses and settings all get `C:/...`. `normalizePath()` returns the API string too.
    - `resolvePath` compares with `PathPolicy.isSamePath` (case-insensitive on Windows), so a path typed in the wrong case resolves to the canonical case instead of not found.
    - Drive list: `getMetadata("/")` is a read-only folder, the listing shows local drives (`FileSystems.rootDirectories` minus network drives and drives without media), `safeWalk` (search, folder size) walks each drive, zip of `/` is skipped.
    - Guards: `isPathEditable` (edit, delete, move, rename) and share reject the drive list; `FilesystemService.createFolder/createBlankFile` reject root-relative paths.
    - Fixed on the way: `EntityService.move` matched children on the native `Path` string (`C:\a`), so on Windows they never matched the stored API paths. Search and share listings sent native paths; the auth code file was not hidden on Windows (native string in a `/` trie); `File(pathString)` replaced with `path.toFile()`; share metadata used `fileName` (null on a drive root).
    - Tests: `PathPolicyWindowsTest`, `ResolvePathTest`, drive-list cases in `FilesystemServiceTest` and `FileSecurityServiceTest`, a drive-path case in `VisibilityTrieTest`. `EntityServiceMoveTest` now runs on Windows. The two backslash-in-a-name tests are POSIX-only.
    - Drive list access (Q15, Q61): every signed-in user can open `/` and sees every drive, even when `/` is not exposed. Opening a drive is checked like any folder (exposure and permissions). Folder size and search of `/` are refused (verification fixes 2), because they would walk every drive.
  - [v] 2b frontend paths (2026-10-24: `npm run check` 0 errors, 53 Vitest tests):
    - `$lib/code/util/path.ts` mirrors `PathPolicy`: `pathSegments`, `joinSegments`, `normalizePath`, `parentPath`, `filenameOf`, `childPath`, `isChildOf`, `isDirectChildOf`, `isSamePath` (case-insensitive on Windows), `displayPath` (`C:\Users\x`), driven by `appState.platform.pathStyle`.
    - `codeUtil` `appendFilename`, `resolvePath`, `filenameFromPath`, `isChildOf` and `isPathDirectChild` delegate to it. `parentFromPath` stays string-based for relative upload paths; server paths use `parentPath`.
    - Moved onto it: the page path from the URL (`/files/C:/Users` opens `C:/Users`), breadcrumbs (full paths per segment, copy shows `C:\...`), Backspace to parent, go-to-parent after delete and on not found, upload and new-folder targets, move/copy/rename parents, the context menu "open parent" link, the selection scope, `FolderTreeSelector`, folder-link entries.
    - The copy-path dialogs (context menu, upload and download panels) show native paths. `prefixSlash` does nothing on Windows.
    - A wrong-case URL is rewritten to the canonical path (`goto` with `replaceState`, Q16).
    - `getUniqueFilename` ignores case on Windows (until listings carry `caseSensitive`).
    - Not checked in a browser yet against a Windows server.
  - [v] 2c path keys (2026-10-25: server 605 tests, 0 failed; web 53):
    - `WindowsNative.isCaseSensitiveDirectory` (`CreateFileW` with no access rights, `GetFileInformationByHandleEx(FileCaseSensitiveInfo)`). `CaseSensitivity` caches the flag per exact path string for 30 s; a folder that can't be read (missing, FAT) counts as case-insensitive and is not cached.
    - `PathPolicy.pathKey`: the API path on Linux. On Windows each name is folded with simple per-code-point upper case unless its parent is case-sensitive; the drive letter is upper case; paths without a drive fold every name. `PathPolicy.isKeyWithin` compares keys by whole segments. `FilePath.pathKey` holds the key.
    - V9 adds `files.path_key` (unique index). Linux copies `path`; Windows computes keys (`PathKeyRebuild`). Every Windows start runs `PathKeyRebuild` again, so flags changed while the server was stopped are picked up. When two entities get the same key, the older keeps it and the newer is orphaned.
    - Entities are looked up by key: `EntityRepository.getByPathKey`, prefix queries on `path_key`, the entity cache, both tries and the permission tree. `EntityService.move` builds new keys from the target key plus the old key suffix. A case-only rename is not a "move into itself".
    - Entity repair stores the current case of a path whose key still matches (a case-only rename outside Filemat).
    - `FileLockService` and the entity verify locks key on path keys; ancestors and children are found by segments.
    - Visibility rules: keys are computed in memory when the rules load and when one is added, so no DB column. The trie lists rules by the path as entered.
    - Folder listings carry `caseSensitive` for the listed folder. Uploads pick a free name with it (fold only where the folder ignores case).
    - Changed from the plan: `saved_files` and `folder_visibility` get no `path_key` column (saved files still match by path, which only affects the saved star after a case-only rename outside Filemat). V9 gets its `VACUUM INTO` backup with Phase 3, which also adds `file_key` to it.
    - `docs/database.md`: per-OS data folder, `files.path_key` and the columns of V2-V8 that were missing, and a Migrations section (how Flyway runs, V2-V9, rules for new migrations, the Windows key rebuild). Phase 3 added `file_key` (in V9), the migration backup and a File keys section.
    - Known limits: a flag toggled while the server runs keeps the old keys until the next start. Lookups miss, which fails open for a permission that restricts a child of a granted folder and for hide rules below the folder (verification 3, V6); `fsutil ... disable` works on a non-empty folder. `FilePath.startsWith` and other `Path.startsWith` checks still ignore case on Windows; for block lists that over-matches (safe), for the rest it moves with Phases 4 and 5.
  - Not in Phase 2: UNC (Phase 8).
- [v] Phase 3: file identity (2026-10-25: server 624 tests, 0 failed). The milestone (permissions follow moves and renames on NTFS) is covered by tests, not checked in a browser.
  - `FileIdentity.fileKey`: POSIX `posix:<dev>:<ino>`, Windows `win:<volumeSerial>:<fileId>` from `GetFileInformationByHandleEx(FileIdInfo)` (`WindowsNative.fileId`, no access rights, full sharing, links not followed). No fallback to `GetFileInformationByHandle`, since Windows 10 and later all have `FileIdInfo`.
  - `FileIdentity.isSupported`: POSIX keeps the `unix` attribute view check. Windows checks the volume (`GetVolumePathName`, then `GetVolumeInformation` with `FILE_SUPPORTS_OPEN_BY_FILE_ID`, network drives excluded), cached per volume root for a minute.
  - V9 also adds `files.file_key` (unique index). `FileKeyBackfill` fills it for every entity whose file exists and sets `is_filesystem_supported` again, so Windows entities created before (all path-only) now get keys. Missing files keep a null key. Progress is logged every 1000 entities.
  - Before pending migrations run on an existing database, `DatabaseSetup` writes a `VACUUM INTO` backup next to it (Q27).
  - `inode` is no longer read or written; V9 drops the column and its index (Q28).
  - `EntityRepository.getByFileKey` and `updateFileKey`; `FileSecurityService.verifyEntityIdentity` (was `verifyEntityInode`) repairs by file key. A supported entity without a key (missing at backfill) takes its key once the file is back.
  - Hard links (Q22): `EntityService.create` makes a path-only entity when the key already belongs to another entity. Repair does not move an entity to another path of the same file when its own path still has the key.
  - Moves (Q23): after a Filemat move, `EntityService.move` reads the key and volume support of each moved entity again, so a move to another volume (or to FAT) keeps permissions. A key that belongs to another entity makes the moved one path-only.
  - Tests: `FileIdentityTest` (rename, move, copy, hard link, Windows key format, volume root), `FileKeyBackfillTest`, new cases in `FileSecurityServiceEntityRepairTest` and `EntityServiceMoveTest`.
  - `file_key` was first a separate V10 and was merged into V9 before release (2026-10-25), so there is one Windows migration as planned. A dev database that already ran the old V9 has to delete its V9 row from `flyway_schema_history` or restore its `.bak`, because Flyway doesn't checksum Java migrations.
  - Changed from the plan: no `(path, lastModified)` cache for keys; keys are read at the same places inodes were.
  - Q25: `EntityPermissionMeta.isPathOnly` (from the entity, or from the volume when there is no entity yet). The details sidebar shows a note under the permissions and in the create dialog.
  - Q26: at startup, `FileVisibilityService` warns for each exposed folder on a `9p`, `drvfs`, `virtiofs`, `fakeowner`, `grpcfuse` or `osxfs` mount (`FileIdentity.unstableMountType`, from `/proc/self/mounts`). Only a warning; entities there keep their keys.
  - Dropping `inode`: done in V9 (owner, 2026-10-26; V9 is not released yet).
- [x] Phase 4: security hardening (2026-10-25: server 684 tests, 0 failed, 37 skipped). Not checked in a browser.
  - `WindowsSystemFolders`: `%SystemDrive%`, `%SystemRoot%`, `%ProgramData%`, the Program Files variables and the `UserProfiles` Known Folder, with fallbacks on the system drive.
  - `SensitiveFolderPaths` uses the §4.1 list on Windows (only the credential, key and browser-profile parts of AppData, Q31/Q60), matched without case. `FM_NON_SENSITIVE_FOLDERS` entries also compare without case.
  - `NonDeletableSystemPaths` uses the §4.2 list on Windows, with one-segment stars (`C:/Users/*` protects each profile). `FileSecurityService.isPathDeletable` rejects every root (`PathPolicy.isRoot`), even when it is force-deletable; `FM_FORCE_DELETABLE_FOLDERS` compares without case on Windows.
  - Default hidden files (Q62): `$Recycle.Bin`, `System Volume Information`, `pagefile.sys`, `hiberfil.sys` and `swapfile.sys` at the root of each drive found at startup.
  - `LinkInspector` reads the reparse tag (`WindowsNative.reparseTag`, `FindFirstFileW`): name-surrogate tags are links, other tags are files with data (Q32, Q42). A folder whose tag can't be read counts as a link.
    - `isJunctionLike` now means a junction or mount point only, so OneDrive folders are deleted, moved and copied like folders.
    - Listings show junctions and mount points as `FOLDER_LINK`, and OneDrive placeholders and dedup files as `FILE`. Downloads work for them; image and video previews are refused.
  - `resolvePath` on Windows: network drives are not found before any file call; `subst` drives are moved to their folder first (Q34), and a `subst` drive on a network folder is not found. A link that resolves to a UNC path is not found.
  - Already done in Phase 2 and covered by the new tests: UNC input, `\\?\`, `\\.\`, ADS, trailing dot or space and device names are rejected when parsed; 8.3 names and junctions resolve through `toRealPath` and are not found unless links are followed.
  - `State` folder lists: blank entries are dropped (an empty entry became `/`), and Windows patterns with `*` are kept instead of failing to parse.
  - Tests: `SensitiveFolderPathsTest`, `NonDeletableSystemPathsTest`, `WindowsSystemFoldersTest` (pure strings, every OS), `LinkInspectorTest` (real junctions, and every tag case with the native call mocked), `WindowsPathSecurityTest` (§10.2 cases, network and `subst` drives mocked, plus one real `subst` drive), `FileVisibilityServiceTest` (sensitive list, default hidden files), new cases in `WindowsNativeTest`, `FilesystemServiceTest` (junction as folder link), `FileContentServiceTest` (downloads), `SafeWalkTest`, `StateEnvTest` and `FileSecurityServiceTest`. The `/etc` delete tests now use the system folder of the OS they run on.
  - Not done: a symlink to a UNC path is followed by `toRealPath` before the UNC check (it needs the privilege to create symlinks; Windows allows local-to-remote links by default). Fixed after verification 3 (V10). Link-aware move of a junction across volumes. Placeholder detection only for the files themselves: a folder of online-only files is still walked by search (names only, no content is read). `docs/configuration.md` still describes the Linux lists only (Phase 7 docs).
- [x] Phase 5: file operation robustness (2026-10-25: server 717 tests, 0 failed, 38 skipped; web check clean, 53 tests). Not checked in a browser.
  - `FileErrors`: a file in use is a plain `FileSystemException` or `IOException` whose text is the system message for error 32 or 33 (read with `FormatMessage`, so localized Windows matches too). Delete, move, copy, text edit and upload overwrite retry it 3 × 100 ms, then answer 409 "File is in use." (`Result.conflict`, error `in-use`), without the process name (Q39).
  - Q40 changed (owner, 2026-10-25): no rename-then-delete fallback and no `pending-deletes` sweep. Tested on Windows 10 22H2 NTFS: `DeleteFile` uses POSIX semantics, so the name is free at once even while a share-delete handle is open, and a folder with an open file inside can't be renamed anyway (§6.1).
  - Read-only files stay read-only (Q38): delete, text edit and upload overwrite fail with "File is read-only." A folder delete removes everything else and says read-only files were left. Rename and same-drive move work and keep the attribute; a move to another drive is refused before copying. A copy stays read-only (`COPY_ATTRIBUTES`).
  - Recursive delete, move and copy collect the reasons (`FileFailures`), so a folder result says "… Some files are in use by another program." or "… Read-only files were left in place." `/delete-list` now answers `{ deleted, failed: [{ path, message }] }`, and the web shows the first failure (before, failed deletes were silent).
  - Case-only rename (`photo.jpg` to `Photo.jpg`): `isCaseOnlyRename` (same path key, `isSameFile`). It is no longer "moved into itself" or "already exists"; it is one atomic move after checking RENAME on everything inside, because a plain `Files.move` does nothing for the same file. Copy to such a name is "already exists" (checked before locking, since both names share one lock). "Into itself" checks compare path keys, not `Path.startsWith`.
  - Move fallback (copy, then delete): only for errors that aren't "in use" or "read-only", and the copy is removed if the source can't be deleted, so no duplicate is left.
  - Text edit writes over the file and then truncates it. `Files.writeString` truncated a file that another program had locked before the write failed, which emptied it (also true of the old `writeText`).
  - Upload overwrite: replacing a target that another program has open is reported by Windows as access denied, so a writable, non-read-only target counts as in use. The `.filemat-replace-*.tmp` file is hidden while the upload is copied.
  - Handles: image size reading uses `Files.newInputStream` (share-delete) in place of `ImageIO.createImageInputStream(File)`. FFmpeg still opens files itself (Phase 6). `getFileContent` still releases its read lock when the stream is returned: with POSIX delete semantics, deleting a file that is being downloaded works as on Linux, and holding the lock would block renames for the whole download.
  - Delete dialogs on Windows say deleted files don't go to the Recycle Bin (Q41).
  - Checked, no change needed: `FileLockService` keys on path keys (Phase 2); folder upload paths treat `\` as a separator and reject `..` per segment (Phase 2); long paths work for file operations (the JDK adds `\\?\` itself; new test with a 300+ character path, Q43).
  - Tests: `FileErrorsTest`, `FileMoveServiceTest`, and new cases in the delete, move, copy, `FilesystemService` and `FileContentService` tests (files held open with java.io, which doesn't share delete; locks with `FileChannel.lock`; `dos:readonly`).
  - Not done: TUS runtime checks (concurrent PATCH, resume after a restart, expiry cleanup with an open chunk) need a running server and go on the manual checklist (§10.3). `tus-java-server` lock files are released by the OS when the process ends, so a crash leaves no stale lock. Creation time is not shown (§6.4, optional). Search doesn't skip `Windows\WinSxS` (§6.5, optional). A file in use by FFmpeg (javacv) is still opened without share-delete until Phase 6.
- [x] Phase 6: media (2026-10-25: server 744 tests, 0 failed, 38 skipped; the FFmpeg tests ran against `C:\ffmpeg\ffmpeg.exe`; web check clean). Not checked in a browser.
  - `MediaPreviewBackend` (`probe`, `firstFrame`) with two implementations. `MediaPreviewService` picks one once: `JavacvBackend` when `FfmpegSupport` loads the natives (Linux, unchanged), otherwise `ExternalFfmpegBackend` with `FM_FFMPEG_PATH`, otherwise `ffmpeg` from `PATH`. A set `FM_FFMPEG_PATH` is the only one tried, so a wrong value is reported and not silently replaced by another ffmpeg. The executable must run `-version` and print "ffmpeg version".
  - `ThumbnailService` (video previews, image fallback for AVIF/HEIC) and `FileContentMetadataService` use the backend. The old grabber code and `metadataFromGrabber` are gone.
  - External process (§7.3): `ProcessBuilder` with a list, no shell; input `-i file:<absolute path>`; `-protocol_whitelist file`; `-nostdin`; first frame as BMP on stdout; size and duration read from ffmpeg's input details (not localized); 30 s timeout, then the process and its children are killed; at most cores/2 (1 to 4) processes at once; stdout capped at 256 MB and stderr at 64 KB, both drained while it runs. `\\?\` is added for paths of 260+ characters (Q43; ffmpeg 5.1+ does it too). Only absolute paths are used, from `PATH` too, and on Windows only `.exe` (a `.cmd` or `.bat` would run through cmd.exe, which parses the arguments again).
  - `-noautorotate`, so frames and sizes match javacv. javacv also gets `protocol_whitelist=file` (a small Linux hardening). Tested: ffmpeg 7.x already limits a local playlist to `file,crypto,data`, so the option mostly guards older builds.
  - Q47: startup log line (warning "Video previews are off. …" with the reason, or info "Video previews use FFmpeg at …"), moved from `ThumbnailService` to `MediaPreviewService`. Admin → System settings has a "Video previews" card (`GET /v1/admin/system/video-previews`: `isAvailable`, `source`, `version`, `problem`).
  - Not done: low process priority (needs JNA `SetPriorityClass`; normal priority for now). FFmpeg still opens the input without share-delete, so deleting a video while its thumbnail is made can get "File is in use." after the retries (§6.1, §7.3). Linux javacv not run here (no Linux machine in this session).
  - Tests: `MediaPreviewServiceTest` (order, `FM_FFMPEG_PATH`, quoting, `.cmd`, logging), `FfmpegOutputTest`, `ExternalFfmpegBackendTest` (real ffmpeg, skipped without one: frames, stills, `-y ünïcödé` names, 280-character paths, non-media, playlist URL, timeout), and new cases in the metadata and admin controller tests.
- [x] Phase 7: packaging and docs (2026-10-25: server 751 tests, 0 failed, 38 skipped). `windowsDist` and the web build in `bootJar` were not run (a dry run shows the task order); the zip is not built or tried yet.
  - Gradle: the node-gradle plugin (7.1.0) with the installed node and npm (`npm ci`, Q10); `webBuild` runs `npm run build` and `bootJar` puts `web/build` in `BOOT-INF/classes/static` (`-PskipWeb` leaves it out). `build-files.sh` and `build.ps1` no longer copy the web build themselves. `build.ps1` takes switches (`-Windows`, `-Docker`, `-Push`) and asks nothing. `web/package.json` has `engines.node` (`^20.19.0 || >=22.12.0`, as Vite 7).
  - `windowsDist -PreleaseVersion=<v>` → `build/dist/filemat-<v>-windows-x64.zip` with `filemat\filemat.cmd`, `filemat.jar`, `jre\`, `ffmpeg\ffmpeg.exe`, `LICENSES\` (Filemat GPL, FFmpeg GPL, `THIRD-PARTY.txt` with source links). Windows only.
  - JRE: jlink (23 modules from jdeps plus runtime-only ones, `--compress=zip-6`) from a **Temurin 21** toolchain that Gradle downloads, not the installed Oracle JDK 21.0.2: Oracle's license doesn't allow redistributing a modified (jlinked) runtime, and that build lacks two years of security fixes.
  - FFmpeg (Q46): BtbN `autobuild-2026-07-31-14-10`, `n7.1.5-12-g1fdbca85aa` win64 GPL static, SHA-256 checked. It is the last month-end 7.1 build (BtbN moved to 8.1 and 9.0 in August 2026), kept until mid-2028. The next bump has to move to 8.1.
  - `filemat.cmd` (CRLF enforced by `FixCrLfFilter`): sets `FM_FFMPEG_PATH` to the bundled ffmpeg unless set, switches the console to UTF-8 (`chcp 65001`, restored after) with `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`, and passes arguments on. `org.sqlite.tmpdir` is already set in code (Phase 1).
  - Logs (Q53), Windows only: `ConsoleLogFile` copies everything printed (Filemat's own log lines are `println`, so Spring's `logging.file.name` would miss them) to `<dataDir>\logs\filemat.log`, rotated at 10 MB with 9 older files. A file error stops the copy, never the console.
  - Port hint (§5.4): `WindowsPortFailureAnalyzer` (in `META-INF/spring.factories`) replaces "Port 8080 was already in use" on Windows with the excluded-port-range check and how to change the port.
  - Docs (§11): new `docs/windows.md` and `docs/windows-beta-checklist.md` (§10.3, §10.4); README (requirements, Windows install, separator, new env vars, PowerShell dev setup), `configuration.md` (Windows sensitive and non-deletable lists, `FM_DATA_DIR`, `FM_FFMPEG_PATH`), `technical-details.md` and `settings.md` (Windows paths), `docker-deployment.md` (Docker Desktop), AGENTS.md (`gradlew.bat`, `windowsDist`).
  - Not done: building and trying the zip (the first item of the beta checklist). No `icacls` hint in the UI, only in the docs (§4.9). No Controlled Folder Access hint in error messages, only in the docs.
- [v] Phase 8, ARM64 zip (2026-10-25). Not built or run; there's no ARM64 PC to test on (Q4). No server code changed, so no tests ran; a `--dry-run` of `windowsDist` shows the task order for both zips.
  - `windowsDist` builds `filemat-<v>-windows-x64.zip` and `filemat-<v>-windows-arm64.zip` (`windowsDistX64` or `windowsDistArm64` for one). The server needs no change: JNA and sqlite-jdbc ship win32-aarch64 natives, and video previews use the external `ffmpeg.exe` there, as on x64.
  - FFmpeg arm64: BtbN `ffmpeg-n7.1.5-12-g1fdbca85aa-winarm64-gpl-7.1` from the same pinned release, SHA-256 checked.
  - JRE: Temurin is now pinned (`jdk-21.0.12.1+1`, both JDK zips SHA-256 checked) in place of the Gradle toolchain from Phase 7. The x64 JDK's jlink builds both runtimes from each target's jmods, and jlink only links jmods of its own exact version, which a floating toolchain can't promise. Bumps use the Adoptium API link in `build.gradle.kts`. The build also runs on an ARM64 PC with Windows 11 (the x64 jlink runs under emulation).
  - `THIRD-PARTY.txt` names the Temurin release. `build.ps1 -Windows` copies both zips. README, `windows.md`, the beta checklist (new ARM64 item) and AGENTS.md updated.
  - A full `windowsDist` downloads both JDKs (about 205 and 193 MB) and both FFmpeg zips. The x64 JDK is extracted whole, the arm64 one only for its jmods.
- [v] Phase 8, first run of the zip (2026-10-25). `windowsDist` built both zips (x64 205 MB, arm64 187 MB; the JAR is 122 MB of that). The arm64 `java.exe`, `jli.dll` and `ffmpeg.exe` are ARM64 PE files. The x64 zip ran from a folder named `Fílemat ok (x86) & co`, with a fresh `FM_DATA_DIR`, through setup in the app's browser pane.
  - Worked: setup, drive list, listing and opening `a#b`, `50%`, `a&b` and `写真.png` by URL and after a reload, image and video thumbnails (bundled FFmpeg), video range requests, text edit with UTF-8, create, case-only rename, copy, zip download, invalid names (`CON.txt`, `a:b`, `name.`) refused, read-only and in-use files refused with their reason, search (case, CJK, subfolders), Admin → System video card, the port hint, `logs\filemat.log`, a data folder created by Filemat gets the owner-only ACL.
  - Fixed: Java can't start from a folder with letters outside the ANSI code page (the launcher and the JVM read their own path in it, so `Fïlemat` on a cp1250 Windows fails with "could not find java.dll"). `filemat.cmd` now echoes its folder through that code page and warns. 8.3 short names don't help: the JVM reads its long path again.
  - Fixed: uploads failed with the Windows defaults. The upload folder (`<data>\uploads`) and the thumbnail cache (`<data>\cache\thumbnails`) are in the data folder, which is blocked for users, and Filemat's own move and delete hit the same checks ("Cannot move Filemat data folder contents."). `moveFile` and `deleteFile` take `isInternalMove` / `isInternalDelete`, used by the upload finish and cleanup and the thumbnail cache move and clear; they skip the checks only for files inside the data folder. The thumbnail cache was silently never written there either (`getCacheFile` checked the blocked path).
  - Fixed: a path in the wrong case (`FILES/report.TXT`) now shows its real case. The server returned the requested case, so the Phase 2b URL rewrite never ran; once it did, it aborted the parent folder load, and every aborted load navigated one folder up until `/files`. Aborted loads are now ignored, and the rewrite reloads under the real path.
  - Fixed (also on Linux): the app hung on its spinner when the browser refuses service workers (`serviceWorker.ready` never settles). It now gives up after 3 s, or at once without a registration.
  - Seen, not changed: the upload folder preview shows `uploads\uploads` (tus keeps `uploads` and `locks` inside the chosen folder); the drive list's details show 1970 dates and a summed size; the thumbnail cache field shows `C:/` paths. Changing the upload folder (`SettingService`) still moves the old one as a user move, so it would fail for the default; not tried.
  - Tests: server 754, 0 failed (38 skipped), with new cases for the wrong-case metadata and the thumbnail cache in the data folder; web 53; svelte-check 0 errors. The internal move/delete has no unit test (it needs files in the real data folder); the upload and the cache clear were checked in the running zip.
  - Not tried: the firewall prompt, an administrator window, Task Scheduler, a 300-character path, junctions, OneDrive, FAT32/exFAT, `subst`, Ctrl+C during an upload, an upgrade, Docker Desktop, the arm64 zip on ARM64.
- [x] Fixes from the second verification pass (2026-10-25), one commit per chunk, in the order the verification recommends. Item IDs (N1, S4, R3, C1, Q9 and so on) refer to that pass.
  - [x] 1, uploads and Filemat's own files (N1, S4, S3/R4, R3/C1, C13, N7, Q9; R14 and the case-only cache move 1.2-e came with it). Server 778 tests, 0 failed (38 skipped).
    - `State.internalFolderPath(InternalFolder)` is the one place that finds Filemat's own working folders: the real path of the upload folder or the thumbnail cache. A root, the data folder or a parent of it is never one. `moveFile` and `deleteFile` take `internalFolder` in place of `isInternalMove`/`isInternalDelete`: the user checks are skipped only for a source inside that folder, compared by real path and path key. The database and the rest of the data folder can't be reached this way, and moves into the data folder are refused again, checked on the real destination.
    - Folder uploads (N1): every target is resolved with the links setting before any check, so the data folder, sensitive, exposure and permission checks see where the files really land. With links off a link is refused; with links on its target is checked. Each folder is resolved and checked again when it is created, and each file's folder again when the file arrives.
    - S4: an upload folder (or thumbnail cache) outside the exposed folders works, on Linux (`/tmp/filemat`) and Windows. A failed cleanup of an upload's temp folder is logged (N7).
    - The data folder is injectable for tests (`State(dataFolderPath = ...)`, Q9). The default upload and cache folders are built from the real data folder (S3), and the unused `Props.dataFolder` is gone (C11). "Inside the data folder" is one function, `State.App.isInDataFolder` (C13).
    - Thumbnail cache: "delete cache" and "move cache" only touch cache files (`ThumbnailService.clearCache(folder)` / `moveCache`), never the rest of the folder (R14). A change of case of the cache folder is not a move (1.2-e).
    - Tests: `StateInternalFolderTest`, `FolderUploadServiceLinkTest` (links into unexposed folders, a `Local Settings`-style link deeper down, links followed, a link that appears after the session), internal move and delete cases in `FilesystemMoveServiceTest` and `FilesystemDeletionServiceTest` (with a temp data folder), and `ThumbnailServiceCacheTest`.
  - [x] 2, drive list and search (R1, N2, R2). Server 784 tests, 0 failed (38 skipped); web 54.
    - Folder size and search of the drive list `/` are refused for everyone, admins too: each would walk every drive. The web hides the search button there, and the details show no size, dates or "Calculate folder size" for it (they showed 1970 dates and a summed size).
    - Search (N2) needs a signed-in user unless it comes with a share link (it was `@Unauthenticated` for everyone). The search folder is checked with `isAllowedToAccessFile` before the walk starts, so a user can't make the server walk a folder they can't open. Searches are rate-limited per user (30, then one every 2 s), as share-link searches already were per IP.
    - The server still sends `0` dates for the drive list; the web hides them.
    - Tests: `FileControllerSearchTest`, `FileFolderEntriesServiceTest`, new cases in `FileEntryListsServiceTest`, `path.test.ts` (`isDriveList`).
  - [x] 3, JNA failure fails closed (R8, N5, 1.11-e). Server 788 tests, 0 failed (38 skipped).
    - `jna.tmpdir` is set to `<data>\native` (with `org.sqlite.tmpdir`), before the first native call, so JNA no longer unpacks to `%TEMP%`. Not true until B2 was fixed (fixes 7): `Props` made a native call first, so a first start on a new data folder failed. The data folder's ACL is decided by whether the folder existed before that call; `main` creates the `native` folder itself after the data folder check.
    - A null drive type counts as a network drive: `resolvePath` doesn't find the path and the drive list leaves the drive out. `DatabaseLocationPolicy` refuses a data folder whose drive type can't be read, so when JNA can't load, Filemat stops at startup with one message that says why and what to allow (`WindowsNative.unavailableReason`). Decided: refuse to start rather than run without drives.
    - File identity was already safe: no file key and no volume support make entities path-only, as on FAT. An elevation check that can't run prints a warning instead of nothing.
    - Tests: `WindowsNativeUnavailableTest` (starts a JVM where JNA can't unpack its DLL: data folder refused, drives not found, no drives listed, path-only identity), null cases in `WindowsPathSecurityTest` and `DatabaseLocationPolicyTest`, `unavailableReason` in `WindowsNativeTest`.
  - [x] 4, Filemat's own files (R17, N3, R14/1.9-f, R15, R5, R6, R7, C26; 1.4-a and 1.4-c came with N3). Server 798 tests, 0 failed (39 skipped); web 54.
    - Migration backup: created empty and private first (`AclPolicy.createPrivateFile`), then filled with `VACUUM INTO ?` (a bound parameter), so it is never `0644`. Only the three newest `.bak` files are kept.
    - Data folder and database (the data folder part was changed on 2026-10-31 by code review M1: only a data folder Filemat creates is restricted, an existing one is left with a warning): `AclPolicy.restrictToOwner` runs on every start and does nothing when they are already private, so an existing `FM_DATA_DIR` is made private too. Linux still stops when it can't restrict what it just created, and only warns for what already existed. On Windows the owner-only DACL is now written natively (`WindowsNative.setOwnerOnlyDacl`, `SetNamedSecurityInfo`): it is protected from the parent's, and it replaces the inherited entries of what is already inside (logs, backups, uploads, cache). Java's ACL view wrote with `SetFileSecurity`, which did neither. The `user.name` fallback is gone.
    - Upload and cache folders: `State.realInternalFolder` refuses a root, the data folder and its parents, at setup, in Admin → System and when the upload service starts. A stored `D:/` is read as `D:/`, not the drive-relative `D:`.
    - Changing the upload folder no longer moves the old folder as a user move (which the data folder blocked): `TusService.moveUploadsInProgress` renames its `uploads` and `locks` into the new folder. On another drive they stay, and the admin is told. Changing to the same folder does nothing. The `"str"` log message is gone.
    - Web: the setup page no longer shows `uploads` + `uploads`; the upload and cache folder fields show `C:` + backslash paths.
    - Decided (owner, 2026-10-26): an upload or cache folder inside an exposed folder is not refused. Setting permissions or exposure for these folders automatically is out of scope for now.
    - Tests: `DatabaseSetupTest` (backup is a full private copy, three kept; upload folder setting), `TusServiceTest`, a parent-grants-Users case in `AclPolicyTest` (inheritance removed from existing children, DACL protected, a later parent change doesn't reach it), new cases in `SettingServiceTest` and `ThumbCacheSettingServiceTest`.
- [x] Third verification pass (2026-10-26). Its report (`docs/windows-verification-3.md`) was removed once its findings were fixed or accepted; it is in git history. Fixes 1-4 hold. Server 802 tests, 0 failed (49 skipped on Windows; 138 on Linux in WSL, where the whole suite ran for the first time); web 54. A Linux upgrade of a real V8 database (1079 entities) and javacv decoding were run in WSL.
  - V1: an 8.3 short name (`SSH~1`, `CREDEN~1`) as the moved name, as a copied path with links followed, as a deleted link or as an overwritten upload target named the same file, but the sensitive list, hidden folders, unexposed rules and the data folder compare names. `withRealNames` (`toRealPath(NOFOLLOW_LINKS)` on Windows) gives those paths their real names; links are still not followed.
  - V4: image sizes were read through `MemoryCacheImageInputStream`, which copies a file into memory up to the header a reader seeks to (a TIFF's is often at the end). A channel-based stream seeks instead. Linux too.
  - V7: the media metadata of OneDrive online-only files was read, which downloads them (Q42). It is empty now, like their preview.
  - V8: tests wrote the setup code to the real data folder. Gradle gives tests `FM_DATA_DIR=build/test-data-folder`.
  - V9: folder downloads to a Windows client also rename `CONIN$`, `CONOUT$` and `NUL .txt`.
  - [x] V2: FFmpeg opened the local files an HLS playlist (`.m3u8`) lists, so anyone who can write a file got frames of any video Filemat can read (also on Linux since before this branch). Both backends set `format_whitelist` (`ffmpegFormatWhitelist`): video containers (`mov` also reads AVIF and HEIC) and single-image pipes. Playlists, manifests and `image2` sequences are refused. Checked with the bundled FFmpeg 7.1.5: mp4, mov, m4v, 3gp, mkv, webm, avi, ts, ogv, flv, wmv, mpg, AVIF, PNG, JPEG, WebP, BMP, TIFF, GIF and JPEG XL open.
  - [x] V3: tus reads `uploads/<id>/info` with Java deserialization, and users may write there (upload folder inside an exposed folder, or `/tmp/filemat` created by another local user). `SerialFilter` sets a JVM-wide `ObjectInputFilter` in `main()` and before tus starts: only `me.desair.tus.server.upload.*`, `java.lang.*` and `java.util.*`, with depth and size limits.
  - [x] V5: Filemat's move and copy recreated folders without the case-sensitive flag, so `a.txt` and `A.txt` collided. `createFoldersLike` gives each new folder the flag of its source folder (`WindowsNative.setCaseSensitiveDirectory`), or fails that folder when it can't.
  - V6, accepted: stale keys fail open (see §2c known limits). Needs someone who can toggle the flag on the server; not fixed, listed in the test plan's known limitations.
  - [x] V10: a symbolic link to a network folder was followed by `toRealPath`, listings, search walks with links followed and zip downloads. `reachesNetwork` reads link targets without following them, and such links are never followed.
  - [x] V11: share containment (`isPathInside`) compares path keys on Windows, so `Share` and `SHARE` in a case-sensitive folder are two folders.
  - [x] V12: the drive list `/` has no file key and no volume support.
  - V13, not changed: external FFmpeg output is capped at 256 MB per run, at most 4 runs at once; a decoded frame larger than 8192 × 8192 fails.
  - V4 follow-up: the channel-based image stream keeps the last 8 KB read, since readers read headers a byte at a time.
- [x] `inode` dropped and manual test plan (2026-10-26; server 813 tests, 0 failed, Windows and Linux).
  - V9 drops `files.inode` and `idx_files_inode` (Q28, changed by the owner; no separate migration, V9 is unreleased). `sqlite-schema.sql` keeps the column as the baseline V8 copies from. It also kept the index, which broke every start after V9 (B1); the index line is removed in the working tree, and `MigrationChainTest` runs the schema and every migration on a new database, then the schema again. A dev database that already ran V9 keeps an unused `inode` column.
  - [windows-test-plan.md](windows-test-plan.md): steps and expected results for every manual check (W1-W44 on Windows, L1-L18 on Linux), with `utilities/windows-test-fixtures.ps1` for the test files. It replaces the open manual items above as the place to run them; tick them here once run.
- [x] Code review of the whole branch (2026-10-30, [windows-code-review.md](windows-code-review.md)) and fixes 26 for its medium findings (2026-10-31; server 868 tests, 0 failed, Windows and Linux; web 76).
  - M1: only a data folder Filemat creates gets the owner-only ACL (or `rwxr-x---`); an existing one is left, with a warning when other accounts can access it. The database is restricted every start, `native` and `logs` when created.
  - M2: an empty ACL (NULL DACL) is not private. M3: link targets on a network drive count as the network, and a resolved path on a network drive is refused. M4: delete resolves the parent folder before checking for a link.
  - Fixes 27 (2026-10-31; server 879, 0 failed, Windows and Linux; web 77): the low findings L1-L10 and I1. L9 keeps writing in place (file ID and ACL stay), with a whole-file lock and the bytes past the old end written first. I2 was not a bug. I3 and I4 are left.
- Dropped: the `platform-parity` CI workflow (Q55), Vitest/Playwright (Q11), the `.gitattributes` renormalize (Q64), WinSW and install scripts (portable, no service).

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

**Approach [decided Q1, Q2]:** build it on the `windows-support` branch, in the phase order in §13, with Linux tests green at every step. Linux bugs are documented and fixed as part of the Windows work where they matter.

---

## 1. Scope and support targets

### 1.1 In scope [decided Q3, Q4, Q29, Q48]
- A native Windows backend, shipped as a **portable zip** and run in the foreground (console) as the current user. Auto-start is documented through Task Scheduler (§8.2).
- Windows 10 22H2 x64 is the target and the only tested OS. Windows 11 should work. Windows Server isn't tested.
- NTFS as the primary filesystem. ReFS should work but isn't tested. FAT32 and exFAT have path-only identity, with a UI warning (§3).
- Local drives (`C:\`, `D:\`). Mapped network drives are rejected and `subst` drives are resolved (Q34).
- Docker Desktop on Windows (Linux containers), best effort. Warn at startup if bind-mount inodes look unstable (Q26, §8.5).

### 1.2 Out of scope (for now)
- An installer (MSI), a Windows service, winget/Scoop/Chocolatey and code signing (Q48, Q49).
- UNC shares (Q35). Every UNC input is rejected before any I/O.
- Testing on ARM64. The arm64 zip is built since Phase 8, but there's no ARM64 PC to test it on (Q4).
- CI (Q55). Windows tests run by hand on the owner's Windows 10 PC.
- Windows containers (a `nanoserver` or `servercore` image).
- Exposing Windows ACL editing in the UI. Filemat permissions stay app-level, and the OS ACL is a hard lower bound.
- Recycle Bin integration. Delete stays permanent, and the delete dialog says so on Windows (Q41).
- macOS. It would benefit from the same abstraction, since APFS is case-insensitive by default, but isn't targeted.
- Moving an existing Linux database to a Windows host. All stored paths are absolute; see §12.4.

### 1.3 Decisions
All owner decisions are answered in §15 (2026-10-24). The ones that shape the design: Java 21 with JNA (Q56, Q8), `C:/` wire paths with per-segment routes (Q12, Q13), a drive-list root visible to everyone (Q15, Q61), per-directory case sensitivity (Q36), two media backends (Q44), and a portable zip with no service (Q48).

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
Add a small platform layer, `org.filemat.server.config.platform` (built there and kept there; the design below had `common.platform`). "New server code" near the top lists what it holds: `AtomicFiles` below was not built.

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
- Windows virtual root: `/`. A synthetic directory whose children are all available drives (`C:/`, `D:/`, including removable and optical drives with media), shown to every user. Exposure and permissions can be set on a drive root like any folder. Mapped network drives aren't listed (Q15, Q34, Q61). The root is never passed to `Paths.get`.
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

### 2.3 Case sensitivity [decided Q36]
Linux: every directory is case-sensitive and `path_key = path`, so nothing changes. Windows: directories are case-insensitive by default, but on Windows 10 1803+ any directory can be made case-sensitive with `fsutil file setCaseSensitiveInfo <dir> enable`. WSL does this for directories it creates. Filemat must handle both kinds of directory.

- **The flag belongs to a directory** and applies to the names directly inside it. It isn't inherited by existing subfolders, though new subfolders created inside a case-sensitive folder get it.
- **Reading it**: `CaseSensitivity.isCaseSensitive(dir)` opens the directory with no access rights and full sharing (the same JNA open as `FileIdentity`, §3.3) and calls `GetFileInformationByHandleEx(FileCaseSensitiveInfo)`. The directory is case-sensitive when `FILE_CS_FLAG_CASE_SENSITIVE_DIR` is set. When the call fails (an older build, FAT, exFAT or another filesystem without support), treat the directory as case-insensitive, which is the Windows default.
- **Caching**: keep the flag per directory in Caffeine, keyed by the directory's `path_key`, with a short TTL. An admin can toggle the flag while Filemat runs. Every create, rename or move that targets a directory re-reads its flag.
- **Lookup key (`path_key`)** is built segment by segment. A segment is folded when its parent directory is case-insensitive, and kept as it is when the parent is case-sensitive. Folding is `uppercase` per code point with `Locale.ROOT` and **no** special casing. NTFS compares names with its per-volume `$UpCase` table (simple uppercase mapping), and full case mapping would turn `ß` into `SS`, which NTFS doesn't do. Document that rare Unicode edge cases can differ. Example: if `C:/Proj` is case-sensitive, `C:/Proj/Readme` gets the key `C:/PROJ/Readme`, which is distinct from `C:/PROJ/README`.
- **Never compare `WindowsPath` objects** for authorization, identity or locking. `Path.equals`, `Path.startsWith` and `HashMap<Path, …>` on Windows *always* ignore case, so inside a case-sensitive folder they would treat `secret` and `Secret` as the same file. Every comparison goes through `path_key` strings and `PathPolicy.startsWith(childKey, parentKey)`, which is segment-wise. This covers `FileLockService`, `VisibilityTrie`, `EntityPermissionTree` and the existing server `Path.startsWith` checks. Those checks are fine on Linux (see below) but not on Windows.
- **Security lists match case-insensitively everywhere on Windows**, including inside case-sensitive folders. A rule for `C:/Users/*/.ssh` also blocks `.SSH`. This fails closed.
- **Display path**: always the canonical on-disk case, taken from `toRealPath(NOFOLLOW_LINKS)` for existing files. Never echo the client's casing back as canonical, because the frontend compares with `===`.
- **Name conflicts** ("a file named X already exists") use `Files.exists`, and the filesystem answers correctly for both kinds of directory.
- **Frontend**: folder listings carry `caseSensitive`, so `getUniqueFilename` and client-side duplicate checks fold case only where Windows does (§9.4).
- **Flag toggled on a folder that already has entities**: the stored `path_key`s of its direct children go stale. Check the flag against the cache when an entity is resolved inside the folder, and rebuild the children's keys when it changed. Until then a lookup can miss, which means losing an explicit permission (fail closed). Document this as a known edge case.
- **Performance**: building a key costs one cached flag read per ancestor directory. Only paths that touch the DB (entities, visibility, saved files, locks) need keys; listings don't.
- [corrected] On the server, nearly all prefix checks already use `java.nio.file.Path.startsWith`, which compares whole segments, so `/foo` doesn't match `/foobar` there. The real Linux prefix bugs were the SQL `LIKE CONCAT(:prefix, '/%')` queries (§14) and `files.ts:1093-1097` in the frontend. Both are fixed (see the progress tracker). On Windows these checks still move to `path_key` comparisons, as described above.

### 2.4 8.3 short names and other aliases
- `C:\PROGRA~1` is `C:\Program Files`, and `C:\Users\ADMINI~1` is a profile. Block-list and permission checks on the lexical string miss these.
- **Fix**: always resolve existing paths with `toRealPath()` before authorization. It expands short names and fixes case. For paths that don't exist yet (create, upload target), resolve the deepest existing ancestor with `toRealPath()` and append the validated remaining segments.
- Also reject any input segment that matches `~[0-9]` in 8.3 form and doesn't round-trip through `toRealPath`. This is defence in depth.
- Other aliases to consider: hard links (the same file under two paths, so the same file key; §3.4), `subst` drives, and mapped network drives. Mapped drives are per-logon-session and invisible to services, so document "use UNC instead".
- **[gap]** `toRealPath()` (with link following) on a `subst` drive returns the underlying path, and on a mapped drive it returns the `\\server\share` UNC form. Canonicalization can therefore turn an allowed local-looking path into a UNC path *after* the UNC gate (§4.3). Run the UNC gate on the resolved path as well as the input, and decide whether `subst` drives are rejected or resolved (§15 Q34).

### 2.5 Length limits
- `MAX_PATH` = 260. The JDK prefixes `\\?\` internally for long paths in NIO, so Java file operations mostly work. External tools (FFmpeg, shell commands) and some Win32 APIs called through JNA do not. Either pass long paths to FFmpeg with the `\\?\` prefix or reject them for previews.
- A filename can be at most 255 UTF-16 code units on NTFS; on ext4 the limit is 255 bytes. Validate in UTF-16 units on Windows.
- Report `PathTooLong` cleanly instead of a generic 500.

### 2.6 Config values that hold paths
- `FM_NON_SENSITIVE_FOLDERS`, `FM_HIDDEN_FOLDER_PATHS` and `FM_FORCE_DELETABLE_FOLDERS` are split on `:` (README:65, `docs/configuration.md`). On Windows, use `;` (`File.pathSeparator`). Linux keeps `:`, so it stays backwards compatible. Accept both `\` and `/` on Windows. `*` works only in `FM_NON_SENSITIVE_FOLDERS`, which names entries of the sensitive list (`/home/*/.ssh` → `C:/Users/*/.ssh`). The other two lists compare whole paths, so an entry with `*` gets a warning at startup (R10, fixes 9).

---

## 3. File identity ("entities")

### 3.1 How it works now
`files.inode` (unique index) together with `is_filesystem_supported` lets `FileSecurityService.resolveEntityConflicts` (around lines 242-360) re-attach permissions and shares when a file moves, or orphan them when a different file appears at the same path. `FileUtils.getInode` reads `unix:ino`, and `isSupportedFilesystem` checks for the `unix` attribute view.

**There is an existing Linux bug in this design:** inode numbers are unique only per device. A unique `inode` index across two mounted filesystems can collide (for example `/` and `/mnt/data` both having inode 2 at the root, or any reused inode). The key should be `(st_dev, st_ino)`. Fix this in the same migration.

### 3.2 Why the obvious pure-Java approach doesn't work
The obvious approach is to build the key from `BasicFileAttributes.fileKey()` plus `FileStore.name():type()`. **On the Windows NTFS provider in OpenJDK, `fileKey()` returns `null`** (the provider keeps the volume serial and file index internally for `isSameFile` but doesn't expose them). That would fall through to `isStable = false` for every NTFS file. Also, `FileStore.name()` is the volume *label*, which isn't unique and which users can change. **Verify this with a test on a Windows runner before building on it** (`FileIdentityWindowsTest`: create a file, assert that the key is non-null and survives a rename).

**[validated]** On JDK 25 on Windows 10 NTFS, `fileKey()` returns `null`, `FileStore.name()` returns the volume label (`Windoors` on the test machine), and `supportsFileAttributeView("unix")` is `false`. A pure-Java identity doesn't work, so the native call in §3.3 is required.

### 3.3 Recommended Windows file key
- Use a native call (JNA, or FFM on Java 22+; §15 Q8) to open a handle with `CreateFileW(path, 0 /*no access needed*/, FILE_SHARE_READ|WRITE|DELETE, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT)`.
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
- Hard links [decided Q22]: two paths share one key, and the unique index rejects the second entity. Linux has the same problem today. The key stays unique, the first path wins, and the second path gets a path-only entity marked `is_filesystem_supported = false`.
- Weak-identity volumes (FAT, exFAT) [decided Q25]: path-only identity. The UI warns when a permission is set on such a volume.

### 3.5 Database migration (next free number after V8 on `main`)
- Before V9 runs, take an automatic `VACUUM INTO` backup next to the DB, and log backfill progress (Q27).
- Add `files.file_key TEXT` (unique, nullable) and `files.path_key TEXT` (unique). Keep `inode` for one release, read-only, then drop it in a later migration (Q28). On Windows `path_key` follows §2.3.
- Add `folder_visibility.path_key` and `saved_files.path_key`. The `saved_files` primary key becomes `(user_id, path_key)`. SQLite can't alter a primary key, so this needs a table rebuild; follow the V8 pattern.
- Also check `shared_files`, `users.home_folder_path` (V6) and any other path column for the same treatment.
- Backfill on Linux: `path_key = path`, and `file_key = "posix:<dev>:<ino>"` computed in a Java migration that stats each path. Missing files get `null` and are orphaned exactly as they would be today.
- Migrations must be idempotent and run on both OSes. Test them on the Windows PC against a DB fixture created on Linux; the paths won't resolve, so they must fail safe.
- `EntityRepository`: `getByInode` → `getByFileKey`, `updateInodeAndPath` → `updateFileKeyAndPath`. Path lookups query by `path_key`.

---

## 4. Platform security policy

### 4.1 Sensitive folders (Windows defaults for `SensitiveFolderPaths`)
Block these when `FM_HIDE_SENSITIVE_FOLDERS=true`. Resolve them through environment variables and Known Folders at startup, never by hardcoding `C:`:
- `%SystemRoot%\System32\config` (SAM, SYSTEM and SECURITY hives), `%SystemRoot%\System32\config\RegBack`
- `%SystemRoot%\NTDS` (domain controllers), `%SystemRoot%\System32\Microsoft\Protect`
- `%SystemRoot%\repair`, `%SystemRoot%\Panther` and `%SystemRoot%\System32\Sysprep` (unattend files can contain admin passwords)
- `%ProgramData%\Microsoft\Crypto`, `%ProgramData%\Microsoft\Windows\WER` (crash dumps hold memory contents)
- `C:\Users\*\NTUSER.DAT*`, `C:\Users\*\.ssh`
- Parts of `C:\Users\*\AppData`, not all of it [decided Q31, Q60]:
  - `Roaming\Microsoft\{Credentials,Protect,Crypto,SystemCertificates}` and `Local\Microsoft\{Credentials,Vault}` (credentials and DPAPI master keys)
  - browser profiles: `Local\Google\Chrome\User Data`, `Local\Microsoft\Edge\User Data` and `Roaming\Mozilla\Firefox\Profiles`
- Also: `C:\Users\*\.gnupg`, `C:\Users\*\.gnupg`, `C:\Users\*\.aws`, `C:\Users\*\.azure`, `C:\Users\*\.kube`, `C:\Users\*\.docker`
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
- Policy when `FM_FOLLOW_SYMBOLIC_LINKS=false`: don't traverse or follow **any name-surrogate reparse point** (symlink, junction, mount point). Detect `FILE_ATTRIBUTE_REPARSE_POINT` and read the tag through `FindFirstFileW` (`dwReserved0`) or `FSCTL_GET_REPARSE_POINT`. Checking only the attribute and not the tag would also block OneDrive placeholder files and dedup files, so filter on `IsReparseTagNameSurrogate(tag)`.
- `safeWalk` (`FileUtils.kt`) and search must not recurse into junctions. Windows profiles contain legacy junction loops such as `C:\Users\x\AppData\Local\Application Data` pointing to itself. `walkIdentity`/`isAlreadyWalked` already exists for cycles; make sure it detects junction cycles too.
- When following is enabled: re-authorize the resolved target and enforce containment (a `resolveContainedExisting` helper). That covers share roots and visibility.
- Creating symlinks needs `SeCreateSymbolicLinkPrivilege` (admin) or Developer Mode. If Filemat has any "create link" or copy-preserving-links behaviour, handle `FileSystemException: A required privilege is not held` by copying the target or rejecting with a clear message.
- `.lnk` shortcut files are ordinary files. Never resolve them.
- **[gap] Non-link reparse-point files are not "regular files" in Java.** The JDK reports `isOther=true` and `isRegularFile=false` for *any* file carrying `FILE_ATTRIBUTE_REPARSE_POINT`. Besides junctions (confirmed), that includes OneDrive/cloud placeholders, **Data Deduplication files (common on Windows Server file servers)**, AppExecLinks and WIM-backed files. Today `FilesystemService.kt:103-111` classifies them as `FileType.OTHER`, and `FileContentService.kt:68` returns **notFound** for downloads. Thumbnails skip them too (`ThumbnailService.kt:498,537,604`). Classify by reparse tag instead: a non-name-surrogate tag on a non-directory is a regular file. Verify on a dedup volume and in a OneDrive folder (§10.3), and see §15 Q42.

### 4.5 Cloud files (OneDrive and similar)
- Placeholder files carry `FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS` and `RECALL_ON_OPEN`/`OFFLINE`. Reading content (thumbnails, previews, Tika MIME sniffing, zip download, search by content) **triggers a download**, possibly gigabytes, or fails when offline.
- Plan:
  - Skip thumbnail and preview generation and content sniffing for these files. Use extension-based MIME instead.
  - Show an "online-only" badge (optional).
  - Use `FILE_FLAG_OPEN_NO_RECALL` for metadata calls.
- Note: the `LocalSystem` service account has no OneDrive anyway. This matters for people who run Filemat as their own user.

### 4.6 Hidden and system attributes [decided Q18, Q62]
- Linux has dotfiles; Windows has `dos:hidden` and `dos:system`. Filemat ignores both and shows these files like any other. There's no `isHidden`/`isSystem` in the DTO and no toggle.
- The default hidden list (`FM_HIDDEN_FOLDER_PATHS` defaults on Windows) covers `<drive>:\$Recycle.Bin`, `System Volume Information`, `pagefile.sys`, `hiberfil.sys` and `swapfile.sys`. They're also on the sensitive list (§4.1), so they're blocked by default. With `FM_HIDE_SENSITIVE_FOLDERS=false` they're still hidden.
- Filemat's own temp files (`.filemat-replace-*.tmp`, `.filemat-deleting-*`) still get `dos:hidden`, so they don't show up in Explorer.

### 4.7 ACLs and the "permission" flags the UI uses
- `isExecutable` and `isWritable` come from `Files.isExecutable`/`isWritable`. On Windows, `isWritable` on a *directory* checks only the read-only attribute plus an ACL check. `isExecutable` on a directory maps to `FILE_TRAVERSE`. The UI (`GridFileEntry.svelte:37`, `RowFileEntry.svelte:40`) treats `!isExecutable` as "can't open". Verify on Windows that ordinary folders report `true` and the folder isn't greyed out. Both calls do an access check and are slow on large listings; measure.
- The read-only attribute on folders is ignored by Windows for most purposes (Explorer uses it for customization). Don't map it to "not writable" for directories.
- An `AccessDeniedException` from the OS should become a Filemat "Missing filesystem permission" response and not a 500. That is a general improvement that also helps Linux.

### 4.8 The Filemat data folder's own permissions
- Replace `PosixFilePermissions.fromString("rwxr-x---")` and `rw-------` (`ServerApplication.kt`) with `AclPolicy.restrictToOwner(path)`:
  - Windows: `AclFileAttributeView` with a protected DACL (no inheritance) and full control for the current user's SID, `SYSTEM` and `BUILTIN\Administrators`.
  - POSIX: the current chmod.
- This matters for `setup-code.txt` and `auth-code.txt` (sensitive action auth) and for the DB. `%LOCALAPPDATA%` is already private to the user, so this is defence in depth.
- On failure [decided Q33]: warn and continue on Windows, because v1 only has interactive runs. Exit when a service mode is added later and `FM_DATA_DIR` is set explicitly. Linux keeps its current behaviour.

### 4.9 Running elevated [decided Q29, Q30]
- Filemat runs as the user who starts `filemat.cmd`. Detect an elevated Administrator token or `LocalSystem` (a token-elevation check via JNA) and log a loud warning at startup. Don't refuse to start.
- Running as a normal user means Filemat can read what that user can read, but not other users' profiles. When an admin exposes a folder the process can't read, show a hint with the `icacls` command that grants access.
- **[gap] Controlled Folder Access** (Defender ransomware protection) silently blocks writes by unknown executables (`java.exe`) to Documents, Pictures, Desktop and similar folders, which shows up as `AccessDenied`/`FileNotFound`. Document it, and map the error to a hint where possible.

---

## 5. Startup, data directory and configuration

### 5.1 Data dir
- `FM_DATA_DIR` env var, with defaults [decided Q59]:
  - Windows: `%LOCALAPPDATA%\Filemat`. The portable app folder never holds data, so it can be replaced on upgrade or sit in a read-only location.
  - Linux: `/var/lib/filemat`, unchanged
- Derive from it: DB, `setup-code.txt`, `auth-code.txt`, `logs\`, the default thumbnail cache (`<dataDir>\cache\thumbnails` on Windows, `/var/cache/filemat` on Linux, unchanged) and the default upload folder.
- Upload folder default on Windows: `<dataDir>\uploads`, *not* `%TEMP%`, because Storage Sense and Disk Cleanup purge temp folders. Linux keeps `/tmp/filemat` (Q54).
- Uploads to a different volume than the upload folder finalize as a copy (§6.6). Document that setting the upload folder on the main data drive keeps finalization a rename.
- Replace every hardcoded literal: `Props.kt:13,18,19`, `ServerApplication.kt:38`, `SensitiveFolderPaths.kt` (`/var/lib/filemat`), and the frontend copies (§9).
- Expose the effective paths to the frontend through the setup/state endpoint, so the UI never hardcodes them.

### 5.2 SQLite
- `sqlite-jdbc` (xerial, through `spring-data-sqlite`) ships Windows x64/ARM64 natives, but extracts the DLL to `java.io.tmpdir` at startup. Under a locked-down service account or with AppLocker/WDAC, loading a DLL from temp can be blocked. Set `-Dorg.sqlite.tmpdir=<dataDir>\native` or ship the DLL alongside and use `-Dorg.sqlite.lib.path`.
- Reject a data dir on a network or mapped drive at startup: check `GetDriveTypeW` = `DRIVE_REMOTE` and UNC prefixes. SQLite locking over SMB corrupts databases. Add a small `DatabaseLocationPolicy` for this.
- WAL mode: `-wal`/`-shm` files are held open, and antivirus may scan them. Recommend a Defender exclusion for the data dir in the docs, not done automatically.
- Backups while the service is running must use the SQLite backup API or `VACUUM INTO`, not a file copy, which fails on Windows because the file is locked.

### 5.3 Charset and console
- Java 17 on Windows: the default charset is the ANSI code page (for example `windows-1252`). Java 18+ (JEP 400) defaults to UTF-8. The audit found that the server code uses Kotlin `readText`/`writeText`/`String(bytes)` (which default to UTF-8) or explicit UTF-8 (`utilFunctions.kt:249,260,541,547`, `TusService.kt:104`), so **nothing is affected today**. Java 21 is still recommended as a guard against future `java.io.FileReader`/`PrintWriter(File)` use, and it's the chosen baseline anyway (Q56).
- Console output: set `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` in the start script, because the console code page is often 437/850 and box characters or non-ASCII filenames in logs are garbled.
- Filenames themselves are fine: Java uses the UTF-16 Win32 APIs.

### 5.4 Ports, firewall, binding
- Port 8080: Hyper-V/WSL/Docker reserve dynamic port ranges (`netsh int ipv4 show excludedportrange protocol=tcp`). Port 8080 sometimes lands in an excluded range and binding fails with "An attempt was made to access a socket in a way forbidden by its access permissions". Detect `BindException` and log this specific hint.
- Firewall [decided Q37]: Filemat never adds a rule. On the first run, Windows shows its own "allow access" prompt for `java.exe`. The docs explain that prompt and the manual `New-NetFirewallRule` command.

### 5.5 Shutdown
- Windows has no SIGTERM. In a console, Ctrl+C runs the JVM shutdown hooks, so Spring's graceful shutdown (`server.shutdown=graceful`) works. Closing the console window gives the process only about 5 seconds before Windows kills it, so keep `spring.lifecycle.timeout-per-shutdown-phase` short and make sure the SQLite connection pool closes first.
- Task Scheduler's "End" kills the process with no shutdown hooks. SQLite WAL survives that, and TUS uploads resume from their last chunk. Document it.
- Test that in-progress TUS uploads and the SQLite WAL survive Ctrl+C, closing the window and a hard kill.

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
  5. Decided (Q39, Q40):
     - Retry 3 × 100 ms, then return 409 "File is in use" without naming the process.
     - ~~Rename-then-delete is only the fallback after the retries.~~
     - ~~Every rename is recorded in a small list in the data dir (`pending-deletes`). The startup sweep retries only those entries and never walks the disks, so it stays cheap.~~
     - Changed 2026-10-25: no fallback. Windows 10 1809+ deletes with POSIX semantics on NTFS, so the name is free at once (tested), and a folder with an open file inside can't be renamed either way.
- `FileLockService`: the in-process locks key on `Path`, and `WindowsPath` equality ignores case, so they are consistent today. Switch them to `path_key` anyway, so correctness doesn't depend on the type of the key and lock keys match the DB keys.

### 6.2 Read-only attribute [decided Q38]
Rule: **if a file is read-only, it's read-only.** Filemat never clears `dos:readonly`.
- Delete, overwrite (`REPLACE_EXISTING`, saving text content, an upload finalizing onto an existing name) and content replacement of a read-only file fail with a specific "File is read-only" error, not a generic error or a 500. Detect it with `DosFileAttributes.isReadOnly` when the OS returns `AccessDeniedException`.
- Recursive delete: read-only files and their parent folders stay in place, everything else is deleted, and the result lists what was left, using the existing partial-failure reporting.
- Rename and same-volume move are allowed, because Windows allows them on read-only files and they don't change content.
- Cross-volume move is a copy plus a delete, so check for read-only files *before* copying. Otherwise a duplicate would be left behind. Fail the move with the same error.
- A copy keeps the attribute (`COPY_ATTRIBUTES`), so the copy is read-only too.
- Directories: Windows mostly ignores the flag on folders (Explorer uses it for folder customization), so Filemat ignores it too (§4.7).
- UI: `isWritable` (`Files.isWritable`) already reports `false` for read-only files on Windows, so editing and overwrite actions can be disabled up front.
- Linux: no change.

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
| C. External FFmpeg executable (`FM_FFMPEG_PATH`, `ProcessBuilder`) behind a `MediaPreviewBackend` interface | Small JAR (about 30 MB); any platform including ARM64; users can update FFmpeg for codec or security fixes | Process-spawn overhead; must harden argument passing; FFmpeg must be installed or bundled |

**Decision [decided Q44, Q45, Q57, Q58]: keep javacv and add C as a fallback.** That gives two media code paths behind one `MediaPreviewBackend` interface:
- `JavacvBackend`: used when `FfmpegSupport.isAvailable`, which means Linux x64/arm64, exactly as today.
- `ExternalFfmpegBackend`: used otherwise. It looks for `ffmpeg` in `FM_FFMPEG_PATH`, then `PATH`.
- If neither is available, video previews are off, with a warning in admin settings and a startup log line (Q47).
- One JAR for every platform. The Windows zip carries the unused Linux natives, which fail to load and fall back cleanly. Drop opencv, which isn't referenced in the code.
- The Windows zip bundles a **BtbN GPL** build, pinned with a SHA-256 checksum and bumped each Filemat release (Q46). Filemat is GPLv3, so a GPL FFmpeg is license-compatible. Ship its license text and a source link.

### 7.3 Hardening for the external process
- Never go through a shell. Use the `ProcessBuilder(list)` form only. On Windows the JDK still builds a command line string, and quoting rules for `"` and trailing `\` are fragile. Pass the input as `-i file:<path>` (forces the file protocol, so a filename like `http:...` or `concat:` can't change the protocol), or copy/hardlink to a temp name.
- Filenames starting with `-`: always use a full absolute path.
- Long paths (§2.5): prefix `\\?\` (Q43).
- Timeouts: kill the process tree on timeout (`ProcessHandle.descendants()`), cap concurrency, and use a low priority (`start /low` isn't available without a shell, so use JNA `SetPriorityClass` or accept normal priority).
- Locking: FFmpeg opens the input without share-delete, so a user deleting a video while its thumbnail is generated gets a sharing violation. Retry handles it (§6.1).
- Done in Phase 6 (2026-10-25), except low priority. See the progress tracker.

---

## 8. Packaging, service and distribution

### 8.1 Artifacts per release [decided Q48, Q49, Q65, portable]
- `filemat.jar`: one JAR for every platform (§7.2), built as `server/build/libs/server-app.jar`. Built and uploaded by hand, as today.
- `filemat-<ver>-windows-x64.zip`: a **portable** app folder. Unzip it anywhere and run `filemat.cmd`. There's no installer, no service, no registry entries and no firewall rule. Contents:
  ```
  filemat\
  ├─ filemat.cmd       start script
  ├─ filemat.jar
  ├─ jre\              jlink JRE 21 (about 50 MB)
  ├─ ffmpeg\ffmpeg.exe BtbN GPL, pinned (Q46)
  └─ LICENSES\         FFmpeg GPL text plus source link, JRE GPLv2+CPE, Filemat GPLv3
  ```
- `filemat.cmd`:
  - runs `"%~dp0jre\bin\java.exe" -jar "%~dp0filemat.jar"`
  - sets `FM_FFMPEG_PATH` to `%~dp0ffmpeg\ffmpeg.exe` unless it's already set
  - adds `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` (§5.3) and `-Dorg.sqlite.tmpdir=<dataDir>\native` (§5.2)
  - is written with CRLF line endings, which the `windowsDist` task enforces (§8.4)
- Configuration uses environment variables only (Q50): set them as user or system env vars, or in a wrapper `.cmd` that calls `filemat.cmd`.
- Unsigned (Q49). SmartScreen may warn about `java.exe`/`ffmpeg.exe` from a downloaded zip. Document "Unblock" in the file's Properties.
- Later: an MSI, a service mode, signing. The ARM64 zip is built since Phase 8.

### 8.2 Running, auto-start, upgrades and logs
- **Running**: `filemat.cmd` in a console, as the current user (§4.9). There's no service in v1.
- **Auto-start**: documented, not automated. Create a Task Scheduler task with the trigger "At log on" (or "At startup" with "Run whether user is logged on or not"), the action `filemat.cmd`, and "Start in" set to the app folder.
- **Upgrade** [Q52 superseded by portable]: stop Filemat, replace the app folder (or unzip the new one beside it), start again. The data dir is in `%LOCALAPPDATA%`, so nothing moves. The DB migration backs itself up (Q27). No script is needed; keep the old folder for rollback.
- **Logs** [Q53]: `<dataDir>\logs` with size-based rotation. Done in Phase 7 as a copy of the console output (`ConsoleLogFile`), not logback's `logging.file.name`, because Filemat's own log lines are printed directly. Windows only. `FM_PRINT_LOGS` behaviour is unchanged.
- **Port** 8080 by default, with the excluded-port-range hint (§5.4).

### 8.3 Gradle and build scripts
- Add a cross-platform build. Either:
  - a Gradle task chain: `npmCi` → `npmBuild` → copy `web/build` to `build/resources/main/static` → `bootJar`, using `npm.cmd` on Windows (`Os.isFamily(FAMILY_WINDOWS)`) or the `com.github.node-gradle.node` plugin, or
  - a single Node script `utilities/build.mjs`.
- *Recommendation: the Gradle plugin, so `gradlew(.bat) bootJar` is the one command everywhere.*
- Keep `utilities/build.ps1` working, but make it non-interactive with switches, and use `Push-Location`/`Pop-Location`. Fix the usage text in `build-files.sh` and remove its dead variables.
- Add the foojay toolchain resolver to `settings.gradle.kts` and align on `jvmToolchain(21)`.
- A Gradle `windowsDist` task builds the zip on your machine. It runs jlink, downloads FFmpeg and checks its SHA-256 pin, and copies `filemat.cmd` and the licenses.

### 8.4 Repo hygiene [decided Q64]
- No `.gitattributes` and no renormalize commit. Line endings in the repo stay as they are.
- Because of that, `windowsDist` converts `filemat.cmd` to CRLF when it packages it (Ant `FixCrLfFilter`), whatever the checkout has. `cmd.exe` mis-parses labels and `goto` in LF batch files.
- Root `.editorconfig`. AGENTS.md already claims one exists.
- `web/package.json` `engines.node` (`>=20.19`), because `.npmrc` already has `engine-strict=true`.

### 8.5 Docker on Windows hosts [decided Q26: best effort]
- The image stays Linux (Docker Desktop runs it under WSL2). Document `-v C:\Data:/data` and WSL paths, and that `user: 1111:1111` doesn't apply to Windows bind mounts.
- **Identity risk:** inode numbers on Docker Desktop bind mounts (grpcfuse/virtiofs/9p from NTFS) may not be stable across container or WSL restarts. If they aren't, permissions silently orphan. At startup, detect exposed folders on such mounts (filesystem type in `/proc/self/mounts`) and log a warning. Document "use a Docker volume or WSL ext4 path for best results".
- Inside WSL, `/mnt/c` (drvfs) has the same concern, and Windows files appear with fake POSIX permissions.

### 8.6 Later / optional
- A Windows service mode (WinSW plus a virtual account), an MSI, winget/Scoop/Chocolatey, and code signing.
- Recycle Bin delete (`SHFileOperation` with `FOF_ALLOWUNDO` or `IFileOperation` via JNA). Skipped: delete stays permanent, and the delete dialog says so on Windows (Q41).
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
- An opaque `v1.<base64url>` route codec (considered earlier): it avoids all of these problems but makes URLs unreadable and un-editable, changes every existing bookmark and saved link, and adds a decode step to every request. *Recommendation: don't adopt it. Per-segment encoding plus a readable path is enough.* Keep it in reserve if a real case shows up that encoding can't handle.
- On the backend, make sure Spring/Tomcat accepts `%5C` and `%2F` in paths and query params (Tomcat rejects encoded slashes in the path by default; query params are fine). File paths are presumably query/body params today; confirm.
  - **[validated / gap]** API file paths are query/body params (`@RequestParam`), so the API is fine. The problem is **SPA deep links and page reloads**: `/files/<encoded path>` reaches Tomcat and `ResourceController` (the `index.html` fallback). By default Tomcat rejects `%2F` in the request path with a 400 (`encodedSolidusHandling=reject`). A Linux filename containing `\` (`%5C`) needs a check against the configured Tomcat version too. Either set `encodedSolidusHandling=passthrough`/decode for the SPA fallback only, or keep the file path out of the URL path (for example `/files?path=`). This is a route-format decision (§15 Q13).
- **[done, Phase 0]** `filePageUrl(path, pagePath)` in `codeUtil.svelte.ts` builds every file page URL (`encodeURIComponent` per segment, `:` kept readable). `getFilePagePath`, `navigateToFilePath`, Backspace-to-parent, the context menu, the home page, the sidebar home link and the "home" highlight in `appState` use it. `getZipContentUrl` now encodes by default. No server change was needed: segments are split on `/`, so `%2F` is never produced, and the SPA fallback serves `index.html` for any other decoded path. Passthrough stays in reserve for a real case.
- **[decided Q13, Q16]** Per-segment encoded routes (`/files/C:/Users/x`). Enable encoded-slash passthrough for the SPA fallback only. When a URL uses the wrong case, serve the canonical path and rewrite the URL with `replaceState`.

### 9.3 Filename validation
- Add `validateFilename(name, style)` to `$lib/code/util/validation.ts`, mirroring `PathPolicy.validateFileName`. Better: the server sends the rule set (invalid chars, reserved names, max length) in the state endpoint, so the two can't drift.
- Use it in: `handleNewFolder` (`fileActions.ts:116-169`), `handleNewFile` (`pageLogic.ts:208-222`), rename (`FileBrowser.svelte:261-275`), `FolderTreeSelector.svelte:42`, upload and folder upload (`folderUpload.ts:222,261,275,440-444`).
- The server stays authoritative; the UI only gives early feedback.

### 9.4 Case-insensitivity
- `getUniqueFilename` (`codeUtil.svelte.ts:508-537`; used in `files.ts:298-301`) must fold case when the target folder is case-insensitive, which the listing's `caseSensitive` flag tells it (§2.3). Otherwise uploading `photo.jpg` next to `Photo.jpg` overwrites it or fails. In a case-sensitive Windows folder, and on Linux, it doesn't fold.
- `folderDownload.ts` writes to the *client's* disk, so its folding depends on the client OS, not the server (a known gap in the progress tracker).
- Strict `===` path comparisons stay correct only if the server always returns canonical case (§2.3). Keep `===` and rely on the server.

### 9.5 Setup and settings UI
- Remove hardcoded `/tmp/filemat`, `/var/cache/filemat`, `/var/lib/filemat/setup-code.txt` (`setup/+page.svelte:39,43,261,357`) and `/var/lib/filemat/auth-code.txt` (`SensitiveAuthDialog.svelte:17`). Render them from server-provided values.
- Path inputs (`HomeFolderPathSetting.svelte:61`, `UploadFolderPathSetting.svelte:80`, `ThumbnailCacheSetting.svelte:218-223`, exposed files): show a placeholder that matches the OS and validate the format client-side.
- Later (Q21): a server-side folder picker that reuses `FolderTreeSelector` against the virtual root.
- Delete dialog on Windows: say plainly that delete is permanent and skips the Recycle Bin (Q41).
- Weak-identity volumes: warn when a permission is set on FAT or exFAT (Q25).
- Missing FFmpeg: a warning in admin settings (Q47).
- Wording: "Symbolic links" → "Symbolic links and junctions" (`setup/+page.svelte:428-432`, `DetailsSidebar.svelte:373-374`). "Sensitive Linux folders" → "sensitive system folders".

### 9.6 Client-side download (independent of server OS)
- `folderDownload.ts` writes server filenames to the user's local disk through the File System Access API. On a **Windows client** downloading from a **Linux server**, names with `:`, `?`, `*`, `"`, `<`, `>`, `|`, a trailing dot or a reserved name fail today. Sanitize them on the client (replace with `_`) and make the reserved-name check case-insensitive. This bug exists now, independent of Windows server support. Good first PR.

### 9.7 Hidden files and sorting [decided Q18, Q20]
- No hidden-files toggle. Windows hidden and system files are shown (§4.6).
- Sorting stays as it is on every platform.

---

## 10. Testing strategy

### 10.1 Test runs [decided Q11, Q55, Q63]
- No CI. The owner runs the tests by hand:
  - Linux: `./gradlew test`
  - Windows 10 PC: `gradlew.bat test`
  - frontend: `npm run check`
- Windows-only tests are annotated `@EnabledOnOs(OS.WINDOWS)`, and POSIX-only ones `@EnabledOnOs(LINUX)`, so the same suite runs on both.
- No Vitest or Playwright. Keep `path.ts` small and pure, and mirror its rules in `PathPolicy` tests on the server.
- Symlink tests need Developer Mode or an elevated shell on the Windows PC. Run with `-Dfilemat.requireSymlinks=true` there so they don't silently skip, and `-Dfilemat.requireWindowsFeatures=true` so tests that need 8.3 names, `subst`, junctions or case-sensitive folders fail instead of skipping (or the env vars `FILEMAT_REQUIRE_SYMLINKS`, `FILEMAT_REQUIRE_WINDOWS_FEATURES`). The build passes both properties to the test JVM (fixes 13; before, only the env var reached it).

### 10.2 Unit tests to add (server)
- `PathPolicyWindowsTest`: drive-absolute, UNC, rejected forms (`C:foo`, `\foo`, `\\?\`, `\\.\`, `file:ads`, trailing dot/space, `CON`, `con.txt`, `COM¹`, `LPT0`), `..` containment, `\` normalization, case keys, `isRoot` for `C:/`, `//s/sh/`, `/`. Run it on all OSes where possible by testing pure-string logic separately from `Path` construction.
- `PathPolicyPosixTest`: `\` stays literal; existing behaviour unchanged.
- `FileIdentityTest` (per OS): key non-null on NTFS/ext4, stable across rename, changes on copy, `null` or unstable on FAT (use a FAT-formatted VHD or USB stick, or skip).
- `LinkInspectorTest` (Windows): symlink (needs Developer Mode or an elevated shell), junction (`mklink /J`), mount point, junction loop, OneDrive placeholder (skip when no OneDrive folder exists).
- Entity reconnect tests: move a file with a permission, rename case-only, cross-volume move. Cross-volume needs a second volume on the PC: a second drive, a USB stick or a small mounted VHDX. Skip the test when none is configured (for example through a `FILEMAT_TEST_SECOND_VOLUME` env var). A `subst` drive is *not* a different volume and doesn't test this.
- `CaseSensitivityTest` (Windows): create a folder, enable the flag with `fsutil file setCaseSensitiveInfo <dir> enable`, then:
  - create `a.txt` and `A.txt` and check they get distinct `path_key`s, permissions and locks
  - check a permission on one doesn't apply to the other
  - check the sensitive list still matches case-insensitively
  - disable the flag and check the cache picks the change up

  Skip the test if `fsutil` fails; some Windows 10 builds need the WSL feature or admin rights for it.
- Sharing violation: hold a `FileInputStream` without share-delete (via JNA `CreateFile` with share mode 0), attempt delete → expect a 409, not a 500.
- Security tests: `C:\PROGRA~1\...`, `c:\windows\system32\config\sam`, `C:\Windows\System32\config\SAM.` (trailing dot), `//127.0.0.1/c$/...` (UNC loopback must be rejected), `\\?\C:\Windows`, `C:\Users\x\AppData` through junction `Application Data`. All must be blocked.
- Migration tests: Linux fixture DB → migrate on Windows (paths don't resolve → orphaned safely); Linux DB → migrate on Linux → `file_key` backfilled.
- Existing tests that assume `/tmp` or POSIX paths: the server audit lists them (§14). Switch them to `@TempDir` and `Path` joins.

### 10.3 Manual release checklist
Write a new `docs/windows-beta-checklist.md` covering the main flows, plus:
- running as a normal user, and the warning when elevated
- the portable folder in a path with spaces and non-ASCII characters
- a Task Scheduler auto-start
- Defender real-time scanning on during the tests
- the OneDrive folder exposed
- Ctrl+C, closing the console window, and ending the task during an upload
- deleting and overwriting a read-only file, and a recursive delete with a read-only file inside
- a case-sensitive folder (`fsutil file setCaseSensitiveInfo`) with `a.txt` and `A.txt`
- a drive-letter change on a USB drive
- a 300-char path
- a non-ASCII username profile (`C:\Users\Jürgen`)
- a CJK filename
- excluded-port-range port 8080

### 10.4 Docker Desktop check
- Run the Linux image with a Windows bind mount. Set a permission on a file, restart the container and WSL (`wsl --shutdown`), and confirm the permission is still attached (inode stability, §8.5).

---

## 11. Documentation updates
- README.md:33-35,51: requirements "Linux or Windows, Java 21 (or Docker)"; a Windows section (portable zip, `filemat.cmd`); fix the Java version.
- README.md:65 and `docs/configuration.md`: the multi-value separator per OS; Windows examples; rename "sensitive Linux folders".
- `docs/configuration.md`: the Windows sensitive and non-deletable lists (§4.1, §4.2); a `FM_DATA_DIR` row; `FM_FFMPEG_PATH`; a UNC opt-in setting.
- `docs/technical-details.md`, `docs/settings.md`, `docs/database.md`: per-OS default paths; `file_key`/`path_key` columns instead of `inode`.
- `docs/docker-deployment.md`: a Docker Desktop on Windows section.
- New `docs/windows.md`, covering:
  - running the portable zip, and "Unblock" for SmartScreen
  - data dir and `FM_*` env vars
  - Task Scheduler auto-start, the firewall prompt and the manual rule, the Defender exclusion
  - upgrade by replacing the folder, backup (`VACUUM INTO`)
  - the `icacls` hint for folders the user can't read
  - read-only files stay read-only
  - case-sensitive folders are supported
  - known limitations: FAT identity, mapped drives rejected, OneDrive placeholders, delete is permanent, drive-letter changes un-expose folders
- README dev section and AGENTS.md: `gradlew.bat`, PowerShell env syntax (`$env:FM_DEV_MODE="true"`), `FM_DEV_BACKEND` on Windows.

---

## 12. Risks and open questions
1. **`fileKey()` returning null on Windows** (§3.2). If confirmed, JNA is required, which adds a native dependency and per-call overhead. Benchmark the listing of a 10k-file folder.
2. **Performance**: `toRealPath()` on every authorized request costs several syscalls per path segment on Windows and is much slower than Linux. Cache canonicalization results per request and per short TTL, keyed by `path_key`. Measure listing and thumbnail endpoints.
3. **Security surface grows**: Windows path aliasing is a known bug class, and per-directory case sensitivity (§2.3) adds to it. The security review comes later (Q6). Until then, property-based tests generate random segment strings for `PathPolicy`.
4. **Linux regressions**: the path abstraction touches every file operation. Keep POSIX behaviour byte-identical (the same stored strings) and run the full Linux suite before each merge. There's no CI to catch it.
5. **Cross-OS DB portability**: not supported. If ever needed, add an admin "remap path prefix" tool (`/srv/data` → `D:/data`) that rewrites `path`/`path_key` and recomputes `file_key`.
6. **Scope creep**: the refactor touches every file operation. Keep each phase shippable on Linux, and don't start Phase N+1 before Phase N's milestone is verified.
7. **ARM64**: out of v1. JNA and sqlite-jdbc both ship win-arm64, and the external FFmpeg backend works there, so it's mostly a packaging task. Done in Phase 8 (the arm64 zip, untested).
8. **Licensing**: Filemat is GPLv3, and the bundled BtbN GPL FFmpeg is compatible. Ship its GPL text plus a source link. jlink JRE redistribution is fine (GPLv2+CPE).
9. **Case-sensitive folders** (§2.3): the flag can be toggled at runtime, and keys go stale until they're rebuilt. In the meantime a child's own permission and hide rules below the folder don't match, so the parent's broader rule applies (fails open, verification 3, V6). Filemat's own move and copy keep the flag (V5, fixed).

---

## 13. Phased implementation order
Work happens on `windows-support` (Q1). Linux tests stay green throughout, run by hand (no CI).

**Phase 0: prep (no behaviour change)** (done, not built yet)
1. ~~`.gitattributes` and renormalize~~ dropped (Q64). Add a root `.editorconfig` only.
2. Java 21: toolchain, README, foojay resolver (Q56).
3. Remove opencv (Q45).
4. Frontend: client-side download filename sanitization (§9.6, done) and per-segment route encoding (§9.2, Q13).
5. Fix the `/foo` vs `/foobar` prefix bugs and the `//name` at root bug (done).

**Phase 1: platform layer and startup** (1a and 1b done; built, tested and checked on Windows, Linux start not checked yet)
0. Quick blockers that also harden Linux:
   - `BasicFileAttributes` in `FilesystemService.getMetadata`
   - lazy ffmpeg init that catches `Throwable` (done, `FfmpegSupport`)
   - surface the `initializeTusService()` failure (done)
   - an `@ExceptionHandler(InvalidPathException)` that returns 400 (done)
   - `path.fileName` in place of `substringAfterLast("/")`
   - a `toRealPath()`'d absolute `Props.dataFolderPath`
   - a junction check before recursing in delete, move and copy (done, `isJunctionLike`)
1. `Platform`, `PathPolicy` (POSIX implementation only, wired everywhere in place of the string ops; behaviour identical).
2. `FM_DATA_DIR` (Windows default `%LOCALAPPDATA%\Filemat`), `Props` derived paths, `AclPolicy` replacing `setPosixFilePermissions`, the env-var separator, `DatabaseLocationPolicy`, the elevated-run warning (JNA).
3. Setup/state endpoint exposes `pathStyle`, the separator, default paths and filename rules; the frontend reads them.
→ *Milestone: the server starts on Windows.*

**Phase 2: Windows paths**
1. `PathPolicy` Windows implementation, the drive-list virtual root, the `VisibilityTrie`/`EntityPermissionTree` segment model, root guards, controller filename extraction.
2. `CaseSensitivity` (the per-directory flag via JNA, cached) and `path_key` built per §2.3. Move all server path comparisons off `WindowsPath` equality onto `path_key`.
3. The `path_key` migration plus repositories plus `FileLockService` keys.
4. Frontend `path.ts` and call-site migration; breadcrumbs; native-backslash display (Q14); `caseSensitive` in listings.
→ *Milestone: browse, expose, upload and download on `C:` and `D:`, including in a case-sensitive folder.*

**Phase 3: identity**
1. `FileIdentity` (POSIX dev+ino, Windows JNA FileIdInfo), the `file_key` migration and backfill, the per-volume support detection.
2. Port `resolveEntityConflicts` to `file_key`; add the cross-volume move re-key.
→ *Milestone: permissions follow moves and renames on NTFS.*

**Phase 4: security hardening**
1. Windows sensitive and non-deletable lists, resolved via env/Known Folders.
2. Reparse-point policy (name-surrogate tags), junction-safe walk, UNC gating before any I/O, 8.3/trailing-dot/ADS rejection, `toRealPath` authorization.
3. AppData partial block list (Q60), default hidden list (Q62).
4. A security test suite (§10.2).

**Phase 5: file operation robustness**
1. Stream-closing audit, sharing-violation mapping plus retry and 409, delete-pending handling with the recorded sweep, read-only files left read-only with a clear error (§6.2), case-only rename, cross-volume move, TUS checks.

**Phase 6: media**
1. The `MediaPreviewBackend` interface: javacv when it loads, otherwise external FFmpeg with the §7.3 hardening. The missing-FFmpeg warning in admin settings.

**Phase 7: packaging and docs**
1. Gradle web build integration, `windowsDist` (jlink plus `filemat.cmd` plus the pinned FFmpeg), logback file rotation.
2. Docs (§11), the beta checklist, Docker Desktop notes.
3. Ship as **beta**, which it stays (Q5).

**Phase 8: later**
UNC shares, ARM64 zip (done, untested), a service mode, an MSI, code signing, a server-side folder picker, a path remap tool, the external security review.

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
- `FilesystemService.kt:106-110`: junctions are listed as `FOLDER`, not `FOLDER_LINK`. **[validated]** Also: dedup and cloud-placeholder files become `OTHER` and can't be downloaded (§4.4).
- `sharePath.kt:92` (`symlinkImmediateTargetsStayInside`) and `utilFunctions.kt:375` (`pathContainsSymlink`) ignore junctions.
- **[validated]** Java on Windows reports a junction as `isDirectory=true, isOther=true, isSymbolicLink=false` even with `NOFOLLOW_LINKS`. `FilesystemDeletionService.kt:87-91` checks `Files.isDirectory(..., NOFOLLOW_LINKS)` and recurses, so the data-loss bug is real. A cheap pure-Java detector is `attrs.isOther && attrs.isDirectory`, or `dos:attributes & 0x400`. Use JNA only to read the reparse tag (§4.4).
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
- Symlink tests: `testutil/TestLinks.kt:17-28` already falls back to `mklink /J`. Set `-Dfilemat.requireSymlinks=true` on the Windows PC (Developer Mode or an elevated shell) so the 19 skipped tests actually run.
- Missing tests: case-only rename, junction delete, read-only delete, reserved names, `C:\` root operations.

### 14.9 Not affected (verified)
- No `WatchService`/inotify, no `ProcessBuilder`/`Runtime.exec` in main code, no `df`/`du`/mount logic, no `HOME`/`XDG`/`USER` lookups, no `Files.getOwner`.
- Charset: Kotlin and NIO defaults are UTF-8 (§5.3).
- Zip entry names use `invariantSeparatorsPathString` (`FileContentService.kt:149,279`), which is correct.
- `FileLockService` keys on `Path`, which is case-insensitive on Windows. That is wrong inside case-sensitive folders (§2.3), where `a.txt` and `A.txt` would share a lock. It must switch to `path_key` (Phase 2).
- TwelveMonkeys, Thumbnailator, Tika and metadata-extractor are pure Java. sqlite-jdbc 3.47.2.0 bundles Windows natives (§5.2).

---

## 15. Questions for the owner

Each question names the earliest phase that needs the answer and gives a default. A blank answer means "go with the default". Fill in the `Answer:` lines inline.

### A. Process and scope
- **Q1 [P0] Delivery strategy.** Small PRs straight into `main` per phase, with Linux releases shipping in between and Windows "unofficial" until P7? Or a long-lived `windows-support` integration branch merged at beta? *Default: small PRs into `main`.* Answer: No strategy
- **Q2 [P0] Linux fixes first.** Ship the Linux bugs this audit found as a standalone Linux release before any Windows work? That covers the `/foo` vs `/foobar` prefix match, `LIKE` wildcards, `(dev, ino)` collisions, route encoding and client download filename sanitization. *Default: yes.* Answer: Document the Linux bugs, fix them during Windows work if required.
- **Q3 [P0] Supported Windows versions.** Windows 10 is out of support (consumer ESU ended 2026-10-13). Is Windows 10 still a target, or only Windows 11 plus Server 2019/2022/2025? *Default: Windows 11 and Server 2019+; Windows 10 "should work, not tested".* Answer: Windows 10 is the target
- **Q4 [P0] What you can test.** Do you have Windows Server (Data Deduplication, service accounts), ReFS, ARM64 hardware, a domain, or an OneDrive account? Anything untested gets listed as such in the beta docs. *Default: Windows 10/11 x64 plus GitHub runners only.* Answer: Windows 10 pc
- **Q5 [P7] Beta → stable criteria.** In which version does Windows ship as beta, and what makes it stable: a number of releases with no Windows-specific security bugs, the full manual checklist passing, or an external review? *Default: beta for 2 releases, stable once the checklist passes and no open security issues remain.* Answer: always beta
- **Q6 [P4] Security review.** Is there budget or time for an external review of `PathPolicy` and the authorization paths, or are property-based fuzz tests in CI enough? *Default: fuzz tests in CI, no paid review.* Answer: review is later

### B. Platform and dependencies
- **Q7 [P0] Java baseline: 21 or 25?** 25 is the current LTS and has final FFM. It forces the Kotlin 2.2+ and Spring Boot 3.5+ upgrades. *Default: 21 now, 25 as a later separate upgrade.* Answer: keep 21
- **Q8 [P3] Native access.** JNA (a new 3 MB dependency that works on 21) or FFM (no dependency, needs 25)? Or no native code at all, accepting that Windows has path-only identity and no junction-tag detection? *Default: JNA, unless Q7 = 25.* Answer: JNA
- **Q9 [P0] Kotlin and Spring Boot upgrades.** Do they happen as separate PRs before this work, inside it, or not at all for now? *Default: separate PRs first, only if needed by Q7.* Answer: no upgrades
- **Q10 [P7] Build entry point.** The Gradle node plugin (`gradlew bootJar` builds everything), keep the shell/PowerShell scripts, or a Node build script? *Default: the Gradle plugin, keeping the scripts as thin wrappers.* Answer: per default
- **Q11 [P0] Frontend test tooling.** Is it OK to add Vitest and Playwright as devDependencies and run them in CI? *Default: yes.* Answer: no

### C. Paths, URLs and UX
- **Q12 [P2] Wire format on Windows.** `C:/Users/x` (forward slashes, which are URL-friendly) or `C:\Users\x` (native) in the API and DB? *Default: forward slashes.* Answer: forward slashes
- **Q13 [P2] Route format.** Per-segment encoded `/files/C:/Users/x` (readable, but needs a Tomcat encoded-slash setting for page reloads, §9.2), `/files?path=C:/Users/x`, or an opaque base64 token? *Default: per-segment encoded.* Answer: per-segment encoded
- **Q14 [P2] Display format.** Do breadcrumbs, the details sidebar and "Copy path" show `C:\Users\x` (native) or the wire form? *Default: native backslashes for display and copy.* Answer: native backslash for display and copy
- **Q15 [P2] Virtual root.** Is there a synthetic `/` that lists drives? Which drives: all fixed drives, or only those containing exposed folders? Should non-admins see it? *Default: synthetic root; admins see all fixed drives, non-admins only the drives that contain something exposed to them.* Answer: for windows, `/` will display all available drives, permissions and folder exposure configs can apply to roots of drives.
- **Q16 [P2] Wrong case in URLs.** If someone opens `/files/c:/users/x`: serve it and rewrite the URL to the canonical case (`replaceState`), redirect, or 404? *Default: serve and rewrite.* Answer: serve and rewrite
- **Q17 [P2] Portable filenames on Linux.** Should a Linux server optionally reject Windows-invalid names (`FM_PORTABLE_FILENAMES`) for mixed environments? *Default: no; only sanitize on client download.* Answer: validate anything based on the OS the server is running on.
- **Q18 [P2] Hidden/system files.** Hide them by default with a per-user toggle, always show them, or match whatever Linux does with dotfiles today? *Default: hide with a toggle.* Answer: show them
- **Q19 [P2] Extra metadata.** Show creation time, a read-only flag or an "online-only" badge for OneDrive files? *Default: creation time yes, the others later.* Answer: only native metadata such as creation time (or modification time?)
- **Q20 [P2] Sorting.** Should natural sort (`file2` before `file10`, like Explorer) apply on all platforms? That is a visible change for Linux users. *Default: yes, everywhere.* Answer: it remains as-is
- **Q21 [P8] Folder picker.** A server-side folder picker for path settings (upload folder, thumbnail cache, home folder, exposed folders): v1 or later? It matters more on Windows. *Default: later.* Answer: later

### D. File identity and permissions
- **Q22 [P3] Hard links.** Keep `file_key` unique (the first path wins, the others are path-only), or make it non-unique and resolve by `(file_key, path)`? *Default: unique, first path wins.* Answer: unique, first path wins
- **Q23 [P3] What follows a file.** Do permissions and shares follow a cross-volume move made through Filemat (copy + delete, then re-key)? Do they ever follow a copy? *Default: moves yes, copies never.* Answer: moves yes, copies never
- **Q24 [P3] Drive-letter changes.** Keep exposure path-based (a changed letter un-exposes the folder, which fails safe)? Or key exposure by volume GUID plus relative path, so it follows the drive (more work, new schema)? *Default: path-based, documented.* Answer: -based, documented
- **Q25 [P3] Weak-identity volumes (FAT, exFAT, network).** Path-only identity, plus a UI warning when setting a permission there? *Default: yes, with a warning.* Answer: yes, with a warning
- **Q26 [P3] Docker Desktop.** Officially supported or best effort? If inodes turn out unstable on Windows bind mounts: detect it and warn at startup, mark those mounts unsupported, or only document it? *Default: best effort; warn at startup if detected.* Answer: best effort; warn at startup if detected
- **Q27 [P3] Migration safety.** Take an automatic `VACUUM INTO` backup of the DB before V9 runs? Is a slow first start on large Linux DBs acceptable (one stat per entity)? *Default: auto-backup yes; slow start acceptable, with progress logged.* Answer: Default: auto-backup yes; slow start acceptable, with progress logged
- **Q28 [P3] `inode` column.** Keep it read-only for one release, then drop it, or drop it in V9? *Default: keep for one release.* Answer: keep for one release. Changed (owner, 2026-10-26): dropped in V9.

### E. Security
- **Q29 [P1/P7] Service account.** Virtual account `NT SERVICE\Filemat` (safe, but each exposed folder needs an ACL grant, §4.9), a dedicated local user, or LocalSystem? When running as LocalSystem or elevated: refuse to start, or warn? *Default: virtual account; warn loudly and don't refuse.* Answer: virtual account; warn loudly and don't refuse, should be able to be ran as normal user
- **Q30 [P1] Unreadable exposed folders.** When an admin exposes a folder the service account can't read: show a hint, offer to grant the ACL (needs elevation), or do nothing? *Default: show a hint with the `icacls` command.* Answer: show a hint with the `icacls` command
- **Q31 [P4] Sensitive list content.** Is blocking *all* of `C:\Users\*\AppData` acceptable, or do some users need parts of it? Can `FM_HIDE_SENSITIVE_FOLDERS=false` be used on Windows as it is on Linux? *Default: the list as in §4.1; the flag works the same.* Answer: do not block entire appdata, flag works the same
- **Q32 [P4] Junctions and the link flag.** Are junctions and mount points governed by the existing `FM_FOLLOW_SYMBOLIC_LINKS`, or by a separate flag? What's the default on Windows? Profiles contain many legacy junctions. *Default: the same flag; default off.* Answer: Default: the same flag; default off
- **Q33 [P1] Data-dir ACL failure.** Exit, or warn? *Default: exit when running as a service with an explicit `FM_DATA_DIR`, warn on interactive runs.* Answer: exit when running as a service with an explicit `FM_DATA_DIR`, warn on interactive runs
- **Q34 [P2] Subst and mapped drives.** Reject them, or resolve them to the underlying/UNC path (§2.4)? *Default: reject mapped drives, resolve `subst`.* Answer: reject mapped drives, resolve `subst`
- **Q35 [P8] UNC shares.** Out of v1, or allowed through an admin allow-list in v1? *Default: out of v1; every UNC input rejected before any I/O.* Answer: out of v1; every UNC input rejected before any I/O
- **Q36 [P2] Case-sensitive directories (WSL flag).** Refuse to expose them, or document them as unsupported? *Default: document.* Answer: document
- **Q37 [P7] Firewall rule.** Should the installer add an inbound rule automatically, behind an opt-in switch, or never? *Default: opt-in switch.* Answer: never

### F. File operations
- **Q38 [P5] Read-only attribute.** Clear `dos:readonly` automatically on delete/overwrite when the user has Filemat permission, or fail with a message? *Default: clear it and log.* Answer:
- **Q39 [P5] Files in use.** Retry 3 × 100 ms and then return a 409 "File is in use"? Should the message name the locking process? That needs the Restart Manager API, which is more native code. *Default: retry plus 409, no process name.* Answer: retry plus 409, no process name
- **Q40 [P5] Delete-pending fallback.** Rename-then-delete can leave `.filemat-deleting-*` files behind after a crash. Is that OK, with a sweep at startup? *Default: retry first, rename as a fallback, startup sweep.* Answer: retry first, rename as a fallback, startup sweep (must not be slow or heavy). Changed 2026-10-25: retry plus 409 only (see §6.1).
- **Q41 [P5] Recycle Bin.** Windows users expect it. Confirm that delete stays permanent, with a clear warning in the UI? *Default: permanent; reword the delete dialog on Windows.* Answer: permanent; reword the delete dialog on Windows
- **Q42 [P4] Non-link reparse files** (OneDrive placeholders, dedup files, §4.4). Treat them as regular files? May a download hydrate a placeholder (possibly gigabytes)? Skip thumbnails and content sniffing for them? *Default: regular files; downloads allowed; never thumbnailed or sniffed.* Answer: regular files; downloads allowed; never thumbnailed or sniffed
- **Q43 [P5] Paths over 260 characters.** Fully support them for file operations and skip previews, or also pass `\\?\` paths to FFmpeg? *Default: full support for file operations, `\\?\` for FFmpeg.* Answer: full support for file operations, `\\?\` for FFmpeg

### G. Media
- **Q44 [P6] External FFmpeg on Linux too?** If yes: the Docker image adds the `ffmpeg` package, and native installs must install it or lose video previews. If no: two media code paths to maintain. Remove javacv after one release? *Default: external everywhere; javacv behind a flag for one release, then removed.* Answer: two media code paths to maintain
- **Q45 [P0] Drop opencv now.** It isn't referenced in the code. Remove it in Phase 0 after confirming the tests and video thumbnails still work? *Default: yes.* Answer: yes
- **Q46 [P6] FFmpeg build and updates.** Which build (BtbN or gyan.dev, GPL; both are fine under GPLv3) and which version? Is it downloaded at build time with a pinned checksum, or committed? How often is it bumped? *Default: BtbN GPL, a pinned release with a checksum, bumped each Filemat release.* Answer: BtbN GPL, a pinned release with a checksum, bumped each Filemat release
- **Q47 [P6] Missing FFmpeg.** Degrade silently, or show a warning in admin settings? *Default: warning in admin settings plus a startup log line.* Answer: warning in admin settings plus a startup log line

### H. Packaging and operations
- **Q48 [P7] Distribution formats.** The zip (jlink JRE plus WinSW v2), a plain JAR for people with their own JRE, an MSI, winget/Scoop/Chocolatey? Which are in v1? *Default: zip plus JAR in v1, the rest later.* Answer: zip plus JAR in v1, the rest later
- **Q49 [P7] Code signing.** Budget for signing, for example Azure Trusted Signing (about $10/month)? Or ship unsigned and accept SmartScreen warnings? *Default: unsigned beta.* Answer: unsigned beta
- **Q50 [P7] Configuration on Windows.** Env vars in the WinSW XML (the same model as Linux), or add a config file in the data dir (`filemat.conf`)? A config file would then apply to Linux too. *Default: env vars only in v1.* Answer: env vars only in v1
- **Q51 [P7] Install locations and names.** App in `C:\Program Files\Filemat`, data in `%ProgramData%\Filemat`, service name `Filemat`, display name "Filemat"? *Default: as listed.* Answer: as listed
- **Q52 [P7] Upgrades.** A manual `upgrade.ps1` (stop, swap the app dir, start, keep the previous dir for rollback), or an in-app update check? *Default: script only.* Answer: script only
- **Q53 [P7] Logs and port.** File logs with rotation under `%ProgramData%\Filemat\logs`, and default port 8080 despite the Hyper-V excluded-port risk? *Default: yes to both, with the bind-failure hint from §5.4.* Answer: yes to both, with the bind-failure hint from §5.4.
- **Q54 [P1] Linux upload default.** Move `/tmp/filemat` → `<dataDir>/uploads` for new installs only, for all installs, or not at all? *Default: new installs only.* Answer: linux default unchanged
- **Q55 [P0] CI cost and policy.** Is the repo public (Windows minutes are free), or is there a minutes budget? When does Windows CI become a required check: the Phase 1 milestone or P7? Do ARM runners run nightly? *Default: required from the Phase 1 milestone; ARM nightly.* Answer: ci/cd is irrelevant and repo is public

### I. Follow-ups to the answers (2026-10-24)
**Status: all answered and folded into the sections above (2026-10-24).** Later decisions that override earlier answers:
- **Portable, not installed:** a zip with no service, no installer and no install scripts. Data goes in `%LOCALAPPDATA%\Filemat`. This supersedes the service parts of Q29, Q33, Q37, Q51 and Q52 (§8).
- **Q36:** per-directory case sensitivity must be *supported*, not just documented (§2.3).
- **Q38:** read-only files stay read-only. Filemat never clears `dos:readonly` (§6.2).
- **Q24** means path-based. **Q1** means work stays on `windows-support`.

- **Q56 [P0] Java version.** The toolchain is `jvmToolchain(17)` today, but Q7 says "keep 21" and Q9 says "no upgrades". Should the toolchain and the bundled JRE move to 21? That works with Kotlin 1.9.25 and Spring Boot 3.4.2 as they are. Or stay on 17, which JNA also supports? *Default: move to 21 and upgrade nothing else.* Answer: java 21
- **Q57 [P6] Windows JAR (follows from Q44).** Linux keeps the bundled javacv natives and Windows uses `ffmpeg.exe`. Does the Windows zip ship the same JAR, or a separate build without javacv natives? The same JAR carries unused Linux natives, which fail to load and fall back cleanly. *Default: same JAR, one artifact.* Answer: same JAR, one artifact
- **Q58 [P6] Media backend selection.** Use javacv when its natives load, otherwise the external `ffmpeg` (`FM_FFMPEG_PATH`, then `PATH`), on any OS? That also covers a plain JAR run on Windows. *Default: yes.* Answer: yes
- **Q59 [P1] Data dir for a normal-user run (follows from Q29).** When Filemat runs from `bin\filemat.cmd` instead of as a service, does it use `%ProgramData%\Filemat` (shared with the service, so the ACLs get messier) or `%LOCALAPPDATA%\Filemat`? *Default: `FM_DATA_DIR` if set, otherwise `%LOCALAPPDATA%\Filemat`; the service sets `FM_DATA_DIR` to `%ProgramData%\Filemat`.* Answer: per default
- **Q60 [P4] Which parts of AppData stay blocked (follows from Q31).** *Default list:*
  - `Roaming\Microsoft\{Credentials,Protect,Crypto,SystemCertificates}` and `Local\Microsoft\{Credentials,Vault}`.
  - Browser profiles: `Local\Google\Chrome\User Data`, `Local\Microsoft\Edge\User Data` and `Roaming\Mozilla\Firefox\Profiles`.
  - Also `C:\Users\*\.ssh`, `C:\Users\*\NTUSER.DAT*` and the Filemat data dir.

  Answer: as listed
- **Q61 [P2] Drive list in `/` (follows from Q15).** Should it include removable (USB) and optical drives? Mapped network drives are already out (Q34). Do non-admins see every drive letter, or only the drives they can open? *Default: fixed and removable drives with media; non-admins see only drives with something visible to them.* Answer: all drives available are visible.
- **Q62 [P2] Hidden and system files (follows from Q18).** They're shown like normal files. Do `$Recycle.Bin`, `System Volume Information`, `pagefile.sys`, `hiberfil.sys` and `swapfile.sys` go on the default hidden list? *Default: yes.* Answer: yes
- **Q63 [P0] Tests without CI (follows from Q11 and Q55).** Windows-only server tests would be `@EnabledOnOs(WINDOWS)` and run by you with `gradlew test`. The frontend `path.ts` gets no automated tests. Is that OK? *Default: yes.* Answer: yes
- **Q64 [P0] Line-ending renormalize.** Should the `.gitattributes` and `git add --renormalize .` commit land now, as its own commit before more code changes, or at the end? It touches about 323 files. *Default: now.* Answer: ignore commits
- **Q65 [P7] Release builds.** With no CI, the zip is built on your machine (`gradlew windowsDist`, which downloads the pinned FFmpeg) and uploaded by hand, like the JAR today. Is that OK? *Default: yes.* Answer: yes

# Windows support: changelog

A short write-up of each finished chunk of work toward [windows-support-plan.md](windows-support-plan.md), newest first. Open items are in [windows-status.md](windows-status.md).

## 2026-10-31: History squashed into 4 commits

- The branch's 60 incremental commits are now four, one per area: build and packaging, server with its tests, web, docs. The server code and its tests share one commit because the code alone breaks `main`'s old tests. The final tree is byte for byte the one tested below.
- Checked: each commit compiles on its own (server code and tests). `main`'s tests pass on the first commit, and the full suite passes on the second, in a clean worktree. Web 77 tests and svelte-check pass on the final tree.
- Commit hashes in the entries below refer to the old history, kept on the local branch `windows-support-backup` (and on `origin/windows-support` until it is force-pushed).

## 2026-10-31: Fixes 27, the code review's low findings (L1-L10, I1)

- **L1:** a `subst` drive that points to a folder on a mapped network drive is refused before anything connects. `localDrivePath` checks the folder it points to the same way, up to 26 drives deep.
- **L2:** the "everything under this folder" queries never matched the children of `/` or `C:/` (`//`, `C://`). They now trim the root's trailing `/`, and moving saved files from a root keeps one separator. Not reachable today; it was a trap for later callers.
- **L3:** on FAT and exFAT, the case flag of every folder was read again for every path key. A folder without the flag is now cached as not case-sensitive.
- **L4:** starting with a drive unplugged folded the keys of its case-sensitive folders, so `a` and `A` there collided and the newer entity lost its path, with its permissions and shares. Rows whose folder is missing keep their stored key.
- **L5:** `$Recycle.Bin`, `System Volume Information` and the page files were only hidden on drives present at start-up. They are now checked by name.
- **L6:** tus error responses were hand-built JSON, so a message with a quote or a backslash (any Windows path) broke it. They are serialized now.
- **L7:** the thumbnail cache folder was stored as typed, the upload folder as its real path, so a link or another case compared differently. Both are stored as the real path.
- **L8:** changing the upload folder could move `uploads` and then fail on `locks`. Both targets are checked first, and a failed second move moves the first back.
- **L9:** saving an edited file could end half new and half old. Not fixed with a temp file swapped in, which would give the file a new ID (permissions and shares follow it), the parent's ACL and no hard links. The save now locks the whole file first, so another program's lock on part of it gives 409 before anything is written, and it writes the bytes past the old end first, so a full disk fails before the old content is touched.
- **L10:** after saving the upload folder, the input could keep the typed text and Save stayed enabled. It now shows the path the server answered with.
- **I1:** the web compared typed Windows paths with `toLowerCase`, the server with per-letter upper case. `foldCase` in `path.ts` folds like the server, so `ı` and `i` match while `ß` and `ẞ`, or `k` and the Kelvin sign, don't.
- **I2** was wrong: a `!` in the folder of `filemat.cmd` doesn't break the code page check. The same lines, run with `!` in the folder name, give the folder back unchanged.
- **Tests:** `WindowsPathSecurityTest` (subst on a mapped drive), `PathPrefixQueryTest` (root prefixes), `PathKeyTest` (flag cached on FAT), `PathKeyRebuildTest` (missing folder keeps its keys), `FileVisibilityServiceTest` (a drive plugged in later), `TusServiceTest` (only `locks` in the new folder), `ThumbCacheSettingServiceTest` (stored through a link, and in another case), `FileContentServiceTest` (a longer text, and another process locking bytes 50-59), web `path.test.ts` (case folding).
- **Run:** server 879 tests, 0 failed, on Windows (50 skipped) and on Linux in WSL2 (160 skipped, the Windows-only ones). Web 77, svelte-check 0 errors.

## 2026-10-31: Fixes 26, the code review's medium findings (M1-M4)

- **M1:** the data folder is made private only when Filemat creates it. Before, every start rewrote the ACL of whatever `FM_DATA_DIR` named, and on Windows that reached every file inside, so `FM_DATA_DIR=D:\` would have locked a whole drive. An existing folder keeps its permissions, and startup warns when other accounts can access it (`AclPolicy.isRestricted`). The database is still restricted on every start, and the `native` and `logs` folders when Filemat creates them, so other accounts can't swap the DLLs. On Linux the folder is back to `main`'s behaviour (only when created).
- **M2:** an ACL that the JDK reads as empty (a NULL DACL, which gives everyone full access) no longer counts as private.
- **M3:** a symbolic link whose target is on another drive is checked before it is followed. A network drive, a `subst` drive of a network folder or a drive of unknown type counts as the network (`isOnNetworkDrive` in `utilFunctions.kt`, built on `localDrivePath`). `resolvePath` and `resolveNonExistent` also refuse a resolved path on a network drive, as they already did for UNC paths.
- **M4:** deleting resolves the folder first, then checks whether the name in it is a link, like move does. With links off, a link inside a linked folder is no longer checked as if it were in the folder that holds the link. A path without a drive is not found. This was also on Linux and `main`.
- **Tests for the fixes:** `AclPolicyTest` (empty ACL), `StartupNativeFolderTest` (a start on an existing folder leaves its ACL and those inside, and restricts the database), `WindowsPathSecurityTest` (a link to a mapped drive, a resolved path on a network drive), new `FileDeletionServiceTest` (link in a linked folder with links off and on, root, path without a drive).
- **Tests for code that had none:** new `TransactionsTest` (`inOwnTransaction` commits, rolls back, and leaves a caller's transaction alone) and `MigrationHasColumnTest`; `SharePathTest` gets `toShareRelativePath`, `joinSharePath` and `symlinkImmediateTargetsStayInside`; `SafeWalkTest` gets the walk identity helpers that stop link loops; `AclPolicyTest` gets `isRestricted` on Linux.
- **Run:** server 868 tests, 0 failed, on Windows (49 skipped) and on Linux in WSL2 (154 skipped, the Windows-only ones). Web 76 tests, svelte-check 0 errors. Symbolic links can't be created on this Windows account, so the tests that need them were skipped there. All of them except the two M3 tests also run on Linux and passed; the two M3 tests are open in [windows-status.md](windows-status.md).
- **Docs:** the review, [windows-status.md](windows-status.md), [windows-branch-guide.md](windows-branch-guide.md), [windows.md](windows.md) and [windows-overview.md](windows-overview.md) describe the new behaviour.

## 2026-10-30: Code review of the whole branch

- **Review:** the branch was reviewed as one diff against `main` (server, web, build, packaging). 4 medium and 10 low findings, each with a fix, are in [windows-code-review.md](windows-code-review.md) and listed in [windows-status.md](windows-status.md). None is fixed yet.
- **Clean-ups, no behaviour change, not built:** `getPathRelationship` removed (dead), unused imports removed, import order fixed in 11 files, a few one-line simplifications, a leftover `console.log`, stale W37 lines in windows-overview.md. The full list is at the end of the review.

## 2026-10-30: Fixes 25, W37 in the page

- **W37:** a file or folder opened through a `subst` drive (`/files/S:/names/a%23b.txt`) still showed `S: > names > a#b.txt` in the page, because the page builds its path and breadcrumb from the URL, and fixes 20 had only changed the `meta.path` the server sends. `loadPageData` already switched the URL to the real case for a path typed in the wrong case; it now does that too when the real path is on another drive (`isOtherDrive` in `path.ts`: a Windows path can only come back on another drive through a `subst` drive). Only that case redirects, so a followed link on Linux keeps its URL. 2 new web tests (76 in all, svelte-check 0 errors). Checked in the page with a real `subst S:`: `S:/names/a%23b.txt`, `S:/names` and `s:/Names/A%23B.TXT` land on `C:/FmTest/names/…` with the breadcrumb `C: > FmTest > names`, and a `C:` URL is left alone. W37 is PASS again.

## 2026-10-30: Agent test run (W45, W31, W37, L7)

- **W45:** PASS. Zip built from `c081e583` (`windowsDistX64`, version `0.0.0-test`), started with its own `jre\bin\java.exe` on a new `FM_DATA_DIR`. TOTP: 2FA on, a wrong code gives 400, the right code signs in. Zip download of a 9-entry folder: same names, same hashes. No missing-class or module errors.
- **W31:** the visibility part passes again with `C:\` exposed: `.ssh`, `System32\config`, `$Recycle.Bin` and the running data folder are refused (400, sensitive), `C:\` and `C:\Users` list. The delete and move of `C:\Windows` and `C:\Users` was not run: it is destructive if the refusal fails.
- **W37:** back to PART. `meta.path` is real through `subst`, but the page's breadcrumb still showed the `subst` path (fixed the same day, see Fixes 25 above).
- **L7:** PASS in a browser: the six odd names open by URL on a Linux server (WSL2, the zip's JAR with the web app).
- **Not run, on purpose:** L8 `.heic` (no encoder here), everything that needs admin rights, extra hardware, Docker or another device.

## 2026-10-30: Linux test run after fixes 19-24

- **Tests:** server tests on Linux (WSL2 Ubuntu, Java 21) at `c081e583`: 853 run, 0 failed, 0 errors, 161 skipped (the tests that need Windows). Run from a copy of the repo in the WSL home, so `server/build` isn't shared with the Windows build.
- **Docs:** [windows-status.md](windows-status.md) has the two build snags on this PC (`JAVA_HOME` set to JDK 25, and the unquoted `-PreleaseVersion` in PowerShell).

## 2026-10-29: Fixes 24, open code items from the second pass

- **Small bugs:**
  - **Docs:** the test plan's section 9 says what the drive list shows: `/` itself has no dates or size (the web hides them), and the drives in it show their own. The code was right.
  - **R19:** file retries (3 × 100 ms per file) now share a budget per operation. `FileErrors.withRetryBudget` (3 s) wraps the recursive move, copy and delete, which run while they hold the WRITE lock; when it is used up, a file in use fails at once. Single-file calls and everything outside those operations keep 3 × 100 ms. 3 new tests.
  - **1.2-d:** the duplicate check of a folder upload uses the NTFS fold (`WindowsPathPolicy.foldCase`, simple upper case per code point) instead of `lowercase()`, and not at all when the destination folder is case-sensitive. `ß` and `ẞ`, and the Kelvin sign and `k`, are different names; `i` and `ı` are the same. 5 new tests (the case-sensitive folder one needs `fsutil`, and is skipped without it).
  - **1.3-h:** every start also gives entities without a file key the key of their file (`DatabaseSetup.initialize_repairFileKeys`, the V9 backfill limited to entities on supported filesystems), so files that came back while the server was stopped no longer wait to be opened. 2 new tests.
  - **1.13-c:** the file name typed in the folder selector loses `\` as well as `/` on Windows (`stripSeparators` in `path.ts`); on Linux a backslash is a normal name character and stays. 2 new tests.
  - **1.12-e:** can't be done: after Ctrl+C `cmd` asks "Terminate batch job (Y/N)?" and ends the file on Y without running anything more. `filemat.cmd` says so in a comment, and [windows.md](windows.md) tells the user to answer `N` to get the old code page back.
  - **1.12-b:** a leftover `server/src/main/resources/static` no longer puts every file in the JAR twice: `processResources` leaves `static/**` out when the web app is built. Not `duplicatesStrategy = EXCLUDE` in `bootJar`, as planned: tried with a leftover file, it keeps the old file (resources come before the web build). With the exclusion the web build's file is the one in the JAR. `-PskipWeb` still packs what is there.
  - **FFmpeg priority:** the external FFmpeg runs at below normal priority (`WindowsNative.lowerPriority`, `SetPriorityClass` right after the start; if it fails, it runs at normal priority). 2 new tests. Share-delete stays open: FFmpeg has no option for it.
  - **Access denied hint:** on Windows, the toast for "Missing permission to copy, move, delete or replace file" and "…to open this folder" says to check the account's access (`icacls`) and Controlled folder access (`withAccessDeniedHint`, used by `handleErr`). 8 new tests.

- **Tests:**
  - **R20:** the test plan has W45, a smoke test of the zip's jlink'd runtime: the module list against `jdeps` (run on the unpacked JAR, since on the fat JAR it only sees the launcher), a TLS handshake with the runtime's `keytool`, a TOTP sign-in and a zip download. Steps 1-2 ran by hand and passed; 3-4 are open.
  - **Q3:** `clearCaseSensitiveFlag` in `testutil/CaseSensitiveFolders.kt` runs `fsutil … disable` and checks its exit code (skips the test when it fails), like `createCaseSensitiveFolder`; `PathKeyTest` uses it.
  - **Q10:** the elevation test compares `isElevatedOrSystem()` with what `whoami /groups` and `/user` say, instead of `!= null`.
  - **Q11:** `testutil/FileOperationMocks` holds the mocks and data-folder `State` that the move, copy and delete service tests each wired up on their own.
  - **Q12, Q13, Q15, Q16, Q20, Q21:** the comment in `validation.test.ts` names the server tests it mirrors; `path.test.ts` uses `it.each` for its tables; the test that swaps `System.out` takes the JUnit resource locks for it; `StateControllerTest` has no `!!`; `ExternalFfmpegBackendTest` finds ffmpeg through `FM_FFMPEG_PATH` before `PATH`, like the app; KDoc on `isReadOnly` and `isHidden`.
  - Left as they are, with the reason in [windows-status.md](windows-status.md): Q7 and Q8 (the real tests exist), Q14, Q18, Q19.
- **Clean-up, no behaviour change:**
  - **C3:** `inOwnTransaction` (`config/database/Transactions.kt`) is the transaction wrapper of `FileKeyBackfill` and `PathKeyRebuild`.
  - **C6:** `WindowsNative.withLongPathPrefix(path, limit)` is the one `\\?\` rule; the limits are named (`folderPathLimit` 248 for `WindowsNative`, `filePathLimit` 260 for the FFmpeg input).
  - **C7:** `PathPolicy.isUncPath` is the one check for a network path (used by `resolvePath` and `DatabaseLocationPolicy`).
  - **C10:** `isJunctionLike` is gone; callers use `LinkInspector.isDirectoryLink`, which has its KDoc.
  - **C9, C14, C31:** `FfmpegSupport` moved to `service/file/media/`; `isDataFolderLocked` in the move and delete services is `isWriteDataFolderProtected`, like the copy service; `isNotStoredLocally` moved from `FileUtilController` to `LinkInspector`.
  - **C12, C23:** removed `PathPolicy.resolve` (and its two tests) and `getZipContentUrl`, which nothing called.
  - **C27:** `FilesystemEntity.pathKey` has no default that does file system calls on Windows; the one caller already passed it, and the tests that built entities without it pass it now.
  - **C28:** comment on the `hasColumn` guards of V9.
  - **C29:** the 7 import lines this branch added to the web app have no semicolons.
  - **R22:** `docs/database.md` and `docs/docker-deployment.md` are LF in the repo now (`git add --renormalize`; whole-file diff, no text change).
  - Left as they are, with the reason in the status doc: C20, C21, C22. The big refactors (C11, C15, C16, C18) were skipped on purpose, as asked.
- Server 853, 0 failed (41 skipped); web 74, svelte-check 0 errors.

## 2026-10-26: Fixes 19-23, bug fixes after the test run

- **Docs:** [windows-status.md](windows-status.md) is the one entry point for what's left: the checks for a person, merging, the release, and the open code items from the second pass (they were in a local-only results file). The plan's tracker is now the history.

- **Fixes 23, tests and housekeeping:** `FileUtilControllerThumbnailTest` covers fixes 18 (a thumbnail or video preview that fails is a 500 without `Cache-Control`; a working one is a JPEG with the long cache header for large sizes). A root `.gitattributes` (`*.cmd text eol=crlf`) makes a checkout write `filemat.cmd` with CRLF (the index keeps LF; the zip build already converted it to CRLF). Test plan steps fixed where the 2026-10-26 run flagged them: W18 (saved file by a move inside Filemat), W31 and W33 (`%LOCALAPPDATA%Filemat` is only refused as the running data folder), W41 and L12 (cleanup at the start and every 2 hours; L12's upload folder).
- **Fixes 22, 1.7-d:** replacing a file in a folder where the account can't create files said "File is in use", because any access denied on a writable target counted as in use. Now only the swap step reads it that way (Windows reports a replace of an open file as access denied); access denied while the temp file is made or filled gives "Missing permission to replace file.". Sharing and lock violations and the 3 × 100 ms retry are unchanged. Test with an `icacls` deny on the folder (failed before the fix). Server 840, 0 failed (41 skipped).
- **Fixes 21, N4:** with links off, a junction that differs only in case (`a` to `A`, inside a case-sensitive folder) was followed, because `resolvePath` compared the typed and the real path with `Path.equals`, which ignores case on Windows. `WindowsPathPolicy.isSamePath` now also compares path keys, which fold case only where the folder does. Real-junction tests (confirmed failing first); a path in the wrong case in a normal folder still opens. Server 839, 0 failed (41 skipped).
- **Fixes 20, W37:** a file opened through a `subst` drive showed the `subst` path in the page and its metadata. `FileMetadataService.displayPath` now moves the requested path to the folder the drive points to (`localDrivePath`) before comparing it with the canonical path, so it shows the real path like the listing entries. A followed link keeps its own name, on the real drive. 2 new tests; server 836, 0 failed (41 skipped). Checked by API with a real `subst S:`. W37 is PASS.
- **Fixes 19, large-image thumbnails:** `ThumbnailService.streamImageThumbnail` decoded the whole image to learn its size (a 12000 × 12000 TIFF: about +1 GB in `java.exe`, on Windows and Linux). The size now comes from the header (`readImageSize`, moved out of `FileContentMetadataService` and shared), and Thumbnailator reads the image with the target size. Its subsampling needs `thumbnailator.conserveMemoryWorkaround=true`, which `ThumbnailService` sets (it is off by default). Measured: private memory +988 MB before, +16 MB peak after. If ImageIO can't read the header, the old full decode and the FFmpeg fallback run as before. 4 new tests; server 834, 0 failed (41 skipped).

## 2026-10-26: Test run, chunks B-E

- **Manual tests:** every `agent` check of the test plan has now run (W5 again, then chunks B-E), on Windows 10 Pro 22H2 x64 and WSL2 Ubuntu: 37 PASS, 8 PART, 3 SKIP, 1 FAIL overall. B1 and B2 are confirmed fixed: every chunk started twice on a new data folder.
- **New failure, W37:** a file opened through a `subst` drive shows the `subst` path in the page and its metadata. Display only; the checks use the real path. Open in the tracker.
- **Seen:** the thumbnail of a 12000 × 12000 TIFF decodes the whole image (+1.3 GB) on both platforms. Not Windows-specific. Open in the tracker.
- **Linux test suite** in WSL at 4204132: 830 tests, 0 failed, 149 skipped (Windows-only). The new symbolic-link copy test ran for the first time and passed. The test plan's "Before each release" now includes this run (Q4).
- **Test plan fixes:** the big TIFF's file size, W27's exposed folder, W23's file, W19's drive list, and a note on the thumbnail memory. Left for a person: the `you` checks and the admin, Developer Mode and browser parts of the PART and SKIP rows.

## 2026-10-26: Fixes 18, thumbnail errors sent as JPEG

- Not Windows-specific. `ThumbnailService` wrote error text ("Error generating image thumbnail", "Could not extract frame from video", and so on) into the output stream when the FFmpeg fallback or the video preview failed. The controllers saw non-empty bytes and answered `200` with `image/jpeg` and, for large sizes, `Cache-Control: private, max-age=86400`, so a browser could keep the text as the thumbnail. Those paths now throw, and the controllers answer 500 with no cache headers (the existing `catch (e: Exception)`, which logs). The service-worker cache already stored only `response.ok`. Server tests 830, 0 failed (41 skipped); no test for the 500 path yet.

## 2026-10-26: Fixes 17, indentation and package name

- **C17**: `ServerApplication.kt` is indented with 4 spaces, as `.editorconfig` says (whitespace only).
- **C8**: the platform code stays in `config.platform`; the plan's §2.2 no longer says `common.platform`.

## 2026-10-26: Fixes 16, web argument order

- **C19**: `codeUtil.isPathDirectChild(parent, child)` called `path.isDirectChildOf(child, parent)`, the opposite order. It is gone: the four callers (`files.ts`, `fileActions.ts`, the files page) call `isDirectChildOf(child, parent)` from `$lib/code/util/path`, like `isChildOf`. Its test moved to `path.test.ts`.
- No behaviour change. svelte-check 0 errors, web 57 tests.

## 2026-10-26: Fixes 15, data folder checks and path key rebuilds

- **C13**: copy, move, delete and the zip download ask `State.app.isInDataFolder` instead of repeating `getPathRelationship(…, dataFolderPath).isInsideTarget` (the same `startsWith`).
- **C2**: V9 and every Windows start both rebuild the path keys. Both stay: the migration leaves a complete column without relying on a later startup step, and the startup rebuild picks up flags changed while the server was stopped. A comment in each says so.
- No behaviour change.

## 2026-10-26: Fixes 14, two clean-ups

- **C4**: `FolderUploadService` checked a trailing dot or space on Windows after `PathPolicy.validateFileName`, which already refuses it; the dead branch is gone.
- **C5**: the two `toRegexList` functions are `SensitiveFolderPaths.toSubtreeRegexList` (a path and everything inside it) and `NonDeletableSystemPaths.toExactRegexList` (only the path itself). No behaviour change.

## 2026-10-26: Fixes 13, test helpers

- **Q2**: `TestLinks.kt` runs `mklink /J` in one place (`createJunction`).
- **Q5**: `-Dfilemat.requireWindowsFeatures=true` (or `FILEMAT_REQUIRE_WINDOWS_FEATURES=true`) makes the tests that need 8.3 names, `subst`, junctions, case-sensitive folders or a profile's legacy junctions fail instead of skip (`assumeWindowsFeature`). The build now passes this and `filemat.requireSymlinks` to the test JVM; before, the `-D` form of the symlink switch never reached it. On the Windows 10 PC every such test runs: the 41 skips are POSIX-only, symlink-privilege and OneDrive tests.

## 2026-10-26: Fixes 12, a flaky test and docs that disagreed with the code

- **Test**: `FileErrorsTest` closed the open file from another thread after 150 ms; it now closes it inside the second try, so the retry is tested without timing (Q6).
- **Docs** (R21): the plan's package list names what exists (`config.platform`, no `AtomicFiles` or `FilenameValidator`). The JAR is `filemat.jar` everywhere users see it, and `server/build/libs/server-app.jar` where the build is described. `FileService.deleteFiles` says what `DeleteFilesResult` holds, and the "Folder Entries" header is above the folder entries.
- **Test plan**: W8 checks non-ASCII text through the data folder's name, since opening a file logs no file name.

## 2026-10-26: Fixes 11, more sensitive folders on Windows

- **Sensitive list** (R23, owner decision): per profile, the Brave, Chromium, Vivaldi and Opera browser profiles, Thunderbird's mail profiles, `Microsoft\TokenBroker` and `IdentityCache` (sign-in tokens), `.git-credentials`, `_netrc`, `.netrc` and the PSReadLine history folder; in the Windows folder, `CSC` (Offline Files). Matched in any case, like the rest. `docs/configuration.md` lists them.
- **Tests**: `SensitiveFolderPathsTest`.

## 2026-10-26: Fixes 10, FFmpeg busy

- **Fix**: with every external FFmpeg slot taken (at most 4), a request thread waited up to 30 s, then failed with a plain `IOException` (R12-a). It now waits 2 s at most and gets `FfmpegBusyException`, which the image thumbnail and video preview endpoints, and content metadata through `ControllerAdvice`, answer with 503 "Video previews are busy. Try again in a moment."
- A busy failure isn't the file's: `probe` no longer turns it into "no metadata" (which was cached), the thumbnail service writes no error text as the preview and caches nothing, and a metadata batch stops at it and leaves the rest out, so the web asks for them again.
- **Tests**: `ExternalFfmpegBackendSlotTest` (no ffmpeg needed), `ThumbnailServiceBusyTest`, new cases in `FileContentMetadataServiceTest` and `ControllerAdviceTest`.

## 2026-10-26: Fixes 9, `*` in the folder lists

- **Fix**: an entry with `*` in `FM_HIDDEN_FOLDER_PATHS` or `FM_FORCE_DELETABLE_FOLDERS` matched nothing, silently (R10/S7-a). Both lists compare whole paths, so wildcards stay unsupported there; each such entry now gets a startup warning that names it. `FM_NON_SENSITIVE_FOLDERS` takes `*`, because its entries name patterns of the sensitive list.
- **Docs**: the plan's §4 no longer promises wildcards; `docs/configuration.md` says which lists take `*`.
- **Tests**: `StateEnvTest`.

## 2026-10-26: Fixes 8, copying a folder with a junction

- **Fix**: a copy treated a junction or mount point as a file, and `Files.copy` made an empty real folder in its place (R18). It is now skipped: nothing is made at the destination, the rest of the folder is copied, and the result says "Links to folders were not copied." (a new `FileFailure`). A junction copied on its own is refused. Symbolic links are copied as links, as before on Linux.
- **Tests**: `FilesystemCopyServiceTest` with real junctions (in a folder, alone, as the only child) and a symbolic link to a folder (skipped without the symlink privilege).
- **Docs**: test plan §9 (known limitations) and W27's expected result.

## 2026-10-26: Fixes 7, first start on a new data folder

- **Fix**: the first start on a new data folder failed (test plan B2). Reading `Props` loaded the Windows list of non-deletable folders, which called JNA before `jna.tmpdir` was set, so JNA unpacked to `%TEMP%`, `<data>\native` was never created and SQLite couldn't extract its DLL. `Props.nonDeletableFolders` is now a getter, `prepareStartup()` sets `jna.tmpdir` and `org.sqlite.tmpdir` before any JNA class loads, and `initializeFileStructure` creates the folder after the data folder check.
- **Test**: `StartupNativeFolderTest` starts a JVM on a new data folder, checks that no JNA class loads before the properties are set, and that SQLite opens with both DLLs in `<data>\native`. A JAR started twice on a new data folder works. Test plan W5 needs a rerun.

## 2026-10-26: Second start fixed, first test run, docs trimmed

- **Fix, second start**: `sqlite-schema.sql` runs on every start and still created `idx_files_inode`, so every start after V9 failed with `no such column: inode` (test plan B1). The line is gone, and `MigrationChainTest` runs the schema again after the migrations.
- **Test run**: chunk A of the test plan on Windows 10 22H2 x64: 7 PASS, 1 FAIL, 3 PART. It found B2: the first start on a new data folder fails, because JNA loads before `jna.tmpdir` is set. Fixed in fixes 7.
- **Docs**: the beta checklist is now the test plan's "Before each release" section, and the third pass's report is removed (its findings are fixed or listed as known limitations).

## 2026-10-26: `inode` dropped, manual test plan

- **Database**: V9 also drops `files.inode` and its index (owner decision, Q28 changed: no release keeps it). The baseline `sqlite-schema.sql` still creates it, because V8 copies the column. A dev database that already ran V9 keeps an unused `inode` column.
- **Test plan**: [windows-test-plan.md](windows-test-plan.md) has the steps and expected results for every manual check, on Windows and Linux. `utilities/windows-test-fixtures.ps1` creates its test files (junctions, links, a case-sensitive folder, a 300-character path, a playlist, a short-name folder).
- **Docs**: the third pass's open issue table shows what was fixed after it.
- **Tests**: server 813, 0 failed: Windows 40 skipped, Linux (WSL) 146 skipped. New: `MigrationChainTest` runs the schema and every migration on a new database.

## 2026-10-26: Third verification pass

- **Report**: `docs/windows-verification-3.md`, removed once done (in git history). Fixes 1-4 hold. The server tests also ran on Linux (WSL), and a Linux database from `main` (V8) was upgraded.
- **Fix, 8.3 short names**: a short name (`SSH~1` for `.ssh`, `CREDEN~1` for `Credentials`) as the name being moved or renamed got past the sensitive list, hidden folders and unexposed rules, so a user with move rights could rename `.ssh` and then read the keys. The same for a copy with links followed, a deleted link and an upload that overwrites. Those paths now use the real names.
- **Fix, large images**: reading an image's size could copy the whole file into memory (a TIFF keeps its header at the end), enough to stop the server. Linux too.
- **Fix, OneDrive**: the size and duration of online-only files were read, which downloads them. They have none now, like no preview.
- **Fix, tests**: the server tests wrote a setup code into the real data folder. They use `server/build/test-data-folder` now.
- **Fix, folder download**: `CONIN$`, `CONOUT$` and `NUL .txt` are renamed on Windows clients too. Two names that clean up to one local name (`a:b` and `a?b`, or `A.txt` and `a.txt` on Windows) no longer write one file: the second gets ` (1)`.
- **Fix, playlists (Linux too)**: an HLS playlist made FFmpeg read any local video it listed, so a user could see a frame of a video they can't open. FFmpeg now only opens video containers and single images (`format_whitelist`), not playlists or numbered image sequences.
- **Fix, upload info files (Linux too)**: tus reads `uploads/<id>/info` with Java deserialization, and users can write there when the upload folder is in an exposed folder (or `/tmp/filemat` was made by another user). A JVM-wide filter now refuses every class but an upload's info.
- **Fix, case-sensitive folders**: Filemat's move and copy recreated folders without the flag, so `a.txt` and `A.txt` collided (the move was left split, the copy dropped one). New folders take the flag of their source.
- **Fix, links to network folders**: a symbolic link to `\\server\share` was followed, so listing its folder sent the account's credentials there. Link targets are read first, and such links are never followed.
- **Fix, smaller**: share containment compares case in case-sensitive folders; the drive list `/` has no file key.
- **Open**: stale case keys fail open until the next start (V6, needs someone who can toggle the flag on the server).
- **Tests**: server 812, 0 failed: Windows 40 skipped, with the bundled `ffmpeg.exe` on `PATH`; Linux (WSL) 146 skipped. Web 57.

## 2026-10-25: Fixes 4, Filemat's own files

- **Fix, backups**: the database backup before a migration was readable by the group on Linux (`0644`) and never removed. It is now private like the database, and only the three newest are kept.
- **Fix, data folder rights**: the data folder was made private only when Filemat created it. Now on every start, so an existing `FM_DATA_DIR` folder (say under `C:\Users\Public`) becomes private too, with what is already in it. On Windows the folder no longer picks up later changes of its parent folder.
- **Fix, Filemat's folders**: the upload and thumbnail folders can't be a drive, the data folder or a folder that contains it. A stored upload folder `D:/` no longer turns into the drive-relative `D:`.
- **Fix, changing the upload folder**: it failed for the default folder, because the move ran as a user move. Uploads in progress now move along (same drive), so they can resume.
- **Web**: no more `uploads\uploads` on the setup page; folder fields show Windows paths with backslashes.
- **Tests**: server 798, 0 failed (39 skipped); web 54.

## 2026-10-25: Fixes 3, when Windows calls fail

- **Fix, fail closed**: when JNA couldn't load (AppLocker or WDAC blocking its DLL in `%TEMP%`), every Windows check passed: network drives were served, the data folder check passed, and file IDs and the admin warning were silently off. Now a drive whose type can't be read is treated as a network drive, and Filemat stops at startup with a message that says why and what to allow.
- **DLL folder**: JNA unpacks its DLL to `<data>\native` like SQLite, not `%TEMP%`.
- **Admin warning**: if Filemat can't check whether it runs as an administrator, it says so.
- **Tests**: server 788, 0 failed (38 skipped), including a run in a JVM where JNA can't load.

## 2026-10-25: Fixes 2, drive list and search

- **Fix, drive list walks**: "Calculate folder size" and search on the drive list walked every drive, for any signed-in user. Both are refused there now; open a drive first. The web hides them, and no longer shows 1970 dates and a summed size for the drive list.
- **Fix, search without signing in**: search worked without a session and walked the folder before checking access. It now needs a signed-in user (or a share link), checks the folder first, and is rate-limited per user.
- **Tests**: server 784, 0 failed (38 skipped); web 54.

## 2026-10-25: Fixes 1, uploads and Filemat's own files

- **Fix, folder uploads and junctions**: a folder upload could write through a junction, even with links off, into folders that aren't exposed and into the data folder (every profile has `Local Settings`, which leads to it). Every folder an upload writes into is now resolved and checked where it really is. With links off, a link is refused.
- **Fix, uploads outside exposed folders**: finishing an upload failed when the upload folder wasn't inside an exposed folder (also the Linux default `/tmp/filemat`). Filemat's own upload and thumbnail folders no longer need to be exposed.
- **Narrower bypass**: Filemat skips the user checks only for files inside its real upload or cache folder, never for the database or anything else in the data folder. Moves into the data folder are refused again. `FM_DATA_DIR` on a junction works.
- **Thumbnail cache**: "delete cache" and "move cache" only touch cache files, not other files in that folder.
- **Logs**: an upload's temp folder that can't be deleted is logged.
- **Tests**: server 778, 0 failed (38 skipped). Tests now use a temp data folder.

## 2026-10-25: Phase 8, first run of the zip

- **Built**: both zips (x64 205 MB, arm64 187 MB). The arm64 Java and FFmpeg are real ARM64 binaries. The x64 zip was run through setup and the main flows in the app's browser pane.
- **Works**: setup, drives, odd names (`a#b`, `50%`, `a&b`, `写真`), thumbnails and video previews with the bundled FFmpeg, edit, rename, copy, zip download, search, read-only and in-use files, the port hint, the log file.
- **Fix, folder names**: Java can't start from a folder with letters Windows' language doesn't cover (Chinese on an English Windows). `filemat.cmd` now says so instead of "could not find java.dll".
- **Fix, uploads**: uploads failed with the Windows defaults, because the upload and thumbnail folders are inside the data folder, which Filemat also kept itself from touching. The thumbnail cache was never written there either. Filemat's own upload and cache files may now be moved and deleted there.
- **Fix, wrong case**: `report.TXT` in the address bar now opens `Report.txt` and shows its real name. Before, the URL kept the wrong case, and fixing that showed the app jumping up to `/files` whenever a load was cancelled.
- **Fix (Linux too)**: the app no longer hangs on its spinner when the browser blocks service workers.
- **Docs**: new [windows-overview.md](windows-overview.md), a short owner write-up of how Windows support works.
- **Not tried yet**: firewall prompt, admin window, Task Scheduler, long paths, junctions, OneDrive, USB drives, upgrade, ARM64.
- **Tests**: server 754, 0 failed (38 skipped); web 53.

## 2026-10-25: Phase 8, ARM64 zip

- **ARM64 zip**: `windowsDist` now builds `filemat-<v>-windows-arm64.zip` next to the x64 one (`windowsDistX64` / `windowsDistArm64` for just one). Same contents, with BtbN's arm64 FFmpeg 7.1.5 and an arm64 Java runtime. No server code changed.
- **Pinned Java**: the runtimes come from Temurin 21.0.12.1, downloaded and checksum-checked like FFmpeg, instead of whatever Temurin 21 Gradle finds. jlink from the x64 JDK builds both, and needs the exact same version for the arm64 parts.
- **Scripts and docs**: `build.ps1 -Windows` copies both zips; README, Windows guide, beta checklist and AGENTS.md mention the arm64 zip.
- **Not done**: built or run. The arm64 zip is untested (no ARM64 PC).

## 2026-10-25: Phase 7, packaging and docs

- **One build command**: `gradlew bootJar` builds the web app too (with the installed npm) and puts it in the JAR. The build scripts no longer copy it, and `build.ps1` takes switches instead of asking.
- **Windows zip**: `gradlew.bat windowsDist -PreleaseVersion=<v>` builds `filemat-<v>-windows-x64.zip`: `filemat.cmd`, the JAR, a trimmed Temurin 21 runtime (jlink), FFmpeg 7.1.5 (BtbN GPL, checksum pinned) and the licenses.
- **filemat.cmd**: uses the bundled FFmpeg, prints UTF-8, passes arguments on (`--server.port=9090`).
- **Log file on Windows**: the console output also goes to `<dataDir>\logs\filemat.log`, rotated at 10 MB, 9 old files kept.
- **Port hint**: when the port can't be opened on Windows, the message explains reserved port ranges and how to change the port.
- **Docs**: new Windows guide and beta checklist; README, configuration (Windows block lists), paths, Docker Desktop and AGENTS.md updated.
- **Not tried yet**: the zip itself and the web build through Gradle.
- **Tests**: server 751, 0 failed (38 skipped).

## 2026-10-25: Phase 6, media

- **Video previews on Windows**: where the bundled FFmpeg doesn't load, Filemat runs an `ffmpeg` executable: `FM_FFMPEG_PATH` if set, otherwise the one on `PATH`. Linux keeps the bundled one.
- **Safe to run**: no shell, the file is always passed as a local file, playlists can't open URLs, 30 s timeout, a few processes at once, and `.cmd`/`.bat` or relative paths are never run.
- **No FFmpeg**: video previews are off, the startup log says why, and Admin → System settings shows a "Video previews" card with the reason (or which FFmpeg is used).
- **Fix**: a video that FFmpeg couldn't open no longer leaks the decoder.
- **Tests**: server 744, 0 failed (38 skipped), including runs of the real ffmpeg.

## 2026-10-25: Phase 5, file operation robustness

- **Files in use**: delete, move, copy, text edit and upload overwrite retry briefly (3 × 100 ms), then answer 409 "File is in use." Folder operations say how many items failed and that some were in use. No rename-then-delete fallback: Windows 10 frees the name at once on NTFS (tested).
- **Read-only files stay read-only**: they can't be deleted, edited or overwritten ("File is read-only."), and a move to another drive is refused before copying. Rename, same-drive move and copy work, and copies stay read-only. A folder delete removes everything else.
- **Case-only rename** (`photo.jpg` to `Photo.jpg`) works on Windows. Before, it was "moved into itself", and Java's own move silently did nothing.
- **Deletes report failures**: `/delete-list` returns what was deleted and why the rest wasn't, and the web shows it. Before, failed deletes were silent.
- **Fixes**: editing a file that another program has locked no longer empties it. A failed move fallback no longer leaves a copy behind. A junction is no longer turned into an empty folder when moved to another drive (the move is refused).
- **Windows UI**: delete dialogs say files don't go to the Recycle Bin.
- **Tests**: server 717, 0 failed (38 skipped); web 53.

## 2026-10-25: Phase 4, security hardening

- **Windows block lists**: sensitive folders (registry hives, credentials, DPAPI keys, browser profiles, SSH and cloud keys, crash dumps, `$Recycle.Bin`, `System Volume Information`, page files) and non-deletable folders (Windows, Program Files, ProgramData, Users and each profile). They are read from environment variables and Known Folders, not hardcoded to `C:`, and match without case. AppData itself is not blocked.
- **Roots**: `/` and drive roots can never be deleted, even when listed in `FM_FORCE_DELETABLE_FOLDERS`.
- **Hidden by default on Windows**: `$Recycle.Bin`, `System Volume Information`, `pagefile.sys`, `hiberfil.sys` and `swapfile.sys` at each drive root.
- **Links**: junctions and mount points are links like symlinks. Search and folder size no longer walk into them, and listings show them as folder links. OneDrive placeholders and deduplicated files are ordinary files: they download, but get no preview.
- **Drives**: network drives are never opened; `subst` drives are served as the folder they point to.
- **Fix**: an empty entry in a folder list variable no longer turns into `/`, and Windows patterns with `*` no longer fail at startup.
- **Tests**: block lists, reparse tags, drive types, the §10.2 path tricks and the default hidden files. Server 684, 0 failed (37 skipped: symlinks need Developer Mode, no OneDrive placeholder here).

## 2026-10-25: one migration

- **Database**: the `file_key` migration (V10) is merged into V9, so the Windows work adds one migration, V9 (`path_key` and `file_key`). Neither was released.

## 2026-10-25: Phase 3 follow-ups, path-only warning and Docker mounts

- **Path-only warning (Q25)**: the permissions panel shows a note when a file's permissions follow its path only (FAT, exFAT, network drives, hard links). The server sends it as `isPathOnly` with the file's permissions.
- **Docker Desktop and WSL (Q26)**: at startup the server warns for each exposed folder on a mount shared from a Windows or macOS host, where inode numbers may change after a restart.
- **Tests**: mount parsing in `FileIdentityTest`, `isPathOnly` in `EntityPermissionServiceTest`. Server 629, 0 failed; svelte-check 0 errors.

## 2026-10-25: Phase 3, file identity

- **File keys**: entities follow their file by a key instead of the inode. Linux: device + inode (fixes collisions between two mounted filesystems). Windows: volume serial + NTFS/ReFS file ID, read with one native call.
- **Windows permissions now follow files** moved or renamed outside Filemat on NTFS and ReFS. FAT, exFAT and network drives stay path-only.
- **Database**: V9 also adds `files.file_key` and fills it for every entity whose file exists. The old `inode` column is no longer used. Every migration on an existing database is preceded by an automatic backup (`filemat-server.db.v<version>-<date>.bak`).
- **Hard links**: the first path keeps the key; other paths follow their path only.
- **Moves to another volume** made through Filemat re-read the key, so permissions stay with the file.
- **Tests**: `FileIdentityTest`, `FileKeyBackfillTest`, hard-link, missing-key and re-key cases in the repair and move tests. Server 624, 0 failed.

## 2026-10-25: Phase 2c, path keys and case-sensitive folders

- **Path keys**: every stored or compared path has a key. On Linux it is the path. On Windows names are upper-cased unless their folder is case-sensitive (`fsutil file setCaseSensitiveInfo`), so `a.txt` and `A.txt` are one file in a normal folder and two files in a case-sensitive one.
- **Database**: V9 adds `files.path_key`. Linux copies `path`. Windows computes keys in the migration and again at every start.
- **Moved onto keys**: entity lookups, prefix queries, the entity cache, the permission tree, exposed and hidden folder rules, file locks.
- **Renames**: a case-only rename is allowed, and a case-only rename done outside Filemat keeps the file's permissions (the stored case is updated).
- **Listings** say whether the folder is case-sensitive; uploads pick a free name by that.
- **Tests**: `PathKeyTest` (real case-sensitive folders via `fsutil`), `FileLockServiceTest`, `PathKeyRebuildTest`, a case-only rename in `EntityServiceMoveTest`, a key case in `VisibilityTrieTest`. Server 605, 0 failed; web 53.
- **Docs**: `database.md` has the new column, the columns earlier migrations added, and a Migrations section (V2-V9, rules for new migrations).
- Next: check Phase 2 in a browser on Windows, then Phase 3 (`file_key`).

## 2026-10-24: Phase 2b, Windows paths in the web app

- **`path.ts`** holds all path logic for server paths (`/a/b`, `C:/a/b`, share paths `/sub`). On Windows the drive is the first segment, the parent of `C:/` is the drive list `/`, and comparisons ignore case.
- **Call sites moved**: page path from the URL, breadcrumbs, parent navigation, upload and new-folder targets, move, copy and rename, the folder picker, the selection scope. The old `codeUtil` helpers delegate to `path.ts`.
- **Display**: breadcrumb "copy path" and the path dialogs show `C:\Users\x`. The exposed-files input no longer forces a leading `/` on Windows.
- **Wrong case in a URL** opens the file and rewrites the URL to the real case.
- **Drive list**: every signed-in user can open `/` and sees every drive; opening a drive is checked as usual (server, Q15/Q61).
- **Tests**: web 53 (new `path.test.ts`, `getUniqueFilename`), server 582, 0 failed.
- Next: 2c, `path_key` with per-folder case sensitivity.

## 2026-10-24: Phase 2a, Windows paths on the server

- **API paths on Windows** are `C:/Users/x` and `/` is a virtual folder listing the local drives. `WindowsPathPolicy` parses both separators and rejects drive-relative paths, alternate data streams, trailing dots or spaces, reserved device names and control characters (400).
- **One choke point**: `FilePath.of` parses through `PathPolicy` and `FilePath.pathString` is the API string. The database, visibility and permission tries, responses and stored settings all use `C:/...` with no per-site changes.
- **Share paths** (`/sub/file`) stay root-relative. `resolvePath` rejects them outside a share, so nothing resolves against the current drive.
- **Wrong case** in a path now resolves to the canonical case on Windows instead of "not found".
- **The drive list** is read-only: it can't be edited, deleted, moved, shared or zipped. Search and folder size walk every drive.
- **Fixes**: entity move matched children on the native string, search and share listings sent native paths, the auth code file was not hidden on Windows, a drive root's share name was "null".
- **Tests**: 581, 0 failed. New: `PathPolicyWindowsTest`, `ResolvePathTest`, drive-list cases. `EntityServiceMoveTest` runs on Windows now.
- Next: frontend `path.ts`, then `path_key` with per-directory case sensitivity.

## 2026-10-24: Bug fixes and unit tests for the Windows work

**Bugs fixed**
- The FFmpeg load warning is now logged when the app is ready, not in the `ThumbnailService` constructor. On a first start the `log` table did not exist yet.
- `PathPolicy.isRoot` checks the path's structure instead of comparing to `"/"`. On Windows the root parses to `\`, so the root guards (delete, move, copy, thumbnail folder) never matched.
- `WindowsNative.accountNameOfSid` returns null for an unknown SID. JNA returned the name `NONE_MAPPED`.
- `VisibilityTrie.getVisibility` marked an inherited rule as explicit when the path had no node. Nothing reads `isExplicit` yet.

**Tests**
- **Server**: 550 tests, 0 failed, 35 skipped (Linux-only on this Windows machine). New tests cover:
  - Filename rules, ACLs, the JNA calls, the database location check, `Platform`, and `Props` data folder resolution.
  - The env list separator, the invalid-path 400, `VisibilityTrie`, and `FilePath`.
  - Folder upload name checks and `EntityService.move` re-pathing.
  - The path prefix SQL (the real `@Query` strings on in-memory SQLite) and the FFmpeg log timing.
  - Extended: root checks, state endpoint platform and paths, `getMetadata`, permission tree moves.
- **Web**: Vitest added (`npm test`, 37 tests). Covers:
  - Page URLs and path helpers.
  - `Validator.filename`, with the same cases as the server.
  - `toLocalFilename` and `fetchAppState`.
- `Props.resolveDataFolder` takes env, OS and home as parameters so it can be tested. `posixFilenameRules` is exported.

## 2026-10-24: Phase 1 verification on Windows

- **Build**: `./gradlew build` passes on JDK 21: 443 tests, 0 failed, 23 skipped (POSIX-only). `npm run check`: 0 errors.
  - Gradle 8.11.1 itself does not run on JDK 25. Set `JAVA_HOME` to JDK 21, or upgrade the wrapper.
- **Fixes**:
  - `FileController.kt:375` used `path.fileName` on a `FilePath`, which did not compile. It now uses `path.filename`.
  - `FileSecurityServiceTest` hard-coded `/var/lib/filemat`. It now uses `Props.dataFolderPath`.
- **Checked on Windows**:
  - The data folder is `%LOCALAPPDATA%\Filemat`.
  - Its ACL gives full control to the user, SYSTEM and Administrators only.
  - The setup state sends the Windows paths.
  - No elevated warning in a normal console.
  - A UNC `FM_DATA_DIR` exits with the network-path message.
- **Not checked**: the admin console warning, a mapped-drive `FM_DATA_DIR`, and starting on Linux.
- **Found**: FFmpeg doesn't load on Windows (Phase 6). Its warning is logged before the `log` table exists, so the first start prints a failed DB log insert.

## 2026-10-24: Phase 1b, `PathPolicy` and filename rules (not built or tested yet)

**Server**
- **New `config/platform/PathPolicy.kt`**: interface plus `PosixPathPolicy`. Call sites use the companion (`PathPolicy.parse(...)`), which picks the OS implementation. Only POSIX exists; Windows comes in Phase 2.
  - `parse`, `normalize`, `toApiString`, `isRoot`, `segments`, `join`, `resolve`, `validateFileName`.
  - `FilenameRules`: invalid characters, reserved names, trailing dot/space, max length and its unit. POSIX: no `/` or NUL, not `.` or `..`, at most 255 UTF-8 bytes.
- **Wired in, same behaviour on Linux**:
  - `getNormalizedPath` delegates to `PathPolicy`.
  - Root guards in copy, delete, move and the thumbnail cache setting use `isRoot`.
  - `VisibilityTrie` and `EntityPermissionTree` split and join with `PathPolicy`.
  - `EntityService.move` builds child paths with `resolve` (a move to `/` no longer gives `//name`).
  - Share-relative root uses `PathPolicy.root`.
- **Folder upload**: the OS switch now comes from `Platform`, and each segment goes through `validateFileName`. On Linux this only adds the 255-byte check, which the filesystem enforced anyway.
- **State endpoint**: `app.platform.filenameRules`.
- **Test**: `PathPolicyPosixTest`.

**Web**
- `appState.platform.filenameRules` (Linux rules until the state loads).
- `Validator.filename` mirrors the server check. New folder, new file and rename show a toast and stop on a bad name.

**Next**: verify Phase 1 (gradle build/test, `npm run check`, Linux and Windows checks), then Phase 2 (Windows `PathPolicy`, `C:/` wire paths, drive-list root, `path_key`).

## 2026-10-24: Phase 1a, startup on Windows (not built or tested yet)

**Server**
- **New `config/platform/`**: `Platform` (OS, path style, separators), `WindowsNative` (JNA: elevation, account names, drive type), `AclPolicy`, `DatabaseLocationPolicy`. New dependency `jna-platform` 5.14.0.
- **`FM_DATA_DIR`**: defaults to `%LOCALAPPDATA%\Filemat` on Windows and `/var/lib/filemat` elsewhere. The DB, `setup-code.txt` and `auth-code.txt` live there. The datasource URL moved from `application.yml` to `main`.
- **Windows defaults**: uploads in `<dataDir>\uploads`, thumbnail cache in `<dataDir>\cache\thumbnails`. Linux defaults are unchanged.
- **Permissions**: `AclPolicy` replaces the POSIX-only calls. Windows gets an owner-only ACL (current user, SYSTEM, Administrators), and a failure only warns. Linux is unchanged and still exits on failure. Writing the setup code file no longer fails on Windows.
- **Startup checks**: exit when the data folder is a UNC path or on a network drive. A loud warning when running elevated or as SYSTEM. SQLite extracts its DLL to `<dataDir>\native`.
- **Smaller**:
  - `FM_NON_SENSITIVE_FOLDERS` and the other folder lists split on `;` on Windows.
  - The upload path setting no longer gets a `/` forced in front on Windows.
  - `getMetadata` reads `BasicFileAttributes`. POSIX attributes failed on Windows.
  - Download and preview filenames use `Path.fileName`.

**Web**
- The app state now has `platform` and `paths`.
  - The setup page shows the real setup-code path and the server's default folders.
  - The sensitive-auth dialog shows the real auth-code path.
  - Paths are only sent before setup or to logged-in users, because they contain the OS username.

**Next**: Phase 1b, `PathPolicy` wiring (no behaviour change).

## 2026-10-24: Backspace on a share page (not built or tested yet)

- Backspace-to-parent on a share page went to `/files/<parent>` and left the share. It now stays on `/share/<id>/<parent>`, using the same `pagePath` rule as the other navigations (`pageLogic.ts handleKeyDown`).

## 2026-10-24: Phase 0, prep (not built or tested yet)

Committed in `d27d29d`.

- **Root `.editorconfig`**: UTF-8, LF, 4 spaces. `*.kts` stays tab-indented and `*.bat`/`*.cmd` use CRLF.
- **Java 21** (Q56): `jvmToolchain(21)` in `build.gradle.kts`, plus the foojay resolver plugin (0.9.0) in `settings.gradle.kts`, so Gradle can download a JDK 21 if none is installed. README says Java 21. The Docker image and the release workflow were already on 21.
- **opencv removed** (Q45): the two `org.bytedeco:opencv` native `runtimeOnly` lines are gone. javacv and the ffmpeg natives are unchanged.
- **Per-segment page URLs** (§9.2, Q13): a new `filePageUrl(path, pagePath)` in `codeUtil.svelte.ts` encodes each segment. It's used by:
  - file, folder and breadcrumb links
  - `navigateToFilePath`
  - Backspace-to-parent
  - the context menu's open and open-parent links
  - the home page redirect and the sidebar home link
  - the "home" highlight
  - Filenames with `#`, `?`, `%`, `&` or `+` no longer break navigation on Linux. The root links to `/files` instead of `/files/`.
- `getZipContentUrl` now encodes the path by default (it had no callers).
- No server change for routes: `%2F` is never produced, so Tomcat's encoded-slash rule doesn't apply.

## 2026-10-24: Plan updated with the owner's answers (docs only)

Every §15 question (Q1–Q65) is answered and folded into `windows-support-plan.md`. Sections where an answer changed the design are marked **[decided Qn]**. The decisions that change the most:

- **Portable zip, no installer, no service.** Unzip anywhere and run `filemat.cmd`. Data defaults to `%LOCALAPPDATA%\Filemat`. Auto-start is documented through Task Scheduler. WinSW, install scripts and the virtual account are dropped (§8).
- **Target:** Windows 10 x64, tested by hand on the owner's PC. No CI, Vitest or Playwright. Always beta, unsigned.
- **Java 17 → 21** only. No Kotlin or Spring upgrade. JNA for native calls.
- **Media:** javacv stays on Linux. An external `ffmpeg` (`FM_FFMPEG_PATH`, then `PATH`) is the fallback, and the Windows zip bundles a pinned BtbN GPL build. One JAR. opencv is removed.
- **Case-sensitive folders are supported** (§2.3). `path_key` folds a segment only when its parent folder is case-insensitive, read natively per directory and cached. Server comparisons move off `WindowsPath` equality.
- **Read-only files stay read-only** (§6.2). Delete and overwrite fail with a clear error, and Filemat never clears `dos:readonly`.
- **Paths:** `C:/` on the wire and `C:\` on display. `/` lists all drives to every user, and exposure and permissions work on drive roots. Routes are encoded per segment.
- **Security:** only parts of AppData are blocked (credentials, DPAPI, browser profiles). UNC is out. Mapped drives are rejected and `subst` drives resolved. Junctions follow `FM_FOLLOW_SYMBOLIC_LINKS` (default off).
- **Other:** Windows hidden and system files are shown, sorting is unchanged, no `.gitattributes` renormalize, and Linux defaults are unchanged.

## 2026-10-24: Decision-free quick fixes (not built or tested yet)

Bug fixes and hardening that don't depend on any §15 question. Most of them fix Linux bugs too. Committed in `1dd2e05` on `windows-support`, together with unit tests.

**Server**
- **Exact path-prefix SQL.** `EntityRepository.getAllByPathPrefix` and `SavedFileRepository.updatePath` used `LIKE CONCAT(:prefix, '/%')`.
  - `_` and `%` in folder names acted as wildcards, so moving `/a_b` could also re-path `/aXb/...`.
  - SQLite `LIKE` ignores ASCII case, so `/Foo` also matched `/foo/...`.
  - Both now use `SUBSTRING(path, 1, LENGTH(:prefix) + 1) = CONCAT(:prefix, '/')`.
- **`SavedFileRepository.exists`** used `&&`, which is invalid in SQLite. It now uses `AND`. This was only reached when the in-memory saved-file map is off.
- **Junction safety.** A new `isJunctionLike(path)` in `FileUtils.kt` returns true when an entry is a directory and `isOther` with `NOFOLLOW_LINKS`, which is how Java reports Windows junctions and mount points. Delete, move and copy now treat these as leaf entries instead of recursing into them.
  - Before this, deleting a folder that contained a junction deleted the junction target's contents.
  - On Linux nothing changes, because directories are never `isOther` there.
- **FFmpeg can't kill startup.** A new `FfmpegSupport` object loads the natives once and catches any `Throwable`.
  - `ThumbnailService` logs a warning at startup when FFmpeg fails to load.
  - In that case video previews and the FFmpeg image fallback return their normal error responses, and `FileContentMetadataService` skips the FFmpeg probe.
- **`InvalidPathException` → 400.** This is a new handler in `ControllerAdvice`. Paths with characters that are invalid on the OS returned 500 before.
- **Upload service failures are visible.** `TusService.initializeTusService()` now logs the error and the upload folder. `Initialization` prints a line when the service fails to start. Before, the failure was swallowed and uploads quietly stopped working.

**Web**
- **Delete → navigate to parent** (`files.ts`): the prefix checks now compare whole segments (`===` or `isChildOf`). Before, deleting `/foo` while viewing `/foobar/x` navigated away from it.
- **Blank file at root** (`pageLogic.ts handleNewFile`): it now uses `resolvePath`, so it no longer sends `//name`.
- **Folder download filenames** (`folderDownload.ts toLocalFilename`): on Windows clients only, the local name is cleaned up.
  - `<>:"/\|?*`, control characters and trailing dots or spaces become `_`.
  - Reserved names (`CON`, `NUL`, `COM1`, ...) get a `_` prefix.
  - The remote path doesn't change, and other operating systems aren't affected.

**Not done / caveats**: see "Known gaps" in the plan's progress tracker. Builds and tests haven't been run yet.

### Tests added for the above (not run yet)
- `JunctionLikeTest` covers `isJunctionLike` (directory, file, missing path, symlinks, and a real NTFS junction on Windows).
- `FilesystemDeletionServiceTest` (new) covers nested delete and a link or junction inside the folder, which must leave the link target intact.
- `FilesystemMoveServiceTest` and `FilesystemCopyServiceTest` each gained junction cases.
- `TestLinks.kt` has a new `junctionOrSkip` helper. Junction tests skip on Linux, and the symlink-based delete test runs on both.
- Still to do: repository SQL tests (need a DB test setup) and Vitest for `toLocalFilename`.

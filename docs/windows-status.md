# Windows support: status and what's left

**Start here.** This file is the one list of what is still to do on the `windows-support` branch, and the order to do it in. When an item is done, delete it here and add a line to [windows-support-changelog.md](windows-support-changelog.md). Nothing open should live anywhere else.

The other docs, and what each is for:

| Doc | What it is |
| --- | --- |
| [windows-test-plan.md](windows-test-plan.md) | Steps and expected results for every manual check, and the record of the run (its Status table). Has the release-time checks. |
| [windows-branch-guide.md](windows-branch-guide.md) | Every change of the branch explained, with the Windows concepts behind it. Read this first. |
| [windows-overview.md](windows-overview.md) | How the Windows support works, for the maintainer. |
| [windows.md](windows.md) | User docs. |
| [windows-support-plan.md](windows-support-plan.md) | Design, decisions (§15) and the progress tracker: the history of every fix. Not a to-do list. |
| [windows-support-changelog.md](windows-support-changelog.md) | What changed, newest first. |
| [windows-code-review.md](windows-code-review.md) | The full-branch code review of 2026-10-30: findings with fixes, and the clean-ups made. |

## Where it stands (2026-10-31, after fixes 27)

- **Code:** every phase, every verification pass, fixes 1-27 and the full-branch code review (all its medium and low findings fixed) are done and committed. No PR yet.
- **History:** on 2026-10-31 the 60 commits were squashed into 4 (build, server with tests, web, docs). Each builds on its own, and the final tree is the same as before. `origin/windows-support` still has the old history until it is force-pushed, and the local branch `windows-support-backup` keeps it. Commit hashes in older entries of the changelog, plan and test plan refer to that history.
- **Automated tests:** server 879, 0 failed (50 skipped) on Windows 10 x64, and 879, 0 failed on Linux (WSL2 Ubuntu, Java 21; the Windows-only tests skipped). Web 77, svelte-check 0 errors. This Windows account can't create symbolic links, so those tests were skipped on Windows; they run on Linux, except the two in the code review section below.
- **Manual tests:** every check an agent can run has run: 40 PASS, 7 PART, 3 SKIP, 0 FAIL. W45 ran in full on 2026-10-30 (the zip's own runtime: TOTP sign-in and zip download pass), and W37 passes in the page since fixes 25. What's left needs a person (below).
- **Nothing blocks a release** except the two blocking checks for a person, W35 and W38.

## To finish, in order

### 1. Checks for a person

Steps are in the test plan under each ID. Record the result in its Status table.

Blocking (must pass before a release):

- [ ] **W38** Network shares: make a share (admin), check that Filemat refuses the UNC path and the mapped drive, also as `FM_DATA_DIR`.
- [ ] **W35** OneDrive: a folder with online-only files. Blocking if your users have OneDrive.

Not blocking (a failure goes in the release notes as a known issue):

- [ ] **W2** Download the zip in a browser, Unblock, SmartScreen.
- [ ] **W3** Start by double-clicking `filemat.cmd` (the rest of W3 passed).
- [ ] **W10** Start from an Administrator window: the warning shows.
- [ ] **W15** Auto-start with Task Scheduler.
- [ ] **W5, default folder:** rename `%LOCALAPPDATA%\Filemat` away, start, check it's created, put yours back. (W5 passed on a new `FM_DATA_DIR`.)
- [ ] **W14** A Windows account with a non-ASCII name (`Jürgen`), default data folder.
- [ ] **W20, W21** Case-sensitive folder: needs admin for `fsutil file setCaseSensitiveInfo`.
- [ ] **W29** Symbolic links: turn on Developer Mode, rerun the fixtures script, run the check.
- [ ] **W31** Delete and move of `C:\Windows` and `C:\Users` through Filemat are refused (the rest passed).
- [ ] **W40** Close the console window during an upload, and end the scheduled task during one (Ctrl+C and a forced kill passed).
- [ ] **W9** Firewall prompt, with a second device.
- [ ] **W36** FAT32 or exFAT drive (`diskpart`, admin).
- [ ] **W39** Controlled folder access.
- [ ] **W43** Docker Desktop.
- [ ] **W44** ARM64 PC. If not run, the release notes say ARM64 is untested.
- [ ] **L8** Linux: a `.heic` thumbnail (`.avif` passed). No `.heic` encoder here (no `ffmpeg` in WSL, and the bundled Windows FFmpeg has no HEIF muxer); needs a real `.heic` file from a phone.
- [ ] **L16** Firefox private window (no service workers).
- [ ] **L17** Folder download from a Linux server to a Windows PC: `a:b`, `CON.txt`, `name.`.
- [ ] **L18** Docker image.
- Can't be run yet: **W42**'s `.bak` part and **L3** need a build with a database migration newer than V9. Run them with the first release that adds one.
- Not looked at by eye, low risk: the video playing in the page (W16, W24, L8) and the path-only note in the details panel (W30).

### 2. Before merging

- [ ] Push `windows-support` and open a PR to `main`.

### 3. Release

Follow the test plan's [Before each release](windows-test-plan.md#before-each-release): build `.\gradlew.bat windowsDist -PreleaseVersion=<v>`, run W1-W15 and every blocking check on the new x64 zip with Defender on, run the server tests on Linux, then write the release notes:

- Beta, unsigned (SmartScreen will warn), ARM64 untested unless W44 ran.
- The known limitations from the test plan's section 9, and any non-blocking check above that failed or wasn't run.
- Upload the two zips by hand (there's no CI).

## Notes for running the build on this PC

- `JAVA_HOME` is JDK 25 here, which Gradle 8.11.1 can't parse ("25.0.2"). Point it at JDK 21 for the build, e.g. `$env:JAVA_HOME = "$env:USERPROFILE\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"`.
- In PowerShell, quote the version: `'-PreleaseVersion=1.2.3'`. Unquoted, it is split at the first dot and Gradle reports "Task '.2.3' not found".
- For the Linux tests, copy the repo into the WSL home (`rsync`, leaving out `build`, `node_modules`, `.gradle`, `.git`) and run `./gradlew test -PskipWeb` there; running it in `/mnt/c` would share `server/build` with the Windows build.

## Known and accepted

Not bugs to fix now. Most are in the test plan's section 9 too.

- A replace that fails because the target can't be deleted (no DELETE right) still says "File is in use": Windows gives the same error as for an open file (fixes 22).
- Large-image thumbnails are read at a lower resolution only when the full image would take a good part of the free heap (Thumbnailator's `conserveMemoryWorkaround`, fixes 19). With a very large `-Xmx`, a huge image can still be decoded in full.
- Saved files follow the path, not the file: a move in Explorer doesn't update them (a move in Filemat does).
- `%LOCALAPPDATA%\Filemat` is only refused as an upload or cache folder when it is the running data folder.

## Open code items (optional, none blocks the release)

### From the code review (2026-10-30)

Details and fixes in [windows-code-review.md](windows-code-review.md). M1-M4 are fixed (fixes 26), L1-L10 and I1 too (fixes 27, 2026-10-31). I2 turned out not to be a bug. I3 and I4 are left: I3 is a small risk (a folder inside the data folder, such as `native`, as the upload or cache folder), and I4 is a refactor.

- [x] **M1** Every start rewrote the ACLs of whatever `FM_DATA_DIR` names. Now only a data folder Filemat creates is restricted; an existing one is left as it is, with a warning when other accounts can access it.
- [x] **M2** A NULL DACL (everyone full access) counted as already private. Now an empty ACL counts as not private.
- [x] **M3** Symbolic links to mapped network drives (`Z:`) were followed when links are on. Now they count as the network, and a resolved path on a network drive is refused.
- [x] **M4** Deleting a symbolic link didn't resolve the folders above it (also on Linux and `main`). Now the folder is resolved first, like move does.
- [ ] Run the two M3 tests in `WindowsPathSecurityTest` (`a symbolic link to a network drive is never followed`, `a path that resolves to a network drive is not found`) on Windows with symbolic links allowed: Developer Mode on, or an administrator console. They need to create a symbolic link, so they were skipped in the 2026-10-31 run, and they don't run on Linux. The other new tests passed on Windows or Linux.
- [x] **L1-L10** A `subst` drive on a mapped drive, root prefixes in SQL, case flags not cached on FAT, start-up with a drive unplugged, drive-root system files on later drives, tus JSON, thumbnail folder stored as typed, half-moved uploads, edit save mixing old and new, upload folder input after save. L9 was fixed in place rather than with a temp file, so the file keeps its ID and ACL; a crash in the middle of a save can still mix old and new.

**From the second verification pass.** Line numbers are from 2026-10-26 and may have moved.

### Small bugs

- [ ] FFmpeg opens the input without share-delete, so deleting a video while its thumbnail is made can give "File is in use" after the retries. Left as it is: FFmpeg has no option for it, and feeding it the file through a pipe (Java opens it with share-delete) breaks seeking for MP4 files with the index at the end. In the test plan's section 9.

### Tests

Looked at in fixes 24 and left as they are:

- **Q7, Q8** are already there: `a real subst drive points to its folder` and `the legacy Application Data junction in a profile is not followed` in `WindowsPathSecurityTest`. Both ran on this PC (the first skips when no letter from P to Y is free or `subst` fails, the second when the profile has no `Application Data` junction).
- **Q14** `PlatformTest` compares with literal separators on purpose: `File.separator` is what `Platform.separator` is made of, so using it would repeat the code under test.
- **Q18** only 5 test files have `D:` or `E:` now, all in strings that never touch a drive (the run passes on a PC with no `D:`); they are other-than-`C:` on purpose.
- **Q19** `ThumbnailServiceCacheTest`: the startup cleanup can only delete cache-named files by age or size, both off in the test, so a late start of it can't change a result. A deterministic fix needs a scope parameter in `ThumbnailService`, which is not worth a production change for a test.

### Clean-up, no behaviour change

Looked at in fixes 24 and left as they are:

- **C20** the two URL encoders differ on purpose: `encodeUrlPathSegment` keeps `:` readable for page URLs (`/files/C:/Users`), `encodeUrlFilePath` encodes it in API query strings. Using one would change the URLs of thumbnail and content requests, and with them the service worker's cache keys.
- **C21** `getUniqueFilename` defaults `ignoreCase` to the server OS. Taking the default away means choosing a value in `folderDownload.ts` (lines ~196 and ~199, which name files on the user's own disk, where the client OS is the right one): that is a behaviour change, not a clean-up.
- **C22** the setup page starts with the Linux paths and swaps in the server's defaults if they are still unchanged. Starting empty would show empty fields when the app state can't be read, so it stays.

- [ ] Big refactors, skipped on purpose: **C11** three data-folder notions (`Props.configuredDataFolderPath`, `Props.dataFolderPath`, `State.app.dataFolderPath`); **C15** long `moveFile`/`moveRecursiveSafe`; **C16** packaging inline in `build.gradle.kts`; **C18** `Platform.isWindows` in 24 main files.

## Later (out of scope for the beta)

Decided in the plan (§15): an MSI installer, a Windows service, code signing, network shares (UNC), the Recycle Bin, moving a database between Linux and Windows, a server-side folder picker (Q21), CI builds, and an external security review.

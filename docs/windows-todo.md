# Windows support: what's left

The one list of open work on the `windows-support` branch. Delete an item when it's done. User docs are in [windows.md](windows.md). The old plan, test plan, changelog, code review and guides were removed on 2026-11-15; they are in git history (`git log -- docs/windows-test-plan.md` and so on) if a detail is ever needed.

Where it stands: all code is done and tested. Server: 921 tests pass on Windows and on Linux (2026-11-20). On Windows, the symlink tests skip without Developer Mode, and the FFmpeg tests skip without `FM_FFMPEG_PATH`. Web: 93. All fixes (F2 to F6, F8, R2, I3, I4) are committed and pushed. Every blocking check passed on the `1.64.0-beta` x64 zip: W29, W35 (after F6) and W38. W5, W21 (after F8) and W47 passed too, W47 except for F7, which is accepted. On 2026-11-21 the zips were rebuilt from `da25628`. The smoke test, W13, W16, W24, W30, W40, W43, L17 and L18 passed on them, and so did parts of W15 and W31. No PR yet. The code review of 2026-11-21 found F9 to F16. All but F14 were fixed the same day and committed in `224443b` (not pushed yet): 932 server tests pass on Windows, web check clean. Not done for them yet: the Linux test run, a new zip and its smoke test (section 4), and a look at the new "Require 2FA" button in a browser. A second review the same evening found F17 to F25, none fixed yet. F17 blocks the release: it leaks files on Windows with the default settings.

The dev PC has ESET as its antivirus, so Microsoft Defender is off there: W39 and a smoke test with Defender need another PC or a VM.

## 1. Bugs to fix

From the code reviews of 2026-11-21. Not in the earlier review (`docs/full-code-review.md`), whose open items stay in `docs/full-code-review-fixes.md`.

Second review (F17 to F25). F17 and F20 were confirmed with a test on Windows, then the test was removed; the others were traced in the code, not run.

- [ ] **F17 A zip reads through junctions when links aren't followed** (High, blocks the release). `FileContentService.zipRecursiveSafe` tells folders apart with `Files.isDirectory(NOFOLLOW_LINKS)` only, without `LinkInspector.isDirectoryLink` like `safeWalk`, folder size, copy, move and delete. Java reports junctions and mount points as folders even then, so the zip goes inside them, and `toRealPath(NOFOLLOW_LINKS)` keeps the junction's path, so every file is checked under the junction, not where it really is. Hidden folders, the sensitive list, the data folder block and narrower permissions at the target don't apply. Test: `allowed\j` a junction to a denied folder, the zip of `allowed` held `allowed/j/secret.txt` with the denied content. Logged-in users only: in a share, `resolveSharedDescendant` refuses the junction. With links followed, the real path is checked, as it should be. Fix: treat a folder junction like a symlink (skip it when links aren't followed, resolve it with `resolvePath` when they are), and add the test.
- [ ] **F18 Renaming a folder loses its own permissions in memory when something inside it has one** (Medium). The folder is moved file by file, children first. The first child with a permission makes the `/b` node in `EntityPermissionTree`, so `movePath("/a", "/b")` for the folder itself finds `/b` and returns false, which `EntityService.updatePermissionPath` ignores. Until a restart, users granted on `/a` can't open `/b`, and a new `/a` is readable by them; after it, the DB puts the grant back on `/b`. Example: alice on `/projects/acme`, bob on `/projects/acme/contracts`, rename `acme`. A common trigger of the open R16, so it should come first there. The Windows one-rename path for case-sensitive folders doesn't have it. Fix: merge the moved node into an existing target node (or re-add the entity's permissions from the DB), with a "tree equals one rebuilt from the DB" test.
- [ ] **F19 A move or rename to a name whose old file was deleted outside Filemat always fails** (Medium). The deleted file's entity keeps its `path_key`, which `idx_files_path_key` keeps unique. `EntityService.move` then fails in `updatePath`, the transaction rolls back, the file is moved back, and the user gets "Failed to rename file." every time. Nothing visits the missing path to orphan the entity. Upload, copy and new folder go through `ensureEntityIndexed`, which repairs it; move doesn't. Only for moved files that have an entity (uploaded, created, shared or with permissions). Example: delete `final.docx` in Explorer, rename `draft.docx` to `final.docx` in Filemat. Fix: before the DB move, orphan entities at the destination keys whose files are gone.
- [ ] **F20 A broken link can't be deleted, moved or renamed** (Low). `FilesystemDeletionService.deleteFile` and `FilesystemMoveService.moveFile` start with `exists()`, which follows links, so a symlink or junction whose target is gone answers "File was not found." although it is listed. Test: a junction to a deleted folder, delete answered not found. Fix: `exists(LinkOption.NOFOLLOW_LINKS)`.
- [ ] **F21 A single-file share downloads as `/`** (Low). `/content` names the file with `path.filename`, and a single-file share's path is `/`, so the header is `filename*=UTF-8''%2F` and the browser saves it without its name or extension. Range answers take the type from `tika.detect("/")` too, so a shared video is `application/octet-stream`. Fix: use `canonicalPath.filename` for both.
- [ ] **F22 Reusing an expired share link name within 5 minutes is a 500** (Low). `getSharesByShareId` already answers not found for an expired share, so the `isExpired()` branch in `FileShareService.createShare` never runs, and the insert hits the `share_id` primary key until the cleanup deletes the old row. Fix: delete the expired row first.
- [ ] **F23 The log file stops for good after one write error** (Low). `RotatingLogFile` sets `failed` and never clears it, so a full disk for a moment or an antivirus lock on open ends `logs\filemat.log`, the only crash record on Windows, until a restart. Fix: retry later, for example on the next rotation check.
- [ ] **F24 Setup puts the setup code in the audit log** (Low). `SetupController.submit` logs `meta("setup-code" to setupCode)`. The code is spent by then, but it is a secret in a permanent row. Also, `/submit` checks the code before it takes a `SETUP` token, so guesses there aren't limited (not exploitable with 26^12 codes, but the wrong order). Fix: drop the meta entry, and consume the rate limit first.
- [ ] **F25 Smaller items** (Low). The new `set-totp-mfa-required` endpoint has no rank check, so it joins R9's list (a `MANAGE_USERS` holder can change whether the super admin must have 2FA). `ExternalFfmpegBackend.firstFrame` asks for the frame at full size (up to 256 MB per run, 4 runs at once, as accepted below); `-vf scale` to the thumbnail size would make it a few MB. The `RateLimiter` comment says `SHARED_FILE_ACCESS` has a burst of 200, the code says 300.
- [ ] **F14 Visibility rules are matched as typed** (Low, decided 2026-11-21: later). `FileVisibilityService.insertPaths` and `FM_HIDDEN_FOLDER_PATHS` store the path unresolved and match it by name against real paths, so a folder hidden through an 8.3 short name (`JOHNSM~1`) or through a link isn't hidden, with no warning. Fix: store the real names (`withRealNames`), or refuse paths that differ from them.
- [ ] **F11 rest:** a logged-in user can still tell a missing path (404) from one that exists but isn't theirs (400 with the reason), here and on the folder page. Answering both alike would hide the reason from the web. Without a login, the file endpoints now answer 401 before looking at the path.
- [ ] **Linux checks for the fixes:** the FIFO zip test (`FileContentServiceTest`) only runs on Linux, so it hasn't run yet.

**Fixed on 2026-11-21 (`224443b`):** F9 a junction is deleted itself, no longer its target's contents, and can also be deleted with links not followed. F10 setup exposes folders in memory only after the commit. F11 file endpoints answer 401 without a login before touching the path, and `/content` checks access before reading the size. F12 copy and folder uploads resolve the typed path before opening it. F13 enabling or disabling 2FA keeps the admin's "required" flag; the user page has a **Require 2FA** / **Stop requiring 2FA** button that doesn't reset the user's 2FA. F15 the log tee writes the file first. F16 copy to a root is a 400, an expired share login on `/content` and thumbnails is a 400, a logged-out thumbnail is a 401, enabling 2FA allows one step of clock drift, and zips skip anything that isn't a regular file.

**F8 fixed on 2026-11-20:** making a new folder case-sensitive doesn't need an administrator but **Delete subfolders and files** on it, which Full control has and Modify doesn't (an administrator always has Full control, which is why elevated runs passed). The refusal now says the account running Filemat needs Full control of the destination folder, and the known limitation in both docs says so.

**F7, accepted for this release** (decided 2026-11-20, in the known limitations below): copying a file or folder the OS account can't read answers `"1 items could not be copied."`, and deleting such a folder answers `"1 items could not be deleted."`, without the permission message and the Windows hint.

F2, F3, F4, F5 and R2 were fixed on 2026-11-16. F6 was fixed on 2026-11-20: online-only OneDrive files were downloaded, because Windows disguises placeholders for Java, so it sees no reparse point. `LinkInspector` now also checks the recall attributes. **Accepted as is:** a start with `FM_DATA_DIR` on a network drive writes `native\<jna>.dll` there before it refuses (harmless, the user's own drive). F3 works: a killed launcher stops Java within about 10 s. **Accepted as is (2026-11-21, W40):** after the console window is closed mid-upload, the server can hold more bytes than its saved offset. The first PATCH after the restart then gets a 500 ("The upload offset does not correspond to the written bytes"), and the server corrects the offset. The web's tus retry (HEAD, then PATCH) carries on from there, and the finished 2 GB file was byte for byte right. Ending the scheduled task didn't show this.

## 2. Reruns after fixes

None open. W29 passed on 2026-11-20, with links followed and not. **When rerunning it, keep the link path off every command line.** Windows checks paths in a new process's arguments: starting `curl.exe` with `C:\FmTest\links\network-link` as an argument connects to the share even with Filemat stopped. Send requests from a browser or with `Invoke-WebRequest`.

## 3. Manual checks for a person

Test files: `utilities\windows-test-fixtures.ps1 -Ffmpeg .\server\build\windows\x64\ffmpeg\ffmpeg.exe` creates `C:\FmTest` (Developer Mode on first for the symlinks). Accounts: `admin` (setup) and `tester` (normal user). Delete fixtures with `cmd /c rmdir /s /q C:\FmTest`.

**Blocking:** none left. W35 (OneDrive) passed on 2026-11-20 after F6, on the API calls the web makes. To repeat it: put a large image, a video, a PDF and a text file in `OneDrive\FmTest-OD`, and run `attrib +U -P` on them (the same as **Free up space**). Expose the folder. Listing, searching and each file's details panel leave them online-only, and thumbnails are refused. Opening the image downloads only that file. In PowerShell, attribute `0x400000` set means a file is still online-only. W38 (network shares) passed on 2026-11-19.

**Not blocking** (a failure or skip goes in the release notes):

- [ ] **W2 Download path.** Download the zip with Edge from a web server or cloud drive (gets the internet mark). Without unblocking, unzip and run: note what SmartScreen shows. Then unblock, unzip, run.
- [ ] **W9 Firewall.** R2 is fixed. First start shows the prompt for `java.exe`; allow Private only. From a phone, `http://<pc-ip>:8080` opens and you can sign in. Restart: no second prompt.
- [ ] **W14** A Windows account with a non-ASCII name (`Jürgen`), default data folder: starts, setup works, an upload finishes. Mostly covered on 2026-11-20: with `LOCALAPPDATA`, `TEMP` and `TMP` under a `Jürgen` folder, it started, created the data folder, set up and finished a tus upload. Left: a real account, whose user profile and home folder have the name too.
- [ ] **W15 Task Scheduler.** Create the task as `windows.md` says, "At log on" and "At startup". After a sign-out or reboot Filemat runs without a window and serves. "Run whether user is logged on or not" needs the account's password typed into Task Scheduler. Passed on 2026-11-21: ending a started task stops Java within 6 s.
- [ ] **W31 rest.** With `C:\` exposed: `C:\Windows` and `C:\Users` can't be deleted or moved. Only in a VM, never on the dev PC. Passed on 2026-11-21: the data folder (`FM_DATA_DIR`) and its database can't be listed or downloaded, and it isn't listed in its parent folder. The default `%LOCALAPPDATA%\Filemat` goes through the same check.
- [ ] **W39 Controlled folder access** (needs a PC with Defender on). Turn it on, expose `Documents`: an upload is refused with a permission error. Allow `jre\bin\java.exe` as `windows.md` says: it works. Turn it off again. On the same PC, smoke-test the zip with Defender's real-time scanning.
- [ ] **W44 ARM64.** On an ARM64 Windows 11 PC the arm64 zip starts and a video thumbnail shows. If not run, release notes say ARM64 is untested.
- [ ] **L8 `.heic`.** A real `.heic` from a phone gets a thumbnail on Linux (`.avif` passed).
- [ ] **L16** Firefox private window (no service workers): the app loads within about 3 s.
- [ ] Not yet possible: **W42**'s `.bak` part and **L3** (backup pruning) need a build with a migration newer than V9. Run with the first release that adds one.
- [ ] Not looked at by eye (low risk): video playing in the page on Linux (L8). W43 passed with Docker Desktop's Hyper-V backend (a Docker restart instead of `wsl --shutdown`); with the WSL 2 backend it's untried.

## 4. Merge and release

- [ ] Open a PR from `windows-support` to `main` (the branch is pushed).
- [ ] Build: `.\gradlew.bat windowsDist '-PreleaseVersion=<v>'` (quote it in PowerShell; JDK 21 for Gradle). Both `1.64.0-beta` zips in `server\build\dist` were built on 2026-11-21 from `da25628`. Build again only if the code changes, then smoke-test the new x64 zip: start, setup and the main flows in a browser. The smoke test passed on the 2026-11-21 zip. The jlink module check passed on 2026-11-19. After any new dependency, rerun it: `jdeps --print-module-deps --ignore-missing-deps --multi-release 21 --class-path "BOOT-INF/lib/*" BOOT-INF/classes` on the unpacked JAR; every module must be in `jreModules` in `server/build.gradle.kts`.
- [ ] Server tests on Linux: passed on 2026-11-20 with the F8 fix. Rerun after any later code change: copy the repo into the WSL home without `build`, `node_modules`, `.gradle` and `.git`, then `./gradlew test -PskipWeb`. Not in `/mnt/c`, because it would share `server/build`.
- [ ] Release notes: beta, unsigned (SmartScreen warns), ARM64 untested unless W44 ran, the known limitations below, and any non-blocking check that failed or wasn't run.
- [ ] Upload both zips by hand (no CI).

### Known limitations (for the release notes)

- Network shares and mapped drives aren't supported.
- Copying a case-sensitive folder, or moving it to another drive, needs the account running Filemat to have Full control of the destination folder (Modify isn't enough to make the new folder case-sensitive). A move on the same drive works.
- FAT32/exFAT drives: permissions follow the path only. A changed drive letter un-exposes its folders.
- Delete is permanent (no Recycle Bin). No service mode or installer.
- A database can't move between Linux and Windows (it stores full paths).
- Saved files follow the path: a move in Explorer doesn't update them (a move in Filemat does).
- Deleting a video while its thumbnail is made can say "File is in use" (FFmpeg opens it without share-delete; no fix without breaking MP4 seeking).
- A replace that fails because the target can't be deleted says "File is in use" (Windows gives the same error).
- Copying a file or folder that the account running Filemat can't read, or deleting such a folder, says only "1 items could not be copied/deleted.", without saying it's a permission problem (F7).
- A folder's case sensitivity changed while Filemat runs can leave a narrower permission inside it unapplied until the next start.
- A junction can't be moved to another drive. Copying a folder skips junctions inside it ("Links to folders were not copied.").
- Search reads names in a folder of OneDrive online-only files (not content).
- External FFmpeg output is capped at 256 MB per run, up to 4 runs at once; when all are busy a preview gets 503 after 2 s.
- A file that reuses a deleted file's key (inode reuse on Linux, rare on NTFS) gets its permissions back (also true on `main`).
- Changing the upload folder moves the `uploads` and `locks` folders inside the old one, even if they are a user's.
- Huge images: thumbnails decode at lower resolution only when the full image would take much of the free heap; a very large `-Xmx` can still decode in full.

## 5. Smaller code items (none blocks the release)

None open (I3 and I4 were done on 2026-11-16).

Decided not to do (2026-11-15), because they would move code around without making it simpler:
- **C11** the three data-folder notions are three real things: the configured path (usable before the folder exists), its real path (after creation), and `State.app.dataFolderPath` (so tests can inject a folder).
- **C15** splitting `moveRecursiveSafe` gives nothing but risk in security-sensitive code.
- **C16** packaging in `build.gradle.kts` (~300 lines total) is readable where it is; a `buildSrc` move is churn.
- **C18** most `Platform.isWindows` checks are one-line branches; interfaces for them would add indirection, not remove it.

## Later (out of scope for the beta)

MSI installer, Windows service, code signing, network shares (UNC), Recycle Bin, moving a database between Linux and Windows, a server-side folder picker, CI builds, an external security review.

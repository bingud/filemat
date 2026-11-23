# Windows support: what's left

The one list of open work on the `windows-support` branch. Delete an item when it's done. User docs are in [windows.md](windows.md). The old plan, test plan, changelog, code review and guides were removed on 2026-11-15; they are in git history (`git log -- docs/windows-test-plan.md` and so on) if a detail is ever needed.

Where it stands: all code is done and tested. Server: 921 tests pass on Windows and on Linux (2026-11-20). On Windows, the symlink tests skip without Developer Mode, and the FFmpeg tests skip without `FM_FFMPEG_PATH`. Web: 93. All fixes (F2 to F6, F8, R2, I3, I4) are committed and pushed. Every blocking check passed on the `1.64.0-beta` x64 zip: W29, W35 (after F6) and W38. W5, W21 (after F8) and W47 passed too, W47 except for F7 (fixed on 2026-11-23, rerun in section 2). On 2026-11-21 the zips were rebuilt from `da25628`. The smoke test, W13, W16, W24, W30, W40, W43, L17 and L18 passed on them, and so did parts of W15 and W31. No PR yet. The code review of 2026-11-21 found F9 to F16. All but F14 were fixed the same day and committed in `224443b` (not pushed yet). A second review the same evening found F17 to F25, all fixed on 2026-11-22 and committed in `f874130` (not pushed yet). A third review on 2026-11-22 found F26 to F33, all fixed the same day, and F34 (tus keeps its files in `.filemat-uploads`) was fixed on 2026-11-23 (F26 to F34 committed in `450d409`, not pushed yet). F7, F14 and a pixel limit for image thumbnails were fixed on 2026-11-23 too. With all of them: 979 server tests pass on Windows (54 skipped, Linux-only) and on Linux in WSL (178 skipped, Windows-only), 2026-11-23. Not done yet: a new zip with its smoke test (section 4), then the reruns in section 2 on that zip.

The dev PC has ESET as its antivirus, so Microsoft Defender is off there: W39 and a smoke test with Defender need another PC or a VM.

## 1. Bugs to fix

From the code reviews of 2026-11-21. Not in the earlier review (`docs/full-code-review.md`), whose open items stay in `docs/full-code-review-fixes.md`.

None open.

**Fixed on 2026-11-23:** F14 an exposed or hidden folder whose path goes through a linked folder or an 8.3 short name (`JOHNSM~1`) never matched, because requests are checked at their real path. Admin → System and setup now refuse such a path and give the real one (`FileVisibilityService.rulePathProblem`). A rule on a link itself is still allowed: it hides the link in its folder. An `FM_HIDDEN_FOLDER_PATHS` entry given that way is hidden at its real path too, and rules saved earlier that never match get a startup warning. F7 copy, move and delete of a file or folder the account running Filemat can't open now say "Missing permission to copy/move/delete file." (last, so the web adds the Windows hint), and the count says "1 item". Thumbnails of images over 24 MP are decoded subsampled to at least 4 times the target size and at most 24 MP, whatever the heap (`ThumbnailService.imageSubsampling`), with the EXIF orientation applied; smaller images go through Thumbnailator as before.

F34 tus keeps its files in `<upload folder>/.filemat-uploads` (`Props.uploadSubfolderName`), no longer in the upload folder itself. tus's cleanup (every 2 h) went through every entry of `<storage>/locks`, took it for an upload id and deleted what was older than 10 s, so it deleted the files in a user's `locks` folder when the upload folder held one. A change of the upload folder also moved a user's `uploads` and `locks` folders. Now only `.filemat-uploads` moves (a rename, or it stays and a warning is logged), and a user's `uploads` and `locks` folders are left alone, in the old and the new folder. tus refuses to start when `.filemat-uploads` is a file or a link, and `State.internalFolderPath(UPLOADS)` means that folder, so finished uploads are only moved and deleted from it. Uploads in progress during the update are lost once (they expire after 48 h anyway); the old `uploads` and `locks` folders stay where they are.

**Fixed on 2026-11-22 (third review, F26 to F33, on the core flows):** F26 deleting or orphaning an entity removes all of its grants from the permission tree, not just the first user and the first role grant, so a new file at the same path doesn't get them. F27 the admin role can't be removed from the last admin. F28 with links followed, copy checks each file at its real path, so a folder link inside a copied link no longer copies hidden or sensitive files (the part of R23 that was still open, like F17 for zips). F29 an orphaned entity found again by its file key gets its permissions back in the tree. F30 a 2FA login is audited and sets the last login date, and a wrong 2FA code is logged with the real IP. F31 the permissions of a blocked path aren't listed. F32 `VisibilityTrie` uses concurrent maps and volatile fields. F33 a range past the end of a file that got shorter closes the file and answers 416.

**Fixed on 2026-11-22 (second review, F17 to F25):** F17 a zip treats junctions and mount points like symlinks: left out when links aren't followed, checked at their real path when they are. F18 a move onto an existing node in `EntityPermissionTree` merges into it, so a folder renamed after its children keeps its own permissions in memory (tested against a tree loaded from the DB). F19 a move orphans a stale entity that still holds the destination key. F20 delete and move find a link whose target is gone. F21 a single-file share is named and typed after the shared file. F22 an expired share's row is deleted before its link name is reused. F23 the log file is opened again 30 s after a write error, with a line saying output is missing. F24 setup no longer logs the setup code, and `/submit` takes a `SETUP` token before checking the code. F25 `set-totp-mfa-required` refuses a target of higher rank, the external FFmpeg scales the frame to the thumbnail size, and the `RateLimiter` comment says 300.

**F11 rest, accepted** (decided 2026-11-22): a logged-in user can tell a missing path (404) from one that exists but isn't theirs (400 with the reason), on the file endpoints and the folder page. The reason is what the web shows. Without a login, the file endpoints answer 401 before looking at the path.

**Fixed on 2026-11-21 (`224443b`):** F9 a junction is deleted itself, no longer its target's contents, and can also be deleted with links not followed. F10 setup exposes folders in memory only after the commit. F11 file endpoints answer 401 without a login before touching the path, and `/content` checks access before reading the size. F12 copy and folder uploads resolve the typed path before opening it. F13 enabling or disabling 2FA keeps the admin's "required" flag; the user page has a **Require 2FA** / **Stop requiring 2FA** button that doesn't reset the user's 2FA. F15 the log tee writes the file first. F16 copy to a root is a 400, an expired share login on `/content` and thumbnails is a 400, a logged-out thumbnail is a 401, enabling 2FA allows one step of clock drift, and zips skip anything that isn't a regular file.

**F8 fixed on 2026-11-20:** making a new folder case-sensitive doesn't need an administrator but **Delete subfolders and files** on it, which Full control has and Modify doesn't (an administrator always has Full control, which is why elevated runs passed). The refusal now says the account running Filemat needs Full control of the destination folder, and the known limitation in both docs says so.

F2, F3, F4, F5 and R2 were fixed on 2026-11-16. F6 was fixed on 2026-11-20: online-only OneDrive files were downloaded, because Windows disguises placeholders for Java, so it sees no reparse point. `LinkInspector` now also checks the recall attributes. **Accepted as is:** a start with `FM_DATA_DIR` on a network drive writes `native\<jna>.dll` there before it refuses (harmless, the user's own drive). F3 works: a killed launcher stops Java within about 10 s. **Accepted as is (2026-11-21, W40):** after the console window is closed mid-upload, the server can hold more bytes than its saved offset. The first PATCH after the restart then gets a 500 ("The upload offset does not correspond to the written bytes"), and the server corrects the offset. The web's tus retry (HEAD, then PATCH) carries on from there, and the finished 2 GB file was byte for byte right. Ending the scheduled task didn't show this.

## 2. Reruns after fixes

On the zip rebuilt with F9 to F34 (the 2026-11-21 zips have none of them):

- [ ] **Uploads after F34.** Start on a copy of a data folder from the 2026-11-21 zip that has an upload left in progress: `.filemat-uploads` is created in the upload folder, the old `uploads` and `locks` folders stay untouched, a new upload finishes, and the old upload in the web starts over or fails with a clear message (not a hang). Then rerun W40: close the console window mid-upload, start again, and the upload resumes and the file is byte for byte right.
- [ ] **Upload folder change.** Put a user's `uploads` and `locks` folder, each with a file, in the upload folder. Pause an upload, change the upload folder (Admin → System) to another folder on the same drive: `.filemat-uploads` moves, the user's folders stay, and the upload resumes. Restart Filemat (tus cleanup runs at start) and wait a minute: the user's files are still there. Change it to a folder on another drive: a warning is logged, and new uploads work.
- [ ] **F7.** Deny the account running Filemat read access to a folder inside an exposed one (`icacls <folder> /deny "%USERNAME%:(OI)(CI)R"`, undo with `/remove:d`). Copying the parent says "1 item could not be copied. Missing permission to copy file." with the Windows hint in the web, and deleting it says the same for delete. This is the part of W47 that failed before.
- [ ] **F14.** In Admin → System, adding a folder through a junction (`C:\Users\<you>\My Documents`-style, or one made with `mklink /J`) or an 8.3 name (`dir /x` shows them) is refused with the real path; the real path, and the junction itself, can be added. Set `FM_HIDDEN_FOLDER_PATHS` to a folder through a junction: the startup log says it is also hidden at its real path, and it is hidden in the web.
- [ ] **Huge image.** A JPEG over 24 MP (for example 10000 × 8000) in an exposed folder gets a thumbnail quickly, upright if it has an EXIF rotation, and Java's memory stays well below the default heap.
- [ ] **In a browser:** the setup page's upload folder step says uploads are kept in `.filemat-uploads`, and the user page's **Require 2FA** / **Stop requiring 2FA** button (F13) sets the flag without resetting the user's 2FA.

W29 passed on 2026-11-20, with links followed and not. **When rerunning it, keep the link path off every command line.** Windows checks paths in a new process's arguments: starting `curl.exe` with `C:\FmTest\links\network-link` as an argument connects to the share even with Filemat stopped. Send requests from a browser or with `Invoke-WebRequest`.

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

- [ ] Push `windows-support` (`224443b` and everything after it are local only), then open a PR from it to `main`.
- [ ] Build: `.\gradlew.bat windowsDist '-PreleaseVersion=<v>'` (quote it in PowerShell; JDK 21 for Gradle). Both `1.64.0-beta` zips in `server\build\dist` were built on 2026-11-21 from `da25628`. Build again only if the code changes, then smoke-test the new x64 zip: start, setup and the main flows in a browser. The smoke test passed on the 2026-11-21 zip. The jlink module check passed on 2026-11-19. After any new dependency, rerun it: `jdeps --print-module-deps --ignore-missing-deps --multi-release 21 --class-path "BOOT-INF/lib/*" BOOT-INF/classes` on the unpacked JAR; every module must be in `jreModules` in `server/build.gradle.kts`.
- [ ] Server tests on Linux: passed on 2026-11-23 with everything up to F34, F7, F14 and the thumbnail pixel limit. Rerun after any later code change: copy the repo into the WSL home without `build`, `node_modules`, `.gradle` and `.git`, then `./gradlew test -PskipWeb`. Not in `/mnt/c`, because it would share `server/build`.
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
- A folder's case sensitivity changed while Filemat runs can leave a narrower permission inside it unapplied until the next start.
- A junction can't be moved to another drive. Copying a folder skips junctions inside it ("Links to folders were not copied.").
- Search reads names in a folder of OneDrive online-only files (not content).
- External FFmpeg output is capped at 256 MB per run, up to 4 runs at once; when all are busy a preview gets 503 after 2 s.
- A file that reuses a deleted file's key (inode reuse on Linux, rare on NTFS) gets its permissions back (also true on `main`).
## 5. Smaller code items (none blocks the release)

None open (I3 and I4 were done on 2026-11-16).

Decided not to do (2026-11-15), because they would move code around without making it simpler:
- **C11** the three data-folder notions are three real things: the configured path (usable before the folder exists), its real path (after creation), and `State.app.dataFolderPath` (so tests can inject a folder).
- **C15** splitting `moveRecursiveSafe` gives nothing but risk in security-sensitive code.
- **C16** packaging in `build.gradle.kts` (~300 lines total) is readable where it is; a `buildSrc` move is churn.
- **C18** most `Platform.isWindows` checks are one-line branches; interfaces for them would add indirection, not remove it.

## Later (out of scope for the beta)

MSI installer, Windows service, code signing, network shares (UNC), Recycle Bin, moving a database between Linux and Windows, a server-side folder picker, CI builds, an external security review.

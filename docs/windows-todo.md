# Windows support: what's left

The one list of open work on the `windows-support` branch. Delete an item when it's done. User docs are in [windows.md](windows.md). The old plan, test plan, changelog, code review and guides were removed on 2026-11-15; they are in git history (`git log -- docs/windows-test-plan.md` and so on) if a detail is ever needed.

Where it stands: all code is done and tested. Server: 921 tests pass on Windows and on Linux (2026-11-20). On Windows, the symlink tests skip without Developer Mode, and the FFmpeg tests skip without `FM_FFMPEG_PATH`. Web: 93. The fixes of 2026-11-16 (F2 to F5, R2, I3, I4) are committed and pushed. The fixes of 2026-11-20 (F6, F8) are committed, not pushed. Every blocking check passed on the `1.64.0-beta` x64 zip: W29, W35 (after F6) and W38. W5, W21 (after F8) and W47 passed too, W47 except for F7, which is accepted. No PR yet, and nothing blocks the release.

## 1. Bugs to fix

None open. **F8 fixed on 2026-11-20:** making a new folder case-sensitive doesn't need an administrator but **Delete subfolders and files** on it, which Full control has and Modify doesn't (an administrator always has Full control, which is why elevated runs passed). The refusal now says the account running Filemat needs Full control of the destination folder, and the known limitation in both docs says so.

**F7, accepted for this release** (decided 2026-11-20, in the known limitations below): copying a file or folder the OS account can't read answers `"1 items could not be copied."`, and deleting such a folder answers `"1 items could not be deleted."`, without the permission message and the Windows hint.

F2, F3, F4, F5 and R2 were fixed on 2026-11-16. F6 was fixed on 2026-11-20: online-only OneDrive files were downloaded, because Windows disguises placeholders for Java, so it sees no reparse point. `LinkInspector` now also checks the recall attributes. **Accepted as is:** a start with `FM_DATA_DIR` on a network drive writes `native\<jna>.dll` there before it refuses (harmless, the user's own drive). F3 works: a killed launcher stops Java within about 10 s.

## 2. Reruns after fixes

None open. W29 passed on 2026-11-20, with links followed and not. **When rerunning it, keep the link path off every command line.** Windows checks paths in a new process's arguments: starting `curl.exe` with `C:\FmTest\links\network-link` as an argument connects to the share even with Filemat stopped. Send requests from a browser or with `Invoke-WebRequest`.

## 3. Manual checks for a person

Test files: `utilities\windows-test-fixtures.ps1 -Ffmpeg .\server\build\windows\x64\ffmpeg\ffmpeg.exe` creates `C:\FmTest` (Developer Mode on first for the symlinks). Accounts: `admin` (setup) and `tester` (normal user). Delete fixtures with `cmd /c rmdir /s /q C:\FmTest`.

**Blocking:** none left. W35 (OneDrive) passed on 2026-11-20 after F6, on the API calls the web makes. To repeat it: put a large image, a video, a PDF and a text file in `OneDrive\FmTest-OD`, and run `attrib +U -P` on them (the same as **Free up space**). Expose the folder. Listing, searching and each file's details panel leave them online-only, and thumbnails are refused. Opening the image downloads only that file. In PowerShell, attribute `0x400000` set means a file is still online-only. W38 (network shares) passed on 2026-11-19.

**Not blocking** (a failure or skip goes in the release notes):

- [ ] **W2 Download path.** Download the zip with Edge from a web server or cloud drive (gets the internet mark). Without unblocking, unzip and run: note what SmartScreen shows. Then unblock, unzip, run.
- [ ] **W9 Firewall.** R2 is fixed. First start shows the prompt for `java.exe`; allow Private only. From a phone, `http://<pc-ip>:8080` opens and you can sign in. Restart: no second prompt.
- [ ] **W14** A Windows account with a non-ASCII name (`Jürgen`), default data folder: starts, setup works, an upload finishes. Mostly covered on 2026-11-20: with `LOCALAPPDATA`, `TEMP` and `TMP` under a `Jürgen` folder, it started, created the data folder, set up and finished a tus upload. Left: a real account, whose user profile and home folder have the name too.
- [ ] **W15 Task Scheduler.** F3 is fixed. Create the task as `windows.md` says, "At log on" and "At startup". After a sign-out or reboot Filemat runs without a window and serves. Ending the task stops it.
- [ ] **W31 rest.** With `C:\` exposed: `C:\Windows` and `C:\Users` can't be deleted or moved. With the default data folder, `%LOCALAPPDATA%\Filemat` can't be opened. (Left on purpose, it's a real PC.)
- [ ] **W39 Controlled folder access.** Turn it on, expose `Documents`: an upload is refused with a permission error. Allow `jre\bin\java.exe` as `windows.md` says: it works. Turn it off again.
- [ ] **W40 rest.** During a 2 GB upload, close the console window, and end the scheduled task (F3 is fixed: Java must stop with it). Restart: the upload resumes. (Ctrl+C and a forced kill passed.)
- [ ] **W43 Docker Desktop.** Run the Linux image with `-v C:\FmTest:/data`, expose `/data`, give `tester` access to `/data/tree/file.txt`. Restart the container, then `wsl --shutdown` and start Docker again: the permission is still there, or the log has the mount warning.
- [ ] **W44 ARM64.** On an ARM64 Windows 11 PC the arm64 zip starts and a video thumbnail shows. If not run, release notes say ARM64 is untested.
- [ ] **L8 `.heic`.** A real `.heic` from a phone gets a thumbnail on Linux (`.avif` passed).
- [ ] **L16** Firefox private window (no service workers): the app loads within about 3 s.
- [ ] **L17 Folder download to Windows.** On Linux: `mkdir clash && cd clash && touch 'a:b' 'a?b' CON.txt 'name.' 'NUL .txt' A.txt a.txt`. From Windows, download `clash` as a folder (not zip) with Chrome or Edge: seven files arrive (`a_b`, `a_b (1)`, `_CON.txt`, `name_`, `_NUL .txt`, `A.txt`, `a (1).txt`), nothing overwritten.
- [ ] **L18 Docker image.** Build and run as `docker-deployment.md` says, with a bind mount: setup, an upload and a video thumbnail work.
- [ ] Not yet possible: **W42**'s `.bak` part and **L3** (backup pruning) need a build with a migration newer than V9. Run with the first release that adds one.
- [ ] Not looked at by eye (low risk): video playing in the page (W16, W24, L8), the path-only note in the details panel (W30, needs a FAT32 or exFAT drive), the FFmpeg card in Settings → System (W13).

## 4. Merge and release

- [ ] Push `windows-support` and open a PR to `main`.
- [ ] Build: `.\gradlew.bat windowsDist '-PreleaseVersion=<v>'` (quote it in PowerShell; JDK 21 for Gradle). Both `1.64.0-beta` zips in `server\build\dist` were built on 2026-11-20 with the F8 fix. Build again only if the code changes.
- [ ] On the final x64 zip, with Defender on, smoke-test by hand: start, setup and the main flows in a browser. The blocking checks passed on the F6 build of 2026-11-20; the F8 build after it only changes a message, and W21 passed on it. The jlink module check passed on 2026-11-19. After any new dependency, rerun it: `jdeps --print-module-deps --ignore-missing-deps --multi-release 21 --class-path "BOOT-INF/lib/*" BOOT-INF/classes` on the unpacked JAR; every module must be in `jreModules` in `server/build.gradle.kts`.
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

# Windows support: what's left

The one list of open work on the `windows-support` branch. Delete an item when it's done. User docs are in [windows.md](windows.md). The old plan, test plan, changelog, code review and guides were removed on 2026-11-15; they are in git history (`git log -- docs/windows-test-plan.md` and so on) if a detail is ever needed.

Where it stands: all code is done and tested (server 879 tests on Windows and Linux, web 77). No PR yet. A release is blocked by F2 and F4 (section 1), and by W38 and W35 (section 3).

## 1. Bugs to fix (found 2026-11-05; approach decided 2026-11-15)

- [ ] **F2 (W21, blocking).** As a normal user, copy and move of a case-sensitive folder fail: Windows needs admin to set the flag on the new folder. The message is a vague "2 items could not be copied." / "Failed to move 2 files.". Nothing is lost; works elevated.
  **Do:** a same-drive move is a plain rename (keeps the flag, no admin). Copy, and a move to another drive, still fail, but say why: "Copying a case-sensitive folder needs Filemat to run as administrator." Add it to the known limitations.
- [ ] **F3 (W15, W40).** Ending the scheduled task kills `cmd.exe` but leaves `java.exe` running (orphaned, still serving; a later start fails on the port).
  **Do:** `filemat.cmd` tells Java its own process (for example an env var with the `cmd.exe` PID, or Java takes `ProcessHandle.current().parent()` when that var is set). On Windows, Filemat shuts down gracefully when that process ends. Also covers closing the console window. A plain `java -jar` start is unaffected.
- [ ] **F4 (W38, blocking).** Exposing `Z:/` (mapped drive) or `//host/share` in settings is accepted (inert, everything stays 404).
  **Do:** refuse network paths in the exposed-files setting with a clear message. **Accepted as is:** a start with `FM_DATA_DIR` on a network drive writes `native\<jna>.dll` there before it refuses (harmless, the user's own drive).
- [ ] **F5 (W47).** A file or folder the Windows account is denied by its ACL gives generic 500s ("Failed to stream file.", "Failed to get contents of this folder.").
  **Do:** read, list, edit, metadata and thumbnails answer "Missing permission to …" (403) like copy/move/delete already do, so the web's Windows hint (`accessDenied.ts`, extend its regex) shows. A zip leaves out files it can't read instead of adding a broken entry.
- [ ] **R2 (from [full-code-review-fixes.md](full-code-review-fixes.md), needed for W9).** Login cookies are always `Secure`, so signing in over plain `http://<pc-ip>:8080` from another device silently fails.
  **Do:** set `Secure` only when the request is HTTPS (including `X-Forwarded-Proto: https` from a proxy).

## 2. Reruns after fixes

- [ ] **W29** (F1 is fixed, not rerun on a build). Elevated `mklink /D C:\FmTest\links\network-link \\192.0.2.1\share` (or the fixtures script with Developer Mode on). Run `for /l %i in (1,0,2) do @netstat -an | findstr 192.0.2.1` while listing `C:\FmTest\links`, once with `FM_FOLLOW_SYMBOLIC_LINKS` unset and once `true`. Expected: the listing loads at once, `network-link` can't be opened, no connection to `192.0.2.1:445`.
- [ ] **M3 tests**: run `a symbolic link to a network drive is never followed` and `a path that resolves to a network drive is not found` in `WindowsPathSecurityTest` with symlinks allowed (Developer Mode, or an elevated console with `--no-daemon`). They skip otherwise and don't run on Linux.

## 3. Manual checks for a person

Test files: `utilities\windows-test-fixtures.ps1 -Ffmpeg .\server\build\windows\x64\ffmpeg\ffmpeg.exe` creates `C:\FmTest` (Developer Mode on first for the symlinks). Accounts: `admin` (setup) and `tester` (normal user). Delete fixtures with `cmd /c rmdir /s /q C:\FmTest`.

**Blocking:**

- [ ] **W38 Network shares.** Mostly passed; finish after F4. Admin PowerShell: `New-SmbShare -Name FmShare -Path C:\FmTest -FullAccess $env:USERNAME`; normal `cmd`: `net use Z: \\localhost\FmShare`. Exposing `Z:\` and `\\localhost\FmShare` must be refused; `FM_DATA_DIR=Z:\fm` must stop without writing anything there. Clean up: `net use Z: /delete`, `Remove-SmbShare FmShare`.
- [ ] **W35 OneDrive** (blocking, decided 2026-11-15). In a OneDrive folder make `FmTest-OD` with a large image, a video, a PDF and a text file; **Free up space** so they're cloud-only. Expose it. Listing, searching and opening each file's details panel leave the cloud icons (no download), no thumbnails show. Opening the image downloads it and shows it.

**Not blocking** (a failure or skip goes in the release notes):

- [ ] **W2 Download path.** Download the zip with Edge from a web server or cloud drive (gets the internet mark). Without unblocking, unzip and run: note what SmartScreen shows. Then unblock, unzip, run.
- [ ] **W5 default folder.** Rename `%LOCALAPPDATA%\Filemat` away, start: it's created with `setup-code.txt`, setup works. Put yours back.
- [ ] **W9 Firewall.** After R2. First start shows the prompt for `java.exe`; allow Private only. From a phone, `http://<pc-ip>:8080` opens and you can sign in. Restart: no second prompt.
- [ ] **W14** A Windows account with a non-ASCII name (`Jürgen`), default data folder: starts, setup works, an upload finishes.
- [ ] **W15 Task Scheduler.** After F3. Create the task as `windows.md` says, "At log on" and "At startup". After a sign-out or reboot Filemat runs without a window and serves. Ending the task stops it.
- [ ] **W21** Rerun after F2: copy `C:\FmTest\cs` to `cs-copy`, move it to `tree\cs-moved`; both keep `a.txt` "lower" and `A.txt` "UPPER", and `fsutil file queryCaseSensitiveInfo` says enabled.
- [ ] **W31 rest.** With `C:\` exposed: `C:\Windows` and `C:\Users` can't be deleted or moved. With the default data folder, `%LOCALAPPDATA%\Filemat` can't be opened. (Left on purpose, it's a real PC.)
- [ ] **W39 Controlled folder access.** Turn it on, expose `Documents`: an upload is refused with a permission error. Allow `jre\bin\java.exe` as `windows.md` says: it works. Turn it off again.
- [ ] **W40 rest.** During a 2 GB upload, close the console window, and (after F3) end the scheduled task. Restart: the upload resumes. (Ctrl+C and a forced kill passed.)
- [ ] **W43 Docker Desktop.** Run the Linux image with `-v C:\FmTest:/data`, expose `/data`, give `tester` access to `/data/tree/file.txt`. Restart the container, then `wsl --shutdown` and start Docker again: the permission is still there, or the log has the mount warning.
- [ ] **W44 ARM64.** On an ARM64 Windows 11 PC the arm64 zip starts and a video thumbnail shows. If not run, release notes say ARM64 is untested.
- [ ] **W47** Rerun after F5: `icacls <file> /deny <you>:(R)` on a file and a folder; read, list, edit, copy, delete give a clear permission message. Remove the deny after.
- [ ] **L8 `.heic`.** A real `.heic` from a phone gets a thumbnail on Linux (`.avif` passed).
- [ ] **L16** Firefox private window (no service workers): the app loads within about 3 s.
- [ ] **L17 Folder download to Windows.** On Linux: `mkdir clash && cd clash && touch 'a:b' 'a?b' CON.txt 'name.' 'NUL .txt' A.txt a.txt`. From Windows, download `clash` as a folder (not zip) with Chrome or Edge: seven files arrive (`a_b`, `a_b (1)`, `_CON.txt`, `name_`, `_NUL .txt`, `A.txt`, `a (1).txt`), nothing overwritten.
- [ ] **L18 Docker image.** Build and run as `docker-deployment.md` says, with a bind mount: setup, an upload and a video thumbnail work.
- [ ] Not yet possible: **W42**'s `.bak` part and **L3** (backup pruning) need a build with a migration newer than V9. Run with the first release that adds one.
- [ ] Not looked at by eye (low risk): video playing in the page (W16, W24, L8), the path-only note in the details panel (W30), the FFmpeg card in Settings → System (W13).

## 4. Merge and release

- [ ] Force-push `windows-support` (history was squashed to 4 commits on 2026-10-31; `windows-support-backup` has the old one), open a PR to `main`.
- [ ] Build: `.\gradlew.bat windowsDist '-PreleaseVersion=<v>'` (quote it in PowerShell; JDK 21 for Gradle).
- [ ] On the new x64 zip, Defender on: smoke-test start, setup, the main flows, and every blocking check above. After any new dependency, rerun the jlink module check: `jdeps --print-module-deps --ignore-missing-deps --multi-release 21 --class-path "BOOT-INF/lib/*" BOOT-INF/classes` on the unpacked JAR; every module must be in `jreModules` in `server/build.gradle.kts`.
- [ ] Run the server tests on Linux (copy the repo into the WSL home without `build`, `node_modules`, `.gradle`, `.git`; `./gradlew test -PskipWeb`). Not in `/mnt/c`, it would share `server/build`.
- [ ] Release notes: beta, unsigned (SmartScreen warns), ARM64 untested unless W44 ran, the known limitations below, and any non-blocking check that failed or wasn't run.
- [ ] Upload both zips by hand (no CI).

### Known limitations (for the release notes)

- Network shares and mapped drives aren't supported.
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
- Changing the upload folder moves the `uploads` and `locks` folders inside the old one, even if they are a user's.
- Huge images: thumbnails decode at lower resolution only when the full image would take much of the free heap; a very large `-Xmx` can still decode in full.

## 5. Smaller code items (none blocks the release)

- [ ] **I3** `realInternalFolder` accepts folders inside the data folder (`<data>\native`, `<data>\logs`) as upload or cache folder. **Do:** refuse any folder inside the data folder except the default `uploads` and `cache\thumbnails`, with a test.
- [ ] **I4** Fold `appendFilename`, `resolvePath`, `filenameFromPath`, `isChildOf`, `filePageUrl`, `encodeUrlPathSegment` from `codeUtil.svelte.ts` into `path.ts`, and update the ~16 callers (`npm run check`, `npm test`).

Decided not to do (2026-11-15), because they would move code around without making it simpler:
- **C11** the three data-folder notions are three real things: the configured path (usable before the folder exists), its real path (after creation), and `State.app.dataFolderPath` (so tests can inject a folder).
- **C15** splitting `moveRecursiveSafe` gives nothing but risk in security-sensitive code.
- **C16** packaging in `build.gradle.kts` (~300 lines total) is readable where it is; a `buildSrc` move is churn.
- **C18** most `Platform.isWindows` checks are one-line branches; interfaces for them would add indirection, not remove it.

## Later (out of scope for the beta)

MSI installer, Windows service, code signing, network shares (UNC), Recycle Bin, moving a database between Linux and Windows, a server-side folder picker, CI builds, an external security review.

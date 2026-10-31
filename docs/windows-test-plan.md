# Manual test plan: Windows support and the Linux changes

A step-by-step run of everything the `windows-support` branch changed that the automated tests can't reach. Each check gives its steps and what must happen. It is run in full once for this branch, and in part before each release ([below](#before-each-release)).

- **W** checks run on Windows, **L** on Linux.
- In brackets: the plan phase or verification finding the check covers (`V2` and the other third-pass findings are summarised in the plan's progress tracker), so a failure points at the code.
- Record a failure with the check ID, the console output and `logs\filemat.log` (Windows).
- Checks marked **(blocking)** must pass before the release. The rest can ship as known issues in the release notes.

## Progress

This file is also the record of the run, so it can go on from another PC: pull the branch, look at the table, take the next open chunk, and commit the updated table when you stop. Which checks are still to do, and what comes after them, is in [windows-status.md](windows-status.md).

- **Status:** empty (not run), `PASS`, `FAIL` (with an entry under [Failures found](#failures-found)), `PART` (only some steps could run, say which in Notes) or `SKIP` (with the reason).
- **Who:** `agent` checks need nothing an agent can't do on its own: no admin rights, no system settings, no accounts, no second device. Run them yourself, or hand a chunk to an agent with the prompt in [section 10](#10-running-a-chunk-with-an-agent). `you` checks need a person: admin rights, settings, OneDrive, a second device, Docker Desktop or ARM64.
- **Chunk:** the groups of `agent` checks that make one sensible agent run, A to E. Run A first: the later chunks rely on its build and accounts.

### Before each release

On Windows 10 22H2 x64 with the new `filemat-<version>-windows-x64.zip`, Defender real-time scanning on: run section 1 (W1-W15 and W45) and every other check marked Blocking. Run W44 on an ARM64 PC, or say in the release notes that ARM64 is untested. Run the server tests on Linux too (`./gradlew test -PskipWeb` in `server/`, in WSL or on a Linux PC), since most POSIX tests skip on Windows. Put the checks and their results in the release notes. Leave this table as the record of the branch's own run.

### Blockers

Fix these before running anything else. None is open; the two below are kept as history.

- **B2. The first start fails on a new data folder** (found 2026-10-26, Win 10 x64, **fixed and committed in 5f63e3f, "windows support fixes 7"**: no JNA class loads before `jna.tmpdir` is set, and `main` creates `<data>\native` itself; `StartupNativeFolderTest`, and a JAR started twice on a new data folder works. W5 passed on the rerun, without the workaround). See [Failures found](#failures-found). Workaround used for the rest of chunk A: `set JAVA_TOOL_OPTIONS=-Djna.tmpdir=<data folder>\native` before `filemat.cmd`. Checks that ran with it say so in Notes.
- **B1. The second start fails** (found 2026-10-26, **fixed and committed in 057fa9f**: the line is gone from `sqlite-schema.sql` and `MigrationChainTest` runs the schema again after the migrations; the test and a second start on the same data folder pass). Every start runs `sqlite-schema.sql` again, and it still has `CREATE UNIQUE INDEX IF NOT EXISTS idx_files_inode ON files(inode)`. After V9 drops `inode`, every start after the first stops with `no such column: inode`, on Windows and Linux. Seen on a Windows data folder started twice, and in L1 after the upgrade from `main`. Fix: remove that line from `sqlite-schema.sql` (V8 already creates the index before V9 drops it), and make `MigrationChainTest` run the schema again after the migrations.

### Status

| ID | Check | Blocking | Who | Chunk | Status | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| W1 | Zip contents | yes | agent | A | PASS | 2026-10-26, Win 10 Pro 22H2 x64. Both zips have the listed files (jre\ 251/204 entries); `filemat.cmd` has 34 CRLF and no bare LF in both (checked bytes, not Notepad's status bar). The jre runs: Temurin 21.0.12.1; ffmpeg n7.1.5. |
| W2 | Download path, SmartScreen | | you | | | |
| W3 | Folder name with spaces and `&` | yes | agent | A | PART | 2026-10-26, Win 10 Pro x64 (ANSI code page 1250). Ran from `cmd` in `%TEMP%\fm-test\Fílemat beta (x86) & co` (not `C:\Apps`, outside the allowed folders), with the B2 workaround. Starts, serves, and Ctrl+C (sent to the console) shuts it down cleanly ("Terminate batch job" prompt then exit). Left for a person: double-click start. Plan error: `Fïlemat` has `ï`, which cp1250 doesn't have, so on a Central European Windows the start shows the W4 warning and fails ("Unable to access jarfile"); used `í`. |
| W4 | Folder outside the code page | | agent | A | PASS | 2026-10-26, Win 10 Pro x64 (cp1250). Folder `写真` (and `Fïlemat…`, which cp1250 can't hold): the folder warning shows, no "could not find java.dll" (Java then says "Unable to access jarfile …\??\filemat.jar"). Run in `%TEMP%\fm-test`, not `C:\Apps`. |
| W5 | First start | yes | agent | A | PASS | 2026-10-26, Win 10 Pro x64, zip rebuilt from HEAD (5e5aee8), no `JAVA_TOOL_OPTIONS`. The real default folder `%LOCALAPPDATA%\Filemat` was not used (it already exists), so a new `FM_DATA_DIR` in the work folder was used (same code path). First start: the data folder and `native\` are created, the setup code is in the console and in `setup-code.txt`, setup with it works (via the API, not the browser). Second start on the same folder: starts, admin login works. |
| W6 | Data folder ACL | yes | agent | A | PASS | 2026-10-26, Win 10 Pro x64. Work-folder data folder (and `db`, files in it): only `strom`, SYSTEM, Administrators. Weak test: `%TEMP%` already has that ACL, so it is inherited. The old default `%LOCALAPPDATA%\Filemat` has the same. W7 is the real test. |
| W7 | Existing data folder with open rights | yes | agent | A | PASS | 2026-10-26, Win 10 Pro x64. Plan steps as written. Before: `C:\Users\Public\FmData` also had INTERACTIVE, SERVICE, BATCH (modify) and CREATOR OWNER (no `Users` group on this PC). After the start (B2 workaround): folder, `old.txt`, db, `native`, `logs\filemat.log` list only `strom`, SYSTEM, Administrators. Folder deleted. |
| W8 | Log file | | agent | A | PART | 2026-10-26, Win 10 Pro x64. `logs\filemat.log` in the data folder has the console output from the "Log folder:" line on (5 earlier lines: banner, user, "Initializing", "Data folder" are console only). Non-ASCII is right: `Jürgen` and `写真` in the data folder name show correctly in the log and in the console, no `??` or U+FFFD (log read as UTF-8). Not as written: opening `写真.png` (content, metadata and thumbnail by API, also create, edit, copy, move with that name) writes no log line with a file name at all, so there was nothing to read; the encoding was checked through the data folder name. Plan error: that step can't be seen. B2 workaround used. |
| W9 | Firewall | | you | | | Needs a second device |
| W10 | Administrator window | | you | | | |
| W11 | Windows calls blocked | | agent | A | PASS | 2026-10-26, Win 10 Pro x64. With the plan's `JAVA_TOOL_OPTIONS`, Filemat exits: "Windows system calls don't work (Could not initialize class com.sun.jna.platform.win32.Kernel32). JNA unpacks its DLL to C:\Windows\System32\nope. Allow DLLs to load from there, or set -Djna.tmpdir …". Started from a hidden `cmd` through a script, with `FM_DATA_DIR` in the work folder. |
| W12 | Port taken | | agent | A | PASS | 2026-10-26, Win 10 Pro x64. With a `TcpListener` on 8080 and no `--server.port`: "APPLICATION FAILED TO START … could not listen on port 8080 … Run `netsh int ipv4 show excludedportrange protocol=tcp` … `netstat -ano \| findstr :8080`". `filemat.cmd --server.port=9090` serves (setup status 200). Listener stopped. Plan: the hint says `netsh int ipv4`, the plan says `netsh interface ipv4`: same command. B2 workaround used. | |
| W13 | FFmpeg card | | agent | A | PASS | 2026-10-26, Win 10 Pro x64. Checked through the endpoint the card reads (`GET /api/v1/admin/system/video-previews`), not by looking at the page: `isAvailable` true, source `…\ffmpeg\ffmpeg.exe`, version `ffmpeg version n7.1.5-…`. With `FM_FFMPEG_PATH=C:\nope\ffmpeg.exe`: `isAvailable` false, problem "FM_FFMPEG_PATH (C:\nope\ffmpeg.exe) is not the full path of an ffmpeg executable.", the console says "Video previews are off. …". Image thumbnail still 200 (6024 bytes); video preview 200 "Video previews are not available". Left for a person: look at the card in the browser. Note: in the off case the console also prints a javacv `UnsatisfiedLinkError: no jniavutil` stack trace (twice), which is noisy on Windows. B2 workaround used. | |
| W14 | Non-ASCII profile | | agent | A | PART | 2026-10-26, Win 10 Pro x64. `FM_DATA_DIR=%TEMP%\fm-test\Jürgen` (not `C:\Data\Jürgen`, outside the allowed folders; no `Jürgen` account). Starts, setup works (API), a 3 MB tus upload finishes and reads back (3145745 bytes). Only with the B2 workaround: without it the start fails (B2). Not tested: a Windows account named `Jürgen` (default data folder under a non-ASCII profile). Plan: `JAVA_TOOL_OPTIONS` is read in the ANSI code page, so the B2 workaround can't name a CJK data folder (JNA's folder became `data-??\native`); a CJK data folder needed an ASCII `jna.tmpdir` (worked). |
| W15 | Task Scheduler | | you | | | |
| W16 | Basic flows | yes | agent | B | PASS | 2026-10-26, Win 10 Pro x64, zip from HEAD. By API (Python), plus the browser for the listing. `D:\FmTest2` exposed. Browse works. Text, image and video open; image thumbnail 6024 bytes (also visible in the browser listing), video preview 200, video metadata 640Ă—360 5 s, range requests on the video give 206 (start and middle). Uploads: 5 MB file, 2 GB file (tus, 64 MB chunks, SHA-256 of the read-back equal, 15 s), folder upload with a nested folder and `ĺ†™.txt` (preflight, session, tus). Downloads: single file (attachment header), `tree` as zip (valid, 16 entries; it held the 2 GB file). Create, edit (`hĂĽĂ© ĺ†™çśź` reopened identical), rename, move, copy (file and folder), delete, and copy, move and delete between `C:` and `D:` in both directions all work. Not seen by eye: the video playing in the page (only the byte ranges it needs). |
| W17 | Paths and names in URLs | yes | agent | B | PASS | 2026-10-26, Win 10 Pro x64, built-in browser on `http://localhost:18080` (signed in with the test admin's token cookie). Each of the 7 files in `names` (`a#b`, `50%`, `a&b`, `a+b`, `a b`, `ĂśnĂŻcĂ¶dĂ©`, `ĺ†™çśź.png`) opens from its page URL (the URL the app makes, e.g. `a%23b.txt`) loaded fresh in a new frame, with the right title and content. `a#b.txt` opened and shown with its text. `C:/FMTEST/NAMES` opens and the URL switches to `C:/FmTest/names`. Backslashes: the breadcrumb shows segments with no slash at all, the paths in Settings â†’ System (upload folder, FFmpeg) show backslashes; no other place showed a full path. A real bookmark wasn't made (a bookmark is the same URL). |
| W18 | Permissions follow the file | yes | agent | B | PART | 2026-10-26, Win 10 Pro x64. By API. `tester` has READ on `tree\one`, `tree\one\two` shared as `two-share`, `tree\one\file.txt` saved. `Rename-Item` `one` to `uno`, then `Move-Item` to `C:\FmTest\uno`, Filemat running. After: `tester` sees `C:\FmTest\uno` (file.txt, two; READ), the old path is 404, the share logs in and lists `C:/FmTest/uno/two` with its file anonymously, the DB rows point at `C:/FmTest/uno`. Not as written: the saved file does not follow an Explorer move: `saved_files` still holds `C:/FmTest/tree/one/file.txt` and the saved list is empty. By design (saved files match by path; the plan, Phase 3, says so); a move or rename in Filemat does update it (checked: `file.txt` to `file2.txt` showed the new path). Plan error: the saved-file part can't pass after an Explorer move; do it with a move in Filemat. Moved `uno` back to `tree\one` afterwards (permission followed again). |
| W19 | Search | | agent | B | PASS | 2026-10-26, Win 10 Pro x64. Search `file` in `tree` finds all 4 `file.txt`, also in `a#b`. `REPORT` in `case` finds `Report.txt`. Signed out, `POST /api/v1/file/search` gives 401. Drive list `/` in the browser: no search button, no "Calculate folder size", and `/` itself shows no dates. Plan wording: the drives listed in `/` do show a real "Last Modified" and size (`C:` 28 KB), which "no dates" and section 9 ("The drive list shows no dates or sizes") don't say; the changelog (fixes 2) only means the 1970 date of `/` itself. |
| W20 | Case | | agent | C | PART | 2026-10-26, Win 10 Pro x64. By API. `case`: creating `report.txt` is refused ("This file already exists."); renaming `Report.txt` to `REPORT.txt` works, and `dir` shows `REPORT.txt`. Not run: the `cs` part. `fsutil file setCaseSensitiveInfo` needs admin ("Access is denied", also in the fixture script), so `C:\FmTest\cs` is empty and not case-sensitive. Left for a person with admin: the `a.txt`/`A.txt` permission check. |
| W21 | Case-sensitive folder moved and copied | yes | agent | C | SKIP | 2026-10-26, Win 10 Pro x64. No case-sensitive folder: `fsutil file setCaseSensitiveInfo C:\FmTest\cs enable` is "Access is denied" without admin, so the fixture `cs` has no files. Left for a person with admin. |
| W22 | Read-only | | agent | C | PASS | 2026-10-26, Win 10 Pro x64. By API. `ro.txt`: delete refused ("File is read-only."), edit refused (same), overwrite by a folder upload with the `overwrite` choice refused ("File is read-only."; content stays). `mixed`: the `a.txt` and `b.txt` are deleted, `ro.txt` stays, message "2 items could not be deleted. Read-only files were left in place." (counts the folder too, doesn't name `ro.txt`). Copy of `ro.txt`: `attrib` shows `A R`. Plan: a plain single-file tus upload to an existing name doesn't overwrite, it makes `ro (1).txt` (the web asks first); the overwrite is the folder-upload `overwrite` choice. Removed `ro (1).txt` and left `ro-copy.txt`. |
| W23 | File in use | | agent | C | PASS | 2026-10-26, Win 10 Pro x64. Locked with `[IO.File]::Open(..., 'Read', 'None')` from a second PowerShell. Move: 409 "File is in use."; delete: "File is in use."; edit: 409 "File is in use.". After closing it, edit and move work. Plan: `C:\FmTest\tree\file.txt` doesn't exist, used `tree\one\file.txt`. Word wasn't used. |
| W24 | Long path | | agent | C | PASS | 2026-10-26, Win 10 Pro x64. By API. The long folder (260 chars, files up to 300+). List, text file, image thumbnail (6024 bytes), video preview, video range 206 and metadata, folder as zip (valid, 4 entries), upload of a file, create folder, delete: all work. Not seen in the page. |
| W25 | Large TIFF | | agent | C | PASS | 2026-10-26, Win 10 Pro x64. `content-metadata` of `media\big.tiff` gives 12000Ă—12000; `java.exe` private memory 598 MB before, 604 MB after three calls (no jump). Plan: the file is 180 MB, not 430 MB (compressed). Watch: the thumbnail request for the same file takes `java.exe` from 604 MB to 1937 MB (+1.3 GB) and returns 826 bytes, so a folder listing with this file asks for that; not part of the check (V4 is the details panel). |
| W26 | Names Windows can't store | | agent | C | PASS | 2026-10-26, Win 10 Pro x64. By API. Folder `a:b`: "Path contains an invalid character."; `CON.txt`: "Path contains an invalid name."; rename to `name.`: "A name cannot end with a dot or space."; rename to `NUL .txt`: "Path contains an invalid name."; also `a<b.txt` refused. Nothing on disk (`dir /a` of `tree`). |
| W27 | Junctions | yes | agent | C | PASS | 2026-10-26, Win 10 Pro x64. `FM_FOLLOW_SYMBOLIC_LINKS` unset. By API. `junction\parent\link` is listed as `FOLDER_LINK`; listing it, or reading `link\keep1.txt`, gives 404. Copy of `parent`: 400 "1 items could not be copied. Links to folders were not copied."; `parent-copy` has only `file.txt`. Moved `parent-copy` to `D:\FmTest2`, deleted it there, deleted `junction\parent`: after each step `target` still has `keep1.txt` and `sub\keep2.txt` (also checked with `dir`). Plan: "Move to `D:\`" refused because only `D:\FmTest2` is exposed; moved into that. |
| W28 | Folder upload through a junction | yes | agent | C | PASS | 2026-10-26, Win 10 Pro x64. By API (folder upload preflight and session). Uploading `samename` with `planted.txt` into `junction\upload`: preflight blocks both with "Uploads cannot go through links to other folders."; the session is refused for overwrite, keep-both, skip and no choice. No `planted.txt` anywhere under `junction` (`dir /s`), `target` unchanged. |
| W29 | Symbolic links | | agent | C | SKIP | 2026-10-26, Win 10 Pro x64. Developer Mode off: `mklink` in cmd gives "You do not have sufficient privilege to perform this operation.", so the `links` fixtures weren't made (the script warned). Left for a person: turn on Developer Mode, make the fixtures, run the check. |
| W30 | Hard links | | agent | C | PASS | 2026-10-26, Win 10 Pro x64. By API. READ for `tester` on `hardlink\original.txt` works (and `tester` can't read `second.txt`). A permission on `second.txt` is created, and the entity permissions response has `isPathOnly: true` for it (the flag the details panel turns into the path-only note); `original.txt` has none. DB: `second.txt` row has `is_filesystem_supported` 0 and no file key. The note itself wasn't looked at in the page. |
| W31 | Sensitive folders | yes | agent | D | PART | 2026-10-26, Win 10 Pro x64. By API with `C:\` exposed (removed afterwards in W32's restart: see below). `C:\` listing has no `$Recycle.Bin`, `System Volume Information`, `hiberfil.sys` or `pagefile.sys`; the home folder listing has no `.ssh`, `.gnupg`, `.git-credentials`, `.docker` or `NTUSER.DAT`; `System32` has no `config`. List, content and metadata of `.ssh`, `.ssh\known_hosts`, `.gnupg`, `System32\config`, `C:\$Recycle.Bin` and of the running data folder (and its db) all give 400. Zip of `.ssh` returns an empty zip (22 bytes). A search for `known_hosts` in the home folder finds only two unrelated files, none in `.ssh`. Not run: delete and move of `C:\Windows` and `C:\Users` (the run's safety check refused to send them; left for a person). Plan: `%LOCALAPPDATA%\Filemat` can be listed (200; content 400) when it isn't the running data folder: only the data folder in use is blocked, so with `FM_DATA_DIR` elsewhere that step can't be checked. A home-folder search takes over a minute. |
| W32 | 8.3 short names | yes | agent | D | PASS | 2026-10-26, Win 10 Pro x64. By API as `tester` (READ, WRITE, DELETE, RENAME, MOVE, SHARE on `C:\FmTest`), `FM_HIDDEN_FOLDER_PATHS=C:\FmTest\blocked\LongSecretFolder`, short name `LONGSE~1`. Move and copy of `â€¦\LONGSE~1` fail (500 "Failed to rename 2 files.", 400 "Copied file was not found."), content, metadata, listing, thumbnail and delete of it all fail (404 or 400; `â€¦\LongSecretFolder\secret.txt` gives "This file is blocked."), zip gives no content. `LongSecretFolder` and `secret.txt` are still in place and nothing named `stolen` exists. The same move with `C:/Users/strom/SSH~1` (the real `.ssh`, 14 entries) fails the same way (500 "Failed to move 15 files.", nothing moved; `C:\` was not exposed at that point, I removed it before this check). Note: the move gets as far as the per-file permission step and reports a 500 with a file count instead of a plain refusal; nothing moved, so not a FAIL. The listing of `blocked` as `tester` is empty. Not run: the 8.3 reads with `C:\Users\<you>` exposed (only the move). |
| W33 | Filemat's own folders | | agent | D | PASS | 2026-10-26, Win 10 Pro x64. By API (the settings calls the page makes). Upload folder and thumbnail cache folder: `C:\` refused ("A drive or the root folder can't be used."), the running data folder and `%LOCALAPPDATA%` (which contains the default data folder) refused ("The Filemat data folder, or a folder that contains it, can't be used."). Not as written: `%LOCALAPPDATA%\Filemat` was accepted, because it isn't the running data folder here (`FM_DATA_DIR` is in the work folder); a folder inside the running data folder (`<data>\native`) is also accepted. The 2 GB upload (tus) with the upload folder changed to `C:\FmUploads` at about half finished normally (full 2147483648 bytes read back; `uploads` and `locks` moved to the new folder). With `keep.txt` in the thumbnail cache folder: a thumbnail file appeared, "Clear preview cache" removed it and `keep.txt` stayed. Left behind: `C:\FmUploads` (empty folders). Setting the upload folder to `%LOCALAPPDATA%\Filemat` moved the `uploads` and `locks` folders into it for a moment, so don't try that step with a data folder that matters. |
| W34 | Data folder through a junction | | agent | D | PASS | 2026-10-26, Win 10 Pro x64. A junction in the work folder (`data-link` to `data-w5`, not `C:\FmData-link` to `%LOCALAPPDATA%\Filemat`: those are outside the allowed folders), started with `FM_DATA_DIR=<work>\data-link` (console: "Data folder: …\data-link"). A 2 MB upload finishes. With the thumbnail cache folder set to `data-link\cache\thumbnails`, the thumbnail file appears in `data-w5\cache\thumbnails`. With the work folder exposed: the real path `data-w5` (and its db) gives 400, the junction path `data-link` (and below) 404, and `data-w5` is not in the listing of the work folder. Neither can be opened. |
| W35 | OneDrive | yes, if common | you | | | |
| W36 | FAT32 and exFAT | | you | | | Admin `diskpart` |
| W37 | `subst` | | agent | D | PASS | 2026-10-30, fixes 25: in the page, `/files/S:/names/a%23b.txt` now switches the URL to `/files/C:/FmTest/names/a%23b.txt` and the breadcrumb is `C: > FmTest > names > a#b.txt`; `S:/names` and `s:/Names/A%23B.TXT` do the same, a `C:` URL stays as it is (browser pane, JAR built from the fixed tree). Before the page fix (earlier the same day) the breadcrumb still showed `S:`. Fixed in fixes 20, checked by API (2026-10-26, Win 10 Pro x64): `S:/Names/a#b.txt`, `s:/names/A#B.TXT` and `S:/Names` give the real `C:/…` path in `meta.path`. First run (before the fix, FAIL): `subst S: C:\FmTest` made before Filemat started (same session). `S:/names/a#b.txt` opens by URL (content right, also `s:` and `S:/NAMES`), and the drive list `/` shows `S:/`. A permission on `C:\FmTest\names` applies through `S:` (`tester` with READ only on `names`: `S:/names/a#b.txt` 200, `S:/case/REPORT.txt` refused like the `C:` path). Not as expected: the opened file's page and its metadata show `S:/names/a#b.txt` (breadcrumb `S: > names > a#b.txt`), not `C:\FmTest\names\a#b.txt`; only the entries in a folder listing show the real `C:/FmTest/â€¦` paths. See Failures found. `subst S: /d` done. |
| W38 | Network shares | yes | you | | | Admin for the share |
| W39 | Controlled folder access | | you | | | |
| W40 | Stopped during an upload | yes | agent | D | PART | 2026-10-26, Win 10 Pro x64. 2 GB tus upload by API. Ctrl+C (sent to the console of a started `filemat.cmd`) at 1.6 GB: graceful shutdown in the log, "Terminate batch job" prompt; start again: normal start, `HEAD` on the upload gives offset 1610612736, the rest is sent and the file reads back at 2147483648 bytes. A forced kill of Filemat at 1.0 GB: the same, offset 1073741824 after the restart, upload continued. Not run: closing the console window by hand and ending the scheduled task (a person; the forced kill covers an abrupt end). Note for runs by an agent: a console started from a tool that ignores Ctrl+C passes that on to Filemat, and the Ctrl+C does nothing; clear it with `SetConsoleCtrlHandler(NULL, false)` in the starting script. |
| W41 | Upload state files | | agent | D | PASS | 2026-10-26, Win 10 Pro x64. Folder `bogus` with a file `info` ("not a serialized object") in `<data>\uploads\uploads`: Filemat starts, no "Failed to clear expired TUS upload files" in the console or `filemat.log` (checked about 2 minutes after the start), a new 1 MB upload works, `bogus` is still there (not expired). Plan: the expired-upload cleanup runs once at the start and then every 2 hours (`TusService.startTusCleanupLoop`), not "a few minutes", so the second run wasn't waited for. The default `%LOCALAPPDATA%\Filemat\uploads\uploads` wasn't used (work-folder data folder). |
| W42 | Upgrade | yes | agent | D | PASS | 2026-10-26, Win 10 Pro x64. "Old" build: the 2026-10-25 zip `filemat-test-windows-x64.zip` (it already has the V9 migration), run on a new data folder (`native\` made by hand first: that build still has B2) with admin and tester, a permission for tester, a share and a saved file. Stopped it, moved the app folder aside (instead of deleting it) and unzipped the build from this run in the same place. After the start: both users, tester's permission, the share login and the saved file are all still there; `icacls` on the data folder lists only SYSTEM, Administrators and the user. No database version change, so no `.bak` was made: that part (the `.bak` and its rights) is not covered. Left for a person: an upgrade from a release with an older database version. |
| W43 | Docker Desktop | | you | | | |
| W44 | ARM64 | | you | | | |
| W45 | Bundled runtime | yes | agent | D | PASS | 2026-10-30 steps 3 and 4 (below), 2026-10-29 steps 1 and 2, Win 10 Pro x64, the x64 runtime from the 2026-10-26 build. Steps 1 and 2 by hand: `jdeps` on the unpacked JAR lists 13 modules (`java.base`, `java.compiler`, `java.desktop`, `java.instrument`, `java.management`, `java.net.http`, `java.prefs`, `java.rmi`, `java.scripting`, `java.security.jgss`, `java.sql.rowset`, `jdk.jfr`, `jdk.unsupported`), all in the runtime's 25; `keytool -printcert -sslserver github.com:443` prints the chain. Steps 3 and 4 on 2026-10-30, with `filemat-0.0.0-test-windows-x64.zip` built from `c081e583` and started with the zip's own `jre\bin\java.exe` (`filemat.cmd`, `FM_DATA_DIR=C:\FmRun\data-w45`, port 9090): 2FA set up with the secret from the page (TOTP made with Python's `hmac`, checked against the RFC 6238 vector); a wrong code at sign-in gives 400 "2FA code is incorrect." (`invalid-totp`), the right code signs in. `POST /api/v1/file/zip-multiple-content` for a 9-entry folder (nested folders, an empty folder, `a b#c.txt`, `Ünï.txt`, a 5000-byte binary) gives a 6366-byte zip that `Expand-Archive` opens with the same 9 names and identical file hashes. No `NoClassDefFoundError`, `ClassNotFoundException` or "module not found" in the console or `logs\filemat.log`; the only warnings are Flyway skipping `db.migration.Migration` (a base class name, not a missing module). Differences from the plan: the account was the setup admin (`admin`), not a separate `tester`; the zip was downloaded by API with the signed-in session (after the same page code ran in the browser and returned the same bytes), not by clicking; `tree` was not used because it holds several multi-GB files. |
| L1 | Upgrade from `main` | yes | agent | E | PASS | 2026-10-26, WSL2 Ubuntu, Java 21. `main` (b201d62, built in a throwaway worktree with `jvmToolchain(21)` because only Java 21 is installed) ran with its fixed `/var/lib/filemat`, which already existed (made 2026-10-24, owned by the test user, empty database from an earlier run), so it was used as is; nobody else's data was in it. Setup, tester, a permission on `tree/one`, a share on `tree/one/two` and a saved file were made on `main`. Stopped it, started the new JAR from this branch with no `FM_DATA_DIR`: "Data folder: /var/lib/filemat", "Backing up the database to …/filemat-server.db.v8-<date>.bak before migrating...", then "Migrating schema main to version 9", `.bak` is `-rw-------`, `table_info(files)` has `path_key` and `file_key` and no `inode`, no `file_key IS NULL` rows. Users, tester's permission, the share login and the saved file all work; a second start of the new JAR works (B1). Not as written: no `sudo cp -a` backup (sudo is only for L9 and L15 here, and `/var/lib` isn't writable without it), the `.bak` is the backup. |
| L2 | Data folder rights | yes | agent | E | PASS | 2026-10-26, WSL2 Ubuntu. `/var/lib/filemat` is `drwxr-x---`, `filemat-server.db` and the `.bak` `-rw-------`. After `chmod 755` on the folder and `644` on the db, a restart puts back `drwxr-x---` and `-rw-------` (the `.bak` stays `-rw-------`). |
| L3 | Backups pruned | | agent | E | SKIP | 2026-10-26, WSL2 Ubuntu. No build with a newer migration than V9 at hand (a unit test covers the pruning). The `.bak` made by the V8 to V9 upgrade in L1 is the only one. |
| L4 | Upload and cache folder refused | | agent | E | PASS | 2026-10-26, WSL2 Ubuntu. Upload folder and thumbnail cache folder set to `/`: refused ("A drive or the root folder can't be used."); `/var/lib` and `/var/lib/filemat`: refused ("The Filemat data folder, or a folder that contains it, can't be used."). With `upload_folder_path` set to `/var/lib` in the database (older install): the console says "Failed to start the upload service. Uploads are unavailable." with the folder named; an upload then gives a plain 500 (no message); after setting the upload folder to a good one, an upload works again without a restart. |
| L5 | Search needs a session | yes | agent | E | PASS | 2026-10-26, WSL2 Ubuntu. `curl -i -X POST /api/v1/file/search -d 'path=/&text=a'` gives `HTTP/1.1 401`. With a share login token (anonymous), search with `path=/` finds the share's `file.txt`; without the token 401; a path outside the share is "not found". Share paths are relative to the share (`/`), not the real path. |
| L6 | Prefix look-alikes | yes | agent | E | PASS | 2026-10-26, WSL2 Ubuntu. By API. `tester` has READ on `a_b` and `foo` only: reads `a_b/inside.txt` and `foo/inside.txt` 200; `a%b`, `aXb`, `foobar` 400. Shared `foo` (`foo-share`) and saved `foo/inside.txt`. Renamed `foo` to `foo2` in Filemat: `tester` still reads `foo2`, the share still logs in and lists `/inside.txt`, the saved file shows `prefix/foo2/inside.txt`; `foobar` has no share and no permission (as do `a%b`). Also renamed `a_b` to `a_c`: `tester` reads `a_c`, still not `a%b` or `aXb`. Renamed back. |
| L7 | Names in URLs | | agent | E | PASS | 2026-10-30, WSL2 Ubuntu (Java 21) with the zip's `filemat.jar` (it has the web app), browser pane on `localhost:9191`: each of the six files opens by its page URL (`/files/…/a%23b.txt` and so on) with the right tab title and breadcrumb, from a full page load (the same as reloading or opening the copied URL in a new tab). Bookmarking itself wasn't done (a bookmark is that URL). Before that, 2026-10-26, WSL2 Ubuntu, API only. The six files `a#b`, `a?b`, `50%`, `a&b`, `a+b`, `a b` all list, and their content, metadata and entries calls work with the path URL-encoded (`%23 %3F %25 %26 %2B %20`). Not run: opening the page URLs, reloading and bookmarking in a browser, because the Linux JAR was built with `-PskipWeb` (no web app in it; there's no Node in WSL). Left for a person, or run the Windows checks W17 against a Linux JAR with the web app. |
| L8 | Video previews with javacv | yes | agent | E | PASS | 2026-10-26, WSL2 Ubuntu, API only (no web app in this JAR). Video previews status: `isAvailable` true, `source` "built-in". Video preview 200 (6005 bytes), video metadata 640×360 5 s, byte ranges on the video give 206 (playback itself not watched). Thumbnails: the `.avif` made with ffmpeg (320×240) gives a JPEG thumbnail (7362 bytes) and metadata; `.png` thumbnail 200. Not tested: `.heic` (no encoder to make one). |
| L9 | Without the FFmpeg natives | | agent | E | PASS | 2026-10-26, WSL2 Ubuntu. `-Dorg.bytedeco.javacpp.platform=linux-ppc64le`, no `ffmpeg` on PATH: the server starts; the console says "Video previews are off. FFmpeg was not found. Install it and add it to PATH, or set FM_FFMPEG_PATH, then restart Filemat." (followed by an `UnsatisfiedLinkError: no jniavutil` stack trace), and the card's endpoint says `isAvailable` false with the same problem; image thumbnails still 200; a video preview request gives 500 `{}` (on Windows the same case gave 200 with a "not available" text). After `sudo apt-get install -y ffmpeg`: `source` `/usr/bin/ffmpeg`, "ffmpeg version 8.0.1-3ubuntu2", console "Video previews use FFmpeg at /usr/bin/ffmpeg.", video preview 200 (6005 bytes), metadata 640×360. ffmpeg is left installed in the WSL Ubuntu. |
| L10 | Playlists | yes | agent | E | PASS | 2026-10-26, WSL2 Ubuntu, API only. `private/secret.ts` (red, made with the Windows ffmpeg, as `ffmpeg` wasn't installed yet) and `public/evil.m3u8` as in the plan; `tester` has READ on `public`. As `tester`: `video-preview` for `evil.m3u8` gives 500 `{}` (no image), `content-metadata` 200 `{}` (no size or duration); the same for admin. `tester` can't read `private/secret.ts` (400). The test is real: `ffmpeg -i public/evil.m3u8 -frames:v 1` outputs a red frame (rgb 253,0,0). |
| L11 | Large TIFF | | agent | E | PASS | 2026-10-26, WSL2 Ubuntu. `big.tiff` is the 180 MB file made on Windows (`color=c=blue:s=12000x12000` with the Windows ffmpeg) and copied in. `content-metadata` gives 12000×12000; the Java process RSS stays at 548 MB over three calls. Not part of the check: the thumbnail request for the same file takes RSS to 1598 MB (+1.05 GB), returning 826 bytes (known bug, see the W25 note). `top` wasn't used, `ps` RSS was. |
| L12 | Upload state files | | agent | E | PASS | 2026-10-26, WSL2 Ubuntu. Folder `bogus` with a file `info` in `<upload folder>/uploads` (the upload folder here is `~/fm-test/data-l1/uploads`, not `/tmp/filemat/uploads`: that path isn't what this install uses): Filemat starts, no "Failed to clear expired TUS upload files" (checked 20 s after the start), a new 1 MB upload works, `bogus` stays. Plan: the path `/tmp/filemat/uploads` doesn't match the default upload folder, which is `<data folder>/uploads` (as on Windows); the cleanup runs at the start and then every 2 hours. |
| L13 | Uploads resume after a restart | yes | agent | E | PASS | 2026-10-26, WSL2 Ubuntu. 2 GB tus upload by API, `kill -INT` on the Java process at 1.0 GB (graceful shutdown in the log; the process is started so that SIGINT isn't ignored, which a plain `cmd &` in a script would do): restart, `HEAD` on the upload gives offset 1073741824, the rest is sent, the file is 2147483648 bytes. A normal upload through the deserialization filter works (also L12). |
| L14 | Folder upload through a symbolic link | | agent | E | PASS | 2026-10-26, WSL2 Ubuntu. `ln -s <files>/other <files>/dest/samename`, `FM_FOLLOW_SYMBOLIC_LINKS` unset. Folder upload of `samename` with `planted.txt` into `dest`: preflight blocks both ("Uploads cannot go through links to other folders."), the session is refused for overwrite, keep-both, skip and no choice; a single tus upload into `dest/samename/` gives "The target folder does not exist.". `other` stays empty. Link and folder removed afterwards. |
| L15 | Two file systems | | agent | E | PASS | 2026-10-26, WSL2 Ubuntu. Two tmpfs mounts (`sudo mount -t tmpfs`, with the test user's uid) under `<files>/fm1` and `fm2` (inside the exposed folder, not `/mnt/fm1`), each with `a.txt` ("one", "two"): `stat` gives inode 2 on both (devices 85 and 87), the DB keys are `posix:85:2` and `posix:87:2`. READ for `tester` on `fm1/a.txt` and a share on `fm2/a.txt`: after a restart both still exist and point at their own file (`tester` reads "one" from `fm1` and is refused on `fm2`; the share gives "two"). First attempt, with entries made before the mounts, showed that an old permission and share at the same path carried over to the new file (matched by path after the key changed); the final result is from fresh entries. Unmounted afterwards. |
| L16 | Browser without service workers | | you | | | Firefox private window |
| L17 | Folder download to a Windows PC | | you | | | Needs both machines |
| L18 | Docker image | | you | | | |

**Run so far (2026-10-26):** every `agent` check has run, on Windows 10 Pro 22H2 x64 and WSL2 Ubuntu: 40 PASS, 7 PART, 3 SKIP, 0 FAIL (W37 failed, fixed in fixes 20 for the API and in fixes 25 for the page; W45 ran in full on 2026-10-30; L7 ran in a browser on 2026-10-30). Left: the 13 `you` checks, and the parts of the PART and SKIP rows that need a person (their Notes say which: admin rights for W20, W21 and W31, Developer Mode for W29, a browser for L7, and so on).

### Failures found

For each: ID, date, machine, steps, expected, actual, log lines, and the likely code. Move an entry to [Blockers](#blockers) if it stops other checks.

#### B2. First start on a new data folder fails: `NativeLibraryNotFoundException` for SQLite

- **Found:** 2026-10-26, Windows 10 Pro 22H2 x64, zip `filemat-0.0.0-test-windows-x64.zip` built from the working tree (with the B1 fix).
- **Steps:** unzip, `set FM_DATA_DIR=%TEMP%\fm-test\data1` (a folder that doesn't exist), run `filemat.cmd --server.port=18080`. Happens the same on a second fresh folder.
- **Expected:** the start creates the data folder and `native\`, prints the setup code.
- **Actual:** Filemat exits with `Unable to start embedded Tomcat`, caused by `org.sqlite.NativeLibraryNotFoundException: No native library found for os.name=Windows, os.arch=x86_64`. Before it: `java.nio.file.NoSuchFileException: <data>\native` from `SQLiteJDBCLoader`. `<data>\native` is never created. With `-Djna.debug_load=true`, JNA extracts `jnidispatch.dll` to `%TEMP%\jna-109773775\`, not to `<data>\native`.
- **Log lines** (`logs\filemat.log`): `ERROR ... org.sqlite.SQLiteJDBCLoader : Failed to open directory`, `java.nio.file.NoSuchFileException: ...\data1\native`, then `Failed to load native library through System.loadLibrary`.
- **Likely code:** `ServerApplication.kt` `main()`: `Props` (line 26) runs before `useNativeLibraryFolder()` (line 31), and `Props.nonDeletableFolders` (`Props.kt:49`) is `NonDeletableSystemPaths`, which reads `WindowsSystemFolders.current` through JNA (`shell32`). That loads JNA before `jna.tmpdir` is set, so JNA uses `%TEMP%\jna-<hash>`, and then sqlite-jdbc (`org.sqlite.tmpdir` points to `<data>\native`, which doesn't exist) can't extract its DLL. `useNativeLibraryFolder()` needs the data folder from `Props`, so it needs to run before the paths lists load, and create the `native` folder itself rather than leaving it to JNA.
- **Workaround:** `set JAVA_TOOL_OPTIONS=-Djna.tmpdir=<data>\native` before `filemat.cmd`: JNA then creates the folder, and the start works (also the second start).
- **Fixed** in "windows support fixes 7": `Props.nonDeletableFolders` is a getter, `prepareStartup()` sets `jna.tmpdir` and `org.sqlite.tmpdir` before any JNA class loads, and `initializeFileStructure` creates the folder after the data folder check. W5 passed on the rerun.

#### W37. A file opened through a `subst` drive shows the `subst` path, not the real one

- **Found:** 2026-10-26, Windows 10 Pro 22H2 x64, zip from HEAD (5e5aee8).
- **Steps:** `subst S: C:\FmTest`, then start Filemat (`FM_DATA_DIR` in the work folder, `C:\FmTest` exposed). Open `http://localhost:18080/files/S:/names/a%23b.txt`, or `POST /api/v1/folder/file-and-folder-entries` with `path=S:/names/a#b.txt`.
- **Expected** (W37, and `docs/windows.md`: "`subst` drives work and are shown as their real folder"): it opens and shows as `C:\FmTest\names\a#b.txt`.
- **Actual:** it opens, but the page breadcrumb shows `S: > names > a#b.txt` and the metadata `path` is `S:/names/a#b.txt`. The entries of a folder listing (`S:/names`) do show the real `C:/FmTest/names/…`, and a permission on `C:\FmTest\names` applies. Not a security problem; the permission and exposure checks use the real path.
- **Log lines:** none.
- **Likely code:** the metadata for the requested path keeps the path as typed (`meta.path` in `FolderController.fileOrFolderEntriesMapping` / `FileMetadataService`) instead of the canonical one; listing entries use the canonical path.
- **Fixed** in "windows support fixes 20": `FileMetadataService.displayPath` moves a path on a `subst` drive to the folder the drive points to before it compares it with the canonical path. A followed link still shows its own name (on the real drive). Checked by API with a real `subst S:`; the web page builds its breadcrumb from that path.
- **Fixed again** in "windows support fixes 25": that last claim was wrong. With the zip from `c081e583`, `meta.path` for `S:/names/a#b.txt` was `C:/FmTest/names/a#b.txt`, but the page at `/files/S:/names/a%23b.txt` still showed the breadcrumb `S: > names > a#b.txt`: `filesState.path` comes from the URL (`page.params.path`) and the breadcrumb is built from it, not from `meta.path`. `loadPageData` already switched the URL to the real case for a path typed in the wrong case; it now does the same when the real path is on another drive (`isOtherDrive` in `path.ts`, the only way a Windows path can come back on another drive is a `subst` drive). Only that case redirects, not every difference, so a followed link on Linux keeps its URL. Checked in the page (see the W37 row).

## 0. Preparation

### Machines

| Machine | Needed for | Notes |
| --- | --- | --- |
| Windows 10 22H2 or Windows 11, x64 | W | A VM is fine, except for W9 (firewall) which needs a second device. Defender real-time scanning stays on. |
| Linux x64 | L | A Linux VM, a server, or WSL (Ubuntu). L1 needs an install from `main` with some data. |
| Windows with Docker Desktop | W43 | Can be the same PC. |
| Windows 11 on ARM64 | W44 | Optional. Without one, the release notes say ARM64 is untested. |
| A phone or second PC | W9 | Same network. |

### Build

On Windows, in `server\` with JDK 21:

```bash
.\gradlew.bat bootJar windowsDist -PreleaseVersion=0.0.0-test
```

This gives `server\build\libs\server-app.jar` (the plain JAR, called `filemat.jar` in the releases and in the zips) and both zips in `server\build\dist`. Copy the JAR to the Linux machine.

### Accounts

After setup on each machine, create:

- `admin`: the setup account.
- `tester`: a normal user with the default user role, no admin permissions.

"As tester" below means in a private browser window, signed in as `tester`.

### Test files (Windows)

`utilities\windows-test-fixtures.ps1` creates them in `C:\FmTest`. Turn on Developer Mode first (Settings → For developers), or the symbolic link checks are skipped:

```bash
powershell -File .\utilities\windows-test-fixtures.ps1 -Ffmpeg .\server\build\windows\x64\ffmpeg\ffmpeg.exe
```

If PowerShell refuses to run scripts, add `-ExecutionPolicy Bypass` after `powershell`. Add `-BigTiff` for W25 and L11: it writes a compressed TIFF of about 180 MB (12000 × 12000 pixels, about 430 MB once decoded). The script prints the 8.3 short name of `C:\FmTest\blocked\LongSecretFolder` and the long folder path; note both. It creates:

| Folder | Holds |
| --- | --- |
| `media` | `video.mp4`, `image.png`, and `big.tiff` with `-BigTiff` |
| `names` | `a#b.txt`, `50%.txt`, `a&b.txt`, `a+b.txt`, `a b.txt`, `Ünïcödé.txt`, `写真.png` |
| `prefix` | Folders `a_b`, `a%b`, `aXb`, `foo`, `foobar` |
| `case` | `Report.txt` |
| `cs` | Case-sensitive folder with `a.txt` ("lower") and `A.txt` ("UPPER") |
| `readonly` | `ro.txt`, and `mixed\` with one read-only and two normal files |
| `junction` | `target\` (two files), `parent\link` (junction to `target`), `upload\samename` (junction to `target`) |
| `links` | `local-link.txt` (symbolic link), `network-link` (symbolic link to `\\192.0.2.1\share`, an address that never answers) |
| `hardlink` | `original.txt` and `second.txt`: one file, two names |
| `long\xxx…` | A path of about 300 characters with a video, an image and a text file |
| `private`, `public` | `private\secret.ts` (a red video); `public\evil.m3u8`, a playlist that lists it |
| `blocked\LongSecretFolder` | For the short name check |
| `tree` | Nested folders for zip download and search |

To delete them afterwards: `cmd /c rmdir /s /q C:\FmTest` (it doesn't enter the junctions).

### Calling the API directly

A few checks call the API in a way the web app doesn't. Open the browser's developer tools (F12) on the Filemat tab, signed in as the user named in the check, and run in the Console:

```js
const post = async (url, fields) => { const f = new FormData(); for (const k in fields) f.append(k, fields[k]); const r = await fetch(url, { method: `POST`, body: f }); return `${r.status} ${await r.text()}` }
```

---

## 1. Windows: build and start

- [ ] **W1 Zip contents (blocking).** Open `filemat-0.0.0-test-windows-x64.zip`. It has `filemat\filemat.cmd`, `filemat.jar`, `jre\`, `ffmpeg\ffmpeg.exe` and `LICENSES\` (Filemat, FFmpeg, `THIRD-PARTY.txt`). `filemat.cmd` has CRLF line endings (Notepad's status bar shows "Windows (CRLF)"). The arm64 zip has the same files. [Phase 7, 8]
- [ ] **W2 Download path.** Put the zip on a web server, or upload it to a cloud drive, and download it with Edge, so it gets the "downloaded from the internet" mark. Without unblocking, unzip and run `filemat.cmd`: write down what SmartScreen shows. Then unblock (Properties → Unblock), unzip again, and run it. [§5]
- [ ] **W3 Folder name (blocking).** Unzip to `C:\Apps\Fïlemat beta (x86) & co`, with a letter the Windows code page has (`ï` on a Western Windows, `í` on a Central European one, where `ï` is W4's case). `filemat.cmd` starts by double-click and from `cmd`. Stop it with Ctrl+C. [Phase 8]
- [ ] **W4 Folder outside the code page.** Copy the folder to `C:\Apps\写真` (on an English or Western Windows) and run `filemat.cmd`: it shows the folder warning instead of "could not find java.dll". [Phase 8]
- [ ] **W5 First start (blocking).** With no `%LOCALAPPDATA%\Filemat`: the start creates it, prints the setup code, and writes `setup-code.txt` there. Setup in the browser works with that code. [Phase 1]
- [ ] **W6 Data folder ACL (blocking).** `icacls %LOCALAPPDATA%\Filemat` lists only your account, SYSTEM and Administrators. [Phase 1, fixes 4]
- [ ] **W7 Existing data folder with open rights (blocking).** Stop Filemat. Run `mkdir C:\Users\Public\FmData` and `echo x > C:\Users\Public\FmData\old.txt`. In a new `cmd`, run `set FM_DATA_DIR=C:\Users\Public\FmData` and then `filemat.cmd`. After the start, `icacls C:\Users\Public\FmData` and `icacls C:\Users\Public\FmData\old.txt` list only your account, SYSTEM and Administrators. Delete the folder afterwards. [fixes 4]
- [ ] **W8 Log file.** `logs\filemat.log` in the data folder has the console output from the "Log folder:" line on. Opening a file writes no file name to the log, so non-ASCII text is checked through the data folder's name: start with `FM_DATA_DIR=%TEMP%\fm-test\Jürgen写真`, and that name shows correctly in the log (read as UTF-8) and in the console, not as `??` or U+FFFD. [Phase 7]
- [ ] **W9 Firewall.** The first start shows the firewall prompt for `java.exe`. Allow **Private** only. From the phone on the same network, `http://<pc-ip>:8080` opens Filemat. Restart Filemat: no second prompt. [§5]
- [ ] **W10 Administrator window.** Run `filemat.cmd` from an administrator `cmd`: the elevated warning shows. From a normal window: no warning. [Phase 1]
- [ ] **W11 Windows calls blocked.** In a normal `cmd`: `set "JAVA_TOOL_OPTIONS=-Djna.nosys=true -Djna.tmpdir=C:\Windows\System32\nope"`, then `filemat.cmd`. Filemat stops with "Windows system calls don't work" and names the folder to allow. Close that window afterwards. [fixes 3]
- [ ] **W12 Port.** In PowerShell, take the port: `$l = [System.Net.Sockets.TcpListener]8080; $l.Start()`. Start Filemat: it stops with the hint about `netsh interface ipv4 show excludedportrange protocol=tcp`. `filemat.cmd --server.port=9090` works. `$l.Stop()` afterwards. [§5.4]
- [ ] **W13 FFmpeg card.** Settings → System → Video previews says "Using FFmpeg at …\ffmpeg\ffmpeg.exe" with a version. Stop Filemat, `set FM_FFMPEG_PATH=C:\nope\ffmpeg.exe`, start it: the card and the console say video previews are off, and why. Image thumbnails still work. [Phase 6]
- [ ] **W14 Non-ASCII profile.** On a Windows account named `Jürgen`, or with `FM_DATA_DIR=C:\Data\Jürgen`: Filemat starts, setup works, an upload finishes. [Phase 1]
- [ ] **W15 Task Scheduler.** Create the task as `docs/windows.md` says, once "At log on" and once "At startup". After a sign-out or a reboot, Filemat runs without a window and serves pages. End the task in Task Scheduler: Filemat stops. [Phase 7]

- [ ] **W45 Bundled runtime (blocking).** The zip's `jre\` is trimmed with jlink (the module list is `jreModules` in `server/build.gradle.kts`), so a module that `jdeps` can't see, because it is only loaded at runtime, is missing there and nowhere else. Run everything with the zip's `jre\bin\java.exe`, not an installed Java. [R20]
  1. **Modules.** With a JDK 21, unpack the JAR (`jar xf filemat.jar` in an empty folder) and run `jdeps --print-module-deps --ignore-missing-deps --multi-release 21 --class-path "BOOT-INF/lib/*" BOOT-INF/classes`. On the JAR itself `jdeps` only sees the launcher. Each module it lists is in `jre\bin\java.exe --list-modules`.
  2. **TLS.** `jre\bin\keytool.exe -printcert -sslserver github.com:443` prints a certificate chain. It must not fail with `SSLHandshakeException` or "No appropriate protocol". This uses the runtime's own TLS, its `cacerts` and its elliptic-curve support (`jdk.crypto.ec`), which are what a Filemat behind an HTTPS proxy or a feature that calls an HTTPS URL would need.
  3. **TOTP.** Start Filemat from the zip. As `tester`, turn on two-factor authentication in the account settings, and sign out. Sign in with the password and a code from an authenticator app, or from the secret the page shows (any TOTP tool, 30 seconds, 6 digits): you are signed in. A wrong code is refused.
  4. **Zip download.** As `tester`, download `tree` as a zip from the page. The file opens in Explorer, and holds every file and folder of `tree`.
  Expected: every step works, with no `NoClassDefFoundError`, `ClassNotFoundException` or "module not found" in the console or `logs\filemat.log`. After a release adds a dependency, run step 1 again: a new module is added to `jreModules`.

## 2. Windows: main flows

Expose `C:\FmTest` (Settings → System → Exposed files) and a folder on a second drive (`D:\FmTest2`, or a virtual disk as in W36).

- [ ] **W16 Basic flows (blocking).** Browse; open a text file, an image and the video; upload a file, a folder, and a 2 GB file. Download a file and `tree` as a zip. Create, rename, move, copy and delete files and folders, also between `C:` and `D:`. Edit a text file with `ü` and `写` in it and reopen it. Image and video thumbnails show, the video plays and seeks. [Phase 2-6]
- [ ] **W17 Paths and names (blocking).** Paths show with backslashes. Open each file in `names`, copy the page URL, open it in a new tab, and reload: each opens. Bookmark `a#b.txt` and open the bookmark. In the address bar, change `FmTest/names` to `FMTEST/NAMES` and press Enter: the folder opens and the URL and path switch to the real case. [Phase 2b, 8]
- [ ] **W18 Permissions follow the file (blocking).** As admin, give `tester` read access to `C:\FmTest\tree\one`, share `tree\one\two` publicly, and save `tree\one\file.txt` to the saved files. With Filemat running, in Explorer, rename `one` to `uno` and move `tree\uno` to `C:\FmTest\uno`. In Filemat (reload): `tester` still sees `C:\FmTest\uno`, the share link still opens. For the saved file, move or rename it inside Filemat (`file.txt` to `file2.txt`): the saved list shows the new path. A move in Explorer doesn't update saved files, by design (they match by path). [Phase 3]
- [ ] **W19 Search.** Search `file` in `C:\FmTest\tree`: it finds every `file.txt`, also in `a#b`. Search `REPORT` in `case`: it finds `Report.txt`. The drive list `/` has no search button and no folder size, and `/` itself has no dates; the drives in it show their own dates and sizes. As a signed-out user, run `fetch('/api/v1/file/search', { method: 'POST', body: new URLSearchParams({ path: 'C:/FmTest', text: 'a' }) }).then(r => r.status)` in the console: 401. [fixes 2]

## 3. Windows: file system behaviour

- [ ] **W20 Case.** In `case`, create `report.txt`: refused, the name exists. Rename `Report.txt` to `REPORT.txt`: works, Explorer shows the new case. In `cs`, both `a.txt` and `A.txt` show, with their own content. Give `tester` read access on `A.txt` only: `tester` can open `A.txt` but not `a.txt`. [Phase 2]
- [ ] **W21 Case-sensitive folder moved and copied (blocking).** Copy `C:\FmTest\cs` to `C:\FmTest\cs-copy` in Filemat, then move `cs-copy` to `C:\FmTest\tree\cs-moved`. Both still have `a.txt` "lower" and `A.txt` "UPPER". `fsutil file queryCaseSensitiveInfo C:\FmTest\tree\cs-moved` says enabled. [V5]
- [ ] **W22 Read-only.** `readonly\ro.txt`: delete, overwrite by upload and edit are refused, each with the reason. Delete `readonly\mixed`: Filemat reports `ro.txt` and deletes the other two. Copy `ro.txt`: the copy is read-only too (`attrib`). [Phase 5]
- [ ] **W23 File in use.** Open a `.docx` in Word, or lock any file from PowerShell: `$f = [IO.File]::Open('C:\FmTest\tree\one\file.txt', 'Open', 'Read', 'None')`. In Filemat, move, delete and edit it: "File is in use". Close it (`$f.Close()`): the same actions work. [Phase 5]
- [ ] **W24 Long path.** Browse to the long folder from the script output. Open the text file, see the image and video thumbnails, play the video, download the folder as a zip, upload a file into it. [Q43]
- [ ] **W25 Large TIFF.** With `-BigTiff`: watch `java.exe` in Task Manager, then open the details panel of `media\big.tiff`. It shows a size of 12000 × 12000, and the memory of `java.exe` doesn't jump by about 430 MB (the size of the decoded image; the file itself is about 180 MB). The thumbnail of the same file (`/api/v1/file/image-thumbnail`) doesn't jump either since fixes 19 (it grew `java.exe` by about 1.3 GB before). [V4]
- [ ] **W26 Names Windows can't store.** Create a folder `a:b`, create a file `CON.txt`, rename a file to `name.`, rename a file to `NUL .txt`: each is refused with a clear message, and nothing appears on disk. [Phase 2]
- [ ] **W27 Junctions (blocking).** With `FM_FOLLOW_SYMBOLIC_LINKS` unset: `junction\parent\link` shows as a link and can't be entered. Copy `junction\parent` to `junction\parent-copy`: the copy reports "1 items could not be copied. Links to folders were not copied.", and `parent-copy` has `file.txt` but nothing named `link`. Move `parent-copy` to `D:\FmTest2` (the folder exposed in section 2; the fixtures script doesn't create it), delete it there, then delete `junction\parent`. After each step, `junction\target` still has `keep1.txt` and `sub\keep2.txt`. [Phase 0, 5, R18]
- [ ] **W28 Folder upload through a junction (blocking).** On your PC, make a folder `samename` with a file `planted.txt`. Upload the folder into `C:\FmTest\junction\upload`: refused. `junction\target` has no `planted.txt`. [fixes 1]
- [ ] **W29 Symbolic links.** Needs the `links` fixtures. `local-link.txt` shows as a link. Run `netstat -an | findstr 192.0.2.1` in a loop (`for /l %i in (1,0,2) do @netstat -an | findstr 192.0.2.1`) while you open `C:\FmTest\links` in Filemat, then restart Filemat with `FM_FOLLOW_SYMBOLIC_LINKS=true` and open it again. The listing loads at once, `network-link` can't be opened, and `netstat` never shows a connection to `192.0.2.1:445`. [V10]
- [ ] **W30 Hard links.** Give `tester` access to `hardlink\original.txt`. Setting a permission on `second.txt` shows the path-only warning. [Phase 3]

## 4. Windows: protected folders

Expose `C:\` for these, and remove it afterwards.

- [ ] **W31 Sensitive folders (blocking).** `C:\Users\<you>\.ssh` (create it if missing), `C:\Windows\System32\config` and `C:\$Recycle.Bin` don't show and can't be opened by URL. `C:\Windows` and `C:\Users` can't be deleted or moved. `%LOCALAPPDATA%\Filemat` can't be opened when it is the running data folder (the default one; with `FM_DATA_DIR` elsewhere only that folder is blocked, so start with the default to check this). [Phase 4]
- [ ] **W32 8.3 short names (blocking).** Restart Filemat with `FM_HIDDEN_FOLDER_PATHS=C:\FmTest\blocked\LongSecretFolder`. As `tester` with write access on `C:\FmTest`, in the console (with `post` defined, and the short name the script printed):
  - `await post('/api/v1/file/move', { path: 'C:/FmTest/blocked/LONGSE~1', newPath: 'C:/FmTest/blocked/stolen' })`
  - `await post('/api/v1/file/copy', { path: 'C:/FmTest/blocked/LONGSE~1', newPath: 'C:/FmTest/stolen' })`
  - `fetch('/api/v1/file/content?path=C:/FmTest/blocked/LONGSE~1/secret.txt').then(r => r.status)`

  None of them succeeds, and `LongSecretFolder` is still in place. Repeat the move with `C:/Users/<you>/SSH~1` (see `dir /x C:\Users\<you>`) while `C:\Users\<you>` is exposed. [V1]
- [ ] **W33 Filemat's own folders.** Settings → System: setting the upload folder or the thumbnail cache folder to `C:\`, to `%LOCALAPPDATA%` (it contains the data folder) or, when it is the running data folder (the default one), to `%LOCALAPPDATA%\Filemat` is refused. With `FM_DATA_DIR` elsewhere, `%LOCALAPPDATA%\Filemat` is an ordinary folder and is accepted. Start a 2 GB upload, change the upload folder to `C:\FmUploads` while it runs: the upload carries on or resumes. Put a file `keep.txt` in the thumbnail cache folder and click "Clear preview cache": `keep.txt` stays. [fixes 1, 4]
- [ ] **W34 Data folder through a junction.** Stop Filemat. `mklink /J C:\FmData-link %LOCALAPPDATA%\Filemat`, then start with `FM_DATA_DIR=C:\FmData-link`. Uploads finish and thumbnails are cached (files appear in `cache\thumbnails`). The data folder still can't be opened in Filemat, by either path. [fixes 1]

## 5. Windows: drives and cloud folders

- [ ] **W35 OneDrive (blocking if OneDrive is common for your users).** In a OneDrive folder, make `FmTest-OD` with a large image, a video, a PDF and a text file. Right-click → **Free up space**, so they show the cloud icon. Expose the folder. Listing it, searching it, and opening the details panel of each file leave the cloud icons in place (no download). No thumbnails show. Opening the image downloads it (green tick) and shows it. [Phase 5, V7]
- [ ] **W36 FAT32 and exFAT.** Without a USB drive, create two virtual disks in an administrator `diskpart`:

  ```
  create vdisk file=C:\fm-fat32.vhdx maximum=1024 type=expandable
  select vdisk file=C:\fm-fat32.vhdx
  attach vdisk
  convert mbr
  create partition primary
  format fs=fat32 quick label=FMFAT
  assign letter=T
  ```

  and the same with `fm-exfat.vhdx`, `fs=exfat` and letter `U`. Expose `T:\` and `U:\`. Upload a file to each. Setting a permission shows the path-only warning. Then in `diskpart`: `select volume T`, `remove letter=T`, `assign letter=V`. Reload Filemat: `T:` is gone and `V:` is not exposed. Detach with `select vdisk file=C:\fm-fat32.vhdx` and `detach vdisk`. [Phase 3]
- [ ] **W37 `subst`.** In the same normal `cmd` that starts Filemat: `subst S: C:\FmTest`. Open `S:/names/a#b.txt` by URL: it opens and shows as `C:\FmTest\names\a#b.txt`. A permission on `C:\FmTest\names` applies there too. `subst S: /d` afterwards. [Phase 4]
- [ ] **W38 Network shares (blocking).** In an administrator PowerShell: `New-SmbShare -Name FmShare -Path C:\FmTest -FullAccess $env:USERNAME`. In a normal `cmd`: `net use Z: \\localhost\FmShare`. `Z:` isn't in the drive list. Exposing `Z:\` and `\\localhost\FmShare` is refused, and opening them by URL fails. Starting with `FM_DATA_DIR=Z:\fm` stops with the network drive message. Remove with `net use Z: /delete` and `Remove-SmbShare FmShare`. [Phase 4]
- [ ] **W39 Controlled folder access.** Turn on Ransomware protection → Controlled folder access. Expose `Documents`. Uploading there is refused with a permission error. Allow `jre\bin\java.exe` as `docs/windows.md` says: it works. Turn the protection back off. [Phase 7]

## 6. Windows: interruptions and upgrades

- [ ] **W40 Stopped during an upload (blocking).** Start a 2 GB upload, and at about half: press Ctrl+C in the console. Start Filemat: it starts normally, and the upload resumes from where it was when the browser retries (or after you restart the upload of the same file). Repeat with closing the console window, and with ending the scheduled task. [Phase 5, V3]
- [ ] **W41 Upload state files.** Stop Filemat. In `%LOCALAPPDATA%\Filemat\uploads\uploads`, make a folder `bogus` with a file `info` holding any text. Start Filemat: it starts, starts without "Failed to clear expired TUS upload files" in the console or `logs\filemat.log` (the expired-upload cleanup runs at the start and then every 2 hours, so look right after the start), and new uploads work. The deserialization filter itself is covered by `SerialFilterTest`; W40 shows that real uploads still pass it. [V3]
- [ ] **W42 Upgrade (blocking).** With users, permissions, a share and saved files in place: stop Filemat, delete the app folder, unzip the new build in the same place, start it. Everything is still there. If the database changed version, a `filemat-server.db.v<n>-<date>.bak` is in the data folder, and `icacls` on it lists only your account, SYSTEM and Administrators. [Phase 3, fixes 4]

## 7. Windows: other setups

- [ ] **W43 Docker Desktop.** Run the Linux image with `-v C:\FmTest:/data`, expose `/data` and give `tester` access to `/data/tree/file.txt`. Restart the container, then `wsl --shutdown` and start Docker again. The permission is still on the file. If it isn't, the container log has the startup warning about the mount. [Q26]
- [ ] **W44 ARM64.** On an ARM64 PC with Windows 11: the arm64 zip starts, and a video thumbnail shows. Without one, mark it untested. [Phase 8]

---

## 8. Linux

Build: the `server-app.jar` from Preparation. Run it with `java -jar server-app.jar` as the account your install uses.

- [ ] **L1 Upgrade from `main` (blocking).** On an install from `main` with users, permissions, shares and saved files: stop it and back up the data folder (`sudo cp -a /var/lib/filemat /var/lib/filemat.before-windows`). Start the new JAR without `FM_DATA_DIR`. Check:
  - It uses `/var/lib/filemat`.
  - The log shows the backup and the V9 migration.
  - `ls -l /var/lib/filemat/*.bak` shows `-rw-------`.
  - `sqlite3 /var/lib/filemat/filemat-server.db "PRAGMA table_info(files)"` lists `path_key` and `file_key`, and no `inode`.
  - `sqlite3 /var/lib/filemat/filemat-server.db "SELECT path FROM files WHERE file_key IS NULL"` lists only files that no longer exist.
  - Users, permissions, shares and saved files all work.

  [Phase 3, V9 migration]
- [ ] **L2 Data folder rights (blocking).** `stat -c '%A %n' /var/lib/filemat /var/lib/filemat/filemat-server.db` shows `drwxr-x---` and `-rw-------`. `chmod 755 /var/lib/filemat` and restart: back to `drwxr-x---`. [fixes 4]
- [ ] **L3 Backups pruned.** `touch` three old files `filemat-server.db.v1-20200101-000000.bak` to `…v3…` in the data folder. Start a build that adds a migration: only the three newest `.bak` files are left. (Skip if no newer migration is at hand; a unit test covers it.) [fixes 4]
- [ ] **L4 Upload and cache folder refused.** Settings → System: setting the upload folder or the thumbnail cache folder to `/`, `/var/lib` or `/var/lib/filemat` is refused. If an older install stored one of these, the start says the upload service is off, and uploads work again once it's changed. [fixes 4]
- [ ] **L5 Search needs a session (blocking).** `curl -i -X POST http://localhost:8080/api/v1/file/search -d 'path=/&text=a'` returns 401. Search through a public share link still works. [fixes 2]
- [ ] **L6 Prefix look-alikes (blocking).** Make folders `a_b`, `a%b`, `aXb`, `foo` and `foobar` in an exposed folder, each with a file. Give `tester` access to `a_b` and `foo` only: `tester` can't open `a%b`, `aXb` or `foobar`. Share `foo` and save `foo/inside.txt`. Rename `foo` to `foo2`: the share and the saved file follow; `foobar` has no permission or share. [Phase 0]
- [ ] **L7 Names in URLs.** Files named `a#b`, `a?b`, `50%`, `a&b`, `a+b` and `a b`: open each, copy its URL into a new tab, reload, bookmark it. Each opens. [Phase 0]
- [ ] **L8 Video previews with javacv (blocking).** Video thumbnails and playback work. Settings → System → Video previews names the built-in FFmpeg. An `.avif` or `.heic` image gets a thumbnail. [Phase 6]
- [ ] **L9 Without the FFmpeg natives.** Start with `java -Dorg.bytedeco.javacpp.platform=linux-ppc64le -jar server-app.jar`. The server starts. With no `ffmpeg` on `PATH`, the console and the Video previews card say previews are off and why, and image thumbnails still work. With `ffmpeg` installed (`apt install ffmpeg`), the card names `/usr/bin/ffmpeg` and video thumbnails work through it. [Phase 0, 6]
- [ ] **L10 Playlists (blocking).** Make `private/secret.ts` (a red video: `ffmpeg -f lavfi -i color=c=red:s=320x240:d=3 -f mpegts secret.ts`) and `public/evil.m3u8`:

  ```
  #EXTM3U
  #EXT-X-VERSION:3
  #EXT-X-TARGETDURATION:3
  #EXTINF:3.0,
  ../private/secret.ts
  #EXT-X-ENDLIST
  ```

  Give `tester` access to `public` only. As `tester`, open `/api/v1/file/video-preview?path=<…>/public/evil.m3u8` and `/api/v1/file/content-metadata?path=<…>/public/evil.m3u8`: no red frame and no size or duration. Check that the test is real: `ffmpeg -i public/evil.m3u8 -frames:v 1 check.png` gives a red image. [V2]
- [ ] **L11 Large TIFF.** `ffmpeg -f lavfi -i color=c=blue:s=12000x12000 -frames:v 1 big.tiff`. Open its details panel while watching the Java process in `top`: the size shows, and the memory doesn't grow by about 430 MB (the size of the decoded image; the file is about 180 MB). The thumbnail of the same file doesn't jump either since fixes 19 (it grew the process by about 1.3 GB before). [V4]
- [ ] **L12 Upload state files.** As W41, in `<upload folder>/uploads`: the upload folder is the one set at setup (Admin → System shows it), by default `/tmp/filemat`, so `/tmp/filemat/uploads`. The cleanup runs at the start and then every 2 hours. [V3]
- [ ] **L13 Uploads resume after a restart (blocking).** As W40, with `kill -INT` on the Java process. This also checks that the new deserialization filter still lets real uploads through. [V3]
- [ ] **L14 Folder upload through a symbolic link.** `ln -s /some/other/folder samename` inside an exposed folder. Upload a local folder named `samename` into it: refused while `FM_FOLLOW_SYMBOLIC_LINKS` is unset. Nothing lands in `/some/other/folder`. [fixes 1]
- [ ] **L15 Two file systems.** `sudo mkdir /mnt/fm1 /mnt/fm2 && sudo mount -t tmpfs none /mnt/fm1 && sudo mount -t tmpfs none /mnt/fm2`, then create `a.txt` in each. `stat -c %i /mnt/fm1/a.txt /mnt/fm2/a.txt` usually shows the same number. Expose both. Give `tester` access to `/mnt/fm1/a.txt` and a share on `/mnt/fm2/a.txt`. Restart Filemat: both still exist and point at their own file. [Phase 3]
- [ ] **L16 Browsers without service workers.** Open Filemat in a Firefox private window, which refuses service workers: the app loads within about 3 seconds instead of spinning. [Phase 8]
- [ ] **L17 Folder download to a Windows PC.** On Linux, in an exposed folder: `mkdir clash && cd clash && touch 'a:b' 'a?b' CON.txt 'name.' 'NUL .txt' A.txt a.txt`. From Windows, download `clash` as a folder (not a zip) with Chrome or Edge. Seven files arrive: `a_b`, `a_b (1)`, `_CON.txt`, `name_`, `_NUL .txt`, and `A.txt` and `a.txt` with ` (1)` added to one of them (`a (1).txt`). Nothing overwrote another. [Phase 0, V9 fixes]
- [ ] **L18 Docker image.** Build and run the image as `docs/docker-deployment.md` says, with a bind mount. Setup, an upload and a video thumbnail work. [Phase 7]

---

## 9. Known limitations, not to be filed

- A folder's case sensitivity turned off or on while Filemat runs can leave a narrower permission inside it unapplied until the next start (V6).
- Deleting a video while its thumbnail is being made can report "File is in use" (FFmpeg opens it without share-delete).
- The drive list `/` itself shows no dates or size. The drives in it show their own.
- A junction can't be moved to another drive.
- Copying a folder skips the junctions and mount points inside it: nothing is made in their place, the rest is copied, and the copy reports "Links to folders were not copied." A junction on its own can't be copied. Symbolic links are copied as links.
- Search reads the names in a folder of OneDrive online-only files (but not their content).
- External FFmpeg output is capped at 256 MB per run, with up to 4 runs at once, so a crafted huge frame can push the heap past 1 GB (V13).
- When every FFmpeg run is taken, a preview or metadata request waits 2 seconds, then gets 503 "Video previews are busy. Try again in a moment." The preview shows as broken until the next try.
- A file that reuses the file key of a deleted one (an inode reused on Linux, rare on NTFS) gets that file's permissions back. This was also true before this branch.
- Changing the upload folder moves the `uploads` and `locks` folders inside the old one, even when they are a user's (an upload folder inside a user folder is allowed).

---

## 10. Running a chunk with an agent

One chunk per agent run. A whole plan in one run doesn't fit in a session. Pick the first chunk in the [Status](#status) table that still has empty rows, put its letter and check IDs into the prompt below, and run it with a coding agent in the repo on the right machine (chunks A to D on Windows, E on Linux or WSL).

| Chunk | Checks | Machine | Notes |
| --- | --- | --- | --- |
| A | W1, W3–W8, W11–W14 | Windows | Builds the zips, makes the fixtures. Run first. |
| B | W16–W19 | Windows | W17 needs the agent's browser |
| C | W20–W30 | Windows | Developer Mode on before the fixtures, for W29 |
| D | W31–W34, W37, W40–W42, W45 | Windows | |
| E | L1–L15 | Linux or WSL | Builds `main` for L1 |

The prompt (replace `<CHUNK>` and `<IDS>`):

```text
Run chunk <CHUNK> (checks <IDS>) of docs/windows-test-plan.md in this repo, and record the results in that file. Don't change any code, and don't commit or push.

Read AGENTS.md, then all of docs/windows-test-plan.md: the Progress section, section 0, the sections with your checks, and section 9 (known limitations, not failures). Background: docs/windows-overview.md and docs/windows.md.

Before anything else
- If Progress → Blockers lists an open blocker, check whether it is fixed in the code. If not, stop and say so.
- Work folder: make a new folder outside the repo, %TEMP%\fm-test on Windows or /tmp/fm-test on Linux. Keep everything there: the unzipped app, data folders, scripts, logs and test passwords.
- Build: Gradle needs JDK 21 (set JAVA_HOME; Gradle's own JDKs are in ~/.gradle/jdks). On Windows, in server/: .\gradlew.bat bootJar windowsDist -PreleaseVersion=0.0.0-test. On Linux: ./gradlew bootJar (strip CR from gradlew if the checkout came from Windows). Run builds in the background; they take minutes.
- Windows fixtures: if C:\FmTest is missing, run utilities\windows-test-fixtures.ps1 as section 0 says (add -BigTiff for chunk C). If it exists from an older run, delete it first with cmd /c "rmdir /s /q C:\FmTest". Keep the script's output (long path, short name).
- Run Filemat with FM_DATA_DIR set to a new data folder in the work folder, and --server.port=18080. Exceptions: a check about the default data folder may use it only if it doesn't exist yet (otherwise use yours and note it), and W12 is about port 8080.
- Gate: start Filemat twice on the same data folder. If the second start fails, stop and report.
- Accounts: set up admin and tester as section 0 says, with generated passwords kept in the work folder, never in the chat or the repo.
- Drive Filemat over its HTTP API (Python, curl or PowerShell). Use a browser tool only where a check is about what the page shows.

Limits
- No admin rights or elevation, no sudo unless it works without a password, no firewall, security or system settings, no installs, no signing into accounts.
- Don't change anything outside C:\FmTest (or the Linux test folders the checks name), the work folder and your data folders.
- A step you can't do within these limits makes the check PART or SKIP, with what's left for a person in Notes. Don't retry with more rights.
- Stop every Filemat process you started before you finish.

Recording, after each check (so the work survives being cut off)
- Set Status and a short Notes in the Status table of docs/windows-test-plan.md: PASS, FAIL, PART or SKIP, with the date and the machine (for example "2026-10-27, Win 11 x64").
- For each FAIL, add an entry under Failures found: steps, expected, actual, the log lines (logs\filemat.log in the data folder), and the likely code.
- Add a step of the plan that was wrong, unclear or impossible as written to Notes. Don't rewrite the check.

Final message: the counts per status, one line per FAIL, what is left for a person, and plan errors.
```

When a run ends, review the table changes (`git diff docs/windows-test-plan.md`), then commit and push them, so the other PC sees them.

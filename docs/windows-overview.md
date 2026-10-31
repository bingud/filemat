# Windows support: owner overview

A short summary for the maintainer of how the Windows support works. **What is left to do is in [windows-status.md](windows-status.md).** User docs are in [windows.md](windows.md), the full design and decisions in [windows-support-plan.md](windows-support-plan.md), and each change in [windows-support-changelog.md](windows-support-changelog.md). The manual tests are in [windows-test-plan.md](windows-test-plan.md).

## TL;DR

- **One JAR, two platforms.** The same JAR runs on Linux and Windows: `bootJar` builds `server\build\libs\server-app.jar`, which the releases and the Windows zip call `filemat.jar`. It checks the OS at startup (`Platform`) and picks the Windows or POSIX version of paths, ACLs, file identity and the block lists.
- **Windows ships as a portable zip**, x64 and arm64: unzip, run `filemat.cmd`, open `http://localhost:8080`. No installer, service, registry or firewall changes.
- **Data** goes to `%LOCALAPPDATA%\Filemat` (`FM_DATA_DIR` to change).
- **Video previews** use the bundled `ffmpeg.exe` on Windows, and javacv's built-in FFmpeg on Linux.
- **Beta.** Tested on Windows 10 22H2 x64: the zip was built and run through setup and the main flows on 2026-10-25, and every check of the manual test plan that needs no person ran on 2026-10-26 (W37 failed and was fixed; its Status table has the rest). The checks that need a person (admin rights, OneDrive, network shares, a second device) haven't run. [windows-status.md](windows-status.md) lists what is still open. ARM64 is built but untested. Unsigned.

## How it's built

`.\gradlew.bat windowsDist -PreleaseVersion=1.2.3` (Windows only, JDK 21 for Gradle), or `.\utilities\build.ps1 -ImageVersion 1.2.3 -Windows`. Steps per arch (`x64`, `arm64`):

1. `bootJar` builds the web app with npm and puts it in the JAR.
2. Download the pinned Temurin 21.0.12.1 JDK zip and the pinned BtbN FFmpeg 7.1.5 GPL zip from GitHub, check their SHA-256. They are cached in `server\build\windows\downloads`, so later builds don't download again.
3. **jlink**: the x64 JDK's `jlink.exe` builds a trimmed runtime (about 23 modules) from the target arch's `jmods`. That's how an arm64 runtime is built on an x64 PC. jlink needs the exact same JDK version for both, so Temurin is pinned rather than picked by Gradle.
4. Zip `filemat\` with `filemat.cmd` (CRLF), `filemat.jar`, `jre\`, `ffmpeg\ffmpeg.exe` and `LICENSES\` into `server\build\dist\filemat-<v>-windows-<arch>.zip`.

Bumping: change `ffmpegRelease` and the FFmpeg build names and hashes, or `temurinRelease` and the JDK zip names and hashes, at the end of `server/build.gradle.kts`.

## How it runs

`filemat.cmd` sets `FM_FFMPEG_PATH` to the bundled `ffmpeg.exe` (unless already set), warns when the folder name can't be read by Java (below), switches the console to UTF-8, and runs `jre\bin\java.exe -jar filemat.jar` with the arguments passed on.

At startup the server:

- Creates the data folder and limits its ACL to the user, SYSTEM and Administrators, protected from the parent's. A data folder that already existed is left as it is (it could be a shared folder or a whole drive), with a warning when other accounts can open it. The database is limited on every start, and the `native` and `logs` folders when they are created. An ACL that is already private (the default folder inherits one from the profile) is left as it is; an empty one (a NULL DACL, everyone allowed) is not private.
- Exits if the data folder is on a network path (SQLite corrupts there).
- Warns loudly when running as an administrator or SYSTEM.
- Copies the console output to `<data>\logs\filemat.log`, rotated.
- On a port that can't be opened, explains Windows' reserved port ranges.

Native Windows calls (elevation, file IDs, drive types, case sensitivity, reparse tags, Known Folders) go through **JNA**. JNA's and SQLite's DLLs are extracted to `<data>\native`. A failed call counts as "can't verify" and refuses: an unknown drive type is treated as a network drive. When JNA can't load at all, Filemat stops at startup with the reason, since it can't check the data folder's drive.

## Paths

- On the wire paths are `C:/Users/x`. They show as `C:\Users\x`. `/` is a virtual folder listing the local drives.
- `WindowsPathPolicy` is the one place that parses paths. It refuses alternate data streams, device names (`CON`, `NUL.txt`), trailing dots or spaces, `<>:"|?*` and drive-relative paths.
- **Case:** each stored path has a `path_key`. On Windows a name is upper-cased unless its folder is case-sensitive, so `a.txt` and `A.txt` are one file in a normal folder and two in a case-sensitive one.
- **File identity:** permissions follow a file by `file_key`, the volume serial plus the NTFS/ReFS file ID (device plus inode on Linux). FAT, exFAT and network drives have no stable ID, so their permissions follow the path only, and the UI warns.
- Database migration V9 adds both columns and backs the database up first. It also drops the old `inode` column.

## FFmpeg and other binaries

| Binary | Where it comes from | Used on |
| --- | --- | --- |
| javacv FFmpeg natives | Maven, inside the JAR | Linux only. Loading fails on Windows, which is caught. |
| `ffmpeg\ffmpeg.exe` | BtbN GPL build, pinned and checksum-checked | Windows. Run as a separate process. |
| `jre\` | Temurin 21, trimmed with jlink | Windows zip |
| JNA, sqlite-jdbc natives | Maven, inside the JAR, extracted at runtime | Both, x64 and arm64 |

The external FFmpeg is found from `FM_FFMPEG_PATH`, then `PATH`. It runs:

- without a shell, with the video as a local file path, and only for video containers and single images, so a playlist can't make it open URLs or other local files;
- with a 30 s timeout and a few processes at a time;
- never as a `.cmd`, `.bat` or relative path.

**Admin → System** shows which FFmpeg is used or why previews are off.

The zip is GPL-3 because of FFmpeg. `LICENSES\THIRD-PARTY.txt` names the exact builds and their sources.

## What had compatibility issues

| Problem | Fix |
| --- | --- |
| Paths assumed `/` everywhere (server, SQL, web) | `PathPolicy` on the server and `path.ts` in the web app. Prefix SQL no longer uses `LIKE`, where `_`, `%` and case misbehaved. |
| Case-insensitive names | `path_key`, per-folder case sensitivity, and case-only renames |
| Inodes don't exist | `file_key` from the NTFS file ID |
| POSIX permissions | Windows ACLs (`AclPolicy`) |
| Junctions were recursed into, so deleting a folder could empty the junction's target | Junctions and mount points are treated as links and never recursed into |
| Files in use (locked by Word and others) | Brief retry, then 409 "File is in use" |
| Read-only attribute | Kept. Delete, edit and overwrite are refused. |
| Java's case-only move silently did nothing | Its own rename path |
| javacv FFmpeg doesn't load | External `ffmpeg.exe` |
| Sensitive folders differ (registry hives, DPAPI, browser profiles, `$Recycle.Bin`) | Windows block lists from Known Folders |
| Network shares and mapped drives | Refused. `subst` drives resolve to their real folder for the checks, and a file opened through one shows its real path. |
| Port 8080 reserved by Hyper-V, WSL or Docker | Startup hint and `--server.port` |
| Console code page garbles names | `chcp 65001` and UTF-8 stdout |
| Filenames with `#`, `%` or `&` in page URLs (also a Linux bug) | Each path segment encoded |
| Docker Desktop bind mounts change inode numbers | Startup warning |
| Java reads its own folder in the ANSI code page, so it can't start from a folder with letters outside it (Chinese on an English Windows) | Can't be fixed from outside Java. `filemat.cmd` warns instead of failing with "could not find java.dll". |
| The Windows upload and thumbnail folders are inside the data folder, which is blocked for users, and Filemat blocked itself too | Filemat's own working files (finished uploads, the thumbnail cache) skip the user checks, but only inside the real upload and cache folders. The database and the rest of the data folder stay out of reach. |
| Junctions look like folders even when links are not followed, so a folder upload could write through one (into the data folder through `Local Settings`, say) | Every folder an upload writes into is resolved first and checked on its real path. A link is refused unless links are followed. |
| A symbolic link to `\\server\share` makes Windows connect and send credentials when it's followed | Link targets are read without following them, and such links are never followed. |
| 8.3 short names (`SSH~1` for `.ssh`) name the same file, but the block lists compare names | Paths are resolved to their real names before the checks, also where a link itself is moved, copied or deleted. |

## How a user sets it up

1. Download `filemat-<v>-windows-x64.zip` (or `-arm64`). In **Properties**, tick **Unblock**.
2. Unzip anywhere (a folder name in letters of the Windows language) and run `filemat.cmd` as a normal user, not an administrator.
3. Open `http://localhost:8080` and enter the setup code from the console or `%LOCALAPPDATA%\Filemat\setup-code.txt`.
4. Allow `java.exe` on **Private** networks when Windows asks, to use Filemat from other devices.
5. Optional:
   - `FM_*` environment variables (lists separated by `;`).
   - A Task Scheduler task for auto-start.
   - `icacls` to grant the account access to more folders.
   - Allowing `java.exe` in Defender's Controlled folder access.

Upgrade: stop Filemat, replace the folder, start it again. The data folder stays.

## Not done

Service mode, installer (MSI), code signing, network shares (UNC), Recycle Bin, moving a database between Linux and Windows, an ARM64 test, and an external security review.

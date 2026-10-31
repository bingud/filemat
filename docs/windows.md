# Filemat on Windows (beta)

Windows support is a beta. It is tested on Windows 10 22H2 x64. Windows 11 should work. The ARM64 zip is built the same way but not tested. Windows Server is not tested.

## Running the portable zip

1. Download `filemat-<version>-windows-x64.zip` from the releases, or `filemat-<version>-windows-arm64.zip` for Windows on ARM (for example Snapdragon laptops).
2. Right-click the zip, choose **Properties**, tick **Unblock** and click OK. Otherwise SmartScreen may warn about `java.exe` and `ffmpeg.exe`, because the zip is not signed.
3. Unzip it anywhere, for example to `C:\Apps\filemat`. Java can't start from a folder whose path has letters that Windows' language settings don't cover, such as Chinese letters on an English Windows; `filemat.cmd` then shows a warning. Letters of your own language are fine.
4. Run `filemat.cmd` in a normal (not administrator) window. Filemat listens on port `8080`: open `http://localhost:8080`.

The zip contains:

| Path | Description |
| --- | --- |
| `filemat.cmd` | Start script |
| `filemat.jar` | Filemat |
| `jre\` | Java 21 runtime (Eclipse Temurin) |
| `ffmpeg\ffmpeg.exe` | FFmpeg for video previews |
| `LICENSES\` | License texts and source links |

There is no installer. Filemat adds no service, registry entries or firewall rules.

Arguments are passed to Filemat, for example `filemat.cmd --server.port=9090`.

You can also run the plain JAR from the releases with your own Java 21: `java -jar filemat.jar`. Video previews then need FFmpeg on `PATH`, or `FM_FFMPEG_PATH` (see below).

### Run as a normal user

Filemat runs as the user who starts it, and can read and change what that user can. It warns at startup when it runs as an administrator or as SYSTEM, because then it can read every user's files.

To give it access to a folder that the user can't read, grant access with `icacls` in an administrator window, for example:

```
icacls "D:\Shared" /grant "%USERNAME%:(OI)(CI)M"
```

Run it from the account that runs Filemat, or replace `%USERNAME%` with that account. `M` is modify access; use `RX` for read-only.

## Data folder

Filemat keeps its database, setup code, logs, uploads and thumbnail cache in `%LOCALAPPDATA%\Filemat` (for example `C:\Users\you\AppData\Local\Filemat`). Set `FM_DATA_DIR` to use another folder. It must be on a local drive: SQLite can corrupt a database on a network share.

| Path | Description |
| --- | --- |
| `filemat-server.db` | SQLite database |
| `setup-code.txt` | Setup code for the first start |
| `auth-code.txt` | Code for sensitive admin actions |
| `logs\filemat.log` | Console output. Rotates at 10 MB and keeps 9 older files (`filemat.1.log` is the newest) |
| `uploads\` | Default upload folder |
| `cache\thumbnails\` | Default thumbnail cache folder |
| `native\` | DLLs of JNA (Windows system calls) and SQLite |

When Filemat creates the data folder, it limits it to the current user, SYSTEM and Administrators, and what goes inside follows. A folder that already existed (`FM_DATA_DIR`) keeps its permissions, and Filemat warns at startup when other accounts can open it; the database is limited either way. Users can't open or change it through Filemat. Filemat itself only touches its uploads and thumbnails there.

The upload folder and the thumbnail cache folder (Admin → System) can't be a drive, the data folder or a folder that contains it. Changing the upload folder moves the uploads in progress along when the new folder is on the same drive. Deleting or moving the thumbnail cache only touches thumbnail files.

## Configuration

Filemat is configured with environment variables, as on Linux ([full list](/docs/configuration.md)). Lists use `;` between values on Windows, for example `C:\Private;D:\Backups`.

Set them for your user in **Settings → System → About → Advanced system settings → Environment Variables**, with `setx` (new windows only), or in a small script that calls `filemat.cmd`:

```bat
@echo off
set "FM_HIDDEN_FOLDER_PATHS=C:\Private;D:\Backups"
call "C:\Apps\filemat\filemat.cmd"
```

In PowerShell, set them for the current window with `$env:FM_FOLLOW_SYMBOLIC_LINKS = "true"`.

Environment variables only on Windows:

| Name | Description | Default |
| --- | --- | --- |
| `FM_DATA_DIR` | Application data folder | `%LOCALAPPDATA%\Filemat` |
| `FM_FFMPEG_PATH` | Full path of `ffmpeg.exe` | `ffmpeg\ffmpeg.exe` in the zip (set by `filemat.cmd`), otherwise `ffmpeg.exe` on `PATH` |

`FM_DATA_DIR` and `FM_FFMPEG_PATH` work on Linux too.

## Video previews

The FFmpeg built into the JAR only works on Linux. On Windows, Filemat runs `ffmpeg.exe`: the one from `FM_FFMPEG_PATH`, or else the first one on `PATH`. **Admin → System** shows whether video previews work and which FFmpeg is used.

FFmpeg only gets files as local paths, only opens video files and single images (not playlists, which could point it at URLs or other files), and is stopped after 30 seconds.

## Starting with Windows

Filemat has no service mode yet. To start it automatically, create a Task Scheduler task:

1. Open **Task Scheduler** and choose **Create Task**.
2. **General**: run as the user that should run Filemat. For a start before anyone logs in, choose **Run whether user is logged on or not**. Don't tick **Run with highest privileges**.
3. **Triggers**: **At log on** of that user, or **At startup**.
4. **Actions**: **Start a program**, the program `C:\Apps\filemat\filemat.cmd`, and **Start in** `C:\Apps\filemat`.
5. **Settings**: untick **Stop the task if it runs longer than 3 days**.

With **Run whether user is logged on or not**, there is no console window. Read `logs\filemat.log` in the data folder instead.

## Network access and the firewall

The first time Filemat starts, Windows asks whether `java.exe` may accept connections. Allow **Private networks** to use Filemat from other devices at home. Filemat never adds firewall rules itself.

To add the rule by hand, run this in an administrator PowerShell:

```powershell
New-NetFirewallRule -DisplayName "Filemat" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private
```

### "Windows system calls don't work"

Filemat calls Windows through JNA, which unpacks a DLL to `native\` in the data folder. When AppLocker or WDAC blocks DLLs there, Filemat stops with this message, because it can't tell local drives from network drives without it. Allow DLLs to load from that folder, or point JNA at a folder where they may, for example `set "JAVA_TOOL_OPTIONS=-Djna.tmpdir=C:\Apps\filemat\native"` before `filemat.cmd`.

### Port 8080 doesn't work

Hyper-V, WSL and Docker reserve ranges of ports, and 8080 is sometimes in one. Filemat then stops with a message that the port could not be used. See the reserved ranges with:

```
netsh int ipv4 show excludedportrange protocol=tcp
```

Start Filemat on another port with `filemat.cmd --server.port=9090`, or set `SERVER_PORT=9090`.

## Microsoft Defender

- **Controlled folder access** (ransomware protection) blocks unknown programs from changing Documents, Pictures, Desktop and similar folders. Filemat then can't save, move or delete files there, and reports a missing permission. Allow `jre\bin\java.exe` from the Filemat folder under **Windows Security → Virus & threat protection → Ransomware protection → Allow an app through Controlled folder access**.
- Real-time scanning can make uploads and thumbnails slower. You can exclude the data folder (`%LOCALAPPDATA%\Filemat`) in the Defender settings. Don't exclude the folders you share.

## Upgrading and backups

1. Stop Filemat (Ctrl+C in its window, or end the task). After Ctrl+C, `cmd` asks "Terminate batch job (Y/N)?": `N` puts the console back to its old code page, `Y` leaves it at UTF-8 (65001) until you close the window or run `chcp` yourself.
2. Unzip the new version next to the old one, or replace the old folder. Keep the old folder until the new version works.
3. Start the new `filemat.cmd`.

The data folder doesn't move. A database migration first backs up the database in the data folder (`filemat-server.db.v<n>-<time>.bak`, as private as the database). The three newest backups are kept.

To back up while Filemat runs, copy the database with SQLite's `VACUUM INTO`, for example with the `sqlite3` tool:

```
sqlite3 "%LOCALAPPDATA%\Filemat\filemat-server.db" "VACUUM INTO 'D:\Backups\filemat-server.db'"
```

Or stop Filemat and copy the whole data folder.

## How Windows files behave

- **Paths** are shown with backslashes (`C:\Users\you\Documents`). The top folder lists the drives. It can't be searched and has no folder size, so open a drive first.
- **Case:** Windows names are case-insensitive, so `Report.txt` and `report.txt` are the same file. Folders with case sensitivity turned on (`fsutil file setCaseSensitiveInfo`) work too.
- **Read-only files** stay read-only. Filemat doesn't edit, overwrite or delete them; remove the attribute in Explorer first. Copies keep the attribute.
- **Files in use:** when another program has a file open, Filemat tries again for a moment and then reports that the file is in use.
- **Delete is permanent.** Files don't go to the Recycle Bin.
- **Hidden and system files** are shown like other files. `$Recycle.Bin`, `System Volume Information`, `pagefile.sys`, `hiberfil.sys` and `swapfile.sys` in a drive's top folder are always hidden.
- **Links:** symbolic links, junctions and mount points are not followed unless `FM_FOLLOW_SYMBOLIC_LINKS=true`. That includes uploads: a folder upload can't write through a junction, and with links followed, the junction's target is checked like any folder. Shortcuts (`.lnk`) are ordinary files.
- **Names** that Windows can't store are refused: `<>:"/\|?*`, names ending in a dot or space, and device names such as `CON` or `NUL.txt`.

## Known limitations

- **Network shares and mapped drives** (`\\server\share`, or a drive letter mapped to one) are not supported. `subst` drives work, and the permission checks use their real folder.
- **FAT32 and exFAT drives** (most USB sticks and SD cards): permissions and shares follow the path only. When a file there is renamed or moved outside Filemat, its permissions don't follow it. Filemat warns when you set a permission on such a drive.
- **A changed drive letter** (for example a USB drive that comes back as `F:` instead of `E:`) un-exposes its folders. Expose them again under the new letter.
- **OneDrive online-only files:** Filemat doesn't make thumbnails of them or read their content to detect the file type, so it doesn't download them. Opening or downloading one downloads it through OneDrive. Searching a folder of them reads names only.
- A database can't be moved from a Linux server to Windows or back, because it stores full paths.
- No service mode, installer or Recycle Bin yet.

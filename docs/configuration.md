# Configuration Details

## Environment Variables

Input multiple values by putting a colon in between (`/one:/two`). On Windows, use a semicolon (`C:\One;D:\Two`).

| Name | Description | Default | Example |
| --- | --- | --- | --- |
| `FM_DATA_DIR` | Application data folder. Must be on a local drive | `/var/lib/filemat`, Windows: `%LOCALAPPDATA%\Filemat` | `/srv/filemat-data` |
| `FM_FFMPEG_PATH` | Full path of an `ffmpeg` executable for video previews. Used where the FFmpeg built into Filemat doesn't load (Windows). When set, no other FFmpeg is tried | `ffmpeg` on `PATH`. The Windows zip sets its own | `C:\ffmpeg\bin\ffmpeg.exe` |
| `FM_DEV_MODE` | Whether Filemat is in development mode | `false` | `true` |
| `FM_HIDE_SENSITIVE_FOLDERS` | Whether to hide sensitive system folders | `true` | `false` |
| `FM_NON_SENSITIVE_FOLDERS` | Folders to exclude from sensitive folder list | (empty) | `/root:/etc/ssh` |
| `FM_HIDDEN_FOLDER_PATHS` | Folder paths to fully exclude and block. Full paths only: `*` is not a wildcard here | (empty) | `/root:/home/folder` |
| `FM_FORCE_DELETABLE_FOLDERS` | Protected folders to make deletable. Full paths only: `*` is not a wildcard here | (empty) | `/root:/etc` |
| `FM_ALLOW_READ_DATA_FOLDER` | Allow reading the application data folder | `false` | `true` |
| `FM_ALLOW_WRITE_DATA_FOLDER` | Allow modifying the application data folder | `false` | `true` |
| `FM_FOLLOW_SYMBOLIC_LINKS` | Whether to follow symbolic links | `false` | `true` |
| `FM_CONTENT_BASE_URL` | Absolute prefix for file content, uploads, and thumbnails | (empty) | `https://203.0.113.10:8080` |
| `FM_CONTENT_BASE_URL_FOR_UNAUTHENTICATED` | Also send the content base URL to unauthenticated users | `false` | `true` |
| `FM_PRINT_LOGS` | Whether to print logs to console | `true` | `false` |

Spring Boot settings work too, for example `SERVER_PORT=9090` for another port.

## Security Defaults

### Sensitive Folder Paths

These paths are blocked from access when `FM_HIDE_SENSITIVE_FOLDERS=true` (default). The Filemat data folder is always blocked, unless `FM_ALLOW_READ_DATA_FOLDER` is set.

#### Linux

| Path | Description |
| --- | --- |
| `/etc/shadow` | Password hashes |
| `/etc/sudoers` | Sudo configuration |
| `/etc/ssh` | SSH configuration |
| `/etc/gshadow` | Group password hashes |
| `/root` | Root user home |
| `/home/*/.ssh` | User SSH keys |
| `/home/*/.gnupg` | GPG keys |
| `/etc/ssl/private` | SSL private keys |
| `/var/lib/mysql` | MySQL data |
| `/var/lib/postgresql` | PostgreSQL data |
| `/var/lib/docker` | Docker data |
| `/var/run/docker.sock` | Docker socket |
| `/var/lib/filemat` | Filemat data folder |

#### Windows

The Windows, ProgramData, Program Files and Users folders are read from the system, so they are right on any drive. `*` matches one folder name, and `*:` any drive. Names match in any case.

| Path | Description |
| --- | --- |
| `C:\Windows\System32\config` | Registry hives with password hashes, and the system account's profile |
| `C:\Windows\System32\Microsoft\Protect` | The system's DPAPI keys |
| `C:\Windows\NTDS` | Active Directory database (domain controllers) |
| `C:\Windows\repair`, `C:\Windows\Panther`, `C:\Windows\System32\Sysprep` | Registry backups and setup files, which can hold admin passwords |
| `C:\Windows\ServiceProfiles` | Profiles of service accounts |
| `C:\Windows\System32\inetsrv\config` | IIS configuration |
| `C:\Windows\MEMORY.DMP`, `C:\ProgramData\Microsoft\Windows\WER` | Crash dumps, which hold memory contents |
| `C:\Windows\CSC` | Offline Files: local copies of network files |
| `C:\ProgramData\Microsoft\Crypto` | Machine private keys |
| `C:\ProgramData\Docker`, `C:\ProgramData\MySQL`, `C:\Program Files\PostgreSQL\*\data` | Database and Docker data |
| `C:\ProgramData\ssh` | OpenSSH server host keys |
| `C:\Users\*\NTUSER.DAT*` | User registry hives |
| `C:\Users\*\.ssh`, `.gnupg`, `.aws`, `.azure`, `.kube`, `.docker` | User keys and cloud credentials |
| `C:\Users\*\.git-credentials`, `_netrc`, `.netrc` | Saved passwords of Git and other tools |
| `C:\Users\*\AppData\Roaming\Microsoft\Credentials`, `Protect`, `Crypto`, `SystemCertificates` | Saved credentials and the DPAPI keys that decrypt them |
| `C:\Users\*\AppData\Local\Microsoft\Credentials`, `Vault` | Saved credentials |
| `C:\Users\*\AppData\Local\Microsoft\TokenBroker`, `IdentityCache` | Microsoft account and work account sign-in tokens |
| `C:\Users\*\AppData\Local\Google\Chrome\User Data`, `AppData\Local\Microsoft\Edge\User Data`, `AppData\Roaming\Mozilla\Firefox\Profiles`, `AppData\Local\BraveSoftware`, `AppData\Local\Chromium`, `AppData\Local\Vivaldi`, `AppData\Roaming\Opera Software` | Browser profiles with saved passwords and cookies |
| `C:\Users\*\AppData\Roaming\Thunderbird\Profiles` | Mail profiles with saved passwords |
| `C:\Users\*\AppData\Roaming\Microsoft\Windows\PowerShell\PSReadLine` | PowerShell command history, which can hold typed secrets |
| `*:\System Volume Information`, `*:\$Recycle.Bin`, `*:\$Extend` | Shadow copies, every user's deleted files, file system data |
| `*:\pagefile.sys`, `*:\hiberfil.sys`, `*:\swapfile.sys`, `*:\DumpStack.log*` | Memory written to disk |
| `%LOCALAPPDATA%\Filemat` | Filemat data folder |

The rest of `AppData` is not blocked.

Use `FM_NON_SENSITIVE_FOLDERS` to exclude paths from this list. Write them as in the list, for example `C:/Users/*/.ssh` or `C:\Users\*\.ssh`.

Use `FM_HIDDEN_FOLDER_PATHS` to add additional blocked paths. Give each folder's full path: `*` is not a wildcard there, and Filemat prints a warning at startup for each entry that has one. The same goes for `FM_FORCE_DELETABLE_FOLDERS`. Only `FM_NON_SENSITIVE_FOLDERS` takes `*`, because it names entries of the list above.

### Non-Deletable System Paths

These paths cannot be deleted through Filemat (unless overridden with `FM_FORCE_DELETABLE_FOLDERS`):

Linux:

- `/`, `/bin`, `/sbin`, `/lib`, `/lib64`
- `/usr`, `/etc`, `/boot`, `/dev`
- `/proc`, `/sys`, `/var`, `/run`
- `/opt`, `/srv`, `/root`, `/home`

Windows:

- every drive (`C:\`, `D:\`) and the drive list
- `C:\Windows`, `C:\ProgramData`, `C:\Program Files`, `C:\Program Files (x86)`
- `C:\Users` and every profile in it, including `Default` and `Public`
- `C:\Recovery`, `C:\Boot`, `C:\EFI`, `C:\PerfLogs`

## Role permissions

Roles grant system permissions to users. Users can also get per-file permissions on individual files.

### System permissions

| Permission | Level | What it allows |
| --- | --- | --- |
| `ACCESS_ALL_FILES` | 2 | Read, edit, share, delete, move and rename any file, ignoring per-file permissions. Blocked/sensitive paths, the Filemat data folder and non-deletable system folders still apply |
| `MANAGE_OWN_FILE_PERMISSIONS` | 1 | View and edit the permissions of files the user owns. Also allows listing users to pick who to grant access to |
| `MANAGE_ALL_FILE_PERMISSIONS` | 1 | View and edit the permissions of any file |
| `MANAGE_ALL_FILE_SHARES` | 1 | View and manage public share links created by any user |
| `CHANGE_OWN_HOME_FOLDER` | 1 | Set the path of their own home folder |
| `EXPOSE_FOLDERS` | 1 | Not enforced by the server yet (exposing folders is currently covered by `MANAGE_SYSTEM`) |
| `MANAGE_USERS` | 3 | Admin user management: list, create, delete, edit users, change passwords, reset MFA, assign and remove user roles |
| `MANAGE_SYSTEM` | 3 | Admin system settings (thumbnails, file visibility, symbolic links, upload folder, content base URL) and the audit/system logs |
| `EDIT_ROLES` | 3 | Create and delete roles and change their permissions |
| `SUPER_ADMIN` | 4 | Everything: passes every permission check and has the same file access as `ACCESS_ALL_FILES` |

### File permissions

Granted to a user or role on a specific file or folder.

| Permission | What it allows |
| --- | --- |
| `READ` | View, open and download the file |
| `WRITE` | Edit or modify the file |
| `DELETE` | Delete the file |
| `SHARE` | Create public share links for the file |
| `RENAME` | Rename the file |
| `MOVE` | Move the file |

### Permission levels

Each system permission has a level (shown above). Users can only create, edit or delete roles, and assign roles or edit users, whose highest permission level is not above their own highest level. When editing a role, they also cannot grant a permission they do not have themselves.

### Built-in roles

- `admin`: always has every system permission (reset on each startup). Cannot be deleted.
- `user`: has no system permissions by default. Cannot be deleted.

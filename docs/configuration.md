# Configuration Details

## Environment Variables

Input multiple values by putting a colon in between (`/one:/two`).

| Name | Description | Default | Example |
| --- | --- | --- | --- |
| `FM_DEV_MODE` | Whether Filemat is in development mode | `false` | `true` |
| `FM_HIDE_SENSITIVE_FOLDERS` | Whether to hide sensitive Linux folders | `true` | `false` |
| `FM_NON_SENSITIVE_FOLDERS` | Folders to exclude from sensitive folder list | (empty) | `/root:/etc/ssh` |
| `FM_HIDDEN_FOLDER_PATHS` | Folder paths to fully exclude and block | (empty) | `/root:/home/folder` |
| `FM_FORCE_DELETABLE_FOLDERS` | Protected folders to make deletable | (empty) | `/root:/etc` |
| `FM_ALLOW_READ_DATA_FOLDER` | Allow reading the application data folder | `false` | `true` |
| `FM_ALLOW_WRITE_DATA_FOLDER` | Allow modifying the application data folder | `false` | `true` |
| `FM_FOLLOW_SYMBOLIC_LINKS` | Whether to follow symbolic links | `false` | `true` |
| `FM_CONTENT_BASE_URL` | Absolute prefix for file content, uploads, and thumbnails | (empty) | `https://203.0.113.10:8080` |
| `FM_CONTENT_BASE_URL_FOR_UNAUTHENTICATED` | Also send the content base URL to unauthenticated users | `false` | `true` |
| `FM_PRINT_LOGS` | Whether to print logs to console | `true` | `false` |

## Security Defaults

### Sensitive Folder Paths

These paths are blocked from access when `FM_HIDE_SENSITIVE_FOLDERS=true` (default).

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

Use `FM_NON_SENSITIVE_FOLDERS` to exclude paths from this list.

Use `FM_HIDDEN_FOLDER_PATHS` to add additional blocked paths.

### Non-Deletable System Paths

These paths cannot be deleted through Filemat (unless overridden with `FM_FORCE_DELETABLE_FOLDERS`):

- `/`, `/bin`, `/sbin`, `/lib`, `/lib64`
- `/usr`, `/etc`, `/boot`, `/dev`
- `/proc`, `/sys`, `/var`, `/run`
- `/opt`, `/srv`, `/root`, `/home`

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

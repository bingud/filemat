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
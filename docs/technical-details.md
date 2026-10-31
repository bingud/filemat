# Technical Details

## Application Paths

The data folder is `FM_DATA_DIR`, or by default `/var/lib/filemat` on Linux and `%LOCALAPPDATA%\Filemat` on Windows.

| Linux | Windows | Description |
| --- | --- | --- |
| `/var/lib/filemat` | `%LOCALAPPDATA%\Filemat` | Application data folder |
| `<data folder>/filemat-server.db` | same | SQLite database file |
| `<data folder>/setup-code.txt` | same | Initial setup code file |
| `<data folder>/auth-code.txt` | same | Sensitive action auth code file |
| `/var/cache/filemat` | `<data folder>\cache\thumbnails` | Default thumbnail cache folder |
| `/tmp/filemat` | `<data folder>\uploads` | Default upload folder |
| (stdout only) | `<data folder>\logs\filemat.log` | Console output, rotated at 10 MB, 9 older files kept |
| - | `<data folder>\native` | SQLite's native library |

## Server

| Property | Value |
| --- | --- |
| Default port | `8080` |
| API prefix | `/api/v1/` |
| Database | SQLite |
| ORM | Spring Data JDBC |

## Authentication

| Property | Value |
| --- | --- |
| Type | Cookie-based session tokens |
| Auth cookie | `filemat-auth-token` |
| Token max age | ~1 year (31,557,600 seconds) |
| Cookie security | `Secure`, `HttpOnly`, `SameSite=Lax` |

## Password Hashing

Algorithm: **Argon2**

| Parameter | Value |
| --- | --- |
| Salt length | 16 bytes |
| Hash length | 32 bytes |
| Parallelism | 2 |
| Memory | 64 MB (65,536 KB) |
| Iterations | 5 |

## File Upload

| Property | Value |
| --- | --- |
| Protocol | TUS (resumable uploads) |
| Endpoint | `/api/v1/file/upload` |
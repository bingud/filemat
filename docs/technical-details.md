# Technical Details

## Application Paths

| Path | Description |
| --- | --- |
| `/var/lib/filemat` | Application data folder |
| `/var/lib/filemat/filemat-server.db` | SQLite database file |
| `/var/lib/filemat/setup-code.txt` | Initial setup code file |
| `/var/lib/filemat/auth-code.txt` | Sensitive action auth code file |
| `/tmp/filemat` | Default upload folder |

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
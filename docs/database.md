
# Database details

Spring Data adapter for SQLite: `org.komamitsu:spring-data-sqlite`

Data file: `filemat-server.db` in the data folder. The data folder is `FM_DATA_DIR`, or by default `/var/lib/filemat` on Linux and `%LOCALAPPDATA%\Filemat` on Windows.


## Schema

The base schema (version 1) is defined in [the Spring resources folder](</server/src/main/resources/sqlite-schema.sql>).
Every later change is a Flyway migration (see [Migrations](#migrations)). The tables below show the schema after all migrations.


> PK: Primary Key  
UQ: Unique  
FK: Foreign Key  
NULLABLE: Field can be null  

#### `users` table
Column | type | attributes | detail
--- | --- | --- | ---
user_id | text | PK |  
email | text | UQ |  
username | text | UQ |  
password | text | |  
mfa_totp_secret | text | NULLABLE |  
mfa_totp_status | integer | |  
mfa_totp_codes | text | NULLABLE |  
mfa_totp_required | integer | | added in V2
created_date | integer | |  
last_login_date | integer | NULLABLE |  
is_banned | integer | |  
home_folder_path | text | NULLABLE | API path, added in V6

#### `role` table
Column | type | attributes | detail
--- | --- | --- | ---
role_id | text | PK |  
name | text | |  
created_date | integer | |  
permissions | text | | JSON int array for system permission enums

#### `permissions` table
Column | type | attributes | detail
--- | --- | --- | ---
permission_id | text | PK |  
permission_type | integer | |  
entity_id | text | FK |  
user_id | text | FK, NULLABLE |  
role_id | text | FK, NULLABLE |  
permissions | text | | JSON int array for file permission enums
created_date | integer | |  

#### `files` table
Column | type | attributes | detail
--- | --- | --- | ---
entity_id | text | PK |  
path | text | UQ, NULLABLE | API path (`/a/b`, `C:/a/b` on Windows). Null when the file is gone (orphaned entity)
is_filesystem_supported | integer | | whether `file_key` is stable on the file's volume. False on FAT, exFAT and network volumes, and for the extra paths of a hard-linked file
owner_user_id | text | FK, NULLABLE | set to null when the user is deleted (V8)
path_key | text | UQ, NULLABLE | lookup key of `path`, added in V9. Equal to `path` on Linux; on Windows names are upper-cased unless their folder is case-sensitive
file_key | text | UQ, NULLABLE | identity of the file, added in V9: `posix:<dev>:<ino>` or `win:<volumeSerial>:<fileId>`. Null on unsupported volumes and while the file is missing

#### `auth_token` table
Column | type | attributes | detail
--- | --- | --- | ---
auth_token | text | PK |  
user_id | text | FK |  
created_date | integer | |  
user_agent | text | |  
max_age | integer | |  

#### `log` table
Column | type | attributes | detail
--- | --- | --- | ---
log_id | integer | PK |  
level | integer | |  
type | integer | |  
action | integer | |  
created_date | integer | |  
description | text | |  
message | text | |  
initiator_user_id | text | FK, NULLABLE |  
initiator_ip | text | NULLABLE |  
target_id | text | NULLABLE |  
metadata | text | NULLABLE | JSON object

#### `settings` table
Column | type | attributes | detail
--- | --- | --- | ---
name | text | PK |  
value | text | |  
created_date | integer | |  

#### `shared_files` table
Column | type | attributes | detail
--- | --- | --- | ---
share_id | text | PK |  
file_id | text | FK |  
user_id | text | FK |  
created_date | integer | |  
max_age | integer | |  
is_password | integer | |  
password | text | NULLABLE |  
pathname | text | NULLABLE | added in V3

#### `user_roles` table
Column | type | attributes | detail
--- | --- | --- | ---
role_id | text | PK, FK |  
user_id | text | PK, FK |  
created_date | integer | |  

#### `folder_visibility` table
Column | type | attributes | detail
--- | --- | --- | ---
path | text | PK |  
is_exposed | integer | |  
created_date | integer | |  

#### `saved_files` table
Column | type | attributes | detail
--- | --- | --- | ---
user_id | text | PK |  
path | text | PK |  
created_date | integer | |

## Migrations

At startup the server runs `sqlite-schema.sql` (all `CREATE ... IF NOT EXISTS`), then Flyway (`DatabaseSetup.runFlywayMigrations`, `baselineOnMigrate`).
A new database is baselined at version 1 and then runs every migration, so the base schema file is never changed. Schema changes always go in a new migration.
Before pending migrations run on an existing database, the server copies it to `filemat-server.db.v<current version>-<date>.bak` next to it (`VACUUM INTO`), as private as the database. Only the three newest backups are kept.

Migrations are Kotlin classes in [`server/src/main/kotlin/db/migration`](</server/src/main/kotlin/db/migration>), named `V<number>__<description>`.
They extend `Migration` (`MigrationUtil.kt`): override `execute()` for one SQL statement or `run(st)` for anything more.

Version | Change
--- | ---
V2 | `users.mfa_totp_required`
V3 | `shared_files.pathname`
V4 | adds the "manage all file shares" permission to roles that have every admin permission
V5 | drops `files.follow_symlinks` if it exists
V6 | `users.home_folder_path`
V7 | adds the "change own home folder" permission to the default user and admin roles
V8 | rebuilds `saved_files` and `files` with the foreign keys used when a user is deleted (`files.owner_user_id` is set to null)
V9 | `files.path_key` and `files.file_key`, each with a unique index. Drops `files.inode` and its index. `path_key`: Linux copies `path`; Windows computes the keys from the folders' case-sensitivity flags. `file_key` is filled for every entity whose file exists

Rules for a new migration:
- Use the next free number on `main`.
- Make it safe to run on a database that already has the change (check `PRAGMA table_info` before `ADD COLUMN`, use `IF NOT EXISTS`), like V2, V5 and V9.
- It must run on Linux and Windows. Code that reads the filesystem must not fail on paths that don't exist there.
- SQLite can't change a primary key or foreign key in place. Rebuild the table (create `<table>_new`, copy, drop, rename), and turn off `canExecuteInTransaction` and `PRAGMA foreign_keys` around it, like V8.
- Update the tables above.

### Path keys on Windows
On Windows `files.path_key` depends on the per-folder case-sensitive flag (`fsutil file setCaseSensitiveInfo`), which can change while the server is stopped.
So every start on Windows recomputes the keys after the migrations (`DatabaseSetup.initialize_rebuildPathKeys`, `PathKeyRebuild`).
When two entities end up with the same key, the older one keeps it and the newer one is orphaned (its `path` and `path_key` become null).

### File keys
An entity follows its file by `files.file_key` (`FileIdentity`): when a file is moved or renamed outside Filemat, the entity (with its permissions and shares) finds it again by key.
A key changes on copy, on a move to another volume (Filemat reads the key again after its own moves) and when an editor saves by writing a new file and renaming it.
On Linux the device number can change between boots for some filesystems (btrfs subvolumes, overlay). The entity then takes the new key the next time its path is opened.
Hard links share a key. The older entity keeps it and the other paths follow their path only.

# Native Windows Beta Checklist

Run this checklist before marking a native Windows build as beta-ready.

## Environment

- Install Java 17.
- Run under a dedicated local or domain service account.
- Set `FM_DATA_DIR` to a local NTFS or ReFS directory owned by the service account.
- Confirm the service account can read/write `FM_DATA_DIR` and the upload directory.
- Confirm the service account has only the intended access to exposed roots.
- Keep SQLite on a local filesystem for the primary beta pass.

## Startup And Setup

- Start with `server/gradlew.bat bootRun` or the packaged JAR.
- Confirm `%ProgramData%\Filemat` is used when `FM_DATA_DIR` is unset.
- Confirm the SQLite DB, setup code, auth code, upload folder, and thumbnail cache are created without POSIX errors.
- Complete first-user setup and login.

## Path And File Operations

- Expose a drive folder such as `C:\FilematTest`.
- Expose a UNC path only if explicitly configured for the test account.
- Browse folders whose names contain spaces, dots inside names, mixed case, and Unicode.
- Confirm paths returned to the browser use `/`, not `\`.
- Create, upload, rename, copy, move, edit, download, and delete files.
- Confirm Windows-invalid names are rejected: `CON`, `NUL`, names with `:`, names ending in dot/space, and names containing `\ / < > " | ? *`.
- Confirm same-path case variants do not bypass permissions or visibility.

## Security

- Confirm `C:\Windows`, `C:\Windows\System32`, `C:\System Volume Information`, `$Recycle.Bin`, service-account profile secrets, and `%ProgramData%\Filemat` are blocked or non-deletable by default.
- Confirm Windows ACL denials surface as clear Filemat failures.
- Confirm public shares cannot access `..`, drive paths, UNC paths, root-relative paths, or device paths under a shared root.
- Replace a shared root or permissioned file and confirm the old direct share/permission does not silently apply to the replacement.
- Create symlinks and junctions and confirm they are not traversed by default.
- If symlink traversal is enabled, confirm the resolved target is still authorized and contained.

## Media And SQLite

- Generate image thumbnails and video previews; confirm normal file operations still work if media preview generation fails.
- Confirm antivirus/indexer interference or locked files produce clear errors.
- Run with SQLite WAL/journal settings used in production and confirm restart recovery.

Stable Windows support requires automated Windows CI plus regression tests for path aliases, reparse points, stale entities, public-share containment, DOS attributes, and protected paths.

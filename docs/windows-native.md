# Native Windows Runtime

Native Windows support is intended for beta use until the Windows CI and security regression matrix passes.

## Runtime Layout

- Run the backend with Java 17 and `server/gradlew.bat bootRun`, or build the packaged JAR with `server/gradlew.bat bootJar`.
- Configure the data directory with `FM_DATA_DIR` or the `filemat.data-dir` equivalent. If unset, Filemat uses `%ProgramData%\Filemat`.
- The SQLite database, setup code, auth code, thumbnail cache defaults, and upload defaults derive from the configured data directory.
- Keep the data directory on a local NTFS or ReFS volume where possible. Network shares are allowed only as best-effort storage because SQLite locking, WAL behavior, and antivirus scanning can cause corruption or availability problems.

## Service Account And ACLs

- Run Filemat under a dedicated Windows service account.
- Grant that account full control over the Filemat data directory and upload directory.
- Grant the account only the minimum filesystem access needed for exposed folders.
- Windows ACLs are a hard lower bound: Filemat permissions cannot grant access that the service account cannot read or write.
- Do not expose service-account profile folders, registry hive files, browser credential stores, `%ProgramData%\Filemat`, `System Volume Information`, `$Recycle.Bin`, or Windows system folders unless you have an explicit recovery procedure and accept the risk.

## Service Installation

Filemat does not require administrator rights by itself, but a production service wrapper usually does.

Recommended service options:

- Use WinSW or NSSM to run the packaged backend JAR.
- Set the working directory to the repository or install directory.
- Set `FM_DATA_DIR` explicitly.
- Redirect stdout/stderr to a log directory outside user profile temp folders.
- Back up the SQLite database and data directory before upgrades.

## Filesystem Support

- NTFS and ReFS are preferred because they can provide stable file identity and ACL semantics.
- FAT and exFAT can be browsed when exposed by an admin, but operations that require stable identity for permissions or shares fail safe if the filesystem cannot provide a stable key.
- Symlinks, junctions, mount points, and other reparse points are not traversed by default. If traversal is enabled, Filemat re-authorizes the resolved target and enforces containment.

## Media Previews

The backend includes Windows x86_64 JavaCV FFmpeg/OpenCV runtime classifiers. If native media libraries fail to load, preview endpoints fail gracefully and log the error instead of blocking normal file operations.

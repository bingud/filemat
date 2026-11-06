# Full application code review (2026-11-06)

A skeptical review of the whole application (server and web), on branch `windows-support` at `d82911e`. Context taken from the Windows docs (`windows-status.md`, `windows-code-review.md`, `windows-overview.md`); this review is **not** limited to the Windows changes. Earlier findings that the Windows review already lists as fixed are not repeated.

Findings are written down as they are found and checked against the code. Nothing was built or run.

**Verdicts:** CONFIRMED = the code path was traced and the failure follows from it. PLAUSIBLE = depends on runtime/library behaviour or on a caller not fully traced.
**Severity:** High = security hole or data loss reachable by a normal user. Medium = real bug, or security issue that needs unusual setup. Low = edge case, robustness, perf. Info = observation.

## Summary

| Severity | Findings |
| --- | --- |
| **High** | R1 rate limits bypassable via `X-Forwarded-For` · R9 `MANAGE_USERS` can take over the super admin · R13 stored XSS through files served inline (one click on a fake `.pdf`) · R23 copy reads through links into hidden/sensitive folders (links on) · R30 content-origin cookie is the full session |
| **Medium** | R2 `Secure` cookies break HTTP LAN login · R3 authorization fails open · R10 disabling 2FA clears enforcement · R11 backup codes unusable · R14 no clickjacking/security headers · R15 inode reuse moves permissions and shares · R16 permission tree drifts after moves · R18 user delete erases audit log · R19 shares outlive sharer's access · R22 overwrite transfers ownership · R25 hidden folders not hidden until restart after setup · R28 log table disk-fill · R36 Argon2 memory exhaustion · R38 Linux FAT/CIFS/NFS keys treated as stable · R39 thin Linux sensitive list (`/proc`, cloud keys) · R40 walks block all writes below them · R42 folder rename rebuilds the folder (permissions lost, not atomic) · R46 Linux FFmpeg in-process, unlimited, no timeout · R47 image thumbnails unlimited, 8192 px · R48 `MANAGE_SYSTEM` reads others' files via internal folders · R53 guessable share links · R56 Docker image runs as root · R57 delete in search mode deletes the wrong files |
| **Low** | R4, R5, R6, R7, R12, R17, R20, R21, R24, R26, R27, R29, R31, R32, R33, R34, R35, R37, R41, R43, R44, R45, R49, R50, R51, R52, R54, R55, R58 |
| **Info** | R8 |

Suggested order: R13 + R14 + R30 (one header/CSP filter and a scoped content token), R9, R1, R23, R3; then R46 + R47 + R36 (bounded work per request), R53, R48, R56; then the rest of the Medium list.

**Coverage.** Pass 1 (2026-11-06) read the security-critical paths: auth, admin, roles, permissions, sharing, uploads, file operations, file serving, setup, and the web's fetch, content URL, viewer and service worker code. Pass 2 (resumed on request, R36-R56) went through the rest of `server/src/main`, the web's logic files, the build, the Docker image and the release workflow. Pass 3 (R57-R58) finished the web app and surveyed the tests. The [coverage list](#coverage-list) at the end records what each pass read and what was only scanned.

## Findings

(in the order found; numbering is the order of discovery, not severity)

### R1. Client IP is taken from `X-Forwarded-For` unconditionally: every rate limit can be bypassed. **High**, CONFIRMED
[utilFunctions.kt:138](../server/src/main/kotlin/org/filemat/server/common/util/utilFunctions.kt#L138)
`realIp()` returns the raw `X-Forwarded-For` header whenever it is present, with no trusted-proxy check. Every IP-keyed limit uses it: `LOGIN` (password guessing), `SHARED_FILE_LOGIN_ANON` (share password guessing), `SHARED_FILE_PROBE`, `SHARED_FILE_ACCESS`, `VERIFY_SETUP_CODE`, `SETUP`. A client that sends `X-Forwarded-For: <random>` on each request gets a fresh bucket every time, so password brute force is unlimited. The Windows zip and a plain `docker run` have no proxy in front, so this is the default deployment, not an edge case.
Side effects: the audit log's `initiatorIp` is attacker-chosen (log forging, `1.2.3.4, 5.6.7.8` lists stored as-is), and each spoofed value creates a bucket (memory growth, bounded only by the cleanup in R5).
Fix: a setting/env var for trusted proxies (default: none). Only when `remoteAddr` is a trusted proxy, take the right-most untrusted entry of `X-Forwarded-For`. Same for `X-Forwarded-Proto` (used by `CorsOriginRegistry.requestOrigin` and `AuthController.contentSessionMapping`'s HTTPS check).

### R2. Auth cookies are always `Secure`: login from another device over plain HTTP can't work. **Medium**, PLAUSIBLE (browser behaviour, not run)
[AuthTokenService.kt:113](../server/src/main/kotlin/org/filemat/server/module/auth/service/AuthTokenService.kt#L113), [LoginService.kt:247](../server/src/main/kotlin/org/filemat/server/module/auth/service/LoginService.kt#L247)
`createCookie` and `createLoginTokenCookie` set `secure = true`. Browsers drop a `Secure` cookie set by an `http://` page unless the host is `localhost` (Chrome since 89, Firefox since 75). `windows.md` tells users to allow the firewall "to use Filemat from other devices at home", i.e. `http://192.168.x.x:8080`: the login answers `ok`, the cookie is discarded, and the user stays logged out with no error. W9 (second device) was never run, so this hasn't been seen.
Fix: either document "HTTPS required except on localhost" and show a clear error in the login page when `!window.isSecureContext`, or set `Secure` only when the request is secure (`request.isSecure` / trusted `X-Forwarded-Proto`).

### R3. Endpoint authorization fails open. **Medium** (design), CONFIRMED
[AuthorizationFilter.kt:22](../server/src/main/kotlin/org/filemat/server/module/auth/filter/AuthorizationFilter.kt#L22), [endpointAuthentication.kt:63](../server/src/main/kotlin/org/filemat/server/config/auth/endpointAuthentication.kt#L63)
- The filter looks the servlet path up in `endpointAuthMap` by exact string and **lets the request through when there is no entry**. Map keys are Spring *patterns*, so a route with a path variable never matches: `/v1/file/upload/{uploadId}` (tus `PATCH`/`HEAD`/`DELETE`) is reached without any auth check. Today it is saved only by `request.getPrincipal()!!` in `TusService.handleTusUpload` throwing an NPE (a 500 instead of a 401). Anyone adding a `{id}` route later gets an unauthenticated endpoint by default, the opposite of the "authenticated unless `@Unauthenticated`" rule the code comments promise.
- The map is filled on `ApplicationReadyEvent`, after Tomcat's connector has started (`finishRefresh`). Requests in that window find an empty map and pass every check. The window is short, but it exists on every start.
- `endpointAuthMap` is a plain `HashMap` that is `clear()`ed and refilled while request threads read it.
- The key ignores the HTTP method (`endpointAuthMap[pattern] = …` inside the method loop), so two handlers on one path with different annotations would overwrite each other.
Fix: deny when no entry is found (404/401) for `/api` paths, match with Spring's `HandlerMapping.getHandler(request)` (or the `BEST_MATCHING_PATTERN_ATTRIBUTE`) instead of the raw path, build the map before the connector starts (`SmartInitializingSingleton` or `@PostConstruct` on the mapping bean) into an immutable map, and key it by method + pattern.

### R4. Login timing reveals whether a username exists. **Low**, CONFIRMED
[LoginService.kt:83](../server/src/main/kotlin/org/filemat/server/module/auth/service/LoginService.kt#L83)
An unknown username returns before any password hashing; a known one runs Argon2 (`passwordEncoder.matches`). The response text is the same, but the time differs by the Argon2 cost (tens to hundreds of ms). With R1, enumeration is unlimited.
Fix: run `passwordEncoder.matches` against a fixed dummy hash when the user doesn't exist.

### R5. Rate-limiter cleanup runs every second, a debug value left in. **Low**, CONFIRMED
[RateLimiter.kt:91](../server/src/main/kotlin/org/filemat/server/common/util/RateLimiter.kt#L91)
`// delay(90_000)` is commented out and `delay(1000)` used. Every second it walks every bucket of every limit. Harmless at low load; with R1 an attacker controls how many buckets exist.

### R6. TOTP codes can be replayed within their window. **Low**, CONFIRMED
[TotpUtil.kt:16](../server/src/main/kotlin/org/filemat/server/common/util/TotpUtil.kt#L16)
`verify` accepts the previous, current and next code and keeps no record of used ones, so a code seen once (shoulder-surfed, phished in real time) works again for up to ~90 s. Fix: remember the last accepted time step per user and refuse codes at or before it.

### R7. Static file streams are never closed. **Low**, PLAUSIBLE
[UtilityFilter.kt:42](../server/src/main/kotlin/org/filemat/server/config/filter/UtilityFilter.kt#L42)
`result.body?.inputStream?.copyTo(response.outputStream)` opens a stream on a classpath resource (an entry in the JAR) and never closes it. Each page load leaks a JAR entry stream (an `Inflater` with native memory) until GC. Fix: `.use { it.copyTo(...) }`. Related: static responses have no `Cache-Control`, so the hashed `_app/immutable/*` assets are revalidated or refetched on every load, and `HEAD`/`POST` to any non-`/api` path return `index.html` with 200.

### R9. A user with `MANAGE_USERS` can take over the super admin. **High**, CONFIRMED
[AdminUserService.kt:52](../server/src/main/kotlin/org/filemat/server/module/admin/service/AdminUserService.kt#L52), [AdminUserService.kt:207](../server/src/main/kotlin/org/filemat/server/module/admin/service/AdminUserService.kt#L207), [AdminUserService.kt:89](../server/src/main/kotlin/org/filemat/server/module/admin/service/AdminUserService.kt#L89)
`deleteUser` checks `hasSufficientPermissionsFor(target)`, but `changeUserPassword`, `resetTotpMfa` and `updateProperty` (username, email, home folder) check nothing about the target. The only gate is the class-level `@Authenticated([MANAGE_USERS])`. So a user with a role that has `MANAGE_USERS` (level 3) can:
1. `POST /v1/admin/user/reset-totp-mfa` for the super admin (turns off their 2FA, logs them out),
2. `POST /v1/admin/user/change-password` for the super admin,
3. log in as the super admin.
Also: `assignRole` compares only the *highest level* (`hasSufficientPermissionsFor`), so a `MANAGE_USERS` user can assign themselves any existing role whose permissions are all level ≤ 3 (`MANAGE_SYSTEM`, `EDIT_ROLES`, `ACCESS_ALL_FILES`), even ones they don't hold. And `AdminRoleController.create` uses the same level check, while `RoleService.updatePermissionList` uses `containsAll`: two different rules for "can't grant more than you have".
Fix: run the same target check as `deleteUser` in every admin action on another user (and refuse self-targeted ones where it matters). Decide on one rule for granting: `userPermissions.containsAll(granted)` (with `SUPER_ADMIN` implying all) is the strict one; the level rule lets equals grant each other's powers.

### R10. Disabling 2FA clears the admin's "2FA required" flag. **Medium**, CONFIRMED
[MfaService.kt:127](../server/src/main/kotlin/org/filemat/server/module/auth/service/MfaService.kt#L127)
`disable` calls `updateUserMfa(..., isRequired = false)`. A user whose 2FA was enforced by an admin (`resetTotpMfa(enforce = true)` → `mfa_totp_required = 1`) can turn 2FA off in their account settings, and the enforcement is gone for good; the next login doesn't ask to set it up again. `enable_confirmSecret` also writes `isRequired = false`, which loses the flag the same way once the user later disables it.
Fix: pass the user's current `mfaTotpRequired` through, and refuse `disable` while it is set. The return value of `updateUserMfa` in `disable` is also ignored: a DB failure logs "User disabled TOTP 2FA" and answers OK.

### R11. 2FA backup codes are shown to the user but can never be used. **Medium**, CONFIRMED
[MfaService.kt:60](../server/src/main/kotlin/org/filemat/server/module/auth/service/MfaService.kt#L60), [LoginService.kt:183](../server/src/main/kotlin/org/filemat/server/module/auth/service/LoginService.kt#L183), `web/src/lib/component/auth/MfaSetupDialog.svelte:79`
Six codes are generated, stored in `users.mfa_totp_codes` (plain text), and the dialog says "You can use them to access your account if you lose access to your 2FA". No server code reads them: `verifyTotpMfa` and `MfaService.disable` accept only a TOTP. A user who loses the phone is locked out until an admin resets 2FA, after having been promised otherwise.
Fix: accept a backup code in `verifyTotpMfa` (constant-time compare, single use, stored hashed), or stop generating and showing them.

### R12. Per-account 2FA guessing is limited, but never locked. **Low**, CONFIRMED
[LoginService.kt:175](../server/src/main/kotlin/org/filemat/server/module/auth/service/LoginService.kt#L175)
`VERIFY_TOTP` is 8 burst + 1 per 30 s per user. With 3 valid codes per window that is ~0.9 % per day of finding a code once the password is known, ~23 % a month, with nothing logged beyond individual WARN lines and nothing alerting the user. Consider a lockout or alert after N failures.

### R13. Uploaded HTML/SVG is served inline on the app's own origin: stored XSS → admin takeover. **High**, CONFIRMED (code path; the `.pdf` variant relies on Tika's HTML magic, not run)
[FileController.kt:386](../server/src/main/kotlin/org/filemat/server/module/file/controller/FileController.kt#L386)
`GET /api/v1/file/content?path=…` answers with the Tika-detected type (`text/html`, `image/svg+xml`, `application/xhtml+xml`) and `Content-Disposition: inline`, from the same origin as the SPA (unless the optional `FM_CONTENT_BASE_URL` is set, which is off by default). There is no `Content-Security-Policy: sandbox`, no `X-Content-Type-Options: nosniff`, nothing anywhere in the server or web that sets a CSP.
Shortest attack, one click inside the app: the viewer picks the display by *extension* and shows `.pdf` files in an `<iframe src={contentUrl}>` with no `sandbox` attribute ([FileViewer.svelte:190](../web/src/routes/(app)/(files)/files/[...path]/_elements/layout/FileViewer.svelte#L190)). The server picks the `Content-Type` by *bytes* (`tika.detect(inputStream)`, magic only). A file named `invoice.pdf` that contains `<html><script>…` is served as `text/html` into a same-origin, unsandboxed iframe, and its script has `window.parent` and the session. A user only needs write access to a folder the admin browses.
The viewer's "Raw Content" menu entry also opens the content URL directly in the tab.
Other route: any user who can write to a folder an admin can read uploads `x.html` (or an SVG with `<script>`), and sends the admin the content link. The auth cookie is `SameSite=Lax`, so it is sent on a top-level navigation from a chat or e-mail link. The script runs on the Filemat origin and can call every `/api/v1/admin/*` endpoint as the admin (the cookie is HttpOnly, but same-origin `fetch` carries it). The same works through a public share link (`?shareToken=`), which needs no account to open.
Fix: for `inline` responses, send `Content-Security-Policy: sandbox; default-src 'none'; img-src 'self' data:; media-src 'self'; style-src 'unsafe-inline'` (sandbox without `allow-scripts`/`allow-same-origin`) and `X-Content-Type-Options: nosniff`; force `attachment` (or `text/plain`) for `text/html`, `application/xhtml+xml`, `image/svg+xml` and `text/xml`. In the web, only load the PDF iframe when the server's `Content-Type` is `application/pdf` (check with a HEAD first), since a `sandbox` attribute may stop some browsers' built-in PDF viewers; the server-side CSP is the real guard. A separate content origin alone does **not** fix this today, see R30.

### R30. The content-origin cookie is the full session token, so a separate content origin isolates nothing. **High** (for anyone relying on `FM_CONTENT_BASE_URL` for isolation), CONFIRMED
[ContentSessionService.kt:53](../server/src/main/kotlin/org/filemat/server/module/auth/service/ContentSessionService.kt#L53), [AuthController.kt:84](../server/src/main/kotlin/org/filemat/server/module/auth/controller/AuthController.kt#L84)
`buildSetCookieHeader(authToken.authToken, …)` sets the *same* auth token on the content origin, under the same cookie name. The content origin is the same Filemat server, so with that cookie every endpoint answers there, admin ones included; the filters don't limit a content-origin request to `@Cors` reads. Script in an uploaded HTML file opened on the content origin does a same-origin `fetch('/api/v1/admin/user/change-password', {credentials: 'include'})`: the Origin equals the Host, `CorsFilter` sees no cross-origin request, the token is valid, and it runs as the admin. The 1-hour `Max-Age` doesn't shorten the token's life either: it's the year-long session token.
Fix: mint a separate, read-only content token (own table or signed value: user ID, expiry, scope `content`), accept it only on the `@Cors` read endpoints, and refuse the main session cookie on the content host (or vice versa) by comparing the request's Host with the configured content origin.

### R14. Missing security headers on the app itself: clickjacking. **Medium**, CONFIRMED
No `X-Frame-Options` / `frame-ancestors` anywhere (grep finds none in server or web). Any site can frame the Filemat UI and trick a logged-in user into clicks (delete, share creation, the "make public" toggles). Also no CSP on `index.html`, no `Referrer-Policy` (share tokens are in query strings, see later findings), no `nosniff`.
Fix: a filter adding `X-Frame-Options: DENY` (or `frame-ancestors 'none'`), `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff` to every response, and a CSP for the SPA.

### R15. Permissions of a deleted file can jump to an unrelated file through inode reuse (Linux). **Medium**, PLAUSIBLE
[FileSecurityService.kt:380](../server/src/main/kotlin/org/filemat/server/module/file/service/file/component/FileSecurityService.kt#L380), `FileIdentity` (Linux key = device + inode)
When a file with an entity (permissions, shares) is deleted **outside** Filemat, its entity keeps the old `file_key`. ext4/xfs hand freed inode numbers out again quickly. When any request later touches a *different* path whose new file got that inode, `repairEntityIdentity` finds the entity by key, sees that the old path no longer has that key (`keyHeldElsewhere = false`), and moves the entity, with all its user/role permissions and **its public share links**, to the new file. A share link for `/home/a/report.pdf` can start serving `/home/c/payroll.xlsx`.
The repair runs on every read check (`hasPermissionToAccessFile` → `verifyEntityIdentity`), before the permission decision, so the user whose access triggers it doesn't even need rights to the old file.
NTFS file IDs include a sequence number and are much less exposed. On Linux, a key alone isn't an identity.
Fix: store and compare a second attribute when reconnecting by key (birth time where the FS reports it, else size + mtime at index time), and never reconnect *across folders* without it; or drop automatic cross-path reconnection and only reconnect on a Filemat move.

### R16. The in-memory permission tree loses track of permissions after some moves. **Medium**, CONFIRMED
[EntityPermissionTree.kt:178](../server/src/main/kotlin/org/filemat/server/module/permission/model/EntityPermissionTree.kt#L178), [EntityService.kt:454](../server/src/main/kotlin/org/filemat/server/module/file/service/EntityService.kt#L454)
- `movePath` returns `false` when a node already exists at the target, and `EntityService.updatePermissionPath` ignores the result. Nodes are never pruned: `removePermissionByEntityId` empties the maps but leaves the node. So after `/shared/b` once had a permission (and was deleted), renaming `/shared/a` (granted to user B) to `/shared/b` leaves B's grant in memory at `/shared/a`. B loses `/shared/b`, and **any new file or folder created at `/shared/a` is readable by B** until the next restart, when the tree is rebuilt from the DB and the grant reappears at `/shared/b`.
- An entity orphaned with `move(path, null)` has its permissions removed from the tree. When it is reconnected later (`updatePath` with a new path), `updatePermissionPath` does nothing because the *old* `pathKey` is null, so the permissions stay out of memory until a restart brings them back. Access changes silently on restart in both cases.
Fix: on move, merge into an existing target node (or re-add the entity's permissions from the DB by `entityId`), prune empty nodes on removal, and on reconnect re-add the entity's DB permissions under the new key. Add a consistency test: after any sequence of moves/deletes, the tree equals one rebuilt from the DB.

### R17. `withVerifyPathLock` doesn't guarantee mutual exclusion. **Low**, CONFIRMED
[FileSecurityService.kt:295](../server/src/main/kotlin/org/filemat/server/module/file/service/file/component/FileSecurityService.kt#L295)
Thread A holds lock L for a key; B gets the same L from `computeIfAbsent` and waits. A unlocks and `remove(key, L)`. C now creates L2 and locks it while B also proceeds under L: B and C run the repair for the same path at once. The inner `withEntityRepairLock` (a single global lock) covers most of the dangerous parts, so the damage is limited, but the per-path lock is decorative. Same pattern in other "lock map + remove on unlock" places (see `FileLockService` notes below if any). Fix: reference-count the lock entries, or use a striped lock (Guava `Striped`), or just rely on the global repair lock.

### R18. Deleting a user deletes their audit log. **Medium**, CONFIRMED
[sqlite-schema.sql:77](../server/src/main/resources/sqlite-schema.sql#L77)
`log.initiator_user_id REFERENCES users(user_id) ON DELETE CASCADE`, and no migration changes it (V8 rebuilt `saved_files` and `files` only). Every log row a user initiated (uploads, deletes, shares, permission changes, their own admin actions) disappears with the account. An admin, or with R9 any `MANAGE_USERS` holder, can erase the trail of what an account did by deleting it.
Fix: `ON DELETE SET NULL` (the rows keep the username in `description`/`message` already), via a table rebuild migration like V8.

### R19. Public shares outlive the sharer's access. **Medium**, CONFIRMED
[FileShareService.kt:405](../server/src/main/kotlin/org/filemat/server/module/sharedFile/service/FileShareService.kt#L405), [FileService.kt:225](../server/src/main/kotlin/org/filemat/server/module/file/service/file/FileService.kt#L225)
The `SHARE` permission is checked only in `createShare`. At access time (`getByShareToken`, `resolvePathWithOptionalShare`) nothing checks that the share's `userId` still has `READ`/`SHARE` on the file. Removing a user's permission, their role, or their `SHARE` right leaves every link they created serving the file (and, for a folder, everything below it, including files added later). Only deleting the account removes them (FK cascade).
Fix: at access time, check `isAllowedToShareFile(shareOwnerPrincipal, shareRoot)` (cached briefly), or delete a user's shares on the entity when their effective rights there drop.

### R20. Share password tokens survive share deletion and re-creation under the same ID. **Low**, CONFIRMED
[FileShareService.kt:46](../server/src/main/kotlin/org/filemat/server/module/sharedFile/service/FileShareService.kt#L46)
`tokenMap` maps a token to a *share ID* for 8 hours, and share IDs are chosen by the user (`sharePath`). Delete a password-protected share and create a new one with the same ID (another file, another password): tokens issued for the old share still unlock the new one. Map the token to `(shareId, createdDate)` or to the entity ID and compare.

### R21. Small share-service bugs. **Low**, CONFIRMED
- `getSharesByPath` checks access on the canonical path but looks the entity up by the *raw* path (`getEntityIdByPath(rawPath)`), so through a link or a different-case/8.3 path the share list is empty (or another entity's). [FileShareService.kt:309](../server/src/main/kotlin/org/filemat/server/module/sharedFile/service/FileShareService.kt#L309)
- `deleteShare` does `FilePath.of(entity.path!!)`: an orphaned entity (path null) throws an NPE → 500, and the share can't be deleted from the UI.
- `FileShareController.create` hashes the share password with Argon2 *before* any permission check, so any logged-in user can make the server burn Argon2 time with failing requests. The password isn't validated at all (an empty string makes a "protected" share).
- Non-password shares use the share ID itself as the token. IDs are user-chosen; a short one (`report`) is guessable, and `SHARED_FILE_ACCESS`'s per-IP limit is bypassable (R1). Consider a minimum length or a random suffix.

### R22. Overwriting a file by upload makes the uploader its owner, with control of its existing permissions. **Medium**, CONFIRMED
[FolderUploadService.kt:556](../server/src/main/kotlin/org/filemat/server/module/file/service/FolderUploadService.kt#L556)
`finalizeOverwrite` keeps the existing entity (with every user/role grant on it and its shares) and sets `ownerId = uploader`. Ownership is what `canEditEntityPermission` checks with `MANAGE_OWN_FILE_PERMISSIONS`. So a user who may only `WRITE`+`DELETE` in a folder can overwrite a colleague's file and then edit that file's permission list: remove the colleague's grants, give themselves `SHARE`/`MOVE`/`RENAME`, add other users. A delete followed by a fresh upload would give a *new* entity with no grants, which is the safer outcome; overwrite gives the uploader the old ACL to play with.
Fix: keep the existing owner on overwrite (ownership is about who created the file's ACL, not who last wrote its bytes), or drop the old grants when ownership changes.

The same lock-map pattern as R17 is in [FileLockService.kt:131](../server/src/main/kotlin/org/filemat/server/module/file/service/FileLockService.kt#L131): `cleanup` removes the lock from the map while another thread may already hold a reference from `computeIfAbsent`; that thread can lock the removed instance while a third creates a new one. Narrow window, but two writers on one path is exactly what the service exists to stop.

### R23. Copy checks the path as typed, so with "follow symbolic links" on it copies out of hidden and sensitive folders. **High** (when links are followed), CONFIRMED
[FileCopyService.kt:52](../server/src/main/kotlin/org/filemat/server/module/file/service/file/component/FileCopyService.kt#L52), [FilesystemCopyService.kt:215](../server/src/main/kotlin/org/filemat/server/module/file/service/filesystem/fileOperation/FilesystemCopyService.kt#L215)
Unless the copied item is itself a link to another filesystem, `actualCanonicalPath = withRealNames(rawPath)`: the path *as typed*, with linked folders in the middle unresolved. The checks (`isAllowedToAccessFile`, and per file in `copyRecursiveSafe` on `sourceFilePath`) then run against that unresolved path, while `Files.copy(..., NOFOLLOW_LINKS)` only refuses a link in the *last* component and happily reads through linked parents.
Example (Linux, follow-links on): `/data` is exposed and contains `home -> /home/alice`. Reading `/data/home/.ssh/id_rsa` resolves to `/home/alice/.ssh/id_rsa` and is blocked by the sensitive list. Copying `/data/home/.ssh/id_rsa` to `/data/x` is checked as `/data/home/.ssh/id_rsa`, which is under the exposed `/data`, isn't on the sensitive list (that list holds `/home/alice/.ssh`), and inherits `/data`'s grants. The key lands in `/data/x`, readable. Same for the data folder (`allowReadDataFolder`), admin-hidden folders, and per-file permission denials at the link target.
This is the same class as the Windows review's M4 (fixed for delete), not fixed for copy.
Fix: resolve the source's parent with `resolvePath` and append the name, as `FileMoveService` and `FileDeletionService` now do; in the recursion, check each child on its resolved path.

### R24. Smaller file-operation bugs. **Low**, CONFIRMED
- `getFileContent` takes a READ lock and releases it in `finally` *before* the caller streams the file, so the lock never covers the read; a delete or overwrite can run during a download. [FileContentService.kt:79](../server/src/main/kotlin/org/filemat/server/module/file/service/file/component/FileContentService.kt#L79)
- `editFile` writes `newContent.toByteArray(UTF_8)`. A file in another encoding (Latin-1, UTF-16 with BOM) is silently re-encoded on save, and any bytes the browser decoded as U+FFFD are lost. Either refuse to edit non-UTF-8 files or round-trip the detected charset.
- `createFolder`/`createBlankFile`/`moveFile` call `FilePath.ofAlreadyNormalized(rawPath.path.parent)` with no null check: `path=/` (or a drive root) gives an NPE → 500 instead of a 400.
- Multi-file zip: two selected items with the same name (`/a/x.txt`, `/b/x.txt`) or one item selected twice give `ZipException: duplicate entry`, which is caught and logged as a skip. The user gets a zip silently missing files. Same for anything skipped for permissions: the download looks complete.

### R25. Right after setup, `FM_HIDDEN_FOLDER_PATHS` and the auth-code file are not hidden until a restart. **Medium**, CONFIRMED
[Initialization.kt:44](../server/src/main/kotlin/org/filemat/server/config/Initialization.kt#L44), [FileVisibilityService.kt:40](../server/src/main/kotlin/org/filemat/server/module/file/service/FileVisibilityService.kt#L40), [SetupController.kt:99](../server/src/main/kotlin/org/filemat/server/common/controller/SetupController.kt#L99)
`FileVisibilityService.initialize()` is what fills `hiddenFileTrie` (the env hidden folders and `Props.authCodeFile`), and it runs at startup *only when the app is already set up*. `SetupController.submit` inserts the exposed paths but never calls `initialize()`. On a fresh install, from setup until the first restart, every folder in `FM_HIDDEN_FOLDER_PATHS` inside an exposed folder is fully visible. The same holds for `EntityPermissionService.loadPermissionsFromDatabase` (harmless: no permissions yet) and is a sign that "initialised" is a startup-only notion.
Fix: split `initialize()` into "load hidden lists" (always, at startup) and "load DB rules"; or call the full initialisation at the end of setup.

### R26. Setup stores the upload folder as typed. **Low**, CONFIRMED
[SetupController.kt:213](../server/src/main/kotlin/org/filemat/server/common/controller/SetupController.kt#L213)
`state.realInternalFolder(uploadFolderPath)` is only used as a check, and `uploadFolderPath.pathString` (as typed) is saved and put in `state.app.uploadFolderPath`. The Windows review's L7 fixed exactly this for the thumbnail folder and `SettingService.set_uploadFolderPath` stores the real path, but setup was missed: with a link or an 8.3 name, `isInInternalFolder` compares the real path of finished uploads against the typed one.

### R27. "Files shared with me" leaks metadata of files that are no longer exposed. **Low**, CONFIRMED
[FileEntryListsService.kt:163](../server/src/main/kotlin/org/filemat/server/module/file/service/file/component/FileEntryListsService.kt#L163)
`getPermittedFileList` reads `filesystemService.getMetadata` for every entity the tree grants READ on, without `isPathAllowed` and without `verifyEntityIdentity`. A grant on a folder that the admin later un-exposed, hid, or that became part of the sensitive list still shows its name, size and dates in the list. Use `getFullMetadata` (which runs the access checks) like `getSharedFileList` does.

### R28. Unauthenticated requests can fill the disk through the log table. **Medium**, CONFIRMED
[LoginService.kt:211](../server/src/main/kotlin/org/filemat/server/module/auth/service/LoginService.kt#L211), [LogService.kt:116](../server/src/main/kotlin/org/filemat/server/module/log/service/LogService.kt#L116), [sqlite-schema.sql:66](../server/src/main/resources/sqlite-schema.sql#L66)
Every failed login writes a row whose `metadata` holds the raw `username` and `User-Agent` (`meta = mapOf("ip", "user-agent", "target-username")`), *including* the "invalid username" case, where the username failed validation and can be as large as Tomcat's 2 MB form limit. The per-IP login limit is bypassable (R1). There is no retention, no size cap and no row limit on `log`, and the table has no index on `created_date`, which every admin log page sorts by (full scan + sort as the table grows). The rows are also echoed to the console log by default.
Fix: truncate logged user input (e.g. 256 chars), add retention (age or row count) and `CREATE INDEX idx_log_created_date ON log(created_date)`.

### R29. Turning "follow symbolic links" on doesn't need the physical-access code that exposing a folder needs. **Low** (design), CONFIRMED
[AdminSettingsController.kt:217](../server/src/main/kotlin/org/filemat/server/module/admin/controller/AdminSettingsController.kt#L217)
Adding/removing visibility rules and changing the content URL require `verifyOtp` (a code printed to the server console). `set/follow-symlinks` and `set/upload-folder-path` only need `MANAGE_SYSTEM`. With links followed, any symlink inside an exposed folder (to `/`, a home folder) effectively exposes its target, so the toggle is as powerful as an exposure rule. Gate it the same way, or document why not.
Smaller: `remove-file-visibility` ignores the result of `removePath` and logs/answers success even when the DB delete failed; `parseEnumList` in the log endpoint throws on an unknown name (500 instead of 400); `amount` is unbounded.

### R31. JDBC converter list has a copy-paste error. **Low**, CONFIRMED
[DatabaseConfig.kt:30](../server/src/main/kotlin/org/filemat/server/config/database/DatabaseConfig.kt#L30)
Under `// permission`, `StringToFilePermissionListConverter()` is registered a second time where `StringToSystemPermissionConverter` (the reverse of `SystemPermissionToStringConverter`, listed just above it) was meant. Nothing reads a single `SystemPermission` column today, so it doesn't fail yet; the first entity that does will fail at runtime. The single-value converters don't round-trip either: `SystemPermissionToStringConverter`/`FilePermissionToStringConverter` write `Json.encodeToString(enum)` (the quoted *name*, `"READ"`), while the readers parse an *index* (`source.toInt()`). [typeConverters.kt:116](../server/src/main/kotlin/org/filemat/server/config/database/typeConverters.kt#L116). `PermissionType.fromInt` uses `entries[i] ?: throw …`, whose `?:` is unreachable (an out-of-range index throws `IndexOutOfBoundsException` first).

### R32. SQLite runs in rollback-journal mode with a 10-connection pool. **Low** (robustness), PLAUSIBLE
[application.yml](../server/src/main/resources/application.yml), no `journal_mode`/`busy_timeout` anywhere.
Hikari's default pool is 10 connections. Without WAL, any write locks the whole DB file against readers, and concurrent writers get `SQLITE_BUSY` after sqlite-jdbc's default 3 s busy timeout. Logs are written on many request paths (R28), entity repair writes on read paths, so "database is locked" 500s under parallel uploads/browsing are likely on slow disks. `PRAGMA journal_mode=WAL` (persistent, set once) and `busy_timeout` in `connection-init-sql` are the usual fix. (WAL needs care on network drives, which Filemat already refuses for the data folder.)

### R33. A developer tool is shipped as a public page, with an unsafe "safe preview". **Low**, CONFIRMED
[web/src/routes/(external)/util/+page.svelte](../web/src/routes/(external)/util/+page.svelte)
`/util` is an "SVG Padding Cropper" with a link to the author's site, reachable without login in every build. `createSafePreview` is not safe: it parses the pasted SVG and injects `root.innerHTML` with `{@html}`, so `<image href=x onerror=…>` runs script. It's self-XSS only (the user pastes it), but the name promises otherwise and the page has no business in a release. Move it out of `src/routes` (or behind `dev`).

### R34. Auth odds and ends. **Low**, CONFIRMED
- `/v1/auth/logout` deletes the token server-side but never clears the cookie (no `Max-Age=0`), and the web then does `localStorage.clear()`, wiping unrelated preferences (dark mode, sort order, volume) along with tus resume data. [AuthController.kt:28](../server/src/main/kotlin/org/filemat/server/module/auth/controller/AuthController.kt#L28), [LogoutSetting.svelte:16](../web/src/routes/(app)/settings/account/_setting-components/LogoutSetting.svelte#L16)
- `is_banned` exists in the schema, the model, the principal and the login check, but nothing can set it: no endpoint, no service method. Either a dead feature or a missing one; if added later, note that the principal cache and existing tokens are never re-checked against it.
- `web/src/lib/code/auth/auth.svelte.ts` is an empty file.
- `Principal.roles` is a `MutableList` shared by every request thread and mutated in place by `removeRoleFromAllPrincipals`, `assignRole` and `removeRoles` (`roles.add`/`remove` inside `computeIfPresent`, but readers iterate it without a lock): a concurrent permission check can hit `ConcurrentModificationException`. Use an immutable list and replace it with `copy(roles = …)`.
- Auth tokens live 1 year with no sliding expiry or idle timeout, and there is no UI to list or revoke other sessions (only "log out all" on password change).

### R35. Errors swallowed without a trace. **Low**, CONFIRMED
- `EntityService.move` wraps the whole transaction in `runCatching { … }.getOrElse { Result.error("Failed to move file.") }` and drops the exception: a failed DB update during a move (the file is then moved back) leaves nothing in the log to explain it. [EntityService.kt:316](../server/src/main/kotlin/org/filemat/server/module/file/service/EntityService.kt#L316)
- `MfaService.disable` ignores the result of `updateUserMfa` (R10).
- `FileDeletionService.deleteFiles` turns every failure into a message, but `FilesystemDeletionService.deleteRecursiveSafe` returns `failedCount` *without* incrementing when `entityService.getByPath` errors, so a DB failure after a successful delete leaves a stale entity and reports success.
- `addFileToZip` returns silently (empty zip) when the share or path can't be resolved.

### R36. Each password hash takes 64 MB, with no limit on concurrent hashes: memory exhaustion. **Medium**, CONFIRMED
[Config.kt:19](../server/src/main/kotlin/org/filemat/server/config/Config.kt#L19)
`Argon2PasswordEncoder(16, 32, 2, 65536, 5)` uses 64 MB and 5 passes per hash. Hashes run on request threads for login (existing users), share login, share creation (before the permission check, R21), password change and user creation. Tomcat's default pool is 200 threads: 200 parallel logins against a known username need ~12.8 GB, and the per-IP limit doesn't stop them (R1). The JVM runs out of heap (or the zip's default `-Xmx`, whatever the JRE picks) and every user is affected.
Fix: a small semaphore around `encode`/`matches` (e.g. CPU count), returning 429 when it's full; keep the cost, cap the concurrency. Fixing R4 with a dummy hash makes unknown usernames cost the same, so the cap matters more after that fix.

### R37. The default Linux upload folder is `/tmp/filemat`. **Low** (multi-user hosts), CONFIRMED
[Props.kt:44](../server/src/main/kotlin/org/filemat/server/config/Props.kt#L44)
On a host with other local accounts, `/tmp` is world-writable: someone can create `/tmp/filemat` (or a link of that name) before Filemat starts. `realInternalFolder` follows links and only refuses a root or the data folder, so the upload folder then belongs to (or points to) the other account, which can read every upload in progress and plant `info` files (the `SerialFilter` comment already assumes this). Uploads in progress are created with the default umask, so they may be readable by other accounts even without that. systemd's `tmpfiles` cleaning can also delete in-progress uploads. Docker images aren't affected. Prefer a folder inside the data folder on Linux too (as Windows does), or `/var/lib/filemat/uploads`.

### R38. On Linux, file keys on FAT, exFAT, NTFS-3G, CIFS and NFS mounts count as stable. **Medium**, PLAUSIBLE
[FileIdentity.kt:52](../server/src/main/kotlin/org/filemat/server/config/platform/FileIdentity.kt#L52)
`isSupported` on Linux is `getFileStore(path).supportsFileAttributeView("unix")`, which is true for every mounted filesystem, `vfat`, `exfat`, `cifs` and `nfs` included. On Windows the same volumes are rightly treated as "path only". On Linux `vfat`/`exfat` derive inode numbers from the directory position (they change on rename and are reused right away), CIFS inode numbers may be synthesised per mount, and `dev` numbers of USB disks change between mounts. With R15's reconnect logic, permissions and shares on a USB stick or SMB mount can attach to other files after a rename or remount. The `unstableMountType` list only covers Docker Desktop/WSL host mounts, and only to print a warning.
Fix: read the mount type from `/proc/self/mounts` (code for that already exists) and treat `vfat`, `exfat`, `msdos`, `ntfs`/`ntfs3`/`fuseblk`, `cifs`, `smb3`, `nfs*`, `fuse.*` and the Docker Desktop types as unsupported, like Windows does.

### R39. The Linux sensitive-folder list is much thinner than the Windows one. **Medium**, CONFIRMED
[SensitiveFolderPaths.kt:47](../server/src/main/kotlin/org/filemat/server/config/properties/SensitiveFolderPaths.kt#L47)
Windows blocks cloud credentials, browser profiles, `.git-credentials`, `.netrc`, shell history and more. Linux blocks only `.ssh` and `.gnupg` under `/home/*`, and nothing for: `/proc` (an exposed `/` serves `/proc/self/environ`, the server's own environment, and `/proc/<pid>/root` links to the whole filesystem when links are followed), `/sys`, `~/.aws`, `~/.kube`, `~/.docker/config.json`, `~/.config` (browser profiles, `gh` tokens, rclone), `~/.mozilla`, `~/.git-credentials`, `~/.netrc`, `~/.bash_history`, `~/.local/share/keyrings`, `/var/lib/filemat` when it isn't the data folder, `/etc/letsencrypt`, home folders outside `/home` (`/var/home` on Fedora Atomic, `/Users` on macOS, which runs the POSIX list). Exposing a home folder on Linux is the most common setup, so this is where the list matters most.
Fix: bring the POSIX list to parity with the Windows one (same tool folders under `/home/*` and `/root`), add `/proc` and `/sys`, and build the home patterns from `/etc/passwd` home directories rather than assuming `/home/*`.

### R40. Searches, zips and folder-size counts hold read locks that block every write below them. **Medium**, CONFIRMED
[FileUtils.kt:87](../server/src/main/kotlin/org/filemat/server/common/util/FileUtils.kt#L87), [FileLockService.kt:113](../server/src/main/kotlin/org/filemat/server/module/file/service/FileLockService.kt#L113), [FileContentService.kt:238](../server/src/main/kotlin/org/filemat/server/module/file/service/file/component/FileContentService.kt#L238)
`safeWalk` takes a READ lock on each folder it enters and holds it until the folder's whole subtree is walked; `zipRecursiveSafe` does the same. `isAncestorLocked` refuses a WRITE when any ancestor has a read lock. So while any user searches `/home` (rate-limited, but one search over a big tree runs for minutes) or downloads a large folder as a zip, every upload, rename, delete, edit and new folder anywhere below fails at once with "This file is currently being modified". A slow zip download client holds the locks as long as it likes. A user with read access to a shared root can keep everyone else from writing.
Fix: a walk needs consistency for the file it is reading, not the whole tree. Lock each file only while it is read (zip) and don't lock folders during search/size at all.

### R41. Account identity: inconsistent e-mail normalisation, case-sensitive usernames, a 4-character minimum password. **Low**, CONFIRMED
[AdminUserController.kt:125](../server/src/main/kotlin/org/filemat/server/module/admin/controller/AdminUserController.kt#L125), [StringUtils.kt:25](../server/src/main/kotlin/org/filemat/server/common/util/StringUtils.kt#L25), [Validator.kt:28](../server/src/main/kotlin/org/filemat/server/common/util/Validator.kt#L28)
- Only admin user creation runs `normalizeEmail`, which *rewrites* the stored address (drops dots and `+tags` for Gmail). Setup, `changeEmail` and login use the address as typed. A user created as `john.doe@gmail.com` is stored as `johndoe@gmail.com` and can't log in with the address they were given. None of the paths fold case, so `Bob@x.com` and `bob@x.com` are two accounts.
- Usernames are compared case-sensitively (`admin` and `Admin` are different accounts) and accept any Unicode letter, so `аdmin` (Cyrillic а) can sit next to `admin` in user lists and logs.
- `Validator.password` accepts 4 characters. With R1 that is guessable in minutes. NIST's minimum is 8, with a breached-password check if possible.
- `Result.reject("")` is returned in `AdminUserRoleService.assignRole`, so the UI shows an empty error when a role is above the caller's level.

### R42. Renaming or moving a folder rebuilds it file by file: folder permissions are lost, and it isn't atomic. **Medium**, CONFIRMED
[FilesystemMoveService.kt:133](../server/src/main/kotlin/org/filemat/server/module/file/service/filesystem/fileOperation/FilesystemMoveService.kt#L133)
`moveRecursiveSafe` never moves a folder as one item, even for a rename in place on the same volume: it creates a *new* folder at the destination (`createFoldersLike` → `Files.createDirectories`), moves each file into it, then deletes the old folder. Consequences:
- **The folder's own permissions are lost.** On Linux the new folder gets the process umask (a `0700` private folder renamed through Filemat becomes `0755`, readable by other local accounts); owner, group, setgid bit, ACLs and xattrs are gone. On Windows the new folder inherits the parent's ACL, dropping explicit entries. The folder's mtime and file ID change.
- **It isn't atomic.** A crash, a full disk or a file in use halfway leaves the tree split across two folders; other clients see a half-moved folder while it runs.
- **It's slow**: one permission check, one DB transaction and one `getAllByPathPrefix` (a full table scan, R43) per file, while WRITE locks are held on both trees. Renaming a photo folder with 20 000 files takes as long as copying it.
- Children are moved *before* the folder's own MOVE/RENAME right is checked; when the folder's check fails, the already-moved children stay at the destination and the source folder stays behind.
Fix: first try `Files.move(folder, dest, ATOMIC_MOVE)` when the user may move everything inside (`canRenameAll` already exists for exactly that check) and the volume is the same; fall back to the per-file path only for cross-volume moves or partial rights. Copy the folder's attributes (POSIX mode/owner, ACL) when a folder has to be recreated.

### R43. Every path-prefix query is a full table scan. **Low** (performance), CONFIRMED
[EntityRepository.kt:23](../server/src/main/kotlin/org/filemat/server/module/file/repository/EntityRepository.kt#L23), [SavedFileRepository.kt:31](../server/src/main/kotlin/org/filemat/server/module/savedFile/repository/SavedFileRepository.kt#L31)
`SUBSTRING(path_key, 1, LENGTH(…)) = CONCAT(…)` can't use `idx_files_path_key`, so `getAllByPathPrefix` reads every row of `files`. It runs once per moved item (R42) and once per `repairEntityIdentity` orphaning. A range on the key does the same exact comparison and uses the index: `path_key = :k OR (path_key >= :p AND path_key < :q)` with `:p = rtrim(k,'/') || '/'` and `:q = rtrim(k,'/') || '0'` (`'0'` follows `'/'` in code-point order). (`SavedFileRepository` also sits in the `sharedFile.repository` package.)

### R44. Folder uploads: one global session cap, abandoned sessions hold slots for 48 h, and quadratic manifest checks. **Low** (DoS by logged-in users), CONFIRMED
[FolderUploadService.kt:69](../server/src/main/kotlin/org/filemat/server/module/file/service/FolderUploadService.kt#L69), [FolderUploadService.kt:753](../server/src/main/kotlin/org/filemat/server/module/file/service/FolderUploadService.kt#L753)
- `maxActiveSessions = 128` is server-wide, and a session only goes away when *every* file in it completed or after 48 h. A closed tab or a cancelled upload keeps its slot for two days. Eight users each with 16 abandoned uploads (the per-user cap) block folder uploads for everyone. There is no endpoint to cancel a session.
- `normalizeManifest` checks every file against every directory (`filePaths.forEach { directoryPaths.firstOrNull { … startsWith … } }`): with the 20 000 + 20 000 limits that is 4·10⁸ string comparisons. `createSession` runs `normalizeManifest` and then `preflight`, which runs it again, plus one permission check per distinct folder and one `exists` per file, all on the request thread.
- `createSession` creates every folder of the manifest before the first byte arrives; a cancelled upload leaves an empty tree behind.
Fix: count sessions per user only (or expire idle sessions after, say, an hour without a PATCH), add a cancel endpoint, check "file used as folder" with a set of directory prefixes, and run the manifest normalisation once.

### R45. One file vanishing during a listing fails the whole folder; folder size counts hidden folders. **Low**, CONFIRMED
[FileFolderEntriesService.kt:174](../server/src/main/kotlin/org/filemat/server/module/file/service/file/component/FileFolderEntriesService.kt#L174), [FileFolderEntriesService.kt:125](../server/src/main/kotlin/org/filemat/server/module/file/service/file/component/FileFolderEntriesService.kt#L125)
- `internalGetFolderEntries` lists the folder, then stats each entry and throws `IllegalStateException("Metadata object was null for a known file.")` when one is gone. A folder where programs create and delete temp files (Downloads during a browser download, a build folder) intermittently fails to open with "Failed to get contents of this folder" and an ERROR log row. Skip entries whose metadata is null.
- `calculateFolderSize` checks READ on the requested folder only and counts every descendant, including the data folder, sensitive folders (`.ssh`) and folders the user is explicitly denied. It reveals how many files and how many bytes they hold. Documented in the KDoc, but it's still a leak; skip what `isPathAllowed` blocks at least.
- For a *file* inside a share, `getSharedFileOrFolderEntries` returns `filesystemService.getMetadata` without `isPathAllowed`, so the name, size and dates of a blocked file inside a shared folder (`/.ssh/id_rsa` in a shared home) are answered; only the content request is refused.
- Every listing runs `verifyEntityIdentity` (DB lookup + file key read) and a permission-tree walk per entry: listing a folder of 10 000 files costs 10 000 DB lookups and 10 000 extra `CreateFile`/`stat` calls.

### R46. On Linux, FFmpeg runs in-process with no concurrency limit and no timeout. **Medium**, CONFIRMED
[JavacvBackend.kt:12](../server/src/main/kotlin/org/filemat/server/module/file/service/file/media/JavacvBackend.kt#L12), [MediaPreviewService.kt:78](../server/src/main/kotlin/org/filemat/server/module/file/service/file/media/MediaPreviewService.kt#L78)
The hardening in `ExternalFfmpegBackend` (at most 1–4 processes, 2 s slot wait → 503, 30 s kill, separate process) only exists on the Windows path. Linux (Docker included) uses `JavacvBackend`, which decodes inside the JVM with none of it:
- no limit: every thumbnail, video preview and content-metadata request decodes at once (`content-metadata-batch` probes up to 2 500 files per request), on request threads;
- no timeout: a file that makes the demuxer or decoder loop or stall holds a Tomcat thread (and a READ lock, R40) forever;
- in-process: a decoder crash or memory corruption on a crafted file takes down the whole server, where on Windows only an `ffmpeg.exe` dies.
All of this is reachable without an account through a public share of a folder with videos. FFmpeg has a long CVE history in demuxers, so "attacker-supplied media decoded inside the main process" deserves the same care Windows got.
Fix: wrap `JavacvBackend` in the same `Semaphore` + `FfmpegBusyException`, run it on a bounded executor with a `Future.get(timeout)` and `grabber.close()` on timeout, or simply use `ExternalFfmpegBackend` on Linux too when an `ffmpeg` binary is present (it is in the Docker image's distro packages).

### R47. Image thumbnails: no concurrency limit, sizes up to 8192 px, and every size is a separate cache entry. **Medium**, CONFIRMED
[ThumbnailService.kt:49](../server/src/main/kotlin/org/filemat/server/module/file/service/file/ThumbnailService.kt#L49), [FileUtilController.kt:70](../server/src/main/kotlin/org/filemat/server/module/file/controller/FileUtilController.kt#L70)
`size` accepts any value 1..8192. An 8192 × 8192 output alone is a 268 MB `BufferedImage`, plus the decoded source; images are decoded on request threads with no semaphore (only FFmpeg has slots). Forty parallel `size=8192` requests (e.g. a share page of photos, unauthenticated, per-IP limit bypassable with R1) exhaust a default heap. When the header can't be read, the source is decoded in full (`scale(1.0)`), so a decompression bomb (a small PNG declaring 50 000 × 50 000) is decoded at full size. The windows status lists the full decode as accepted, but not the concurrency.
Also: every distinct `size` up to 2048 is cached as its own file, and `saveAndStreamThumbnail` rescans the whole cache folder after each generation (`cleanCache(targetPathHash)` lists every entry), so requests for sizes 1..2048 fill the cache to `maxSizeMb` and cost O(cache) per request. A client that disconnects during generation leaves a truncated JPEG whose header still passes `isCacheFileValid`, and it is served broken until the file changes.
Fix: allow a few fixed sizes (the web uses a handful), cap parallel image decodes with a semaphore, refuse sources whose header size exceeds a pixel budget, write the cache file to a temp name and rename it when complete, and key the per-file cleanup by a directory listing glob only once in a while.

### R48. `MANAGE_SYSTEM` can point the upload or thumbnail folder into a folder they can read, and see other users' files. **Medium**, CONFIRMED
[State.kt:51](../server/src/main/kotlin/org/filemat/server/common/State.kt#L51), [SettingService.kt:29](../server/src/main/kotlin/org/filemat/server/module/setting/service/SettingService.kt#L29), [ThumbCacheSettingService.kt:155](../server/src/main/kotlin/org/filemat/server/module/setting/service/component/ThumbCacheSettingService.kt#L155)
`realInternalFolder` only refuses a root and the data folder. A user with `MANAGE_SYSTEM` but without `ACCESS_ALL_FILES` (a "system admin" role) can set the upload folder to a folder exposed to them, then read every other user's upload while it is in progress (`uploads/<id>/data` holds the bytes until the last chunk), or set the thumbnail cache folder there and get downscaled copies of everyone's photos and video frames as other users browse them. Neither setting needs the physical-access code (R29). Both folders are also created with default permissions (`mkdirs`), so on Linux `/var/cache/filemat` thumbnails of private photos are world-readable on the host.
Fix: refuse an internal folder that is inside (or contains) an exposed folder (`visibilityTrie` says exposed) and create it owner-only like the data folder; or require the sensitive code for these settings.

### R49. The setup code is never removed from the database, and some secret files aren't private on Linux. **Low**, CONFIRMED
[SettingService.kt:173](../server/src/main/kotlin/org/filemat/server/module/setting/service/SettingService.kt#L173), [AppService.kt:36](../server/src/main/kotlin/org/filemat/server/module/service/AppService.kt#L36), [SensitiveAuthService.kt:73](../server/src/main/kotlin/org/filemat/server/module/auth/service/SensitiveAuthService.kt#L73)
- `SettingService.removeSetting` calls `settingRepository.getSetting(name)` instead of `removeSetting(name)`, so `deleteSetupCode` leaves `application_setup_code` in the `settings` table forever (and returns success). `SettingRepository.removeSetting` exists and is unused.
- The setup code file is created `rwxrwx---` (group can read and change it, and it's marked executable). The admin auth code (`auth-code.txt`, the "physical access" proof) is written with `File.writeText` on Linux, so it gets the umask (usually `0644`); only Windows makes it private. The data folder is `rwxr-x---`, so the group of the Filemat account can read both.
- Both codes are also printed to stdout, which on Linux goes to journald or `docker logs`, readable by anyone in the `docker`/`adm` groups; on Windows it goes to `logsilemat.log`. "Proof of physical access" is really "proof of access to the logs".
- `set_followSymLinks` writes the DB and logs "Symlinks enabled" even when `FM_FOLLOW_SYMBOLIC_LINKS` locks the value, so the setting silently doesn't change; `set_thumbnail` updates `state.thumbCache.folderPath` before the DB write, so a failed write leaves memory and DB disagreeing.

### R50. Admin user creation skips the e-mail and username checks. **Low**, CONFIRMED
[AdminUserController.kt:127](../server/src/main/kotlin/org/filemat/server/module/admin/controller/AdminUserController.kt#L127)
```kotlin
Validator.email(email)
    ?: Validator.username(username)
    ?: Validator.password(rawPassword)
    ?.let { return bad(it, "validation") }
```
`?.` binds tighter than `?:`, so this is `email ?: username ?: (password?.let { return … })`. An invalid e-mail or username produces a message that is thrown away, and the user is created anyway; only a bad password returns 400. `SetupController` wraps the chain in parentheses and is right. An admin can create `"john smith"`, a 10 000-character name, or a username with `@` (which login then routes to the e-mail lookup), and such accounts can't log in because `LoginService` validates the username. Fix: parenthesise as in setup.

### R51. System role defaults differ between fresh and upgraded installs; system roles can be edited but revert. **Low**, CONFIRMED
[RoleService.kt:232](../server/src/main/kotlin/org/filemat/server/module/role/service/RoleService.kt#L232), [Initialization.kt:30](../server/src/main/kotlin/org/filemat/server/config/Initialization.kt#L30)
- Migrations run before `createSystemRoles`. On a fresh database V4 and V7 find no roles, then `createSystemRoles` creates `user` with *no* permissions. An install that was upgraded through V7 has `CHANGE_OWN_HOME_FOLDER` on `user`. Same release, different defaults.
- `updatePermissionList` accepts the `admin` role, but every start resets it to all permissions, so an edit lasts until the next restart. Either refuse edits of system roles or stop overwriting them.
- `MANAGE_OWN_FILE_PERMISSIONS` is enough to list every username (`/v1/user/minilist?allUsers=true`); fine for a picker, but worth knowing that it's an enumeration of all accounts for anyone who can share their own files.

### R52. System errors are dropped by a hidden rate limit; session tokens are stored in plain text. **Low**, CONFIRMED
[LogService.kt:90](../server/src/main/kotlin/org/filemat/server/module/log/service/LogService.kt#L90), [AuthTokenRepository.kt:13](../server/src/main/kotlin/org/filemat/server/module/auth/repository/AuthTokenRepository.kt#L13)
- `LogService.log` keeps one `SYSTEM` log per *calling method* per 10 seconds and silently drops the rest. Twenty different files failing to delete in one operation leave one row; a "failed to save auth token" right after an unrelated error from the same method is lost. Nothing says that rows were dropped. Each log call also builds a full stack trace (`getActualCallerPackage`) just to find the caller.
- `metadata["package"] = caller` is put into a *new* map when `meta` is null, but `createLog` is then called with the original `meta`, so the package is recorded only for logs that already had metadata (and the caller's map is mutated).
- `getLogs` logs its own failure with `initiatorId = meta.targetId` (always null there) instead of `meta.initiatorId`.
- `auth_token.auth_token` holds the raw cookie value. A copy of the database (the migration `.bak` files next to it, a backup, a stolen disk) gives a year of valid sessions for every user. Store `SHA-256(token)` and look up by hash; the token already has 128 random characters, so no salt is needed.

### R53. Public share links are whatever word the user types. **Medium**, CONFIRMED
[FileSharingDialog.svelte:89](../web/src/routes/(app)/(files)/files/[...path]/_elements/ui/fileSharing/FileSharingDialog.svelte#L89), [FileShareService.kt:203](../server/src/main/kotlin/org/filemat/server/module/sharedFile/service/FileShareService.kt#L203)
The share dialog has one free-text "link" field and no generated default; the server accepts any non-taken string as the share ID, and for shares without a password that ID *is* the access token. Users will type `report`, `photos`, `invoice`. Anyone can then enumerate common words against `/v1/file/share/get-password-status` (says whether a share exists… returns `false` for both "no share" and "no password", but `/get-metadata` with the ID as token answers 200 with the file name for an existing non-password share) at 30 + 0.5/s per IP, unlimited with R1, and read the files. This upgrades R21's last point.
Fix: generate a random ID (≥ 22 URL-safe characters) by default and let the user add a readable *prefix*; or require a password for short, dictionary-like IDs.

### R54. Web file handling bugs. **Low**, CONFIRMED
- **Text viewer downloads the whole file into memory.** `loadFileContent` → `streamFileContent` reads the full response into a `Blob`, then `blob.text()` into CodeMirror, with no size check. Opening a multi-GB `.log`, or "open as text" on any large file, hangs or crashes the tab. Cap text view at a few MB and offer download instead. [files.ts:156](../web/src/lib/code/module/files/files.ts#L156)
- **Saving a folder to disk follows folder-link cycles forever.** `planPathForDownload` recurses into every entry `isFolder` accepts, and with follow-links on that includes `FOLDER_LINK`. A link to an ancestor (`/data/loop -> /data`) makes the client list `/data/loop/loop/loop/…` until the server's path length limit, building a huge plan first. The server's zip has cycle detection; the client walk has none. [files.ts:1340](../web/src/lib/code/module/files/files.ts#L1340)
- `deleteFiles` builds its network-error toast from `entries.join('
')`, and `entries` are objects: the user sees `"[object Object]"`. [files.ts:1081](../web/src/lib/code/module/files/files.ts#L1081)
- `moveMultipleFiles` reads `response.code` without checking `response.failed`, so a network error throws `TypeError` instead of a toast. [files.ts:1854](../web/src/lib/code/module/files/files.ts#L1854)
- `getFileExtension` returns the whole name when there is no dot, so files named `pdf`, `mp4` or `log` open in the PDF, video or text viewer. [codeUtil.svelte.ts:470](../web/src/lib/code/util/codeUtil.svelte.ts#L470)
- `sortFileMetadata` keeps only `FILE`/`FILE_LINK` and `FOLDER`/`FOLDER_LINK` when folders aren't mixed with files: sockets, FIFOs and other `OTHER` entries silently vanish from listings.
- `isFile` checks a `fileType` of `ANY_LINK`, which the server never sends.
- `chunkSize: 64 * 1024 * 1024, // 128 MB chunk`.
- When the client sees a 404 from `HEAD` on an upload whose bytes it had sent, it marks the upload "already completed", but a 404 also follows the 48 h expiry of an upload whose finalize failed; the user is told it succeeded when it didn't. [files.ts:665](../web/src/lib/code/module/files/files.ts#L665)

### R55. More web/server mismatches and small bugs. **Low**, CONFIRMED
- **Users with non-ASCII usernames can't log in by name from the web.** The web `Validator.username` allows `[A-Za-z0-9_-]` only; the server's allows any Unicode letter or digit (`isLetterOrDigit`). An account `Jürgen` (the Windows test plan's W14 user) can be created by an admin, but the login form refuses the name before sending it. [validation.ts:48](../web/src/lib/code/util/validation.ts#L48)
- **tus uploads aren't tied to the user who started them.** `HEAD`/`PATCH`/`DELETE /api/v1/file/upload/{id}` only need *a* login; the upload ID (a UUID) is the only secret. Anyone who learns one (shared computer: tus keeps upload URLs and their `path` metadata in `localStorage`, which survives a session expiry, not just a logout) can read the target path and size, append to, finalize or cancel another user's upload. Store the owner in the upload metadata at POST and compare it on every request.
- `startStateAutoSync` says "every 20 seconds, unless idle (then 60)" and does the opposite: `clientState.isIdle ? 20_000 : 60_000`. [stateFetcher.ts:198](../web/src/lib/code/state/stateFetcher.ts#L198)
- Setup step 1 calls `handleException` on a network error and then falls through to `response.code.ok`, which throws on the failed result. [setup/+page.svelte:97](../web/src/routes/(external)/setup/+page.svelte#L97)
- The share password form checks `if (loading) return` but never sets `loading = true`, so double-submits go through (each one costs an Argon2 hash on the server, R36). [share/[shareId]/[...path]/+page.svelte:126](../web/src/routes/(app)/(files)/share/[shareId]/[...path]/+page.svelte#L126)
- `getContentUrl` puts `shareToken=${token}` into the URL without encoding. IDs typed in the UI are restricted to URL-safe characters, but the server stores `encodePathSegment(sharePath)` (so `%` sequences are possible) and API clients can send anything.
- `assignRole` failures caused by rank map to 404 with an empty message (`Result.reject("")` → `notFound()`), so the UI shows "not found" for "not allowed".
- Dropping two folders at once nests them under a synthetic `Dropped folders` folder on the server, which the user didn't ask for.
- `loadPageData` handles a failed load by navigating to the parent, then carries on with `result.value` (undefined) and reads `data.meta`: the `if (!result) return null` guard can never fire (a `Result` object is always truthy). When the navigation doesn't change `lastFilePathLoaded` (an error at `/`), it throws. [pageLogic.ts:98](../web/src/routes/(app)/(files)/files/[...path]/_code/pageLogic.ts#L98)
- `(app)/+layout.svelte` adds a `beforeunload` listener on every mount and never removes it; the root layout adds the same check again.

### R56. Build, image and release pipeline. **Medium** (container runs as root), CONFIRMED
[server/Dockerfile](../server/Dockerfile), [create-docker-image-release.yml](../.github/workflows/create-docker-image-release.yml), [build.gradle.kts](../server/build.gradle.kts)
- **The Docker image runs Filemat as root.** There is no `USER` line. Inside the container Filemat can read and write every bind-mounted file regardless of its owner, so any server-side bug (R23, R46's in-process decoder) runs with root's view of the mounts. The docs mention `user: 1111:1111` in compose, but the default is root. Add a non-root user, `VOLUME /var/lib/filemat`, and a `HEALTHCHECK`.
- **Every published release, betas included, is pushed as `latest`.** `docker pull bingud/filemat` gets whatever was released last.
- `workflow_dispatch`'s `release_tag` is interpolated straight into `run:` scripts (`"${{ inputs.release_tag || github.ref_name }}"`). Only people who can dispatch the workflow can use it, but pass it through `env:` to avoid shell injection. Actions are pinned by tag, not by commit SHA. Tests do run (`gradlew build` includes `test`), but only on release, never on pushes or PRs.
- The base image `eclipse-temurin:21-jre-jammy` is a floating tag, so two builds of the same commit can differ.
- Dependencies are dated: Spring Boot 3.4.2 (January 2025; the 3.4 line is out of OSS support), Tomcat and Spring fixes since then are missing; `metadata-extractor` is declared and never used; `kotlin("jvm") 1.9.25` sits next to `kotlin("plugin.serialization") 2.1.10`, so the Kotlin version actually used is not the one the build says. No dependency or vulnerability scanning (Dependabot/Renovate, OWASP dependency-check, `npm audit`). The web build relies on `magic-string` (imported in `vite.config.ts`) without declaring it.
- The JAR ships javacv's Linux x64 and arm64 FFmpeg natives in every build, the Windows zip included, where they can't load.

### R57. In search results, the trash button deletes the folder view's selection, not the selected search results. **Medium** (data loss), CONFIRMED
[fileActions.ts:35](../web/src/routes/(app)/(files)/files/[...path]/_code/fileActions.ts#L35), [+page.svelte:361](../web/src/routes/(app)/(files)/files/[...path]/+page.svelte#L361)
`filesState.selectedEntries` keeps two selections: `list` for the folder view and `searchList` for search results. Starting a search doesn't clear `list`. The trash button is shown whenever `hasSelected` (search-aware) is true, including in search mode, and `option_deleteSelectedFiles` then:
- reads `selectedEntries.list` and deletes `selectedEntries.metaList`, both from the **folder view**,
- but writes the confirmation text from `selectedEntries.count`, which is the **search** count, and names no files.

So: click `report.pdf` in a folder (a single click selects it), search, select one result, press trash, confirm "delete 1 selected file" → `report.pdf` is deleted, the search result stays. With nothing selected in the folder view the button silently does nothing. Move is hidden in search mode, so only delete is affected.
Fix: use `activeMetaList` (already defined: search-aware) in `option_deleteSelectedFiles`, list the file names in the dialog, and clear `list` when a search starts.

### R58. Web UI odds and ends from the last pass. **Low**, CONFIRMED
- **Removing an exposure rule looks done even when it failed.** The exposed-files page deletes the row from its list after `doRequest` whatever the answer (wrong or expired code, server error). Combined with R29 (the server answers OK even when the DB delete failed), an admin can believe a "hide" or "expose" rule is gone when it isn't. [exposed-files/+page.svelte](../web/src/routes/(app)/settings/system/exposed-files/+page.svelte)
- **The web's permission levels differ from the server's.** `data/permissions.ts` gives `ACCESS_ALL_FILES` level 1 and `MANAGE_ALL_FILE_PERMISSIONS` level 2; the server's `SystemPermission` has them as 2 and 1. The role editor and "new role" page offer or hide permissions by the web's numbers, and the System and Users settings pages open for any level-3 permission (an `EDIT_ROLES`-only user sees System settings and gets 403s). The server already sends roles; it could send the levels too.
- **Page titles pile up.** `TitleState.register` builds a cleanup function inside `untrack(...)` but doesn't return it, so `$effect(() => appState.title.register(...))` never removes its segment. Every folder opened adds one to `appState.title.segments`, and a page that registers no title shows the previous page's.
- `hasAnyPermission` pushes `"SUPER_ADMIN"` into the array it is given (callers pass literals today, so it's harmless, but it's a trap).
- **Upload file handles outlive the session.** Logout clears `localStorage` (R34) but not the IndexedDB `filemat-upload-handles` store, so the next person on that browser profile is offered to resume the previous user's uploads from their local files (with a permission prompt).
- Incomplete uploads restored from a previous session count as "blocking", so every tab close asks "Leave site?" until each one is resumed or cancelled.
- The "Raw Content" menu entry links the content URL in the same tab, which is one more way for a user to open an uploaded HTML file on the app's origin (R13).
- Selecting all entries is quadratic (`addSelected` does `list.includes` per path); noticeable in folders with tens of thousands of files.

### R8. Any 500 from the token lookup writes the internal error text into the response. **Info**
[AuthenticationFilter.kt:27](../server/src/main/kotlin/org/filemat/server/module/auth/filter/AuthenticationFilter.kt#L27)
A database error while looking up the cookie's token fails *every* request carrying that cookie, including `@Unauthenticated` ones like `/v1/auth/logout` and public share access, with a plain-text 500. Not a hole, but a logged-in user can't even log out while the DB is unhealthy.

## General findings

Observations about the code base as a whole, written along the way. Each is a habit or a gap, with examples; none is a single bug.

### Security architecture

- **Authorization is fail-open and spread out.** The filter allows anything it can't match (R3), and the real checks live in each service as hand-written `isAllowedTo…` calls before each operation. Every new code path must remember the right call on the right path (resolved vs raw), and several didn't (R9, R23, R27, R21). A single "resolve, then authorize, then return a capability object" step that file operations *must* go through (e.g. `AuthorizedPath` that only `FileSecurityService` can construct) would make the raw/unchecked path unrepresentable.
- **"Raw path vs canonical path" is the recurring bug class.** M4 (Windows review), R21, R23 and several `rawPath` uses for display/lookup are all the same mistake. `FilePath` doesn't encode whether it was resolved. A `CanonicalPath` type (only produced by `resolvePath`) and services that accept only it would let the compiler catch these.
- **In-memory state duplicates the DB and drifts.** Principal cache, token cache, permission tree, entity maps, visibility trie, CORS registry, share-token map, folder-upload sessions. Each has its own invalidation code, and R16, R25 and R34 are drift bugs. Several are only reconciled on restart. Prefer one source of truth with short-lived caches, and add "rebuild equals live" tests for the ones that stay.
- **Hand-rolled concurrency primitives.** Lock maps with remove-on-unlock (R17, R22), non-thread-safe `HashMap`/`mutableMapOf` mutated under readers (`endpointAuthMap`, `VisibilityTrie.children`, `Principal.roles`), `var` flags like `isCleanupRunning` without synchronisation. Use `ConcurrentHashMap` everywhere state is shared, immutable snapshots for read-mostly structures, and a striped lock library instead of ad-hoc maps.
- **No defence-in-depth headers or CSP** (R13, R14). For an app whose job is to serve untrusted files, a strict CSP on the SPA and a sandbox CSP on content are the baseline, not an extra.
- **Trusting client-supplied headers** (`X-Forwarded-For`, `X-Forwarded-Proto`, `Host`) without a trusted-proxy setting (R1).
- **Privilege model is coarse.** Numeric "levels" on system permissions allow equals to grant each other everything (R9). Admin actions on *other users* don't compare privilege at all except delete. The model needs a written rule ("you may only act on users whose permissions are a subset of yours; you may only grant what you hold") applied in one helper.

### Error handling and results

- **`Result` everywhere, but status mapping is ad hoc.** Controllers map `notFound` to 400, 404 or 500 depending on the author (thumbnail access denied → 500 "Failed to get file metadata", share "not found" → 400 "This file was not found."). `Result.reject` means both "forbidden" and "bad input". A small `Result → ResponseEntity` mapper with a distinct `forbidden` kind would make responses consistent and let the web tell "no access" from "missing".
- **`!!` on the principal in 49 controller methods** (`request.getPrincipal()!!`) is the only thing standing between an auth-map miss and an unauthenticated call (R3). AGENTS.md says to avoid `!!`; a `@CurrentUser principal: Principal` argument resolver that throws 401 would be both safer and shorter.
- **Errors swallowed or logged without context** (R35), `runCatching { … }` around whole operations, `catch (e: Exception) { return Result.error("Failed to …") }` (121 `catch (e: Exception)` blocks in main code) with `logService.error(..., e.stackTraceToString())` copy-pasted. Most service methods are 60 % boilerplate try/catch/log. A helper (`dbCall(action, "message") { … }`) would cut this in half and make the failure paths uniform.
- **Silent partial results.** Zip skips, search skips, copy/delete partial failures: the user often can't tell that something was left out (R24).

### Code organisation and style

- **Huge files with mixed concerns.** `files.ts` (1947 lines), `codeUtil.svelte.ts` (946), `fileBrowserUtil.svelte.ts` (896), `FolderUploadService.kt` (1001), `ThumbnailService.kt` (943), `utilFunctions.kt` (630, a grab-bag of path security, HTTP helpers, time formatting, base64, ranges). Path-security code in `utilFunctions.kt` next to `formatMillisecondsToReadableTime` hides the most security-critical functions in the least obvious place.
- **`@Lazy` circular dependencies.** `FileService` ↔ `FileSecurityService` ↔ `EntityService` ↔ `EntityPermissionService` ↔ `FileShareService` are wired with `@Lazy` in many places. It works, but it means the module boundaries don't exist; `FileService` is a facade that forwards to nine services which call back into it.
- **Debug leftovers in production code:** `delay(1000)` with the real value commented out (R5), `todo()` helper, `runDev`, the `/util` page (R33), 38 `console.log` in the web, a commented-out `STATIC_ASSETS` list in the service worker, "now nullable" comments in SQL.
- **Typing shortcuts in the web:** 43 `any`/`as any`, `safeFetch` mutates the native `Response` and the caller's `RequestInit` (`fetch(url, arg) as any as SafeFetchResult`, `arg.credentials = …`).
- **Naming that misleads:** `tokenToUserIdMap` maps token → `AuthToken`; `hasSufficientPermissionsFor` compares max levels only; `createSafePreview` isn't safe; `FileShare.followSymlinks` is a `const val false` in a data class companion; `isExposed = true` means "hidden" in `hiddenFileTrie` (commented, but still a trap).
- **Copy-paste** (R31), `@RequestParam("isExposed") isExposed: Boolean` next to the project's usual `raw…String` + `toBooleanStrictOrNull()` pattern, three spellings of the same "parse ULID or 400" dance.

### Data and persistence

- **No retention for anything**: logs (R28), expired auth tokens are cleared, but entity rows for files deleted outside Filemat, orphaned entities (`path = NULL`) and their permissions stay forever, and feed R15.
- **Schema file plus migrations both define tables**: `sqlite-schema.sql` still creates the pre-V9 `files` table with `inode` on a fresh install, and migrations then rebuild it. A fresh install's schema is "old schema + every migration", which works, but the `.sql` file no longer describes the database and invites edits in the wrong place.
- **Audit log integrity** isn't designed for: cascade delete (R18), attacker-controlled IPs (R1), no append-only guarantee, no export.
- **Secrets at rest:** TOTP secrets and backup codes are stored in plain text in SQLite (R11); the DB file's ACL is the only protection. Consider at least hashing backup codes.

### Testing

- 99 server test files and 7 web test files, heavily weighted to the Windows path layer (good). But there are **no tests** for: `AuthorizationFilter`/endpoint map (R3), `AdminUserRoleService`/`RoleService`/`AdminUserService` privilege rules (R9), `MfaService` (R10, R11), `FileShareService` (R19, R20), `EntityPermissionService`/`EntityPermissionTree` move semantics (R16), `FolderUploadService` and `TusService` end-to-end, `FileCopyService` through links (R23). These are exactly the places with the findings above. A handful of "user X must not be able to …" integration tests against the running app would cover most of the High items.
- The web has almost no tests outside path utilities; no component or end-to-end tests of the upload/download flows.

### Operations

- The default deployment (Windows zip, plain Docker) is plain HTTP with no proxy, which collides with `Secure` cookies (R2) and with header trust (R1). The docs should say clearly which setups are supported: "HTTPS reverse proxy required for remote access" or a built-in TLS option.
- No health endpoint, no metrics, no structured logging; console logging of every DB log row by default.

### Added in pass 2

- **Two hardening standards.** The Windows code paths got careful treatment (FFmpeg out of process with slots and a timeout, file IDs only on volumes that keep them, a long sensitive list, private secret files). The Linux equivalents, which the Docker image and most installs use, didn't: in-process FFmpeg (R46), every mount trusted for file keys (R38), a short sensitive list (R39), umask-default secret files (R49), root in the container (R56). The Windows review's thoroughness should be applied to the Linux side next.
- **Unbounded work per request** is a theme: image decode sizes and concurrency (R47), FFmpeg (R46), Argon2 (R36), quadratic manifest checks (R44), 2 500-path metadata batches, full-table prefix scans per moved file (R43), whole files read into browser memory (R54), and read locks held for a whole walk (R40). A per-request budget (bounded executors with 503 on overflow) for each expensive resource would cover most of them.
- **Migrations call application code** (`SystemPermission`, `PathKeyRebuild`, `FileKeyBackfill`, `Platform`, the native layer). If any of those change later, re-running an old migration on an old database does something different from what it did when it was written. Migrations should carry their own copy of the logic or plain SQL. (`V5` also imports four unused classes.)
- **Dead code**: `CounterStateFlow`, `AuthTokenRepository.getUserByToken`, `UlidListSerializer`, `pathContainsSymlink`, `todo()`, `runDev`, `SettingRepository.removeSetting` (unused; R49 is the bug that results), `is_banned` (R34), `shared_files.pathname` (added by V3, never read), `web/src/lib/code/auth/auth.svelte.ts`, the commented-out `html` categories in `data/files.ts`, the `metadata-extractor` dependency.
- **Client and server validation disagree** (R41, R55): username alphabets, e-mail rules (`includes("@") && includes(".")` vs `InternetAddress.validate()`), TOTP digits. Since the server sends `filenameRules` to the web already, the same could be done for usernames and passwords.
- **Silent caps and silent drops**: logs dropped per caller (R52), zip entries skipped (R24), listing entries of type `OTHER` hidden (R54), search capped at 10 000 without saying so, `content-metadata-batch` capped at 2 500 without saying so.
- **Accessibility warnings are switched off globally** (`a11y_click_events_have_key_events`, `a11y_no_static_element_interactions`, `a11y_autofocus` in `svelte.config.js` and `npm run check`), so keyboard and screen-reader problems are never reported.

## Coverage list

Pass 1, read in full or in the relevant part: `AuthenticationFilter`, `AuthorizationFilter`, `endpointAuthentication`, `UtilityFilter`, `CorsFilter`, `CorsOriginRegistry`, `ResourceController`, `ControllerAdvice`, `Initialization`, `AuthController`, `LoginController`, `LoginService`, `AuthService`, `AuthTokenService`, `ContentSessionService`, `SensitiveAuthService`, `MfaService`, `RateLimiter`, `TotpUtil`, `StringUtils`, `Principal`, `AdminUserService`, `AdminUserController` (part), `AdminUserRoleService`, `AdminRoleController` (part), `RoleService` (part), `Permission`, `UserController`, `UserService` (part), `FileSecurityService`, `EntityPermissionTree`, `EntityPermissionService`, `EntityService` (most), `FileDeletionService`, `FilesystemDeletionService`, `FileVisibilityService`, `VisibilityTrie`, `utilFunctions` (resolve/link part), `FileController` (most), `FileService`, `sharePath`, `FileShare`, `FileShareController`, `FileShareService`, `TusService`, `FolderUploadService` (finalize part), `FileLockService`, `FileContentService`, `FileMoveService` (part), `FileCopyService`, `FilesystemCopyService` (part), `FileEntryListsService`, `SetupController`, `StateController`, `AdminSettingsController`, `FileUtilController` (thumbnail part), `FileMetadataService` (part), `LogRepository`, `AdminLogController`, `LogService` (part), `DatabaseConfig`, `sqlite-schema.sql`, `V8`, `application.yml`. Web: `service-worker.ts`, `serviceWorker.ts`, `contentUrl.ts`, `contentSession.ts`, `safeFetch`, `util/+page.svelte`, `FileViewer.svelte` (part), `LogoutSetting.svelte`, `FileContextMenuPopover.svelte` (part), `package.json`.

Pass 2, read in full: `ServerApplication`, `State`, `Props`, `Config`, `serialization`, `SerialFilter`, `DatabaseSetup`, `typeConverters`, `FileKeyBackfill`, `PathKeyRebuild`, migrations V2-V9 and `MigrationUtil`, `PathPolicy`, `AclPolicy`, `CaseSensitivity`, `FileIdentity`, `LinkInspector`, `Platform`, `DatabaseLocationPolicy`, `WindowsNative`, `ConsoleLogFile`, `WindowsSystemFolders`, `WindowsPortFailureAnalyzer`, `SensitiveFolderPaths`, `NonDeletableSystemPaths`, `FileErrors`, `FileUtils`, `utilFunctions` (rest), `Result`, `Either`, `Locker`, `Token`, `CounterStateFlow`, `BufferedResponseWrapper`, `RequestPathOverrideWrapper`, `AController`, `ErrorResponse`, `Validator`, `RateLimitedLog`, `globalUtils`, DTOs, `FilesystemService`, `FilesystemMoveService`, `FileMetadataService`, `FileFolderEntriesService`, `FolderController`, `FolderUploadController`, `FolderUploadService` (rest), `FileController` (rest), `FileUtilController` (rest), `ThumbnailService`, `ExternalFfmpegBackend`, `JavacvBackend`, `FfmpegSupport`, `MediaPreviewBackend`, `MediaPreviewService`, `FileContentMetadataService`, `SettingService`, `ThumbCacheSettingService`, `AppService`, `SavedFileService`, `SavedFileController`, `PermissionController`, `UserUtilService`, `UserUtilController`, `AdminUserController` (rest), `AdminRoleController` (rest), `AdminUserRoleController`, `RoleService` (rest), `UserRoleService`, `LogService`, all repositories, `FilePath`, `AuthToken`, `Role`, user models, `FolderUploadModels`, `FileMetadata`, `EntityService` (rest), `build.gradle.kts`, `Dockerfile`, the release workflow, `utilities/*`, `filemat.cmd`. Web: `files.ts`, `folderUpload.ts`, `folderDownload.ts` (part), `shareTokenStorage.ts`, `tusIncompleteUploads.ts` (part), `codeUtil.svelte.ts`, `path.ts`, `stateUtils.ts`, `validation.ts`, `config/values.ts`, `data/files.ts`, `stateFetcher.ts`, `sensitiveAuth.svelte.ts`, `fileUtilities.ts`, `pageLogic.ts` (part), `filesState.svelte.ts` (part), `FileViewer.svelte`, `FileSharingDialog.svelte` (script), login, setup (script), share page (script), user admin `actions.ts` (part), both layouts, `svelte.config.js`, `vite.config.ts`; every `safeFetch` endpoint cross-checked against the server.

Pass 3 (after asking whether everything was read): every remaining web file. Read: all of `lib/code` (state objects, admin and user modules, permissions, settings, upload handle store, ui utilities, types and data files), `fileBrowserUtil.svelte.ts`, `fileActions.ts`, the files page script, `FileBrowser`, `FileSearchButton`, `FilePermissionCreator`, `FilePermissionEditor`, the exposed-files, change-password, role and user settings scripts, and the logs page script. The remaining Svelte files (buttons, entry rows, panels, dialogs, sidebars, breadcrumbs, folder tree, settings toggles, icons) were scanned for requests, links (`href`/`src`), `{@html}`, event listeners and timers rather than read for layout. Server test code (13 400 lines) was surveyed: one Spring context test, no HTTP-level tests, `AdminUserServiceTest` covers only `createUser`, `LoginServiceTest` covers login flows but not timing (R4). `windows-test-fixtures.ps1` was not reviewed (a test helper, not shipped).

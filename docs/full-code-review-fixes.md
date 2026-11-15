# Code review fixes (2026-11-07)

Fixes for the clear-cut findings of [full-code-review.md](full-code-review.md), one commit each, on `windows-support` after `3c3533f`. Then the decisions on the findings that needed one, as a todo list.

Tests, after the last commit: full server suite 904 tests, 0 failures (51 skipped by their own environment checks, as before); web unit tests 91 passed; `npm run check` 0 errors. Nothing was run in a browser, and the four Svelte page fixes (#21, #22, #24, #27) have no automated test.

## Changelog

### Server

**1. Admin user creation skipped e-mail and username checks** (R50, `46f67ad`)
A misplaced `?.` meant only the password was validated when an admin created a user. Invalid e-mails, usernames with spaces or `@`, or 10 000-character names were accepted, and those accounts couldn't log in.
Fix: parenthesised the check, as setup already does.
You'll notice: the "new user" form now refuses an invalid e-mail or username with a message, instead of creating a broken account.

**2. Setup code stayed in the database forever** (R49, `225a773`)
`removeSetting` read the setting instead of deleting it, so the setup code was never removed after setup.
Fix: it calls the repository's delete.
You'll notice: nothing in the UI. After setup, the code is gone from the `settings` table.

**3. Rate-limiter cleanup ran every second** (R5, `f1dab41`)
A debug value (`delay(1000)`) was left in place of the commented-out 90 s.
Fix: back to 90 s. Only full, unused buckets are removed, and a full bucket behaves like a new one, so limits work exactly as before.
You'll notice: nothing, apart from slightly less background CPU.

**4. Static file streams never closed** (R7, `5f3e37a`)
Each page or asset load opened a stream on a JAR entry and left it for the garbage collector.
Fix: the stream is closed after copying.
You'll notice: nothing. Less native memory held under load.

**5. A failed log query recorded the wrong user** (R52, `6ec3046`)
When loading the admin log page failed, the error row named `targetId` (always empty) as the initiator instead of the admin.
Fix: uses `initiatorId`.
You'll notice: such error rows now show who opened the log page.

**6. Removing a file visibility rule always answered "ok"** (R29, `b5c31fc`)
The server ignored the result of the database delete and logged and answered success even when it failed.
Fix: a failed removal answers 500 with the error.
You'll notice: if the delete fails, the admin sees an error instead of a false success (see also #24).

**7. Unknown log filters crashed with a 500** (R29, `4c0e6b5`)
An unknown severity or log type name, or a list that isn't JSON, threw inside the log endpoint.
Fix: answers 400 "Invalid severity list." / "Invalid log type list.".
You'll notice: nothing in normal use; the web only sends valid names.

**8. A failed entity move left no trace** (R35, `51085c0`)
`EntityService.move` turned any exception into "Failed to move file." and dropped the exception.
Fix: logs it as a `SYSTEM` error with both paths and the stack trace.
You'll notice: such failures now show up in the admin log.

**9. Creating or moving at a root path crashed** (R24, `9983bd0`)
`createFolder`, `createBlankFile` and `moveFile` read the parent of a root path (`/`, `C:\`) without a null check, which threw and answered 500.
Fix: they refuse it with "A folder can't be created here." / "A file can't be created here." / "Cannot move root folder." / "A file can't be moved here." (400), like delete already did.
You'll notice: only through hand-made requests; the UI doesn't send these.

**10. One vanishing file failed the whole folder** (R45, `46ffb0b`)
The listing threw when an entry was deleted between listing the folder and reading its details, so folders where programs create and delete temp files intermittently failed to open.
Fix: entries that can't be read anymore are left out.
You'll notice: Downloads or build folders open reliably. An entry whose details can't be read at all is now left out instead of failing the folder.

**11. Disabling 2FA reported success when it failed** (R10, `e926b2a`)
`MfaService.disable` ignored the database result, logged "User disabled TOTP 2FA" and answered OK.
Fix: a failed write answers an error and logs nothing.
You'll notice: on a database error, the user sees "Failed to update 2FA." instead of a false success.

**12. A role-rank refusal said "user not found"** (R41/R55, `9bbcb5e`)
Assigning a role above your own rank returned an empty rejection, which the controller mapped to 404, so the web said "This user was not found."
Fix: the refusal says "You can't assign a role with higher permissions than your own." and answers 403.
You'll notice: the real reason is shown in the user settings.

**13. Logout didn't clear the cookie** (R34, `c76e4cb`)
The token was deleted on the server, but the browser kept sending the dead cookie.
Fix: logout answers with an expired auth cookie.
You'll notice: nothing visible; the browser drops the cookie.

**14. Share list empty when a file was opened through a link** (R21, `9213d65`)
Shares are created on the resolved file, but the share list looked them up by the path as typed. Through a linked folder (or an 8.3 name) the list came back empty.
Fix: looks them up by the resolved path.
You'll notice: existing shares appear in the share dialog when the file is opened through a link.

**15. Hidden folders not hidden between setup and the first restart** (R25, `4e5eeaf`)
`FM_HIDDEN_FOLDER_PATHS` and the auth code file were loaded only at startup, and only when the app was already set up. On a fresh install they stayed visible in exposed folders until the first restart.
Fix: the hidden list (needs no database) is also loaded at startup before setup.
You'll notice: hidden folders are hidden from the first login after setup.

### Web

**16. Delete error toast showed "[object Object]"** (R54, `9710645`)
On a network error, the toast joined the file objects instead of their paths.
Fix: lists the paths.
You'll notice: the toast names the files.

**17. Moving files offline threw instead of showing an error** (R54, `5098b37`)
`moveMultipleFiles` read the status without checking for a network failure.
Fix: shows "Failed to move files." on a network error.
You'll notice: a toast instead of nothing (and a console error).

**18. Wrong chunk size comment** (R54, `8b9ea31`)
The comment said 128 MB for a 64 MB chunk. Comment only.

**19. Files named `pdf`, `mp4` or `log` opened in that viewer** (R54, `d4b3f02`)
`getFileExtension` returned the whole name when there was no dot.
Fix: a name without a dot has no extension. Dotfiles (`.bashrc`) keep `bashrc`.
You'll notice: such files are treated like any extensionless file (no PDF/video/text viewer guessed, no thumbnail attempt).

**20. State synced more often while idle than while active** (R55, `a6abcff`)
The code did the opposite of its comment: 20 s when idle, 60 s when active.
Fix: 20 s while active, 60 s while idle.
You'll notice: changes made by an admin (settings, roles) reach an active tab within 20 s instead of 60; idle tabs make a third of the requests.

**21. Setup step 1 threw after a network error** (R55, `65c214e`)
After the error toast, the code went on to read the missing response.
Fix: stops after the toast.
You'll notice: only the toast, no console exception; the button works again.

**22. Share password form allowed double submits** (R55, `3d5dd33`)
The form checked `loading` but never set it, so each click sent a login (an Argon2 hash on the server).
Fix: sets `loading` while the request runs.
You'll notice: double clicks send one request.

**23. A failed folder load could throw** (R55, `34f2067`)
After navigating to the parent folder on an error, `loadPageData` carried on with no data; when the navigation didn't start a new load (an error at `/`), it threw.
Fix: returns after navigating.
You'll notice: no console exception in that case.

**24. File visibility rules disappeared from the list when removal failed** (R58, `081897d`)
The exposed files page deleted the row whatever the server answered (expired code, server error).
Fix: removes the row only on success.
You'll notice: a failed removal leaves the rule listed, next to the error toast.

**25. Page titles piled up** (R58, `a10c147`)
`register` built its cleanup function but didn't return it, so every opened folder added a segment that was never removed.
Fix: returns it; the cleanup removes only its own segment.
You'll notice: the tab title follows the current page, and a page without a title no longer shows the previous one.

**26. `hasAnyPermission` changed the caller's array** (R58, `d9aae33`)
It pushed `SUPER_ADMIN` into the array it was given.
Fix: works on a copy. You'll notice: nothing (no caller relied on it).

**27. The app layout's unload listener was never removed** (R55, `64278e2`)
Each mount of the app layout added a `beforeunload` listener.
Fix: removed on unmount.
You'll notice: nothing; the root layout's listener still asks before leaving with uploads running.

**28. Delete in search results deleted the wrong files** (R57, `260d4d8`)
In search mode, the trash button deleted the folder view's selection (hidden behind the results) while the dialog counted the search selection. With nothing selected in the folder view it silently did nothing.
Fix: in search mode it deletes the selected search results, looked up in the results themselves, and removes deleted files from the results.
You'll notice: the trash button in search results deletes what you selected there, and those results disappear.

## Decisions (2026-11-15)

The answers to the review's questions, as work to do. Delete an item when it's done.

### Security

- [ ] **R9, rank guard (1).** Change password, reset 2FA and edit username/e-mail/home folder refuse a target of higher rank, as delete already does.
- **R9, granting rights (2).** Keep the level rule: equals can grant each other everything. No change.
- **R10, required 2FA (3).** The user may turn 2FA off; if an admin required it, they're asked to set it up again at the next login.
- [ ] **R1, proxy headers (4).** Add `FM_TRUSTED_PROXIES`, default nobody. Only from a trusted proxy, read `X-Forwarded-For` (right-most untrusted entry) and `X-Forwarded-Proto`; ignore them from anyone else. Covers the rate limits, the audit log IP, `CorsOriginRegistry.requestOrigin` and the HTTPS check in `AuthController.contentSessionMapping`.
- [ ] **R13/R14, headers (5).** `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer` on every response, and a sandbox CSP (no scripts) on file content.
- [ ] **R13, HTML files (6).** Serve `text/html`, `application/xhtml+xml`, `text/xml` and `image/svg+xml` content inline as `text/plain`: the raw text shows, never a rendered page.
- [ ] **R3, endpoint auth (7).** Fail closed: match routes by Spring pattern and HTTP method, build the map (immutable) before the port opens, and refuse an `/api` request with no entry (401). Tus `PATCH`/`HEAD`/`DELETE` get a real auth check.
- [ ] **R4, login timing (8).** Hash a dummy password when the username doesn't exist.
- [ ] **R18, audit log on user delete (9).** Logs never change and the initiator ID never changes: deleting a user leaves their log rows exactly as they are. Migration: drop the `ON DELETE CASCADE` foreign key on `log.initiator_user_id` (no `SET NULL`).
- **R22, overwrite (10).** Overwriting a file by upload drops the old grants (no change to make the original owner stay).
- [ ] **R2, `Secure` cookies (11).** Set `Secure` only when the request is HTTPS (trusted `X-Forwarded-Proto` per R1). Also in `windows-todo.md`.
- [ ] **R55, tus uploads (12).** Each upload belongs to the user who started it; anyone else gets 403.
- [ ] **R19, shares after rights are removed (13).** When a share link is opened, check the sharer still has the rights (cached briefly); a removed right stops the link.
- [ ] **R53, share IDs (14).** Random share ID by default, with an optional readable prefix.
- **R56, Docker as root (15).** Stays root. No change.

### Smaller behaviour choices

- **R34, logout (16).** No change.
- [ ] **R6, TOTP replay (17).** Refuse a 2FA code that was already used.
- [ ] **R11, backup codes (18).** Make them work: single use, stored hashed.
- [ ] **R41, usernames (19).** The server allows only ASCII letters, digits, `-` and `_`, like the web. Existing names stay.
- **R41, password length (20).** Minimum stays 4.
- [ ] **R41, e-mail (21).** Store as typed (trim, lowercase the domain only); stop rewriting Gmail addresses.
- **R49, secret files (22).** No change.
- [ ] **R52, `package` in log metadata (23).** Drop it.
- [ ] **R54, sockets and FIFOs (24).** Hide them in both sortings.
- [ ] **R54, `ANY_LINK` (25).** Remove the dead branch in `isFile`.
- [ ] **R57, delete dialog (26).** List the file names in the dialog, and clear the folder selection when a search starts.
- **N1, search selection details (27).** No change.
- **R26, upload folder from setup (28).** Leave it.
- [ ] **R31, single-value permission converters (29).** Fix them to write the index (don't delete them).
- [ ] **R35, delete with a failed DB lookup (30).** Report the delete as done and log the DB error.
- [ ] **R21, small share items (31).** Refuse an empty share password, hash it only after the permission check, and let the owner delete a share whose file no longer exists.
- **R20, share password tokens (32).** No change.
- **R24, zip duplicates (33).** No change.
- [ ] **R28, log table (34).** Truncate logged user input to 256 characters and index `created_date`. Logs are kept forever.
- [ ] **R32, SQLite (35).** WAL mode with a 5 s busy timeout.
- **R33, `/util` page (36).** No change.
- [ ] **R7, caching (37).** `Cache-Control: immutable` for `_app/immutable/*`, and refuse non-`GET` requests to page paths.

### Bigger work (38)

- [ ] R16, R15/R38, R17 + the `FileLockService` lock map, R23, R30, R36, R39, R40, R42, R43, R44, R46, R47, R48: each needs its own plan. Order not decided: the owner picks which comes first.

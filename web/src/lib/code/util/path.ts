import { appState } from "$lib/code/stateObjects/appState.svelte"

/**
 * File paths from the server, in the API format of the server OS.
 *
 * POSIX: `/a/b`. Windows: `C:/a/b`, where the drive is the first segment and `/` is the list of drives.
 * Share paths have no drive on any OS (`/sub/file`).
 * Mirrors `PathPolicy` on the server.
 */

const driveSegment = /^[A-Za-z]:$/

function isWindows(): boolean {
    return appState.platform.pathStyle === `windows`
}

/**
 * Returns the non-empty segments of a path. On Windows the drive is the first segment (`C:`).
 */
export function pathSegments(path: string): string[] {
    const normalized = isWindows() ? path.replaceAll(`\\`, `/`) : path
    return normalized.split(`/`).filter(v => v !== ``)
}

/**
 * Joins segments into a path (`/a/b`, `C:/a/b`). No segments give the root `/`.
 */
export function joinSegments(segments: string[]): string {
    if (segments.length === 0) return `/`
    const first = segments[0]
    if (isWindows() && driveSegment.test(first)) {
        return `${first.toUpperCase()}/${segments.slice(1).join(`/`)}`
    }
    return `/${segments.join(`/`)}`
}

/**
 * Normalizes a path to the API format: no empty segments, and `C:/a` instead of `/C:/a` (as in page URLs)
 */
export function normalizePath(path: string): string {
    return joinSegments(pathSegments(path))
}

export function isRootPath(path: string): boolean {
    return pathSegments(path).length === 0
}

/**
 * Returns whether a server path is the Windows drive list, which is not a real folder: it has no size, dates or search
 */
export function isDriveList(path: string): boolean {
    return isWindows() && isRootPath(path)
}

/**
 * Returns the last segment of a path. A drive root returns its drive (`C:`), the root returns an empty string.
 */
export function filenameOf(path: string): string {
    const segments = pathSegments(path)
    return segments[segments.length - 1] ?? ``
}

/**
 * Returns the parent folder of a path. The parent of a drive root is the root, and the root has no parent.
 */
export function parentPath(path: string): string | null {
    const segments = pathSegments(path)
    if (segments.length === 0) return null
    return joinSegments(segments.slice(0, -1))
}

/**
 * Appends a name or relative path to a folder path
 */
export function childPath(parent: string, name: string): string {
    return joinSegments([...pathSegments(parent), ...pathSegments(name)])
}

/**
 * Folds case like the server's Windows path keys: each letter becomes its upper case, unless that is more than one letter.
 * So `ı` and `i` match, while `ß` and `ẞ`, or `k` and the Kelvin sign, don't. `toLowerCase` would get these wrong.
 */
export function foldCase(name: string): string {
    let folded = ``
    for (const char of name) {
        const upper = char.toUpperCase()
        folded += [...upper].length === 1 ? upper : char
    }
    return folded
}

function sameSegment(a: string, b: string): boolean {
    // Windows paths from the server are in canonical case, so this only matters for typed paths
    return isWindows() ? foldCase(a) === foldCase(b) : a === b
}

/**
 * Returns whether two paths name the same file
 */
export function isSamePath(a: string, b: string): boolean {
    const aSegments = pathSegments(a)
    const bSegments = pathSegments(b)
    return aSegments.length === bSegments.length && aSegments.every((segment, i) => sameSegment(segment, bSegments[i]))
}

/**
 * Returns whether two Windows paths are on different drives (`S:/a` and `C:/FmTest/a`). The server answers
 * with the folder a `subst` drive points to, so a typed path and the one it comes back as can differ this way
 */
export function isOtherDrive(a: string, b: string): boolean {
    if (!isWindows()) return false
    const aDrive = pathSegments(a)[0]
    const bDrive = pathSegments(b)[0]
    return aDrive !== undefined && bDrive !== undefined
        && driveSegment.test(aDrive) && driveSegment.test(bDrive)
        && aDrive.toLowerCase() !== bDrive.toLowerCase()
}

/**
 * Returns whether a path is inside a folder, compared by whole segments (`/ab` is not inside `/a`)
 */
export function isChildOf(path: string, parent: string): boolean {
    const segments = pathSegments(path)
    const parentSegments = pathSegments(parent)
    if (segments.length <= parentSegments.length) return false
    return parentSegments.every((segment, i) => sameSegment(segment, segments[i]))
}

/**
 * Returns whether a path is directly inside a folder
 */
export function isDirectChildOf(path: string, parent: string): boolean {
    return isChildOf(path, parent) && pathSegments(path).length === pathSegments(parent).length + 1
}

/**
 * Returns a path as the server OS shows it: `C:\a\b` on Windows, unchanged elsewhere
 */
export function displayPath(path: string): string {
    if (!isWindows()) return path
    const normalized = normalizePath(path)
    return normalized === `/` ? normalized : normalized.replaceAll(`/`, `\\`)
}

/**
 * Removes the path separators of the server OS from a typed file name: `/` everywhere, and `\` on Windows
 */
export function stripSeparators(name: string): string {
    return isWindows() ? name.replace(/[\\/]/g, ``) : name.replaceAll(`/`, ``)
}

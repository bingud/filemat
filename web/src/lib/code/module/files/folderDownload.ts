import { getUniqueFilename } from "$lib/code/util/codeUtil.svelte"

export type FolderDownloadResolution = `overwrite` | `skip` | `keep-both`

export type FolderDownloadEntryType = `file` | `directory` | `other`

export type FolderDownloadConflict = {
    relativePath: string,
    targetPath: string,
    incomingType: FolderDownloadEntryType,
    existingType: FolderDownloadEntryType,
    allowedResolutions: FolderDownloadResolution[],
    message?: string | null,
}

export type PlannedDownloadEntry = {
    remotePath: string,
    relativePath: string,
    name: string,
    kind: `file` | `directory`,
    size: number,
    jobId: string,
}

export type ResolvedDownloadDirectory = {
    localRelativePath: string,
    jobId: string,
}

export type ResolvedDownloadFile = {
    remotePath: string,
    localRelativePath: string,
    displayName: string,
    size: number,
    jobId: string,
    skipped: boolean,
}

export type ResolvedDownloadPlan = {
    directories: ResolvedDownloadDirectory[],
    files: ResolvedDownloadFile[],
}

type DirectoryRemap = {
    relativePath: string,
    localRelativePath: string,
}

const FILE_VS_FILE_RESOLUTIONS: FolderDownloadResolution[] = [`overwrite`, `skip`, `keep-both`]
const TYPE_MISMATCH_RESOLUTIONS: FolderDownloadResolution[] = [`skip`, `keep-both`]

export function joinRelativePath(parent: string, name: string): string {
    return parent ? `${parent}/${name}` : name
}

export function parentRelativePath(relativePath: string): string {
    const index = relativePath.lastIndexOf(`/`)
    return index === -1 ? `` : relativePath.slice(0, index)
}

export function filenameOfRelativePath(relativePath: string): string {
    const index = relativePath.lastIndexOf(`/`)
    return index === -1 ? relativePath : relativePath.slice(index + 1)
}

function isUnderPrefix(relativePath: string, prefix: string): boolean {
    return relativePath === prefix || relativePath.startsWith(`${prefix}/`)
}

function applyDirectoryRemaps(relativePath: string, remaps: DirectoryRemap[]): string {
    const remap = remaps
        .filter(entry => isUnderPrefix(relativePath, entry.relativePath))
        .sort((a, b) => b.relativePath.length - a.relativePath.length)[0]
    if (!remap) return relativePath

    const suffix = relativePath.slice(remap.relativePath.length).replace(/^\//, ``)
    return suffix ? joinRelativePath(remap.localRelativePath, suffix) : remap.localRelativePath
}

async function directoryAt(
    rootHandle: FileSystemDirectoryHandle,
    relativePath: string,
    cache: Map<string, FileSystemDirectoryHandle | null>,
): Promise<FileSystemDirectoryHandle | null> {
    if (!relativePath) return rootHandle

    const cached = cache.get(relativePath)
    if (cached !== undefined) return cached

    const parent = await directoryAt(rootHandle, parentRelativePath(relativePath), cache)
    if (!parent) {
        cache.set(relativePath, null)
        return null
    }

    try {
        const dir = await parent.getDirectoryHandle(filenameOfRelativePath(relativePath))
        cache.set(relativePath, dir)
        return dir
    } catch {
        cache.set(relativePath, null)
        return null
    }
}

async function childKind(
    parent: FileSystemDirectoryHandle,
    name: string,
): Promise<`file` | `directory` | null> {
    try {
        await parent.getDirectoryHandle(name)
        return `directory`
    } catch (error) {
        if (error instanceof DOMException && error.name === `TypeMismatchError`) return `file`
        if (!(error instanceof DOMException) || error.name !== `NotFoundError`) return null
    }

    try {
        await parent.getFileHandle(name)
        return `file`
    } catch (error) {
        if (error instanceof DOMException && error.name === `TypeMismatchError`) return `directory`
        return null
    }
}

function reservedNames(reservedByParent: Map<string, Set<string>>, parentRel: string): Set<string> {
    let names = reservedByParent.get(parentRel)
    if (!names) {
        names = new Set()
        reservedByParent.set(parentRel, names)
    }
    return names
}

async function uniqueNameIn(
    rootHandle: FileSystemDirectoryHandle,
    parentRel: string,
    name: string,
    dirCache: Map<string, FileSystemDirectoryHandle | null>,
    reservedByParent: Map<string, Set<string>>,
): Promise<string> {
    const parent = await directoryAt(rootHandle, parentRel, dirCache)
    const reserved = reservedNames(reservedByParent, parentRel)
    const taken = [...reserved]
    if (parent && await childKind(parent, name)) taken.push(name)

    let unique = getUniqueFilename(name, taken)
    while (reserved.has(unique) || (parent && await childKind(parent, unique))) {
        taken.push(unique)
        unique = getUniqueFilename(name, taken)
    }
    reserved.add(unique)
    return unique
}

async function nameExistsIn(
    rootHandle: FileSystemDirectoryHandle,
    parentRel: string,
    name: string,
    dirCache: Map<string, FileSystemDirectoryHandle | null>,
    reservedByParent: Map<string, Set<string>>,
): Promise<boolean> {
    if (reservedNames(reservedByParent, parentRel).has(name)) return true
    const parent = await directoryAt(rootHandle, parentRel, dirCache)
    if (!parent) return false
    return (await childKind(parent, name)) !== null
}

export async function collectDownloadConflicts(
    rootHandle: FileSystemDirectoryHandle,
    entries: PlannedDownloadEntry[],
): Promise<FolderDownloadConflict[]> {
    const dirCache = new Map<string, FileSystemDirectoryHandle | null>()
    const conflicts: FolderDownloadConflict[] = []

    for (const entry of entries) {
        const parentRel = parentRelativePath(entry.relativePath)
        const parent = await directoryAt(rootHandle, parentRel, dirCache)
        if (!parent) continue

        const existing = await childKind(parent, entry.name)
        if (!existing) continue

        if (entry.kind === `directory`) {
            if (existing === `directory`) continue
            conflicts.push({
                relativePath: entry.relativePath,
                targetPath: entry.relativePath,
                incomingType: `directory`,
                existingType: `file`,
                allowedResolutions: [...TYPE_MISMATCH_RESOLUTIONS],
                message: `A file with this name already exists.`,
            })
            continue
        }

        if (existing === `file`) {
            conflicts.push({
                relativePath: entry.relativePath,
                targetPath: entry.relativePath,
                incomingType: `file`,
                existingType: `file`,
                allowedResolutions: [...FILE_VS_FILE_RESOLUTIONS],
            })
            continue
        }

        conflicts.push({
            relativePath: entry.relativePath,
            targetPath: entry.relativePath,
            incomingType: `file`,
            existingType: `directory`,
            allowedResolutions: [...TYPE_MISMATCH_RESOLUTIONS],
            message: `A folder with this name already exists.`,
        })
    }

    return conflicts
}

export async function applyDownloadResolutions(
    rootHandle: FileSystemDirectoryHandle,
    entries: PlannedDownloadEntry[],
    conflicts: FolderDownloadConflict[],
    resolutions: Record<string, FolderDownloadResolution>,
): Promise<ResolvedDownloadPlan> {
    const dirCache = new Map<string, FileSystemDirectoryHandle | null>()
    const reservedByParent = new Map<string, Set<string>>()
    const conflictByPath = new Map(conflicts.map(conflict => [conflict.relativePath, conflict]))
    const skippedPrefixes: string[] = []
    const remaps: DirectoryRemap[] = []

    const directoryConflicts = conflicts
        .filter(conflict => conflict.incomingType === `directory`)
        .sort((a, b) => a.relativePath.length - b.relativePath.length)

    for (const conflict of directoryConflicts) {
        const resolution = resolutions[conflict.relativePath]
        if (resolution === `skip`) {
            skippedPrefixes.push(conflict.relativePath)
            continue
        }
        if (resolution !== `keep-both`) continue

        const parentRel = applyDirectoryRemaps(parentRelativePath(conflict.relativePath), remaps)
        const unique = await uniqueNameIn(
            rootHandle,
            parentRel,
            filenameOfRelativePath(conflict.relativePath),
            dirCache,
            reservedByParent,
        )
        remaps.push({
            relativePath: conflict.relativePath,
            localRelativePath: joinRelativePath(parentRel, unique),
        })
    }

    const directories: ResolvedDownloadDirectory[] = []
    for (const entry of entries) {
        if (entry.kind !== `directory`) continue
        if (skippedPrefixes.some(prefix => isUnderPrefix(entry.relativePath, prefix))) continue

        const localRelativePath = applyDirectoryRemaps(entry.relativePath, remaps)
        directories.push({
            localRelativePath,
            jobId: entry.jobId,
        })
        reservedNames(reservedByParent, parentRelativePath(localRelativePath)).add(filenameOfRelativePath(localRelativePath))
    }

    directories.sort((a, b) => a.localRelativePath.split(`/`).length - b.localRelativePath.split(`/`).length)

    const files: ResolvedDownloadFile[] = []
    for (const entry of entries) {
        if (entry.kind !== `file`) continue

        if (skippedPrefixes.some(prefix => isUnderPrefix(entry.relativePath, prefix))) {
            files.push({
                remotePath: entry.remotePath,
                localRelativePath: entry.relativePath,
                displayName: entry.name,
                size: entry.size,
                jobId: entry.jobId,
                skipped: true,
            })
            continue
        }

        const conflict = conflictByPath.get(entry.relativePath)
        const resolution = conflict ? resolutions[entry.relativePath] : undefined
        if (resolution === `skip`) {
            files.push({
                remotePath: entry.remotePath,
                localRelativePath: entry.relativePath,
                displayName: entry.name,
                size: entry.size,
                jobId: entry.jobId,
                skipped: true,
            })
            continue
        }

        let localRelativePath = applyDirectoryRemaps(entry.relativePath, remaps)
        if (resolution === `keep-both`) {
            const parentRel = parentRelativePath(localRelativePath)
            const name = filenameOfRelativePath(localRelativePath)
            if (await nameExistsIn(rootHandle, parentRel, name, dirCache, reservedByParent)) {
                const unique = await uniqueNameIn(rootHandle, parentRel, name, dirCache, reservedByParent)
                localRelativePath = joinRelativePath(parentRel, unique)
            }
        }

        reservedNames(reservedByParent, parentRelativePath(localRelativePath)).add(filenameOfRelativePath(localRelativePath))
        files.push({
            remotePath: entry.remotePath,
            localRelativePath,
            displayName: filenameOfRelativePath(localRelativePath),
            size: entry.size,
            jobId: entry.jobId,
            skipped: false,
        })
    }

    return { directories, files }
}

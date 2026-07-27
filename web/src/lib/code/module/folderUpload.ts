import { filesState } from "$lib/code/stateObjects/filesState.svelte"
import { uploadConflictDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte"
import { formData, handleErr, handleException, parentFromPath, safeFetch } from "$lib/code/util/codeUtil.svelte"
import { toast } from "@jill64/svelte-toast"
import { getFileData, startTusUpload } from "./files"

export type FolderUploadResolution = "overwrite" | "skip" | "keep-both"

type FolderUploadFile = {
    file: File,
    relativePath: string,
    size: number,
    lastModified: number,
}

type FolderUploadBatch = {
    rootName: string,
    directories: string[],
    files: FolderUploadFile[],
    skippedUnsupported: string[],
}

type FolderUploadManifest = {
    destinationPath: string,
    rootName: string,
    directories: { relativePath: string }[],
    files: { relativePath: string, size: number, lastModified: number }[],
}

type FolderUploadConflict = {
    relativePath: string,
    targetPath: string,
    incomingType: "file" | "directory" | "other",
    existingType: "file" | "directory" | "other",
    allowedResolutions: FolderUploadResolution[],
    message?: string | null,
}

type FolderUploadPreflightResponse = {
    conflicts: FolderUploadConflict[],
    blocked: { relativePath: string, targetPath: string, message: string }[],
    summary: { fileCount: number, directoryCount: number, totalBytes: number, totalPathBytes: number },
}

type FolderUploadSessionResponse = {
    sessionId: string | null,
    queuedFiles: { relativePath: string, targetPath: string, resolution: FolderUploadResolution }[],
    skippedFiles: string[],
    createdDirectories: string[],
    expiresAt: number,
    unresolvedConflicts?: FolderUploadConflict[],
}

type FileSystemEntryLike = {
    name: string,
    fullPath?: string,
    isFile: boolean,
    isDirectory: boolean,
}

type FileSystemFileEntryLike = FileSystemEntryLike & {
    file: (success: (file: File) => void, error?: (error: DOMException) => void) => void,
}

type FileSystemDirectoryEntryLike = FileSystemEntryLike & {
    createReader: () => {
        readEntries: (success: (entries: FileSystemEntryLike[]) => void, error?: (error: DOMException) => void) => void,
    },
}

export function uploadFolderWithTus() {
    const input = document.createElement(`input`)
    input.type = `file`
    input.style.display = `none`
    input.multiple = true
    input.setAttribute(`webkitdirectory`, ``)

    input.onchange = async (e) => {
        const files = Array.from((e.target as HTMLInputElement).files || [])
        document.body.removeChild(input)
        if (!files.length) return

        const batch = batchFromPickerFiles(files)
        if (!batch) return
        await startFolderUploadBatch(batch)
    }

    document.body.appendChild(input)
    input.click()
}

export async function uploadDroppedFolders(dataTransfer: DataTransfer): Promise<boolean> {
    const items = Array.from(dataTransfer.items || [])
    const entries = items
        .map(item => (item as any).webkitGetAsEntry?.() as FileSystemEntryLike | null)
        .filter((entry): entry is FileSystemEntryLike => !!entry)

    const hasDirectory = entries.some(entry => entry.isDirectory)
    if (!hasDirectory) return false

    const files: FolderUploadFile[] = []
    const directories: string[] = []
    const skippedUnsupported: string[] = []

    for (const entry of entries) {
        if (entry.isDirectory) {
            await collectDirectoryEntry(entry as FileSystemDirectoryEntryLike, entry.name, files, directories, skippedUnsupported)
            continue
        }

        if (entry.isFile) {
            const file = await readFileEntry(entry as FileSystemFileEntryLike)
            const normalizedFilePath = normalizeBrowserRelativePath(entry.name)
            if (!file || !normalizedFilePath) {
                skippedUnsupported.push(entry.name)
                continue
            }

            files.push({
                file,
                relativePath: normalizedFilePath,
                size: file.size,
                lastModified: file.lastModified,
            })
            continue
        }

        skippedUnsupported.push(entry.name)
    }

    const batch = batchFromCollectedEntries(files, directories, skippedUnsupported)
    if (!batch) return false
    await startFolderUploadBatch(batch)
    return true
}

async function startFolderUploadBatch(batch: FolderUploadBatch) {
    if (!filesState.path) return

    if (batch.skippedUnsupported.length) {
        toast.plain(`${batch.skippedUnsupported.length} unsupported folder ${batch.skippedUnsupported.length === 1 ? "entry was" : "entries were"} skipped.`)
    }

    const manifest = toManifest(batch, filesState.path)
    const preflight = await requestPreflight(manifest)
    if (!preflight) return

    if (preflight.blocked.length) {
        const first = preflight.blocked[0]
        handleErr({
            description: `Folder upload blocked: ${first.relativePath}`,
            notification: first.message || `Some files cannot be uploaded.`,
        })
        return
    }

    let resolutions: Record<string, FolderUploadResolution> = {}
    if (preflight.conflicts.length) {
        const resolved = await uploadConflictDialogState.show({ conflicts: preflight.conflicts })
        if (!resolved) return
        resolutions = resolved
    }

    let session: FolderUploadSessionResponse | null = null
    while (true) {
        session = await requestSession(manifest, resolutions, preflight.conflicts.length ? undefined : `keep-both`)
        if (!session) return

        const unresolved = session.unresolvedConflicts || []
        if (!unresolved.length) break

        const resolved = await uploadConflictDialogState.show({
            conflicts: unresolved,
            title: unresolved.length === 1 ? `Cannot overwrite 1 file` : `Cannot overwrite ${unresolved.length} files`,
        })
        if (!resolved) return
        resolutions = { ...resolutions, ...resolved }
    }

    if (!session?.sessionId) return

    if (session.skippedFiles.length) {
        toast.plain(`${session.skippedFiles.length} file${session.skippedFiles.length === 1 ? `` : `s`} skipped.`)
    }

    const filesByRelativePath = new Map(batch.files.map(file => [file.relativePath, file]))
    let refreshTimer: ReturnType<typeof setTimeout> | null = null
    const scheduleRefresh = () => {
        if (refreshTimer) clearTimeout(refreshTimer)
        refreshTimer = setTimeout(async () => {
            const path = filesState.path
            if (!path) return
            const result = await getFileData(path, filesState.meta.fileEntriesUrlPath, filesState.abortController?.signal, {
                silent: true,
                shareToken: filesState.getShareToken(),
            })
            if (result.isUnsuccessful || !result.value) return
            filesState.data.folderMeta = result.value.meta
            filesState.data.entries = result.value.entries
        }, 250)
    }

    for (const queuedFile of session.queuedFiles) {
        const uploadFile = filesByRelativePath.get(queuedFile.relativePath)
        if (!uploadFile) continue

        startTusUpload(uploadFile.file, {
            targetPath: queuedFile.targetPath,
            targetFilename: queuedFile.targetPath.substring(queuedFile.targetPath.lastIndexOf(`/`) + 1),
            metadata: {
                folderUploadSessionId: session.sessionId,
                relativePath: queuedFile.relativePath,
            },
            displayPath: queuedFile.relativePath,
            batchId: session.sessionId,
            relativePath: queuedFile.relativePath,
            refreshCurrentFolderOnSuccess: true,
            onSuccess: scheduleRefresh,
            snapshotBeforeUpload: true,
        })
    }
}

function batchFromPickerFiles(files: File[]): FolderUploadBatch | null {
    const folderFiles = files
        .map(file => {
            const relativePath = normalizeBrowserRelativePath((file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name)
            if (!relativePath) return null
            return {
                file,
                relativePath,
                size: file.size,
                lastModified: file.lastModified,
            }
        })
        .filter((file): file is FolderUploadFile => !!file)

    if (!folderFiles.length) return null

    const rootName = folderFiles[0].relativePath.split(`/`)[0]
    const directories = directoriesFromFiles(folderFiles)

    return {
        rootName,
        directories,
        files: folderFiles,
        skippedUnsupported: [],
    }
}

function batchFromCollectedEntries(files: FolderUploadFile[], directories: string[], skippedUnsupported: string[]): FolderUploadBatch | null {
    if (!files.length && !directories.length) return null

    const roots = new Set([...files.map(file => file.relativePath.split(`/`)[0]), ...directories.map(directory => directory.split(`/`)[0])])
    const hasTopLevelFiles = files.some(file => !file.relativePath.includes(`/`))
    const rootName = roots.size === 1 ? Array.from(roots)[0] : (hasTopLevelFiles ? `Dropped items` : `Dropped folders`)

    if (roots.size > 1) {
        files = files.map(file => ({ ...file, relativePath: `${rootName}/${file.relativePath}` }))
        directories = directories.map(directory => `${rootName}/${directory}`)
        directories.unshift(rootName)
    }

    return {
        rootName,
        directories: Array.from(new Set([...directories, ...directoriesFromFiles(files)])),
        files,
        skippedUnsupported,
    }
}

function toManifest(batch: FolderUploadBatch, destinationPath: string): FolderUploadManifest {
    return {
        destinationPath,
        rootName: batch.rootName,
        directories: batch.directories.map(relativePath => ({ relativePath })),
        files: batch.files.map(file => ({
            relativePath: file.relativePath,
            size: file.size,
            lastModified: file.lastModified,
        })),
    }
}

async function requestPreflight(manifest: FolderUploadManifest): Promise<FolderUploadPreflightResponse | null> {
    const response = await safeFetch(`/api/v1/folder/upload/preflight`, {
        body: formData({ manifest: JSON.stringify(manifest) }),
    })

    if (response.failed) {
        handleException(`Folder upload preflight failed.`, `Failed to check folder upload conflicts.`, response.exception)
        return null
    }

    const status = response.code
    const json = response.json()
    if (status.failed) {
        handleErr({
            description: `Folder upload preflight failed.`,
            notification: json.message || `Failed to check folder upload conflicts.`,
            isServerDown: status.serverDown,
        })
        return null
    }

    return json as FolderUploadPreflightResponse
}

async function requestSession(
    manifest: FolderUploadManifest,
    resolutions: Record<string, FolderUploadResolution>,
    defaultResolution: FolderUploadResolution | undefined,
): Promise<FolderUploadSessionResponse | null> {
    const response = await safeFetch(`/api/v1/folder/upload/session`, {
        body: formData({
            request: JSON.stringify({
                manifest,
                resolutions: Object.entries(resolutions).map(([relativePath, resolution]) => ({ relativePath, resolution })),
                defaultResolution,
            }),
        }),
    })

    if (response.failed) {
        handleException(`Folder upload session failed.`, `Failed to start folder upload.`, response.exception)
        return null
    }

    const status = response.code
    const json = response.json()
    if (status.failed) {
        handleErr({
            description: `Folder upload session failed.`,
            notification: json.message || `Failed to start folder upload.`,
            isServerDown: status.serverDown,
        })
        return null
    }

    return json as FolderUploadSessionResponse
}

function directoriesFromFiles(files: FolderUploadFile[]): string[] {
    const directories = new Set<string>()
    for (const file of files) {
        let parent = parentFromPath(file.relativePath)
        while (parent) {
            directories.add(parent)
            parent = parentFromPath(parent)
        }
    }
    return Array.from(directories).sort((a, b) => a.length - b.length)
}

async function collectDirectoryEntry(
    entry: FileSystemDirectoryEntryLike,
    relativePath: string,
    files: FolderUploadFile[],
    directories: string[],
    skippedUnsupported: string[],
) {
    const normalizedDirectory = normalizeBrowserRelativePath(relativePath)
    if (!normalizedDirectory) return
    directories.push(normalizedDirectory)

    const children = await readAllDirectoryEntries(entry)
    for (const child of children) {
        const childRelativePath = `${normalizedDirectory}/${child.name}`
        if (child.isDirectory) {
            await collectDirectoryEntry(child as FileSystemDirectoryEntryLike, childRelativePath, files, directories, skippedUnsupported)
        } else if (child.isFile) {
            const file = await readFileEntry(child as FileSystemFileEntryLike)
            const normalizedFilePath = normalizeBrowserRelativePath(childRelativePath)
            if (!file || !normalizedFilePath) {
                skippedUnsupported.push(childRelativePath)
                continue
            }

            files.push({
                file,
                relativePath: normalizedFilePath,
                size: file.size,
                lastModified: file.lastModified,
            })
        } else {
            skippedUnsupported.push(childRelativePath)
        }
    }
}

function readAllDirectoryEntries(entry: FileSystemDirectoryEntryLike): Promise<FileSystemEntryLike[]> {
    const reader = entry.createReader()
    const entries: FileSystemEntryLike[] = []

    return new Promise((resolve, reject) => {
        const read = () => {
            reader.readEntries((batch) => {
                if (!batch.length) {
                    resolve(entries)
                    return
                }

                entries.push(...batch)
                read()
            }, reject)
        }

        read()
    })
}

function readFileEntry(entry: FileSystemFileEntryLike): Promise<File | null> {
    return new Promise((resolve) => {
        entry.file(resolve, () => resolve(null))
    })
}

function normalizeBrowserRelativePath(input: string): string | null {
    const normalized = input.replaceAll(`\\`, `/`).replace(/^\/+|\/+$/g, ``)
    if (!normalized) return null
    if (normalized.split(`/`).some(segment => !segment || segment === `.` || segment === `..`)) return null
    return normalized
}

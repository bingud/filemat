import {
    clearIncompleteUpload,
    findIncompleteByPath,
    headTusUpload,
    listStoredTusUploads,
    removeStoredTusUpload,
    TUS_UPLOAD_ENDPOINT,
    terminateTusUpload,
    tryFinalizeCompleteTusUpload,
} from "$lib/code/module/files/tusIncompleteUploads"
import {
    deleteUploadFileHandle,
    fileFromStoredHandle,
    getUploadFileHandle,
    putUploadFileHandle,
    supportsOpenFilePicker,
} from "$lib/code/module/files/uploadFileHandleStore"
import { confirmDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte"
import { filesState } from "$lib/code/stateObjects/filesState.svelte"
import type { FileMetadata, FullFileMetadata } from "$lib/code/auth/types"
import type { FileCategory } from "$lib/code/data/files"
import { addComputedValuesToFileMeta, arrayRemove, decodeBase64, entriesOf, filenameFromPath, formData, generateRandomNumber, generateRandomString, getUniqueFilename, handleErr, handleException, isChildOf, isPathDirectChild, isSymlink, letterS, parentFromPath, parseJson, resolvePath, Result, safeFetch, sortArrayAlphabetically, unixNowMillis } from "$lib/code/util/codeUtil.svelte"
import { uploadState, type FileUpload, type PreviousTusUpload, type TusUploadOptions } from "$lib/code/stateObjects/subState/uploadState.svelte"
import { toast } from "@jill64/svelte-toast"
import { goto } from "$app/navigation"
import { persistentToast_loading } from "$lib/code/util/uiUtil"
import * as tus from "tus-js-client"


export type FileData = { meta: FullFileMetadata, entries: FullFileMetadata[] | null }
export const UPLOAD_CONCURRENCY_LIMIT = 1
const SNAPSHOT_BEFORE_UPLOAD_MAX_BYTES = 64 * 1024 * 1024

export type { TusUploadOptions }

/**
 * Fetches file metadata (and folder entries if the file is a folder)
 */
export async function getFileData(
    path: string,
    urlPath: string,
    signal: AbortSignal | undefined,
    options: { foldersOnly?: boolean, silent?: boolean, shareToken?: string }
): Promise<Result<FileData>> {
    const body = formData({ path: path, foldersOnly: options.foldersOnly || false })
    if (options.shareToken) {
        body.append("shareToken", options.shareToken)
    }

    const response = await safeFetch(urlPath, {
        body: body,
        signal: signal
    })
    if (response.failed) {
        handleErr({
            description: `Failed to fetch file data`,
            notification: options.silent ? undefined : `Failed to open file.`,
        })
        return Result.error(`Failed to fetch folder entries`)
    }
    const status = response.code
    const json = response.json()

    if (status.notFound) {
        return Result.notFound()
    } else if (status.failed) {
        if (path === "/" && json.error === "no-permission") return Result.reject(json.message)

        handleErr({
            description: `Failed to open file.`,
            notification: options.silent ? undefined : (json.message || `Failed to open file.`),
            isServerDown: status.serverDown
        })
        return Result.error(json.message)
    }

    let data = json as FileData
    if (data.entries) {
        data.entries.forEach(addComputedValuesToFileMeta)
    }

    addComputedValuesToFileMeta(data.meta)

    return Result.ok(data)
}

export async function getFileListFromCustomEndpoint({
    path, urlPath, signal, silent, bodyParams
}: {
    path: string
    urlPath: string
    signal: AbortSignal | undefined
    silent: boolean
    bodyParams?: Record<string, string>
}): Promise<Result<FullFileMetadata[]>> {
    const body = formData({ path: path, foldersOnly: false })
    if (bodyParams) {
        entriesOf(bodyParams).forEach(([k, v]) => {
            body.append(k, v)
        })
    }

    const response = await safeFetch(urlPath, {
        body: body,
        signal: signal
    })
    if (response.failed) {
        handleErr({
            description: `Failed to fetch folder entries`,
            notification: silent ? undefined : `Failed to open folder.`,
        })
        return Result.error(`Failed to fetch folder entries`)
    }
    const status = response.code
    const json = response.json()

    if (status.notFound) {
        return Result.notFound()
    } else if (status.failed) {
        handleErr({
            description: `Failed to open folder.`,
            notification: silent ? undefined : (json.message || `Failed to open folder.`),
            isServerDown: status.serverDown
        })
        return Result.error(json.message)
    }

    if (json) {
        json.forEach(addComputedValuesToFileMeta)
    }

    return Result.ok(json)
}


export async function streamFileContent(
    path: string, 
    options: {
        shareToken?: string, 
        signal: AbortSignal
    }
): Promise<Blob | null> {
    const body = formData({ path: path })
    if (options.shareToken) {
        body.append("shareToken", options.shareToken)
    }

    const response = await safeFetch(`/api/v1/file/content`,{ 
        body: body, signal: options.signal
    }, true)
    if (response.failed) {
        const exception = response.exception
        if (exception.name === "AbortError") {
            return null
        }

        handleErr({
            description: `Failed to receive streamed file`,
            notification: `Failed to download file.`,
        })
        return null
    }

    const status = response.code
    if (status.notFound) {
        handleErr({
            description: `File content not found`,
            notification: `This file was not found.`,
        })
        return null
    } else if (status.failed) {
        const text = await response.text()
        const json = parseJson(text)
        handleErr({
            description: `Failed to download file.`,
            notification: json?.message || `Failed to download file.`,
            isServerDown: status.serverDown,
        })
        return null
    }

    if (!response.body) return null

    const reader = (response.body as any as ReadableStream<Uint8Array>).getReader()
    const chunks = []
    let receivedLength = 0

    while (true) {
        const { done, value } = await reader.read()
        if (done) break

        chunks.push(value)
        receivedLength += value.length
    }

    const blob = new Blob(chunks as BlobPart[], {
        type: response.headers.get("Content-Type") || "application/octet-stream",
    })
    return blob
}

/**
 * Get the contents of a blob
 */
export async function getBlobContent(blob: Blob, fileCategory: FileCategory): Promise<any | null> {
    switch (fileCategory) {
        case "html":
        case "text":
        case "md":
            return await blob.text();
        case "image":
        case "video":
        case "audio":
            return URL.createObjectURL(blob); // Use as a source in media elements
        case "pdf":
            return blob.arrayBuffer(); // Can be used with PDF viewers
        default:
            return null;
    }
}


/**
 * Initiate a file upload with TUS.
 */
export async function uploadWithTus(isMultiple: boolean = true) {
    if (supportsOpenFilePicker()) {
        try {
            const showOpenFilePicker = (window as any).showOpenFilePicker as (options?: any) => Promise<any[]>
            const handles = await showOpenFilePicker({
                multiple: isMultiple,
                excludeAcceptAllOption: false,
            })
            for (const handle of handles || []) {
                const file = await handle.getFile() as File
                // Await so incomplete-upload confirm dialogs cannot stack/race.
                await startTusUpload(file, { fileHandle: handle })
            }
        } catch {
            // User cancelled the picker (or the API failed). Do not open a second picker.
        }
        return
    }

    const input = document.createElement('input')
    input.type = 'file'
    input.style.display = 'none'
    input.multiple = isMultiple

    input.onchange = async (e) => {
        const files = (e.target as HTMLInputElement).files
        if (!files || files.length === 0) {
            return
        }

        for (const file of files) {
            // Await so incomplete-upload confirm dialogs cannot stack/race.
            await startTusUpload(file)
        }

        // Clean up the input element
        input.value = ''
        document.body.removeChild(input)
    }

    // Append to body, trigger click, and remove
    document.body.appendChild(input)
    input.click()
}


/**
 * Initiate a TUS file upload
 */
export async function startTusUpload(file: File, options: TusUploadOptions = {}) {
    uploadState.panelOpen = true

    // Construct the full target path
    const currentPath = filesState.path === '/' ? '' : filesState.path
    const inputFilename = file.name

    const targetFilename = options.targetFilename || getUniqueFilename(
        inputFilename,
        (filesState.data.entries ?? []).map(v => v.filename!),
    )
    const targetPath = options.targetPath || `${currentPath}/${targetFilename}`

    // Folder sessions already resolved conflicts; a leftover incomplete for the same
    // path must not open Continue/Restart dialogs. Clear local state immediately and
    // terminate the old server upload in the background so we never block the batch.
    if (options.metadata?.folderUploadSessionId) {
        const incomplete = findIncompleteByPath(targetPath)
        if (incomplete) {
            uploadState.removeUpload(targetPath)
            void removeStoredTusUpload(incomplete.urlStorageKey)
            void terminateTusUpload(incomplete.uploadUrl)
            void deleteUploadFileHandle(targetPath, incomplete.uploadUrl, incomplete.urlStorageKey)
        }
    } else {
        const conflictDecision = await resolveIncompletePathConflict(targetPath)
        if (conflictDecision === `abort`) return
        if (conflictDecision === `continue`) {
            const incomplete = findIncompleteByPath(targetPath)
            if (incomplete) {
                if (options.fileHandle) {
                    await putUploadFileHandle(targetPath, options.fileHandle)
                    if (incomplete.uploadUrl) await putUploadFileHandle(incomplete.uploadUrl, options.fileHandle)
                }
                await resumeIncompleteUpload(incomplete, file)
            }
            return
        }
    }

    beginTusUpload(file, options, targetPath, targetFilename)
}

function beginTusUpload(
    file: File,
    options: TusUploadOptions,
    targetPath: string,
    targetFilename: string,
    previousUpload: PreviousTusUpload | null = null,
) {
    console.log(`Attempting to upload ${file.name} to ${targetPath}`)

    if (options.fileHandle) {
        void putUploadFileHandle(targetPath, options.fileHandle)
    }

    // Get the actual uploaded filename from the server
    let actualFilename: string | null = null

    const upload = new tus.Upload(file, {
        endpoint: TUS_UPLOAD_ENDPOINT,
        retryDelays: [0, 1000, 3000, 5000, 7000, 10000, 15000, 20000],
        metadata: {
            ...(options.metadata || {}),
            path: targetPath,
        },
        chunkSize: 64 * 1024 * 1024, // 128 MB chunk
        removeFingerprintOnSuccess: true,
        onUploadUrlAvailable: () => {
            const handle = options.fileHandle
            if (!handle) return
            // Persist under path immediately; also under upload URL once known.
            void putUploadFileHandle(targetPath, handle)
            if (upload.url) {
                void putUploadFileHandle(upload.url, handle)
            }
            const state = uploadState.get(targetPath)
            if (state) {
                state.uploadUrl = upload.url
                state.fileHandle = handle
            }
        },
        onAfterResponse: (_req, response) => {
            // Get the actual uploaded filename from the server
            // If the file already exists, the server will add a number to the end of the filename
            const res = response.getUnderlyingObject() as XMLHttpRequest | null
            const actualFilenameHeader = res?.getResponseHeader("actual-uploaded-filename")
            if (actualFilenameHeader) {
                actualFilename = decodeBase64(actualFilenameHeader)

                const state = uploadState.all[targetPath]
                if (state) {
                    state.actualPath = actualFilename
                }
            }
        },
        onError: (error) => {
            try {
                const isAborted = error?.message?.includes("aborted")
                if (isAborted) {
                    const state = uploadState.get(targetPath)
                    if (state) {
                        if (state.action === `pausing` || state.status === `paused`) {
                            state.status = `paused`
                            state.action = null
                        } else {
                            state.status = `canceled`
                        }
                    }
                    return
                }

                const res = (error as tus.DetailedError).originalResponse?.getUnderlyingObject() as XMLHttpRequest | null
                const text = res?.responseText
                const json = parseJson(text || "")
                const fileChanged = isUploadFileChangedError(error)
                const message = fileChanged
                    ? `Failed to upload "${file.name}" because the local file changed while it was being uploaded.`
                    : json?.message || text || "Failed to upload file."

                const isCustomError = json?.error === "custom"
                handleException(`Failed to upload file with TUS. Is custom error: ${isCustomError}`, message, error)

                const state = uploadState.all[targetPath]
                if (state) {
                    state.status = "failed"
                }

                // Permanent failures should not become "incomplete" after refresh.
                // Transient/network failures keep tus localStorage so the upload can resume.
                if (isCustomError || fileChanged) {
                    void forgetPersistedTusUpload(upload, targetPath, previousUpload)
                }
            } finally { startUploadFromQueue() }
        },
        onProgress: (bytesUploaded, bytesTotal) => {
            const percentage = Number(((bytesUploaded / bytesTotal) * 100).toFixed(2))
            
            const state = uploadState.all[targetPath]
            if (state) {
                state.status = "uploading"

                state.percentage = percentage
                state.bytesTotal = bytesTotal
                state.bytesUploaded = bytesUploaded
            }
        },
        onSuccess: () => {
            try {
                const uploadedFile = upload.file as File

                const uploadFolder = targetPath.substring(0, targetPath.lastIndexOf('/')) || "/"
                const actualUploadedPath = actualFilename ? (`${uploadFolder === "/" ? "/" : `${uploadFolder}/`}${actualFilename}`) : null

                // Add the uploaded file to entries if it belongs in the current folder
                if (!options.refreshCurrentFolderOnSuccess && filesState.path === uploadFolder) {
                    filesState.data.entries?.push({
                        path: actualUploadedPath || targetPath,
                        filename: actualFilename || targetFilename,
                        modifiedDate: unixNowMillis(),
                        createdDate: unixNowMillis(),
                        fileType: "FILE",
                        size: uploadedFile.size,
                        permissions: filesState.data.folderMeta!.permissions,
                        isWritable: true,
                        isExecutable: true,
                        isSaved: false,
                        isSymlink: false,
                    })
                }

                const state = uploadState.all[targetPath]
                if (state) {
                    state.status = "success"
                }
                deleteUploadFileHandle(targetPath, upload.url, previousUpload?.uploadUrl, previousUpload?.urlStorageKey)

                if (options.refreshCurrentFolderOnSuccess) {
                    options.onSuccess?.()
                }
            } finally { startUploadFromQueue() }
        },
        onShouldRetry: (err, retryAttempt, options) => {
            if (isUploadFileChangedError(err)) return false

            // Try to extract JSON from the failed response
            const originalResponse = err.originalResponse?.getUnderlyingObject() as XMLHttpRequest | null
            if (originalResponse) {
                const text = originalResponse.responseText
                const json = parseJson(text || "")

                // If it's our custom error, abort retries
                if (json?.error === "custom") {
                    return false
                }
            }

            // Otherwise retry if we still have attempts left
            return retryAttempt < (options.retryDelays?.length || 0)
        },
    });

    if (previousUpload) {
        upload.resumeFromPreviousUpload(previousUpload as any)
    }

    (upload as any).onAbort = () => {
        startUploadFromQueue()
    }

    const rawStart = upload.start.bind(upload)
    upload.start = () => startUploadWithOptionalSnapshot(upload, file, options, targetPath, rawStart)

    // Preserve progress already known for this path (e.g. incomplete after refresh).
    // Otherwise queued resumes briefly show 0 MB until their request starts.
    const existing = uploadState.get(targetPath)
    const extras = {
        uploadUrl: upload.url || previousUpload?.uploadUrl || null,
        urlStorageKey: previousUpload?.urlStorageKey || existing?.urlStorageKey || null,
        previousUpload,
        bytesUploaded: existing?.bytesUploaded || 0,
        bytesTotal: existing?.bytesTotal || file.size,
        fileHandle: options.fileHandle || existing?.fileHandle || null,
    }

    // Get count of currently uploading files
    const currentlyUploadedFiles = uploadState.list.filter(v => v.status === "uploading")
    const currentlyUploadedCount = currentlyUploadedFiles.length

    // Check whether to queue or start upload
    if (currentlyUploadedCount < UPLOAD_CONCURRENCY_LIMIT) {
        if (!uploadState.addUpload(targetPath, upload, "uploading", options, extras)) return
        // Start the upload
        upload.start()
    } else {
        if (!uploadState.addUpload(targetPath, upload, "queued", options, extras)) return
    }
}

async function resolveIncompletePathConflict(targetPath: string): Promise<"proceed" | "continue" | "abort"> {
    let incomplete = findIncompleteByPath(targetPath)
    if (!incomplete?.uploadUrl) return `proceed`

    const head = await headTusUpload(incomplete.uploadUrl)
    if (!head.ok) {
        await removeStoredTusUpload(incomplete.urlStorageKey)
        uploadState.removeUpload(targetPath)
        return `proceed`
    }

    const choice = await confirmDialogState.show({
        title: `Incomplete upload found`,
        message: `An incomplete upload already exists for this file.`,
        confirmText: `Resume upload`,
        alternateText: `New upload`,
        cancelText: `Cancel`,
    })

    if (choice === true) return `continue`
    if (choice === `alternate`) {
        await clearIncompleteUpload(incomplete)
        return `proceed`
    }
    return `abort`
}

/**
 * Pick a local file for resume (File System Access API when available, else input).
 * When a handle is returned, caller can persist it for later resumes.
 */
export function pickFileForResume(acceptName?: string): Promise<{ file: File, handle?: FileSystemFileHandle } | null> {
    if (supportsOpenFilePicker()) {
        const showOpenFilePicker = (window as any).showOpenFilePicker as (options?: any) => Promise<any[]>
        return showOpenFilePicker({
            multiple: false,
            excludeAcceptAllOption: false,
        }).then(async handles => {
            const handle = handles?.[0]
            if (!handle) return null
            const file = await handle.getFile() as File
            return { file, handle }
        }).catch(() => null)
    }

    return new Promise(resolve => {
        const input = document.createElement(`input`)
        input.type = `file`
        input.style.display = `none`
        if (acceptName) {
            // Hint only; user can still pick any file.
            input.title = `Select ${acceptName}`
        }
        input.onchange = () => {
            const file = input.files?.[0] || null
            document.body.removeChild(input)
            resolve(file ? { file } : null)
        }
        input.oncancel = () => {
            document.body.removeChild(input)
            resolve(null)
        }
        document.body.appendChild(input)
        input.click()
    })
}

function fileMatchesPreviousUpload(file: File, previous: PreviousTusUpload | null, expectedSize: number): boolean {
    if (previous?.size != null && previous.size !== file.size) return false
    if (expectedSize > 0 && file.size !== expectedSize) return false
    // keep-both remaps target path; prefer original name from relativePath when present.
    const originalName = previous?.metadata?.relativePath
        ? filenameFromPath(previous.metadata.relativePath)
        : null
    if (originalName && file.name !== originalName) return false
    return true
}

function isClientProgressComplete(fileUpload: FileUpload, previous?: PreviousTusUpload | null): boolean {
    const total = fileUpload.bytesTotal || previous?.size || 0
    if (total <= 0) return false
    return fileUpload.bytesUploaded >= total
}

/**
 * Mark an upload done after finalize (or after the TUS temp was already deleted
 * because finalize succeeded and the client missed the response).
 */
async function markUploadFinalized(
    fileUpload: FileUpload,
    opts: {
        uploadUrl?: string | null
        urlStorageKey?: string | null
        actualFilename?: string | null
        assumedAlreadyFinalized?: boolean
    } = {},
) {
    await removeStoredTusUpload(opts.urlStorageKey)
    await deleteUploadFileHandle(fileUpload.path, opts.uploadUrl, opts.urlStorageKey)
    fileUpload.status = `success`
    if (opts.actualFilename) {
        const uploadFolder = fileUpload.path.substring(0, fileUpload.path.lastIndexOf(`/`)) || `/`
        fileUpload.actualPath = uploadFolder === `/`
            ? `/${opts.actualFilename}`
            : `${uploadFolder}/${opts.actualFilename}`
    }
    fileUpload.options.onSuccess?.()
    toast.success(opts.assumedAlreadyFinalized ? `Upload already completed.` : `Upload finalized.`)
}

/**
 * When bytes are fully on the server, finalize (or treat a gone upload as already finalized).
 * @returns true if the upload was resolved (success or hard failure that should stop retry/resume)
 */
async function resolveCompleteServerUpload(
    fileUpload: FileUpload,
    uploadUrl: string,
    previous: PreviousTusUpload | null,
): Promise<boolean> {
    const head = await headTusUpload(uploadUrl)

    // Temp upload already deleted after a successful finalize the client never saw.
    if (!head.ok) {
        if (isClientProgressComplete(fileUpload, previous)) {
            await markUploadFinalized(fileUpload, {
                uploadUrl,
                urlStorageKey: previous?.urlStorageKey,
                assumedAlreadyFinalized: true,
            })
            return true
        }
        return false
    }

    if (
        head.offset == null
        || head.length == null
        || head.length <= 0
        || head.offset < head.length
    ) {
        return false
    }

    const finalized = await tryFinalizeCompleteTusUpload(uploadUrl, head.offset)
    if (finalized.ok) {
        await markUploadFinalized(fileUpload, {
            uploadUrl,
            urlStorageKey: previous?.urlStorageKey,
            actualFilename: finalized.actualFilename,
        })
        return true
    }

    // Finalize already ran and wiped the TUS folder — do not start a duplicate upload.
    if (finalized.notFound) {
        await markUploadFinalized(fileUpload, {
            uploadUrl,
            urlStorageKey: previous?.urlStorageKey,
            assumedAlreadyFinalized: true,
        })
        return true
    }

    handleErr({
        description: `Failed to finalize complete TUS upload`,
        notification: finalized.message || `Failed to finalize the upload. Try again or restart it.`,
    })
    return true
}

/**
 * Pause an in-flight upload by aborting the current request (keeps server bytes).
 */
export async function pauseUpload(fileUpload: FileUpload) {
    if (fileUpload.status !== `uploading` || !fileUpload.upload) return
    if (fileUpload.action) return

    fileUpload.action = `pausing`
    if (fileUpload.upload.url) {
        fileUpload.uploadUrl = fileUpload.upload.url
    }

    try {
        await fileUpload.upload.abort(false)
    } catch {
        // Request may already be finished; still mark paused below.
    }

    if (fileUpload.status === `uploading` || fileUpload.action === `pausing`) {
        fileUpload.status = `paused`
        fileUpload.action = null
        startUploadFromQueue()
    }
}

/**
 * Resume a paused upload (same tab; Upload instance still available).
 */
export function resumePausedUpload(fileUpload: FileUpload) {
    if (fileUpload.status !== `paused` || !fileUpload.upload) return

    if (fileUpload.upload.url) {
        fileUpload.uploadUrl = fileUpload.upload.url
    }

    const currentlyUploadedCount = uploadState.list.filter(v => v.status === `uploading`).length
    if (currentlyUploadedCount >= UPLOAD_CONCURRENCY_LIMIT) {
        fileUpload.status = `queued`
        return
    }

    fileUpload.status = `uploading`
    fileUpload.upload.start()
}

/**
 * Resume from the upload panel: paused uses the live Upload; incomplete reattaches the file.
 */
export async function resumeUpload(fileUpload: FileUpload) {
    if (fileUpload.status === `paused`) {
        resumePausedUpload(fileUpload)
        return
    }
    if (fileUpload.status === `incomplete`) {
        await resumeIncompleteUpload(fileUpload)
    }
}

/** Pause every active upload; park queued ones so they do not auto-start. */
export async function pauseAllUploads() {
    const list = [...uploadState.list]
    for (const up of list) {
        if (up.status === `queued`) {
            up.status = `paused`
        }
    }
    for (const up of list) {
        if (up.status === `uploading`) {
            await pauseUpload(up)
        }
    }
}

/** Resume every paused/incomplete upload. */
export async function resumeAllUploads() {
    const list = [...uploadState.list]
    for (const up of list) {
        if (up.status === `paused` || up.status === `incomplete`) {
            await resumeUpload(up)
        }
    }
}

/** Resume an incomplete upload after the user reattaches the local file. */
export async function resumeIncompleteUpload(fileUpload: FileUpload, preselectedFile?: File) {
    if (fileUpload.status !== `incomplete`) return

    const previous = fileUpload.previousUpload
    if (!previous?.uploadUrl) {
        handleErr({
            description: `Incomplete upload is missing upload URL`,
            notification: `Cannot resume this upload. Please restart it.`,
        })
        return
    }

    // Bytes already on server: tus would emit success without PATCH/finalize.
    // Force an empty PATCH so the server re-runs finalize instead.
    if (await resolveCompleteServerUpload(fileUpload, previous.uploadUrl, previous)) {
        return
    }

    let file = preselectedFile || null
    let fileHandle: FileSystemFileHandle | undefined = fileUpload.fileHandle || undefined

    if (!file) {
        // Use in-memory handle first (loaded at sync) so requestPermission stays close to the click.
        if (!fileHandle) {
            const storedHandle =
                await getUploadFileHandle(fileUpload.path)
                || await getUploadFileHandle(previous.uploadUrl)
                || await getUploadFileHandle(previous.urlStorageKey)
            if (storedHandle) fileHandle = storedHandle as FileSystemFileHandle
        }

        if (fileHandle) {
            file = await fileFromStoredHandle(fileHandle)
            if (!file) {
                // Permission denied or handle stale — clear and fall through to picker.
                fileHandle = undefined
                fileUpload.fileHandle = null
            }
        }
    }

    if (!file) {
        const picked = await pickFileForResume(filenameFromPath(fileUpload.path))
        if (!picked) return
        file = picked.file
        fileHandle = picked.handle
    }

    if (!fileMatchesPreviousUpload(file, previous, fileUpload.bytesTotal)) {
        handleErr({
            description: `Picked file does not match incomplete upload`,
            notification: `That file does not match the incomplete upload. Pick the original file to continue.`,
        })
        return
    }

    const options: TusUploadOptions = {
        ...fileUpload.options,
        targetPath: fileUpload.path,
        targetFilename: filenameFromPath(fileUpload.path),
        snapshotBeforeUpload: false,
        fileHandle,
    }

    if (fileHandle) {
        fileUpload.fileHandle = fileHandle
        void putUploadFileHandle(fileUpload.path, fileHandle)
        if (previous.uploadUrl) void putUploadFileHandle(previous.uploadUrl, fileHandle)
        if (previous.urlStorageKey) void putUploadFileHandle(previous.urlStorageKey, fileHandle)
    }

    beginTusUpload(file, options, fileUpload.path, filenameFromPath(fileUpload.path), previous)
}

async function forgetPersistedTusUpload(
    upload: tus.Upload,
    targetPath: string,
    previousUpload: PreviousTusUpload | null,
) {
    const url = upload.url || previousUpload?.uploadUrl || null
    if (previousUpload?.urlStorageKey) {
        await removeStoredTusUpload(previousUpload.urlStorageKey)
    } else if (url) {
        const stored = await listStoredTusUploads()
        const match = stored.find(entry => entry.uploadUrl === url)
        if (match) await removeStoredTusUpload(match.urlStorageKey)
    }
    await deleteUploadFileHandle(targetPath, url, previousUpload?.urlStorageKey)
}

export async function cancelUpload(fileUpload: FileUpload) {
    if (fileUpload.action === `canceling`) return
    fileUpload.action = `canceling`

    if (
        fileUpload.status === `incomplete`
        || fileUpload.status === `paused`
        || fileUpload.status === `queued`
        || !fileUpload.upload
    ) {
        const uploadUrl = fileUpload.uploadUrl || fileUpload.upload?.url || null
        if (fileUpload.upload) {
            try {
                await fileUpload.upload.abort(!!uploadUrl)
            } catch {
                // fall through to manual cleanup
            }
        }
        await terminateTusUpload(uploadUrl)
        await removeStoredTusUpload(fileUpload.urlStorageKey)
        await deleteUploadFileHandle(
            fileUpload.path,
            uploadUrl,
            fileUpload.urlStorageKey,
        )
        uploadState.removeUpload(fileUpload.path)
        startUploadFromQueue()
        return
    }

    try {
        await fileUpload.upload.abort(true)
    } catch {
        await terminateTusUpload(fileUpload.uploadUrl || fileUpload.upload.url)
        await removeStoredTusUpload(fileUpload.urlStorageKey)
    }
    await deleteUploadFileHandle(
        fileUpload.path,
        fileUpload.uploadUrl,
        fileUpload.upload.url,
        fileUpload.urlStorageKey,
    )
    uploadState.removeUpload(fileUpload.path)
    const onAbort = (fileUpload.upload as any)?.onAbort
    if (onAbort && typeof onAbort === `function`) {
        onAbort()
    }
}

/** Cancel every non-finished upload and remove finished rows from the panel. */
export async function cancelAllUploads() {
    const list = [...uploadState.list]
    for (const up of list) {
        if (
            up.status === `success`
            || up.status === `canceled`
            || up.status === `skipped`
            || up.status === `failed`
        ) {
            uploadState.removeUpload(up.path)
            continue
        }
        await cancelUpload(up)
    }
}

function startUploadWithOptionalSnapshot(
    upload: tus.Upload,
    file: File,
    options: TusUploadOptions,
    targetPath: string,
    rawStart: () => void,
) {
    if (!options.snapshotBeforeUpload || file.size > SNAPSHOT_BEFORE_UPLOAD_MAX_BYTES) {
        rawStart()
        return
    }

    file.arrayBuffer()
        .then(buffer => {
            const snapshot = new File([buffer], file.name, {
                type: file.type,
                lastModified: file.lastModified,
            })
            const uploadWithFile = upload as unknown as { file: File }
            uploadWithFile.file = snapshot
            rawStart()
        })
        .catch(error => {
            handleException(
                `Failed to snapshot file before upload.`,
                `Failed to prepare "${file.name}" for upload. The file may have changed while it was being read.`,
                error,
            )

            const state = uploadState.all[targetPath]
            if (state) {
                state.status = "failed"
            }
            startUploadFromQueue()
        })
}

function isUploadFileChangedError(error: unknown): boolean {
    const text = `${(error as Error | undefined)?.message || ""} ${error || ""}`.toLowerCase()
    return text.includes("err_upload_file_changed") || text.includes("upload_file_changed")
}

function startUploadFromQueue() {
    const uploads = sortArrayAlphabetically(
        uploadState.list.filter(v => v.status === "queued"), 
        (v) => v.path
    )
    
    if (!uploads.length) return

    const currentlyUploadedCount = uploadState.list.filter(v => v.status === `uploading`).length
    if (currentlyUploadedCount >= UPLOAD_CONCURRENCY_LIMIT) return

    const first = uploads[0]
    if (!first.upload) {
        first.status = "failed"
        startUploadFromQueue()
        return
    }
    if (first.upload.url) {
        first.uploadUrl = first.upload.url
    }
    first.upload.start()
    first.status = "uploading"
}

/**
 * Retry a failed upload by creating a fresh tus.Upload.
 * Reusing the spent Upload instance is unreliable: it keeps a stale URL/source
 * and exhausted retry state, which can make manual retry silently do nothing.
 */
export async function retryTusUpload(fileUpload: FileUpload) {
    if (fileUpload.status !== "failed" && fileUpload.status !== "canceled") return
    if (!fileUpload.upload) return

    const previous = fileUpload.previousUpload
    const uploadUrl = fileUpload.uploadUrl || fileUpload.upload.url || previous?.uploadUrl || null
    if (uploadUrl && await resolveCompleteServerUpload(fileUpload, uploadUrl, previous)) {
        return
    }

    const file = fileUpload.upload.file as File
    const options: TusUploadOptions = {
        ...fileUpload.options,
        targetPath: fileUpload.path,
        targetFilename: filenameFromPath(fileUpload.path),
    }

    // Drop the spent Upload so we don't resume from its dead URL/source.
    try {
        fileUpload.upload.abort(false)
    } catch {
        // ignore abort errors on already-failed uploads
    }
    uploadState.removeUpload(fileUpload.path)

    startTusUpload(file, options)
}


export async function deleteFiles(entries: FileMetadata[]) {
    if (!entries.length) return
    const paths = entries.map(v => v.path)
    const serialized = JSON.stringify(paths)
    
    const removeToast = persistentToast_loading(`Deleting file${letterS(entries.length)}...`)
    const response = await safeFetch(`/api/v1/file/delete-list`, {
        method: "POST",
        body: formData({ pathList: serialized }),
        credentials: "same-origin"
    })
    removeToast()
    
    if (response.failed) {
        handleErr({
            description: response.exception,
            notification: `Failed to delete files: \n"${entries.join('\n')}"`
        })
        return
    }
    
    const status = response.code
    const json = response.json()
    
    if (status.failed) {
        handleErr({
            description: `Failed to delete file.`,
            notification: json.message || `Failed to delete file. (${status})`,
            isServerDown: status.serverDown
        })
        return
    }

    const successfullyDeleted = json as string[]
    // Remove the deleted entry from the current entries list
    if (filesState.data.entries && entries) {
        filesState.data.entries = filesState.data.entries.filter(e => successfullyDeleted.includes(e.path) === false)
    }

    // Navigate to parent folder if current file was deleted
    const currentPath = filesState.data.currentMeta?.path || null
    if (currentPath && entries.some(e => currentPath.startsWith(e.path))) {
        let closestParent = parentFromPath(currentPath)
        entries.forEach((entry) => {
            if (closestParent.startsWith(entry.path)) {
                closestParent = parentFromPath(closestParent)
            }
        })

        const pagePath = filesState.isShared ? filesState.meta.pagePath : "/files"
        if (closestParent) navigateToFilePath(closestParent, pagePath)
    }
    
    // If deleted entry was selected, clear selection
    filesState.selectedEntries.unselectAll(successfullyDeleted)

    const unsuccessfulCount = paths.length - successfullyDeleted.length
    if (unsuccessfulCount > 0) {
        handleErr({
            notification: `Failed to delete ${unsuccessfulCount} file${letterS(unsuccessfulCount)}.`
        })
    }
}


export async function downloadFilesAsZip(paths: string[], shareToken: string | undefined = undefined) {
    const serializedList = JSON.stringify(paths)

    const body = formData({ pathList: serializedList })
    if (shareToken) {
        body.append("shareToken", shareToken)
    }
    
    await downloadFiles(`/api/v1/file/zip-multiple-content`, { body })
}


/**
 * POSTs `form` to `url` and triggers a native download of the response.
 *
 * @param url       endpoint that returns `Content-Disposition: attachment`
 * @param form      FormData to send in the POST body
 */
export async function downloadFiles(
    url: string, 
    p: {
        body?: FormData, 
        method?: "POST" | "GET"
    }
) {
    const response = await safeFetch(
        url,
        {
            method: p.method || "POST",
            body: p.body,
            credentials: 'same-origin',
        },
        true
    )

    if (response.failed) {
        handleErr({
            description: response.exception,
            notification: `Failed to download from ${url}`,
        })
        return
    }

    const status = response.code

    if (status.failed) {
        const json = response.json()
        handleErr({
            description: `Failed to download file.`,
            notification: json.message || `Failed to download file. (${status})`,
            isServerDown: status.serverDown,
        })
        return
    }

    // stream blob and save
    const blob = await response.blob()

    const cd = response.headers.get('Content-Disposition') || ''
    let filename = 'download'
    const m = /filename\*?=(?:UTF-8'')?["']?([^;"']+)/i.exec(cd)
    if (m?.[1]) {
        filename = decodeURIComponent(m[1])
    }

    const urlObj = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.style.display = 'none'
    a.href = urlObj
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(urlObj)
}


export async function moveFile(path: string, newPath: string) {
    const isRename = parentFromPath(path) === parentFromPath(newPath)

    const removeToast = isRename === false ? persistentToast_loading("Moving file...") : () => {}

    const response = await safeFetch(`/api/v1/file/move`, {
        body: formData({ path: path, newPath: newPath })
    })
    removeToast()

    if (response.failed) {
        handleException(`Failed to move file.`, `Failed to move file.`, response.exception)
        return
    }

    const status = response.code
    const json = response.json()

    if (status.notFound) {
        handleErr({
            notification: `Moved file was not found.`
        })
        return
    } else if (status.failed) {
        handleErr({
            description: `Failed to move file.`,
            notification: json.message || `Failed to move file.`,
            isServerDown: status.serverDown
        })
        return
    }

    updateFileListAfterFileMove(path, newPath)
}

export async function copyFile(path: string, newPath: string) {
    const removeToast = persistentToast_loading("Copying file...")

    const response = await safeFetch(`/api/v1/file/copy`, {
        body: formData({ path, newPath })
    })
    removeToast()

    if (response.failed) {
        handleException(`Failed to copy file.`, `Failed to copy file.`, response.exception)
        return
    }
    
    const status = response.code
    const json = response.json()

    if (status.notFound) {
        handleErr({
            notification: `Copied file was not found.`
        })
        return
    } else if (status.failed) {
        handleErr({
            description: `Failed to copy file.`,
            notification: json.message || `Failed to copy file.`,
            isServerDown: status.serverDown
        })
        return
    }

    if (json) {
        const meta = json as FullFileMetadata
        addComputedValuesToFileMeta(meta)

        // Add copied file to entries of current folder
        if (filesState.data.folderMeta && isPathDirectChild(filesState.data.folderMeta.path, newPath)) {
            filesState.data.entries?.push(meta)
        }
    } else {
        // Is destination file in current folder
        if (filesState.data.folderMeta && isPathDirectChild(filesState.data.folderMeta.path, newPath)) {
            // Is original file in current folder
            if (isPathDirectChild(filesState.data.folderMeta.path, path)) {
                const originalEntry = filesState.data.entries!.find(v => v.path === path)
                
                if (originalEntry) {
                    const newEntry = structuredClone(originalEntry)
                    const now = unixNowMillis()
                    newEntry.path = newPath
                    newEntry.modifiedDate = now
                    newEntry.createdDate = now
                    
                    filesState.data.entries!.push(newEntry)
                }
            }
        }
    }
}

export async function moveMultipleFiles(newParentPath: string, paths: string[]) {
    const removeToast = persistentToast_loading("Moving files...")
    
    const response = await safeFetch(`/api/v1/file/move-multiple`, { 
        body: formData({ newParent: newParentPath, paths: JSON.stringify(paths) })
    })
    removeToast()
    
    const status = response.code
    const json = response.json()

    if (status.failed) {
        handleErr({
            description: `Failed to move file.`,
            notification: json.message || `Failed to move file.`,
            isServerDown: status.serverDown
        })
        return
    }

    const movedFiles = json as string[]
    const failedCount = paths.length - movedFiles.length

    movedFiles.forEach(oldPath => {
        const newPath = resolvePath(newParentPath, filenameFromPath(oldPath))
        console.log(`old `, oldPath, "new", newPath)
        updateFileListAfterFileMove(oldPath, newPath)
    })

    if (failedCount > 0) {
        if (failedCount === paths.length) {
            toast.error(`Failed to move file${letterS(paths.length)}.`)
        } else {
            toast.error(`Failed to move ${failedCount} file${letterS(failedCount)}`)
        }
    }
}

function updateFileListAfterFileMove(oldPath: string, newPath: string) {
    console.log(`moving`, oldPath, newPath)
    if (isChildOf(oldPath, filesState.path)) {
        const entry = filesState.data.entries?.find(v => v.path === oldPath)
        if (entry) {
            // Check if file was moved in the same folder
            if (parentFromPath(oldPath) === parentFromPath(newPath)) {
                entry.filename = filenameFromPath(newPath)
                entry.path = newPath
            } else {
                arrayRemove(filesState.data.entries!, (v) => v.path === oldPath)
                filesState.data.entries
            }
        }
    }
    
    filesState.selectedEntries.unselect(oldPath)
}

export async function getFileLastModifiedDate(
    path: string, 
    {
        silent = true,
        shareToken
    }: {
        silent?: boolean,
        shareToken?: string,
    }
): Promise<number | null> {
    const response = await safeFetch(`/api/v1/file/last-modified-date`, { 
        body: formData({ path: path, shareToken: shareToken })
    })
    if (response.failed) {
        handleException(`Failed to get last modified date of current file.`, null, response.exception)
        return null
    }
    const status = response.code
    const content = response.content

    if (status.ok) {
        const int = parseInt(content)
        return int
    } else if (status.notFound) {
        if (!silent) {
            handleErr({
                description: `File not found when getting modification date`,
                notification: `This file was not found.`
            })
        }
        return null
    } else if (status.failed) {
        handleErr({
            description: `Failed to check file modification time.`,
            notification: response.json().message || `Failed to check file modification time.`,
            isServerDown: status.serverDown
        })
        return null
    }

    return null
}

export function navigateToFilePath(path: string, pagePath: string) {
    return goto(`${pagePath}${path}`)
}
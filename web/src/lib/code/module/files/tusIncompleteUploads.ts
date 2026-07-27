import * as tus from "tus-js-client"
import { uploadState, type PreviousTusUpload, type TusUploadOptions } from "$lib/code/stateObjects/subState/uploadState.svelte"
import { decodeBase64, filenameFromPath } from "$lib/code/util/codeUtil.svelte"
import { deleteUploadFileHandle, getUploadFileHandle } from "./uploadFileHandleStore"

export const TUS_UPLOAD_ENDPOINT = `/api/v1/file/upload`

function getUrlStorage() {
    return tus.defaultOptions.urlStorage
}

export async function listStoredTusUploads(): Promise<PreviousTusUpload[]> {
    if (!tus.canStoreURLs) return []
    const storage = getUrlStorage()
    if (!storage) return []
    const entries = await storage.findAllUploads()
    return entries
        .filter(entry => {
            const url = entry.uploadUrl || ``
            return url.includes(TUS_UPLOAD_ENDPOINT)
        })
        .map(entry => ({
            size: entry.size,
            metadata: entry.metadata || {},
            creationTime: entry.creationTime,
            urlStorageKey: entry.urlStorageKey,
            uploadUrl: entry.uploadUrl,
            parallelUploadUrls: entry.parallelUploadUrls,
        }))
}

export async function removeStoredTusUpload(urlStorageKey: string | null | undefined) {
    if (!urlStorageKey || !tus.canStoreURLs) return
    try {
        await getUrlStorage()?.removeUpload(urlStorageKey)
    } catch {
        // ignore storage removal errors
    }
}

export async function terminateTusUpload(uploadUrl: string | null | undefined) {
    if (!uploadUrl) return
    try {
        await tus.Upload.terminate(uploadUrl, {
            endpoint: TUS_UPLOAD_ENDPOINT,
        })
    } catch {
        // Server may already have expired the upload; local cleanup still proceeds.
    }
}

export async function headTusUpload(uploadUrl: string): Promise<{
    ok: boolean
    offset: number | null
    length: number | null
}> {
    try {
        const response = await fetch(uploadUrl, {
            method: `HEAD`,
            credentials: `same-origin`,
            headers: {
                "Tus-Resumable": `1.0.0`,
            },
        })
        if (!response.ok) {
            return { ok: false, offset: null, length: null }
        }
        const offsetHeader = response.headers.get(`Upload-Offset`)
        const lengthHeader = response.headers.get(`Upload-Length`)
        const offset = offsetHeader != null ? Number.parseInt(offsetHeader, 10) : null
        const length = lengthHeader != null ? Number.parseInt(lengthHeader, 10) : null
        return {
            ok: true,
            offset: Number.isFinite(offset as number) ? offset : null,
            length: Number.isFinite(length as number) ? length : null,
        }
    } catch {
        return { ok: false, offset: null, length: null }
    }
}

/**
 * When bytes are already fully received but finalize failed, tus-js-client would
 * resume with HEAD offset===length and emit success without PATCH. Send an empty
 * PATCH at the current offset so the server re-runs finalize.
 */
export async function tryFinalizeCompleteTusUpload(uploadUrl: string, offset: number): Promise<{
    ok: boolean
    actualFilename: string | null
    message: string | null
    notFound: boolean
}> {
    try {
        const response = await fetch(uploadUrl, {
            method: `PATCH`,
            credentials: `same-origin`,
            headers: {
                "Tus-Resumable": `1.0.0`,
                "Upload-Offset": `${offset}`,
                "Content-Type": `application/offset+octet-stream`,
                "Content-Length": `0`,
            },
            body: new Blob([]),
        })

        if (response.status === 404 || response.status === 410) {
            return { ok: false, actualFilename: null, message: `Upload no longer exists on the server.`, notFound: true }
        }

        const text = await response.text()
        let message: string | null = null
        try {
            const json = text ? JSON.parse(text) : null
            message = json?.message || (text || null)
        } catch {
            message = text || null
        }

        if (!response.ok) {
            return { ok: false, actualFilename: null, message: message || `Failed to finalize upload.`, notFound: false }
        }

        const actualFilenameHeader = response.headers.get(`actual-uploaded-filename`)
        let actualFilename: string | null = null
        if (actualFilenameHeader) {
            actualFilename = decodeBase64(actualFilenameHeader)
        }

        return { ok: true, actualFilename, message: null, notFound: false }
    } catch (error) {
        return {
            ok: false,
            actualFilename: null,
            message: error instanceof Error ? error.message : `Failed to finalize upload.`,
            notFound: false,
        }
    }
}

export function pathFromPreviousUpload(previous: PreviousTusUpload): string | null {
    const path = previous.metadata?.path
    return path && path.length > 0 ? path : null
}

export function optionsFromPreviousUpload(previous: PreviousTusUpload, path: string): TusUploadOptions {
    const metadata = { ...previous.metadata }
    // Session is forgotten after refresh; finalize uses path + resolution.
    delete metadata.folderUploadSessionId

    return {
        targetPath: path,
        targetFilename: filenameFromPath(path),
        metadata,
        displayPath: previous.metadata.relativePath || filenameFromPath(path),
        relativePath: previous.metadata.relativePath || undefined,
        snapshotBeforeUpload: false,
    }
}

/**
 * Validate tus localStorage incompletes against the server, purge dead entries,
 * and show valid ones in the upload panel.
 */
export async function syncIncompleteTusUploads() {
    if (!tus.canStoreURLs) return

    const stored = await listStoredTusUploads()
    for (const previous of stored) {
        const uploadUrl = previous.uploadUrl
        const path = pathFromPreviousUpload(previous)

        if (!uploadUrl || !path) {
            await removeStoredTusUpload(previous.urlStorageKey)
            continue
        }

        const existing = uploadState.get(path)
        if (existing && (existing.status === `uploading` || existing.status === `queued`)) {
            continue
        }

        const head = await headTusUpload(uploadUrl)
        if (!head.ok) {
            await removeStoredTusUpload(previous.urlStorageKey)
            if (existing?.status === `incomplete` && existing.urlStorageKey === previous.urlStorageKey) {
                uploadState.removeUpload(path)
            }
            continue
        }

        const bytesTotal = head.length ?? previous.size ?? 0
        const bytesUploaded = head.offset ?? 0

        const fileHandle =
            await getUploadFileHandle(path)
            || await getUploadFileHandle(uploadUrl)
            || await getUploadFileHandle(previous.urlStorageKey)

        uploadState.addUpload(path, null, `incomplete`, optionsFromPreviousUpload(previous, path), {
            uploadUrl,
            urlStorageKey: previous.urlStorageKey,
            previousUpload: previous,
            bytesUploaded,
            bytesTotal,
            fileHandle: fileHandle as FileSystemFileHandle | null,
        })
    }
}

export async function clearIncompleteUpload(fileUpload: {
    path: string
    uploadUrl: string | null
    urlStorageKey: string | null
}) {
    await terminateTusUpload(fileUpload.uploadUrl)
    await removeStoredTusUpload(fileUpload.urlStorageKey)
    await deleteUploadFileHandle(fileUpload.path, fileUpload.uploadUrl, fileUpload.urlStorageKey)
    uploadState.removeUpload(fileUpload.path)
}

export function findIncompleteByPath(path: string) {
    const existing = uploadState.get(path)
    if (existing?.status === `incomplete`) return existing
    return null
}

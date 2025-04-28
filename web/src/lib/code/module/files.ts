import * as tus from "tus-js-client";
import { filesState } from "../stateObjects/filesState.svelte";
import type { FileMetadata, FileType } from "../auth/types";
import type { FileCategory } from "../data/files";
import { filenameFromPath, formData, getUniqueFilename, handleError, handleErrorResponse, handleException, parseJson, safeFetch, unixNowMillis } from "../util/codeUtil.svelte";
import { uploadState } from "../stateObjects/subState/uploadState.svelte";


export type FileData = { meta: FileMetadata, entries: FileMetadata[] | null }

export async function getFileData(path: string, signal: AbortSignal): Promise<FileData | null> {
    const response = await safeFetch(`/api/v1/folder/file-or-folder-entries`, { body: formData({ path: path }), signal: signal })
    if (response.failed) {
        handleException(`Failed to fetch folder entries`, `Failed to open folder.`, response.exception)
        return null
    }
    const status = response.code
    const json = response.json() as FileData

    if (status.serverDown) {
        handleError(`Server ${status} while getting folder entries`, `Failed to open folder. Server is unavailable.`)
        return null
    } else if (status.failed) {
        handleErrorResponse(json, `Failed to open folder.`)
        return null
    }

    if (json.entries) {
        json.entries.forEach((v) => {
            v.filename = filenameFromPath(v.path)
        })
    }

    return json
}


export async function streamFileContent(path: string, signal: AbortSignal): Promise<Blob | null> {
    const response = await safeFetch(`/api/v1/file/content`, { body: formData({ path: path }), signal: signal }, true)
    if (response.failed) {
        const exception = response.exception
        if (exception.name === "AbortError") {
            return null
        }
        
        handleException(`Failed to receive streamed file`, `Failed to download file.`, exception)
        return null
    }

    const status = response.code
    if (status.serverDown) {
        handleError(`Failed to stream file. server ${status}`, `Failed to download file. Server is unavailable.`)
        return null
    } else if (status.failed) {
        const text = await response.text()
        const json = parseJson(text)
        handleErrorResponse(json, `Failed to download file.`)
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

    const blob = new Blob(chunks, { type: response.headers.get("Content-Type") || "application/octet-stream" });
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
export function uploadWithTus() {
    const input = document.createElement('input')
    input.type = 'file'
    input.style.display = 'none' // Keep it hidden

    input.onchange = (e) => {
        const file = (e.target as HTMLInputElement).files?.[0]
        if (!file) {
            return
        }

        uploadState.panelOpen = true

        // Construct the full target path
        const currentPath = filesState.path === '/' ? '' : filesState.path
        const inputFilename = file.name

        const entries = filesState.data.entries!.map(v => v.filename!)
        const targetFilename = getUniqueFilename(inputFilename, entries)
        const targetPath = `${currentPath}/${file.name}`

        console.log(`Attempting to upload ${file.name} to ${targetPath}`)

        // Get the actual uploaded filename from the server
        let actualFilename: string | null = null

        const upload = new tus.Upload(file, {
            endpoint: "/api/v1/file/upload", // Your TUS endpoint
            retryDelays: [0, 3000, 5000, 10000, 20000],
            metadata: {
                filename: targetPath,
                // Add any other metadata your server needs
                // filetype: file.type 
            },
            onAfterResponse: (response) => {
                // Get the actual uploaded filename from the server
                // If the file already exists, the server will add a number to the end of the filename
                const res = response.getUnderlyingObject() as XMLHttpRequest | null
                const actualFilenameHeader = res?.getResponseHeader("actual-uploaded-filename")
                if (actualFilenameHeader) {
                    actualFilename = actualFilenameHeader

                    const state = uploadState.all[targetPath]
                    if (state) {
                        state.actualPath = actualFilenameHeader
                    }
                }
            },
            onError: (error) => {
                const isAborted = error.message.includes("aborted")
                if (isAborted) {
                    const state = uploadState.get(targetPath)
                    if (state) {
                        state.status = "canceled"
                    }
                    return
                }

                const res = (error as tus.DetailedError).originalResponse?.getUnderlyingObject() as XMLHttpRequest | null
                const text = res?.responseText
                const json = parseJson(text || "")
                const message = json.message || text || error.message || "Failed to upload file."

                const isCustomError = json.error === "custom"
                handleException(`Failed to upload file with TUS. Is custom error: ${isCustomError}`, message, error)

                const state = uploadState.all[targetPath]
                if (state) {
                    state.status = "failed"
                }
            },
            onProgress: (bytesUploaded, bytesTotal) => {
                const percentage = Number(((bytesUploaded / bytesTotal) * 100).toFixed(2))
                console.log(bytesUploaded, bytesTotal, percentage + "%")
                
                const state = uploadState.all[targetPath]
                if (state) {
                    state.status = "uploading"

                    state.percentage = percentage
                    state.bytesTotal = bytesTotal
                    state.bytesUploaded = bytesUploaded
                }
            },
            onSuccess: () => {
                const uploadedFile = upload.file as File

                const uploadFolder = targetPath.substring(0, targetPath.lastIndexOf('/')) || "/"
                const actualUploadedPath = actualFilename ? (`${uploadFolder === "/" ? "/" : `${uploadFolder}/`}${actualFilename}`) : null

                // Add the uploaded file to entries if it belongs in the current folder
                if (filesState.path === uploadFolder) {
                    filesState.data.entries?.push({
                        path: actualUploadedPath || targetPath,
                        filename: actualFilename || targetFilename,
                        modifiedDate: unixNowMillis(),
                        createdDate: unixNowMillis(),
                        fileType: "FILE",
                        size: uploadedFile.size
                    })
                }

                const state = uploadState.all[targetPath]
                if (state) {
                    state.status = "success"
                }
            },
            onShouldRetry: (err, retryAttempt, options) => {
                // Try to extract JSON from the failed response
                const xhr = err.originalResponse?.getUnderlyingObject() as XMLHttpRequest | null
                if (!xhr) return false

                const text = xhr.responseText
                const json = parseJson(text || "")

                // If it's our custom error, abort retries
                if (json?.error === "custom") {
                    return false
                }

                // Otherwise retry if we still have attempts left
                return retryAttempt < (options.retryDelays?.length || 0)
            },
        })

        if (!uploadState.addUpload(targetPath, upload)) return

        // Start the upload
        upload.start()

        // Clean up the input element
        document.body.removeChild(input)
    }

    // Append to body, trigger click, and remove
    document.body.appendChild(input)
    input.click()
}
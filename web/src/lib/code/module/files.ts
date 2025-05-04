import * as tus from "tus-js-client";
import { filesState } from "../stateObjects/filesState.svelte";
import type { FileMetadata, FileType, FullFileMetadata } from "../auth/types";
import type { FileCategory } from "../data/files";
import { filenameFromPath, formData, getFileId, getUniqueFilename, handleError, handleErrorResponse, handleException, parseJson, safeFetch, unixNowMillis } from "../util/codeUtil.svelte";
import { uploadState } from "../stateObjects/subState/uploadState.svelte";


export type FileData = { meta: FullFileMetadata, entries: FullFileMetadata[] | null }

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
        const targetPath = `${currentPath}/${targetFilename}`

        console.log(`Attempting to upload ${file.name} to ${targetPath}`)

        // Get the actual uploaded filename from the server
        let actualFilename: string | null = null

        const upload = new tus.Upload(file, {
            endpoint: "/api/v1/file/upload", // Your TUS endpoint
            // retryDelays: ((attempt: any) => {
            //     const delays = [0, 1000, 3000, 5000, 7000, 10000, 15000, 20000]
            //     return attempt < delays.length ? delays[attempt] : 20000
            // }) as any as number[],
            retryDelays: [0, 1000, 3000, 5000, 7000, 10000, 15000, 20000],
            metadata: {
                filename: targetPath,
                // Add any other metadata your server needs
                // filetype: file.type 
            },
            chunkSize: 5 * 1024 * 1024,
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
                const isAborted = error?.message?.includes("aborted")
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
                const message = json?.message || text || "Failed to upload file."

                const isCustomError = json?.error === "custom"
                handleException(`Failed to upload file with TUS. Is custom error: ${isCustomError}`, message, error)

                const state = uploadState.all[targetPath]
                if (state) {
                    state.status = "failed"
                }
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
                        size: uploadedFile.size,
                        permissions: filesState.data.meta!.permissions
                    })
                }

                const state = uploadState.all[targetPath]
                if (state) {
                    state.status = "success"
                }
            },
            onShouldRetry: (err, retryAttempt, options) => {
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


export async function deleteFiles(entries: FileMetadata[]) {
    if (!entries.length) return
    const paths = entries.map(v => v.path)
    const serialized = JSON.stringify(paths)
    
    const response = await safeFetch(`/api/v1/file/delete-list`, {
        method: "POST",
        body: formData({ pathList: serialized }),
        credentials: "same-origin"
    })
    
    if (response.failed) {
        handleError(response.exception, `Failed to delete files: \n"${entries.join('\n')}"`);
        return
    }
    
    const status = response.code;
    const text = response.content
    const json = response.json()
    
    if (status.ok) {
        // Remove the deleted entry from the current entries list
        if (filesState.data.entries && entries) {
            filesState.data.entries = filesState.data.entries.filter(e => paths.includes(e.path) === false)
        }
        
        // If deleted entry was selected, clear selection
        filesState.selectedEntries.unselectAll(paths)

        const deletedEntries = parseInt(text)
        if (deletedEntries != null) {
            if (entries.length !== deletedEntries) {
                const failedEntries = entries.length - deletedEntries
                handleError(`Server did not delete all requested files. Deleted ${deletedEntries} of ${entries.length}.`, `Failed to delete ${failedEntries} file${failedEntries === 1 ? '' : 's'}.`)
            }
        }
    } else if (status.serverDown) {
        handleError(`Server ${status} when deleting file.`, "Failed to delete file. The server is unavailable.");
    } else {
        handleErrorResponse(json, `Failed to delete file. (${status})`);
    }
}
import * as tus from "tus-js-client";
import { filesState } from "../stateObjects/filesState.svelte";
import type { FileMetadata, FileType, FullFileMetadata } from "../auth/types";
import type { FileCategory } from "../data/files";
import { arrayRemove, decodeBase64, filenameFromPath, formData, getUniqueFilename, handleError, handleErrorResponse, handleException, isChildOf, letterS, parentFromPath, parseJson, resolvePath, Result, safeFetch, sortArray, sortArrayAlphabetically, unixNowMillis } from "../util/codeUtil.svelte";
import { uploadState } from "../stateObjects/subState/uploadState.svelte";
import { toast } from "@jill64/svelte-toast";


export type FileData = { meta: FullFileMetadata, entries: FullFileMetadata[] | null }

export async function getFileData(path: string, signal: AbortSignal | undefined, foldersOnly: boolean = false): Promise<Result<FileData>> {
    const response = await safeFetch(`/api/v1/folder/file-or-folder-entries`, { body: formData({ path: path, foldersOnly: foldersOnly }), signal: signal })
    if (response.failed) {
        const error = `Failed to fetch folder entries`
        handleException(error, `Failed to open folder.`, response.exception)
        return Result.error(error)
    }
    const status = response.code

    if (status.serverDown) {
        const error = `Server ${status} while getting folder entries`
        handleError(error, `Failed to open folder. Server is unavailable.`)
        return Result.error(error)
    } else if (status.notFound) {
        return Result.notFound()
    } else if (status.failed) {
        const error = response.json()
        handleErrorResponse(error, `Failed to open folder.`)
        return Result.error(error.message)
    }

    const json = response.json() as FileData

    if (json.entries) {
        json.entries.forEach((v) => {
            v.filename = filenameFromPath(v.path)
        })
    }

    return Result.ok(json)
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
    } else if (status.notFound) {
        handleError(`file content not found`, `This file was not found.`)
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
export function uploadWithTus(isMultiple: boolean = true) {
    const input = document.createElement('input')
    input.type = 'file'
    input.style.display = 'none'
    input.multiple = isMultiple

    input.onchange = (e) => {
        const files = (e.target as HTMLInputElement).files
        if (!files || files.length === 0) {
            return
        }

        for (const file of files) {
            startTusUpload(file)
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
export function startTusUpload(file: File) {
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
        endpoint: "/api/v1/file/upload",
        retryDelays: [0, 1000, 3000, 5000, 7000, 10000, 15000, 20000],
        metadata: {
            filename: targetPath,
        },
        chunkSize: 5 * 1024 * 1024,
        onAfterResponse: (response) => {
            // Get the actual uploaded filename from the server
            // If the file already exists, the server will add a number to the end of the filename
            const res = response.getUnderlyingObject() as XMLHttpRequest | null
            const actualFilenameHeader = res?.getResponseHeader("actual-uploaded-filename")
            if (actualFilenameHeader) {
                actualFilename = decodeBase64(actualFilenameHeader)

                const state = uploadState.all[targetPath]
                if (state) {
                    state.actualPath = actualFilenameHeader
                }
            }
        },
        onError: (error) => {
            try {
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
            } finally { startUploadFromQueue() }
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
    });

    (upload as any).onAbort = () => {
        startUploadFromQueue()
    }

    // Get count of currently uploading files
    const currentlyUploadedFiles = uploadState.list.filter(v => v.status === "uploading")
    const currentlyUploadedCount = currentlyUploadedFiles.length

    // Check whether to queue or start upload
    if (currentlyUploadedCount < 1) {
        if (!uploadState.addUpload(targetPath, upload, "uploading")) return
        // Start the upload
        upload.start()
    } else {
        if (!uploadState.addUpload(targetPath, upload, "queued")) return
    }
}

function startUploadFromQueue() {
    const uploads = sortArrayAlphabetically(
        uploadState.list.filter(v => v.status === "queued"), 
        (v) => v.path
    )
    
    if (!uploads.length) return

    const first = uploads[0]
    first.upload.start()
    first.status = "uploading"
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


export async function downloadFilesAsZip(paths: string[]) {
    const serializedList = JSON.stringify(paths)
    await downloadFiles(`/api/v1/file/zip-multiple-content`, formData({ pathList: serializedList }))
}


/**
 * POSTs `form` to `url` and triggers a native download of the response.
 *
 * @param url       endpoint that returns `Content-Disposition: attachment`
 * @param form      FormData to send in the POST body
 */
async function downloadFiles(url: string, form: FormData) {
    const response = await safeFetch(url, {
        method: 'POST',
        body: form,
        credentials: 'same-origin',
    }, true)

    if (response.failed) {
        handleError(
            response.exception,
            `Failed to download from ${url}`
        )
        return
    }

    const status = response.code

    // stream blob and save
    if (status.ok) {
        // extract raw fetch Response to get blob() and headers
        const blob = await response.blob()

        // attempt to parse filename from Content-Disposition
        const cd = response.headers.get('Content-Disposition') || ''
        let filename = 'download'
        const m = /filename\*?=(?:UTF-8'')?["']?([^;"']+)/i.exec(cd)
        if (m?.[1]) {
            filename = decodeURIComponent(m[1])
        }

        // create object URL + anchor click
        const urlObj = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.style.display = 'none'
        a.href = urlObj
        a.download = filename
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
        URL.revokeObjectURL(urlObj)
    } else if (status.serverDown) {
        handleError(
            `Server returned ${status} when downloading.`,
            `Download failed. The server is unavailable.`
        )
    } else {
        const json = await response.json()
        handleErrorResponse(
            json,
            `Failed to download file. (${status})`
        )
    }
}


export async function moveFile(path: string, newPath: string) {
    const response = await safeFetch(`/api/v1/file/move`, { 
        body: formData({path: path, newPath: newPath})
    })
    const status = response.code
    const json = response.json()

    if (status.ok) {
        updateFileListAfterFileMove(path, newPath)
    } else if (status.serverDown) {
        handleError(
            `Server returned ${status} when moving file.`,
            `File move failed. The server is unavailable.`
        )
    } else if (status.notFound) {
        handleError(`file not found when moving`, `This file was not found.`)
    } else {
        handleErrorResponse(
            json,
            `Failed to move file. (${status})`
        )
    }
}

export async function moveMultipleFiles(newParentPath: string, paths: string[]) {
    const response = await safeFetch(`/api/v1/file/move-multiple`, { 
        body: formData({newParent: newParentPath, paths: JSON.stringify(paths)})
    })
    const status = response.code
    const json = response.json()

    if (status.ok) {
        const movedFiles = json as string[]
        const failedCount = paths.length - movedFiles.length

        movedFiles.forEach(oldPath => {
            const newPath = resolvePath(newParentPath, filenameFromPath(oldPath))
            console.log(`old `, oldPath, "new", newPath);
            
            updateFileListAfterFileMove(oldPath, newPath)
        })

        if (failedCount > 0) {
            if (failedCount === paths.length) {
                toast.error(`Failed to move file${letterS(paths.length)}.`)
            } else {
                toast.error(`Failed to move ${failedCount} file${letterS(failedCount)}`)
            }
        }
    } else if (status.serverDown) {
        handleError(
            `Server returned ${status} when moving file.`,
            `File move failed. The server is unavailable.`
        )
    } else {
        handleErrorResponse(
            json,
            `Failed to move file. (${status})`
        )
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
}

export async function getFileLastModifiedDate(path: string, silent: boolean = true): Promise<number | null> {
    const response = await safeFetch(`/api/v1/file/last-modified-date`, { 
        body: formData({ path: path })
    })
    const status = response.code
    const content = response.content

    if (status.ok) {
        const int = parseInt(content)
        return int
    } else if (status.serverDown) {
        handleError(
            `Server returned ${status} when checking file modification time.`,
            `Failed to check file modification time. The server is unavailable.`
        )
    } else if (status.notFound) {
        if (!silent) {
            handleError(`File not found when getting modification date`, `This file was not found.`)
        }
    } else {
        handleErrorResponse(
            response.json(),
            `Failed to check file modification time. (${status})`
        )
    }
    return null
}
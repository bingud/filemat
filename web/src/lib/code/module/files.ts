import type { FileMetadata, FileType } from "../auth/types";
import { formData, handleError, handleErrorResponse, handleException, parseJson, safeFetch } from "../util/codeUtil.svelte";


export type FileData = { meta: FileMetadata, entries: FileMetadata[] | null }

export async function getFileData(path: string, signal: AbortSignal): Promise<FileData | null> {
    const response = await safeFetch(`/api/v1/folder/file-or-folder-entries`, { body: formData({ path: path, signal: signal }) })
    if (response.failed) {
        handleException(`Failed to fetch folder entries`, `Failed to open folder.`, response.exception)
        return null
    }
    const status = response.code
    const json = response.json()

    if (status.serverDown) {
        handleError(`Server ${status} while getting folder entries`, `Failed to open folder. Server is unavailable.`)
        return null
    } else if (status.failed) {
        handleErrorResponse(json, `Failed to open folder.`)
        return null
    }

    return json
}


export async function streamFileContent(path: string, signal: AbortSignal): Promise<Blob | null> {
    const response = await safeFetch(`/api/v1/file/content`, { body: formData({ path: path, signal: signal }) }, true)
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
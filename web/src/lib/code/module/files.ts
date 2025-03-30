import type { FileMetadata, FileType } from "../auth/types";
import { formData, handleError, handleErrorResponse, handleException, safeFetch } from "../util/codeUtil.svelte";


export type FileData = { meta: FileMetadata, entries: FileMetadata[] | null }

export async function getFileData(path: string): Promise<FileData | null> {
    const response = await safeFetch(`/api/v1/folder/file-or-folder-entries`, { body: formData({ path: path }) })
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
import type { FileMetadata } from "../auth/types";
import { formData, handleError, handleErrorResponse, handleException, safeFetch } from "../util/codeUtil.svelte";


export async function getFolderEntries(path: string): Promise<FileMetadata | null> {
    const response = await safeFetch(`/api/v1/folder/list`, { body: formData({ path: path }) })
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

    return json as FileMetadata
}
import { appState } from "../stateObjects/appState.svelte"
import { handleErr, handleException, parseJson } from "../util/codeUtil.svelte"



/**
 * Fetches the list of folder paths that are marked as sensitive.
 */
export async function fetchSensitiveFolderList(): Promise<string[] | null> {
    try {
        appState.sensitiveFolders = null

        const response = await fetch(`/api/v1/setup/sensitive-folders`, { method: "GET", credentials: "same-origin" })
        const status = response.status
        const text = await response.text()
        const json = parseJson(text)
        
        if (status === 200) {
            appState.sensitiveFolders = json as string[]
            return json
        } else {
            appState.sensitiveFolders = null
            handleErr({
                description: "Failed to load list of sensitive folders.",
                notification: json?.message || "Failed to load list of sensitive folders."
            })
            return null
        }
    } catch (e) {
        handleErr({
            description: "Failed to fetch sensitive folder list.",
            notification: "An error occurred while loading the list of sensitive folders."
        })
        appState.sensitiveFolders = null
        return null
    }
}
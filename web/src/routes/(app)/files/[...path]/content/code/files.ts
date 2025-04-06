import { streamFileContent } from "$lib/code/module/files"
import { filesState } from "./filesState.svelte"



export async function loadFileContent(filePath: string) {
    const blob = await streamFileContent(filePath, filesState.abortController.signal)
    if (filesState.lastFilePathLoaded !== filePath) return    
    if (!blob) return
    filesState.data.content = blob
}
import { page } from "$app/state"
import { isBlank } from "$lib/code/util/codeUtil.svelte"


class FilesState {
    /**
     * The current file path opened
     */
    path: string = $derived.by(() => {
        let path = page.params.path
        if (isBlank(path)) return "/"
        return path
    })

    /**
     * File path segments of the current path
     */
    segments = $derived(this.path.split("/"))
    
}



export let filesState: FilesState

export function createFilesState() {
    filesState = new FilesState()
}

export function destroyFilesState() {
    filesState = undefined!
}
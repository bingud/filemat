import { handleException } from "../util/codeUtil.svelte"

class AppState {
    isSetup: boolean | null = $state(null)
    sensitiveFolders: string[] | null = $state(null)
}

export const appState = new AppState()





class AppState {
    /**
     * Indicates whether application has been set up.
     */
    isSetup: boolean | null = $state(null)
    /**
     * List of folder paths that are marked as sensitive.
     */
    sensitiveFolders: string[] | null = $state(null)
    
    /**
     * Which path the app was opened on
     */
    firstPath: string | null = $state(null)
}

/**
 * Stores general app state and settings.
 */
export const appState = new AppState()



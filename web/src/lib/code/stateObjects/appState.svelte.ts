

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
     * Indicates whether the first page the user entered is stil open 
     * or whether the user navigated
     */
    isInitialPageOpen = $state(true)
}

/**
 * Stores general app state and settings.
 */
export const appState = new AppState()



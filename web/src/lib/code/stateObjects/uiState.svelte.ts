

class UiState {
    /**
     * Indicates whether mobile navigation menu is open
     */
    menuOpen: boolean = $state(false)

    /**
     * Indicates whether app is in dark mode
     */
    isDark: boolean = $state(true)

    /**
     * Indicates whether screen size is desktop
     */
    isDesktop: boolean = $state(false)

    /**
     * Binds methods to this context
     */
    constructor() {
        this.reset = this.reset.bind(this)
        this.onResize = this.onResize.bind(this)
    }

    /**
     * Resets UI state
     */
    reset() {
        this.menuOpen = false
    }

    /**
     * Handles screen resizing
     */
    onResize() {
        if (this.isDesktop) {
            this.menuOpen = false
        }
    }
}

/**
 * Stores UI state.
 */
export const uiState = new UiState()



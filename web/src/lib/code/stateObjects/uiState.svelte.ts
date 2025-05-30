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

    screenWidth: number = $state(0)

    /**
     * Holds settings UI state
     */
    settings = $state({
        defaultSection: "preferences" as "preferences",
        section: "preferences" as SettingSectionId,
        title: "preferences",
        menuOpen: false as boolean
    })

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
        this.settings.section = this.settings.defaultSection
        this.settings.title = this.settings.section
        this.settings.menuOpen = false
    }

    /**
     * Handles screen resizing
     */
    onResize() {
        if (this.isDesktop) {
            this.menuOpen = false
            this.settings.menuOpen = false
        }
    }
}

/**
 * Stores UI state.
 */
export const uiState = new UiState()

export type SettingSectionId = "preferences" | "users" | "userinfo" | "roles" | "settings"
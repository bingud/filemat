import { goto } from "$app/navigation"
import type { FileMetadata, FullFileMetadata } from "$lib/code/auth/types"
import { appState } from "$lib/code/stateObjects/appState.svelte"
import { filesState } from "$lib/code/stateObjects/filesState.svelte"

export type FileContextMenuProps = {
    option_rename: (entry: FileMetadata) => any
    option_move: (entry: FileMetadata) => any
    option_delete: (entry: FileMetadata) => any
    option_details: (entry: FileMetadata) => any
    option_save: (entry: FileMetadata, action: "save" | "unsave") => any
    closeFileContextMenuPopover: () => any
}

export type FileEntryHandlerProps = {
    event_dragStart: (e: DragEvent, entry: FullFileMetadata) => void
    event_dragOver: (e: DragEvent, entry: FullFileMetadata) => void
    event_dragLeave: (e: DragEvent, entry: FullFileMetadata) => void
    event_drop: (e: DragEvent, entry: FullFileMetadata) => void
    event_dragEnd: (e: DragEvent, entry: FullFileMetadata) => void
    entryOnClick: (e: MouseEvent, entry: FullFileMetadata) => void
    onClickSelectCheckbox: (path: string) => void
}

export type FileListProps = FileContextMenuProps & FileEntryHandlerProps & {
    sortedEntries: typeof filesState.data.sortedEntries
}

export type FileEntryProps = FileEntryHandlerProps & {
    entry: FullFileMetadata
    entryOnContextMenu: (e: MouseEvent, entry: FullFileMetadata) => void
    entryMenuOnClick: (button: HTMLButtonElement, entry: FullFileMetadata) => void
}

export function changeSortingMode(mode: typeof filesState.sortingMode) {
    const current = filesState.sortingMode

    if (mode === current) {
        const currentDirection = filesState.sortingDirection
        if (currentDirection === "asc") filesState.sortingDirection = "desc"
        if (currentDirection === "desc") filesState.sortingDirection = "asc"
    } else {
        filesState.sortingDirection = "asc"
        filesState.sortingMode = mode
    }
}

export class ImageLoadQueue {
    // Target element for IntersectionObserver root (defaults to viewport if null)
    private scrollContainer: HTMLElement | null = null
    
    // Observers for detecting visibility
    private viewportObserver: IntersectionObserver | null = null
    private nearbyObserver: IntersectionObserver | null = null
    
    // Queue management sets
    private pending: Set<HTMLImageElement> = new Set() // Registered but not loaded
    private highPriority: Set<HTMLImageElement> = new Set() // Currently in viewport
    private lowPriority: Set<HTMLImageElement> = new Set() // Close to viewport
    private loading: Set<HTMLImageElement> = new Set() // Currently fetching
    
    private processing = false
    private concurrency = 2 // Max simultaneous downloads
    private loadDistance = 500 // Pixel margin for "nearby" detection


    // Sets the scroll container and resets observers to use the new root
    setScrollContainer(container: HTMLElement | null) {
        this.scrollContainer = container
        this.reinitObservers()
    }

    private initObservers() {
        const options = {
            root: this.scrollContainer
        }

        // Detects images actually inside the visible area
        this.viewportObserver = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    const img = entry.target as HTMLImageElement
                    if (entry.isIntersecting) {
                        this.highPriority.add(img)
                        this.lowPriority.delete(img)
                    } else {
                        this.highPriority.delete(img)
                    }
                }
                this.process()
            },
            { ...options, rootMargin: "0px" }
        )

        // Detects images approaching the viewport (prefetching)
        this.nearbyObserver = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    const img = entry.target as HTMLImageElement
                    // Only add to low priority if not already high priority
                    if (entry.isIntersecting && !this.highPriority.has(img)) {
                        this.lowPriority.add(img)
                    } else if (!entry.isIntersecting) {
                        this.lowPriority.delete(img)
                    }
                }
                this.process()
            },
            { ...options, rootMargin: `${this.loadDistance}px` }
        )
    }

    // Restarts observers (e.g., when container changes) and re-observes pending images
    private reinitObservers() {
        this.viewportObserver?.disconnect()
        this.nearbyObserver?.disconnect()
        this.highPriority.clear()
        this.lowPriority.clear()
        
        this.initObservers()
        
        for (const img of this.pending) {
            this.viewportObserver?.observe(img)
            this.nearbyObserver?.observe(img)
        }
    }

    // Registers an image to be managed by the queue
    register(img: HTMLImageElement) {
        if (!img.hasAttribute('data-src')) return

        if (!this.viewportObserver) {
            this.initObservers()
        }

        this.pending.add(img)
        this.viewportObserver!.observe(img)
        this.nearbyObserver!.observe(img)
        
        // Trigger process in case we are in loadAllPreviews mode or slots are open
        this.process() 
    }

    // Cleans up an image from all queues and observers
    unregister(img: HTMLImageElement) {
        this.pending.delete(img)
        this.highPriority.delete(img)
        this.lowPriority.delete(img)
        this.viewportObserver?.unobserve(img)
        this.nearbyObserver?.unobserve(img)
    }

    // Debounced trigger for the processing loop
    private process() {
        if (this.processing) return
        this.processing = true

        queueMicrotask(() => {
            try {
                this.runProcessLoop()
            } finally {
                this.processing = false
            }
        })
    }

    // Finds candidates to load based on priority and concurrency limits
    private runProcessLoop() {
        while (this.loading.size < this.concurrency) {
            const capacity = this.concurrency - this.loading.size
            if (capacity <= 0) break

            let candidate: HTMLImageElement | null = null

            if (appState.settings.loadAllPreviews) {
                // If loading all, ignore priority sets and take the next pending image
                // Set iteration follows insertion order
                for (const img of this.pending) {
                    if (!this.loading.has(img)) {
                        candidate = img
                        break
                    }
                }
            } else {
                // Priority mode: Check visible images first
                for (const img of this.highPriority) {
                    if (this.pending.has(img) && !this.loading.has(img)) {
                        candidate = img
                        break
                    }
                }

                // If no visible images, check nearby images
                if (!candidate) {
                    for (const img of this.lowPriority) {
                        if (this.pending.has(img) && !this.loading.has(img)) {
                            candidate = img
                            break
                        }
                    }
                }
            }

            if (!candidate) break
            
            this.startLoad(candidate)
        }
    }

    // Initiates the actual image load
    private startLoad(img: HTMLImageElement) {
        if (this.loading.has(img)) return

        const src = img.getAttribute('data-src')
        if (!src) {
            this.unregister(img)
            return
        }

        this.pending.delete(img)
        this.loading.add(img)
        
        // Stop observing once loading starts
        this.viewportObserver?.unobserve(img)
        this.nearbyObserver?.unobserve(img)
        this.highPriority.delete(img)
        this.lowPriority.delete(img)

        const cleanup = () => {
            img.onload = null
            img.onerror = null
            this.loading.delete(img)
            this.process() // Trigger next item in queue
        }

        img.onload = cleanup
        img.onerror = () => {
            console.error('Image load failed:', src)
            cleanup()
        }
        
        img.src = src
    }

    // Svelte Action to attach to <img> elements
    getAction() {
        return (node: HTMLImageElement) => {
            let lastSrc = node.getAttribute('data-src')
            this.register(node)

            return {
                update: () => {
                    const currentSrc = node.getAttribute('data-src')
                    if (currentSrc !== lastSrc) {
                        lastSrc = currentSrc
                        this.unregister(node)
                        node.removeAttribute('src') // Reset src so it can be lazy loaded again
                        this.register(node)
                    }
                },
                destroy: () => {
                    this.unregister(node)
                }
            }
        }
    }

    // Cleanup method for component teardown
    destroy() {
        this.viewportObserver?.disconnect()
        this.nearbyObserver?.disconnect()
        this.viewportObserver = null
        this.nearbyObserver = null
        this.pending.clear()
        this.highPriority.clear()
        this.lowPriority.clear()
        this.loading.clear()
        this.processing = false
    }
}


export function selectSiblingFile(direction: 'previous' | 'next', onlyFiles: boolean = false, openFile: boolean = false) {
    const entries = (filesState.isSearchOpen ? filesState.search.sortedEntries : filesState.data.sortedEntries)!
    if (entries.length < 1) return

    const currentPath = filesState.selectedEntries.singlePath

    let currentIndex = -1
    if (currentPath) {
        currentIndex = entries.findIndex(e => e.path === currentPath)
    }
    
    let newIndex: number = currentIndex
    let newEntry: FullFileMetadata
    
    if (direction === 'previous') {
        function decrease() {
            newIndex = newIndex === -1 
                ? entries.length - 1 
                : (newIndex - 1 + entries.length) % entries.length
        }
        decrease()
        newEntry = entries[newIndex]
        let start = newIndex
        let started = false

        if (onlyFiles) {
            while (newEntry.fileType !== "FILE" && newEntry.fileType !== "FILE_LINK") {
                if (newIndex === start && started) break
                started = true
                decrease()
                newEntry = entries[newIndex]
            }
        }
    } else {
        function increase() {
            newIndex = newIndex === -1 
                ? 0 
                : (newIndex + 1) % entries.length
        }
        increase()
        newEntry = entries[newIndex]
        let start = newIndex
        let started = false

        if (onlyFiles) {
            while (newEntry.fileType !== "FILE" && newEntry.fileType !== "FILE_LINK") {
                if (newIndex === start && started) break
                started = true
                increase()
                newEntry = entries[newIndex]
            }
        }
    }
    
    filesState.selectedEntries.selectedPositions.set(newEntry.path, true)
    filesState.selectedEntries.setSelected(newEntry.path)
    
    if (openFile) {
        openEntry(newEntry.path)
    }
    
    scrollSelectedEntryIntoView(newEntry.path)
}

export function openEntry(path: string) {
    if (filesState.metaLoading) return
    goto(`${filesState.meta.pagePath}${encodeURI(path)}`)
}

export function scrollSelectedEntryIntoView(path: string | null = null) {
    setTimeout(() => {
        const targetPath = filesState.selectedEntries.singlePath || path
        if (targetPath) {
            const selector = `[data-entry-path="${targetPath.replace(/"/g, '\\"')}"]`
            const element = document.querySelector(selector)
            if (element) {
                element.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
            }
        }
    }, 10)
}
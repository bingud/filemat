import { goto } from "$app/navigation"
import type { FileMetadata, FullFileMetadata } from "$lib/code/auth/types"
import { appState } from "$lib/code/stateObjects/appState.svelte"
import { filesState } from "$lib/code/stateObjects/filesState.svelte"

export type FileContextMenuProps = {
    option_rename: (entry: FileMetadata) => any
    option_move: (entry: FileMetadata) => any
    option_copy: (entry: FileMetadata) => any
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
    
    // Map to link file paths to image elements for data-driven sorting
    private imageMap = new Map<string, HTMLImageElement>()
    private nodeToPath = new WeakMap<HTMLImageElement, string>()

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
    register(img: HTMLImageElement, path?: string) {
        if (!img.hasAttribute('data-src')) return

        if (!this.viewportObserver) {
            this.initObservers()
        }

        this.pending.add(img)
        if (path) {
            this.imageMap.set(path, img)
            this.nodeToPath.set(img, path)
        }

        this.viewportObserver!.observe(img)
        this.nearbyObserver!.observe(img)
        
        // Trigger process in case we are in loadAllPreviews mode or slots are open
        this.process() 
    }

    // Sorts the pending queue to match the given list of file paths
    // This is called by an effect whenever the sort order changes
    reorderQueue(sortedPaths: string[]) {
        const newPending = new Set<HTMLImageElement>()

        // 1. Add images in the new sort order
        for (const path of sortedPaths) {
            const img = this.imageMap.get(path)
            if (img && this.pending.has(img)) {
                newPending.add(img)
                this.pending.delete(img)
            }
        }

        // 2. Add any remaining images (e.g. not in current list or no path)
        for (const img of this.pending) {
            newPending.add(img)
        }

        this.pending = newPending
        // No need to trigger process() here as the queue size didn't change
    }

    // Cleans up an image from all queues and observers
    unregister(img: HTMLImageElement) {
        this.pending.delete(img)
        this.highPriority.delete(img)
        this.lowPriority.delete(img)
        this.viewportObserver?.unobserve(img)
        this.nearbyObserver?.unobserve(img)
        
        const path = this.nodeToPath.get(img)
        if (path) {
            this.imageMap.delete(path)
            this.nodeToPath.delete(img)
        }
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
        // Helper to find best candidate from a set based on screen position (Top to Bottom)
        const getSortedCandidate = (set: Set<HTMLImageElement>) => {
            const candidates: HTMLImageElement[] = []
            for (const img of set) {
                if (this.pending.has(img) && !this.loading.has(img)) {
                    candidates.push(img)
                }
            }

            if (candidates.length === 0) return null
            if (candidates.length === 1) return candidates[0]

            // Sort by vertical position
            return candidates.sort((a, b) => {
                return a.getBoundingClientRect().top - b.getBoundingClientRect().top
            })[0]
        }

        while (this.loading.size < this.concurrency) {
            const capacity = this.concurrency - this.loading.size
            if (capacity <= 0) break

            let candidate: HTMLImageElement | null = null

            // 1. ALWAYS check High Priority (Visible) first
            candidate = getSortedCandidate(this.highPriority)

            // 2. Check Low Priority (Nearby)
            if (!candidate) {
                candidate = getSortedCandidate(this.lowPriority)
            }

            // 3. Fallback: Load All (Remaining Pending)
            if (!candidate && appState.settings.loadAllPreviews) {
                // Pending is already sorted by reorderQueue, so just take the first one
                for (const img of this.pending) {
                    if (!this.loading.has(img)) {
                        candidate = img
                        break
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
        return (node: HTMLImageElement, path?: string) => {
            let lastSrc = node.getAttribute('data-src')
            this.register(node, path)

            return {
                update: (newPath?: string) => {
                    const currentSrc = node.getAttribute('data-src')
                    if (currentSrc !== lastSrc) {
                        lastSrc = currentSrc
                        this.unregister(node)
                        node.removeAttribute('src') // Reset src so it can be lazy loaded again
                        this.register(node, newPath)
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
        this.imageMap.clear()
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
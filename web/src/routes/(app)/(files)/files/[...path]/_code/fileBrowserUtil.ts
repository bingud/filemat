import { goto } from "$app/navigation"
import type { FileMetadata, FullFileMetadata } from "$lib/code/auth/types"
import type { GridPreviewSize, RowPreviewSize } from "$lib/code/config/values"
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
    
    // Single unified observer for detecting visibility
    private observer: IntersectionObserver | null = null
    
    // Queue management sets
    private pending: Set<HTMLImageElement> = new Set() // Registered but not loaded
    private highPriority: Set<HTMLImageElement> = new Set() // Currently in viewport
    private lowPriority: Set<HTMLImageElement> = new Set() // Close to viewport
    private loading: Set<HTMLImageElement> = new Set() // Currently fetching
    
    // Map to link file paths to image elements for data-driven sorting
    private imageMap = new Map<string, HTMLImageElement>()
    private nodeToPath = new WeakMap<HTMLImageElement, string>()

    // RAF-based debouncing (replaces queueMicrotask for frame-aligned processing)
    private rafId: number | null = null
    
    private concurrency = 2 // Max simultaneous downloads
    private loadDistance = 500 // Pixel margin for "nearby" detection
    
    // Threshold to distinguish "in viewport" from "nearby" based on intersection ratio
    // Elements with ratio >= this value are considered high priority (visible)
    private visibilityThreshold = 0.01


    // Sets the scroll container and resets observers to use the new root
    setScrollContainer(container: HTMLElement | null) {
        this.scrollContainer = container
        this.reinitObserver()
    }

    private initObserver() {
        // Single observer with extended rootMargin and multiple thresholds
        // This replaces the previous two-observer approach
        this.observer = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    const img = entry.target as HTMLImageElement
                    
                    if (entry.isIntersecting) {
                        // Determine priority based on intersection ratio
                        // High ratio = actually visible in viewport
                        // Low ratio = just entering the extended margin
                        if (entry.intersectionRatio >= this.visibilityThreshold) {
                            this.highPriority.add(img)
                            this.lowPriority.delete(img)
                        } else {
                            // Only add to low priority if not already high priority
                            if (!this.highPriority.has(img)) {
                                this.lowPriority.add(img)
                            }
                        }
                    } else {
                        // Element left the extended margin entirely
                        this.highPriority.delete(img)
                        this.lowPriority.delete(img)
                    }
                }
                this.scheduleProcess()
            },
            { 
                root: this.scrollContainer,
                rootMargin: `${this.loadDistance}px`,
                // Multiple thresholds: 0 for entering margin, visibilityThreshold for actual viewport
                threshold: [0, this.visibilityThreshold, 0.5, 1.0]
            }
        )
    }

    // Restarts observer (e.g., when container changes) and re-observes pending images
    private reinitObserver() {
        this.observer?.disconnect()
        this.highPriority.clear()
        this.lowPriority.clear()
        
        this.initObserver()
        
        for (const img of this.pending) {
            this.observer?.observe(img)
        }
    }

    // Registers an image to be managed by the queue
    register(img: HTMLImageElement, path?: string) {
        if (!img.hasAttribute(`data-src`)) return

        if (!this.observer) {
            this.initObserver()
        }

        this.pending.add(img)
        if (path) {
            this.imageMap.set(path, img)
            this.nodeToPath.set(img, path)
        }

        this.observer!.observe(img)
        
        // Trigger process in case we are in loadAllPreviews mode or slots are open
        this.scheduleProcess() 
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
        this.observer?.unobserve(img)
        
        const path = this.nodeToPath.get(img)
        if (path) {
            this.imageMap.delete(path)
            this.nodeToPath.delete(img)
        }
    }

    // RAF-based debounced trigger for the processing loop
    // Using requestAnimationFrame instead of queueMicrotask prevents forced reflows
    // by ensuring processing happens at the optimal time in the frame lifecycle
    private scheduleProcess() {
        if (this.rafId !== null) return
        
        this.rafId = requestAnimationFrame(() => {
            this.rafId = null
            this.runProcessLoop()
        })
    }

    // Gets the next candidate to load, respecting the sorted order from reorderQueue
    // Iterates through `pending` (which maintains visual/sorted order) and finds
    // the first image that matches the desired priority level
    // This avoids getBoundingClientRect calls while still loading top-to-bottom
    private getNextCandidateFromPending(priorityFilter?: Set<HTMLImageElement>): HTMLImageElement | null {
        for (const img of this.pending) {
            if (this.loading.has(img)) continue
            
            // If a priority filter is specified, only return images in that set
            if (priorityFilter && !priorityFilter.has(img)) continue
            
            return img
        }
        return null
    }

    // Finds candidates to load based on priority and concurrency limits
    // Uses pending set iteration order (maintained by reorderQueue) to ensure
    // images load sequentially from top to bottom in visual order
    private runProcessLoop() {
        while (this.loading.size < this.concurrency) {
            let candidate: HTMLImageElement | null = null

            // 1. ALWAYS check High Priority (Visible) first - but in sorted order
            candidate = this.getNextCandidateFromPending(this.highPriority)

            // 2. Check Low Priority (Nearby) - but in sorted order
            if (!candidate) {
                candidate = this.getNextCandidateFromPending(this.lowPriority)
            }

            // 3. Fallback: Load All (Remaining Pending) - already in sorted order
            if (!candidate && appState.settings.loadAllPreviews) {
                candidate = this.getNextCandidateFromPending()
            }

            if (!candidate) break
            
            this.startLoad(candidate)
        }
    }

    // Initiates the actual image load
    private startLoad(img: HTMLImageElement) {
        if (this.loading.has(img)) return

        const src = img.getAttribute(`data-src`)
        if (!src) {
            this.unregister(img)
            return
        }

        this.pending.delete(img)
        this.loading.add(img)
        
        // Stop observing once loading starts
        this.observer?.unobserve(img)
        this.highPriority.delete(img)
        this.lowPriority.delete(img)

        const cleanup = () => {
            img.onload = null
            img.onerror = null
            this.loading.delete(img)
            this.scheduleProcess() // Trigger next item in queue
        }

        img.onload = cleanup
        img.onerror = () => {
            console.error(`Image load failed:`, src)
            cleanup()
        }
        
        img.src = src
    }

    // Svelte Action to attach to <img> elements
    getAction() {
        return (node: HTMLImageElement, path?: string) => {
            let lastSrc = node.getAttribute(`data-src`)
            this.register(node, path)

            return {
                update: (newPath?: string) => {
                    const currentSrc = node.getAttribute(`data-src`)
                    if (currentSrc !== lastSrc) {
                        lastSrc = currentSrc
                        this.unregister(node)
                        node.removeAttribute(`src`) // Reset src so it can be lazy loaded again
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
        // Cancel any pending RAF to prevent orphaned callbacks
        if (this.rafId !== null) {
            cancelAnimationFrame(this.rafId)
            this.rafId = null
        }
        
        this.observer?.disconnect()
        this.observer = null
        this.pending.clear()
        this.highPriority.clear()
        this.lowPriority.clear()
        this.loading.clear()
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
            const element = document.querySelector(selector) as HTMLAnchorElement
            if (element) {
                element.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
                element.focus()
            }
        }
    }, 10)
}
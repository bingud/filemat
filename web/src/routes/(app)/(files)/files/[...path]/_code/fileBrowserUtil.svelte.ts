import { goto } from "$app/navigation"
import type { FileMetadata, FullFileMetadata } from "$lib/code/auth/types"
import type { GridPreviewSize, RowPreviewSize } from "$lib/code/config/values"
import { getFileCategoryFromFilename } from "$lib/code/data/files"
import { appState } from "$lib/code/stateObjects/appState.svelte"
import { filesState } from "$lib/code/stateObjects/filesState.svelte"
import { encodeUrlFilePath, filenameFromPath } from "$lib/code/util/codeUtil.svelte"
import { SvelteSet } from "svelte/reactivity"

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

export class VisibilityManager {
    private scrollContainer: HTMLElement | null = null
    private observer: IntersectionObserver | null = null
    /** A second observer with no margin, used to detect images that are truly in the visible viewport */
    private visibleImageObserver: IntersectionObserver | null = null
    
    private pending: Set<HTMLImageElement> = new Set()
    private highPriority: Set<HTMLImageElement> = new Set()
    private lowPriority: Set<HTMLImageElement> = new Set()
    private loading: Set<HTMLImageElement> = new Set()
    
    private imageMap = new Map<string, HTMLImageElement>()
    private nodeToPath = new WeakMap<HTMLImageElement, string>()
    /** Tracks headless (offscreen) images created for loadAllPreviews preloading */
    private headlessImages = new Set<HTMLImageElement>()

    private rafId: number | null = null
    
    /** Tracks entry paths that have been observed at least once, to distinguish first mount from re-registration */
    private knownEntryPaths = new Set<string>()

    private concurrency = 2
    private renderDistance = 500
    private visibilityThreshold = 0.01

    visibleEntryPaths = new SvelteSet<string>()
    private entryElements = new Map<HTMLElement, string>()


    setScrollContainer(container: HTMLElement | null) {
        this.scrollContainer = container
        this.reinitObserver()
    }

    private initObserver() {
        this.observer = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    if (entry.target instanceof HTMLImageElement) {
                        this.handleImageIntersect(entry)
                    } else {
                        this.handleEntryIntersect(entry)
                    }
                }
                this.scheduleImageLoad()
            },
            { 
                root: this.scrollContainer,
                rootMargin: `${this.renderDistance}px`,
                threshold: [0, this.visibilityThreshold, 0.5, 1.0]
            }
        )

        // A zero-margin observer that only fires for images actually inside the visible viewport.
        // Used to promote images to highPriority without relying on distance calculations.
        this.visibleImageObserver = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    const img = entry.target as HTMLImageElement
                    if (entry.isIntersecting) {
                        // Truly visible: promote to high priority regardless of margin observer state
                        if (this.pending.has(img) || this.lowPriority.has(img)) {
                            this.highPriority.add(img)
                            this.lowPriority.delete(img)
                        }
                    } else {
                        // Left the real viewport: demote back to lowPriority if still in margin observer range
                        if (this.highPriority.has(img) && !this.loading.has(img)) {
                            this.highPriority.delete(img)
                            if (this.pending.has(img)) {
                                this.lowPriority.add(img)
                            }
                        }
                    }
                }
                this.scheduleImageLoad()
            },
            {
                root: this.scrollContainer,
                rootMargin: `0px`,
                threshold: 0,
            }
        )
    }

    private handleEntryIntersect(entry: IntersectionObserverEntry) {
        const element = entry.target as HTMLElement
        const path = this.entryElements.get(element)
        if (!path) return
        
        if (entry.isIntersecting) {
            this.visibleEntryPaths.add(path)
        } else {
            this.visibleEntryPaths.delete(path)
        }
    }

    private handleImageIntersect(entry: IntersectionObserverEntry) {
        const img = entry.target as HTMLImageElement
        
        if (entry.isIntersecting) {
            // The margin observer fires for anything within renderDistance px.
            // The visibleImageObserver (zero margin) will promote truly visible images
            // to highPriority. Until then, place new images in lowPriority.
            if (!this.highPriority.has(img)) {
                this.lowPriority.add(img)
            }
        } else {
            this.highPriority.delete(img)
            this.lowPriority.delete(img)
        }
    }

    private reinitObserver() {
        this.observer?.disconnect()
        this.visibleImageObserver?.disconnect()
        this.highPriority.clear()
        this.lowPriority.clear()
        this.visibleEntryPaths.clear()
        this.knownEntryPaths.clear()
        
        this.initObserver()
        
        for (const img of this.pending) {
            this.observer?.observe(img)
            this.visibleImageObserver?.observe(img)
        }
        for (const [element] of this.entryElements) {
            this.observer?.observe(element)
        }
    }

    /**
     * Returns true if the element's bounding rect overlaps the visible viewport of the scroll container
     * (with the configured renderDistance margin). Used to eagerly mark entries visible on first mount
     * without waiting for the async IntersectionObserver callback, but ONLY for entries that are
     * actually near the viewport — preventing all entries from rendering fully on initial load.
     */
    private isNearViewport(node: HTMLElement): boolean {
        const rect = node.getBoundingClientRect()
        if (this.scrollContainer) {
            const containerRect = this.scrollContainer.getBoundingClientRect()
            return (
                rect.bottom >= containerRect.top - this.renderDistance &&
                rect.top <= containerRect.bottom + this.renderDistance
            )
        }
        return (
            rect.bottom >= -this.renderDistance &&
            rect.top <= window.innerHeight + this.renderDistance
        )
    }

    observeEntry = (node: HTMLElement, path: string) => {
        if (!this.observer) {
            this.initObserver()
        }
        
        this.entryElements.set(node, path)
        this.knownEntryPaths.add(path)

        // Only eagerly mark visible if the element is actually near the viewport.
        // This prevents all N entries from being fully rendered on the initial mount.
        if (this.isNearViewport(node)) {
            this.visibleEntryPaths.add(path)
        }

        this.observer!.observe(node)
        
        return {
            update: (newPath: string) => {
                const oldPath = this.entryElements.get(node)
                if (oldPath && oldPath !== newPath) {
                    this.visibleEntryPaths.delete(oldPath)
                }
                this.entryElements.set(node, newPath)
                this.visibleEntryPaths.add(newPath)
            },
            destroy: () => {
                this.observer?.unobserve(node)
                this.entryElements.delete(node)
            }
        }
    }

    /**
     * Svelte action for skeleton/placeholder elements.
     * On first mount (path never seen before), eagerly marks as visible only if the element
     * is actually near the viewport — avoiding full renders for all off-screen entries.
     * On re-registration (path was visible, then scrolled away), does NOT eagerly add,
     * allowing the IntersectionObserver to determine visibility naturally and preventing infinite loops.
     */
    observeSkeleton = (node: HTMLElement, path: string) => {
        if (!this.observer) {
            this.initObserver()
        }

        this.entryElements.set(node, path)

        // First time this path is ever observed: eagerly mark visible only if near viewport.
        // Re-registration after element swap: let IntersectionObserver decide.
        if (!this.knownEntryPaths.has(path)) {
            this.knownEntryPaths.add(path)
            if (this.isNearViewport(node)) {
                this.visibleEntryPaths.add(path)
            }
        }

        this.observer!.observe(node)

        return {
            update: (newPath: string) => {
                const oldPath = this.entryElements.get(node)
                if (oldPath && oldPath !== newPath) {
                    this.visibleEntryPaths.delete(oldPath)
                }
                this.entryElements.set(node, newPath)
                if (!this.knownEntryPaths.has(newPath)) {
                    this.knownEntryPaths.add(newPath)
                    if (this.isNearViewport(node)) {
                        this.visibleEntryPaths.add(newPath)
                    }
                }
            },
            destroy: () => {
                this.observer?.unobserve(node)
                this.entryElements.delete(node)
            }
        }
    }

    register(img: HTMLImageElement, path?: string) {
        if (!img.hasAttribute(`data-src`)) return

        if (!this.observer) {
            this.initObserver()
        }

        // If there's an existing headless image for this path, clean it up
        if (path) {
            const existing = this.imageMap.get(path)
            if (existing && existing !== img && this.headlessImages.has(existing)) {
                this.pending.delete(existing)
                this.headlessImages.delete(existing)
                this.nodeToPath.delete(existing)
            }
            this.imageMap.set(path, img)
            this.nodeToPath.set(img, path)
        }

        this.pending.add(img)

        this.observer!.observe(img)
        this.visibleImageObserver?.observe(img)
        this.scheduleImageLoad() 
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

    /**
     * Pre-registers headless (offscreen) images for all image/video entries so that
     * loadAllPreviews can fetch them even when their grid entries are in skeleton state.
     * The images are added to the pending queue without being observed by IntersectionObserver.
     * When an entry later scrolls into view and mounts a real FileThumbnail <img>,
     * the headless image is replaced and the browser serves the data from the service worker cache.
     */
    preloadAllPreviews(entries: FullFileMetadata[], pixelSize: number) {
        const shareTokenParam = filesState.getIsShared() ? `&shareToken=${filesState.meta.shareToken}` : ``

        let added = false
        for (const entry of entries) {
            // Skip entries that already have a registered image (real or headless)
            if (this.imageMap.has(entry.path)) continue

            const format = getFileCategoryFromFilename(entry.filename || filenameFromPath(entry.path))
            if (format !== `image` && format !== `video`) continue

            const endpoint = format === `image` ? `image-thumbnail` : `video-preview`
            const src = `/api/v1/file/${endpoint}?size=${pixelSize}&path=${encodeUrlFilePath(entry.path)}&modified=${entry.modifiedDate}${shareTokenParam}`

            const img = document.createElement(`img`)
            img.setAttribute(`data-src`, src)

            this.headlessImages.add(img)
            this.pending.add(img)
            this.imageMap.set(entry.path, img)
            this.nodeToPath.set(img, entry.path)
            added = true
        }

        if (added) {
            this.scheduleImageLoad()
        }
    }

    // Cleans up an image from all queues and observers.
    // When loadAllPreviews is enabled and a real (non-headless) image is unregistered
    // (e.g. entry went back to skeleton), a headless replacement is created so the
    // thumbnail still gets fetched and cached by the service worker.
    unregister(img: HTMLImageElement) {
        const wasHeadless = this.headlessImages.has(img)
        const wasInPending = this.pending.has(img)
        const dataSrc = img.getAttribute(`data-src`)

        this.pending.delete(img)
        this.highPriority.delete(img)
        this.lowPriority.delete(img)
        this.headlessImages.delete(img)
        this.observer?.unobserve(img)
        this.visibleImageObserver?.unobserve(img)
        
        const path = this.nodeToPath.get(img)
        if (path) {
            this.imageMap.delete(path)
            this.nodeToPath.delete(img)

            // If a real image is being unregistered (entry went to skeleton) and it was
            // still pending (never started loading), create a headless replacement so that
            // loadAllPreviews can still fetch and cache the thumbnail via the service worker.
            if (!wasHeadless && wasInPending && dataSrc && appState.settings.loadAllPreviews) {
                const headless = document.createElement(`img`)
                headless.setAttribute(`data-src`, dataSrc)
                this.headlessImages.add(headless)
                this.pending.add(headless)
                this.imageMap.set(path, headless)
                this.nodeToPath.set(headless, path)
                this.scheduleImageLoad()
            }
        }
    }

    private scheduleImageLoad() {
        if (this.rafId !== null) return
        
        this.rafId = setTimeout(() => {
            this.rafId = null
            this.runProcessLoop()
        }, 0) as unknown as number
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
        this.visibleImageObserver?.unobserve(img)
        this.highPriority.delete(img)
        this.lowPriority.delete(img)

        const cleanup = () => {
            img.onload = null
            img.onerror = null
            this.loading.delete(img)
            this.scheduleImageLoad()
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
        if (this.rafId !== null) {
            clearTimeout(this.rafId)
            this.rafId = null
        }

        this.observer?.disconnect()
        this.observer = null
        this.visibleImageObserver?.disconnect()
        this.visibleImageObserver = null
        this.pending.clear()
        this.highPriority.clear()
        this.lowPriority.clear()
        this.loading.clear()
        this.imageMap.clear()
        this.headlessImages.clear()
        this.knownEntryPaths.clear()
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
    
    filesState.selectedEntries.setSelected(newEntry.path)
    
    if (openFile) {
        openEntry(newEntry.path)
    }
    
    scrollSelectedEntryIntoView(newEntry.path)
}

export function openEntry(path: string) {
    if (filesState.metaLoading) return
    goto(getFilePagePath(path, filesState.meta.pagePath))
}

export function getFilePagePath(path: string, pagePath: string) {
    const slash = path.startsWith('/') ? '' : '/'
    return `${pagePath}${slash}${encodeUrlFilePath(path)}`
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
import { goto } from "$app/navigation"
import type { FileMetadata, FullFileMetadata } from "$lib/code/auth/types"
import { getFileCategoryFromFilename } from "$lib/code/data/files"
import { appState } from "$lib/code/stateObjects/appState.svelte"
import { filesState } from "$lib/code/stateObjects/filesState.svelte"
import { encodeUrlFilePath, filenameFromPath, filePageUrl } from "$lib/code/util/codeUtil.svelte"
import { config, fullscreenPixelSizes, fullscreenPreviewSize, maxCachedPixelSize, noSwCacheParam, thumbnailCacheQuery } from "$lib/code/config/values"
import { contentCrossOrigin, joinContentUrl } from "$lib/code/util/contentUrl"
import { SvelteSet } from "svelte/reactivity"

function applyPreviewCors(img: HTMLImageElement) {
    const cors = contentCrossOrigin()
    if (cors) {
        img.crossOrigin = cors
    } else {
        img.removeAttribute(`crossorigin`)
    }
}

/** Queue key for the placeholder image of `path`, so it is ordered right beside the real thumbnail. */
export const fallbackQueueKey = (path: string) => `${path}\u0000fallback`

let thumbCachePromise: Promise<Cache> | null = null

/**
 * Finds a locally cached thumbnail of the same file at another size, to show while `src` loads.
 * Returns null if `src` itself is cached or no other size is. Prefers the closest larger size.
 */
export async function findCachedThumbFallback(src: string): Promise<string | null> {
    if (typeof window === `undefined` || !(`caches` in window)) return null

    const wanted = Number(new URL(src, location.href).searchParams.get(`size`))
    const sizes = [...Object.values(config.preview.row), ...Object.values(config.preview.grid)].map(s => s.pixelSize).concat(fullscreenPixelSizes)
    const candidates = [...new Set(sizes)]
        .filter(size => size !== wanted)
        .sort((a, b) => (a > wanted ? 0 : 1) - (b > wanted ? 0 : 1) || Math.abs(a - wanted) - Math.abs(b - wanted))
        .map(size => src.replace(`size=${wanted}`, `size=${size}`).replace(`&${noSwCacheParam}`, ``) + thumbnailCacheQuery(size))

    try {
        const cache = await (thumbCachePromise ??= caches.open(`thumb-cache`))
        if (await cache.match(src)) return null
        for (const candidate of candidates) {
            if (await cache.match(candidate)) return candidate
        }
    } catch {
        thumbCachePromise = null
    }
    return null
}

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
    onClickSelectCheckbox: (e: MouseEvent, path: string) => void
}

export type FileListProps = FileContextMenuProps & FileEntryHandlerProps & {
    sortedEntries: typeof filesState.data.sortedEntries
    visibilityManager: VisibilityManager
}

export type FileEntryProps = FileEntryHandlerProps & {
    entry: FullFileMetadata
    visibilityManager: VisibilityManager
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

    private networkConcurrency = 2
    private cachedConcurrency = 4
    private renderDistance = 500
    private visibilityThreshold = 0.01
    private readonly thumbCacheName = `thumb-cache`
    private readonly cacheHintTtlSeconds = 10

    private loadingFromNetwork: Set<HTMLImageElement> = new Set()
    private cacheStateBySrc = new Map<string, { isCached: boolean, checkedAtSeconds: number }>()
    private cacheChecksInFlight = new Set<string>()

    visibleEntryPaths = new SvelteSet<string>()
    private entryElements = new Map<HTMLElement, string>()

    /** Called when files scroll into view. FileList uses this to fetch contentMeta. */
    onVisibleEntriesChange: (() => void) | null = null


    setScrollContainer(container: HTMLElement | null) {
        this.scrollContainer = container
        this.reinitObserver()
    }

    private initObserver() {
        this.observer = new IntersectionObserver(
            (entries) => {
                // A file row entered the viewport in this callback.
                let becameVisible = false
                for (const entry of entries) {
                    if (entry.target instanceof HTMLImageElement) {
                        this.handleImageIntersect(entry)
                    } else if (this.handleEntryIntersect(entry)) {
                        becameVisible = true
                    }
                }
                if (becameVisible) this.onVisibleEntriesChange?.()
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

    /** True only when the path was added to the visible set this callback. */
    private handleEntryIntersect(entry: IntersectionObserverEntry): boolean {
        const element = entry.target as HTMLElement
        const path = this.entryElements.get(element)
        if (!path) return false

        if (entry.isIntersecting) {
            if (this.visibleEntryPaths.has(path)) return false
            this.visibleEntryPaths.add(path)
            return true
        }
        this.visibleEntryPaths.delete(path)
        return false
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
            this.onVisibleEntriesChange?.()
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
                this.onVisibleEntriesChange?.()
            },
            destroy: () => {
                this.observer?.unobserve(node)
                const oldPath = this.entryElements.get(node)
                this.entryElements.delete(node)
                if (oldPath) this.visibleEntryPaths.delete(oldPath)
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
                this.onVisibleEntriesChange?.()
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
                        this.onVisibleEntriesChange?.()
                    }
                }
            },
            destroy: () => {
                this.observer?.unobserve(node)
                const oldPath = this.entryElements.get(node)
                this.entryElements.delete(node)
                if (oldPath) this.visibleEntryPaths.delete(oldPath)
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
                this.dropHeadless(existing)
            }
            this.imageMap.set(path, img)
            this.nodeToPath.set(img, path)
        }

        this.pending.add(img)
        this.primeCacheState(img)

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
            // The placeholder goes first, so it shows before the real thumbnail
            for (const key of [fallbackQueueKey(path), path]) {
                const img = this.imageMap.get(key)
                if (img && this.pending.has(img)) {
                    newPending.add(img)
                    this.pending.delete(img)
                }
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
        // Large thumbnails are not kept by the service worker, so preloading every one would only waste bandwidth.
        // Only the previews near the viewport are loaded, by the observers.
        if (pixelSize > maxCachedPixelSize) return

        this.clearUnusedPreviews(entries.map(entry => entry.path))

        const shareTokenParam = filesState.getIsShared() ? `&shareToken=${filesState.meta.shareToken}` : ``

        let added = false
        for (const entry of entries) {
            // Skip entries that already have a registered image (real or headless)
            if (this.imageMap.has(entry.path)) continue

            const format = getFileCategoryFromFilename(entry.filename || filenameFromPath(entry.path))
            if (format !== `image` && format !== `video`) continue

            const endpoint = format === `image` ? `image-thumbnail` : `video-preview`
            const src = `${joinContentUrl(`/api/v1/file/${endpoint}`)}?size=${pixelSize}&path=${encodeUrlFilePath(entry.path)}&modified=${entry.modifiedDate}${shareTokenParam}`

            const img = document.createElement(`img`)
            applyPreviewCors(img)
            img.setAttribute(`data-src`, src)

            this.headlessImages.add(img)
            this.pending.add(img)
            this.primeCacheState(img)
            this.imageMap.set(entry.path, img)
            this.nodeToPath.set(img, entry.path)
            added = true
        }

        if (added) {
            this.scheduleImageLoad()
        }
    }

    /**
     * Drop queued thumbnails that are no longer in the open folder, and discard
     * hidden preload images for those paths. In-list <img> nodes are left alone
     * until Svelte unmounts them.
     */
    clearUnusedPreviews(keepPaths: string[] = []) {
        const keep = new Set(keepPaths)
        for (const [path, img] of [...this.imageMap.entries()]) {
            if (keep.has(path)) continue
            this.unqueue(img)
            if (this.headlessImages.has(img)) this.dropHeadless(img)
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

        this.unqueue(img)
        this.loading.delete(img)
        this.loadingFromNetwork.delete(img)
        this.headlessImages.delete(img)
        
        const path = this.nodeToPath.get(img)
        if (path) {
            this.imageMap.delete(path)
            this.nodeToPath.delete(img)

            // If a real image is being unregistered (entry went to skeleton) and it was
            // still pending (never started loading), create a headless replacement so that
            // loadAllPreviews can still fetch and cache the thumbnail via the service worker.
            if (!wasHeadless && wasInPending && dataSrc && appState.settings.loadAllPreviews && !path.endsWith(`\u0000fallback`)) {
                const headless = document.createElement(`img`)
                applyPreviewCors(headless)
                headless.setAttribute(`data-src`, dataSrc)
                this.headlessImages.add(headless)
                this.pending.add(headless)
                this.primeCacheState(headless)
                this.imageMap.set(path, headless)
                this.nodeToPath.set(headless, path)
                this.scheduleImageLoad()
            }
        }
    }

    private unqueue(img: HTMLImageElement) {
        this.pending.delete(img)
        this.highPriority.delete(img)
        this.lowPriority.delete(img)
        this.observer?.unobserve(img)
        this.visibleImageObserver?.unobserve(img)
    }

    private dropHeadless(img: HTMLImageElement) {
        this.unqueue(img)
        this.loading.delete(img)
        this.loadingFromNetwork.delete(img)
        this.headlessImages.delete(img)
        img.onload = null
        img.onerror = null
        img.removeAttribute(`src`)
        const path = this.nodeToPath.get(img)
        if (path && this.imageMap.get(path) === img) this.imageMap.delete(path)
        this.nodeToPath.delete(img)
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
    private getNextCandidateFromPending(priorityFilter?: Set<HTMLImageElement>, requireCached: boolean = false): HTMLImageElement | null {
        for (const img of this.pending) {
            if (this.loading.has(img)) continue
            
            // If a priority filter is specified, only return images in that set
            if (priorityFilter && !priorityFilter.has(img)) continue
            if (requireCached && !this.isCacheHitCandidate(img, true)) continue
            
            return img
        }
        return null
    }

    // Finds candidates to load based on priority and concurrency limits
    // Uses pending set iteration order (maintained by reorderQueue) to ensure
    // images load sequentially from top to bottom in visual order
    private runProcessLoop() {
        while (this.loading.size < this.cachedConcurrency) {
            let candidate: HTMLImageElement | null = null
            const networkSlotsAvailable = this.loadingFromNetwork.size < this.networkConcurrency
            const requireCached = !networkSlotsAvailable

            // 1. ALWAYS check High Priority (Visible) first - but in sorted order
            candidate = this.getNextCandidateFromPending(this.highPriority, requireCached)

            // 2. Check Low Priority (Nearby) - but in sorted order
            if (!candidate) {
                candidate = this.getNextCandidateFromPending(this.lowPriority, requireCached)
            }

            // 3. Fallback: Load All (Remaining Pending) - already in sorted order
            if (!candidate && appState.settings.loadAllPreviews) {
                candidate = this.getNextCandidateFromPending(undefined, requireCached)
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
        const isCacheHit = this.isCacheHitCandidate(img, false)
        if (!isCacheHit) {
            this.loadingFromNetwork.add(img)
        }
        
        // Stop observing once loading starts
        this.observer?.unobserve(img)
        this.visibleImageObserver?.unobserve(img)
        this.highPriority.delete(img)
        this.lowPriority.delete(img)

        const cleanup = () => {
            img.onload = null
            img.onerror = null
            this.loading.delete(img)
            this.loadingFromNetwork.delete(img)
            this.scheduleImageLoad()
        }

        img.onload = cleanup
        img.onerror = () => {
            console.error(`Image load failed:`, src)
            cleanup()
        }

        applyPreviewCors(img)
        img.src = src
    }

    private getNowSeconds(): number {
        return Math.floor(Date.now() / 1000)
    }

    private isCacheHintFresh(checkedAtSeconds: number): boolean {
        return (this.getNowSeconds() - checkedAtSeconds) <= this.cacheHintTtlSeconds
    }

    private isCacheHitCandidate(img: HTMLImageElement, requireFreshHint: boolean): boolean {
        const src = img.getAttribute(`data-src`)
        if (!src) return false

        const state = this.cacheStateBySrc.get(src)
        if (!state) {
            this.primeCacheState(img)
            return false
        }

        if (!this.isCacheHintFresh(state.checkedAtSeconds)) {
            this.primeCacheState(img, true)
            return false
        }

        if (requireFreshHint) {
            return state.isCached
        }

        return state.isCached
    }

    private primeCacheState(img: HTMLImageElement, force: boolean = false) {
        const src = img.getAttribute(`data-src`)
        if (!src) return
        if (this.cacheChecksInFlight.has(src)) return
        if (!force) {
            const existing = this.cacheStateBySrc.get(src)
            if (existing && this.isCacheHintFresh(existing.checkedAtSeconds)) return
        }
        if (typeof window === `undefined` || !(`caches` in window)) return

        this.cacheChecksInFlight.add(src)
        caches.open(this.thumbCacheName)
            .then(cache => cache.match(src))
            .then((cached) => {
                if (!cached) {
                    this.cacheStateBySrc.set(src, { isCached: false, checkedAtSeconds: this.getNowSeconds() })
                    return
                }
                this.cacheStateBySrc.set(src, { isCached: true, checkedAtSeconds: this.getNowSeconds() })
            })
            .catch(() => {
                this.cacheStateBySrc.set(src, { isCached: false, checkedAtSeconds: this.getNowSeconds() })
            })
            .finally(() => {
                this.cacheChecksInFlight.delete(src)
                this.scheduleImageLoad()
            })
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

        this.clearUnusedPreviews()
        this.observer?.disconnect()
        this.observer = null
        this.visibleImageObserver?.disconnect()
        this.visibleImageObserver = null
        this.pending.clear()
        this.highPriority.clear()
        this.lowPriority.clear()
        this.loading.clear()
        this.loadingFromNetwork.clear()
        this.imageMap.clear()
        this.cacheStateBySrc.clear()
        this.cacheChecksInFlight.clear()
        this.headlessImages.clear()
        this.knownEntryPaths.clear()
    }
}


export function selectSiblingFile(direction: 'previous' | 'next', onlyFiles: boolean = false, openFile: boolean = false) {
    const entries = (filesState.isSearchOpen ? filesState.search.sortedEntries : filesState.data.sortedEntries)!
    if (entries.length < 1) return

    // Prefer the open file path so viewer nav stays file-by-file even if selection was cleared
    const currentPath = filesState.data.fileMeta?.path
        ?? filesState.selectedEntries.singlePath

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

/**
 * Type-ahead selection: typing selects the first entry whose filename starts with the typed text.
 * Esc / Backspace end the query and keep the selection, arrows cycle through matches while a query is active.
 * @returns true if the event was consumed
 */
export function handleTypeAheadKey(event: KeyboardEvent): boolean {
    const typeAhead = filesState.typeAhead

    if (event.ctrlKey || event.altKey || event.metaKey) return false
    if (filesState.data.fileMeta || !filesState.data.sortedEntries) return false
    if (isTextEntryFocused()) return false

    if (typeAhead.active) {
        if (event.key === `Escape` || event.key === `Backspace`) {
            event.preventDefault()
            typeAhead.reset()
            return true
        }

        const isPrevious = event.key === `ArrowUp` || event.key === `ArrowLeft`
        const isNext = event.key === `ArrowDown` || event.key === `ArrowRight`
        if ((isPrevious || isNext) && typeAhead.matchPaths.length > 0) {
            event.preventDefault()
            moveTypeAheadMatch(isPrevious ? `previous` : `next`)
            return true
        }
    }

    // Printable character; a leading space is not part of a query
    if (event.key.length === 1 && !(event.key === ` ` && !typeAhead.active)) {
        event.preventDefault()
        typeAhead.query += event.key

        const firstMatch = typeAhead.matchPaths[0]
        if (firstMatch) {
            filesState.selectedEntries.setSelected(firstMatch)
            scrollSelectedEntryIntoView(firstMatch)
        }
        return true
    }

    return false
}

function moveTypeAheadMatch(direction: 'previous' | 'next') {
    const { matchPaths, matchIndex } = filesState.typeAhead
    const count = matchPaths.length
    if (count < 1) return

    let newIndex: number
    if (matchIndex === -1) {
        newIndex = direction === `next` ? 0 : count - 1
    } else {
        newIndex = direction === `next` ? (matchIndex + 1) % count : (matchIndex - 1 + count) % count
    }

    const path = matchPaths[newIndex]
    filesState.selectedEntries.setSelected(path)
    scrollSelectedEntryIntoView(path)
}

/**
 * Text fields only; unlike isUserInAnyInput this ignores focused buttons, links and row checkboxes
 */
function isTextEntryFocused(): boolean {
    const el = document.activeElement as HTMLElement | null
    if (!el) return false
    if (el.isContentEditable) return true
    if (el.tagName === `TEXTAREA` || el.tagName === `SELECT`) return true
    return el instanceof HTMLInputElement && el.type !== `checkbox`
}

export function openEntry(path: string) {
    if (filesState.metaLoading) return
    return goto(getFilePagePath(path, filesState.meta.pagePath))
}

export function getFilePagePath(path: string, pagePath: string) {
    return filePageUrl(path, pagePath)
}

/**
 * Finds the tile of an entry in the list the user is looking at.
 * The folder list stays mounted (hidden) under search results, so both lists can hold the same path.
 */
function findEntryElement(path: string): HTMLElement | null {
    const list = filesState.isSearchOpen ? `search` : `folder`
    return document.querySelector(`[data-file-list="${list}"] [data-entry-path="${CSS.escape(path)}"]`)
}

/**
 * Scrolls the entry into view right away
 * @returns false if the entry is not in the list
 */
export function scrollEntryIntoViewNow(path: string, behavior: ScrollBehavior = `smooth`): boolean {
    const element = findEntryElement(path)
    if (!element) return false

    // Fullscreen scroll snap: align the tile to the top, where its snap point is
    const snapped = filesState.ui.fileViewType === `grid`
        && appState.settings.previewSize.grid === fullscreenPreviewSize
        && appState.settings.fullscreenScrollSnap
    element.scrollIntoView({ block: snapped ? `start` : `nearest`, behavior })
    element.focus({ preventScroll: true })
    return true
}

export function scrollSelectedEntryIntoView(path: string | null = null, behavior: ScrollBehavior = `smooth`) {
    setTimeout(() => {
        const targetPath = path || filesState.selectedEntries.singlePath
        if (targetPath) scrollEntryIntoViewNow(targetPath, behavior)
    }, 10)
}
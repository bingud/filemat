import { goto } from "$app/navigation"
import type { FileMetadata, FullFileMetadata } from "$lib/code/auth/types"
import { filesState } from "$lib/code/stateObjects/filesState.svelte"


export type FileEntryHandlerProps = {
    event_dragStart: (e: DragEvent, entry: FullFileMetadata) => void
    event_dragOver: (e: DragEvent, entry: FullFileMetadata) => void
    event_dragLeave: (e: DragEvent, entry: FullFileMetadata) => void
    event_drop: (e: DragEvent, entry: FullFileMetadata) => void
    event_dragEnd: (e: DragEvent, entry: FullFileMetadata) => void
    entryOnClick: (e: MouseEvent, entry: FullFileMetadata) => void
    onClickSelectCheckbox: (path: string) => void
    entryMenuOnClick: (button: HTMLButtonElement, entry: FullFileMetadata) => void
}

export type FileListProps = FileEntryHandlerProps & {
    sortedEntries: typeof filesState.data.sortedEntries
    option_rename: (entry: FileMetadata) => any
    option_move: (entry: FileMetadata) => any
    option_delete: (entry: FileMetadata) => any
    option_details: (entry: FileMetadata) => any

}

export type FileEntryProps = FileEntryHandlerProps & {
    entry: FullFileMetadata
    entryOnContextMenu: (e: MouseEvent, entry: FullFileMetadata) => void
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
    private scrollContainer: HTMLElement | null = null
    private viewportObserver: IntersectionObserver | null = null
    private nearbyObserver: IntersectionObserver | null = null
    
    private pending: Set<HTMLImageElement> = new Set()
    private highPriority: Set<HTMLImageElement> = new Set()
    private lowPriority: Set<HTMLImageElement> = new Set()
    private loading: Set<HTMLImageElement> = new Set()
    
    private processing = false
    private concurrency = 2
    private loadDistance = 500

    setScrollContainer(container: HTMLElement | null) {
        this.scrollContainer = container
        this.reinitObservers()
    }

    private initObservers() {
        const options = {
            root: this.scrollContainer
        }

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

        this.nearbyObserver = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    const img = entry.target as HTMLImageElement
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

    register(img: HTMLImageElement) {
        if (!img.hasAttribute('data-src')) return

        if (!this.viewportObserver) {
            this.initObservers()
        }

        this.pending.add(img)
        this.viewportObserver!.observe(img)
        this.nearbyObserver!.observe(img)
    }

    unregister(img: HTMLImageElement) {
        this.pending.delete(img)
        this.highPriority.delete(img)
        this.lowPriority.delete(img)
        this.viewportObserver?.unobserve(img)
        this.nearbyObserver?.unobserve(img)
    }

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

    private runProcessLoop() {
        while (this.loading.size < this.concurrency) {
            const capacity = this.concurrency - this.loading.size
            if (capacity <= 0) break

            let candidate: HTMLImageElement | null = null

            for (const img of this.highPriority) {
                if (this.pending.has(img) && !this.loading.has(img)) {
                    candidate = img
                    break
                }
            }

            if (!candidate) {
                for (const img of this.lowPriority) {
                    if (this.pending.has(img) && !this.loading.has(img)) {
                        candidate = img
                        break
                    }
                }
            }

            if (!candidate) break
            
            this.startLoad(candidate)
        }
    }

    private startLoad(img: HTMLImageElement) {
        if (this.loading.has(img)) return

        const src = img.getAttribute('data-src')
        if (!src) {
            this.unregister(img)
            return
        }

        this.pending.delete(img)
        this.loading.add(img)
        
        this.viewportObserver?.unobserve(img)
        this.nearbyObserver?.unobserve(img)
        this.highPriority.delete(img)
        this.lowPriority.delete(img)

        const cleanup = () => {
            img.onload = null
            img.onerror = null
            this.loading.delete(img)
            this.process()
        }

        img.onload = cleanup
        img.onerror = () => {
            console.error('Image load failed:', src)
            cleanup()
        }
        
        img.src = src
    }

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
                        node.removeAttribute('src')
                        this.register(node)
                    }
                },
                destroy: () => {
                    this.unregister(node)
                }
            }
        }
    }

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
    
    scrollSelectedEntryIntoView()
}

export function openEntry(path: string) {
    goto(`${filesState.meta.pagePath}${encodeURI(path)}`)
}

export function scrollSelectedEntryIntoView() {
    setTimeout(() => {
        if (filesState.selectedEntries.singlePath) {
            const selector = `[data-entry-path="${filesState.selectedEntries.singlePath.replace(/"/g, '\\"')}"]`
            const element = document.querySelector(selector)
            if (element) {
                element.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
            }
        }
    }, 10)
}
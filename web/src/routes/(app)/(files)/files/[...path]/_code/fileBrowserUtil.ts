export class ImageLoadQueue {
    private scrollContainer: HTMLElement | null = null
    // High priority observer (0px margin)
    private viewportObserver: IntersectionObserver | null = null
    // Low priority observer (400px margin)
    private prefetchObserver: IntersectionObserver | null = null
    
    private pending: Set<HTMLImageElement> = new Set()
    private highPriority: Set<HTMLImageElement> = new Set()
    private lowPriority: Set<HTMLImageElement> = new Set()
    private loading: Set<HTMLImageElement> = new Set()
    
    private processing = false
    private rootMargin: string = "400px"
    private concurrency = 6 // Increased from 2 for better responsiveness

    setScrollContainer(container: HTMLElement | null) {
        this.scrollContainer = container
        this.reinitObservers()
    }

    setRootMargin(margin: string) {
        this.rootMargin = margin
        this.reinitObservers()
    }

    private initObservers() {
        const options = {
            root: this.scrollContainer
        }

        // Observer 1: Strictly what is on screen
        this.viewportObserver = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    const img = entry.target as HTMLImageElement
                    if (entry.isIntersecting) {
                        this.highPriority.add(img)
                    } else {
                        this.highPriority.delete(img)
                    }
                }
                this.process()
            },
            { ...options, rootMargin: "0px" }
        )

        // Observer 2: Nearby items (Prefetch)
        this.prefetchObserver = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    const img = entry.target as HTMLImageElement
                    if (entry.isIntersecting) {
                        this.lowPriority.add(img)
                    } else {
                        this.lowPriority.delete(img)
                    }
                }
                this.process()
            },
            { ...options, rootMargin: this.rootMargin }
        )
    }

    private reinitObservers() {
        this.viewportObserver?.disconnect()
        this.prefetchObserver?.disconnect()
        this.highPriority.clear()
        this.lowPriority.clear()
        
        this.initObservers()
        
        for (const img of this.pending) {
            this.viewportObserver?.observe(img)
            this.prefetchObserver?.observe(img)
        }
    }

    register(img: HTMLImageElement) {
        if (!img.hasAttribute('data-src')) return

        if (!this.viewportObserver) {
            this.initObservers()
        }

        this.pending.add(img)
        this.viewportObserver!.observe(img)
        this.prefetchObserver!.observe(img)
    }

    unregister(img: HTMLImageElement) {
        this.pending.delete(img)
        this.highPriority.delete(img)
        this.lowPriority.delete(img)
        this.viewportObserver?.unobserve(img)
        this.prefetchObserver?.unobserve(img)
    }

    private process() {
        if (this.processing) return
        this.processing = true

        // Use microtask to allow sets to settle before processing
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

            // 1. Find a High Priority image (in Viewport) that is pending and not loading
            for (const img of this.highPriority) {
                if (this.pending.has(img) && !this.loading.has(img)) {
                    candidate = img
                    break
                }
            }

            // 2. If no High Priority, find a Low Priority image (Prefetch)
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

        // Move to loading state
        this.pending.delete(img)
        this.loading.add(img)
        
        // Stop observing immediately to save resources
        this.viewportObserver?.unobserve(img)
        this.prefetchObserver?.unobserve(img)
        this.highPriority.delete(img)
        this.lowPriority.delete(img)

        const cleanup = () => {
            img.onload = null
            img.onerror = null
            this.loading.delete(img)
            // Trigger process again to fill the slot
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
                        // Fully reset this node
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
        this.prefetchObserver?.disconnect()
        this.viewportObserver = null
        this.prefetchObserver = null
        this.pending.clear()
        this.highPriority.clear()
        this.lowPriority.clear()
        this.loading.clear()
        this.processing = false
    }
}
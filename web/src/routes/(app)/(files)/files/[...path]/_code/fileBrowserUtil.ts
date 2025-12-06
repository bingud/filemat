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
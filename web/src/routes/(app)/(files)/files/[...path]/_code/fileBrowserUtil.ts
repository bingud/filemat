export class ImageLoadQueue {
    private scrollContainer: HTMLElement | null = null
    private observer: IntersectionObserver | null = null
    private pending: Set<HTMLImageElement> = new Set()
    private nearVisible: Set<HTMLImageElement> = new Set()
    private inViewport: Set<HTMLImageElement> = new Set()
    private loading: Set<HTMLImageElement> = new Set()
    private processing = false
    private rootMargin: string = "400px"
    private concurrency = 2

    setScrollContainer(container: HTMLElement | null) {
        this.scrollContainer = container
        this.reinitObserver()
    }

    setRootMargin(margin: string) {
        this.rootMargin = margin
        this.reinitObserver()
    }

    private initObserver() {
        this.observer = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    const img = entry.target as HTMLImageElement
                    if (entry.isIntersecting) {
                        this.nearVisible.add(img)
                    } else {
                        this.nearVisible.delete(img)
                    }

                    if (this.isActuallyVisible(img)) {
                        this.inViewport.add(img)
                    } else {
                        this.inViewport.delete(img)
                    }
                }
                this.process()
            },
            {
                root: this.scrollContainer,
                rootMargin: this.rootMargin
            }
        )
    }

    private reinitObserver() {
        this.observer?.disconnect()
        this.nearVisible.clear()
        this.inViewport.clear()
        this.initObserver()
        for (const img of this.pending) {
            this.observer?.observe(img)
        }
    }

    register(img: HTMLImageElement) {
        if (!img.hasAttribute('data-src')) return

        if (!this.observer) {
            this.initObserver()
        }

        this.pending.add(img)
        this.observer!.observe(img)
        this.process()
    }

    unregister(img: HTMLImageElement) {
        this.pending.delete(img)
        this.nearVisible.delete(img)
        this.inViewport.delete(img)
        this.observer?.unobserve(img)

        // If it's currently loading we cannot reliably cancel the browser load
        // but we ensure it's removed from pending/visibility sets so it won't be
        // re-started after completion
    }

    private process() {
        if (this.processing) return

        this.processing = true

        try {
            while (this.loading.size < this.concurrency) {
                const capacity = this.concurrency - this.loading.size
                const candidates: HTMLImageElement[] = []

                // pick from in-viewport first
                for (const img of this.inViewport) {
                    if (candidates.length >= capacity) break
                    if (!this.pending.has(img)) continue
                    if (this.loading.has(img)) continue
                    candidates.push(img)
                }

                // then pick from near-viewport
                if (candidates.length < capacity) {
                    for (const img of this.nearVisible) {
                        if (candidates.length >= capacity) break
                        if (!this.pending.has(img)) continue
                        if (this.loading.has(img)) continue
                        if (this.inViewport.has(img)) continue
                        candidates.push(img)
                    }
                }

                if (candidates.length === 0) break

                for (const img of candidates) {
                    this.startLoad(img)
                }
            }
        } finally {
            this.processing = false
        }
    }

    private startLoad(img: HTMLImageElement) {
        if (this.loading.has(img)) return

        const src = img.getAttribute('data-src')
        if (!src) {
            this.pending.delete(img)
            this.nearVisible.delete(img)
            this.inViewport.delete(img)
            return
        }

        // Move from pending -> loading
        this.pending.delete(img)
        this.loading.add(img)
        this.observer?.unobserve(img)

        this.load(img)
            .catch(() => {
                console.error('Image load failed:', src)
            })
            .finally(() => {
                this.loading.delete(img)
                this.nearVisible.delete(img)
                this.inViewport.delete(img)
                // ensure it's not observed/queued anymore
                this.observer?.unobserve(img)
                this.process()
            })
    }

    private load(img: HTMLImageElement): Promise<void> {
        return new Promise((resolve, reject) => {
            const src = img.getAttribute('data-src')
            if (!src) {
                resolve()
                return
            }

            const handleLoad = () => {
                cleanup()
                resolve()
            }

            const handleError = () => {
                cleanup()
                reject(new Error('Failed to load'))
            }

            const cleanup = () => {
                img.removeEventListener('load', handleLoad)
                img.removeEventListener('error', handleError)
            }

            img.addEventListener('load', handleLoad)
            img.addEventListener('error', handleError)
            // set src to start loading
            img.src = src
        })
    }

    private isActuallyVisible(img: HTMLImageElement): boolean {
        try {
            const rect = img.getBoundingClientRect()
            if (rect.width === 0 && rect.height === 0) return false

            let rootRect: { top: number; left: number; bottom: number; right: number }
            if (this.scrollContainer) {
                const r = this.scrollContainer.getBoundingClientRect()
                rootRect = { top: r.top, left: r.left, bottom: r.bottom, right: r.right }
            } else {
                rootRect = {
                    top: 0,
                    left: 0,
                    bottom: window.innerHeight,
                    right: window.innerWidth
                }
            }

            const intersects =
                rect.bottom > rootRect.top &&
                rect.top < rootRect.bottom &&
                rect.right > rootRect.left &&
                rect.left < rootRect.right

            return intersects
        } catch (e) {
            return false
        }
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
        this.observer?.disconnect()
        this.observer = null
        this.pending.clear()
        this.nearVisible.clear()
        this.inViewport.clear()
        this.loading.clear()
        this.processing = false
    }
}
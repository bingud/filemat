export class ImageLoadQueue {
    private scrollContainer: HTMLElement | null = null
    private observer: IntersectionObserver | null = null
    private pending: Set<HTMLImageElement> = new Set()
    private visible: Set<HTMLImageElement> = new Set()
    private loading: HTMLImageElement | null = null
    private processing = false
    private rootMargin: string = "400px"

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
                        this.visible.add(img)
                    } else {
                        this.visible.delete(img)
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
        this.visible.clear()
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
    }

    unregister(img: HTMLImageElement) {
        this.pending.delete(img)
        this.visible.delete(img)
        this.observer?.unobserve(img)
        
        if (this.loading === img) {
            this.loading = null
            this.processing = false
            this.process()
        }
    }

    private async process() {
        if (this.processing) return
        
        this.processing = true

        while (this.visible.size > 0) {
            const img = this.visible.values().next().value
            
            if (!img) break
            
            if (!this.pending.has(img)) {
                this.visible.delete(img)
                continue
            }

            this.loading = img
            
            try {
                await this.load(img)
            } catch {
                console.error(
                    'Image load failed:',
                    img.getAttribute('data-src')
                )
            }
            
            this.pending.delete(img)
            this.visible.delete(img)
            this.loading = null
        }

        this.processing = false
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
            img.src = src
        })
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
        this.visible.clear()
        this.loading = null
        this.processing = false
    }
}
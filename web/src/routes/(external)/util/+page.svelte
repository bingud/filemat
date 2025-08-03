<script lang="ts">
    let inputSvg = $state<string>('')
    let outputSvg = $state<string>('')
    let error = $state<string>('')

    interface BoundingBox {
        minX: number
        minY: number
        maxX: number
        maxY: number
    }

 
    function getBoundingBox(element: Element): BoundingBox | null {
        // Create a temporary SVG in the DOM with proper setup
        const tempSvg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
        tempSvg.setAttribute('width', '1000')
        tempSvg.setAttribute('height', '1000')
        tempSvg.style.position = 'absolute'
        tempSvg.style.visibility = 'hidden'
        tempSvg.style.top = '-9999px'
        document.body.appendChild(tempSvg)

        try {
            // Clone the entire SVG content
            const clonedElement = element.cloneNode(true) as SVGSVGElement
            
            // Remove any existing viewBox to get actual coordinates
            clonedElement.removeAttribute('viewBox')
            clonedElement.setAttribute('width', '1000')
            clonedElement.setAttribute('height', '1000')
            
            // Replace the temp SVG with our cloned content
            tempSvg.innerHTML = clonedElement.innerHTML
            
            // Get the actual bounding box using native method
            const tempBBox = tempSvg.getBBox()
            
            if (tempBBox.width === 0 && tempBBox.height === 0) {
                return null
            }
            
            return {
                minX: tempBBox.x,
                minY: tempBBox.y,
                maxX: tempBBox.x + tempBBox.width,
                maxY: tempBBox.y + tempBBox.height
            }
            
        } catch (error) {
            console.warn('Failed to get bounding box:', error)
            return null
        } finally {
            document.body.removeChild(tempSvg)
        }
    }


    interface SvgPreview {
        content: string
        viewBox: string
    }

    function createSafePreview(svgString: string): SvgPreview | null {
        if (!svgString.trim()) return null
        try {
            const parser = new DOMParser()
            const doc = parser.parseFromString(svgString, 'image/svg+xml')
            if (doc.querySelector('parsererror')) return null
            const root = doc.documentElement
            const viewBox = root.getAttribute('viewBox') || '0 0 64 64'
            return { content: root.innerHTML, viewBox }
        } catch {
            return null
        }
    }

    function cropSvgPadding(): void {
        error = ''
        
        if (!inputSvg.trim()) {
            outputSvg = ''
            return
        }
        
        try {
            const parser = new DOMParser()
            const doc = parser.parseFromString(inputSvg, 'image/svg+xml')
            
            if (doc.querySelector('parsererror')) {
                throw new Error('Invalid SVG format')
            }
            
            const rootElement = doc.documentElement
            const bbox = getBoundingBox(rootElement)
            
            if (!bbox) {
                outputSvg = inputSvg
                return
            }
            
            const padding = 0
            const round = (num: number) => Math.round(num * 100) / 100
            const newViewBox = `${round(bbox.minX - padding)} ${round(bbox.minY - padding)} ${round(bbox.maxX - bbox.minX + 2 * padding)} ${round(bbox.maxY - bbox.minY + 2 * padding)}`
            
            let result = inputSvg
            
            const viewBoxRegex = /(viewBox\s*=\s*)(["'])([^"']*)(\2)/i
            const viewBoxMatch = result.match(viewBoxRegex)
            
            if (viewBoxMatch) {
                result = result.replace(viewBoxRegex, `$1$2${newViewBox}$4`)
            } else {
                const openTagRegex = /(<(?:svg|symbol)[^>]*?)(\s*>)/i
                const openTagMatch = result.match(openTagRegex)
                
                if (openTagMatch) {
                    result = result.replace(openTagRegex, `$1 viewBox="${newViewBox}"$2`)
                }
            }
            
            outputSvg = result
            
        } catch (err) {
            const errorMessage = err instanceof Error ? err.message : 'Unknown error'
            error = `Error processing SVG: ${errorMessage}`
            outputSvg = ''
        }
    }

    $effect(() => {
        if (inputSvg) {
            cropSvgPadding()
        }
    })

    let safeInputPreview = $derived(createSafePreview(inputSvg))
    let safeOutputPreview = $derived(createSafePreview(outputSvg))
</script>

<div class="fixed top-0 left-0 w-svw h-svh px-6 py-2">
    <div class="max-w-6xl mx-auto shrink-0 h-full flex flex-col">
        <h1 class="text-3xl font-bold mb-6 text-center">SVG Padding Cropper</h1>
        
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6 flex-grow">
            <div class="h-full flex flex-col gap-4">
                <div class="flex items-center gap-4">
                    <h2 class="text-xl font-semibold">Input SVG</h2>
                    {#if safeInputPreview}
                        <svg
                            class="w-16 h-16 border rounded flex items-center justify-center overflow-hidden bg-white"
                            fill="black"
                            stroke="black"
                            viewBox={safeInputPreview.viewBox}
                            xmlns="http://www.w3.org/2000/svg"
                        >
                            {@html safeInputPreview.content}
                        </svg>
                    {/if}
                </div>
                <textarea 
                    bind:value={inputSvg}
                    placeholder="Paste your SVG code here..."
                    class="w-full flex-grow p-4 bg-gray-800 border border-gray-600 rounded-lg font-mono text-sm resize-none focus:outline-none focus:border-blue-500"
                ></textarea>
            </div>
            
            <div class="h-full flex flex-col gap-4">
                <div class="flex items-center gap-4">
                    <h2 class="text-xl font-semibold">Cropped SVG</h2>
                    {#if safeOutputPreview}
                        <svg
                            class="w-16 h-16 border rounded flex items-center justify-center overflow-hidden bg-white"
                            fill="black"
                            stroke="black"
                            style="background-image: repeating-conic-gradient(#f3f4f6 0% 25%, white 0% 50%) 50% / 8px 8px"
                            viewBox={safeOutputPreview.viewBox}
                            xmlns="http://www.w3.org/2000/svg"
                        >
                            {@html safeOutputPreview.content}
                        </svg>
                    {/if}
                </div>
                
                {#if error}
                    <div class="p-4 bg-red-900 border border-red-600 rounded-lg">
                        <p class="text-red-200">{error}</p>
                    </div>
                {/if}
                
                <textarea 
                    value={outputSvg}
                    readonly
                    placeholder="Cropped SVG will appear here..."
                    class="w-full flex-grow p-4 bg-gray-800 border border-gray-600 rounded-lg font-mono text-sm resize-none"
                ></textarea>
                
                {#if outputSvg}
                    <button 
                        onclick={() => navigator.clipboard.writeText(outputSvg)}
                        class="w-full px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded-lg transition-colors"
                    >
                        Copy to Clipboard
                    </button>
                {/if}
            </div>
        </div>

        <div class="w-full h-[2rem] flex mt-auto">
            <a href="https://koza.dev" target="_blank" class="opacity-10 hover:opacity-50" title="svég pedin croper">Internet item of koza.dev</a>
        </div>
    </div>
</div>
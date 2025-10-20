<script lang="ts">
    import CloudUploadIcon from '$lib/component/icons/CloudUploadIcon.svelte';
    import { onMount, onDestroy, createEventDispatcher } from 'svelte'

    const dispatch = createEventDispatcher<{
        filesDropped: { files: FileList }
    }>()

    let isVisible = $state(false)
    let dragCounter = $state(0) // To handle dragging over child elements

    // Handler for when a drag enters the document
    const handleDocumentDragEnter = (e: DragEvent) => {
        e.preventDefault()
        // Only show dropzone if files are being dragged
        if (e.dataTransfer?.types.includes('Files') && e.dataTransfer?.dropEffect !== 'link') {
            dragCounter++
            if (!isVisible) {
                isVisible = true
            }
        }
    }

    // Handler for when a drag leaves the document
    const handleDocumentDragLeave = (e: DragEvent) => {
        e.preventDefault()
        dragCounter--
        if (dragCounter <= 0) {
            dragCounter = 0 // Reset counter
            isVisible = false
        }
    }

    // Handler for dragging over the document (necessary to allow drop)
    const handleDocumentDragOver = (e: DragEvent) => {
        e.preventDefault() // This allows the drop event to fire
    }

    // Handler for when files are dropped anywhere on the document
    const handleDocumentDrop = (e: DragEvent) => {
        e.preventDefault()
        
        const files = e.dataTransfer?.files
        const isFromPage = e.dataTransfer?.getData('isFromPage')
        if (files && files.length > 0 && !isFromPage) {
            dispatch('filesDropped', { files })
        }
        
        // Hide dropzone and reset counter after drop
        isVisible = false
        dragCounter = 0
    }

    // Handler for dragging over the specific dropzone card (necessary to allow drop on it)
    const handleDropzoneCardDragOver = (e: DragEvent) => {
        e.preventDefault() // This allows the drop event to fire on this specific element
        // Optionally, add visual feedback here if needed when dragging over the card itself
    }

    onMount(() => {
        document.addEventListener('dragenter', handleDocumentDragEnter)
        document.addEventListener('dragleave', handleDocumentDragLeave)
        document.addEventListener('dragover', handleDocumentDragOver)
        document.addEventListener('drop', handleDocumentDrop) // Renamed for clarity
    })

    onDestroy(() => {
        document.removeEventListener('dragenter', handleDocumentDragEnter)
        document.removeEventListener('dragleave', handleDocumentDragLeave)
        document.removeEventListener('dragover', handleDocumentDragOver)
        document.removeEventListener('drop', handleDocumentDrop)
    })
</script>

{#if isVisible}
    <div class="fixed inset-0 bg-black/60 flex items-center justify-center z-[9999]">
        <div 
            class="
                w-[60vw] h-[60vh] max-w-[800px] max-h-[600px] min-w-[300px] min-h-[200px] md:w-[60vw] md:h-[60vh] sm:w-[90vw] sm:h-[50vh] sm:min-w-[280px] sm:min-h-[180px] 
                bg-white dark:bg-neutral-900
                border-3 border-dashed border-slate-300 hover:border-blue-500 dark:border-slate-800 dark:hover:border-blue-600
                flex items-center justify-center transition-colors duration-200 rounded-xl
            "
            ondragover={handleDropzoneCardDragOver}
        >
            <div class="text-center pointer-events-none"> 
                <div class="size-16 siez-12 mx-auto mb-4">
                    <CloudUploadIcon></CloudUploadIcon>
                </div>
                
                <h2 class="m-0 text-2xl sm:text-xl">
                    Drop here to upload
                </h2>
            </div>
        </div>
    </div>
{/if}
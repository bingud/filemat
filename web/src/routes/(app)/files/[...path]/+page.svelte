<script lang="ts">
    import { beforeNavigate } from "$app/navigation";
    import { getFileData } from "$lib/code/module/files";
    import { pageTitle } from "$lib/code/util/codeUtil.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { onDestroy, untrack } from "svelte";
    import FileViewer from "./content/FileViewer.svelte";
    import { breadcrumbState, createBreadcrumbState, destroyBreadcrumbState } from "./content/code/breadcrumbState.svelte";
    import Breadcrumbs from "./content/Breadcrumbs.svelte";
    import FileBrowser from "./content/FileBrowser.svelte";
    import DetailsSidebar from "./content/DetailsSidebar.svelte";
    import { createFilesState, destroyFilesState, filesState } from "./content/code/filesState.svelte";
    import { fade, fly } from "svelte/transition";
    import { linear } from "svelte/easing";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import InfoIcon from "$lib/component/icons/InfoIcon.svelte";
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
    import FileIcon from "$lib/component/icons/FileIcon.svelte";
    import PlusIcon from "$lib/component/icons/PlusIcon.svelte";
    import * as tus from 'tus-js-client';
    
    createFilesState()
    createBreadcrumbState()

    onDestroy(() => {
        destroyFilesState()
        destroyBreadcrumbState()
    })

    const title = $derived(pageTitle(filesState.segments[filesState.segments.length - 1] || "Files"))

    let newButtonPopoverOpen = $state(false);
    
    // Functions for new item actions
    function handleUpload() {
        newButtonPopoverOpen = false;

        const input = document.createElement('input');
        input.type = 'file';
        input.style.display = 'none'; // Keep it hidden

        input.onchange = (e) => {
            const file = (e.target as HTMLInputElement).files?.[0];
            if (!file) {
                return;
            }

            // Construct the full target path
            const currentPath = filesState.path === '/' ? '' : filesState.path; // Handle root path
            const targetFilename = `${currentPath}/${file.name}`;

            console.log(`Attempting to upload ${file.name} to ${targetFilename}`);

            const upload = new tus.Upload(file, {
                endpoint: "/api/v1/file/upload", // Your TUS endpoint
                retryDelays: [0, 3000, 5000, 10000, 20000],
                metadata: {
                    filename: targetFilename,
                    // Add any other metadata your server needs
                    // filetype: file.type 
                },
                onError: (error) => {
                    console.error("Failed because:", error);
                    // Add user feedback for error
                    alert(`Upload failed: ${error}`);
                },
                onProgress: (bytesUploaded, bytesTotal) => {
                    const percentage = ((bytesUploaded / bytesTotal) * 100).toFixed(2);
                    console.log(bytesUploaded, bytesTotal, percentage + "%");
                    // Update UI with progress if needed
                },
                onSuccess: () => {
                    // Cast upload.file to File to access the name property safely
                    const uploadedFile = upload.file as File;
                    console.log("Download %s from %s", uploadedFile.name, upload.url);
                    // Add user feedback for success
                    alert(`Successfully uploaded ${uploadedFile.name}`);
                    // Optionally, refresh the file list after upload
                    // loadPageData(filesState.path); // <-- Be mindful of potential race conditions if uploads are very fast
                }
            });

            // Start the upload
            upload.start();

            // Clean up the input element
            document.body.removeChild(input);
        };

        // Append to body, trigger click, and remove
        document.body.appendChild(input);
        input.click();
        // Note: Removal is now handled in the onchange event after selection or cancellation
    }
    
    function handleNewFolder() {
        newButtonPopoverOpen = false
    }
    
    function handleNewFile() {
        newButtonPopoverOpen = false
    }

    $effect(() => {
        if (filesState.path) {
            untrack(() => {
                filesState.abort()
                filesState.clearState()

                filesState.data.content = null

                if (filesState.path === "/") {
                    filesState.scroll.pathPositions = {}
                }

                loadPageData(filesState.path).then(() => {
                    recoverScrollPosition()
                })
            })
        }
    })

    beforeNavigate(() => {
        saveScrollPosition()
    })

    async function loadPageData(filePath: string) {
        filesState.lastFilePathLoaded = filePath
        filesState.metaLoading = true

        const result = await getFileData(filePath, filesState.abortController?.signal)
        if (filesState.lastFilePathLoaded !== filePath) return
        
        if (result) {
            filesState.data.meta = result.meta
            filesState.data.entries = result.entries || null
            
            // If no entry is selected and this is a folder, select the current folder
            if (filesState.selectedEntry.path === null && result.meta.fileType === "FOLDER") {
                filesState.selectedEntry.path = result.meta.filename
                console.log(`selected`, filesState.selectedEntry.path)
            }
        }
        filesState.metaLoading = false
    }

    // Scrolling position
    function saveScrollPosition() {
        if (!filesState.path || !filesState.scroll.container) return
        const pos = filesState.scroll.container.scrollTop
        filesState.scroll.pathPositions[filesState.path] = pos
    }

    function recoverScrollPosition() {
        if (!filesState.path || !filesState.scroll.container) return
        const pos = filesState.scroll.pathPositions[filesState.path]
        if (!pos) return
        filesState.scroll.container.scrollTo({top: pos})
    }

</script>


<svelte:head>
    <title>{title}</title>
</svelte:head>


<div class="page">
    <div class="w-full flex h-full min-h-0">
        <div bind:this={filesState.scroll.container} class="w-full {filesState.ui.detailsOpen ? 'w-[calc(100%-20rem)]' : 'w-full'} md:w-full flex flex-col h-full overflow-y-auto overflow-x-hidden custom-scrollbar md:gutter-stable-both">
            <!-- Header -->
            <div class="w-full h-[5rem] md:h-[3rem] shrink-0 flex flex-col-reverse md:flex-row px-2 items-center justify-between">
                <div bind:offsetWidth={breadcrumbState.containerWidth} class="w-full md:w-[85%] h-1/2 md:h-full flex items-center">
                    <Breadcrumbs></Breadcrumbs>                    
                </div>

                <div class="w-full md:w-[15%] h-1/2 md:h-full flex items-center justify-end">
                    <Popover.Root bind:open={newButtonPopoverOpen}>
                        <Popover.Trigger class="h-full flex items-center justify-center py-2">
                            <div class="h-full flex items-center justify-center bg-neutral-200 dark:bg-neutral-800 hover:bg-neutral-300 dark:hover:bg-neutral-700 rounded-md px-2">
                                <span class="">New</span>
                            </div>
                        </Popover.Trigger>
                        <Popover.Content align="end" class="relative z-50">
                            <div class="w-[14rem] max-w-full max-h-full rounded-lg bg-neutral-250 dark:bg-neutral-800 py-2 flex flex-col z-50">
                                <button on:click={handleUpload} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                                    <div class="size-5 flex-shrink-0">
                                        <PlusIcon />
                                    </div>
                                    <span>Upload</span>
                                </button>
                                <button on:click={handleNewFolder} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                                    <div class="size-5 flex-shrink-0">
                                        <FolderIcon />
                                    </div>
                                    <span>Folder</span>
                                </button>
                                <button on:click={handleNewFile} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                                    <div class="size-5 flex-shrink-0">
                                        <FileIcon />
                                    </div>
                                    <span>File</span>
                                </button>
                            </div>
                        </Popover.Content>
                    </Popover.Root>

                    <button on:click={() => { filesState.ui.toggleSidebar() }} class="h-full aspect-square p-2 md:p-3 group flex items-center justify-center">
                        <div class="size-full rounded-full hover:bg-neutral-300 dark:hover:bg-neutral-700">
                            <InfoIcon />
                        </div>
                    </button>
                </div>
            </div>

            <!-- Files -->
            <div class="h-[calc(100%-3rem)] w-full">
                {#if !filesState.metaLoading && filesState.data.meta}
                    {#if filesState.data.meta.fileType === "FOLDER" && filesState.data.sortedEntries}
                        <FileBrowser />
                    {:else if filesState.data.meta.fileType.startsWith("FILE")}
                        <div class="center">
                            <FileViewer />
                        </div>                    
                    {/if}
                {:else if !filesState.metaLoading && !filesState.data.meta}
                    <div class="center">
                        <p class="text-xl">Failed to load this file.</p>
                    </div>
                {:else}
                    <div class="center">
                        <Loader></Loader>
                    </div>
                {/if}
            </div>
        </div>


        <div class="contents">
            <!-- File info sidebar -->
            {#if filesState.ui.detailsOpen}
                <div on:click={filesState.unselect} class="fixed z-10 top-0 left-0 w-full h-full overflow-hidden flex justify-end pointer-events-none md:contents min-h-0">
                    <div transition:fly={{ duration: 150, x: 400, opacity: 1 }} class="flex flex-col h-full max-w-full w-[20rem] shrink-0 pointer-events-auto min-h-0">
                        <DetailsSidebar />
                    </div>
                </div>
            {/if}

            <!-- Close sidebar background button -->
            {#if !uiState.isDesktop && filesState.ui.detailsOpen}
                <button aria-label="Close details sidebar" on:click={() => { filesState.ui.detailsOpen = false }} transition:fade={{ duration: 150, easing: linear }} class="absolute top-0 left-0 size-full bg-black/40 !cursor-default pointer-events-auto"></button>
            {/if}
        </div>
    </div>
</div>
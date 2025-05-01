<script lang="ts">
    import { beforeNavigate, goto } from "$app/navigation"
    import { getFileData } from "$lib/code/module/files"
    import { addSuffix, keysOf, pageTitle, unixNowMillis, valuesOf } from "$lib/code/util/codeUtil.svelte"
    import Loader from "$lib/component/Loader.svelte"
    import { onDestroy, onMount, untrack } from "svelte"
    import FileViewer from "./content/FileViewer.svelte"
    import { breadcrumbState, createBreadcrumbState, destroyBreadcrumbState } from "./content/code/breadcrumbState.svelte"
    import Breadcrumbs from "./content/elements/Breadcrumbs.svelte"
    import FileBrowser from "./content/FileBrowser.svelte"
    import DetailsSidebar from "./content/DetailsSidebar.svelte"
    import { createFilesState, destroyFilesState, filesState } from "../../../../lib/code/stateObjects/filesState.svelte"
    import { fade, fly } from "svelte/transition"
    import { linear } from "svelte/easing"
    import { uiState } from "$lib/code/stateObjects/uiState.svelte"
    import InfoIcon from "$lib/component/icons/InfoIcon.svelte"
    import { Popover } from "$lib/component/bits-ui-wrapper"
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte"
    import FileIcon from "$lib/component/icons/FileIcon.svelte"
    import PlusIcon from "$lib/component/icons/PlusIcon.svelte"
    import { formData, handleError, handleErrorResponse, handleException, safeFetch } from "$lib/code/util/codeUtil.svelte"    
    import { uploadWithTus } from "$lib/code/module/files"
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import { uploadState } from "$lib/code/stateObjects/subState/uploadState.svelte";
    import { filePermissionCount, filePermissionMeta } from "$lib/code/data/permissions";

    createFilesState()
    createBreadcrumbState()
    
    onMount(() => {
        window.addEventListener('keydown', handleKeyDown)
        
        return () => {
            window.removeEventListener('keydown', handleKeyDown)
        };
    })

    onDestroy(() => {
        destroyFilesState()
        destroyBreadcrumbState()
    })

    const title = $derived(pageTitle(filesState.segments[filesState.segments.length - 1] || "Files"))
    let newButtonPopoverOpen = $state(false)
    
    function handleKeyDown(event: KeyboardEvent) {
        // Navigate to the parent folder
        if (event.key === "Backspace") {
            const currentPath = filesState.path
            if (currentPath === "/") return

            const parentPath = currentPath.slice(0, currentPath.lastIndexOf("/"))
            goto(`/files${parentPath}`)
        }
    }

    // Functions for new item actions
    function handleUpload() {
        newButtonPopoverOpen = false
        uploadWithTus()
    }
    
    async function handleNewFolder() {
        newButtonPopoverOpen = false
        
        const folderName = prompt("Enter folder name:")
        if (!folderName) return
        
        // Construct the full target path
        const currentPath = filesState.path === '/' ? '' : filesState.path
        const targetPath = `${currentPath}/${folderName}`
        console.log(`targetPath`, targetPath)
        console.log(`currentPath`, currentPath)
        
        // Call API to create folder using safeFetch
        const response = await safeFetch('/api/v1/folder/create', { 
            method: 'POST',
            body: formData({ path: targetPath })
        })
        if (response.failed) {
            handleException("Failed to create folder", "Failed to create folder.", response.exception)
            return
        }
        
        const status = response.code
        const json = response.json()
        
        if (status.ok) {
            filesState.data.entries?.push({
                path: targetPath,
                filename: folderName,
                modifiedDate: unixNowMillis(),
                createdDate: unixNowMillis(),
                fileType: "FOLDER",
                size: 0,
                permissions: keysOf(filePermissionMeta),
            })
        } else if (status.serverDown) {
            handleError(`Server ${status} when creating folder`, "Failed to create folder. Server is unavailable.")
        } else {
            handleErrorResponse(json, "Failed to create folder.")
        }
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

            if (result.meta.fileType === "FOLDER") {
                filesState.data.entries = result.entries || null
            } else if (result.meta.fileType === "FOLDER_LINK") {
                if (appState.followSymlinks) {
                    result.entries?.forEach((entry) => {
                        const linkPath = `${addSuffix(filePath, "/")}${entry.filename!}`
                        entry.path = linkPath
                    })
                    filesState.data.entries = result.entries || null
                }
            }
            
            // If no entry is selected and this is a folder, select the current folder
            if (filesState.selectedEntries.first === null && result.meta.fileType === "FOLDER") {
                filesState.selectedEntries.first = result.meta.path
                console.log(`selected`, filesState.selectedEntries.first)
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
                        <Popover.Trigger class="h-full flex items-center justify-center py-2" hidden={filesState.data.meta?.fileType.startsWith("FILE")}>
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
                    {#if filesState.data.sortedEntries && (filesState.data.meta.fileType === "FOLDER" || (filesState.data.meta.fileType === "FOLDER_LINK" && appState.followSymlinks))}
                        <FileBrowser />
                    {:else if filesState.data.meta.fileType === "FILE" || filesState.data.meta.fileType === "FILE_LINK" || (filesState.data.meta.fileType === "FOLDER_LINK" && !appState.followSymlinks)}
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
                    <div transition:fly={{ duration: 150, x: 400, opacity: 1 }} class="flex flex-col h-full max-w-full w-details-sidebar shrink-0 pointer-events-auto min-h-0">
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
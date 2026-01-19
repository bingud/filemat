<script lang="ts">
    import { beforeNavigate, goto } from "$app/navigation"
    import { appendTrailingSlash, dynamicInterval, explicitEffect, generateRandomNumber, isFile, isPathDirectChild as isPathDirectChildOf, letterS, pageTitle, unixNow } from "$lib/code/util/codeUtil.svelte"
    import Loader from "$lib/component/Loader.svelte"
    import { onDestroy, onMount } from "svelte"
    import { breadcrumbState, createBreadcrumbState, destroyBreadcrumbState } from "./_code/breadcrumbState.svelte"
    import Breadcrumbs from './_elements/layout/Breadcrumbs.svelte';
    import DetailsSidebar from './_elements/layout/DetailsSidebar.svelte';
    import { createFilesState, destroyFilesState, filesState, type StateMetadata } from "$lib/code/stateObjects/filesState.svelte"
    import { fade, fly } from "svelte/transition"
    import { linear } from "svelte/easing"
    import { uiState } from "$lib/code/stateObjects/uiState.svelte"
    import NewFolderIcon from '$lib/component/icons/NewFolderIcon.svelte'
    import NewFileIcon from '$lib/component/icons/NewFileIcon.svelte'
    import TrashIcon from '$lib/component/icons/TrashIcon.svelte'
    import DownloadIcon from '$lib/component/icons/DownloadIcon.svelte'
    import MoveIcon from '$lib/component/icons/MoveIcon.svelte'
    import FileDropzone from './_elements/ui/FileDropzone.svelte'
    import { clientState } from '$lib/code/stateObjects/clientState.svelte'
    import FileBrowser from './_elements/FileBrowser/FileBrowser.svelte'
    import FileViewer from './_elements/layout/FileViewer.svelte'
    import { event_filesDropped, handleKeyDown, handleNewFile, loadPageData, recoverScrollPosition, reloadCurrentFolder, saveScrollPosition } from './_code/pageLogic'
    import { handleNewFolder, option_cancelFileEdit, option_deleteSelectedFiles, option_downloadSelectedFiles, option_moveSelectedFiles, saveEditedFile } from './_code/fileActions'
    import NewFileButton from './_elements/button/NewFileButton.svelte'
    import { fileViewType_getFromLocalstorage } from "$lib/code/util/uiUtil"
    import FileSortingButton from "./_elements/button/FileSortingButton.svelte"
    import FileViewTypeButton from "./_elements/button/FileViewTypeButton.svelte"
    import FileDetailsButton from "./_elements/button/FileDetailsButton.svelte"
    import { textFileViewerState } from "./_code/textFileViewerState.svelte"
    import SaveIcon from "$lib/component/icons/SaveIcon.svelte"
    import { auth } from "$lib/code/stateObjects/authState.svelte"
    import { sharedFilesPageState } from "../../shared-files/state.svelte";
    import { hasAnyPermission } from "$lib/code/module/permissions";
    import SharedFileScopeSwitchPopover from "./_elements/button/SharedFileScopeSwitchPopover.svelte";
    import FileSearchButton from "./_elements/button/FileSearchButton.svelte";
    import CloseIcon from "$lib/component/icons/CloseIcon.svelte";
    import { openEntry } from "./_code/fileBrowserUtil";
    import OpenFileAsCategoryButton from "./_elements/button/OpenFileAsCategoryButton.svelte";
    import { confirmDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte";
    import ArrowLeftIcon from "$lib/component/icons/ArrowLeftIcon.svelte";
    import UndoIcon from "$lib/component/icons/UndoIcon.svelte";

    let {
        meta,
    }: {
        meta: StateMetadata,
    } = $props()

    const stateMeta: StateMetadata = meta || {
        type: "files",
        fileEntriesUrlPath: "/api/v1/folder/file-and-folder-entries",
        pagePath: "/files",
        pageTitle: "Files",
        isArrayOnly: false,
    }

    const filesStateNonce = createFilesState(stateMeta)
    const breadcrumbStateNonce = createBreadcrumbState()

    const pageDataPollingConfig = { idleDelay: 60, delay: 30 }
    let pollingInterval: ReturnType<typeof dynamicInterval> | null = null
    

    onMount(() => {
        window.addEventListener('keydown', handleKeyDown)
        filesState.ui.filePreviewLoader.setScrollContainer(filesState.scroll.container || null)

        const fileViewType = fileViewType_getFromLocalstorage()
        if (fileViewType) filesState.ui.fileViewType = fileViewType

        // Automatically refresh folder entries
        pollingInterval = dynamicInterval(() => {
            reloadCurrentFolder()
        }, () => ((clientState.isIdle ? pageDataPollingConfig.idleDelay : pageDataPollingConfig.delay) * 1000))

        return () => {
            window.removeEventListener('keydown', handleKeyDown)
            pollingInterval?.cancel()
        }
    })

    onDestroy(() => {
        destroyFilesState(filesStateNonce)
        destroyBreadcrumbState(breadcrumbStateNonce)
    })

    const title = $derived(pageTitle(filesState.segments[filesState.segments.length - 1] || (filesState.getIsShared() ? filesState.meta.shareTopLevelFilename : undefined) || stateMeta.pageTitle))
    let lastDataLoadDate: number = unixNow()

    // Load page data when path changes
    explicitEffect(() => [ 
        filesState.path,
    ], () => {
        const effectResult = () => { pollingInterval?.cancel() }

        const newPath = filesState.path
        if (!newPath) return effectResult

        const newMeta = filesState.isSearchOpen 
            ? filesState.search.entries?.find(m => m.path === newPath) || null
            : null
        
        // Mark as searched file only if new file is child
        const isSearchedFile = filesState.isSearchOpen && newMeta && isFile(newMeta) && !filesState.data.fileMeta
        // Mark as searched parent folder if the new path is the searched folder
        const isSearchedParent = filesState.isSearchOpen && filesState.search.searchPath === newPath

        filesState.abort()
        if (!isSearchedFile && !isSearchedParent) filesState.search.clear()

        // Check relationship of the opened file
        const folderMeta = filesState.data.folderMeta
        const pathIsChild = folderMeta ? isPathDirectChildOf(folderMeta.path, newPath) : false
        const pathIsParentFolder = folderMeta ? folderMeta.path === newPath : false

        if (!isSearchedFile) {
            if (pathIsParentFolder || isSearchedParent) {
                filesState.clearOpenState()
            } else if (pathIsChild && filesState.data.fileMeta != null) {
                // Dont clear state of open file, if a file is currently open
                // filesState.clearOpenState()
            } else if (!pathIsChild && !pathIsParentFolder) {
                filesState.clearAllState()
            }
        }

        const shareToken = filesState.getIsShared() ? filesState.meta.shareToken : undefined

        // Do not load page data if navigating back to current parent folder
        // Use existing state
        if (pathIsParentFolder === false && !isSearchedParent) {
            const bodyParams = stateMeta.type === "allShared" ? { getAll: `${sharedFilesPageState.showAll}` } : undefined;

            (newPath === "/" && (stateMeta.isArrayOnly) 
                ? loadPageData(newPath, { urlPath: stateMeta.fileEntriesUrlPath, fileDataType: "array",  shareToken: shareToken,            bodyParams })
                : loadPageData(newPath, { urlPath: stateMeta.fileEntriesUrlPath, fileDataType: "object", loadParentFolder: !isSearchedFile, shareToken: shareToken })
            ).then(() => {
                recoverScrollPosition()
            })

            if (!pathIsChild) pollingInterval?.reset()
        } else { queueMicrotask(recoverScrollPosition) }

        return effectResult
    })

    // Run when user stops being idle
    explicitEffect(() => [
        clientState.isIdle 
    ], () => {
        if (clientState.isIdle === false) {
            const now = unixNow()
            const elapsed = now - (lastDataLoadDate ?? 0)

            // Fetch page data if last fetch was too long ago
            if (elapsed > pageDataPollingConfig.delay) {
                pollingInterval?.reset()
                reloadCurrentFolder()
            }
        }
    })

    // Unselect entry when path changes
    explicitEffect(() => [ 
        filesState.selectedEntries.singlePath,
        filesState.path
    ], () => {
        const selected = filesState.selectedEntries.singlePath
        const current = filesState.path || "/"

        // if there’s a selection but it isn’t under the current directory, reset it
        if (selected && !selected.startsWith(appendTrailingSlash(current)) && selected !== current) {
            filesState.selectedEntries.reset()
        }
    })

    beforeNavigate((nav) => {
        if (filesState.metaLoading) {
            nav.cancel()
            return
        }

        if (textFileViewerState.isFileSavable) {
            confirmDialogState.show({
                title: "Unsaved changes",
                message: "This file has unsaved changes. Are you sure you want to exit?",
                cancelText: "Cancel",
                confirmText: "Yes"
            })?.then((r) => {
                if (!r) return

                if (r === true) {
                    textFileViewerState.isFileSavable = false
                    
                    const destination = nav.to?.url.href
                    if (destination) {
                        goto(destination)
                    }
                }
            })

            nav.cancel()
            return
        }

        saveScrollPosition()
    })

    function cancelSearch() {
        const searched = filesState.search.searchPath
        if (searched && filesState.path !== searched) {
            openEntry(searched)
        }

        filesState.search.clear()
    }
    function closeSearchedFile() {
        if (!filesState.search.searchPath) return
        openEntry(filesState.search.searchPath)
    }

    function onScroll(e: any) {
        filesState.ui.fileContextMenuPopoverOpen = false
    }
</script>


<svelte:head>
    <title>{title}</title>
</svelte:head>

<!-- 5.5, lg 6.5 -->
<div class="page">
    <div class="w-full flex h-full min-h-0">
        <div class="w-full {filesState.ui.detailsOpen ? 'w-[calc(100%-20rem)]' : 'w-full'} lg:w-full flex flex-col h-full">
            <!-- Header -->
            <div class="w-full shrink-0 lg:mt-2   overflow-y-auto custom-scrollbar lg:gutter-stable-both">
                <div class="
                    w-full flex flex-col items-center justify-between gap-2 overflow-hidden bg-surface rounded-b-lg lg:rounded-lg
                    {auth.authenticated ? 'pb-2' : 'py-2'}
                    lg:py-2 px-2
                ">
                    <!-- Top row -->
                    <div bind:offsetWidth={breadcrumbState.containerWidth} class="w-full flex items-center">
                        <Breadcrumbs></Breadcrumbs>                    
                    </div>

                    <!-- Lower row -->
                    <div class="w-full h-[2.5rem] flex items-center justify-between">
                        <!-- Left buttons -->
                        <div class="h-full flex items-center gap-2">
                            {#if filesState.isSearchOpen && filesState.search.searchPath !== filesState.path}
                                <button on:click={closeSearchedFile} title="Back to search" class="file-action-button"><ArrowLeftIcon /></button>
                            {/if}
                            {#if filesState.isSearchOpen}
                                <button on:click={cancelSearch} title="Close search" class="file-action-button"><CloseIcon /></button>
                            {/if}

                            <!-- Parent folder options -->
                            {#if filesState.data.folderMeta && filesState.isFileListOpen &&
                                    (filesState.selectedEntries.hasSelected === false || filesState.selectedEntries.isCurrentPathSelected)
                            }
                                {#if filesState.meta.type !== "shared" && !filesState.isSearchOpen}
                                    <button on:click={handleNewFolder} title="Create a new folder inside this folder" class="file-action-button"><NewFolderIcon /></button>
                                    <button on:click={handleNewFile} title="Create a new blank file inside this folder" class="file-action-button"><NewFileIcon /></button>
                                {/if}
                                {#if (filesState.path !== "/" || filesState.isShared) && !filesState.isSearchOpen}
                                    <button on:click={option_downloadSelectedFiles} title="Download this folder" class="file-action-button"><DownloadIcon /></button>
                                {/if}
                            <!-- Selected child file options -->
                            {:else if filesState.selectedEntries.hasSelected}
                                <button on:click={option_downloadSelectedFiles} title="Download the selected files" class="file-action-button"><DownloadIcon /></button>
                                {#if filesState.meta.type === "files"} 
                                    <button on:click={option_deleteSelectedFiles} title="Delete the selected files" class="file-action-button"><TrashIcon /></button>
                                {/if}
                                {#if filesState.data.fileMeta == null && filesState.meta.type === "files" && !filesState.isSearchOpen}
                                    <button on:click={option_moveSelectedFiles} title="Move the selected file{letterS(filesState.selectedEntries.count)}" class="file-action-button"><MoveIcon /></button>
                                {/if}
                            {/if}
                        </div>

                        <!-- Right buttons -->
                        <div class="h-full flex items-center gap-2">
                            {#if filesState.meta.type === "allShared" && hasAnyPermission(["MANAGE_ALL_FILE_SHARES"])}
                                <SharedFileScopeSwitchPopover></SharedFileScopeSwitchPopover>
                            {/if}

                            {#if filesState.isFileListOpen}
                                <FileSortingButton></FileSortingButton>
                            
                                <FileViewTypeButton></FileViewTypeButton>
                            {/if}

                            {#if uiState.isDesktop && filesState.isFileListOpen && auth.authenticated && !filesState.isSearchOpen}
                                <NewFileButton />
                            {/if}

                            {#if uiState.isDesktop && filesState.isFileListOpen && !filesState.isSearchOpen && (filesState.meta.type === "files" || filesState.isShared)}
                                <FileSearchButton />
                            {/if}
                            

                            {#if textFileViewerState.isFileSavable}
                                <button on:click={option_cancelFileEdit} title="Cancel file editing" class="file-action-button"><UndoIcon /></button>
                            
                                <button on:click={() => { saveEditedFile() }} class="h-full flex items-center justify-center gap-2 bg-surface-content-button rounded-md px-4">
                                    <div class="h-[1.2rem]">
                                        <SaveIcon />
                                    </div>
                                    <p>Save</p>
                                </button>
                            {/if}

                            {#if filesState.currentFile.displayedFileCategory}
                                <OpenFileAsCategoryButton location="bar" />
                            {/if}

                            {#if uiState.isDesktop}
                                <FileDetailsButton></FileDetailsButton>
                            {/if}
                        </div>
                    </div>
                </div>
            </div>

            <!-- Files -->
            <div class="h-[calc(100%-6.5rem)] lg:h-[calc(100%-7.5rem)] w-full my-2 relative">
                {#if filesState.data.entries != null && (filesState.data.folderMeta || stateMeta.isArrayOnly)}
                    <div 
                        bind:this={filesState.scroll.container} 
                        on:scroll|passive|self={onScroll}
                        class="h-full overflow-y-auto overflow-x-hidden custom-scrollbar lg:gutter-stable-both relative
                            {filesState.data.fileMeta || filesState.metaLoading ? '' : ''}
                        ">
                        <FileBrowser />
                    </div>
                {/if}

                {#key filesState.data.fileMeta}
                    {#if filesState.data.fileMeta}
                        <div class="h-full absolute top-0 left-0 center">
                            <FileViewer />
                        </div>
                    {/if}
                {/key}
                
                {#if filesState.metaLoading}
                    <div class="absolute w-full h-full top-0 left-0 flex items-center justify-center">
                        <Loader></Loader>
                    </div>
                {:else if !filesState.data.currentMeta && !stateMeta.isArrayOnly}
                    <div class="center">
                        <p class="text-xl">Could not open this file.</p>
                    </div>
                {/if}
            </div>
        </div>


        <div class="contents">
            <!-- File info sidebar -->
            {#if filesState.ui.detailsOpen}
                <div on:click={filesState.unselect} class="fixed z-10 top-0 left-0 w-full h-full overflow-hidden flex justify-end pointer-events-none lg:contents min-h-0">
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

{#if filesState.isFolderOpen}
    <FileDropzone on:filesDropped={event_filesDropped}></FileDropzone>
{/if}

<style lang="postcss">
    :global(.file-action-button) {
        @apply h-full aspect-square flex items-center justify-center rounded-lg p-2 bg-surface-content-button;
    }
</style>
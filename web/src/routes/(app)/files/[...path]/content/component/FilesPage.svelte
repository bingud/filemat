<script lang="ts">
	import FolderIcon from '$lib/component/icons/FolderIcon.svelte';
    import { beforeNavigate, goto } from "$app/navigation"
    import { deleteFiles, downloadFilesAsZip, getFileData, getFileDataWithDifferentPath, getFileLastModifiedDate, moveFile, moveMultipleFiles, startTusUpload, type FileData } from "$lib/code/module/files"
    import { addSuffix, dynamicInterval, explicitEffect, filenameFromPath, handleErr, isFolder, keysOf, letterS, pageTitle, parentFromPath, resolvePath, unixNow, unixNowMillis, valuesOf } from "$lib/code/util/codeUtil.svelte"
    import Loader from "$lib/component/Loader.svelte"
    import { onDestroy, onMount } from "svelte"
    import { breadcrumbState, createBreadcrumbState, destroyBreadcrumbState } from "../../content/code/breadcrumbState.svelte"
    import Breadcrumbs from './element/Breadcrumbs.svelte';
    import DetailsSidebar from './element/DetailsSidebar.svelte';
    import { createFilesState, destroyFilesState, filesState } from "$lib/code/stateObjects/filesState.svelte"
    import { fade, fly } from "svelte/transition"
    import { linear } from "svelte/easing"
    import { uiState } from "$lib/code/stateObjects/uiState.svelte"
    import InfoIcon from "$lib/component/icons/InfoIcon.svelte"
    import { Popover } from "$lib/component/bits-ui-wrapper"
    import FileIcon from "$lib/component/icons/FileIcon.svelte"
    import PlusIcon from "$lib/component/icons/PlusIcon.svelte"
    import { formData, handleException, safeFetch } from "$lib/code/util/codeUtil.svelte"    
    import { uploadWithTus } from "$lib/code/module/files"
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import { filePermissionMeta } from "$lib/code/data/permissions";
    import NewFolderIcon from '$lib/component/icons/NewFolderIcon.svelte';
    import NewFileIcon from '$lib/component/icons/NewFileIcon.svelte';
    import TrashIcon from '$lib/component/icons/TrashIcon.svelte';
    import { confirmDialogState, folderSelectorState } from '$lib/code/stateObjects/subState/utilStates.svelte';
    import DownloadIcon from '$lib/component/icons/DownloadIcon.svelte';
    import MoveIcon from '$lib/component/icons/MoveIcon.svelte';
    import FileDropzone from './element/FileDropzone.svelte';
    import { clientState } from '$lib/code/stateObjects/clientState.svelte';
    import { toast } from '@jill64/svelte-toast';
    import { isDialogOpen } from '$lib/code/util/stateUtils';
    import FileBrowser from './element/FileBrowser/FileBrowser.svelte';
    import FileViewer from './element/FileViewer.svelte';
    import type { FullFileMetadata } from '$lib/code/auth/types';

    let {
        overrideTopLevelFolderUrlPath = null,
        requireFolderMeta = true,
    }: {
        overrideTopLevelFolderUrlPath?: string | null,
        requireFolderMeta?: boolean,
    } = $props()

    const filesStateNonce = createFilesState()
    const breadcrumbStateNonce = createBreadcrumbState()
    
    onMount(() => {
        window.addEventListener('keydown', handleKeyDown)

        return () => {
            window.removeEventListener('keydown', handleKeyDown)
        };
    })

    onDestroy(() => {
        destroyFilesState(filesStateNonce)
        destroyBreadcrumbState(breadcrumbStateNonce)
    })

    const title = $derived(pageTitle(filesState.segments[filesState.segments.length - 1] || "Files"))
    let newButtonPopoverOpen = $state(false)
    
    function handleKeyDown(event: KeyboardEvent) {
        if (isDialogOpen()) return

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

        const currentPath = filesState.path === '/' ? '' : filesState.path
        const targetPath = `${currentPath}/${folderName}`
        console.log(`targetPath`, targetPath)
        console.log(`currentPath`, currentPath)

        const response = await safeFetch('/api/v1/folder/create', {
            method: 'POST',
            body: formData({ path: targetPath })
        })
        if (response.failed) {
            handleErr({
                description: "Failed to create folder",
                notification: "Failed to create folder.",
            })
            return
        }

        const status = response.code
        const json = response.json()

        if (status.notFound) {
            handleErr({
                description: "Parent folder not found when creating folder",
                notification: "This folder was not found."
            })
            return
        } else if (status.failed) {
            handleErr({
                description: "Failed to create folder.",
                notification: json.message || "Failed to create folder.",
                isServerDown: status.serverDown
            })
            return
        }

        filesState.data.entries?.push({
            path: targetPath,
            filename: folderName,
            modifiedDate: unixNowMillis(),
            createdDate: unixNowMillis(),
            fileType: "FOLDER",
            size: 0,
            permissions: keysOf(filePermissionMeta),
            isExecutable: true,
            isWritable: true,
        })
    }
    
    function handleNewFile() {
        newButtonPopoverOpen = false
    }

    let lastDataLoadDate: number = unixNow()
    const pageDataPollingConfig = { idleDelay: 60, delay: 30 }
    let pollingInterval: ReturnType<typeof dynamicInterval> | null = null

    // Load page data when path changes
    explicitEffect(() => {
        const path = filesState.path
        
        if (path) {
            filesState.abort()
            filesState.clearState()

            filesState.data.content = null

            if (path === "/") {
                filesState.scroll.pathPositions = {}
            }

            (path === "/" && overrideTopLevelFolderUrlPath 
                ? loadPageData(path, { overrideDataUrlPath: overrideTopLevelFolderUrlPath, dataType: "array" })
                : loadPageData(path, { dataType: "object" })
            ).then(() => {
                recoverScrollPosition()
            })
        }

        pollingInterval = dynamicInterval(() => {
            const meta = filesState.data.meta
            if (meta && (meta.fileType === "FOLDER" || (meta.fileType === "FOLDER_LINK" && appState.followSymlinks))) {
                if (filesState.path === path) {
                    reloadPageData()
                }
            }
        }, () => ((clientState.isIdle ? pageDataPollingConfig.idleDelay : pageDataPollingConfig.delay) * 1000))

        return () => { pollingInterval!.cancel() }
    }, () => [ filesState.path ])

    // Run when user stops being idle
    explicitEffect(() => {
        if (clientState.isIdle === false) {
            const now = unixNow()
            const elapsed = now - (lastDataLoadDate ?? 0)

            // Fetch page data if last fetch was too long ago
            if (elapsed > pageDataPollingConfig.delay) {
                pollingInterval?.reset()
                reloadPageData()
            }
        }
    }, () => [ clientState.isIdle ])

    // Unselect entry when path changes
    explicitEffect(() => {
        const selected = filesState.selectedEntries.single
        const current = filesState.path || "/"
        // if there’s a selection but it isn’t under the current directory, reset it
        if (selected && !selected.startsWith(current + (current === "/" ? "" : "/"))) {
            filesState.selectedEntries.reset()
        }
    }, () => [ filesState.selectedEntries.single, filesState.path ])


    beforeNavigate(() => {
        saveScrollPosition()
    })

    /**
     * Reload the current folder data.
     * If modification date of folder has changed, fetch new data
     */
    async function reloadPageData() {
        if (!filesState.path) return
        const meta = filesState.data.meta
        if (!meta || !isFolder(meta)) return

        const modifiedDate = meta.modifiedDate
        const actualModifiedDate = await getFileLastModifiedDate(filesState.path)

        // Folder modification date has not changed
        // Local folder is up to date
        if (modifiedDate === actualModifiedDate) return

        await loadPageData(filesState.path, { silent: true, dataType: "object" })
    }

    async function loadPageData(filePath: string, options: { silent?: boolean, isRefresh?: boolean, overrideDataUrlPath?: string, dataType: "object" | "array" }) {
        filesState.lastFilePathLoaded = filePath
        if (!options.silent) filesState.metaLoading = true

        const result = (options.overrideDataUrlPath
            ? await getFileDataWithDifferentPath(filePath, options.overrideDataUrlPath, filesState.abortController?.signal)
            : await getFileData(filePath, filesState.abortController?.signal, {})
        )

        if (result.notFound) {
            if (filesState.path === "/") return
            if (options.isRefresh) {
                toast.plain("This folder is not available anymore.")
            } else {
                toast.error("This folder was not found.")
            }

            await goto(`/files/${parentFromPath(filesState.path)}`)
        }
        if (result.isUnsuccessful) return
        const dataResult = result.value

        if (filesState.lastFilePathLoaded !== filePath) return
        
        filesState.metaLoading = false
        if (!result) return

        if (options.dataType === "object") {
            const data = dataResult as FileData
            filesState.data.meta = data.meta!

            if (data.meta.fileType === "FOLDER") {
                filesState.data.entries = data.entries || null
            } else if (data.meta.fileType === "FOLDER_LINK") {
                if (appState.followSymlinks) {
                    data.entries?.forEach((entry) => {
                        const linkPath = `${addSuffix(filePath, "/")}${entry.filename!}`
                        entry.path = linkPath
                    })
                    filesState.data.entries = data.entries || null
                }
            }

            // If no entry is selected and this is a folder, select the current folder
            if (filesState.selectedEntries.single === null && data.meta.fileType === "FOLDER") {
                filesState.selectedEntries.list = [data.meta.path]
            }
        } else {
            const data = dataResult as FullFileMetadata[]
            filesState.data.entries = data
        }
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

    function option_deleteSelectedFiles() {
        const selected = filesState.selectedEntries.list
        if (!selected.length) return
        
        confirmDialogState.show({
            title: "Delete File",
            message: `Are you sure you want to delete ${filesState.selectedEntries.count} selected file${filesState.selectedEntries.count > 1 ? 's':''}? This cannot be undone.`,
            confirmText: "Delete",
            cancelText: "Cancel"
        })?.then((confirmed: boolean) => {
            if (!confirmed) return
            if (!filesState.selectedEntries.meta) return

            const list = valuesOf(filesState.selectedEntries.meta).filter(v => !!v)
            deleteFiles(list)
        })
    }

    function option_downloadSelectedFiles() {
        const selected = filesState.selectedEntries.list
        if (!selected || !selected.length) return
        downloadFilesAsZip(selected)
    }

    async function option_moveSelectedFiles() {
        if (!filesState.selectedEntries.hasSelected || !filesState.data.meta) return

        const newParentPath = await folderSelectorState.show!({
            title: "Choose the target folder.",
            initialSelection: filesState.path
        })
        if (!newParentPath) return

        if (filesState.selectedEntries.hasMultiple) {
            moveMultipleFiles(newParentPath, filesState.selectedEntries.list)
        } else {
            const selected = filesState.selectedEntries.single!
            const filename = filenameFromPath(selected)
            moveFile(selected, resolvePath(newParentPath, filename))
        }
    }


    function event_filesDropped(e: CustomEvent<{ files: FileList }>) {
        console.log(`file droippe`, e.detail.files)
        const files = Array.from(e.detail.files)
        
        files.forEach(file => {
            startTusUpload(file)
        })
    }

</script>


<svelte:head>
    <title>{title}</title>
</svelte:head>


<div class="page">
    <div class="w-full flex h-full min-h-0">
        <div bind:this={filesState.scroll.container} class="w-full {filesState.ui.detailsOpen ? 'w-[calc(100%-20rem)]' : 'w-full'} md:w-full flex flex-col h-full overflow-y-auto overflow-x-hidden custom-scrollbar md:gutter-stable-both">
            <!-- Header -->
            <div class="w-full shrink-0 flex flex-col px-2 items-center justify-between overflow-hidden rounded-lg py-2 bg-neutral-200 dark:bg-neutral-900 mt-2 gap-2">
                <!-- Top row -->
                <div bind:offsetWidth={breadcrumbState.containerWidth} class="w-full flex items-center">
                    <Breadcrumbs></Breadcrumbs>                    
                </div>

                <!-- Lower row -->
                <div class="w-full h-[2.5rem] flex items-center justify-between">
                    <!-- Left buttons -->
                    <div class="h-full flex items-center gap-2 py-[0.2rem]">
                        {#if filesState.selectedEntries.hasSelected === false}
                            <button on:click={handleNewFolder} title="Create a new folder inside this folder." class="action-button"><NewFolderIcon /></button>
                            <button on:click={handleNewFile} title="Create a new blank file inside this folder." class="action-button"><NewFileIcon /></button>
                        {:else}
                            <button on:click={option_downloadSelectedFiles} title="Download the selected files." class="action-button"><DownloadIcon /></button>
                            <button on:click={option_deleteSelectedFiles} title="Delete the selected files." class="action-button"><TrashIcon /></button>
                            <button on:click={option_moveSelectedFiles} title="Move the selected file{letterS(filesState.selectedEntries.count)}." class="action-button"><MoveIcon /></button>
                        {/if}
                    </div>

                    <!-- Right buttons -->
                    <div class="h-full flex items-center gap-2">
                        <Popover.Root bind:open={newButtonPopoverOpen}>
                            <Popover.Trigger title="Create or upload a file or folder." class="h-full flex items-center justify-center" hidden={filesState.data.meta?.fileType.startsWith("FILE")}>
                                <div class="h-full flex items-center justify-center gap-2 bg-neutral-300 dark:bg-neutral-800 hover:bg-neutral-300 dark:hover:bg-neutral-700 rounded-md px-4">
                                    <div class="h-[1.2rem]">
                                        <PlusIcon></PlusIcon>
                                    </div>
                                    <p>New</p>
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

                        <button title="Toggle the file details sidebar." on:click={() => { filesState.ui.toggleSidebar() }} class="action-button">
                            <InfoIcon />
                        </button>
                    </div>
                </div>
            </div>

            <!-- Files -->
            <div class="h-[calc(100%-3rem)] w-full">
                {#if !filesState.metaLoading && (!requireFolderMeta || filesState.data.meta)}
                    {#if filesState.data.sortedEntries && (!filesState.data.meta || filesState.data.meta.fileType === "FOLDER" || (filesState.data.meta.fileType === "FOLDER_LINK" && appState.followSymlinks))}
                        <FileBrowser />
                    {:else}
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

<FileDropzone on:filesDropped={event_filesDropped}></FileDropzone>


<style>
    @import "/src/app.css" reference;


    .action-button {
        @apply h-full aspect-square flex items-center justify-center rounded-lg dark:bg-neutral-800 dark:hover:bg-neutral-700 p-2;
    }
</style>
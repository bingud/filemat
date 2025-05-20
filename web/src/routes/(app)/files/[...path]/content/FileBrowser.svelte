<script lang="ts">
    import { dev } from "$app/environment";
    import { goto } from "$app/navigation";
    import type { FileMetadata, FullFileMetadata } from "$lib/code/auth/types";
    import { formatBytesRounded, formatUnixMillis, safeFetch, handleError, handleErrorResponse, formData, addSuffix, filenameFromPath, parentFromPath, appendFilename, getFileExtension } from "$lib/code/util/codeUtil.svelte";
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import FileIcon from "$lib/component/icons/FileIcon.svelte";
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
    import ThreeDotsIcon from "$lib/component/icons/ThreeDotsIcon.svelte";
    import InfoIcon from "$lib/component/icons/InfoIcon.svelte";
    import { onMount } from "svelte";
    import { filesState } from "../../../../../lib/code/stateObjects/filesState.svelte";
    import TrashIcon from "$lib/component/icons/TrashIcon.svelte";
	import ConfirmDialog from '$lib/component/ConfirmDialog.svelte';
    import FolderArrow from "$lib/component/icons/FolderArrow.svelte";
    import FileArrow from "$lib/component/icons/FileArrow.svelte";
    import DownloadIcon from "$lib/component/icons/DownloadIcon.svelte";
    import UploadPanel from "./elements/UploadPanel.svelte";
    import { uploadState } from "$lib/code/stateObjects/subState/uploadState.svelte";
    import { deleteFiles, moveFile } from "$lib/code/module/files";
    import { confirmDialogState, folderSelectorState } from "$lib/code/stateObjects/subState/utilStates.svelte";
    import NewTabIcon from "$lib/component/icons/NewTabIcon.svelte";
    import MoveIcon from "$lib/component/icons/MoveIcon.svelte";
    import FolderTreeSelector from "./elements/FolderTreeSelector.svelte";
    import { fileCategories, isSupportedImageFile } from "$lib/code/data/files";

    // Entry menu popup
    let entryMenuButton: HTMLElement | null = $state(null)
    let menuEntry: FullFileMetadata | null = $state(null)
    let entryMenuPopoverOpen = $state(dev)

    let entryMenuYPos: number | null = $state(null)
    let entryMenuXPos: number | null = $state(null)
    
    onMount(() => {
        // Set the selected entry path
        setSelectedEntryPath()

        window.addEventListener('keydown', handleKeyDown)
        
        return () => {
            // Clean up listener when component unmounts
            window.removeEventListener('keydown', handleKeyDown)
        }
    })

    // Function to scroll selected entry into view
    function scrollSelectedEntryIntoView() {
        setTimeout(() => {
            if (filesState.selectedEntries.single) {
                const selector = `[data-entry-path="${filesState.selectedEntries.single.replace(/"/g, '\\"')}"]`;
                const element = document.querySelector(selector);
                if (element) {
                    element.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
                }
            }
        }, 10)
    }

    function setSelectedEntryPath() {
        const selectedEntryPath = filesState.selectedEntries.selectedPositions.getChild(filesState.path)
        if (!selectedEntryPath) return

        const filename = filesState.path === "/" ? `${selectedEntryPath}` : `${filesState.path}/${selectedEntryPath}`
        
        if (filesState.data.entries?.some(v => v.path === filename)) {
            filesState.selectedEntries.list = [filename]
            // Scroll to the selected entry after a small delay to ensure DOM is updated
            scrollSelectedEntryIntoView()
        }
    }

    function handleKeyDown(event: KeyboardEvent) {
        // Check if Delete key was pressed
        if (event.key === 'Delete' && !event.ctrlKey && !event.altKey && !event.metaKey) {
            // Check if we have a selected entry
            if (filesState.selectedEntries.single && filesState.data.entries) {
                // Find the selected entry in the entries list
                const selectedEntry = filesState.data.entries.find(e => e.path === filesState.selectedEntries.single);
                if (selectedEntry) {
                    // Delete the selected entry
                    option_delete(selectedEntry);
                }
            }
        } 

        if (event.key === "Enter") {
            if (filesState.selectedEntries.singleMeta) {
                entryOnClick(filesState.selectedEntries.singleMeta)
            }
        }
        
        // Handle Up and Down arrow keys for entry navigation
        if ((event.key === 'ArrowUp' || event.key === 'ArrowDown') && 
                 !event.ctrlKey && !event.altKey && !event.metaKey && 
                 filesState.data.sortedEntries?.length) {
            
            event.preventDefault();
            const entries = filesState.data.sortedEntries;
            const currentPath = filesState.selectedEntries.single;
            
            // Find the index of currently selected entry
            let currentIndex = -1;
            if (currentPath) {
                currentIndex = entries.findIndex(e => e.path === currentPath);
            }
            
            let newIndex: number;
            
            if (event.key === 'ArrowUp') {
                if (currentIndex === -1) {
                    // If no entry is selected, select the bottom entry
                    newIndex = entries.length - 1;
                } else {
                    // Move up one entry, or wrap to bottom
                    newIndex = (currentIndex - 1 + entries.length) % entries.length;
                }
            } else { // ArrowDown
                if (currentIndex === -1) {
                    // If no entry is selected, select the top entry
                    newIndex = 0;
                } else {
                    // Move down one entry, or wrap to top
                    newIndex = (currentIndex + 1) % entries.length;
                }
            }
            
            // Update selection
            const newEntry = entries[newIndex];
            filesState.selectedEntries.selectedPositions.set(newEntry.path, true);
            filesState.selectedEntries.single = newEntry.path;
            
            // Scroll selected entry into view
            scrollSelectedEntryIntoView()
        }
    }

    /**
     * onClick for file entry
     */
    function entryOnClick(entry: FileMetadata) {
        if (filesState.selectedEntries.single === entry.path) {
            openEntry(entry.path)
        } else {
            filesState.selectedEntries.setSelected(entry.path)
            // Scroll selected entry into view
            scrollSelectedEntryIntoView()
        }
    }

    /**
     * onClick for entry menu
     */
    function entryMenuOnClick(button: HTMLButtonElement, entry: FullFileMetadata) {
        entryMenuButton = button
        menuEntry = entry
        entryMenuPopoverOpen = true
    }

    function openEntry(path: string) {
        goto(`/files${path}`)
    }
    
    function entryMenuPopoverOnOpenChange(open: boolean) {
        if (!open) {
            entryMenuButton = null
            menuEntry = null
            entryMenuXPos = null
            entryMenuYPos = null
        }
    }

    function option_details(entry: FileMetadata) {
        filesState.selectedEntries.setSelected(entry.path)
        filesState.ui.detailsOpen = true
        closeEntryPopover()
    }

    function option_delete(entry: FileMetadata) {
        // Show confirmation dialog and handle the result
        confirmDialogState.show({
            title: "Delete File",
            message: `Are you sure you want to delete "${entry.filename!}"? This cannot be undone.`,
            confirmText: "Delete",
            cancelText: "Cancel"
        })?.then((confirmed: boolean) => {
            if (confirmed) {
                deleteFiles([entry])
            }
        })
        
        closeEntryPopover()
    }

    async function option_move(entry: FileMetadata) {
        const selection = await folderSelectorState.show!({
            title: "Choose the target folder.",
            initialSelection: parentFromPath(entry.path)
        })
        if (!selection) return

        const newPath = appendFilename(selection, entry.filename!)
        await moveFile(entry.path, newPath)
    }

    function closeEntryPopover() {
        entryMenuButton = null
        menuEntry = null
    }

    function onClickSelectCheckbox(path: string) {
        const isSelected = filesState.selectedEntries.list.includes(path)
        if (isSelected) {
            filesState.selectedEntries.unselect(path)
        } else {
            filesState.selectedEntries.addSelected(path)
        }
    }

    function entryOnContextMenu(e: MouseEvent, entry: FullFileMetadata) {
        e.preventDefault()

        entryMenuPopoverOpen = false
        entryMenuXPos = null
        entryMenuYPos = null

        setTimeout(() => {
            entryMenuXPos = e.clientX
            entryMenuYPos = e.clientY
            entryMenuPopoverOpen = true
            menuEntry = entry
        })
    }
</script>

<style>
    @import "/src/app.css" reference;
    
    .file-grid {
        @apply grid grid-cols-[minmax(0,1fr)_2.5rem] sm:grid-cols-[minmax(0,1fr)_9.6rem_2.5rem] md:grid-cols-[minmax(0,1fr)_9.6rem_4.2rem_2.5rem];
    }
</style>

{#if filesState.data.sortedEntries}
    <div on:click|stopPropagation class="w-full h-fit overflow-x-hidden">
        <!-- Header row (separate grid) -->
        <div class="file-grid gap-x-2 px-4 py-2 font-medium text-neutral-700 dark:text-neutral-400">
            <div class="truncate text-left">
                Name
            </div>
            <div class="whitespace-nowrap max-md:hidden text-right">
                Last Modified
            </div>
            <div class="whitespace-nowrap max-sm:hidden text-right">
                Size
            </div>
            <div class="text-center"></div>
        </div>

        <!-- Each entry is a grid item -->
        {#each filesState.data.sortedEntries as entry}
            {@const selected = filesState.selectedEntries.list.includes(entry.path)}

            <div 
                on:click={() => entryOnClick(entry)}
                on:contextmenu={(e) => { entryOnContextMenu(e, entry) }}
                data-entry-path={entry.path}
                class="file-grid h-[2.5rem] gap-x-2 items-center cursor-pointer select-none group {selected ? 'bg-blue-200 dark:bg-sky-950' : 'hover:bg-neutral-200 dark:hover:bg-neutral-800'}"
            >
                <!-- Filename + Icon -->
                <div class="h-full flex items-center overflow-hidden">
                    {#key selected}
                        <div on:click|stopPropagation|preventDefault={() => { onClickSelectCheckbox(entry.path) }} class="h-full flex items-center justify-center pl-2 pr-1">
                            <input checked={selected} class="opacity-0 checked:opacity-100 group-hover:opacity-100" type="checkbox">
                        </div>
                    {/key}

                    <div class="h-full flex items-center gap-2 min-w-0 overflow-hidden whitespace-nowrap text-ellipsis">
                        <div class="h-6 aspect-square fill-neutral-500 stroke-neutral-500 flex-shrink-0 flex items-center justify-center py-[0.1rem]">
                            {#key entry.filename}
                                {@const format = fileCategories[getFileExtension(entry.filename!)]}

                                {#if format === "image"}
                                    <img loading="lazy" alt="" src="/api/v1/file/image-thumbnail?size=48&path={entry.path}" class="size-full w-auto">
                                {:else if format === "video"}
                                    <img loading="lazy" alt="" src="/api/v1/file/video-preview?size=48&path={entry.path}" class="size-full w-auto">
                                {:else}
                                    {#if entry.fileType === "FILE"}
                                        <FileIcon />
                                    {:else if entry.fileType === "FILE_LINK"}
                                        <FileArrow />
                                    {:else if entry.fileType === "FOLDER"}
                                        <FolderIcon />
                                    {:else if entry.fileType === "FOLDER_LINK"}
                                        <FolderArrow />
                                    {/if}
                                {/if}
                            {/key}
                        </div>
                        <p class="truncate py-1">
                            {entry.filename!}
                        </p>
                    </div>
                </div>

                <!-- Last Modified -->
                <div class="h-full text-right whitespace-nowrap max-sm:hidden flex items-center justify-end opacity-70 py-1">
                    {formatUnixMillis(entry.modifiedDate)}
                </div>

                <!-- Size -->
                <div class="h-full text-right whitespace-nowrap max-md:hidden flex items-center justify-end py-1">
                    {formatBytesRounded(entry.size)}
                </div>

                <!-- Menu button (stopPropagation) -->
                <div class="h-full text-center py-1 pr-1">
                    <button
                        on:click={(e) => { if (filesState.selectedEntries.list.includes(entry.path)) { e.stopPropagation() }; entryMenuOnClick(e.currentTarget, entry) }}
                        class="h-full aspect-square flex items-center justify-center rounded-full p-2 hover:bg-neutral-400/30 dark:hover:bg-neutral-600/50 fill-neutral-700 dark:fill-neutral-500"
                    >
                        <ThreeDotsIcon />
                    </button>
                </div>
            </div>
        {/each}
    </div>


    <!-- Entry context menu popover -->
    {#if entryMenuButton && menuEntry}
        {#key entryMenuButton || menuEntry}
            <div class="z-50 relative">
                <Popover.Root bind:open={entryMenuPopoverOpen} onOpenChange={entryMenuPopoverOnOpenChange}>
                    <Popover.Content onInteractOutside={() => { entryMenuPopoverOpen = false }} customAnchor={entryMenuButton} align="start" >
                        <div class="w-[14rem] max-w-full max-h-full rounded-lg bg-neutral-250 dark:bg-neutral-800 py-2 flex flex-col z-50">
                            <a href={addSuffix(filesState.data.contentUrl, "/") + `${menuEntry.filename!}`} target="_blank" class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                                <div class="size-5 flex-shrink-0">
                                    <NewTabIcon />
                                </div>
                                <span>Open in new tab</span>
                            </a>

                            <a download href={addSuffix(filesState.data.contentUrl, "/") + `${menuEntry.filename!}`} target="_blank" class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                                <div class="size-5 flex-shrink-0">
                                    <DownloadIcon />
                                </div>
                                <span>Download</span>
                            </a>

                            {#if menuEntry.permissions.includes("MOVE")}
                                <button on:click={() => { option_move(menuEntry!) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                                    <div class="size-5 flex-shrink-0">
                                        <MoveIcon />
                                    </div>
                                    <span>Move</span>
                                </button>
                            {/if}

                            {#if menuEntry.permissions.includes("DELETE")}
                                <button on:click={() => { option_delete(menuEntry!) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                                    <div class="size-5 flex-shrink-0">
                                        <TrashIcon />
                                    </div>
                                    <span>Delete</span>
                                </button>
                            {/if}

                            <button on:click={() => { option_details(menuEntry!) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                                <div class="size-5 flex-shrink-0">
                                    <InfoIcon />
                                </div>
                                <span>Details</span>
                            </button>
                            <hr class="basic-hr my-2">
                            <p class="px-4 truncate opacity-70">File: {menuEntry.filename!}</p>
                        </div>
                    </Popover.Content>
                </Popover.Root>
            </div>
        {/key}
    {/if}

    <!-- Entry context menu floating element -->
    {#if menuEntry && entryMenuXPos != null && entryMenuYPos != null}
        <div bind:this={entryMenuButton} class="size-0 fixed z-10" style="top: {entryMenuYPos}px; left: {entryMenuXPos}px"></div>
    {/if}
{:else}
    <div class="center">
        <p>No folder is open.</p>
    </div>
{/if}

{#if uploadState.count > 0 && uploadState.panelOpen}
    <div class="fixed z-10 h-full w-full top-0 left-0 pb-4 pr-[calc(1rem+var(--spacing-details-sidebar))] pointer-events-none  flex items-end justify-end">
        <div class="w-[36rem] h-fit max-h-full max-w-full pointer-events-auto">
            <UploadPanel></UploadPanel>
        </div>
    </div>
{/if}

<FolderTreeSelector></FolderTreeSelector>
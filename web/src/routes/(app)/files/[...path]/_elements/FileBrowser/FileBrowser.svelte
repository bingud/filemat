<script lang="ts">
    import { dev } from "$app/environment";
    import { goto } from "$app/navigation";
    import type { FileMetadata, FullFileMetadata } from "$lib/code/auth/types";
    import { addSuffix, filenameFromPath, parentFromPath, appendFilename, resolvePath, isFolder } from "$lib/code/util/codeUtil.svelte";
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import InfoIcon from "$lib/component/icons/InfoIcon.svelte";
    import { onMount } from "svelte";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import TrashIcon from "$lib/component/icons/TrashIcon.svelte";
    import DownloadIcon from "$lib/component/icons/DownloadIcon.svelte";
    import UploadPanel from "../ui/UploadPanel.svelte";
    import { uploadState } from "$lib/code/stateObjects/subState/uploadState.svelte";
    import { deleteFiles, moveFile, moveMultipleFiles } from "$lib/code/module/files";
    import { confirmDialogState, folderSelectorState, inputDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte";
    import NewTabIcon from "$lib/component/icons/NewTabIcon.svelte";
    import MoveIcon from "$lib/component/icons/MoveIcon.svelte";
    import FolderTreeSelector from "../ui/FolderTreeSelector.svelte";
    import { appState } from '$lib/code/stateObjects/appState.svelte';
    import EditIcon from "$lib/component/icons/EditIcon.svelte";
    import FileEntry from "./FileEntry.svelte";
    import { isDialogOpen } from "$lib/code/util/stateUtils";

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
            if (filesState.selectedEntries.singlePath) {
                const selector = `[data-entry-path="${filesState.selectedEntries.singlePath.replace(/"/g, '\\"')}"]`;
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
        if (isDialogOpen()) return

        // Check if Delete key was pressed
        if (event.key === 'Delete' && !event.ctrlKey && !event.altKey && !event.metaKey) {
            // Check if we have a selected entry
            if (filesState.selectedEntries.singlePath && filesState.data.entries) {
                // Find the selected entry in the entries list
                const selectedEntry = filesState.data.entries.find(e => e.path === filesState.selectedEntries.singlePath);
                if (selectedEntry) {
                    // Delete the selected entry
                    option_delete(selectedEntry);
                }
            }
        } 

        if (event.key === "Enter") {
            if (filesState.selectedEntries.singleMeta) {
                entryOnClick(event, filesState.selectedEntries.singleMeta)
            }
        }
        
        // Handle Up and Down arrow keys for entry navigation
        if ((event.key === 'ArrowUp' || event.key === 'ArrowDown') && 
                 !event.ctrlKey && !event.altKey && !event.metaKey && 
                 filesState.data.sortedEntries?.length) {
            
            event.preventDefault();
            const entries = filesState.data.sortedEntries;
            const currentPath = filesState.selectedEntries.singlePath;
            
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
            filesState.selectedEntries.list = [newEntry.path];
            
            // Scroll selected entry into view
            scrollSelectedEntryIntoView()
        }
    }

    /**
     * onClick for file entry
     */
    function entryOnClick(e: UIEvent, entry: FileMetadata) {
        if (filesState.selectedEntries.singlePath !== entry.path) {
            e.preventDefault()
            filesState.selectedEntries.setSelected(entry.path)
            // Scroll selected entry into view
            scrollSelectedEntryIntoView()
        } else {
            if (isFolder(entry) && !entry.isExecutable) return
            openEntry(entry.path)
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
        const newParentPath = await folderSelectorState.show!({
            title: "Choose the target folder.",
            initialSelection: parentFromPath(entry.path)
        })
        if (!newParentPath) return

        // Move all selected entries if a selected entry was moved
        const selected = filesState.selectedEntries.list
        if (selected.length > 1 && selected.includes(entry.path)) {
            moveMultipleFiles(newParentPath, selected)
        } else {
            const newPath = appendFilename(newParentPath, entry.filename!)
            moveFile(entry.path, newPath)
        }
        closeEntryPopover()
    }

    async function option_rename(entry: FileMetadata) {
        entryMenuPopoverOpen = false
        const newFilename = await inputDialogState.show({title: "Rename file", message: "Enter the new filename:", confirmText: "Rename", cancelText: "Cancel"})
        if (!newFilename) return
        if (newFilename === entry.filename) return

        const parent = parentFromPath(entry.path)
        const newPath = resolvePath(parent, newFilename)
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

    /**
     * # Drag and dropping
    */
    let draggedPaths: string[] | null = null
    let dragImageElement = $state() as HTMLParagraphElement
    let dragging = $state(false)

    function event_dragOver(e: DragEvent, entry: FullFileMetadata) {
        const element = e.currentTarget as HTMLElement

        const isFolder = entry.fileType === "FOLDER" || entry.fileType === "FOLDER_LINK" && appState.followSymlinks
        const isDropArea = draggedPaths && !draggedPaths.includes(entry.path)

        if (isFolder && isDropArea) {
            e.preventDefault()        
            element.classList.add("dragover")
        }
    }

    function event_dragLeave(e: DragEvent, entry: FullFileMetadata) {
        const element = e.currentTarget as HTMLElement
        element.classList.remove("dragover")
    }

    function event_drop(e: DragEvent, entry: FullFileMetadata) {
        if (entry.fileType === "FOLDER_LINK" && !appState.followSymlinks || entry.fileType !== "FOLDER") return
        if (!entry.permissions.includes("WRITE")) return
        if (draggedPaths == null || draggedPaths.length < 1) return

        if (draggedPaths.includes(entry.path)) return

        if (draggedPaths.length > 1) {
            moveMultipleFiles(entry.path, draggedPaths)
        } else {
            const movedPath = draggedPaths[0]
            const newPath = resolvePath(entry.path, filenameFromPath(movedPath))
            moveFile(movedPath, newPath)
        }
    }

    function event_dragStart(e: DragEvent, entry: FullFileMetadata) {
        dragging = true
        const isEntrySelected = filesState.selectedEntries.list.includes(entry.path)
        if (!isEntrySelected) {
            draggedPaths = [entry.path]
        } else {
            draggedPaths = filesState.selectedEntries.list
        }
        
        if (e.dataTransfer && dragImageElement) {
            dragImageElement.style.display = "block"
            dragImageElement.textContent = `Move ${draggedPaths!.length} file${draggedPaths!.length > 1 ? 's':''}`
            e.dataTransfer!.setDragImage(dragImageElement, 0, 0)

            setTimeout(() => {
                dragImageElement.style.display = "none"
            })
        }
    }

    function event_dragEnd(e: DragEvent, entry: FullFileMetadata) {
        draggedPaths = null
        dragging = false
    }

    function changeSortingMode(mode: typeof filesState.sortingMode) {
        const current = filesState.sortingMode

        if (mode === current) {
            const currentDirection = filesState.sortingDirection
            if (currentDirection === "asc") filesState.sortingDirection = "desc"
            if (currentDirection === "desc") filesState.sortingDirection = "asc"
        } else {
            filesState.sortingDirection = "asc"
            filesState.sortingMode = mode
        }
    }
</script>


{#if filesState.data.sortedEntries}
    <div on:click|stopPropagation class="w-full h-fit overflow-x-hidden">
        <!-- Header row (separate grid) -->
        <div class="file-grid gap-x-2 px-4 py-2 font-medium text-neutral-700 dark:text-neutral-400">
            <button
                on:click={() => changeSortingMode("name")}
                class="truncate text-left flex items-center gap-1"
            >
                Name
                <span class="w-3 text-neutral-500 inline-block text-center">
                    {#if filesState.sortingMode === "name"}
                        {filesState.sortingDirection === "asc" ? "▲" : "▼"}
                    {/if}
                </span>
            </button>

            <button
                on:click={() => changeSortingMode("modified")}
                class="whitespace-nowrap max-md:hidden text-right flex items-center gap-1 justify-end"
            >
                Last Modified
                <span class="w-3 text-neutral-500 inline-block text-center">
                    {#if filesState.sortingMode === "modified"}
                        {filesState.sortingDirection === "asc" ? "▲" : "▼"}
                    {/if}
                </span>
            </button>

            <button
                on:click={() => changeSortingMode("size")}
                class="whitespace-nowrap max-sm:hidden text-right flex items-center gap-1 justify-end"
            >
                Size
                <span class="w-3 text-neutral-500 inline-block text-center">
                    {#if filesState.sortingMode === "size"}
                        {filesState.sortingDirection === "asc" ? "▲" : "▼"}
                    {/if}
                </span>
            </button>
        </div>

        <!-- Each entry is a grid item -->
        {#each filesState.data.sortedEntries as entry (entry.path)}
            <FileEntry
                entry={entry}
                event_dragStart={event_dragStart}
                event_dragOver={event_dragOver}
                event_dragLeave={event_dragLeave}
                event_drop={event_drop}
                event_dragEnd={event_dragEnd}
                entryOnClick={entryOnClick}
                entryOnContextMenu={entryOnContextMenu}
                onClickSelectCheckbox={onClickSelectCheckbox}
                entryMenuOnClick={entryMenuOnClick}
            />
        {/each}
    </div>


    <!-- Entry context menu popover -->
    {#if entryMenuButton && menuEntry}
        {#key entryMenuButton || menuEntry}
            <div class="z-50 relative">
                <Popover.Root bind:open={entryMenuPopoverOpen} onOpenChange={entryMenuPopoverOnOpenChange}>
                    <Popover.Content onInteractOutside={() => { entryMenuPopoverOpen = false }} customAnchor={entryMenuButton} align="start" >
                        <div class="w-[14rem] max-w-full max-h-full rounded-lg bg-neutral-250 dark:bg-neutral-800 py-2 flex flex-col z-50 select-none">
                            <a 
                                href={`/files${menuEntry.path}`}
                                target="_blank" class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2" rel="noopener noreferrer"
                            >
                                <div class="size-5 flex-shrink-0">
                                    <NewTabIcon />
                                </div>
                                <span>Open in new tab</span>
                            </a>

                            {#if filesState.data.contentUrl}
                                <a download href={addSuffix(filesState.data.contentUrl, "/") + `${menuEntry.filename!}`} target="_blank" class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                                    <div class="size-5 flex-shrink-0">
                                        <DownloadIcon />
                                    </div>
                                    <span>Download</span>
                                </a>
                            {/if}

                            {#if menuEntry.permissions.includes("MOVE")}
                                <button on:click={() => { option_rename(menuEntry!) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                                    <div class="size-5 flex-shrink-0">
                                        <EditIcon />
                                    </div>
                                    <span>Rename</span>
                                </button>

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

<p bind:this={dragImageElement} class="fixed top-[100vh] right-[100vw] py-4 px-6 rounded-lg whitespace-nowrap bg-neutral-300 dark:bg-neutral-800" style="display: none;">-</p>


<style>
    @import "/src/app.css" reference;
    
    :global(.file-grid) {
        @apply grid grid-cols-[minmax(0,1fr)_2.5rem] sm:grid-cols-[minmax(0,1fr)_9.6rem_2.5rem] md:grid-cols-[minmax(0,1fr)_9.6rem_4.2rem_2.5rem];
    }

    :global(.dragover) {
        @apply dark:bg-neutral-700;
    }
</style>
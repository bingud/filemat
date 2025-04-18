<script lang="ts">
    import { dev } from "$app/environment";
    import { goto } from "$app/navigation";
    import type { FileMetadata } from "$lib/code/auth/types";
    import { filenameFromPath, formatBytes, formatBytesRounded, formatUnixMillis } from "$lib/code/util/codeUtil.svelte";
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import FileIcon from "$lib/component/icons/FileIcon.svelte";
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
    import ThreeDotsIcon from "$lib/component/icons/ThreeDotsIcon.svelte";
    import InfoIcon from "$lib/component/icons/InfoIcon.svelte";
    import { onMount } from "svelte";
    import { filesState } from "./code/filesState.svelte";
    import TrashIcon from "$lib/component/icons/TrashIcon.svelte";
	import ConfirmDialog from '$lib/component/ConfirmDialog.svelte';

    // Entry menu popup
    let entryMenuButton: HTMLButtonElement | null = $state(null)
    let menuEntry: FileMetadata | null = $state(null)
    let entryMenuPopoverOpen = $state(dev)
    
    // Delete confirmation
    let entryToDelete: FileMetadata | null = $state(null)
    let confirmDialog: ConfirmDialog;

    onMount(() => {
        const selectedName = filesState.selectedEntry.selectedPositions.getChild(filesState.path)
        if (!selectedName) return

        const filename = filesState.path === "/" ? `/${selectedName}` : `/${filesState.path}/${selectedName}`

        if (filesState.data.entries?.some(v => v.filename === filename)) {
            filesState.selectedEntry.path = filename
        }
    })


    /**
     * onClick for file entry
     */
    function entryOnClick(entry: FileMetadata) {
        if (filesState.selectedEntry.path === entry.filename) {
            openEntry(entry.filename)
        } else {
            filesState.selectedEntry.selectedPositions.set(entry.filename, true)
            filesState.selectedEntry.path = entry.filename
        }
    }

    /**
     * onClick for entry menu
     */
    function entryMenuOnClick(button: HTMLButtonElement, entry: FileMetadata) {
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
        }
    }

    function option_details(entry: FileMetadata) {
        filesState.selectedEntry.path = entry.filename
        filesState.ui.detailsOpen = true
        closeEntryPopover()
    }

    function option_delete(entry: FileMetadata) {
        entryToDelete = entry
        
        // Show confirmation dialog and handle the result
        confirmDialog.show({
            title: "Delete File",
            message: `Are you sure you want to delete "${filenameFromPath(entry.filename)}"? This cannot be undone.`,
            confirmText: "Delete",
            cancelText: "Cancel"
        }).then((confirmed: boolean) => {
            if (confirmed) {
                handleDeleteConfirm();
            }
        });
        
        closeEntryPopover()
    }
    
    function handleDeleteConfirm() {
        if (entryToDelete) {
            
        }
        entryToDelete = null
    }

    function closeEntryPopover() {
        entryMenuButton = null
        menuEntry = null
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
            {@const selected = filesState.selectedEntry.path === entry.filename}

            <div 
                on:click={() => entryOnClick(entry)}
                class="file-grid h-[2.5rem] gap-x-2 items-center px-1 py-1 cursor-pointer {selected ? 'bg-blue-200 dark:bg-sky-950 select-none' : 'hover:bg-neutral-200 dark:hover:bg-neutral-800'}"
            >
                <!-- Filename + Icon -->
                <div class="h-full flex items-center gap-2 min-w-0 overflow-hidden whitespace-nowrap text-ellipsis">
                    <div class="h-6 aspect-square fill-neutral-500 flex-shrink-0 flex items-center justify-center">
                        {#if entry.fileType.startsWith("FILE")}
                            <FileIcon />
                        {:else if entry.fileType.startsWith("FOLDER")}
                            <FolderIcon />
                        {/if}
                    </div>
                    <p class="truncate">
                        {filenameFromPath(entry.filename)}
                    </p>
                </div>

                <!-- Last Modified -->
                <div class="h-full text-right whitespace-nowrap max-sm:hidden flex items-center justify-end opacity-70">
                    {formatUnixMillis(entry.modifiedDate)}
                </div>

                <!-- Size -->
                <div class="h-full text-right whitespace-nowrap max-md:hidden flex items-center justify-end">
                    {formatBytesRounded(entry.size)}
                </div>

                <!-- Menu button (stopPropagation) -->
                <div class="h-full text-center">
                    <button
                        on:click={(e) => { if (filesState.selectedEntry.path === entry.filename) { e.stopPropagation() }; entryMenuOnClick(e.currentTarget, entry) }}
                        class="h-full aspect-square flex items-center justify-center rounded-full p-2 hover:bg-neutral-400/30 dark:hover:bg-neutral-600/50 fill-neutral-700 dark:fill-neutral-500"
                    >
                        <ThreeDotsIcon />
                    </button>
                </div>
            </div>
        {/each}
    </div>



    {#if entryMenuButton && menuEntry}
        {#key entryMenuButton || menuEntry}
            <div class="z-50 relative">
                <Popover.Root bind:open={entryMenuPopoverOpen} onOpenChange={entryMenuPopoverOnOpenChange}>
                    <Popover.Content onInteractOutside={() => { entryMenuPopoverOpen = false }} customAnchor={entryMenuButton} align="start" >
                        <div class="w-[14rem] max-w-full max-h-full rounded-lg bg-neutral-250 dark:bg-neutral-800 py-2 flex flex-col z-50">
                            <button on:click={() => { option_delete(menuEntry!) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                                <div class="size-5 flex-shrink-0">
                                    <TrashIcon />
                                </div>
                                <span>Delete</span>
                            </button>

                            <button on:click={() => { option_details(menuEntry!) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                                <div class="size-5 flex-shrink-0">
                                    <InfoIcon />
                                </div>
                                <span>Details</span>
                            </button>
                            <hr class="basic-hr my-2">
                            <p class="px-4 truncate opacity-70">File: {filenameFromPath(menuEntry.filename)}</p>
                        </div>
                    </Popover.Content>
                </Popover.Root>
            </div>
        {/key}
    {/if}
{:else}
    <div class="center">
        <p>No folder is open.</p>
    </div>
{/if}


<!-- Confirmation Dialog -->
<ConfirmDialog bind:this={confirmDialog} />
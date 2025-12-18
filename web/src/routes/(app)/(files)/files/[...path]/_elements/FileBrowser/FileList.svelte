<script lang="ts">
    import type { FileMetadata, FullFileMetadata } from "$lib/code/auth/types";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { changeSortingMode, type FileListProps } from "../../_code/fileBrowserUtil";
    import FileContextMenuPopover from "../ui/FileContextMenuPopover.svelte";
    import GridFileEntry from "./GridFileEntry.svelte";
    import RowFileEntry from "./RowFileEntry.svelte";

    let {
        sortedEntries,
        event_dragStart,
        event_dragOver,
        event_dragLeave,
        event_drop,
        event_dragEnd,
        entryOnClick,
        onClickSelectCheckbox,
        option_rename,
        option_move,
        option_delete,
        option_details,
    }: FileListProps = $props()

    let entryMenuButton: HTMLElement | null = $state(null)
    let menuEntry: FullFileMetadata | null = $state(null)

    let entryMenuYPos: number | null = $state(null)
    let entryMenuXPos: number | null = $state(null)


    function entryMenuPopoverOnOpenChange(open: boolean) {
        if (!open) {
            entryMenuButton = null
            menuEntry = null
            entryMenuXPos = null
            entryMenuYPos = null
        }
    }

    function entryOnContextMenu(e: MouseEvent, entry: FullFileMetadata) {
        e.preventDefault()

        filesState.ui.fileContextMenuPopoverOpen = false
        entryMenuXPos = null
        entryMenuYPos = null

        setTimeout(() => {
            entryMenuXPos = e.clientX
            entryMenuYPos = e.clientY
            filesState.ui.fileContextMenuPopoverOpen = true
            menuEntry = entry
        })
    }

    /**
     * onClick for entry menu
     */
    function entryMenuOnClick(button: HTMLButtonElement, entry: FullFileMetadata) {
        entryMenuButton = button
        menuEntry = entry
        filesState.ui.fileContextMenuPopoverOpen = true
    }
</script>


<!-- File list -->
<div on:click|stopPropagation on:scroll={() => { console.log(`scrok`) }} class="w-full h-fit overflow-x-hidden">
    {#if filesState.ui.fileViewType === "rows"}
        <!-- Header row (separate grid) -->
        <div class="file-row-grid gap-x-2 px-4 pb-2 font-medium text-neutral-700 dark:text-neutral-400">
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
                class="whitespace-nowrap max-lg:hidden text-right flex items-center gap-1 justify-end"
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
        {#each sortedEntries as entry (entry.path)}
            <RowFileEntry
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
    {:else if filesState.ui.fileViewType === "tiles"}
        <div class="w-full h-fit grid gap-2
                grid-cols-[repeat(auto-fill,minmax(8rem,1fr))]"
        >
            {#each sortedEntries as entry (entry.path)}
                <GridFileEntry
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
    {/if}
</div>


<!-- Entry context menu popover -->
{#if entryMenuButton && menuEntry}
    {#key entryMenuButton || menuEntry}
        <div class="z-50 relative">
            <FileContextMenuPopover
                entryMenuButton={entryMenuButton}
                entryMenuPopoverOnOpenChange={entryMenuPopoverOnOpenChange}
                menuEntry={menuEntry}
                option_rename={option_rename}
                option_move={option_move}
                option_delete={option_delete}
                option_details={option_details}
            ></FileContextMenuPopover>
        </div>
    {/key}
{/if}

<!-- Entry context menu floating element -->
{#if menuEntry && entryMenuXPos != null && entryMenuYPos != null}
    <div bind:this={entryMenuButton} class="size-0 fixed z-10" style="top: {entryMenuYPos}px; left: {entryMenuXPos}px"></div>
{/if}
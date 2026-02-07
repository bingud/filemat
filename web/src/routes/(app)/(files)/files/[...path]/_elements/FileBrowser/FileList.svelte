<script lang="ts">
    import type { FullFileMetadata } from "$lib/code/auth/types";
    import { type RowPreviewSize, type GridPreviewSize, config } from "$lib/code/config/values";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { explicitEffect } from "$lib/code/util/codeUtil.svelte";
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
        option_copy,
        option_delete,
        option_details,
        option_save,
        closeFileContextMenuPopover,
    }: FileListProps = $props()

    let entryMenuButton: HTMLElement | null = $state(null)
    let menuEntry: FullFileMetadata | null = $state(null)

    let entryMenuYPos: number | null = $state(null)
    let entryMenuXPos: number | null = $state(null)

    $effect(() => {
        if (filesState.ui.fileContextMenuPopoverOpen === false) {
            closeContextMenu()
        }
    })

    // Reorder the image loading queue whenever the sort order changes
    explicitEffect(() => [sortedEntries], () => {
        const paths = sortedEntries?.map(e => e.path) || []
        
        filesState.ui.filePreviewLoader.reorderQueue(paths)
    })

    function closeContextMenu() {
        entryMenuButton = null
        menuEntry = null
        entryMenuYPos = null
        entryMenuXPos = null
    }

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
        {#key config.preview}
            {@const size = filesState.ui.previewSize as RowPreviewSize}

            {#each sortedEntries as entry (entry.filename)}
                <div 
                    style:--icon-padding="{size.iconPadding}rem" 
                    style:--menu-icon-padding="{size.menuIconPadding}rem"
                    class="contents"
                >
                    <RowFileEntry
                        {entry}
                        {event_dragStart}
                        {event_dragOver}
                        {event_dragLeave}
                        {event_drop}
                        {event_dragEnd}
                        {entryOnClick}
                        {entryOnContextMenu}
                        {onClickSelectCheckbox}
                        {entryMenuOnClick}
                        size={size as RowPreviewSize}
                    />
                </div>
            {/each}
        {/key}
    {:else if filesState.ui.fileViewType === "grid"}
        {@const size = filesState.ui.previewSize as GridPreviewSize}

        <div 
            style:--min-width="{size.width}rem"
            style:--icon-padding="{size.iconPadding}rem"
            class="
                w-full h-fit grid gap-2
            "
            style="
                grid-template-columns: repeat(auto-fill, minmax(min(var(--min-width),100%), 1fr));
            "
        >
            {#each sortedEntries as entry (entry.filename)}
                <GridFileEntry
                    {entry}
                    {event_dragStart}
                    {event_dragOver}
                    {event_dragLeave}
                    {event_drop}
                    {event_dragEnd}
                    {entryOnClick}
                    {entryOnContextMenu}
                    {onClickSelectCheckbox}
                    {entryMenuOnClick}
                    size={size}
                />
            {/each}
        </div>
    {/if}
</div>


<!-- Entry context menu popover -->
{#if entryMenuButton && menuEntry}
    {#key entryMenuButton || menuEntry}
        <div class="z-popover relative">
            <FileContextMenuPopover
                {entryMenuButton}
                {entryMenuPopoverOnOpenChange}
                {menuEntry}
                {option_rename}
                {option_move}
                {option_copy}
                {option_delete}
                {option_details}
                {option_save}
                {closeFileContextMenuPopover}
            ></FileContextMenuPopover>
        </div>
    {/key}
{/if}

<!-- Entry context menu floating element -->
{#if menuEntry && entryMenuXPos != null && entryMenuYPos != null}
    <div bind:this={entryMenuButton} class="size-0 fixed z-popover" style="top: {entryMenuYPos}px; left: {entryMenuXPos}px"></div>
{/if}


<style lang="postcss">
    :global(.file-row-grid) {
        @apply grid grid-cols-[minmax(0,1fr)_2.5rem] sm:grid-cols-[minmax(0,1fr)_9.6rem_2.5rem] md:grid-cols-[minmax(0,1fr)_9.6rem_4.2rem_2.5rem];
    }
</style>
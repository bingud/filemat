<script lang="ts">
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { encodeUrlFilePath, isFolder } from "$lib/code/util/codeUtil.svelte";
    import ThreeDotsIcon from "$lib/component/icons/ThreeDotsIcon.svelte";
    import { onMount } from "svelte";
    import type { FileEntryProps } from "../../_code/fileBrowserUtil.svelte";
    import FileThumbnail from "./FileThumbnail.svelte";
    import type { GridPreviewSize } from "$lib/code/config/values";
    import { appState } from "$lib/code/stateObjects/appState.svelte";

    let {
        entry,
        event_dragStart,
        event_dragOver,
        event_dragLeave,
        event_drop,
        event_dragEnd,
        entryOnClick,
        entryOnContextMenu,
        onClickSelectCheckbox,
        entryMenuOnClick,
        size
    }: FileEntryProps & { size: GridPreviewSize } = $props()

    onMount(() => {
        if (!entry) {
            console.log(`Entry in FileEntry is null`)
            console.log(entry)
            return
        }
    })
    
    let isSelected = $derived(!!entry && filesState.selectedEntries.currentSet.has(entry.path))
    
    let isUnopenable = $derived(!entry || (isFolder(entry) && !entry.isExecutable) || (entry.isSymlink && !appState.followSymlinks))

    let isVisible = $derived(appState.settings.alwaysRenderPreviews || filesState.ui.visibilityManager.visibleEntryPaths.has(entry.path))
    
    const observeEntry = filesState.ui.visibilityManager.observeEntry
    const observeSkeleton = filesState.ui.visibilityManager.observeSkeleton
</script>

{#if isVisible}
    <a
        on:click|preventDefault={(e) => entryOnClick(e, entry)}
        on:contextmenu={(e) => { entryOnContextMenu(e, entry) }}
        draggable={entry.permissions?.includes("MOVE")}
        data-entry-path={entry.path} rel="noopener noreferrer"
        style:--entry-height="{size.height}rem"
        class="
            grid-file-entry w-full min-w-0 flex flex-col items-center select-none group rounded-lg outline-0
            {isUnopenable 
                ? 'cursor-default' 
                : 'cursor-pointer'
            }
            {isUnopenable 
                ? isSelected ? 'bg-blue-200 dark:bg-sky-950' : 'hover:bg-neutral-200 dark:hover:bg-neutral-800'
                : isSelected ? 'bg-blue-200/60 dark:bg-sky-950/60' : 'bg-neutral-200/30 dark:bg-neutral-800/30 hover:bg-neutral-200/60 dark:hover:bg-neutral-800/60'
            }
        "
        href={encodeUrlFilePath(`${filesState.meta.pagePath}${(entry.path)}`)}
        on:dragstart={(e) => { event_dragStart(e, entry) }}
        on:dragover={(e) => { event_dragOver(e, entry) }}
        on:dragleave={(e) => { event_dragLeave(e, entry) }}
        on:drop={(e) => { event_drop(e, entry) }}
        on:dragend={(e) => { event_dragEnd(e, entry) }}
        use:observeEntry={entry.path}
    >
        <div class="entry-preview relative flex flex-col w-full">
            <div class="h-full fill-neutral-500 stroke-neutral-500 shrink-0 flex items-center justify-center pointer-events-none">
                {#if entry.filename}
                    <FileThumbnail {entry} size={size.pixelSize} isLarge={true}></FileThumbnail>
                {/if}
            </div>

            {#key isSelected}
                <div on:click|stopPropagation|preventDefault={() => { onClickSelectCheckbox(entry.path) }} class="absolute top-0 left-0 flex items-center justify-center">
                    <input checked={isSelected} class="!size-5 lg:opacity-0 checked:opacity-100 group-hover:opacity-100" type="checkbox">
                </div>
            {/key}
        </div>

        <div class="entry-bar w-full flex items-end justify-between overflow-hidden">
            <div class="flex gap-2 min-w-0 overflow-hidden whitespace-nowrap text-ellipsis">
                <p title={entry.filename} class="truncate">
                    {entry.filename!}
                </p>
            </div>

            <button
                on:click|stopPropagation|preventDefault={(e) => { entryMenuOnClick(e.currentTarget, entry) }}
                class="h-[1.5rem] aspect-square flex items-center justify-center rounded-full p-[0.20rem] hover:bg-neutral-400/30 dark:hover:bg-neutral-600/50 fill-neutral-700 dark:fill-neutral-500"
            >
                <ThreeDotsIcon />
            </button>
        </div>
    </a>
{:else}
    <div
        data-entry-path={entry.path}
        style:--entry-height="{size.height}rem"
        class="grid-file-entry w-full min-w-0 rounded-lg"
        use:observeSkeleton={entry.path}
    >
        <span class="skeleton-filename">{entry.filename}</span>
    </div>
{/if}


<style>
    .grid-file-entry {
        --entry-height: 7rem;
        --entry-padding: 0.5rem;

        --entry-free-height: calc(var(--entry-height) - calc(2 * var(--entry-padding)));

        --entry-bar-height: 2rem;
        --entry-preview-height: calc(var(--entry-free-height) - var(--entry-bar-height));

        height: var(--entry-height);
        padding: var(--entry-padding);
    }

    .entry-bar {
        height: var(--entry-bar-height);
    }
    .entry-preview {
        height: var(--entry-preview-height);
    }

    .skeleton-filename {
        font-size: 1px;
        line-height: 0;
        color: transparent;
        display: block;
        overflow: hidden;
        height: 0;
        width: 0;
    }
</style>

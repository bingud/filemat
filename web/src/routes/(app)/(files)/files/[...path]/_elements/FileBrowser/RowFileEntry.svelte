<script lang="ts">
    import { type FullFileMetadata } from "$lib/code/auth/types";
    import { fileCategories, getFileCategory, getFileCategoryFromFilename } from "$lib/code/data/files";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { formatBytesRounded, formatUnixMillis, getFileExtension, isFolder } from "$lib/code/util/codeUtil.svelte";
    import FileArrow from "$lib/component/icons/FileArrow.svelte";
    import FileIcon from "$lib/component/icons/FileIcon.svelte";
    import FolderArrow from "$lib/component/icons/FolderArrow.svelte";
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
    import ThreeDotsIcon from "$lib/component/icons/ThreeDotsIcon.svelte";
    import { onMount } from "svelte";
    import type { FileEntryProps } from "../../_code/fileBrowserUtil";
    import FileThumbnail from "./FileThumbnail.svelte";

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
    }: FileEntryProps = $props()

    const loadFilePreview = filesState.ui.filePreviewLoader.getAction()

    onMount(() => {
        if (!entry) {
            console.log(`Entry in FileEntry is null`)
            console.log(entry)
        }
    })
    
    let isSelected = $derived(!!entry && filesState.selectedEntries.currentList.includes(entry.path))
    
    let isUnopenable = $derived(!entry || isFolder(entry) && !entry.isExecutable)
</script>


<a
    on:click|preventDefault={(e) => entryOnClick(e, entry)}
    on:contextmenu={(e) => { entryOnContextMenu(e, entry) }}
    draggable={entry.permissions?.includes("MOVE")}
    data-entry-path={entry.path} rel="noopener noreferrer"
    class="
        file-row-grid h-[2.5rem] gap-x-2 items-center select-none group 
        {isUnopenable 
            ? 'cursor-default' 
            : 'cursor-pointer'
        }
        {isUnopenable 
            ? isSelected ? 'bg-blue-200 dark:bg-sky-950' : 'hover:bg-neutral-200 dark:hover:bg-neutral-800'
            : isSelected ? 'bg-blue-200/60 dark:bg-sky-950/60' : 'hover:bg-neutral-200/60 dark:hover:bg-neutral-800/60'
        }
    "
    href={encodeURI(`${filesState.meta.pagePath}${(entry.path)}`)}
    on:dragstart={(e) => { event_dragStart(e, entry) }}
    on:dragover={(e) => { event_dragOver(e, entry) }}
    on:dragleave={(e) => { event_dragLeave(e, entry) }}
    on:drop={(e) => { event_drop(e, entry) }}
    on:dragend={(e) => { event_dragEnd(e, entry) }}
>
    <!-- Filename + Icon -->
    <div class="h-full flex items-center overflow-hidden">
        {#key isSelected}
            <div on:click|stopPropagation|preventDefault={() => { onClickSelectCheckbox(entry.path) }} class="h-full flex items-center justify-center pl-2 pr-1">
                <input checked={isSelected} class="!size-5 opacity-0 checked:opacity-100 group-hover:opacity-100" type="checkbox">
            </div>
        {/key}

        <div class="h-full flex items-center gap-2 min-w-0 overflow-hidden whitespace-nowrap text-ellipsis">
            <div class="h-6 aspect-square fill-neutral-500 stroke-neutral-500 flex-shrink-0 flex items-center justify-center py-[0.1rem] pointer-events-none">
                {#if entry.filename}
                    <FileThumbnail {entry} size={48} isLarge={false}></FileThumbnail>
                {/if}
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

    <!-- Menu button -->
    <div class="h-full text-center py-1 pr-1">
        <button
            on:click|stopPropagation|preventDefault={(e) => { entryMenuOnClick(e.currentTarget, entry) }}
            class="h-full aspect-square flex items-center justify-center rounded-full p-2 hover:bg-neutral-400/30 dark:hover:bg-neutral-600/50 fill-neutral-700 dark:fill-neutral-500"
        >
            <ThreeDotsIcon />
        </button>
    </div>
</a>
<script lang="ts">
    import { type FullFileMetadata } from "$lib/code/auth/types";
    import { getFileCategoryFromFilename } from "$lib/code/data/files";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { isFolder } from "$lib/code/util/codeUtil.svelte";
    import FileArrow from "$lib/component/icons/FileArrow.svelte";
    import FileIcon from "$lib/component/icons/FileIcon.svelte";
    import FolderArrow from "$lib/component/icons/FolderArrow.svelte";
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
    import ThreeDotsIcon from "$lib/component/icons/ThreeDotsIcon.svelte";
    import { onMount } from "svelte";

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
    }: { 
        entry: FullFileMetadata,
        event_dragStart: (e: DragEvent, entry: FullFileMetadata) => void,
        event_dragOver: (e: DragEvent, entry: FullFileMetadata) => void,
        event_dragLeave: (e: DragEvent, entry: FullFileMetadata) => void,
        event_drop: (e: DragEvent, entry: FullFileMetadata) => void,
        event_dragEnd: (e: DragEvent, entry: FullFileMetadata) => void,
        entryOnClick: (e: MouseEvent, entry: FullFileMetadata) => void,
        entryOnContextMenu: (e: MouseEvent, entry: FullFileMetadata) => void,
        onClickSelectCheckbox: (path: string) => void,
        entryMenuOnClick: (button: HTMLButtonElement, entry: FullFileMetadata) => void,
    } = $props()

    const loadFilePreview = filesState.ui.filePreviewLoader.getAction()

    onMount(() => {
        if (!entry) {
            console.log(`Entry in FileEntry is null`)
            console.log(entry)
        }
    })
    
    let isSelected = $derived(!!entry && filesState.selectedEntries.list.includes(entry.path))
    
    let isUnopenable = $derived(!entry || isFolder(entry) && !entry.isExecutable)
</script>


<a
    on:click|preventDefault={(e) => entryOnClick(e, entry)}
    on:contextmenu={(e) => { entryOnContextMenu(e, entry) }}
    draggable={entry.permissions.includes("MOVE")}
    data-entry-path={entry.path} rel="noopener noreferrer"
    class="
        grid-file-entry w-full min-w-0 flex flex-col items-center select-none group rounded-lg
        {isUnopenable 
            ? 'cursor-default' 
            : 'cursor-pointer'
        }
        {isUnopenable 
            ? isSelected ? 'bg-blue-200 dark:bg-sky-950' : 'hover:bg-neutral-200 dark:hover:bg-neutral-800'
            : isSelected ? 'bg-blue-200/60 dark:bg-sky-950/60' : 'bg-neutral-200/30 dark:bg-neutral-800/30 hover:bg-neutral-200/60 dark:hover:bg-neutral-800/60'
        }
    "
    href={encodeURI(`/files${(entry.path)}`)}
    on:dragstart={(e) => { event_dragStart(e, entry) }}
    on:dragover={(e) => { event_dragOver(e, entry) }}
    on:dragleave={(e) => { event_dragLeave(e, entry) }}
    on:drop={(e) => { event_drop(e, entry) }}
    on:dragend={(e) => { event_dragEnd(e, entry) }}
>
    <!-- Icon / preview and checkbox -->
    <div class="entry-preview relative flex flex-col w-full">
        <!-- Preview image -->
        <div class="h-full fill-neutral-500 stroke-neutral-500 shrink-0 flex items-center justify-center pointer-events-none">
            {#if entry.filename}
                {@const format = getFileCategoryFromFilename(entry.filename)}

                {#if format === "image"}
                    <img use:loadFilePreview alt="" src="" data-src="/api/v1/file/image-thumbnail?size=256&path={encodeURIComponent(entry.path)}&modified={entry.modifiedDate}" class="h-full w-full object-contain opacity-0" on:load={(e: any) => { e.currentTarget.classList.remove("opacity-0") }}>
                {:else if format === "video"}
                    <img use:loadFilePreview alt="" src="" data-src="/api/v1/file/video-preview?size=256&path={encodeURIComponent(entry.path)}&modified={entry.modifiedDate}" class="h-full w-full object-contain opacity-0" on:load={(e: any) => { e.currentTarget.classList.remove("opacity-0") }}>
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
            {/if}
        </div>

        <!-- Selection Checkbox -->
        {#key isSelected}
            <div on:click|stopPropagation|preventDefault={() => { onClickSelectCheckbox(entry.path) }} class="absolute top-0 left-0 flex items-center justify-center p-1">
                <input checked={isSelected} class="opacity-0 checked:opacity-100 group-hover:opacity-100" type="checkbox">
            </div>
        {/key}
    </div>

    <!-- Filename + Icon -->
    <div class="entry-bar w-full flex items-end justify-between overflow-hidden">
        <div class="flex gap-2 min-w-0 overflow-hidden whitespace-nowrap text-ellipsis">
            <p class="truncate">
                {entry.filename!}
            </p>
        </div>

        <!-- Menu button -->
        <button
            on:click|stopPropagation|preventDefault={(e) => { entryMenuOnClick(e.currentTarget, entry) }}
            class="h-[1.5rem] aspect-square flex items-center justify-center rounded-full p-[0.20rem] hover:bg-neutral-400/30 dark:hover:bg-neutral-600/50 fill-neutral-700 dark:fill-neutral-500"
        >
            <ThreeDotsIcon />
        </button>
    </div>
</a>


<style>
    /* Config */
    .grid-file-entry {
        --entry-height: 7rem;
        --entry-padding: 0.5rem;

        --entry-free-height: calc(var(--entry-height) - calc(2 * var(--entry-padding)));

        --entry-bar-height: 2rem;
        --entry-preview-height: calc(var(--entry-free-height) - var(--entry-bar-height));

        /* Entry Styling */
        /* width: 9rem;
        max-width: 11rem; */
        height: var(--entry-height);
        padding: var(--entry-padding);
    }

    /* Elements */
    .entry-bar {
        height: var(--entry-bar-height);
    }
    .entry-preview {
        height: var(--entry-preview-height);
    }
</style>
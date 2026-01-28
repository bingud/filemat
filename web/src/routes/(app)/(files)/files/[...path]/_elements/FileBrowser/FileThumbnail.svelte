<script lang="ts">
    import type { FullFileMetadata } from "$lib/code/auth/types";
    import { getFileCategoryFromFilename } from "$lib/code/data/files";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { filenameFromPath } from "$lib/code/util/codeUtil.svelte";
    import FileArrow from "$lib/component/icons/FileArrow.svelte";
    import FileIcon from "$lib/component/icons/FileIcon.svelte";
    import FolderArrow from "$lib/component/icons/FolderArrow.svelte";
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
    import PlayIcon from "$lib/component/icons/PlayIcon.svelte";

    let {
        entry,
        size,
        isLarge
    }: {
        entry: FullFileMetadata,
        size: number,
        isLarge: boolean,
    } = $props()

    const loadFilePreview = filesState.ui.filePreviewLoader.getAction()

    const format = getFileCategoryFromFilename(entry.filename || filenameFromPath(entry.path))
    const shareTokenParam = filesState.getIsShared() ? `&shareToken=${filesState.meta.shareToken}` : ``

    let imageLoadFailed = $state(false)

    function onImageError() {
        imageLoadFailed = true
    }
</script>




{#if format === "image" && !imageLoadFailed}
    <img 
        on:error={onImageError} 
        use:loadFilePreview={entry.path} 
        alt="" 
        data-src="/api/v1/file/image-thumbnail?size={size}&path={encodeURIComponent(entry.path)}&modified={entry.modifiedDate}{shareTokenParam}" 
        class="h-full w-full object-contain opacity-0" 
        on:load={(e: any) => { e.currentTarget.classList.remove("opacity-0") }}
    >
{:else if format === "video" && !imageLoadFailed}
    <div class="relative flex h-full w-full items-center justify-center">
        <img
            on:load={(e) => e.currentTarget.classList.remove("opacity-0")}
            on:error={onImageError}
            use:loadFilePreview={entry.path}
            alt=""
            data-src="/api/v1/file/video-preview?size={size}&path={encodeURIComponent(entry.path)}&modified={entry.modifiedDate}{shareTokenParam}"
            class="h-full w-full object-contain opacity-0"
        >
        {#if isLarge}
            <div class="absolute pointer-events-none text-white size-6 opacity-60">
                <PlayIcon class="[filter:drop-shadow(0_0_1px_rgba(0,0,0,1))_drop-shadow(0_0_3px_rgba(0,0,0,0.6))]"></PlayIcon>
            </div>
        {/if}
    </div>
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
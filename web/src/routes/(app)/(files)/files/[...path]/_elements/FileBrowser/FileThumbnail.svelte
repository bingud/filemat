<script lang="ts">
    import type { FullFileMetadata } from "$lib/code/auth/types";
    import { getFileCategoryFromFilename } from "$lib/code/data/files";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import type { VisibilityManager } from "../../_code/fileBrowserUtil.svelte";
    import { filenameFromPath, normalizeFilePath } from "$lib/code/util/codeUtil.svelte";
    import FileArrow from "$lib/component/icons/FileArrow.svelte";
    import FileIcon from "$lib/component/icons/FileIcon.svelte";
    import FolderArrow from "$lib/component/icons/FolderArrow.svelte";
    import FolderIcon from "$lib/component/icons/FolderIcon.svelte";
    import PlayIcon from "$lib/component/icons/PlayIcon.svelte";

    let {
        entry,
        visibilityManager,
        size,
        isLarge,
    }: {
        entry: FullFileMetadata,
        visibilityManager: VisibilityManager,
        size: number,
        isLarge: boolean,
    } = $props()

    const loadFilePreview = $derived(visibilityManager.getAction())

    const format = $derived(getFileCategoryFromFilename(entry.filename || filenameFromPath(entry.path)))
    let imageLoadFailed = $state(false)

    function onImageError() {
        imageLoadFailed = true
    }

    function getPreviewUrl(endpoint: string) {
        const params = new URLSearchParams()
        params.set("size", `${size}`)
        params.set("path", normalizeFilePath(entry.path))
        params.set("modified", `${entry.modifiedDate}`)
        if (filesState.getIsShared()) params.set("shareToken", filesState.meta.shareToken)

        return `/api/v1/file/${endpoint}?${params.toString()}`
    }
</script>



{#key size}
    {#if format === "image" && !imageLoadFailed}
        <img 
            onerror={onImageError} 
            use:loadFilePreview={entry.path} 
            alt=""
            data-src={getPreviewUrl("image-thumbnail")} 
            class="h-full w-full object-contain opacity-0" 
            onload={(e) => { e.currentTarget.classList.remove("opacity-0") }}
        >
    {:else if format === "video" && !imageLoadFailed}
        <div class="relative flex h-full w-full items-center justify-center">
            <img
                onload={(e) => e.currentTarget.classList.remove("opacity-0")}
                onerror={onImageError}
                use:loadFilePreview={entry.path}
                alt=""
                data-src={getPreviewUrl("video-preview")}
                class="h-full w-full object-contain opacity-0"
            >
            {#if isLarge}
                <div class="absolute pointer-events-none text-white size-6 opacity-60">
                    <PlayIcon class="[filter:drop-shadow(0_0_1px_rgba(0,0,0,1))_drop-shadow(0_0_3px_rgba(0,0,0,0.6))]"></PlayIcon>
                </div>
            {/if}
        </div>
    {:else}
        <div class="w-full h-full flex items-center justify-center p-(--icon-padding)">
            {#if entry.fileType === "FILE"}
                <FileIcon />
            {:else if entry.fileType === "FILE_LINK"}
                <FileArrow />
            {:else if entry.fileType === "FOLDER"}
                <FolderIcon />
            {:else if entry.fileType === "FOLDER_LINK"}
                <FolderArrow />
            {/if}
        </div>
    {/if}
{/key}
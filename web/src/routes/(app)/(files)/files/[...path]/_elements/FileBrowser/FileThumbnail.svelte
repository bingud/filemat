<script lang="ts">
    import type { FullFileMetadata } from "$lib/code/auth/types";
    import { getFileCategoryFromFilename } from "$lib/code/data/files";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { fallbackQueueKey, findCachedThumbFallback, type VisibilityManager } from "../../_code/fileBrowserUtil.svelte";
    import { encodeUrlFilePath, filenameFromPath, formatMediaDuration } from "$lib/code/util/codeUtil.svelte";
    import { contentCrossOrigin, joinContentUrl } from "$lib/code/util/contentUrl";
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

    const loadFilePreview = visibilityManager.getAction()

    const format = getFileCategoryFromFilename(entry.filename || filenameFromPath(entry.path))
    const shareTokenParam = filesState.getIsShared() ? `&shareToken=${filesState.meta.shareToken}` : ``
    const cors = $derived(contentCrossOrigin())
    const imageSrc = $derived(`${joinContentUrl(`/api/v1/file/image-thumbnail`)}?size=${size}&path=${encodeUrlFilePath(entry.path)}&modified=${entry.modifiedDate}${shareTokenParam}`)
    const videoSrc = $derived(`${joinContentUrl(`/api/v1/file/video-preview`)}?size=${size}&path=${encodeUrlFilePath(entry.path)}&modified=${entry.modifiedDate}${shareTokenParam}`)

    let imageLoadFailed = $state(false)

    // Video duration from contentMeta, once the visible-row batch has fetched it.
    let durationMs = $derived(entry.contentMeta?.durationMs)
    let durationLabel = $derived(
        durationMs != null ? formatMediaDuration(durationMs / 1000) : null
    )

    const isMedia = format === "image" || format === "video"
    const src = $derived(format === "video" ? videoSrc : imageSrc)

    // A cached thumbnail of another size, shown until the requested one has loaded.
    let fallbackSrc: string | null = $state(null)
    let fallbackLoaded = $state(false)
    let loaded = $state(false)

    $effect(() => {
        if (!isMedia) return
        let stale = false
        loaded = false
        fallbackLoaded = false
        fallbackSrc = null
        findCachedThumbFallback(src).then((found) => {
            if (!stale) fallbackSrc = found
        })
        return () => { stale = true }
    })

    function onImageError() {
        imageLoadFailed = true
    }
</script>

{#key size}
    {#if isMedia && !imageLoadFailed}
        <div class="relative flex h-full w-full items-center justify-center">
            {#if fallbackSrc && !loaded}
                <img
                    on:load={() => fallbackLoaded = true}
                    on:error={() => fallbackSrc = null}
                    use:loadFilePreview={fallbackQueueKey(entry.path)}
                    alt=""
                    crossorigin={cors}
                    data-src={fallbackSrc}
                    class="absolute inset-0 h-full w-full object-contain"
                    class:opacity-0={!fallbackLoaded}
                >
            {/if}
            <img
                on:load={() => loaded = true}
                on:error={onImageError}
                use:loadFilePreview={entry.path}
                alt=""
                crossorigin={cors}
                data-src={src}
                class="relative h-full w-full object-contain"
                class:opacity-0={!loaded}
            >
            {#if format === "video" && isLarge}
                <div class="absolute pointer-events-none text-white size-6 opacity-60">
                    <PlayIcon class="[filter:drop-shadow(0_0_1px_rgba(0,0,0,1))_drop-shadow(0_0_3px_rgba(0,0,0,0.6))]"></PlayIcon>
                </div>
            {/if}
            {#if format === "video" && durationLabel}
                <div class="absolute bottom-0.5 right-0.5 pointer-events-none rounded-sm bg-black/70 px-1 py-px text-[0.65rem] leading-none text-white">
                    {durationLabel}
                </div>
            {/if}
        </div>
    {:else}
        <div class="w-full h-full flex items-center justify-center p-(--icon-padding)">
            <div class="w-full h-full flex items-center justify-center" style="max-width: var(--icon-max, none); max-height: var(--icon-max, none)">
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
        </div>
    {/if}
{/key}
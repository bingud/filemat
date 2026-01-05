<script lang="ts">
    import { isFileCategory, isTextFileCategory, type FileCategory } from "$lib/code/data/files";
    import { getBlobContent } from "$lib/code/module/files";
    import { explicitEffect } from "$lib/code/util/codeUtil.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { loadFileContent } from "../../_code/fileUtilities";
    import { onDestroy, onMount } from "svelte";
    import CodeChunk from "$lib/component/CodeChunk.svelte";
    import videojs from 'video.js'
    import 'video.js/dist/video-js.min.css'
    import type Player from "video.js/dist/types/player";
    import mime from 'mime'
    import { textFileViewerState } from "../../_code/textFileViewerState.svelte";
    import ChevronLeftIcon from "$lib/component/icons/ChevronLeftIcon.svelte";
    import ChevronRightIcon from "$lib/component/icons/ChevronRightIcon.svelte";
    import { selectSiblingFile } from "../../_code/fileBrowserUtil";
    import OpenFileAsCategoryButton from "../button/OpenFileAsCategoryButton.svelte";
    
    let meta = $derived(filesState.data.fileMeta) 
    let fileCategory = $derived(filesState.currentFile.displayedFileCategory)

    let isViewableFile = $derived(isFileCategory(fileCategory))
    let isText = $derived(isTextFileCategory(fileCategory))

    let videoElement: HTMLVideoElement | undefined = $state(undefined)
    let player: Player | undefined = $state(undefined)

    onMount(() => {
        if (isViewableFile && fileCategory) {
            openAsFileType(fileCategory)
        }
    })

    onDestroy(() => {
        textFileViewerState.reset()
        if (player) {
            player.dispose()
        }
    })

    $effect(() => {
        if (filesState.data.decodedContent != null && filesState.data.contentFilePath === filesState.path) return
        if (filesState.data.content == null) return
        if (!isViewableFile || !fileCategory) return

        const blobPath = filesState.data.contentFilePath
        getBlobContent(filesState.data.content, fileCategory).then((result) => {
            if (blobPath !== filesState.path) return

            filesState.data.decodedContent = result
            if (filesState.data.isFileSymlink) {
                openAsFileType("text")
            }
        })
    })

    // Re-create text editor
    explicitEffect(() => [ 
        uiState.isDark,
        filesState.data.decodedContent,
        textFileViewerState.textEditorContainer,
        isText
    ], () => {
        if (isText) {
            textFileViewerState.createTextEditor()
        }
    })

    // Create the video player
    $effect(() => {
        if (meta && videoElement && !player) {
            player = videojs(videoElement, {
                controls: true,
                fluid: false,
                fill: true,
                persistVolume: true,
                sources: [{
                    src: filesState.data.contentUrl,
                    type: mime.getType(meta.filename!) || "video/mp4"
                }]
            })

            // Set saved volume
            player.ready(() => {
                if (!player) return
                const savedVolume = localStorage.getItem("videojs-volume")
                if (savedVolume != null) {
                    const int = parseFloat(savedVolume)
                    player.volume(int)
                }
            })

            // Save volume when changed
            player.on('volumechange', () => {
                if (!player) return
                localStorage.setItem("videojs-volume", player!.volume()!.toString())
            })
        }
    })

    /// Arrows hovering
    let leftLongHovering: boolean = $state(false)
    let rightLongHovering: boolean = $state(false)
    let leftHovering: boolean = $state(false)
    let rightHovering: boolean = $state(false)
    let leftTimeout: ReturnType<typeof setTimeout>
    let rightTimeout: ReturnType<typeof setTimeout>

    function handleLeftHover(hovering: boolean): void {
        leftHovering = hovering
        if (hovering) {
            leftTimeout = setTimeout(() => {
                leftLongHovering = true
            }, 500)
        } else {
            clearTimeout(leftTimeout)
            leftLongHovering = false
        }
    }

    function handleRightHover(hovering: boolean): void {
        rightHovering = hovering
        if (hovering) {
            rightTimeout = setTimeout(() => {
                rightLongHovering = true
            }, 500)
        } else {
            clearTimeout(rightTimeout)
            rightLongHovering = false
        }
    }
    ///

    explicitEffect(() => [ fileCategory ], () => {
        if (!fileCategory) return
        openAsFileType(fileCategory)
    })

    async function openAsFileType(type: FileCategory) {
        // Do not manually download file if not text
        if (!isTextFileCategory(type)) return

        if (filesState.data.content == null || filesState.data.contentFilePath !== filesState.path) {
            if (filesState.contentLoading) return

            if (isText) {
                downloadContent()
            }
        }
    }

    async function downloadContent() {
        filesState.contentLoading = true
        await loadFileContent(filesState.path)
        filesState.contentLoading = false
    }

</script>


<div class="relative size-full flex flex-col bg-bg overflow-y-auto overflow-x-hidden custom-scrollbar lg:gutter-stable-both">
    <!-- Left tap zone -->
    <div 
        class="absolute left-0 top-0 w-12 h-full z-10 transition-opacity flex items-center {leftHovering ? 'opacity-100' : 'opacity-0'}"
        on:mouseenter={() => handleLeftHover(true)}
        on:mouseleave={() => handleLeftHover(false)}
    >
        {#if leftHovering && !filesState.currentFile.isEditable || leftLongHovering}
            <button 
                on:click={() => { selectSiblingFile('previous', true, true) }} 
                class="flex items-center justify-center w-full h-2/3 bg-surface rounded-lg cursor-pointer"
            >
                <div class="w-full p-3 opacity-50">
                    <ChevronLeftIcon />
                </div>
            </button>
        {/if}
    </div>

    <!-- Right tap zone -->
    <div 
        class="absolute right-0 top-0 w-12 h-full z-10 transition-opacity flex items-center justify-end {rightHovering ? 'opacity-100' : 'opacity-0'}"
        on:mouseenter={() => handleRightHover(true)}
        on:mouseleave={() => handleRightHover(false)}
    >
        {#if rightHovering && !filesState.currentFile.isEditable || rightLongHovering}
            <button 
                on:click={() => { selectSiblingFile('next', true, true) }} 
                class="flex items-center justify-center w-full h-2/3 bg-surface rounded-lg cursor-pointer"
            >
                <div class="w-full p-3 opacity-50">
                    <ChevronRightIcon />
                </div>
            </button>
        {/if}
    </div>

    {#if filesState.contentLoading || !meta}
        <div class="center">
            <Loader></Loader>
        </div>
    {:else if isViewableFile && (!isText || filesState.data.decodedContent != null)}
        <div class="w-full flex-grow min-h-0 flex items-center justify-center">
            {#if filesState.data.isFileSymlink === false}
                {#if isText}
                    <div class="w-full h-full custom-scrollbar overflow-y-auto" 
                        on:focusin={() => { textFileViewerState.isFocused = true }} 
                        on:focusout={() => { textFileViewerState.isFocused = false }}
                        bind:this={textFileViewerState.textEditorContainer}>
                    </div>
                {:else if fileCategory === "image"}
                    <img 
                        src={filesState.data.contentUrl}
                        alt={meta.path} 
                        class="max-w-full max-h-full size-auto"
                        on:dragstart={(e) => { if (e.dataTransfer?.effectAllowed) { e.dataTransfer.dropEffect = 'link'; e.dataTransfer.setData('isFromPage', 'true') } }}
                    >
                {:else if fileCategory === "video"}
                    <div class="size-full overflow-hidden">
                        <video bind:this={videoElement} class="video-js h-full w-full">
                            <track kind="captions" srclang="en" label="No captions" />
                        </video>
                    </div>
                {:else if fileCategory === "audio"}
                    <audio src={filesState.data.contentUrl} controls></audio>
                {:else if fileCategory === "pdf"}
                    <iframe src={filesState.data.contentUrl} title={meta.path} class="w-full h-full"></iframe>
                {/if}
            {:else}
                <div>
                    <div class="flex flex-col items-center justify-center gap-4">
                        <p class="text-neutral-700 dark:text-neutral-300">This is a symbolic link.</p>
                        <p class="text-neutral-500 dark:text-neutral-400 max-w-full break-all text-center">Target path:<br><CodeChunk>{filesState.data.decodedContent}</CodeChunk></p>
                    </div>
                </div>
            {/if}
        </div>
    {:else}
        <div class="flex flex-col items-center justify-center gap-4 w-full flex-grow">
            <p class="">This file type doesn't have a preview.</p>
            <div class="flex items-center gap-4">
                <a download href={filesState.data.contentUrl} target="_blank" class="basic-button">Download</a>
                <OpenFileAsCategoryButton location="file-viewer" />
            </div>
        </div>
    {/if}
</div>
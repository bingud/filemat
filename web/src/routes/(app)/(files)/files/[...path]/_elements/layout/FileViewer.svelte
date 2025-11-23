<script lang="ts">
    import { getFileCategoryFromFilename, isFileCategory, isTextFileCategory, type FileCategory } from "$lib/code/data/files";
    import { getBlobContent } from "$lib/code/module/files";
    import { explicitEffect } from "$lib/code/util/codeUtil.svelte";
    import {basicSetup} from "codemirror"
    import {EditorView} from "@codemirror/view"
    import { barf, ayuLight } from 'thememirror';
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import ChevronDownIcon from "$lib/component/icons/ChevronDownIcon.svelte";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { loadFileContent } from "../../_code/fileUtilities";
    import { onDestroy, onMount } from "svelte";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import CodeChunk from "$lib/component/CodeChunk.svelte";
    import videojs from 'video.js'
    import 'video.js/dist/video-js.min.css'
    import type Player from "video.js/dist/types/player";
    import mime from 'mime'
    import { textFileViewerState } from "../../_code/textFileViewerState.svelte";
    import { auth } from "$lib/code/stateObjects/authState.svelte";

    
    let meta = $derived(filesState.data.fileMeta) 

    const isSymlink = $derived(meta?.fileType.includes("LINK") && !appState.followSymlinks)
    const fileCategory: FileCategory | null = $derived.by(() => {
        if (!meta) return null
        if (isSymlink) return "text" as FileCategory
        return getFileCategoryFromFilename(meta.filename!)
    })
    let isEditable = $derived(fileCategory === "text" && auth.authenticated)

    let displayedFileCategory = $derived(fileCategory)
    let isViewableFile = $derived(isFileCategory(displayedFileCategory))
    let isText = $derived(isTextFileCategory(displayedFileCategory))

    let videoElement: HTMLVideoElement | undefined = $state(undefined)
    let player: Player | undefined = $state(undefined)

    onMount(() => {
        if (isViewableFile && displayedFileCategory) {
            openAsFileType(displayedFileCategory)
        }

        return () => {
            if (player) {
                player.dispose()
            }
        }
    })

    onDestroy(() => {
        textFileViewerState.reset()
    })

    $inspect(fileCategory)

    $effect(() => {
        if (filesState.data.decodedContent != null) return
        if (filesState.data.content == null) return
        if (!isViewableFile || !displayedFileCategory) return

        getBlobContent(filesState.data.content, displayedFileCategory).then((result) => {
            filesState.data.decodedContent = result
            if (isSymlink) {
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
            createTextEditor()
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

    function createTextEditor() {
        textFileViewerState.destroyEditor()

        if (filesState.data.decodedContent == null || !textFileViewerState.textEditorContainer) return

        textFileViewerState.filePath = filesState.data.fileMeta!.path

        const theme = uiState.isDark ? barf : ayuLight
        textFileViewerState.textEditor = new EditorView({
            doc: filesState.data.decodedContent,
            parent: textFileViewerState.textEditorContainer,
            extensions: [
                basicSetup, theme,
                EditorView.updateListener.of((update) => {
                    if (update.docChanged && isEditable) {
                        textFileViewerState.isFileSavable = true
                    }
                })
            ]
        })
    }

    async function openAsFileType(type: FileCategory) {
        displayedFileCategory = type
        // Do not manually download file if not text
        if (!isTextFileCategory(type)) return

        if (filesState.data.content == null) {
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


<div on:click|stopPropagation class="size-full flex flex-col bg-bg overflow-y-auto overflow-x-hidden custom-scrollbar lg:gutter-stable-both">
    {#if filesState.contentLoading || !meta}
        <div class="center">
            <Loader></Loader>
        </div>
    {:else if isViewableFile && (!isText || filesState.data.decodedContent != null)}
        {@const type = displayedFileCategory}

        <!-- Show "Open As" button if displayed file type doesnt match filename extension -->
        {#if displayedFileCategory !== fileCategory}
            <div class="w-full h-fit p-2 shrink-0 flex justify-end">
                {@render openAsButton()}
            </div>
        {/if}
        
        <div class="w-full flex-grow min-h-0 flex items-center justify-center">
            {#if isSymlink === false}
                {#if isText}
                    <div class="w-full h-full custom-scrollbar overflow-y-auto" 
                        on:focusin={() => { textFileViewerState.isFocused = true }} 
                        on:focusout={() => { textFileViewerState.isFocused = false }}
                        bind:this={textFileViewerState.textEditorContainer}>
                    </div>
                {:else if type === "image"}
                    <img 
                        src={filesState.data.contentUrl}
                        alt={meta.path} 
                        class="max-w-full max-h-full size-auto"
                        on:dragstart={(e) => { if (e.dataTransfer?.effectAllowed) { e.dataTransfer.dropEffect = 'link'; e.dataTransfer.setData('isFromPage', 'true') } }}
                    >
                {:else if type === "video"}
                    <div class="size-full overflow-hidden">
                        <video bind:this={videoElement} class="video-js h-full w-full">
                            <track kind="captions" srclang="en" label="No captions" />
                        </video>
                    </div>
                {:else if type === "audio"}
                    <audio src={filesState.data.contentUrl} controls></audio>
                {:else if type === "pdf"}
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
                {@render openAsButton()}
            </div>
        </div>
    {/if}
</div>


{#snippet openAsButton()}
    <div class="size-fit z-10">
        <Popover.Root>
            <Popover.Trigger>
                <button class="basic-button flex items-center gap-2">
                    <p>Open as</p>
                    <span class="h-6 py-1 block"><ChevronDownIcon /></span>
                </button>
            </Popover.Trigger>
            <Popover.Content align="start" sideOffset={8}>
                <div class="rounded-lg bg-neutral-250 dark:bg-neutral-800 py-2 flex flex-col w-[10rem]">
                    <button on:click={() => { openAsFileType("text") }} class="w-full text-start px-4 py-1 hover:bg-neutral-300 dark:hover:bg-neutral-700">Text</button>
                    <button on:click={() => { openAsFileType("image") }} class="w-full text-start px-4 py-1 hover:bg-neutral-300 dark:hover:bg-neutral-700">Image</button>
                    <button on:click={() => { openAsFileType("video") }} class="w-full text-start px-4 py-1 hover:bg-neutral-300 dark:hover:bg-neutral-700">Video</button>
                    <button on:click={() => { openAsFileType("audio") }} class="w-full text-start px-4 py-1 hover:bg-neutral-300 dark:hover:bg-neutral-700">Audio</button>
                </div>
            </Popover.Content>
        </Popover.Root>
    </div>
{/snippet}
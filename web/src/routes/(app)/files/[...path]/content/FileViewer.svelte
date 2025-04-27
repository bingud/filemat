<script lang="ts">
    import { fileCategories, isFileCategory, isTextFileCategory, type FileCategory } from "$lib/code/data/files";
    import { getBlobContent } from "$lib/code/module/files";
    import { getFileExtension, isServerDown } from "$lib/code/util/codeUtil.svelte";
    import {basicSetup} from "codemirror"
    import {EditorView} from "@codemirror/view"
    import { barf, ayuLight } from 'thememirror';
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import ChevronDownIcon from "$lib/component/icons/ChevronDownIcon.svelte";
    import { filesState } from "../../../../../lib/code/stateObjects/filesState.svelte";
    import { loadFileContent } from "./code/files";
    import { onMount } from "svelte";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import CodeChunk from "$lib/component/CodeChunk.svelte";

    const extension = $derived(filesState.data.meta ? getFileExtension(filesState.data.meta.path) : null)
    const isSymlink = $derived(filesState.data.meta?.fileType.includes("LINK") && !appState.followSymlinks)
    const fileType = $derived.by(() => {
        if (isSymlink) return "text"
        return extension ? fileCategories[extension] : null
    })

    let displayedFileCategory = $derived(fileType)
    let isViewableFile = $derived(isFileCategory(displayedFileCategory))
    let isText = $derived(isTextFileCategory(displayedFileCategory))

    let textEditorContainer: HTMLElement | undefined = $state()
    let textEditor: EditorView | undefined

    onMount(() => {
        if (isViewableFile && displayedFileCategory) {
            openAsFileType(displayedFileCategory)
        }
    })

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
    $effect(() => {
        uiState.isDark
        filesState.data.decodedContent
        textEditorContainer
        
        if (isText) {
            createTextEditor()
        }
    })

    function createTextEditor() {
        if (textEditor) {
            try { textEditor.destroy() } catch (e) {}
        }

        if (filesState.data.decodedContent == null || !textEditorContainer) return

        const theme = uiState.isDark ? barf : ayuLight
        textEditor = new EditorView({
            doc: filesState.data.decodedContent,
            parent: textEditorContainer,
            extensions: [basicSetup, theme]
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


<div on:click|stopPropagation class="size-full flex flex-col">
    
    {#if filesState.data.meta}
        {#if filesState.contentLoading}
            <div class="center">
                <Loader></Loader>
            </div>
        {:else if isViewableFile && (!isText || filesState.data.decodedContent != null)}
            {@const type = displayedFileCategory}

            <!-- Show "Open As" button if displayed file type doesnt match filename extension -->
            {#if displayedFileCategory !== fileType}
                <div class="w-full h-fit p-2 shrink-0 flex justify-end">
                    {@render openAsButton()}
                </div>
            {/if}
            
            <div class="w-full flex-grow flex items-center justify-center">
                {#if isSymlink === false}
                    {#if isText}
                        <div class="w-full h-full custom-scrollbar" bind:this={textEditorContainer}></div>
                    {:else if type === "image"}
                        <img src={filesState.data.contentUrl} alt={filesState.data.meta.path} class="max-w-full max-h-full size-auto">
                    {:else if type === "video"}
                        <video controls>
                            <source src={filesState.data.contentUrl}>
                            <track kind="captions" srclang="en" label="No captions" />
                        </video>
                    {:else if type === "audio"}
                        <audio src={filesState.data.contentUrl} controls></audio>
                    {:else if type === "pdf"}
                        <iframe src={filesState.data.contentUrl} title={filesState.data.meta.path} class="w-full h-full"></iframe>
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
    {:else if !filesState.data.meta}
        <div class="center">
            <p class="">No file is open.</p>
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
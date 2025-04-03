<script lang="ts">
    import { fileCategories, isFileCategory, type FileCategory } from "$lib/code/data/files";
    import { getBlobContent } from "$lib/code/module/files";
    import { getFileExtension, isServerDown } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte";
    import {basicSetup} from "codemirror"
    import {EditorView} from "@codemirror/view"
    import { barf, ayuLight } from 'thememirror';
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import ChevronDownIcon from "$lib/component/icons/ChevronDownIcon.svelte";


    let { filename, blob }: { 
        filename: string,
        blob: Blob,
    } = $props()

    const extension = getFileExtension(filename)
    const fileType = fileCategories[extension]
    let displayedFileCategory = $state(fileType)
    let content: any | null = $state(null)

    let textEditorContainer: HTMLElement | undefined = $state()

    onMount(async () => {
        content = await getBlobContent(blob, fileType)
    })

    let textEditor: EditorView | undefined
    function createTextEditor() {
        if (textEditor) {
            try { textEditor.destroy() } catch (e) {}
        }

        if (content == null || !textEditorContainer) return

        const theme = uiState.isDark ? barf : ayuLight
        textEditor = new EditorView({
            doc: content,
            parent: textEditorContainer,
            extensions: [basicSetup, theme]
        })
    }

    // Re-create text editor
    $effect(() => {
        uiState.isDark
        content
        textEditorContainer
        
        createTextEditor()
    })

    async function openAsFileType(type: FileCategory) {
        displayedFileCategory = type
        content = await getBlobContent(blob, displayedFileCategory)
    }

</script>


<div class="size-full flex flex-col">
    
    {#if content == null && displayedFileCategory != null}
        <Loader></Loader>
    {:else if isFileCategory(displayedFileCategory)}
        {@const type = displayedFileCategory}

        <!-- Show "Open As" button if displayed file type doesnt match filename extension -->
        {#if displayedFileCategory !== fileType}
            <div class="w-full h-fit p-2 shrink-0 flex justify-end">
                {@render openAsButton()}
            </div>
        {/if}
        
        <div class="w-full flex-grow flex items-center justify-center">
            {#if type === "text" || type === "md" || type === "html"}
                <div class="w-full h-full custom-scrollbar" bind:this={textEditorContainer}></div>
            {:else if type === "image"}
                <img src={content} alt={filename} class="w-full h-auto">
            {:else if type === "video"}
                <video controls>
                    <source src={content}>
                    <track kind="captions" srclang="en" label="No captions" />
                </video>
            {:else if type === "audio"}
                <audio src={content} controls></audio>
            {:else if type === "pdf"}
                <iframe src={content} title={filename} class="w-full h-auto max-h-full"></iframe>
            {/if}
        </div>
    {:else}
        <div class="flex flex-col items-center justify-center gap-4 w-full flex-grow">
            <p class="">This file type doesn't have a preview.</p>
            <div class="flex items-center gap-4">
                <button class="basic-button">Download</button>
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
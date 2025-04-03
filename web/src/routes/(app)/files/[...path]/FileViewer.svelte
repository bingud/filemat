<script lang="ts">
    import { fileCategories } from "$lib/code/data/files";
    import { getBlobContent } from "$lib/code/module/files";
    import { getFileExtension, isServerDown } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte";
    
    import {basicSetup} from "codemirror"
    import {EditorView} from "@codemirror/view"
    import { barf, ayuLight } from 'thememirror';
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import * as Popover from "$lib/components/ui/popover";
    import Loader from "$lib/component/Loader.svelte";


    let { filename, blob }: { 
        filename: string,
        blob: Blob,
    } = $props()

    const extension = getFileExtension(filename)
    const fileCategory = fileCategories[extension]
    let displayedFileCategory = $state(fileCategory)
    let content: any | null = $state(null)

    let textEditor: HTMLElement | undefined = $state()

    onMount(async () => {
        content = await getBlobContent(blob, fileCategory)
        if (fileCategory === "html" || fileCategory === "md" || fileCategory === "text") {
            
        }
    })

    function createTextEditor() {
        const theme = uiState.isDark ? barf : ayuLight
        new EditorView({
            doc: content,
            parent: textEditor,
            extensions: [basicSetup, theme]
        })
    }

</script>


<div class="size-full flex flex-col items-center justify-center">
    
    {#if content == null && fileCategory != null}
        <Loader/>
    {:else}
        {@const type = displayedFileCategory}
    
        {#if type === "text" || type === "md" || type === "html"}
            {#key uiState.isDark}
                <div class="size-full custom-scrollbar" bind:this={textEditor}></div>
                {createTextEditor()}
            {/key}
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
        {:else if !type}
            <div class="flex flex-col items-center justify-center gap-4">
                <p class="">This file type doesn't have a preview.</p>
                <div class="flex items-center gap-4">
                    <button class="px-3 py-2 rounded bg-neutral-800 hover:bg-neutral-700">Open as</button>
                    <button class="px-3 py-2 rounded bg-neutral-800 hover:bg-neutral-700">Download</button>
                </div>
            </div>
        {/if}
    {/if}
</div>
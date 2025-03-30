<script lang="ts">
    import { page } from "$app/state"
    import { getFileData, type FileData } from "$lib/code/module/files";
    import { filenameFromPath, isBlank, pageTitle } from "$lib/code/util/codeUtil.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { onMount } from "svelte"

    const path = $derived.by(() => {
        const param = page.params.path
        if (isBlank(param)) return "/"
        return param
    })
    const urlPath = $derived(page.url.pathname)
    const segments = $derived(path.split("/"))
    const title = $derived(pageTitle(segments[segments.length - 1]))

    let loading = $state(true)
    let data = $state(null) as FileData | null

    onMount(() => {  })

    $effect(() => {
        if (path) {
            console.log(path)
            data = null
            loadPageData(path)
        }
    })

    let loadingData: string | null = $state(null)
    async function loadPageData(filePath: string) {
        loadingData = filePath
        loading = true

        const result = await getFileData(filePath)
        if (!result) return
        if (loadingData !== filePath) return
        
        data = result
        loading = false
    }

</script>


<svelte:head>
    <title>{title}</title>
</svelte:head>


<div class="page">
    {#if !loading && data}
        <div class="w-full flex flex-col h-full overflow-y-auto">
            <div class="w-full h-[3rem] flex px-6 items-center justify-evenly">
                <p>{data.meta.filename}</p>
                <p>{data.meta.fileType}</p>
                <p>{data.meta.size} bytes</p>
            </div>

            {#if data.meta.fileType === "FOLDER" && data.entries}
                <div class="w-full flex flex-col h-[calc(100%-3rem)]">
                    {#each data.entries as entry}
                        <a href="{urlPath}/{filenameFromPath(entry.filename)}" class="w-full h-[2rem] flex justify-between items-center px-4">
                            <div>{entry.filename}</div>

                            <div>
                                <p>{entry.fileType} - {entry.size}b</p>
                            </div>
                        </a>
                    {/each}
                </div>
            {/if}
        </div>
    {:else}
        <div class="center">
            <Loader></Loader>
        </div>
    {/if}
</div>
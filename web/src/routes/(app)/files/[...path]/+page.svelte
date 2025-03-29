<script lang="ts">
    import { page } from "$app/state"
    import { getFileData, type FileData } from "$lib/code/module/files";
    import { pageTitle } from "$lib/code/util/codeUtil.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { onMount } from "svelte"

    const path = $derived(page.params.path)
    const segments = $derived(page.params.path.split("/"))
    const title = $derived(pageTitle(segments[segments.length - 1]))

    let loading = $state(true)
    let data = $state(null) as FileData | null

    onMount(async () => {
        const fileData = await getFileData(path)
        if (fileData) {
            data = fileData
            loading = false
        }
    })

    $effect(() => {

    })

</script>


<svelte:head>
    <title>{title}</title>
</svelte:head>


<div class="">
    {#if !loading && data}
        {#if data}
            {JSON.stringify(data)}
        {/if}
    {:else}
        <div class="center">
            <Loader></Loader>
        </div>
    {/if}
</div>
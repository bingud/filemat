<script lang="ts">
    import { page } from "$app/state"
    import { pageTitle } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte"

    const path = $derived(`/files/${page.params.path}`)
    const segments = $derived(page.params.path.split("/"))
    const title = $derived(pageTitle(segments[segments.length - 1]))

    onMount(() => {
        
    })

    $effect(() => {
    })

</script>


<svelte:head>
    <title>{title}</title>
</svelte:head>


<p>{page.params.path}</p>
<a href="/files/{page.params.path}/bobong">Next pag</a>
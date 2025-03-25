<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { pageTitle } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte";

    const title = "Create new user"

    onMount(() => {
        uiState.settings.title = "Create new user"
    })

</script>


<svelte:head>
    <title>{pageTitle(title)}</title>
</svelte:head>


<div class="page">

</div>


<style>
    @import "/src/app.css" reference;

</style>
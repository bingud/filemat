<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { pageTitle } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte";

    const title = "Preferences"

    onMount(() => {
        uiState.settings.title = title
    })
</script>


<svelte:head>
    <title>{pageTitle("Preferences")}</title>
</svelte:head>


<div class="page ">

</div>
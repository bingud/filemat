<script lang="ts">
    import { goto } from "$app/navigation";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import { onMount } from "svelte";

    onMount(() => {
        const def = appState.settings.defaultPagePath
        goto(def)
    })

</script>
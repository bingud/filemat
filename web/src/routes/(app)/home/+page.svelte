<script lang="ts">
    import { goto } from "$app/navigation";
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { onMount } from "svelte";

    onMount(() => {
        const path = auth.principal?.homeFolderPath ?? ""
        goto(`/files${path}`)
    })
</script>
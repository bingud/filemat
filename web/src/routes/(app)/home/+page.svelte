<script lang="ts">
    import { goto } from "$app/navigation";
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { filePageUrl } from "$lib/code/util/codeUtil.svelte"
    import { onMount } from "svelte";

    onMount(() => {
        const path = auth.principal?.homeFolderPath ?? ""
        goto(filePageUrl(path))
    })
</script>